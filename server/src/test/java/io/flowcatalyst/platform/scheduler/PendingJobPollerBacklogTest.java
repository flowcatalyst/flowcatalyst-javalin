package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
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

import java.util.function.BooleanSupplier;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [PendingJobPoller] under a backlog: what a tick reports (the scheduler's
/// drain decision), and what a full claim does and does not publish. Its own
/// class, so its own embedded database — the small batch sizes here are only
/// meaningful when no other test's `PENDING` rows share the table.
class PendingJobPollerBacklogTest {

    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);
    private static final HmacTokenVerifier AUTH = HmacTokenVerifier.fromAppKey("test-app-key-" + RUN);
    private static final String ENDPOINT = "http://localhost:18080/api/dispatch/process";

    private static PendingJobPoller poller(DispatchJobRepository repo, DispatchPublisher publisher,
                                           BooleanSupplier leader, int batchSize) {
        return new PendingJobPoller(DATA_SOURCE, repo, new PausedConnectionCache(DATA_SOURCE),
                new PoolCodeResolver(DATA_SOURCE), publisher, AUTH, ENDPOINT, leader, batchSize);
    }

    private static PendingJobPoller poller(DispatchPublisher publisher, int batchSize) {
        return poller(REPO, publisher, () -> true, batchSize);
    }

    /// Each test sees only its own rows: whatever it left PENDING (held,
    /// paused, refused) would otherwise sort ahead of the next test's.
    @AfterEach
    void clearPending() {
        DB.update(MSG_DISPATCH_JOBS).set(MSG_DISPATCH_JOBS.STATUS, "COMPLETED")
                .where(MSG_DISPATCH_JOBS.STATUS.eq("PENDING")).execute();
    }

    @Test
    void aFullProductiveTickReportsItCanDrainImmediately() {
        for (int i = 0; i < 3; i++) {
            seedWriteRow(Seed.of(code("res-full" + i + "-")).withMessageGroup("aaa-res-full-" + RUN + i));
        }
        var result = poller(FakeDispatchPublisher.succeeding(), 3).pollOnce();
        assertThat(result.claimed()).isEqualTo(3);
        assertThat(result.published()).isEqualTo(3);
        assertThat(result.drainImmediately()).isTrue();
    }

    @Test
    void aShortOrUnproductiveTickDoesNotDrainImmediately() {
        seedWriteRow(Seed.of(code("res-short-")).withMessageGroup("bbb-res-short-" + RUN));
        var a = poller(FakeDispatchPublisher.succeeding(), 1000).pollOnce();
        assertThat(a.drainImmediately()).as("short batch").isFalse();

        for (int i = 0; i < 2; i++) {
            seedWriteRow(Seed.of(code("res-fail" + i + "-")).withMessageGroup("ccc-res-fail-" + RUN + i));
        }
        var b = poller(FakeDispatchPublisher.failing(), 2).pollOnce();
        assertThat(b.claimed()).isEqualTo(2);
        assertThat(b.published()).isZero();
        assertThat(b.drainImmediately()).as("full claim that published nothing").isFalse();
    }

    @Test
    void aNonLeaderReportsIdle() {
        seedWriteRow(Seed.of(code("res-nl-")));
        var result = poller(REPO, FakeDispatchPublisher.succeeding(), () -> false, 1).pollOnce();
        assertThat(result.claimed()).isZero();
        assertThat(result.drainImmediately()).isFalse();
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

        var result = poller(publisher, 5).pollOnce();

        assertThat(publisher.batches().stream().flatMap(java.util.List::stream).map(PublishedMessage::jobId))
                .containsExactlyInAnyOrder(active, ungrouped);
        assertThat(REPO.findById(active).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(result.published()).isEqualTo(2);
    }

    // ── a full claim that publishes nothing says so ─────────────────────────

    @Test
    void aFullClaimThatPublishesNothingWarnsOncePerMinuteWithTheCounts() {
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

            assertThat(first.published()).isZero();
            var warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertThat(warnings).as("once, not once per tick").hasSize(1);
            assertThat(warnings.getFirst().getKeyValuePairs().toString())
                    .contains("claimed", "3").contains("heldSkipped").contains("pausedSkipped");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aShortClaimThatPublishesNothingDoesNotWarn() {
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

    /// A DataSource that counts the connections the repository asks for: its
    /// claim and mark statements ride the poller's own transaction, so every
    /// connection it takes here is a read of its own (the hold-back check).
    private static final class CountingDataSource {
        final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
        final javax.sql.DataSource proxy = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                PendingJobPollerBacklogTest.class.getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (p, method, args) -> {
                    if (method.getName().equals("getConnection")) connections.incrementAndGet();
                    try {
                        return method.invoke(DATA_SOURCE, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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

        var result = poller(new DispatchJobRepository(counting.proxy), publisher, () -> true, 100).pollOnce();

        assertThat(counting.connections.get()).as("one hold-back query for 12 candidates in 4 groups").isEqualTo(1);
        assertThat(publisher.batches().stream().flatMap(java.util.List::stream).map(PublishedMessage::jobId))
                .containsExactlyInAnyOrderElementsOf(flowing)
                .doesNotContainAnyElementsOf(held);
        assertThat(result.claimed()).isEqualTo(flowing.size() + held.size());
    }
}
