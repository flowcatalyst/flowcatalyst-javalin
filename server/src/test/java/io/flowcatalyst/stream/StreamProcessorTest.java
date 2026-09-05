package io.flowcatalyst.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// [StreamProcessor] (stream spec §1, §9): the enabled projectors are
/// started under one scope, exposed through [HealthService], and
/// [StreamProcessor#close] interrupts and joins every one of them promptly
/// — the shutdown test the spec calls for, run against every enabled
/// projector at once (not just one, as [ProjectorTest] already covers the
/// pacing/leader-gate mechanics in isolation with a fake step).
class StreamProcessorTest {

    private static final StreamProcessor.Settings ALL_ENABLED = new StreamProcessor.Settings(
            true, 0, true, 0, true, 0, true, 0, 0, 0, 0, 0, 0);
    private static final StreamProcessor.Settings ALL_DISABLED = new StreamProcessor.Settings(
            false, 0, false, 0, false, 0, false, 0, 0, 0, 0, 0, 0);

    @Test
    @DisplayName("every enabled projector registers its own health, named for its stage")
    void registersHealthPerEnabledProjector() {
        var processor = StreamProcessor.start(StreamFixture.DS, ALL_ENABLED, () -> true);
        try {
            var aggregate = processor.healthService().aggregate();
            assertThat(aggregate.totalStreams()).isEqualTo(4);
            Set<String> names = aggregate.streams().stream().map(h -> h.name()).collect(Collectors.toSet());
            assertThat(names).containsExactlyInAnyOrder(
                    "event_fan_out", "event_projection", "dispatch_job_projection", "partition_manager");
        } finally {
            processor.close();
        }
    }

    @Test
    @DisplayName("every sub-toggle off starts no projectors, but the subsystem still runs cleanly")
    void allDisabledStartsNothing() {
        var processor = StreamProcessor.start(StreamFixture.DS, ALL_DISABLED, () -> true);
        try {
            assertThat(processor.healthService().aggregate().totalStreams()).isZero();
            assertThat(processor.healthService().isLive()).isFalse();
        } finally {
            processor.close();
        }
    }

    @Test
    @DisplayName("close interrupts and joins every projector promptly; running goes false on each")
    void closeStopsEveryProjectorPromptly() throws InterruptedException {
        var processor = StreamProcessor.start(StreamFixture.DS, ALL_ENABLED, () -> true);
        Thread.sleep(50); // let every projector actually enter its loop

        long start = System.nanoTime();
        processor.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("shutdown must not wait out a full idle/tick sleep").isLessThan(5_000);
        for (var snapshot : processor.healthService().aggregate().streams()) {
            assertThat(snapshot.running()).as(snapshot.name() + " must have stopped").isFalse();
        }
    }
}
