package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [StaleQueuedJobPoller] (dispatch-seam spec §3 timing table `StaleAfter`,
/// §4): a `QUEUED` row older than the stale threshold reverts to `PENDING`;
/// a younger one is left alone; leader-gating.
class StaleQueuedJobPollerTest {

    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);

    private static String seedQueuedWithUpdatedAt(String tag, Instant updatedAt) {
        String id = seedWriteRow(Seed.of(code(tag)).withStatus("QUEUED"));
        DB.update(MSG_DISPATCH_JOBS).set(MSG_DISPATCH_JOBS.UPDATED_AT, updatedAt.atOffset(ZoneOffset.UTC))
                .where(MSG_DISPATCH_JOBS.ID.eq(id)).execute();
        return id;
    }

    @Test
    void aQueuedRowOlderThanStaleAfterRevertsWhileAYoungerOneIsLeftAlone() {
        Instant now = Instant.now();
        String stale = seedQueuedWithUpdatedAt("stale", now.minus(Duration.ofMinutes(10)));
        String fresh = seedQueuedWithUpdatedAt("fresh", now.minus(Duration.ofSeconds(5)));

        var poller = new StaleQueuedJobPoller(REPO, () -> true, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(5));
        List<String> reverted = poller.recoverOnce();

        assertThat(reverted).contains(stale).doesNotContain(fresh);
        assertThat(REPO.findById(stale).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(fresh).orElseThrow().status())
                .as("younger than the stale threshold; left QUEUED")
                .isEqualTo(DispatchJobStatus.QUEUED);
    }

    @Test
    void notLeaderRecoversNothing() {
        Instant now = Instant.now();
        String stale = seedQueuedWithUpdatedAt("notleaderstale", now.minus(Duration.ofMinutes(10)));

        var poller = new StaleQueuedJobPoller(REPO, () -> false, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(5));
        List<String> reverted = poller.recoverOnce();

        assertThat(reverted).isEmpty();
        assertThat(REPO.findById(stale).orElseThrow().status())
                .as("a non-leader tick must not recover — the status counter must not change")
                .isEqualTo(DispatchJobStatus.QUEUED);
    }
}
