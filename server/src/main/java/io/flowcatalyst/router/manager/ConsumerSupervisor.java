package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.ConsumerBuild;
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
    ///
    /// Judged against [ConsumerLoop#lastAlive], not [ConsumerLoop#lastPoll]
    /// alone: this method is what actually triggers a rebuild
    /// (`RouterServer#restartStalledLoops`), so it must see everything that
    /// makes a loop legitimately silent rather than stuck — a capacity pause
    /// with no pool to feed, or (`docs/spec/router.md` §3.2, §5 row 47) a
    /// `NatsQueue` poll still blocked on its continuous subscription while
    /// the broker itself remains provably alive. Reading `lastPoll` here
    /// directly would restart both, in a loop: NATS idling past the stall
    /// threshold is exactly the shape that collapsed Go's throughput to
    /// 1,100 deliveries/s under repeated "stalled consumer detected (poll is
    /// hung)" restarts, and this consumer never even reaches the poll error
    /// or empty-batch branches that would otherwise heartbeat it.
    public boolean stalled(ConsumerLoop loop) {
        return stalled(loop.lastAlive());
    }

    /// Records that a queue is polling again, so its next stall starts from
    /// zero rather than inheriting an old count.
    public void recovered(String queueName) {
        if (attempts.remove(queueName) != null) {
            log.atInfo().setMessage("consumer is polling again; restart count cleared")
                    .addKeyValue("queue", queueName)
                    .log();
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
    /// **The stalled consumer is never closed here** (R-26,
    /// `docs/spec/router-completion.md` §2 ruling 5 — a deliberate deviation
    /// from a pre-ruling implementation that did close it, aborting whatever
    /// it was still holding). It is left exactly as it was; the caller —
    /// `RouterServer`, once a replacement exists — hands it to
    /// [RouterManager#replaceConsumer], which detaches it to the manager's
    /// lingering set. An in-flight delivery still referencing it keeps
    /// running and resolves its own ack/nack on it, exactly as a reconfigured
    /// or removed queue's consumer does.
    ///
    /// @return the replacement, or empty when it could not be built
    public Optional<Consumer> restart(String queueName, QueueConfig config, Consumer stalled,
                                      RouterManager.ConsumerFactory factory) throws InterruptedException {
        var attempt = attempts.computeIfAbsent(queueName, ignored -> new AtomicInteger()).incrementAndGet();

        Thread.sleep(restartDelay);

        // A rebuild that answers Missing (the queue existed when this
        // consumer stalled but has since been deleted — a narrow race, since
        // an ordinary disappearance is caught by ConsumerLoop's own
        // QueueMissing poll result well before the stall threshold) is
        // treated the same as Failed here: this method's contract is
        // "rebuilt or not", and a queue that is not there to rebuild against
        // is not rebuilt, without inventing a third meaning for a signal
        // this call site has no use for.
        var built = factory.create(config);
        Optional<Consumer> replacement = built instanceof ConsumerBuild.Built b
                ? Optional.of(b.consumer())
                : Optional.empty();
        // The two outcomes point at different causes and so read differently:
        // a rebuild that keeps succeeding suggests broker or network health,
        // one that cannot rebuild at all suggests configuration.
        warnings.raise(severityFor(attempt), "CONSUMER_HEALTH", replacement.isPresent()
                ? "Consumer " + queueName + " was stalled and has been rebuilt (attempt " + attempt + ")"
                : "Consumer " + queueName + " is stalled and cannot be rebuilt (attempt " + attempt + ")");

        if (replacement.isEmpty()) {
            log.atWarn().setMessage("could not rebuild consumer; will try again on the next tick")
                    .addKeyValue("queue", queueName)
                    .addKeyValue("attempt", attempt)
                    .log();
            return Optional.empty();
        }
        log.atInfo().setMessage("consumer rebuilt")
                .addKeyValue("queue", queueName)
                .addKeyValue("attempt", attempt)
                .log();
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
