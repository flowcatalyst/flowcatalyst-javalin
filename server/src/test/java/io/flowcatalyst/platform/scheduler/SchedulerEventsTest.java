package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.scheduler.jfr.ClaimedBatchEvent;
import io.flowcatalyst.platform.scheduler.jfr.LanePublishEvent;
import io.flowcatalyst.testjfr.Recorded;
import jdk.jfr.consumer.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// The scheduler's flight-recorder events, read back out of a real recording
/// (`docs/spec/jfr-events.md`): `ClaimedBatch` once per claim, `LanePublish`
/// once per dispatcher-lane batch.
class SchedulerEventsTest {

    private static PendingJobPoller poller(DispatchPublisher publisher) {
        return new PendingJobPoller(DATA_SOURCE, new DispatchJobRepository(DATA_SOURCE),
                new PausedConnectionCache(DATA_SOURCE), new PoolCodeResolver(DATA_SOURCE), publisher,
                HmacTokenVerifier.fromAppKey("test-app-key-" + RUN), "http://localhost:18080/api/dispatch/process",
                () -> true, SchedulerConfig.DEFAULTS.withDispatchers(1).withBatchSize(100));
    }

    @Test
    void aClaimRecordsOneClaimedBatchEvent() throws Exception {
        for (int i = 0; i < 3; i++) seedWriteRow(Seed.of(code("jfrclaim" + i + "-")));
        try (var poller = poller(FakeDispatchPublisher.succeeding())) {
            List<RecordedEvent> events = Recorded.from(ClaimedBatchEvent.class, () -> {
                poller.pollOnce();
                poller.awaitIdle(Duration.ofSeconds(15));
            });

            assertThat(events).hasSize(1);
            var e = events.getFirst();
            assertThat(e.getInt("size")).isEqualTo(3);
            assertThat(e.getInt("submitted")).isEqualTo(3);
            assertThat(e.getInt("heldBack")).isZero();
            assertThat(e.getInt("wanted")).isEqualTo(100);
        }
    }

    @Test
    void aLaneBatchRecordsOneLanePublishEvent() throws Exception {
        for (int i = 0; i < 2; i++) seedWriteRow(Seed.of(code("jfrlane" + i + "-")));
        try (var poller = poller(FakeDispatchPublisher.succeeding())) {
            List<RecordedEvent> events = Recorded.from(LanePublishEvent.class, () -> {
                poller.pollOnce();
                poller.awaitIdle(Duration.ofSeconds(15));
            });

            assertThat(events).isNotEmpty();
            assertThat(events.stream().mapToInt(e -> e.getInt("taken")).sum()).isEqualTo(2);
            assertThat(events.stream().mapToInt(e -> e.getInt("published")).sum()).isEqualTo(2);
            assertThat(events.stream().mapToInt(e -> e.getInt("unpublished")).sum()).isZero();
            assertThat(events).allSatisfy(e -> assertThat(e.getInt("lane")).isZero());
        }
    }
}
