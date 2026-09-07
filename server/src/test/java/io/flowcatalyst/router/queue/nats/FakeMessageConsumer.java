package io.flowcatalyst.router.queue.nats;

import io.nats.client.MessageConsumer;
import io.nats.client.api.ConsumerInfo;

/// Hand-written stub of a [MessageConsumer] (CONVENTIONS §6: no mocking
/// library) — just enough to pin what [NatsQueue#close] does to the
/// standing listener (`docs/spec/router.md` §7.4: NATS is a genuine
/// subscription, not a poller) without a live broker. Every method
/// [NatsQueue] does not call throws, the same discipline
/// `FakeJetStreamMessage` uses.
final class FakeMessageConsumer implements MessageConsumer {

    int closeCalls;

    @Override
    public void close() {
        closeCalls++;
    }

    @Override
    public String getConsumerName() {
        throw new UnsupportedOperationException("not exercised by NatsQueue");
    }

    @Override
    public ConsumerInfo getConsumerInfo() {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public ConsumerInfo getCachedConsumerInfo() {
        throw new UnsupportedOperationException("not exercised by NatsQueue");
    }

    @Override
    public void stop() {
        throw new UnsupportedOperationException("not exercised by NatsQueue");
    }

    @Override
    public boolean isStopped() {
        throw new UnsupportedOperationException("not exercised by NatsQueue");
    }

    @Override
    public boolean isFinished() {
        throw new UnsupportedOperationException("not exercised by NatsQueue");
    }
}
