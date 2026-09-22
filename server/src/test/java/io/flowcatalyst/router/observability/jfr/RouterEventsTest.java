package io.flowcatalyst.router.observability.jfr;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.manager.QueueBroker;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Acknowledger;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.testjfr.Recorded;
import jdk.jfr.consumer.RecordedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The flight-recorder events, read back out of a real recording.
///
/// Every message-loss defect this router has shipped was invisible from the
/// outside — the message left the broker, which is what normally happens. The
/// point of these events is to make "what happened to message X, and who
/// decided" answerable after the fact, so the tests assert on the recorded
/// fields rather than on the calls that produced them.
class RouterEventsTest {

    private final InFlightTracker tracker = new InFlightTracker(Clock.systemUTC());
    private final FakeAcknowledger queue = new FakeAcknowledger();
    private final QueueBroker broker = new QueueBroker(Map.of("queue-1", queue), tracker);

    @Test
    @DisplayName("an ack records who decided, not just that it happened")
    void ackRecordsItsReason() throws Exception {
        var events = Recorded.from(MessageSettledEvent.class,
                () -> broker.ack(message("m1"), "delivered"));

        var settled = only(events);
        assertThat(settled.getString("messageId")).isEqualTo("m1");
        assertThat(settled.getString("queue")).isEqualTo("queue-1");
        assertThat(settled.getString("action")).isEqualTo("ack");
        // The field that separates a correct ack from a catastrophic one.
        assertThat(settled.getString("reason")).isEqualTo("delivered");
        assertThat(settled.getBoolean("brokerConfirmed")).isTrue();
    }

    @Test
    @DisplayName("an unconfirmed ack is recorded as unconfirmed")
    void unconfirmedAckIsVisible() throws Exception {
        // The broker did not confirm, so it may redeliver — the one case
        // where an ack does not mean the message is gone.
        queue.confirmAcks = false;

        var events = Recorded.from(MessageSettledEvent.class,
                () -> broker.ack(message("m1"), "delivered"));

        assertThat(only(events).getBoolean("brokerConfirmed")).isFalse();
    }

    @Test
    @DisplayName("a nack records the delay it asked for")
    void nackRecordsItsDelay() throws Exception {
        var events = Recorded.from(MessageSettledEvent.class,
                () -> broker.nack(message("m1"), Duration.ofSeconds(30), "target-unavailable"));

        var settled = only(events);
        assertThat(settled.getString("action")).isEqualTo("nack");
        assertThat(settled.getString("reason")).isEqualTo("target-unavailable");
        assertThat(settled.getDuration("requestedDelay")).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("a release is recorded even though the broker is never told")
    void releaseIsRecorded() throws Exception {
        // Precisely the case that was invisible: no broker call, so no broker
        // metric, no log, and nothing to distinguish it from a message that
        // is still being worked on.
        var events = Recorded.from(MessageSettledEvent.class,
                () -> broker.release(message("m1")));

        var settled = only(events);
        assertThat(settled.getString("action")).isEqualTo("release");
        assertThat(settled.getString("reason")).isEqualTo("abandoned");
        assertThat(settled.getBoolean("brokerConfirmed")).isFalse();
    }

    @Test
    @DisplayName("an ack on a deregistered queue is recorded, not silently skipped")
    void ackWithNoConsumerIsRecorded() throws Exception {
        var orphan = new QueueBroker(Map.<String, Acknowledger>of(), tracker);

        var events = Recorded.from(MessageSettledEvent.class,
                () -> orphan.ack(message("m1"), "delivered"));

        assertThat(only(events).getBoolean("brokerConfirmed")).isFalse();
    }

    @Test
    @DisplayName("a disabled event records nothing at all")
    void disabledRecordsNothing() throws Exception {
        // shouldCommit() gates every field write, so an operator who does not
        // want a per-message event can turn it off and the delivery path stops
        // paying for it. Worth pinning because these are enabled by default:
        // being able to switch them off is the other half of that choice.
        var events = Recorded.from(GroupDecisionEvent.class,
                () -> broker.ack(message("m1"), "delivered"));

        assertThat(events).isEmpty();
    }

    private static RecordedEvent only(List<RecordedEvent> events) {
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private static QueuedMessage message(String id) {
        return QueuedMessage.of(
                new Message(id, "", null, null, MediationType.HTTP, "https://x.test/h",
                        null, false, DispatchMode.NEXT_ON_ERROR),
                "broker-" + id, "receipt-" + id, "queue-1");
    }

    private static final class FakeAcknowledger implements Acknowledger {
        boolean confirmAcks = true;

        @Override
        public String identifier() {
            return "queue-1";
        }

        @Override
        public boolean ack(QueuedMessage message) {
            return confirmAcks;
        }

        @Override
        public boolean honoursDelayedReturn() {
            return true;
        }

        @Override
        public void defer(QueuedMessage message, Duration delay) {
            nack(message, delay);
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }
    }
}
