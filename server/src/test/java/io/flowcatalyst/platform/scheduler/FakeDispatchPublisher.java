package io.flowcatalyst.platform.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// An in-memory [DispatchPublisher] test double: records every batch handed
/// to it, and optionally fails all or part of a call (to pin both the
/// whole-batch and the partial revert-on-failure paths, ruling O2) without
/// touching any real queue.
final class FakeDispatchPublisher implements DispatchPublisher {

    private final List<List<PublishedMessage>> batches = new ArrayList<>();
    private final boolean failWhole;
    private final Set<String> failJobIds;

    private FakeDispatchPublisher(boolean failWhole, Set<String> failJobIds) {
        this.failWhole = failWhole;
        this.failJobIds = failJobIds;
    }

    static FakeDispatchPublisher succeeding() {
        return new FakeDispatchPublisher(false, Set.of());
    }

    /// Reports the WHOLE batch as unpublished, exactly as
    /// [PostgresQueuePublisher] and [NoopPublisher]'s failure mode would (ruling
    /// O2's "still effectively all-or-nothing in practice" carve-out) — the
    /// fixture [PendingJobPollerTest]'s pre-existing whole-batch-revert test uses.
    static FakeDispatchPublisher failing() {
        return new FakeDispatchPublisher(true, Set.of());
    }

    /// Publishes every job NOT in `jobIds` and reports only `jobIds` as
    /// unpublished — the shape a chunked [SqsDispatchPublisher] partial
    /// failure actually takes, for pinning ruling O2's partial-revert
    /// contract at the [PendingJobPoller] level without standing up SQS.
    static FakeDispatchPublisher failingForJobIds(Set<String> jobIds) {
        return new FakeDispatchPublisher(false, jobIds);
    }

    @Override
    public void publish(List<PublishedMessage> batch) throws PublishException {
        if (failWhole) {
            List<String> ids = batch.stream().map(PublishedMessage::jobId).toList();
            throw new PublishException("simulated whole-batch publish failure", null, ids);
        }
        if (!failJobIds.isEmpty()) {
            List<String> failed = batch.stream().map(PublishedMessage::jobId).filter(failJobIds::contains).toList();
            if (!failed.isEmpty()) {
                batches.add(batch.stream().filter(m -> !failJobIds.contains(m.jobId())).toList());
                throw new PublishException("simulated partial publish failure", null, failed);
            }
        }
        batches.add(List.copyOf(batch));
    }

    List<List<PublishedMessage>> batches() {
        return List.copyOf(batches);
    }

    List<PublishedMessage> lastBatch() {
        return batches.getLast();
    }
}
