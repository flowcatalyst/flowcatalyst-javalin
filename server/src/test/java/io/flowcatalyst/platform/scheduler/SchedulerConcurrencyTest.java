package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.PlanDatabases;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// The class of test that would have caught the queue table's stall: realistic width on a real database in the
/// realistic statistics state (the active partition analysed while no job was PENDING, then a burst of 200,000).
/// The engine runs as in production — 10 lanes marking batches of 100, a poller claiming 500 at a time, 1,000 jobs in
/// flight, on a pool with the scheduler's planner settings — while two callback-like workers move published jobs
/// PENDING -> PROCESSING -> COMPLETED on a second pool WITHOUT the settings. Asserted: no statement takes longer than
/// 250 ms on either pool (the slowest is reported), throughput does not collapse (with a bad plan no single statement
/// exceeded 250 ms but the rate fell 80%), and no job is lost, published twice or published out of order.
class SchedulerConcurrencyTest {

    private static final int BURST = 200_000;
    private static final int TARGET = 40_000;
    private static final long STATEMENT_LIMIT_MS = 250;
    private static final double MIN_JOBS_PER_SECOND = 10_000; // measured 14-16k here; a sargable by-key guard gives about 9k

    /// Times every statement a pool runs; keeps the slowest.
    private static final class Timing {
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicReference<String> maxSql = new AtomicReference<>("");
        final AtomicLong count = new AtomicLong();

        javax.sql.DataSource over(javax.sql.DataSource ds) {
            return (javax.sql.DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {javax.sql.DataSource.class},
                    (p, m, a) -> {
                        try {
                            Object r = m.invoke(ds, a);
                            return r instanceof Connection c && m.getName().equals("getConnection") ? connection(c) : r;
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private Connection connection(Connection c) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
                try {
                    Object r = m.invoke(c, a);
                    if (r instanceof java.sql.PreparedStatement ps && m.getName().equals("prepareStatement")) return statement(ps, (String) a[0]);
                    return r;
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }

        private java.sql.PreparedStatement statement(java.sql.PreparedStatement ps, String sql) {
            return (java.sql.PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class},
                    (p, m, a) -> {
                        long t0 = System.nanoTime();
                        try {
                            return m.invoke(ps, a);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        } finally {
                            if (m.getName().startsWith("execute")) {
                                long d = System.nanoTime() - t0;
                                count.incrementAndGet();
                                long prev;
                                while (d > (prev = maxNanos.get())) {
                                    if (maxNanos.compareAndSet(prev, d)) {
                                        maxSql.set(sql);
                                        break;
                                    }
                                }
                            }
                        }
                    });
        }

        double maxMs() {
            return maxNanos.get() / 1e6;
        }
    }

    /// Publishes nothing anywhere: records what the lanes hand it, in order, and flags a job seen twice or a group's
    /// sequence that does not increase.
    private static final class OrderedPublisher implements DispatchPublisher {
        final Map<String, Integer> seen = new HashMap<>();
        final Map<String, Integer> lastSeq = new HashMap<>();
        final List<String> duplicate = new ArrayList<>();
        final List<String> outOfOrder = new ArrayList<>();
        final AtomicLong published = new AtomicLong();
        final BlockingQueue<String> out = new ArrayBlockingQueue<>(BURST);

        @Override
        public void publish(List<PublishedMessage> batch) {
            synchronized (this) {
                for (PublishedMessage m : batch) {
                    String id = m.jobId();
                    int n = Integer.parseInt(id.substring(1));
                    String group = "g" + String.format("%04d", n % 500);
                    int seq = n / 500;
                    if (seen.merge(id, 1, Integer::sum) > 1) duplicate.add(id);
                    Integer last = lastSeq.get(group);
                    if (last != null && seq <= last) outOfOrder.add(id + ": " + seq + " after " + last);
                    lastSeq.merge(group, seq, Math::max);
                }
            }
            published.addAndGet(batch.size());
            for (PublishedMessage m : batch) out.offer(m.jobId());
        }
    }

    @Test
    void lanesPollerAndCallbacksAgainstABurstKeepEveryStatementFastAndLoseNothing() throws Exception {
        String db = PlanDatabases.clone("dq_conc_run");
        try (Connection admin = new PlanDatabases.Source(db, false, false).getConnection()) {
            PlanDatabases.seedCompleted(admin, 60_000);
            PlanDatabases.exec(admin, "ANALYZE msg_dispatch_jobs"); // statistics: PENDING is absent from the active partition
            PlanDatabases.burst(admin, BURST, false);
        }
        String url = TestPg.instance().getJdbcUrl("postgres", db);
        var schedTiming = new Timing();
        var cbTiming = new Timing();
        var schedPool = Database.newPool(url, Pools.schedulerPoolSizeFor(10), Pools.SCHEDULER_SERVER_SETTINGS);
        var cbPool = Database.newPool(url, 4); // the platform's pool: no planner settings
        var pub = new OrderedPublisher();
        long m0 = PlanDatabases.monthStart().getEpochSecond();
        var completed = new AtomicLong();
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        DispatchScheduler scheduler = null;
        try {
            var callbacks = new DispatchJobLifecycle(cbTiming.over(cbPool));
            for (int w = 0; w < 2; w++) {
                var t = new Thread(() -> {
                    while (!stop.get()) {
                        try {
                            String id = pub.out.poll(100, TimeUnit.MILLISECONDS);
                            if (id == null) continue;
                            Instant created = Instant.ofEpochSecond(m0 + Integer.parseInt(id.substring(1)));
                            if (callbacks.claimForDelivery(id, created)) {
                                callbacks.markCompleted(id, created, Instant.now(), 1L);
                                completed.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }, "callback-worker-" + w);
                t.setDaemon(true);
                t.start();
                workers.add(t);
            }
            scheduler = DispatchScheduler.start("test-app-key-conc", "http://localhost:18080/api/dispatch/process",
                    schedTiming.over(schedPool), pub, () -> true);
            assertThat(scheduler).isNotNull();

            long start = System.nanoTime();
            while (pub.published.get() < TARGET && System.nanoTime() - start < Duration.ofMinutes(3).toNanos()) Thread.sleep(50);
            scheduler.close();
            scheduler = null;
            Thread.sleep(2000); // let the callback workers drain what was published
            stop.set(true);
            for (Thread t : workers) t.join(5000);
            double seconds = (System.nanoTime() - start) / 1e9;

            long n = pub.published.get();
            double rate = n / seconds;
            String report = String.format("CONCURRENCY: %d jobs published in %.1f s (%.0f jobs/s), %d completed by the callback workers;"
                            + " scheduler pool: %d statements, max %.1f ms; callback pool: %d statements, max %.1f ms%n"
                            + "slowest scheduler statement: %.140s%nslowest callback statement: %.140s%n",
                    n, seconds, rate, completed.get(), schedTiming.count.get(), schedTiming.maxMs(), cbTiming.count.get(), cbTiming.maxMs(),
                    schedTiming.maxSql.get().replaceAll("\\s+", " "), cbTiming.maxSql.get().replaceAll("\\s+", " "));
            System.out.println(report);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/scheduler-concurrency.txt"), report);

            assertThat(completed.get()).as("the callback workers completed nothing%n%s", report).isGreaterThan(1000L);
            assertThat(n).as("the engine did not get through %d jobs in time%n%s", TARGET, report).isGreaterThanOrEqualTo(TARGET);
            // A planner stall shows as a collapse in throughput before any single statement passes the limit.
            assertThat(rate).as("throughput collapsed%n%s", report).isGreaterThan(MIN_JOBS_PER_SECOND);
            assertThat(schedTiming.maxMs()).as("a scheduler statement exceeded the limit%n%s", report).isLessThan(STATEMENT_LIMIT_MS);
            assertThat(cbTiming.maxMs()).as("a callback statement exceeded the limit%n%s", report).isLessThan(STATEMENT_LIMIT_MS);
            synchronized (pub) {
                assertThat(pub.outOfOrder).as("published out of order").isEmpty();
                assertThat(pub.duplicate).as("published twice").isEmpty();
            }
            // nothing published was left PENDING (a lost mark-QUEUED)
            List<String> published;
            synchronized (pub) {
                published = new ArrayList<>(pub.seen.keySet());
            }
            try (Connection c = new PlanDatabases.Source(db, false, false).getConnection();
                 java.sql.PreparedStatement ps = c.prepareStatement(
                         "SELECT count(*) FROM msg_dispatch_jobs WHERE id = ANY(?) AND status = 'PENDING'")) {
                ps.setArray(1, c.createArrayOf("text", published.toArray(String[]::new)));
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("a published job was left PENDING").isZero();
                }
            }
        } finally {
            stop.set(true);
            if (scheduler != null) scheduler.close();
            schedPool.close();
            cbPool.close();
        }
    }
}
