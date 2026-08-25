package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.queue.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/// Watches the poll loops and rebuilds the ones that have stopped polling
/// (`docs/spec/router.md` §4.4).
///
/// A consumer can stop making progress without failing: a broker connection
/// that is open but dead, a long-poll that never returns, a loop that exited
/// on a stopped consumer. None of those raise an error anyone sees — the
/// queue simply goes quiet. The only reliable signal is that its heartbeat
/// has stopped advancing, which is why [ConsumerLoop] refuses to heartbeat on
/// a failed poll: a heartbeat has to mean *progress*, or this watchdog is
/// blind.
public final class ConsumerSupervisor {

    private static final Logger log = LoggerFactory.getLogger(ConsumerSupervisor.class);

    /// No heartbeat for this long means stalled (spec constant 40/46).
    public static final Duration STALL_THRESHOLD = Duration.ofSeconds(60);

    /// Pause before rebuilding, so a broker that has just refused every
    /// consumer at once is not immediately hit by all of them again
    /// (constant 3).
    public static final Duration RESTART_DELAY = Duration.ofSeconds(5);

    /// Restart attempts after which the warning escalates (constant 4).
    /// Repeated restarts are the platform failing to fix itself, and at some
    /// point that is not a warning any more.
    public static final int CRITICAL_AFTER_ATTEMPTS = 10;

    /// Restart attempts per queue, cleared when the queue recovers. Only a
    /// *successful* rebuild counts — see [#restart].
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    private final Warnings warnings;
    private final Clock clock;
    private final Duration restartDelay;

    public ConsumerSupervisor(Warnings warnings, Clock clock) {
        this(warnings, clock, RESTART_DELAY);
    }

    /// `restartDelay` is injectable because it is real elapsed time — a test
    /// asserting escalation should not have to wait out eleven of them.
    public ConsumerSupervisor(Warnings warnings, Clock clock, Duration restartDelay) {
        this.warnings = warnings;
        this.clock = clock;
        this.restartDelay = restartDelay;
    }

    /// Whether a heartbeat has stopped advancing.
    ///
    /// Takes the heartbeat rather than the loop: judging liveness needs one
    /// timestamp, and depending on the whole loop would mean a test had to
    /// run one to ask the question.
    ///
    /// A loop that has **never** polled is not stalled — it may simply not
    /// have started yet, and treating a cold start as a stall would restart
    /// every consumer moments after boot.
    public boolean stalled(Optional<Instant> lastPoll) {
        return lastPoll
                .map(last -> Duration.between(last, clock.instant()).compareTo(STALL_THRESHOLD) > 0)
                .orElse(false);
    }

    /// Convenience for the supervising loop, which holds the [ConsumerLoop].
    public boolean stalled(ConsumerLoop loop) {
        return stalled(loop.lastPoll());
    }

    /// Records that a queue is polling again, so its next stall starts from
    /// zero rather than inheriting an old count.
    public void recovered(String queueName) {
        if (attempts.remove(queueName) != null) {
            log.info("consumer {} is polling again; restart count cleared", queueName);
        }
    }

    /// Attempts count for a queue, for the monitoring surface.
    public int restartAttempts(String queueName) {
        var counter = attempts.get(queueName);
        return counter == null ? 0 : counter.get();
    }

    /// Rebuilds a stalled consumer.
    ///
    /// **Every attempt is counted, successful or not** (owner ruling
    /// 2026-08-25, §13 Q28 — a deliberate deviation from Go).
    ///
    /// Go increments only on a *successful* rebuild, which inverts the
    /// escalation it exists for: a consumer that can never be rebuilt — bad
    /// credentials, a deleted queue, a wrong URI — would warn at WARNING
    /// forever, once per tick, and never reach CRITICAL. The failure mode
    /// most needing a human stayed the quietest, while one that kept
    /// rebuilding and re-stalling escalated properly. The counter answers
    /// "how many times has the platform tried and failed to fix this?", and
    /// a failed rebuild is more of that, not less.
    ///
    /// @return the replacement, or empty when it could not be built
    public Optional<Consumer> restart(String queueName, QueueConfig config, Consumer stalled,
                                      RouterManager.ConsumerFactory factory) throws InterruptedException {
        var attempt = attempts.computeIfAbsent(queueName, ignored -> new AtomicInteger()).incrementAndGet();

        Thread.sleep(restartDelay);

        // Stop the old one first. Its in-flight deliveries are aborted and its
        // ordered groups parked; redelivery resumes them, which is the whole
        // reason a stalled consumer can be replaced at all rather than having
        // to be drained.
        stalled.close();

        var replacement = factory.create(config);
        // The two outcomes point at different causes and so read differently:
        // a rebuild that keeps succeeding suggests broker or network health,
        // one that cannot rebuild at all suggests configuration.
        warnings.raise(severityFor(attempt), "CONSUMER_HEALTH", replacement.isPresent()
                ? "Consumer " + queueName + " was stalled and has been rebuilt (attempt " + attempt + ")"
                : "Consumer " + queueName + " is stalled and cannot be rebuilt (attempt " + attempt + ")");

        if (replacement.isEmpty()) {
            log.warn("could not rebuild consumer {} (attempt {}); will try again on the next tick",
                    queueName, attempt);
            return Optional.empty();
        }
        log.info("consumer {} rebuilt (attempt {})", queueName, attempt);
        return replacement;
    }

    /// Repeated restarts are the platform failing to fix itself, and past
    /// some point that is not a warning any more.
    private Warnings.Severity severityFor(int attempt) {
        return attempt > CRITICAL_AFTER_ATTEMPTS ? Warnings.Severity.CRITICAL : Warnings.Severity.WARNING;
    }

    /// When a freshly started loop should be considered to have last polled,
    /// so a rebuild is not immediately judged stalled again.
    public Instant now() {
        return clock.instant();
    }
}
