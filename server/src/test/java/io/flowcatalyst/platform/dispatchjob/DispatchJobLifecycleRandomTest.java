package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;

/// Random concurrent lifecycle operations against a real database: six workers, 3,000 operations an iteration,
/// every transition, claimed-version and stale-version mark-QUEUED included. What must hold whatever the
/// interleaving:
///
///  - no operation fails (a deadlock victim, which Postgres chooses between multi-row statements that lock jobs
///    in different orders, is the one tolerated outcome: its statement rolled back whole);
///  - every job ends in a status the schema admits;
///  - a COMPLETED job never leaves COMPLETED (only an operator requeue may, and set A is never requeued);
///  - a mark-QUEUED carrying a STALE version never marks a job (the optimistic check), whatever the status.
///
/// Iterations: `-Ddq.random.iterations` (default 60, about 35 s — this test only finds a rare interleaving by
/// being run many times).
class DispatchJobLifecycleRandomTest {

    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DS);
    private static final Set<String> LEGAL = Set.of("PENDING", "QUEUED", "PROCESSING", "IN_PROGRESS", "COMPLETED",
            "FAILED", "ERROR", "CANCELLED", "EXPIRED");

    @Test
    void randomConcurrentLifecycleOperationsKeepEveryInvariant() throws Exception {
        int iterations = Integer.getInteger("dq.random.iterations", 60);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < iterations; i++) failures.addAll(oneRun(i));
        System.out.println("DispatchJobLifecycleRandomTest: iterations=" + iterations + " failures=" + failures.size());
        assertThat(failures).as("over %d iterations", iterations).isEmpty();
    }

    private static List<String> oneRun(int iteration) throws Exception {
        int jobsN = 40;
        int workers = 6;
        int opsPerWorker = 500;
        List<String> ids = new ArrayList<>();
        List<Instant> created = new ArrayList<>();
        String[] statuses = {"PENDING", "PENDING", "QUEUED", "PROCESSING", "FAILED", "COMPLETED"};
        Random seedRandom = new Random(42 + iteration);
        for (int i = 0; i < jobsN; i++) {
            String group = i % 3 == 0 ? null : "rnd-" + RUN + "-g" + seedRandom.nextInt(10);
            String id = seedWriteRow(Seed.of(code("rnd")).withStatus(statuses[seedRandom.nextInt(statuses.length)])
                    .withMode("BLOCK_ON_ERROR").withUpdatedAt(Instant.now().minusSeconds(3600))
                    .withMessageGroup(group).withSequence(i));
            ids.add(id);
            created.add(DB.fetchOne("SELECT created_at FROM msg_dispatch_jobs WHERE id = ?", id).get(0, java.time.OffsetDateTime.class).toInstant());
        }
        // set A (even indexes) is never requeued; the jobs it STARTS completed must stay completed
        Set<String> completedA = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int k = 0; k < jobsN; k += 2) {
            if ("COMPLETED".equals(status(ids.get(k)))) completedA.add(ids.get(k));
        }
        var cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setDataSource(DS);
        cfg.setMaximumPoolSize(8);
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var pds = new com.zaxxer.hikari.HikariDataSource(cfg)) {
            var life = new DispatchJobLifecycle(pds);
            var repo = new DispatchJobRepository(pds);
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            List<Future<Integer>> results = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                long seed = 1000L * (iteration + 1) + w;
                results.add(pool.submit((Callable<Integer>) () -> {
                    Random r = new Random(seed);
                    int done = 0;
                    for (int n = 0; n < opsPerWorker; n++) {
                        int k = r.nextInt(jobsN);
                        String id = ids.get(k);
                        Instant at = created.get(k);
                        boolean setA = k % 2 == 0;
                        try {
                            switch (r.nextInt(11)) {
                                case 0 -> life.claimForDelivery(id, at);
                                case 1 -> life.markCompleted(id, at, Instant.now(), 3L);
                                case 2 -> life.markFailed(id, at, "boom");
                                case 3 -> life.scheduleRetry(id, at, Instant.now().plusSeconds(r.nextInt(3) * 30), 1, "retry");
                                case 4 -> life.reschedule(id, at, r.nextBoolean() ? null : Instant.now().plusSeconds(30));
                                case 5 -> life.settleAcked(List.of(id, ids.get(r.nextInt(jobsN)), ids.get(r.nextInt(jobsN))), "settled");
                                case 6 -> life.sweepStrandedSiblings(Instant.now().plusSeconds(60), "reaper");
                                case 7, 8 -> {
                                    var j = repo.findById(id);
                                    if (j.isPresent()) life.markQueued(List.of(new ClaimRow(id, null, null, null, null, null, at, 0, null, j.get().updatedAt())));
                                }
                                case 9 -> {
                                    if (!setA) requeue(pds, id, at);
                                }
                                default -> {
                                    int marked = life.markQueued(List.of(new ClaimRow(id, null, null, null, null, null, at, 0, null,
                                            Instant.now().minusSeconds(7200))));
                                    if (marked != 0) failures.add("a stale-version mark-QUEUED marked " + id);
                                }
                            }
                        } catch (org.jooq.exception.DataAccessException e) {
                            if (!String.valueOf(e.getCause()).contains("deadlock detected")) throw e;
                        }
                        done++;
                    }
                    return done;
                }));
            }
            for (Future<Integer> f : results) f.get(180, TimeUnit.SECONDS);
            pool.shutdown();
        }
        for (int k = 0; k < jobsN; k++) {
            String st = status(ids.get(k));
            if (!LEGAL.contains(st)) failures.add("iteration " + iteration + ": " + ids.get(k) + " is " + st);
        }
        for (String id : completedA) {
            if (!"COMPLETED".equals(status(id))) failures.add("iteration " + iteration + ": " + id + " left COMPLETED");
        }
        return failures;
    }

    private static String status(String id) {
        return DB.fetchOne("SELECT status FROM msg_dispatch_jobs WHERE id = ?", id).get(0, String.class);
    }

    private static void requeue(javax.sql.DataSource pds, String id, Instant at) {
        try (var conn = pds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                DispatchJobLifecycle.requeue(conn, id, at);
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
