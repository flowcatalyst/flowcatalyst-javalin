package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [PendingJobPoller] under a backlog: what a claim reports (the scheduler's
/// back-off decision), and what a full claim does and does not publish. Its
/// own class, so its own embedded database — the small batch sizes here are
/// only meaningful when no other test's `PENDING` rows share the table.
class PendingJobPollerBacklogTest {

    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);
    private static final HmacTokenVerifier AUTH = HmacTokenVerifier.fromAppKey("test-app-key-" + RUN);
    private static final String ENDPOINT = "http://localhost:18080/api/dispatch/process";

    private final List<PendingJobPoller> pollers = new ArrayList<>();

    private PendingJobPoller poller(DispatchJobRepository repo, DispatchPublisher publisher,
                                    BooleanSupplier leader, int batchSize) {
        var poller = new PendingJobPoller(DATA_SOURCE, repo, new DispatchJobLifecycle(DATA_SOURCE), new PausedConnectionCache(DATA_SOURCE),
                new PoolCodeResolver(DATA_SOURCE), publisher, AUTH, ENDPOINT, leader,
                SchedulerConfig.DEFAULTS.withBatchSize(batchSize));
        pollers.add(poller);
        return poller;
    }

    private PendingJobPoller poller(DispatchPublisher publisher, int batchSize) {
        return poller(REPO, publisher, () -> true, batchSize);
    }

    private static PendingJobPoller.PollResult pollAndSettle(PendingJobPoller poller) {
        var result = poller.pollOnce();
        assertThat(poller.awaitIdle(Duration.ofSeconds(15))).as("the lanes went idle").isTrue();
        return result;
    }

    /// Each test sees only its own rows: whatever it left PENDING (held,
    /// paused, refused) would otherwise sort ahead of the next test's.
    @AfterEach
    void clearPending() {
        pollers.forEach(PendingJobPoller::close);
        pollers.clear();
        DB.update(MSG_DISPATCH_JOBS).set(MSG_DISPATCH_JOBS.STATUS, "COMPLETED")
                .where(MSG_DISPATCH_JOBS.STATUS.eq("PENDING")).execute();
    }

    @Test
    void aFullClaimThatSubmittedSomethingDoesNotBackOff() {
        for (int i = 0; i < 3; i++) {
            seedWriteRow(Seed.of(code("res-full" + i + "-")).withMessageGroup("aaa-res-full-" + RUN + i));
        }
        var result = poller(FakeDispatchPublisher.succeeding(), 3).pollOnce();
        assertThat(result.claimed()).isEqualTo(3);
        assertThat(result.submitted()).isEqualTo(3);
        assertThat(result.wanted()).isEqualTo(3);
        assertThat(result.backOff()).as("a full claim that submitted rows: more may be waiting").isFalse();
    }

    /// Mutant: no back-off on a short claim — the poller spins on an empty queue.
    @Test
    void aShortClaimBacksOff() {
        seedWriteRow(Seed.of(code("res-short-")).withMessageGroup("bbb-res-short-" + RUN));
        var a = poller(FakeDispatchPublisher.succeeding(), 1000).pollOnce();
        assertThat(a.claimed()).isEqualTo(1);
        assertThat(a.backOff()).as("short claim").isTrue();
    }

    /// Mutant: no back-off when nothing was submitted — a full claim of held
    /// rows is claimed again at once, for ever.
    @Test
    void aFullClaimOfOnlyHeldRowsBacksOff() {
        String group = "000-allheld-" + RUN;
        seedWriteRow(Seed.of(code("allheld-head-")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(1).withStatus("FAILED"));
        for (int i = 0; i < 3; i++) {
            seedWriteRow(Seed.of(code("allheld" + i + "-")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                    .withSequence(2 + i));
        }
        var result = poller(FakeDispatchPublisher.succeeding(), 3).pollOnce();
        assertThat(result.claimed()).isEqualTo(3);
        assertThat(result.submitted()).isZero();
        assertThat(result.heldBack()).isEqualTo(3);
        assertThat(result.backOff()).isTrue();
    }

    /// Without the pause a failing broker is retried in a hot loop: its rows
    /// are still PENDING, so every claim returns them again. The claim that
    /// follows a lane failure must say "back off" even though it is full and
    /// submitted rows. Mutant: ignore the lanes' failure report.
    @Test
    void aClaimFollowingALaneFailureBacksOff() {
        for (int i = 0; i < 2; i++) {
            seedWriteRow(Seed.of(code("res-fail" + i + "-")).withMessageGroup("ccc-res-fail-" + RUN + i));
        }
        var poller = poller(FakeDispatchPublisher.failing(), 2);

        var first = pollAndSettle(poller);
        assertThat(first.claimed()).isEqualTo(2);

        var second = pollAndSettle(poller);
        assertThat(second.claimed()).as("the failed rows are PENDING and not in flight, so claimed again")
                .isEqualTo(2);
        assertThat(second.submitted()).isEqualTo(2);
        // The lane's failure report is consumed by whichever claim looks first:
        // the first one if the lane was quick, otherwise the second. Either way
        // one of the two claims — both full, both submitting — must back off.
        assertThat(first.backOff() || second.backOff())
                .as("a lane failed since the previous claim").isTrue();
    }

    @Test
    void aNonLeaderReportsIdle() {
        seedWriteRow(Seed.of(code("res-nl-")));
        var result = poller(REPO, FakeDispatchPublisher.succeeding(), () -> false, 1).pollOnce();
        assertThat(result.claimed()).isZero();
        assertThat(result.backOff()).isTrue();
    }

    // ── paused subscriptions do not starve the queue ────────────────────────

    /// Batch-size paused jobs sort ahead (group "aaa" before "zzz"); an active
    /// subscription's job behind them must still be claimed and published. When
    /// the filter ran after the claim the paused rows filled every batch and
    /// the job behind them was never reached. Mutant: claim without excluding
    /// the paused subscriptions.
    @Test
    void pausedJobsSortingFirstDoNotStarveAnActiveJobBehindThem() {
        String pausedSub = SchedulerFixture.subscription(SchedulerFixture.connection("PAUSED"));
        String activeSub = SchedulerFixture.subscription(SchedulerFixture.connection("ACTIVE"));
        for (int i = 0; i < 6; i++) {
            seedWriteRow(Seed.of(code("starve-paused" + i + "-")).withSubscriptionId(pausedSub)
                    .withMessageGroup("aaa-starve-" + RUN).withSequence(i));
        }
        String active = seedWriteRow(Seed.of(code("starve-active-")).withSubscriptionId(activeSub)
                .withMessageGroup("zzz-starve-" + RUN));
        String ungrouped = seedWriteRow(Seed.of(code("starve-nosub-")));
        var publisher = FakeDispatchPublisher.succeeding();

        var result = pollAndSettle(poller(publisher, 5));

        assertThat(publisher.batches().stream().flatMap(java.util.List::stream).map(PublishedMessage::jobId))
                .containsExactlyInAnyOrder(active, ungrouped);
        assertThat(REPO.findById(active).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(result.submitted()).isEqualTo(2);
    }

    // ── a full claim that publishes nothing says so ─────────────────────────

    @Test
    void aFullClaimThatSubmitsNothingWarnsOncePerMinuteWithTheCounts() {
        String group = "000-starved-" + RUN;
        seedWriteRow(Seed.of(code("held-head-")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(1).withStatus("FAILED"));
        for (int i = 0; i < 3; i++) {
            seedWriteRow(Seed.of(code("held" + i + "-")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                    .withSequence(2 + i));
        }
        var logger = (Logger) LoggerFactory.getLogger(PendingJobPoller.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var poller = poller(FakeDispatchPublisher.succeeding(), 3);
            var first = poller.pollOnce();
            poller.pollOnce();

            assertThat(first.submitted()).isZero();
            var warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertThat(warnings).as("once, not once per tick").hasSize(1);
            assertThat(warnings.getFirst().getKeyValuePairs().toString())
                    .contains("claimed", "3").contains("heldSkipped");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aShortClaimThatSubmitsNothingDoesNotWarn() {
        String group = "000-notstarved-" + RUN;
        seedWriteRow(Seed.of(code("held2-head-")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(1).withStatus("FAILED"));
        seedWriteRow(Seed.of(code("held2-")).withMessageGroup(group).withMode("BLOCK_ON_ERROR").withSequence(2));
        var logger = (Logger) LoggerFactory.getLogger(PendingJobPoller.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            poller(FakeDispatchPublisher.succeeding(), 100).pollOnce();
            assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN)).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ── the hold-back check is one query, not one per candidate ─────────────

    /// A DataSource whose connections count the hold-back statements
    /// (`DISTINCT ON (message_group)`) prepared on them.
    private static final class CountingDataSource {
        final java.util.concurrent.atomic.AtomicInteger holdBackQueries = new java.util.concurrent.atomic.AtomicInteger();
        final javax.sql.DataSource proxy = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                PendingJobPollerBacklogTest.class.getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (p, method, args) -> {
                    Object result = invoke(method, DATA_SOURCE, args);
                    if (method.getName().equals("getConnection") && result instanceof java.sql.Connection c) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                PendingJobPollerBacklogTest.class.getClassLoader(),
                                new Class<?>[]{java.sql.Connection.class}, (cp, cm, cargs) -> {
                                    if (cm.getName().equals("prepareStatement") && cargs != null
                                            && cargs[0] instanceof String sql && sql.contains("DISTINCT ON (message_group)")) {
                                        holdBackQueries.incrementAndGet();
                                    }
                                    return invoke(cm, c, cargs);
                                });
                    }
                    return result;
                });

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /// Mutant: ask once per BLOCK_ON_ERROR candidate (12 round trips here).
    @Test
    void manyBlockOnErrorCandidatesAcrossSeveralGroupsAreCheckedInOneQuery() {
        var held = new java.util.ArrayList<String>();
        var flowing = new java.util.ArrayList<String>();
        for (int g = 0; g < 4; g++) {
            String group = "hold-" + g + "-" + RUN;
            // Groups 0 and 1 have a FAILED head at sequence 2: sequences 3.. are
            // held, sequence 1 (positioned before it) is not. Groups 2 and 3 have none.
            boolean failedHead = g < 2;
            flowing.add(seedWriteRow(Seed.of(code("h" + g + "a-")).withMessageGroup(group)
                    .withMode("BLOCK_ON_ERROR").withSequence(1)));
            if (failedHead) {
                seedWriteRow(Seed.of(code("h" + g + "f-")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                        .withSequence(2).withStatus("FAILED"));
            }
            for (int i = 3; i < 5; i++) {
                String id = seedWriteRow(Seed.of(code("h" + g + "b" + i + "-")).withMessageGroup(group)
                        .withMode("BLOCK_ON_ERROR").withSequence(i));
                (failedHead ? held : flowing).add(id);
            }
        }
        var counting = new CountingDataSource();
        var publisher = FakeDispatchPublisher.succeeding();

        var result = pollAndSettle(poller(new DispatchJobRepository(counting.proxy), publisher, () -> true, 100));

        assertThat(counting.holdBackQueries.get()).as("one hold-back query for 12 candidates in 4 groups").isEqualTo(1);
        assertThat(publisher.batches().stream().flatMap(java.util.List::stream).map(PublishedMessage::jobId))
                .containsExactlyInAnyOrderElementsOf(flowing)
                .doesNotContainAnyElementsOf(held);
        assertThat(result.claimed()).isEqualTo(flowing.size() + held.size());
    }
}
