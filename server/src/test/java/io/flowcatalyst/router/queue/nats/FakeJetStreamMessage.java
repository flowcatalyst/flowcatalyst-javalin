package io.flowcatalyst.router.queue.nats;

import io.flowcatalyst.router.observability.Warnings;

import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.impl.AckType;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsJetStreamMetaData;
import io.nats.client.support.Status;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/// Hand-written stub of a JetStream [Message] (CONVENTIONS §7: no mocking
/// library) — just enough to drive [NatsQueue#ack]/[NatsQueue#nack] and
/// observe what they called, without a live broker. `metaData()` throws
/// deliberately: [NatsQueue] never calls it again once a message is in
/// [NatsQueue#pending] — metadata is read once, at fetch time, in
/// [NatsQueue#handleFetched].
final class FakeJetStreamMessage implements Message {

    private final byte[] data;

    int ackCalls;
    int nakCalls;
    int termCalls;
    Duration lastNakDelay;
    /// Thrown from the next call to [#ack] or [#nak], then cleared — lets a
    /// test simulate a broker-side failure and assert it is swallowed.
    RuntimeException failWith;

    FakeJetStreamMessage(byte[] data) {
        this.data = data;
    }

    private void maybeFail() {
        if (failWith != null) {
            RuntimeException e = failWith;
            failWith = null;
            throw e;
        }
    }

    @Override
    public void ack() {
        maybeFail();
        ackCalls++;
    }

    @Override
    public void ackSync(Duration timeout) throws TimeoutException, InterruptedException {
        throw new UnsupportedOperationException("not exercised by NatsQueue");
    }

    @Override
    public void nak() {
        maybeFail();
        nakCalls++;
    }

    @Override
    public void nakWithDelay(Duration delay) {
        maybeFail();
        nakCalls++;
        lastNakDelay = delay;
    }

    @Override
    public void nakWithDelay(long delayMillis) {
        nakWithDelay(Duration.ofMillis(delayMillis));
    }

    @Override
    public void term() {
        termCalls++;
    }

    @Override
    public void inProgress() {
        throw new UnsupportedOperationException("NatsQueue never calls InProgress — §7.4");
    }

    @Override
    public boolean isJetStream() {
        return true;
    }

    @Override
    public String getSubject() {
        return "flowcatalyst.test";
    }

    @Override
    public String getReplyTo() {
        return null;
    }

    @Override
    public boolean hasHeaders() {
        return false;
    }

    @Override
    public Headers getHeaders() {
        return null;
    }

    @Override
    public boolean isStatusMessage() {
        return false;
    }

    @Override
    public Status getStatus() {
        return null;
    }

    @Override
    public byte[] getData() {
        return data;
    }

    /// Deprecated upstream, but still abstract on `Message`, so a stub has
    /// to implement it. Suppressed rather than left to trip the build's
    /// warning gate over a method we are only obliged to declare.
    @Override
    @SuppressWarnings("deprecation")
    public boolean isUtf8mode() {
        return false;
    }

    @Override
    public Subscription getSubscription() {
        return null;
    }

    @Override
    public String getSID() {
        return null;
    }

    @Override
    public Connection getConnection() {
        return null;
    }

    @Override
    public NatsJetStreamMetaData metaData() {
        throw new UnsupportedOperationException("NatsQueue reads metadata once, at fetch time");
    }

    @Override
    public AckType lastAck() {
        return null;
    }
}
