package io.flowcatalyst.router.lifecycle;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.queue.Acknowledger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/// Notices messages that have been owned too long without progressing
/// (`docs/spec/router.md` §4.6).
///
/// ### What "stalled" means here, and what it does not
///
/// A stalled message is one this process claimed and then stopped doing
/// anything about — not one that is failing, and not one that is slow. Both
/// of those are visible elsewhere: a failing delivery moves the circuit
/// breaker, and a slow one shows in the latency windows. A stall is the case
/// with **no** signal anywhere, which is exactly why it needs a detector.
///
/// **Retrying entries are excluded.** `attempts > 0` means the message is
/// legitimately working through its backoff, which can far exceed the stall
/// threshold; flagging those would bury the real stalls in noise from a
/// target that is merely down.
public final class StallDetector {

    private static final Logger log = LoggerFactory.getLogger(StallDetector.class);

    /// How long an un-retried message may be owned before it is suspicious
    /// (spec constant 40).
    public static final Duration STALL_THRESHOLD = Duration.ofSeconds(300);

    /// How long before a stalled message may be forced back to its queue —
    /// only when [Config#forceNack] is on.
    public static final Duration FORCE_NACK_AFTER = Duration.ofSeconds(600);

    /// Delay applied when a stalled message is forced back.
    public static final Duration FORCE_NACK_DELAY = Duration.ofSeconds(30);

    /// @param forceNack whether to return stalled messages to their queue.
    ///                  **Off by default, deliberately.** A stall is a
    ///                  symptom of something this process does not
    ///                  understand, and returning the message makes it
    ///                  another instance's problem while destroying the
    ///                  evidence. Reporting is the safe default; forcing is
    ///                  an operator's decision.
    public record Config(boolean forceNack, Duration stallThreshold, Duration forceNackAfter) {

        public static final Config REPORT_ONLY = new Config(false, STALL_THRESHOLD, FORCE_NACK_AFTER);

        public Config {
            stallThreshold = stallThreshold == null ? STALL_THRESHOLD : stallThreshold;
            forceNackAfter = forceNackAfter == null ? FORCE_NACK_AFTER : forceNackAfter;
        }
    }

    /// What one sweep found, so a caller can log or assert it without
    /// reaching into the tracker.
    ///
    /// @param stalled  entries past the threshold
    /// @param released entries forced back to their queue
    public record Sweep(List<InFlightMessage> stalled, int released) {

        public Sweep {
            stalled = List.copyOf(stalled);
        }
    }

    private final InFlightTracker tracker;
    private final Warnings warnings;
    private final Function<String, Acknowledger> queues;
    private final Config config;
    private final Clock clock;

    public StallDetector(InFlightTracker tracker, Warnings warnings,
                         Function<String, Acknowledger> queues, Config config, Clock clock) {
        this.tracker = tracker;
        this.warnings = warnings;
        this.queues = queues;
        this.config = config;
        this.clock = clock;
    }

    /// Examines everything currently owned and reports what has stalled.
    ///
    /// Safe to call on a schedule: a message that is still stalled on the
    /// next sweep is reported again, which is what an operator watching a
    /// warning feed wants — a stall that stops being mentioned reads as
    /// resolved.
    public Sweep sweep() {
        var now = clock.instant();
        var stalled = tracker.snapshot().stream()
                .filter(entry -> !entry.retrying())
                .filter(entry -> entry.elapsedSeconds(now) >= config.stallThreshold().toSeconds())
                .toList();

        int released = 0;
        for (var entry : stalled) {
            warnings.raise(Warnings.Severity.WARNING, "STALL",
                    "Message " + entry.messageId() + " stalled for " + entry.elapsedSeconds(now)
                            + "s in pool " + describePool(entry));
            if (shouldForce(entry, now) && forceBack(entry)) {
                released++;
            }
        }
        return new Sweep(stalled, released);
    }

    /// An unresolved pool code reads better as a placeholder than as an
    /// empty gap in a warning an operator is scanning.
    private static String describePool(InFlightMessage entry) {
        return entry.poolCode().isEmpty() ? "(none named)" : entry.poolCode();
    }

    private boolean shouldForce(InFlightMessage entry, java.time.Instant now) {
        return config.forceNack() && entry.elapsedSeconds(now) >= config.forceNackAfter().toSeconds();
    }

    /// Returns one stalled message to its queue and releases ownership.
    ///
    /// Order matters: the queue is nacked **first**, and the tracker entry is
    /// released only if that succeeded. Releasing first would mean a failed
    /// nack left the message owned by nobody and still invisible at the
    /// broker — lost until its visibility lapses, with nothing tracking it.
    private boolean forceBack(InFlightMessage entry) {
        var queue = queues.apply(entry.queueIdentifier());
        if (queue == null) {
            log.warn("cannot release stalled message {}: queue {} is no longer registered",
                    entry.messageId(), entry.queueIdentifier());
            return false;
        }
        try {
            queue.nack(toQueued(entry), FORCE_NACK_DELAY);
        } catch (RuntimeException e) {
            // Keep the entry; the next sweep tries again.
            log.warn("could not release stalled message {}", entry.messageId(), e);
            return false;
        }
        tracker.remove(entry.messageId());
        log.info("released stalled message {} back to queue {}", entry.messageId(), entry.queueIdentifier());
        return true;
    }

    /// The tracker holds a snapshot, not the original message, so a nack is
    /// reconstructed from what it kept — which is exactly the identity and
    /// the freshest receipt handle a nack needs.
    private static io.flowcatalyst.router.pool.QueuedMessage toQueued(InFlightMessage entry) {
        return new io.flowcatalyst.router.pool.QueuedMessage(
                new io.flowcatalyst.router.wire.Message(entry.messageId(), entry.poolCode(), null, null,
                        io.flowcatalyst.router.wire.MediationType.HTTP, "", 
                        entry.messageGroupId().isEmpty() ? null : entry.messageGroupId(),
                        false, io.flowcatalyst.platform.shared.dispatch.DispatchMode.IMMEDIATE),
                entry.brokerMessageId(), entry.receiptHandle(), entry.queueIdentifier(), entry.attempts());
    }
}
