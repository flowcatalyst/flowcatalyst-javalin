package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle.FanOutJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle.QueueDrift;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.assertQueueMirrorsJob;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.queueRow;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedQueued;
import static org.assertj.core.api.Assertions.assertThat;

/// `msg_dispatch_queue` (dispatch-queue spec, step 2): the lifecycle keeps it
/// exactly one row per `PENDING` job. [DispatchJobLifecycleTest] pins the
/// invariant after every (transition, from-status) pair; this class pins the
/// rest: creation (single, batch, fan-out, duplicates), bulk transitions,
/// mark-`QUEUED`'s stale-row rule, the migration's backfill, the drift check,
/// the documented READ COMMITTED race (and the direction that cannot lose a
/// job), and a randomized concurrent run. Its own class, so its own database:
/// the table-wide [DispatchJobLifecycle#queueDrift()] is meaningful here.
class DispatchQueueTest {

    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DS);
    private static final DispatchJobRepository REPO = new DispatchJobRepository(DS);

    // ── helpers ────────────────────────────────────────────────────────────

    private static String seedJob(String status, String group, int sequence) {
        Seed seed = Seed.of(code("dq")).withStatus(status).withMode("BLOCK_ON_ERROR")
                .withUpdatedAt(Instant.now().minusSeconds(3600));
        if (group != null) seed = seed.withMessageGroup(group).withSequence(sequence);
        return seedQueued(seed);
    }

    private static Instant createdAt(String id) {
        OffsetDateTime at = DB.select(MSG_DISPATCH_JOBS.CREATED_AT).from(MSG_DISPATCH_JOBS)
                .where(MSG_DISPATCH_JOBS.ID.eq(id)).fetchOne(MSG_DISPATCH_JOBS.CREATED_AT);
        return at.toInstant();
    }

    /// A new job built from `template`, with its own id.
    private static DispatchJob copyOf(DispatchJob t, String id, String group, int sequence) {
        return new DispatchJob(id, t.externalId(), t.kind(), code("dqnew"), t.source(), t.subject(), t.targetUrl(),
                t.protocol(), t.payload(), t.payloadContentType(), t.dataOnly(), t.eventId(), t.correlationId(),
                t.clientId(), t.subscriptionId(), t.serviceAccountId(), t.dispatchPoolId(), group, t.mode(), sequence,
                t.timeoutSeconds(), t.schemaId(), t.maxRetries(), t.retryStrategy(), DispatchJobStatus.PENDING,
                t.attemptCount(), t.lastError(), t.metadata(), t.idempotencyKey(), t.descriptor(), t.queue(),
                Instant.now(), Instant.now(), null, null, null, null, null);
    }

    private static DispatchJob template() {
        return REPO.findById(seedJob("PENDING", null, 1)).orElseThrow();
    }

    private static void assertClean(List<String> ids, String label) {
        for (String id : ids) assertQueueMirrorsJob(id, label + " " + id);
        assertThat(LIFECYCLE.queueDrift(ids)).as(label + ": drift over these jobs").isEqualTo(new QueueDrift(0, 0));
    }

    private static void inTx(java.util.function.Consumer<Connection> work) {
        try (Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.accept(conn);
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── create ─────────────────────────────────────────────────────────────

    @Test
    void createdJobsHaveQueueRowsAndASkippedDuplicateChangesNothing() {
        DispatchJob t = template();
        DispatchJob one = copyOf(t, Tsid.generate(), "dq-" + RUN + "-g1", 3);
        LIFECYCLE.insertBatch(List.of(one));
        assertClean(List.of(one.id()), "single insert");

        // a duplicate (same id and created_at) is skipped: no queue row added, none changed
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() WHERE job_id = ?", one.id());
        Map<String, Object> before = queueRow(one.id());
        LIFECYCLE.insertBatch(List.of(one));
        assertThat(queueRow(one.id())).as("the skipped duplicate left the queue row byte-identical").isEqualTo(before);
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = NULL WHERE job_id = ?", one.id());
        assertThat(DB.fetchCount(MSG_DISPATCH_JOBS, MSG_DISPATCH_JOBS.ID.eq(one.id()))).isEqualTo(1);

        // a batch of N gives N rows
        List<DispatchJob> batch = new ArrayList<>();
        for (int i = 0; i < 25; i++) batch.add(copyOf(t, Tsid.generate(), i % 3 == 0 ? null : "dq-" + RUN + "-b" + (i % 4), i));
        LIFECYCLE.insertBatch(batch);
        List<String> ids = batch.stream().map(DispatchJob::id).toList();
        assertThat(DB.fetchCount(io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_QUEUE,
                io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_QUEUE.JOB_ID.in(ids))).isEqualTo(25);
        assertClean(ids, "batch insert");

        // a batch with one duplicate of an existing job adds only the new ones
        DispatchJob fresh = copyOf(t, Tsid.generate(), null, 1);
        LIFECYCLE.insertBatch(List.of(one, fresh));
        assertClean(List.of(one.id(), fresh.id()), "mixed batch");
    }

    @Test
    void insertNewWithSuppliedIdsHasQueueRowsAndARefusalWritesNone() {
        DispatchJob t = template();
        DispatchJob a = copyOf(t, Tsid.generate(), null, 1);
        DispatchJob b = copyOf(t, Tsid.generate(), "dq-" + RUN + "-n", 2);
        var ok = LIFECYCLE.insertNew(List.of(a, b), List.of(a.id(), b.id()));
        assertThat(ok).isInstanceOf(io.flowcatalyst.sdk.result.Result.Ok.class);
        assertClean(List.of(a.id(), b.id()), "insertNew");

        // the ids are taken now: the whole batch is refused, nothing is added
        DispatchJob c = copyOf(t, Tsid.generate(), null, 1);
        var refused = LIFECYCLE.insertNew(List.of(c, copyOf(t, a.id(), null, 1)), List.of(c.id(), a.id()));
        assertThat(refused).isInstanceOf(io.flowcatalyst.sdk.result.Result.Err.class);
        assertThat(queueRow(c.id())).as("a refused batch queued nothing").isNull();
    }

    @Test
    void fanOutJobsHaveQueueRowsAndADuplicateIsSkipped() {
        Instant now = Instant.now();
        List<FanOutJob> jobs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            jobs.add(new FanOutJob(Tsid.generate(), code("fan"), "src", "subj", null, null, null,
                    i % 2 == 0 ? "dq-" + RUN + "-f" : null, "{}", "https://hook.example/x", false, null, null, null,
                    i, 30, 3, "BLOCK_ON_ERROR", null, null, null, "[]", now));
        }
        inTx(tx -> DispatchJobLifecycle.insertFanOut(tx, jobs));
        List<String> ids = jobs.stream().map(FanOutJob::id).toList();
        assertClean(ids, "fan-out");

        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() WHERE job_id = ?", ids.get(0));
        Map<String, Object> before = queueRow(ids.get(0));
        FanOutJob extra = new FanOutJob(Tsid.generate(), code("fan"), "src", "subj", null, null, null, null, "{}",
                "https://hook.example/x", false, null, null, null, 1, 30, 3, "IMMEDIATE", null, null, null, "[]", now);
        inTx(tx -> DispatchJobLifecycle.insertFanOut(tx, List.of(jobs.get(0), extra)));
        assertThat(queueRow(ids.get(0))).as("the skipped duplicate changed nothing").isEqualTo(before);
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = NULL WHERE job_id = ?", ids.get(0));
        assertClean(List.of(ids.get(0), extra.id()), "fan-out with a duplicate");
    }

    // ── bulk transitions ───────────────────────────────────────────────────

    @Test
    void bulkTransitionsKeepEveryTouchedJobExact() {
        // settled-return of many ids
        List<String> inFlight = new ArrayList<>();
        for (int i = 0; i < 20; i++) inFlight.add(seedJob(i % 2 == 0 ? "QUEUED" : "PROCESSING", null, 1));
        String completed = seedJob("COMPLETED", null, 1);
        List<String> changed = LIFECYCLE.settleAcked(append(inFlight, completed), "settled: test");
        assertThat(changed).containsExactlyInAnyOrderElementsOf(inFlight);
        assertClean(append(inFlight, completed), "settleAcked");

        // reaper sweep: siblings behind a FAILED head, in several groups
        List<String> siblings = new ArrayList<>();
        for (int g = 0; g < 4; g++) {
            String group = "dq-" + RUN + "-sweep" + g;
            seedJob("FAILED", group, 1);
            for (int i = 0; i < 5; i++) siblings.add(seedJob(i % 2 == 0 ? "QUEUED" : "PROCESSING", group, 2 + i));
        }
        List<String> swept = LIFECYCLE.sweepStrandedSiblings(Instant.now().plusSeconds(60), "reaper: test");
        assertThat(swept).containsAll(siblings);
        assertClean(siblings, "sweep");

        // requeue of many, from any status, in one transaction
        List<String> any = new ArrayList<>();
        for (String s : List.of("PENDING", "QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED", "EXPIRED", "ERROR"))
            for (int i = 0; i < 3; i++) any.add(seedJob(s, null, 1));
        inTx(tx -> {
            for (String id : any) assertThat(DispatchJobLifecycle.requeue(tx, id, createdAt(id))).isTrue();
        });
        assertClean(any, "requeue");
    }

    private static List<String> append(List<String> l, String x) {
        var out = new ArrayList<>(l);
        out.add(x);
        return out;
    }

    // ── mark-QUEUED ────────────────────────────────────────────────────────

    private static ClaimRow claim(String id, Instant version) {
        return new ClaimRow(id, null, null, null, null, null, createdAt(id), 0, null, version);
    }

    private static Instant version(String id) {
        return REPO.findById(id).orElseThrow().updatedAt();
    }

    @Test
    void markQueuedRemovesTheMarkedRowsAndKeepsARefreshedOne() {
        String a = seedJob("PENDING", null, 1);
        String b = seedJob("PENDING", null, 1);
        // c is claimed, then re-enters PENDING (a deferral) before the mark
        String c = seedJob("PENDING", null, 1);
        ClaimRow claimedC = claim(c, version(c));
        LIFECYCLE.reschedule(c, createdAt(c), Instant.now().plusSeconds(30));
        Map<String, Object> cQueue = queueRow(c);

        int marked = LIFECYCLE.markQueued(List.of(claim(a, version(a)), claim(b, version(b)), claimedC));
        assertThat(marked).as("a and b marked; c's version moved on").isEqualTo(2);

        assertThat(queueRow(a)).as("marked: left the queue").isNull();
        assertThat(queueRow(b)).isNull();
        assertQueueMirrorsJob(a, "a");
        assertQueueMirrorsJob(b, "b");
        assertThat(queueRow(c)).as("re-entered PENDING since the claim: its refreshed row is kept").isEqualTo(cQueue);
        assertQueueMirrorsJob(c, "c");
    }

    @Test
    void markQueuedRemovesAStaleRowAtTheClaimedVersionForAJobThatMovedOn() {
        // a job that is no longer PENDING whose queue row survived (the documented race, or a bug)
        String id = seedJob("COMPLETED", null, 1);
        Instant v = version(id);
        DB.execute("INSERT INTO msg_dispatch_queue (job_id, job_created_at, message_group, sequence, scheduled_for,"
                + " subscription_id, dispatch_pool_id, client_id, mode, queue, version)"
                + " SELECT id, created_at, message_group, sequence, scheduled_for, subscription_id, dispatch_pool_id,"
                + " client_id, mode, queue, updated_at FROM msg_dispatch_jobs WHERE id = ?", id);
        assertThat(LIFECYCLE.queueDrift(List.of(id)).orphaned()).isEqualTo(1);

        int marked = LIFECYCLE.markQueued(List.of(claim(id, v)));
        assertThat(marked).as("the job itself did not match").isZero();
        assertThat(queueRow(id)).as("the stale row at the claimed version is gone").isNull();
        assertClean(List.of(id), "after the stale-row removal");

        // a stale row at ANOTHER version is not this claim's to remove
        String other = seedJob("COMPLETED", null, 1);
        DB.execute("INSERT INTO msg_dispatch_queue (job_id, job_created_at, sequence, mode, version)"
                + " SELECT id, created_at, sequence, mode, updated_at + interval '1 second' FROM msg_dispatch_jobs WHERE id = ?", other);
        LIFECYCLE.markQueued(List.of(claim(other, version(other))));
        assertThat(queueRow(other)).as("a different version is left alone").isNotNull();
    }

    // ── the drift check ────────────────────────────────────────────────────

    @Test
    void queueDriftReportsWhatAHandCorruptionDoes() {
        String missing = seedJob("PENDING", null, 1);
        String stale = seedJob("PENDING", null, 1);
        String staleSchedule = seedJob("PENDING", null, 1);
        String fine = seedJob("PENDING", null, 1);
        String movedOn = seedJob("PENDING", null, 1);
        List<String> all = List.of(missing, stale, staleSchedule, fine, movedOn);
        assertThat(LIFECYCLE.queueDrift(all)).isEqualTo(new QueueDrift(0, 0));

        DB.execute("DELETE FROM msg_dispatch_queue WHERE job_id = ?", missing);
        DB.execute("UPDATE msg_dispatch_queue SET version = version - interval '1 second' WHERE job_id = ?", stale);
        DB.execute("UPDATE msg_dispatch_queue SET scheduled_for = now() WHERE job_id = ?", staleSchedule);
        DB.execute("UPDATE msg_dispatch_jobs SET status = 'COMPLETED' WHERE id = ?", movedOn); // behind the lifecycle's back
        String ghost = Tsid.generate();
        DB.execute("INSERT INTO msg_dispatch_queue (job_id, job_created_at, sequence, mode, version)"
                + " VALUES (?, now(), 1, 'IMMEDIATE', now())", ghost);

        assertThat(LIFECYCLE.queueDrift(all)).as("missing + stale version + stale schedule; one job moved on")
                .isEqualTo(new QueueDrift(3, 1));
        assertThat(LIFECYCLE.queueDrift(List.of(ghost))).as("a queue row whose job does not exist").isEqualTo(new QueueDrift(0, 1));
        QueueDrift global = LIFECYCLE.queueDrift();
        assertThat(global.missingOrStale()).isGreaterThanOrEqualTo(3);
        assertThat(global.orphaned()).isGreaterThanOrEqualTo(2);

        // leave the class's database consistent for the table-wide checks of the other tests
        for (String j : all) DB.execute("UPDATE msg_dispatch_jobs SET status = 'COMPLETED' WHERE id = ?", j);
        for (String j : all) DB.execute("DELETE FROM msg_dispatch_queue WHERE job_id = ?", j);
        DB.execute("DELETE FROM msg_dispatch_queue WHERE job_id = ?", ghost);
    }

    // ── the migration's backfill ───────────────────────────────────────────

    @Test
    void theMigrationBackfillsExactlyThePendingJobsAndRerunningIsANoOp() throws Exception {
        // a database that has jobs but not yet the table: drop it, seed jobs the old way
        DB.execute("DROP TABLE msg_dispatch_queue");
        List<String> pending = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pending.add(DispatchJobFixture.seedWriteRowOnly(Seed.of(code("mig")).withStatus("PENDING").withMessageGroup(i % 2 == 0 ? "dq-" + RUN + "-m" : null)
                    .withSequence(i).withMode("BLOCK_ON_ERROR")));
        }
        for (String s : List.of("QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED", "EXPIRED"))
            other.add(DispatchJobFixture.seedWriteRowOnly(Seed.of(code("mig")).withStatus(s)));

        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V21__dispatch_queue.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        runScript(sql);
        List<String> queued = DB.fetch("SELECT job_id FROM msg_dispatch_queue").getValues(0, String.class);
        assertThat(queued).as("the backfill produced exactly the PENDING jobs, and no other").containsExactlyInAnyOrderElementsOf(
                DB.fetch("SELECT id FROM msg_dispatch_jobs WHERE status = 'PENDING'").getValues(0, String.class));
        assertThat(queued).containsAll(pending).doesNotContainAnyElementsOf(other);
        for (String id : pending) assertQueueMirrorsJob(id, "backfilled");

        Map<String, Object> before = queueRow(pending.get(0));
        long count = DB.fetchOne("SELECT count(*) FROM msg_dispatch_queue").get(0, Long.class);
        runScript(sql);
        assertThat(DB.fetchOne("SELECT count(*) FROM msg_dispatch_queue").get(0, Long.class)).as("re-running is a no-op").isEqualTo(count);
        assertThat(queueRow(pending.get(0))).isEqualTo(before);
        assertThat(LIFECYCLE.queueDrift()).isEqualTo(new QueueDrift(0, 0));
    }

    private static void runScript(String sql) throws SQLException {
        try (Connection c = DS.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    // ── the READ COMMITTED race ────────────────────────────────────────────

    /// Waits until some backend is blocked on a lock behind the test's own transaction.
    private static void awaitBlocked() throws Exception {
        for (int i = 0; i < 100; i++) {
            Long n = DB.fetchOne("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()").get(0, Long.class);
            if (n != null && n > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("no statement blocked on the job's row lock");
    }

    @Test
    void anEnterThatWaitsBehindALeaveCannotLoseTheJob() throws Exception {
        String id = seedJob("PENDING", null, 1);
        Instant created = createdAt(id);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection a = DS.getConnection()) {
            a.setAutoCommit(false);
            try (Statement st = a.createStatement()) {
                // A is a leave in flight: the job goes QUEUED and its queue row is deleted, uncommitted
                st.execute("UPDATE msg_dispatch_jobs SET status = 'QUEUED', updated_at = now() WHERE id = '" + id + "'");
                st.execute("DELETE FROM msg_dispatch_queue WHERE job_id = '" + id + "'");
                // B is an enter (a deferral, PENDING again): it blocks on the job's row lock
                Future<?> b = pool.submit(() -> LIFECYCLE.reschedule(id, created, Instant.now().plusSeconds(30)));
                awaitBlocked();
                a.commit();
                b.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertQueueMirrorsJob(id, "the enter that waited behind the leave");
        assertThat(LIFECYCLE.queueDrift(List.of(id))).isEqualTo(new QueueDrift(0, 0));
    }

    @Test
    void aLeaveThatWaitsBehindAnEnterCanLeaveAStaleRowWhichMarkQueuedHeals() throws Exception {
        // The documented, accepted anomaly: the leave's DELETE does not see the row the enter inserted.
        String id = seedJob("PROCESSING", null, 1);
        Instant created = createdAt(id);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection a = DS.getConnection()) {
            a.setAutoCommit(false);
            // A is an enter in flight: the operator requeue, PROCESSING -> PENDING, queue row inserted, uncommitted
            assertThat(DispatchJobLifecycle.requeue(a, id, created)).isTrue();
            // B is a leave (the delivery completes): its UPDATE matches the old row version, so it blocks
            // behind A's row lock, then re-checks the new version (PENDING, live) and completes it
            Future<?> b = pool.submit(() -> LIFECYCLE.markCompleted(id, created, Instant.now(), 1L));
            awaitBlocked();
            a.commit();
            b.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.COMPLETED);
        QueueDrift drift = LIFECYCLE.queueDrift(List.of(id));
        // Never the dangerous direction; the stale extra row is the accepted outcome
        assertThat(drift.missingOrStale()).isZero();
        assertThat(drift.orphaned()).as("a queue row outlived the job's PENDING status (accepted, self-healing)").isEqualTo(1);

        // ...and the scheduler's mark-QUEUED, which claimed that version, removes it
        Instant staleVersion = ((OffsetDateTime) queueRow(id).get("version")).toInstant();
        LIFECYCLE.markQueued(List.of(claim(id, staleVersion)));
        assertThat(queueRow(id)).isNull();
        assertThat(LIFECYCLE.queueDrift(List.of(id))).isEqualTo(new QueueDrift(0, 0));
    }

    /// A mark-QUEUED whose claim is stale must not delete the row of a job that re-entered PENDING while the
    /// statement ran. Two connections: A requeues the job (new queue version, uncommitted); B's mark-QUEUED of
    /// the OLD version saw the old row, blocks on it, and on A's commit re-evaluates its DELETE — which must
    /// re-check the version. Mutant: key the DELETE on job_id only — the job is PENDING with no queue row.
    @Test
    void aStaleMarkQueuedDoesNotDeleteTheRowOfAJobThatReEnteredPending() throws Exception {
        String id = seedJob("PENDING", null, 1);
        Instant created = createdAt(id);
        Instant staleVersion = ((OffsetDateTime) queueRow(id).get("version")).toInstant();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection a = DS.getConnection()) {
            a.setAutoCommit(false);
            assertThat(DispatchJobLifecycle.requeue(a, id, created)).isTrue(); // PENDING again: new version, uncommitted
            Future<Integer> b = pool.submit(() -> LIFECYCLE.markQueued(List.of(claim(id, staleVersion))));
            awaitBlocked();
            a.commit();
            assertThat(b.get(10, TimeUnit.SECONDS)).as("the job moved on since the claim: nothing marked").isZero();
        } finally {
            pool.shutdownNow();
        }
        assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertQueueMirrorsJob(id, "the refreshed row survived the stale mark-QUEUED");
    }

    // ── randomized ─────────────────────────────────────────────────────────

    @Test
    void randomConcurrentLifecycleOperationsNeverLoseAJob() throws Exception {
        int iterations = Integer.getInteger("dq.random.iterations", 1);
        long lost = 0;
        for (int i = 0; i < iterations; i++) lost += randomRun();
        System.out.println("DispatchQueueTest random: iterations=" + iterations + " missingOrStale total=" + lost);
        assertThat(lost).as("PENDING jobs without a queue row or with a stale one, over %d iterations", iterations).isZero();
    }

    /// One randomized run; returns the number of PENDING jobs left without an exact queue row.
    private long randomRun() throws Exception {
        // pooled: thousands of operations per run, and a hundred runs, would exhaust ephemeral ports unpooled
        var cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setDataSource(DS);
        cfg.setMaximumPoolSize(8);
        try (var pds = new com.zaxxer.hikari.HikariDataSource(cfg)) {
            return randomRun(pds);
        }
    }

    private static void pooledTx(javax.sql.DataSource pds, java.util.function.Consumer<Connection> work) {
        try (Connection conn = pds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.accept(conn);
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long randomRun(javax.sql.DataSource pds) throws Exception {
        var plife = new DispatchJobLifecycle(pds);
        var prepo = new DispatchJobRepository(pds);
        int jobsN = 40;
        int workers = 6;
        int opsPerWorker = 500; // 3,000 operations
        List<String> ids = new ArrayList<>();
        List<Instant> created = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (int g = 0; g < 10; g++) groups.add("dq-" + RUN + "-rg" + g);
        String[] statuses = {"PENDING", "PENDING", "QUEUED", "PROCESSING", "FAILED", "COMPLETED"};
        Random seedRandom = new Random(42);
        for (int i = 0; i < jobsN; i++) {
            String status = statuses[seedRandom.nextInt(statuses.length)];
            boolean grouped = i % 3 != 0;
            String group = grouped ? groups.get(seedRandom.nextInt(groups.size())) : null;
            String id = seedQueued(Seed.of(code("rnd")).withStatus(status).withMode("BLOCK_ON_ERROR")
                    .withUpdatedAt(Instant.now().minusSeconds(3600))
                    .withMessageGroup(group).withSequence(i));
            ids.add(id);
            created.add(createdAt(id));
        }
        assertThat(plife.queueDrift(ids)).as("clean before the run").isEqualTo(new QueueDrift(0, 0));

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<Integer>> results = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            long seed = 1000 + w;
            results.add(pool.submit((Callable<Integer>) () -> {
                Random r = new Random(seed);
                int done = 0;
                for (int n = 0; n < opsPerWorker; n++) {
                    int k = r.nextInt(jobsN);
                    String id = ids.get(k);
                    Instant at = created.get(k);
                    try {
                    switch (r.nextInt(11)) {
                        case 0 -> plife.claimForDelivery(id, at);
                        case 1 -> plife.markCompleted(id, at, Instant.now(), 3L);
                        case 2 -> plife.markFailed(id, at, "boom");
                        case 3 -> plife.scheduleRetry(id, at, Instant.now().plusSeconds(r.nextInt(3) * 30), 1, "retry");
                        case 4 -> plife.reschedule(id, at, r.nextBoolean() ? null : Instant.now().plusSeconds(30));
                        case 5 -> plife.settleAcked(List.of(id, ids.get(r.nextInt(jobsN)), ids.get(r.nextInt(jobsN))), "settled");
                        case 6 -> plife.sweepStrandedSiblings(Instant.now().plusSeconds(60), "reaper");
                        case 7, 8 -> {
                            // a mark-QUEUED of a claim at the job's current version
                            var j = prepo.findById(id);
                            if (j.isPresent()) plife.markQueued(List.of(
                                    new ClaimRow(id, null, null, null, null, null, at, 0, null, j.get().updatedAt())));
                        }
                        case 9 -> pooledTx(pds, tx -> DispatchJobLifecycle.requeue(tx, id, at));
                        default -> plife.markQueued(List.of(
                                new ClaimRow(id, null, null, null, null, null, at, 0, null, Instant.now().minusSeconds(7200))));
                    }
                    } catch (org.jooq.exception.DataAccessException e) {
                        // two multi-row statements locking jobs in different orders: Postgres kills one; the
                        // operation simply did not happen (its statement rolled back whole)
                        if (!String.valueOf(e.getCause()).contains("deadlock detected")) throw e;
                    }
                    done++;
                }
                return done;
            }));
        }
        int total = 0;
        for (Future<Integer> f : results) total += f.get(120, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(total).isEqualTo(workers * opsPerWorker);

        QueueDrift drift = plife.queueDrift(ids);
        System.out.println("DispatchQueueTest random run: ops=" + total + " jobs=" + jobsN + " drift=" + drift);
        // orphans are the documented race's and allowed; each must be a non-PENDING job with a row
        assertThat(drift.orphaned()).isLessThanOrEqualTo(jobsN);
        return drift.missingOrStale();
    }
}
