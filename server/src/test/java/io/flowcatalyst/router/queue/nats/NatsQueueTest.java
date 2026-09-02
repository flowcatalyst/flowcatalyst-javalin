package io.flowcatalyst.router.queue.nats;

import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer.PollResult;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [NatsQueue] behaviour that does not require a live NATS server
/// (`docs/spec/router.md` §7.1, §7.4). Everything reachable without a
/// broker connection is tested here — see the class doc for what is NOT.
///
/// Not testable without a live broker (documented here rather than skipped
/// silently, per the task's testing instructions): the actual JetStream
/// `Fetch` call in [NatsQueue#poll] (batching, blocking wait, real message
/// metadata), stream/consumer provisioning in the constructor, [NatsQueue#metrics]
/// against a real `ConsumerInfo`, and the constructor's URI-to-connection
/// wiring end to end.
class NatsQueueTest {

    private static final byte[] VALID_PAYLOAD =
            "{\"id\":\"msg_1\",\"mediationType\":\"HTTP\",\"mediationTarget\":\"https://example.test/hook\",\"dispatchMode\":\"IMMEDIATE\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private static NatsQueue testQueue() {
        return new NatsQueue("nats-test", NatsQueueUri.parse("nats://localhost:4222?stream=S&consumer=C"));
    }

    // --- identifier -----------------------------------------------------

    @Test
    @DisplayName("a connection whose stream cannot be provisioned is closed, not stranded")
    void failedProvisioningClosesTheConnection() {
        // Nats.connect has already succeeded at this point, so the connection
        // is live and nothing holds a reference to it yet. QueueFactory logs
        // the provisioning failure and returns empty, and the reconfigure loop
        // retries the same queue on the next config poll — so a stream name
        // the account may not create leaks one reconnecting connection per
        // poll, without bound, for the life of the process.
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var boom = new IOException("no jetstream");

        assertThatThrownBy(() -> NatsQueue.adopting(closed::incrementAndGet, () -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(closed.get()).isOne();
    }

    @Test
    @DisplayName("a connection that provisions successfully stays open")
    void successfulProvisioningKeepsTheConnection() throws Exception {
        var closed = new java.util.concurrent.atomic.AtomicInteger();

        var provisioned = NatsQueue.adopting(closed::incrementAndGet, () -> "ready");

        assertThat(provisioned).isEqualTo("ready");
        assertThat(closed.get()).isZero();
    }

    @Test
    @DisplayName("identifier is stream/consumer, matching the historical Go format")
    void identifierIsStreamSlashConsumer() {
        var cfg = NatsQueueUri.parse("nats://localhost:4222?stream=ORDERS&consumer=fc-orders");
        var queue = new NatsQueue(cfg.identifier(), cfg);

        assertThat(queue.identifier()).isEqualTo("ORDERS/fc-orders");
    }

    // --- classify (fetch decision) --------------------------------------

    @Test
    @DisplayName("a message whose metadata failed to read is termed regardless of its payload")
    void metadataFailureIsTermedEvenWithValidPayload() {
        var outcome = NatsQueue.classify(false, 1L, "STREAM", VALID_PAYLOAD);

        assertThat(outcome).isInstanceOf(FetchOutcome.Malformed.class);
    }

    @Test
    @DisplayName("a payload that is not valid Message JSON classifies as malformed")
    void malformedJsonClassifies() {
        byte[] garbage = "not json at all".getBytes(StandardCharsets.UTF_8);

        var outcome = NatsQueue.classify(true, 1L, "STREAM", garbage);

        assertThat(outcome).isInstanceOf(FetchOutcome.Malformed.class);
    }

    @Test
    @DisplayName("an empty payload classifies as malformed")
    void emptyPayloadClassifies() {
        var outcome = NatsQueue.classify(true, 1L, "STREAM", new byte[0]);

        assertThat(outcome).isInstanceOf(FetchOutcome.Malformed.class);
    }

    @Test
    @DisplayName("a well-formed message is delivered with a stream:seq receipt and a stream-seq broker id")
    void wellFormedMessageIsDelivered() {
        var outcome = NatsQueue.classify(true, 42L, "FLOWCATALYST", VALID_PAYLOAD);

        assertThat(outcome).isInstanceOf(FetchOutcome.Deliver.class);
        var deliver = (FetchOutcome.Deliver) outcome;
        assertThat(deliver.receipt()).isEqualTo("FLOWCATALYST:42");
        assertThat(deliver.brokerMessageId()).isEqualTo("42");
        assertThat(deliver.payload().id()).isEqualTo("msg_1");
        assertThat(deliver.payload().mediationType()).isEqualTo(MediationType.HTTP);
        assertThat(deliver.payload().dispatchMode()).isEqualTo(DispatchMode.IMMEDIATE);
    }

    @Test
    @DisplayName("a redelivery keeps the SAME broker id, so the tracker sees a redelivery and not a rival copy")
    void redeliveryKeepsTheSameBrokerId() {
        // This test used to assert the opposite, and passed — on code that
        // silently turned at-least-once into at-most-once. A redelivery
        // carries a fresh CONSUMER sequence; when that fed the broker id, the
        // tracker read the redelivery as a different copy of the message (an
        // external requeue) and ACK-deleted it, so JetStream destroyed its own
        // copy on every ack-wait lapse. Both identities must therefore come
        // from the STREAM sequence, which every delivery of a message shares.
        //
        // Only the stream sequence is an input at all now: the consumer
        // sequence was removed from `classify` rather than left unused, so the
        // mistake cannot be made again from inside this method.
        var first = (FetchOutcome.Deliver) NatsQueue.classify(true, 42L, "FLOWCATALYST", VALID_PAYLOAD);
        var redelivered = (FetchOutcome.Deliver) NatsQueue.classify(true, 42L, "FLOWCATALYST", VALID_PAYLOAD);

        assertThat(redelivered.brokerMessageId()).isEqualTo(first.brokerMessageId());
        assertThat(redelivered.receipt()).isEqualTo(first.receipt());

        // ...and a genuinely different message still gets a different id.
        var other = (FetchOutcome.Deliver) NatsQueue.classify(true, 43L, "FLOWCATALYST", VALID_PAYLOAD);
        assertThat(other.brokerMessageId()).isNotEqualTo(first.brokerMessageId());
    }

    // --- effectiveBatch ---------------------------------------------------

    @Test
    @DisplayName("a requested batch within the configured cap is used as-is")
    void batchWithinCapIsUsedAsIs() {
        assertThat(NatsQueue.effectiveBatch(5, 10)).isEqualTo(5);
    }

    @Test
    @DisplayName("a requested batch over the configured cap is capped")
    void batchOverCapIsCapped() {
        assertThat(NatsQueue.effectiveBatch(50, 10)).isEqualTo(10);
    }

    @Test
    @DisplayName("a non-positive requested batch falls back to the configured cap")
    void nonPositiveBatchFallsBackToCap() {
        assertThat(NatsQueue.effectiveBatch(0, 10)).isEqualTo(10);
        assertThat(NatsQueue.effectiveBatch(-3, 10)).isEqualTo(10);
    }

    // --- stopped after close ---------------------------------------------

    @Test
    @DisplayName("poll answers Stopped forever after close")
    void pollAnswersStoppedAfterClose() throws InterruptedException {
        var queue = testQueue();

        queue.close();

        assertThat(queue.poll(10)).isEqualTo(PollResult.STOPPED);
        assertThat(queue.poll(10)).isEqualTo(PollResult.STOPPED);
    }

    @Test
    @DisplayName("close is idempotent")
    void closeIsIdempotent() {
        var queue = testQueue();

        queue.close();
        queue.close();
    }

    @Test
    @DisplayName("close clears pending deliveries")
    void closeClearsPending() {
        var queue = testQueue();
        queue.pending.put("STREAM:1", new FakeJetStreamMessage(VALID_PAYLOAD));

        queue.close();

        assertThat(queue.pending).isEmpty();
    }

    // --- ack --------------------------------------------------------------

    @Test
    @DisplayName("ack of a known receipt acks the underlying message, removes it, and counts it")
    void ackKnownReceipt() {
        var queue = testQueue();
        var fake = new FakeJetStreamMessage(VALID_PAYLOAD);
        queue.pending.put("STREAM:1", fake);
        var queued = QueuedMessage.of(minimalMessage(), "1:1", "STREAM:1", "nats-test");

        queue.ack(queued);

        assertThat(fake.ackCalls).isEqualTo(1);
        assertThat(queue.pending).doesNotContainKey("STREAM:1");
        assertThat(queue.acked.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("ack of an unknown receipt is a no-op: no throw, no count")
    void ackUnknownReceiptIsNoOp() {
        var queue = testQueue();
        var queued = QueuedMessage.of(minimalMessage(), "1:1", "STREAM:does-not-exist", "nats-test");

        queue.ack(queued);

        assertThat(queue.acked.get()).isZero();
    }

    @Test
    @DisplayName("ack never throws even when the underlying broker call fails")
    void ackNeverThrows() {
        var queue = testQueue();
        var fake = new FakeJetStreamMessage(VALID_PAYLOAD);
        fake.failWith = new RuntimeException("broker unreachable");
        queue.pending.put("STREAM:1", fake);
        var queued = QueuedMessage.of(minimalMessage(), "1:1", "STREAM:1", "nats-test");

        queue.ack(queued);

        // Removed from pending regardless of the broker call's outcome —
        // the delivery is not retried by re-polling it a second time.
        assertThat(queue.pending).doesNotContainKey("STREAM:1");
        assertThat(queue.acked.get()).isZero();
    }

    // --- nack -------------------------------------------------------------

    @Test
    @DisplayName("nack with a positive delay NAKs with that delay and counts it as a failure")
    void nackWithPositiveDelay() {
        var queue = testQueue();
        var fake = new FakeJetStreamMessage(VALID_PAYLOAD);
        queue.pending.put("STREAM:1", fake);
        var queued = QueuedMessage.of(minimalMessage(), "1:1", "STREAM:1", "nats-test");

        queue.nack(queued, Duration.ofSeconds(30));

        assertThat(fake.nakCalls).isEqualTo(1);
        assertThat(fake.lastNakDelay).isEqualTo(Duration.ofSeconds(30));
        assertThat(queue.nacked.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("nack with a zero or negative delay NAKs immediately")
    void nackWithNonPositiveDelayNaksImmediately() {
        var queue = testQueue();
        var fake = new FakeJetStreamMessage(VALID_PAYLOAD);
        queue.pending.put("STREAM:1", fake);
        var queued = QueuedMessage.of(minimalMessage(), "1:1", "STREAM:1", "nats-test");

        queue.nack(queued, Duration.ZERO);

        assertThat(fake.nakCalls).isEqualTo(1);
        assertThat(fake.lastNakDelay).isNull();
    }

    @Test
    @DisplayName("nack with a null delay NAKs immediately, same as zero")
    void nackWithNullDelayNaksImmediately() {
        var queue = testQueue();
        var fake = new FakeJetStreamMessage(VALID_PAYLOAD);
        queue.pending.put("STREAM:1", fake);
        var queued = QueuedMessage.of(minimalMessage(), "1:1", "STREAM:1", "nats-test");

        queue.nack(queued, null);

        assertThat(fake.nakCalls).isEqualTo(1);
        assertThat(fake.lastNakDelay).isNull();
    }

    @Test
    @DisplayName("nack of an unknown receipt is a no-op: no throw, no count")
    void nackUnknownReceiptIsNoOp() {
        var queue = testQueue();
        var queued = QueuedMessage.of(minimalMessage(), "1:1", "STREAM:does-not-exist", "nats-test");

        queue.nack(queued, Duration.ofSeconds(5));

        assertThat(queue.nacked.get()).isZero();
    }

    @Test
    @DisplayName("nack never throws even when the underlying broker call fails")
    void nackNeverThrows() {
        var queue = testQueue();
        var fake = new FakeJetStreamMessage(VALID_PAYLOAD);
        fake.failWith = new RuntimeException("broker unreachable");
        queue.pending.put("STREAM:1", fake);
        var queued = QueuedMessage.of(minimalMessage(), "1:1", "STREAM:1", "nats-test");

        queue.nack(queued, Duration.ofSeconds(5));

        assertThat(queue.pending).doesNotContainKey("STREAM:1");
        assertThat(queue.nacked.get()).isZero();
    }

    // --- metrics without a broker ------------------------------------------

    @Test
    @DisplayName("metrics is empty when there is no live connection")
    void metricsEmptyWithoutConnection() {
        var queue = testQueue();

        assertThat(queue.metrics()).isEmpty();
    }

    private static Message minimalMessage() {
        return new Message("msg_1", null, null, null, MediationType.HTTP,
                "https://example.test/hook", null, false, DispatchMode.IMMEDIATE);
    }

    // ── The ACTION, not just the classification ──────────────────────────
    //
    // The classification tests above pass whether or not term() is ever
    // called. These drive the switch that acts on a verdict, which is where
    // a malformed message either leaves the stream or redelivers forever.

    @Test
    @DisplayName("a malformed verdict actually terms the message")
    void malformedVerdictTermsTheMessage() {
        var queue = testQueue();
        var msg = new FakeJetStreamMessage("not json at all".getBytes(StandardCharsets.UTF_8));
        var delivered = new java.util.ArrayList<io.flowcatalyst.router.pool.QueuedMessage>();

        queue.apply(new FetchOutcome.Malformed(), msg, delivered);

        assertThat(msg.termCalls).isOne();
        assertThat(msg.ackCalls).isZero();
        assertThat(msg.nakCalls).isZero();
        assertThat(delivered).isEmpty();
    }

    @Test
    @DisplayName("a term that fails at the broker is swallowed, not thrown")
    void failingTermIsSwallowed() {
        // Poll must not die because one disposal failed; the message will
        // redeliver and be termed again.
        var queue = testQueue();
        var msg = new FakeJetStreamMessage(new byte[0]);
        msg.failWith = new IllegalStateException("broker gone");

        queue.apply(new FetchOutcome.Malformed(), msg, new java.util.ArrayList<>());
    }

    @Test
    @DisplayName("a deliver verdict yields the message and holds it pending its acknowledgement")
    void deliverVerdictHoldsPending() {
        var queue = testQueue();
        var msg = new FakeJetStreamMessage(VALID_PAYLOAD);
        var delivered = new java.util.ArrayList<io.flowcatalyst.router.pool.QueuedMessage>();
        var verdict = (FetchOutcome.Deliver) NatsQueue.classify(true, 42L, "STREAM", VALID_PAYLOAD);

        queue.apply(verdict, msg, delivered);

        assertThat(msg.termCalls).isZero();
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().id()).isEqualTo("msg_1");
        // Held so the eventual ack can find the jnats message it belongs to.
        assertThat(queue.pending).containsKey(verdict.receipt());
    }

}
