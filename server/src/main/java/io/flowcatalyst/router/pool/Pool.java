package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.policy.GroupFlushRegistry;
import io.flowcatalyst.router.policy.RateLimiter;
import io.flowcatalyst.router.policy.RetryPolicy;
import io.flowcatalyst.router.pool.OrderedGroups.HeadFailure;
import io.flowcatalyst.router.wire.MediationOutcome;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/// One processing pool: bounded concurrency, a rate limit, and delivery of
/// every message routed to it (`docs/spec/router.md` §3.4, §3.5).
///
/// ### Two dispatch shapes
///
/// `IMMEDIATE` messages each get their own worker and are bounded only by
/// the semaphore. Ordered messages queue per group, and one **drainer** per
/// group delivers them strictly in turn — the group, not the pool, is the
/// unit of ordering.
///
/// ### Cancellation is interruption
///
/// No context is threaded through. Every blocking point — the semaphore, the
/// rate limiter, a backoff — exits on interruption and restores the flag
/// (CONVENTIONS §8). What each exit does with the message it holds is the
/// interesting part, and differs by where it happened: a message that has
/// not been delivered is handed back to the broker or re-fronted in its
/// group, never dropped.
///
/// ### No panic scaffolding
///
/// Go recovers panics in the worker so one bad delivery cannot take the
/// process down. Per-thread failure isolation makes that unnecessary here;
/// what survives is the *policy* it guarded — an unexpected exception is a
/// retry after [#UNEXPECTED_FAILURE_DELAY], not a lost message.
public final class Pool implements AutoCloseable {

    /// Backoff after an unexpected exception (spec constant 16). Deliberately
    /// flat: an exception we did not anticipate tells us nothing about how
    /// long to wait, so escalating on it would be false precision.
    static final Duration UNEXPECTED_FAILURE_DELAY = Duration.ofSeconds(10);

    /// Delay attached to a nack when the pool cannot take a message at all
    /// (spec constant 11).
    static final Duration REJECTED_NACK_DELAY = Duration.ofSeconds(10);

    /// Floor when a rate-limit wait is cancelled (spec constant 18).
    static final Duration RATE_LIMIT_CANCELLED_FLOOR = Duration.ofSeconds(5);

    private static final int QUEUE_CAPACITY_MULTIPLIER = 20;
    private static final int MIN_QUEUE_CAPACITY = 50;

    /// @param code             the pool's identifier, as configured
    /// @param concurrency      simultaneous deliveries
    /// @param requestsPerMinute rate limit; zero is unlimited
    public record Config(String code, int concurrency, int requestsPerMinute) {

        public Config {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("pool code is required");
            }
            if (concurrency < 1) {
                throw new IllegalArgumentException("concurrency must be positive: " + concurrency);
            }
        }

        /// Messages the pool will hold before pushing back, so a burst is
        /// absorbed but an outage does not become unbounded memory.
        public int queueCapacity() {
            return Math.max(concurrency * QUEUE_CAPACITY_MULTIPLIER, MIN_QUEUE_CAPACITY);
        }
    }

    /// The two curves a pool backs off on. Injected rather than fixed so a
    /// pool is not welded to one schedule — and so a test can assert the
    /// dispatch decisions without waiting out real backoffs.
    ///
    /// @param delivery failed deliveries, and the source of the rejection
    ///                 budget for ordered heads
    /// @param deferred a healthy target asking us to come back later
    public record Backoffs(RetryPolicy delivery, RetryPolicy deferred) {

        public static final Backoffs DEFAULT = new Backoffs(RetryPolicy.DELIVERY, RetryPolicy.DEFERRED);
    }

    private final Config config;
    private final Backoffs backoffs;
    private final Mediator mediator;
    private final Broker broker;
    private final PoolMetrics metrics;
    private final GroupFlushRegistry flushes;
    private final RateLimiter limiter;
    private final OrderedGroups groups = new OrderedGroups();
    private final Clock clock;

    /// Replaced wholesale by [#updateConcurrency]. A worker holding a permit
    /// keeps releasing to the semaphore it acquired from, so a resize never
    /// releases a permit into the wrong one.
    private final AtomicReference<Semaphore> slots;

    /// IMMEDIATE messages awaiting a slot or sitting in a backoff. Ordered
    /// messages are counted by [OrderedGroups#buffered], so there is one
    /// owner per number rather than a total that can drift from its parts.
    private final AtomicInteger immediateWaiting = new AtomicInteger();

    /// Workers currently inside a delivery attempt — holding a semaphore
    /// permit and, usually, an open socket. Distinct from [#queueSize], which
    /// counts what is *waiting*: together they answer "is this pool busy or
    /// backed up?", which one number alone cannot.
    private final AtomicInteger activeWorkers = new AtomicInteger();

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopped;

    public Pool(Config config, Mediator mediator, Broker broker, PoolMetrics metrics, Clock clock) {
        this(config, Backoffs.DEFAULT, mediator, broker, metrics, clock);
    }

    public Pool(Config config, Backoffs backoffs, Mediator mediator, Broker broker,
                PoolMetrics metrics, Clock clock) {
        this.config = config;
        this.backoffs = backoffs;
        this.mediator = mediator;
        this.broker = broker;
        this.metrics = metrics;
        this.clock = clock;
        this.flushes = new GroupFlushRegistry(clock);
        this.limiter = new RateLimiter(config.requestsPerMinute());
        this.slots = new AtomicReference<>(new Semaphore(config.concurrency()));
    }

    public Config config() {
        return config;
    }

    public GroupFlushRegistry flushRegistry() {
        return flushes;
    }

    /// Messages accepted and not yet delivered — waiting for a slot, sitting
    /// in a backoff, or queued behind their group's head.
    public int queueSize() {
        return immediateWaiting.get() + groups.buffered();
    }

    /// Deliveries in progress right now.
    public int activeWorkers() {
        return activeWorkers.get();
    }

    /// Whether the pool's own rate limiter is holding messages back right
    /// now — as opposed to a target throttling us, which is a 429 and shows
    /// up separately. Conflating the two hides which side is the bottleneck.
    ///
    /// Observational: reading it takes no token.
    public boolean rateLimited() {
        return limiter.limited();
    }

    /// Message groups currently holding work. An ordered group is a
    /// serialisation point, so a rising count is the shape of ordered
    /// backlog that [#queueSize] alone would not distinguish from a busy
    /// IMMEDIATE pool.
    public int messageGroupCount() {
        return groups.groupCount();
    }

    /// Accepts a message for delivery, or hands it straight back.
    ///
    /// Rejection is a nack, never a drop: a pool that is stopped or full has
    /// formed no opinion about the message, so it must return to the broker
    /// to be delivered by someone else or later.
    public void submit(QueuedMessage message) {
        if (stopped) {
            broker.nack(message, REJECTED_NACK_DELAY);
            return;
        }
        if (queueSize() >= config.queueCapacity()) {
            broker.nack(message, REJECTED_NACK_DELAY);
            return;
        }
        if (message.ordered()) {
            submitOrdered(message);
        } else {
            immediateWaiting.incrementAndGet();
            start(() -> runImmediate(message));
        }
    }

    private void submitOrdered(QueuedMessage message) {
        boolean mustDrain = groups.offer(message);
        if (stopped) {
            // Raced with stop: the buffer is being flushed and nothing will
            // drain it, so hand the message back rather than stranding it.
            groups.drainAll().forEach(m -> broker.nack(m, REJECTED_NACK_DELAY));
            return;
        }
        if (mustDrain) {
            start(() -> runDrainer(message.group()));
        }
    }

    /// One IMMEDIATE message, retried in place for as long as it takes.
    ///
    /// Retryable outcomes **never touch the broker** here (§3.6): the message
    /// stays inside the pipeline, which is what keeps its position and its
    /// attempt count. That is the invariant Go's guardrail test pins, and it
    /// is deliberately *not* how ordered heads behave — see [#runDrainer].
    private void runImmediate(QueuedMessage initial) {
        var message = initial;
        while (true) {
            var semaphore = slots.get();
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                immediateWaiting.decrementAndGet();
                broker.nack(message, REJECTED_NACK_DELAY);
                return;
            }
            Attempt attempt;
            try {
                immediateWaiting.decrementAndGet();
                attempt = deliverOnce(message);
            } finally {
                semaphore.release();
            }
            if (attempt instanceof Attempt.Settled) {
                return;
            }
            var failure = (Attempt.Failed) attempt;
            var delay = backoffFor(message, failure.outcome());
            message = message.retrying();
            immediateWaiting.incrementAndGet();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                immediateWaiting.decrementAndGet();
                // No broker action: the message was never acknowledged, so
                // the broker's own redelivery brings it back. Nacking here
                // would race that redelivery with our own.
                return;
            }
        }
    }

    /// Delivers one group in order, for as long as it holds work.
    private void runDrainer(String group) {
        while (true) {
            var head = groups.pollHead(group);
            if (head.isEmpty()) {
                return;
            }
            var message = head.get();
            var semaphore = slots.get();
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Undelivered: put it back at the front and let go of the
                // group, so a later submit or redelivery resumes in order.
                groups.reFront(message);
                groups.releaseDrainer(group);
                return;
            }
            Attempt attempt;
            try {
                attempt = deliverOnce(message);
            } finally {
                semaphore.release();
            }
            if (attempt instanceof Attempt.Failed failed
                    && !handleHeadFailure(group, message, failed.outcome())) {
                return;
            }
        }
    }

    /// Applies the Q1 ruling to a failed head.
    ///
    /// @return whether this drainer should keep going. Returning false means
    ///         the group has been released — by being returned to the broker
    ///         or blocked — and a fresh drainer will be started by whatever
    ///         brings work back.
    private boolean handleHeadFailure(String group, QueuedMessage message, MediationOutcome outcome) {
        return switch (groups.onHeadFailure(message, outcome, backoffs.delivery().burstSize())) {
            case HeadFailure.RetryHead retry -> {
                var next = retry.head().retrying();
                groups.reFront(next);
                yield sleepBackoff(group, backoffFor(retry.head(), outcome));
            }
            case HeadFailure.ReturnGroup returned -> {
                // The target is down. Nothing here is wrong; the broker holds
                // them until it or the target gives way.
                broker.nack(returned.head(), REJECTED_NACK_DELAY);
                returned.siblings().forEach(sibling -> broker.nack(sibling, REJECTED_NACK_DELAY));
                yield false;
            }
            case HeadFailure.Continue carryOn -> {
                broker.ack(carryOn.failed());
                yield true;
            }
            case HeadFailure.BlockGroup blocked -> {
                broker.ack(blocked.failed());
                // Siblings were never delivered; the platform re-sends the
                // whole group in order once the failure is resolved.
                blocked.siblings().forEach(broker::ack);
                yield false;
            }
        };
    }

    /// Waits out an ordered head's backoff while holding **no** slot, so a
    /// group in backoff does not occupy concurrency the rest of the pool
    /// could use.
    ///
    /// @return whether the drainer should continue
    private boolean sleepBackoff(String group, Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The message is already re-fronted, so simply let the group go.
            groups.releaseDrainer(group);
            return false;
        }
    }

    /// One delivery attempt and its consequences (§3.5).
    private Attempt deliverOnce(QueuedMessage message) {
        var group = message.group();
        if (!group.isEmpty() && flushes.suppressed(group)) {
            // Checked before the rate limiter, which is the point: a flushed
            // group spends neither a token nor a slot.
            metrics.recordSuppressed();
            broker.ack(message);
            return new Attempt.Settled();
        }
        if (limiter.limited()) {
            metrics.recordRateLimited();
        }
        try {
            limiter.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt.Failed(new MediationOutcome.ErrorConnection(
                    (int) RATE_LIMIT_CANCELLED_FLOOR.toSeconds(), "rate limit wait cancelled"));
        }

        var startedAt = clock.instant();
        MediationOutcome outcome;
        activeWorkers.incrementAndGet();
        try {
            outcome = mediator.deliver(message.message(), backoffs.delivery().endsBurst(message.attempts()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt.Failed(new MediationOutcome.ErrorConnection(0, "interrupted"));
        } catch (RuntimeException e) {
            // The policy Go's panic recovery guarded, kept without the
            // scaffolding: an unexpected failure is a retry, not a lost
            // message. Reported as unavailability because we cannot claim the
            // target rejected anything.
            return new Attempt.Failed(new MediationOutcome.ErrorConnection(
                    (int) UNEXPECTED_FAILURE_DELAY.toSeconds(), "unexpected failure: " + e));
        } finally {
            activeWorkers.decrementAndGet();
        }
        var took = Duration.between(startedAt, clock.instant());
        return resolve(message, outcome, took);
    }

    private Attempt resolve(QueuedMessage message, MediationOutcome outcome, Duration took) {
        return switch (outcome) {
            case MediationOutcome.Success success -> {
                if (success.flushGroup()) {
                    applyFlush(message, success.delaySeconds());
                }
                metrics.recordSuccess(took);
                broker.ack(message);
                yield new Attempt.Settled();
            }
            // The request was wrong, not the target. Retrying it unchanged
            // cannot succeed, so it is dropped rather than kept forever.
            case MediationOutcome.ErrorConfig ignored -> {
                metrics.recordFailure(took);
                broker.ack(message);
                yield new Attempt.Settled();
            }
            case MediationOutcome.Deferred deferred -> {
                metrics.recordTransient(took);
                yield new Attempt.Failed(deferred);
            }
            case MediationOutcome.ErrorProcess process -> {
                metrics.recordTransient(took);
                yield new Attempt.Failed(process);
            }
            case MediationOutcome.ErrorConnection connection -> {
                metrics.recordFailure(took);
                yield new Attempt.Failed(connection);
            }
            case MediationOutcome.RateLimited rateLimited -> {
                // The target is throttling us, not failing: no breaker
                // impact, and counted apart from our own limiter.
                metrics.recordRateLimited();
                yield new Attempt.Failed(rateLimited);
            }
            // No metric: no call was made, so there is nothing to say about
            // the target that the breaker is not already saying.
            case MediationOutcome.CircuitOpen circuitOpen -> new Attempt.Failed(circuitOpen);
        };
    }

    private void applyFlush(QueuedMessage message, int delaySeconds) {
        var group = message.group();
        if (group.isEmpty()) {
            // An ungrouped message has no siblings to suppress; honouring it
            // would mean flushing the shared empty bucket.
            return;
        }
        flushes.flush(group, Duration.ofSeconds(delaySeconds));
    }

    /// The wait before this message's next attempt. A deferral is a healthy
    /// target asking us to come back, so it gets the shorter curve.
    private Duration backoffFor(QueuedMessage message, MediationOutcome outcome) {
        var policy = outcome instanceof MediationOutcome.Deferred ? backoffs.deferred() : backoffs.delivery();
        return policy.delayBefore(message.attempts() + 1, outcome.delaySeconds());
    }

    /// Resizes concurrency. Workers already holding a permit keep it on the
    /// old semaphore, so effective concurrency during the change is at most
    /// `in-flight + n`, settling to `n`.
    ///
    /// @return false when `n` is not positive — a zero-capacity pool would
    ///         accept messages and never deliver them
    public boolean updateConcurrency(int n) {
        if (n < 1) {
            return false;
        }
        slots.set(new Semaphore(n));
        return true;
    }

    /// Replaces the rate limit in place; zero is unlimited.
    public void updateRateLimit(int requestsPerMinute) {
        limiter.reconfigure(requestsPerMinute);
    }

    /// Stops accepting work and hands back everything still queued.
    ///
    /// Buffered messages are **nacked**, not dropped: they were accepted but
    /// never delivered, and the broker is where they must go to be picked up
    /// by another instance. Deliveries already in flight finish on their own.
    public void stop() {
        stopped = true;
        var buffered = groups.drainAll();
        if (buffered.isEmpty()) {
            return;
        }
        // Each nack is a broker round-trip, and a pool can be holding
        // hundreds. In turn, that is hundreds of serial round-trips inside a
        // shutdown budget; at once, it is one.
        try (var handback = Executors.newVirtualThreadPerTaskExecutor()) {
            buffered.forEach(message -> handback.execute(() -> broker.nack(message, REJECTED_NACK_DELAY)));
        }
    }

    /// Stops, then waits briefly for in-flight deliveries to finish before
    /// interrupting them.
    @Override
    public void close() {
        stop();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    private void start(Runnable task) {
        try {
            workers.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The pool closed between the stopped check and here.
            stopped = true;
        }
    }

    /// The outcome of one delivery attempt, as the dispatch loops see it.
    private sealed interface Attempt {

        /// Acknowledged one way or another; nothing further to do.
        record Settled() implements Attempt {
        }

        /// Retryable. What happens next depends on whether the message is
        /// ordered, which is the caller's business, not this method's.
        record Failed(MediationOutcome outcome) implements Attempt {
        }
    }
}
