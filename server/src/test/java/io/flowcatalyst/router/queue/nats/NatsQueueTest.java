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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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
    @DisplayName("close stops the standing listener")
    void closeStopsTheStandingConsumer() {
        // Pins §7.4's listener model (owner ruling 2026-09-07: NATS must be
        // a genuine subscription, not a poller): [NatsQueue] holds one
        // standing `MessageConsumer` for the queue's whole life, so closing
        // the QUEUE must be what finally stops it — nothing else in
        // NatsQueue does.
        var consumer = new FakeMessageConsumer();
        var queue = new NatsQueue("nats-test", NatsQueueUri.parse("nats://localhost:4222?stream=S&consumer=C"),
                consumer);

        queue.close();

        assertThat(consumer.closeCalls).isOne();

        // Idempotent close must not close it a second time.
        queue.close();
        assertThat(consumer.closeCalls).isOne();
    }

    // ── poll: a genuine listener, not a poller ────────────────────────────
    //
    // `docs/spec/router.md` §7.4 (owner ruling 2026-09-07): the standing
    // consumer's handler pushes each message into [NatsQueue#buffer], a
    // `BlockingQueue` bounded at `max-messages`; [NatsQueue#poll] is an
    // untimed [java.util.concurrent.BlockingQueue#take] for the first
    // message plus a [java.util.concurrent.BlockingQueue#drainTo] for the
    // rest. These tests seed/read `buffer` directly (CONVENTIONS §6) rather
    // than faking the NATS client's `consume` machinery — the machinery
    // itself (a continuously refilled server-side pull, one message
    // delivered at a time to the handler) is a live-broker fact, not
    // reachable without a real connection; what IS reachable, and what
    // throughput depends on, is this class's own buffering and blocking
    // logic.

    private static NatsQueue queueWithMaxMessages(int maxMessages) {
        return new NatsQueue("nats-test",
                NatsQueueUri.parse("nats://localhost:4222?stream=S&consumer=C&max-messages=" + maxMessages),
                new FakeMessageConsumer());
    }

    @Test
    @DisplayName("already-buffered messages are returned in order, up to max, without waiting")
    void bufferedMessagesReturnedInOrderUpToMaxWithoutWaiting() throws InterruptedException {
        var queue = queueWithMaxMessages(10);
        var termOrder = new java.util.ArrayList<String>();
        var m1 = new FakeJetStreamMessage(new byte[0], "m1", termOrder);
        var m2 = new FakeJetStreamMessage(new byte[0], "m2", termOrder);
        var m3 = new FakeJetStreamMessage(new byte[0], "m3", termOrder);
        var m4 = new FakeJetStreamMessage(new byte[0], "m4", termOrder);
        var m5 = new FakeJetStreamMessage(new byte[0], "m5", termOrder);
        queue.buffer.put(m1);
        queue.buffer.put(m2);
        queue.buffer.put(m3);
        queue.buffer.put(m4);
        queue.buffer.put(m5);

        long startNanos = System.nanoTime();
        var result = queue.poll(3);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs).isLessThan(100);
        // Fake messages have no readable JetStream metadata
        // (`FakeJetStreamMessage`'s class doc), so every one classifies as
        // Malformed and is termed rather than delivered — classification
        // itself is not what this test is about (see
        // `malformedVerdictTermsTheMessage`). `termOrder` is how it proves
        // poll read exactly the first 3, in order, and left the rest — the
        // `delivered` list's own order is not usable here since it stays
        // empty for a Malformed batch.
        assertThat(result).isEqualTo(PollResult.empty());
        assertThat(termOrder).containsExactly("m1", "m2", "m3");
        assertThat(queue.buffer).hasSize(2);
    }

    @Test
    @DisplayName("poll on an empty buffer blocks until a message is handed in, then returns promptly")
    void pollOnEmptyBufferBlocksUntilMessageArrives() throws InterruptedException {
        var queue = queueWithMaxMessages(10);
        var msg = new FakeJetStreamMessage(new byte[0]);
        var pollReturned = new java.util.concurrent.CountDownLatch(1);
        var poller = new Thread(() -> {
            try {
                queue.poll(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                pollReturned.countDown();
            }
        });
        poller.start();
        try {
            // Give the poller time to genuinely park in `buffer.take()`.
            Thread.sleep(150);
            assertThat(pollReturned.getCount()).isEqualTo(1);

            queue.buffer.put(msg);
            boolean completed = pollReturned.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);

            assertThat(completed).isTrue();
        } finally {
            poller.interrupt();
            poller.join(1000);
        }
    }

    @Test
    @DisplayName("close while poll is blocked unblocks it promptly")
    void closeWhileBlockedUnblocksPollPromptly() throws InterruptedException {
        var queue = queueWithMaxMessages(10);
        var pollReturned = new java.util.concurrent.CountDownLatch(1);
        var poller = new Thread(() -> {
            try {
                queue.poll(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                pollReturned.countDown();
            }
        });
        poller.start();
        try {
            Thread.sleep(150);
            assertThat(pollReturned.getCount()).isEqualTo(1);

            long startNanos = System.nanoTime();
            queue.close();
            boolean completed = pollReturned.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(completed).isTrue();
            assertThat(elapsedMs).isLessThan(500);
        } finally {
            poller.join(1000);
        }
    }

    @Test
    @DisplayName("the handler blocks when the buffer is full, and unblocks after a poll")
    void handlerBlocksWhenBufferIsFullAndUnblocksAfterPoll() throws InterruptedException {
        // max-messages=1: the smallest buffer that can actually be filled.
        var queue = queueWithMaxMessages(1);
        var m1 = new FakeJetStreamMessage(new byte[0]);
        var m2 = new FakeJetStreamMessage(new byte[0]);
        queue.buffer.put(m1); // fills the buffer (capacity 1)

        var putCompleted = new java.util.concurrent.CountDownLatch(1);
        var putter = new Thread(() -> {
            try {
                queue.buffer.put(m2); // must block: no room
                putCompleted.countDown();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        putter.start();
        try {
            // The putter is standing in for the real MessageHandler
            // (`NatsQueue`'s class doc: "put blocking... is the
            // back-pressure"); it must still be blocked after a generous
            // wait, or the buffer was not actually bounded.
            boolean completedWhileFull = putCompleted.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(completedWhileFull).isFalse();

            queue.poll(10); // frees the one slot

            boolean completedAfterPoll = putCompleted.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(completedAfterPoll).isTrue();
        } finally {
            putter.join(1000);
        }
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

    // ── lastBrokerActivity (2026-09-07) ───────────────────────────────────
    //
    // `docs/spec/router.md` §3.2, §5 row 47: `poll()` now blocks untimed, so
    // `ConsumerSupervisor`'s stall watchdog needs independent evidence the
    // broker is alive rather than reading "poll has not returned" as a hang.
    // The test seam has no live `Connection` ([NatsQueue#connection] is
    // always `null` here), so [NatsQueue#lastBrokerActivity] falls straight
    // through to [NatsQueue]'s own `lastActivity` tracking — the branch that
    // actually needs a test, since the CONNECTED-status branch is only
    // reachable with a live broker (see the class doc's "not testable
    // without a live broker" list).

    @Test
    @DisplayName("lastBrokerActivity is seeded at construction, so a fresh queue is never judged stale before its first delivery")
    void lastBrokerActivitySeededAtConstruction() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var queue = new NatsQueue("nats-test",
                NatsQueueUri.parse("nats://localhost:4222?stream=S&consumer=C"), new FakeMessageConsumer(), clock);

        assertThat(queue.lastBrokerActivity()).hasValue(clock.instant());
    }

    @Test
    @DisplayName("lastBrokerActivity advances to the moment poll delivers a message, and stays behind it until the next one")
    void lastBrokerActivityAdvancesOnDeliveredMessage() throws InterruptedException {
        // Mutant: stop recording activity in poll() → this test fails, since
        // lastBrokerActivity() would stay pinned at the construction-time
        // seed instead of advancing past it.
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var queue = new NatsQueue("nats-test",
                NatsQueueUri.parse("nats://localhost:4222?stream=S&consumer=C&max-messages=10"),
                new FakeMessageConsumer(), clock);
        var m1 = new FakeJetStreamMessage(new byte[0]);
        queue.buffer.put(m1);

        clock.advance(Duration.ofSeconds(30));
        queue.poll(10);

        assertThat(queue.lastBrokerActivity())
                .as("a delivered message is fresh broker evidence at the instant it was observed")
                .hasValue(clock.instant());

        // A second poll with nothing buffered must NOT advance it again —
        // otherwise a genuinely hung poll would look alive forever just by
        // being asked.
        var pollReturned = new java.util.concurrent.CountDownLatch(1);
        var afterFirstDelivery = clock.instant();
        var poller = new Thread(() -> {
            try {
                queue.poll(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                pollReturned.countDown();
            }
        });
        poller.start();
        try {
            Thread.sleep(150); // parked in buffer.take(); nothing delivered
            clock.advance(stallGap());

            assertThat(queue.lastBrokerActivity())
                    .as("no NEW delivery yet — activity must not have advanced past the last real one")
                    .hasValue(afterFirstDelivery);
        } finally {
            poller.interrupt();
            poller.join(1000);
        }
    }

    /// A gap comfortably past any real stall threshold this suite cares
    /// about, without depending on `ConsumerSupervisor`'s constant from a
    /// different package.
    private static Duration stallGap() {
        return Duration.ofSeconds(90);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
