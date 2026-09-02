package io.flowcatalyst.platform.scheduler;

import java.util.ArrayList;
import java.util.List;

/// An in-memory [DispatchPublisher] test double: records every batch handed
/// to it, and optionally fails every call (to pin the revert-on-failure
/// path) without touching any real queue.
final class FakeDispatchPublisher implements DispatchPublisher {

    private final List<List<PublishedMessage>> batches = new ArrayList<>();
    private final boolean fail;

    private FakeDispatchPublisher(boolean fail) {
        this.fail = fail;
    }

    static FakeDispatchPublisher succeeding() {
        return new FakeDispatchPublisher(false);
    }

    static FakeDispatchPublisher failing() {
        return new FakeDispatchPublisher(true);
    }

    @Override
    public void publish(List<PublishedMessage> batch) throws PublishException {
        if (fail) {
            throw new PublishException("simulated publish failure", null);
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
