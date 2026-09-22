package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;

/// [DispatchJobReaper] itself (dispatch-seam spec §7): its configured
/// liveness cutoff reaches [DispatchJobRepository#sweepStrandedSiblings]
/// correctly, its reason string carries the `"reaper"` marker an operator
/// greps for, and [DispatchJobReaper#start] actually runs the sweep on a
/// background thread rather than only when called synchronously. The
/// predicate itself (positional ordering, legacy `ERROR`, `NEXT_ON_ERROR`
/// exclusion, PROCESSING freshness) is pinned at the repository level in
/// [DispatchJobRepositoryTest].
class DispatchJobReaperTest {

    private static final DispatchJobRepository repo = new DispatchJobRepository(DS);

    @Test
    void reasonMarksTheRowsAsReaperResetsNotHookOrHumanResets() {
        assertThat(DispatchJobReaper.REASON).as("operators grep for this substring").contains("reaper");
    }

    @Test
    void sweepOnceUsesTheConfiguredLivenessCutoff() {
        String group = "grp-reaper-cutoff-" + RUN;
        String reaperCode = code("reapercutoff");
        Instant t = Instant.now().minusSeconds(120);
        seedWriteRow(Seed.of(reaperCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(1).withCreatedAt(t).withStatus("FAILED"));
        String tenSecondsStale = seedWriteRow(Seed.of(reaperCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(2).withCreatedAt(t.plusSeconds(1)).withStatus("PROCESSING")
                .withUpdatedAt(Instant.now().minusSeconds(10)));

        // A 5-second liveness cutoff: a row updated 10s ago is stale by that cutoff and gets swept.
        var reaper = new DispatchJobReaper(repo, Duration.ofMinutes(2), Duration.ofSeconds(5));
        List<String> reset = reaper.sweepOnce();
        assertThat(reset).contains(tenSecondsStale);
        assertThat(repo.findById(tenSecondsStale).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
    }

    @Test
    void startRunsTheSweepOnABackgroundThreadWithoutBeingCalledDirectly() throws InterruptedException {
        String group = "grp-reaper-start-" + RUN;
        String startCode = code("reaperstart");
        Instant t = Instant.now().minusSeconds(120);
        seedWriteRow(Seed.of(startCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(1).withCreatedAt(t).withStatus("FAILED"));
        String stranded = seedWriteRow(Seed.of(startCode).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(2).withCreatedAt(t.plusSeconds(1)).withStatus("QUEUED"));

        try (var reaper = new DispatchJobReaper(repo, Duration.ofMillis(50), Duration.ofMinutes(45))) {
            reaper.start();
            // Poll briefly for the background sweep to land — no explicit sweepOnce() call here.
            long deadline = System.currentTimeMillis() + 5000;
            DispatchJobStatus status = DispatchJobStatus.QUEUED;
            while (System.currentTimeMillis() < deadline && status == DispatchJobStatus.QUEUED) {
                Thread.sleep(50);
                status = repo.findById(stranded).orElseThrow().status();
            }
            assertThat(status).as("the started reaper reset the row on its own, not via a direct sweepOnce() call")
                    .isEqualTo(DispatchJobStatus.PENDING);
        }
    }

    /// Owner ruling 2026-09-22: the default liveness cutoff is 15 minutes (the
    /// old system's value), not the 45 the mediator's 3 × 15-minute worst case
    /// suggested — with the stale-QUEUED sweep gone this is the only automatic
    /// redrive, and the owner accepts redriving a second attempt still in flight.
    @Test
    void theDefaultProcessingCutoffIsFifteenMinutes() {
        assertThat(DispatchJobReaper.DEFAULT_PROCESSING_LIVE_AFTER).isEqualTo(Duration.ofMinutes(15));
    }
}
