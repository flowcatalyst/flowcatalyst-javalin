package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

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
}
