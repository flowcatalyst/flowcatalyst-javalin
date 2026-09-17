package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.policy.GroupFlushRegistry;
import io.flowcatalyst.router.policy.RateLimiter;
import io.flowcatalyst.router.policy.RetryPolicy;
import io.flowcatalyst.router.pool.OrderedGroups.HeadFailure;
import io.flowcatalyst.router.settled.BlockedSiblings;
import io.flowcatalyst.router.settled.SettledJob;
import io.flowcatalyst.router.settled.SettledReport;
import io.flowcatalyst.router.settled.SettledReporter;
import io.flowcatalyst.router.wire.MediationOutcome;

import io.flowcatalyst.router.concurrent.Concurrently;
import io.flowcatalyst.router.observability.jfr.DispatchEvent;
import io.flowcatalyst.router.observability.jfr.GroupDecisionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
///
/// ### The A-01 gate
///
/// A `BLOCK_ON_ERROR` group's untried siblings, once its head is terminally
/// REJECTED, are either NACKed back to the broker or ACKed and reported to
/// the platform — never ACKed with nothing to recover them. Which one is
/// [#siblingPolicy], a [BlockedSiblings] the composition root chooses from
/// whether a platform base URL is configured; see that type's doc for the
/// full contract (router-specification.md §0, §3.2, §5.4).
public final class Pool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Pool.class);

    /// Backoff after an unexpected exception (spec constant 16). Deliberately
    /// flat: an exception we did not anticipate tells us nothing about how
    /// long to wait, so escalating on it would be false precision.
    static final Duration UNEXPECTED_FAILURE_DELAY = Duration.ofSeconds(10);

    /// Delay attached to a nack when the pool cannot take a message at all
    /// (spec constant 11). Also the delay a `BLOCK_ON_ERROR` head's untried
    /// siblings get when [#siblingPolicy] releases them (A-01 gate off).
    static final Duration REJECTED_NACK_DELAY = Duration.ofSeconds(10);

    /// Reason recorded on the [SettledReport] built for the platform's
    /// settled-message hook (`docs/spec/dispatch-seam.md` §6) — fixed, not
    /// derived from the mediation outcome, because every `BlockGroup`
    /// failure reaches here the same way: the head followed the retry
    /// policy and was terminally REJECTED (R-57).
    static final String SETTLED_REASON = "head failed under BLOCK_ON_ERROR";

    /// Floor when a rate-limit wait is cancelled (spec constant 18).
    static final Duration RATE_LIMIT_CANCELLED_FLOOR = Duration.ofSeconds(5);

    private static final int QUEUE_CAPACITY_MULTIPLIER = 20;
    private static final int MIN_QUEUE_CAPACITY = 50;

    /// The warning category for the pool's own limiter holding deliveries
    /// back (§7.3) — distinct from a target's own 429, which never reaches
    /// this class's warning path at all.
    private static final String RATE_LIMIT = "RATE_LIMIT";

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
    private final Warnings warnings;

    /// The A-01 gate: what happens to a `BLOCK_ON_ERROR` head's untried
    /// siblings once they leave [#groups]. Defaults to
    /// [BlockedSiblings.Release] on every constructor that does not name it
    /// — the router-specification §0 MUST: ACKing them is forbidden until a
    /// platform half exists to recover them, and the composition root
    /// ([io.flowcatalyst.server.Router]) is the only caller allowed to
    /// switch this on, from whether a platform URL is configured.
    private final BlockedSiblings siblingPolicy;

    /// Whether the last [#deliverOnce] observed the limiter holding messages
    /// back — so the INFO warning fires once on the transition into limiting
    /// rather than once per limited delivery, and clears itself the moment a
    /// delivery proceeds unlimited.
    private final AtomicBoolean rateLimitWarned = new AtomicBoolean();

    /// Whether this pool's [#queueSize] is currently at or over
    /// [Config#queueCapacity] — tracked so [#capacityChanged] notifies
    /// [#capacityListener] only on the crossing back under capacity, never on
    /// every admission or completion (`docs/spec/router.md` §3.2; defect
    /// fixed 2026-09-07 — see [#capacityChanged]).
    private final AtomicBoolean full = new AtomicBoolean(false);

    /// Run on the crossing back under capacity — how a parked
    /// [io.flowcatalyst.router.manager.ConsumerLoop] learns there is room
    /// again without polling on a fixed interval (§3.2). Defaults to a no-op
    /// so a pool built and used before [#onCapacityFreed] is wired up —
    /// every test in this module, and any pool warmed before it is
    /// registered — never NPEs.
    private volatile Runnable capacityListener = () -> {
    };

    /// Resized in place rather than replaced — see [ResizableSemaphore] for
    /// why swapping the instance strands everyone already waiting on it.
    private final ResizableSemaphore slots;

    /// IMMEDIATE messages awaiting a slot or sitting in a backoff. Ordered
    /// messages are counted by [OrderedGroups#buffered], so there is one
    /// owner per number rather than a total that can drift from its parts.
    private final AtomicInteger immediateWaiting = new AtomicInteger();

    /// Workers currently inside a delivery attempt — holding a semaphore
    /// permit and, usually, an open socket. Distinct from [#queueSize], which
    /// counts what is *waiting*: together they answer "is this pool busy or
    /// backed up?", which one number alone cannot.
    /// What is inside each worker right now, keyed by the **worker**, not by
    /// message id: de-duplication lets two copies of one id coexist briefly,
    /// and keying by id would silently collapse them into one row and one
    /// count. One worker runs one mediation at a time, so the thread is the
    /// natural identity.
    private final ConcurrentHashMap<Thread, Mediating> mediating = new ConcurrentHashMap<>();

    /// How many times a message may be retried IN PLACE before it is handed
    /// back to the broker instead. Matches Go's `maxInPipelineAttempts`.
    public static final int MAX_IN_PIPELINE_ATTEMPTS = 10;

    /// How long a stand-down will spend handing buffered messages back before
    /// giving up on the broker and letting redelivery do it instead.
    static final Duration HANDBACK_TIMEOUT = Duration.ofSeconds(5);

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopped;

    /// Set by [#drain] (X-11, `docs/spec/router-completion.md` §2 ruling 6):
    /// stops admitting like [#stopped], but — unlike [#stop] — never hands
    /// back what is already buffered. Distinct from `stopped` because a
    /// draining pool must still be usable by its own drainers and workers
    /// until [#drained] is true; `stopped` additionally implies the worker
    /// executor is going away, which [#close] is the only thing allowed to
    /// decide.
    private volatile boolean draining;

    public Pool(Config config, Mediator mediator, Broker broker, PoolMetrics metrics, Clock clock) {
        this(config, Backoffs.DEFAULT, mediator, broker, metrics, clock, Warnings.NO_OP, new BlockedSiblings.Release());
    }

    public Pool(Config config, Backoffs backoffs, Mediator mediator, Broker broker,
                PoolMetrics metrics, Clock clock) {
        this(config, backoffs, mediator, broker, metrics, clock, Warnings.NO_OP, new BlockedSiblings.Release());
    }

    public Pool(Config config, Backoffs backoffs, Mediator mediator, Broker broker,
                PoolMetrics metrics, Clock clock, Warnings warnings) {
        this(config, backoffs, mediator, broker, metrics, clock, warnings, new BlockedSiblings.Release());
    }

    public Pool(Config config, Backoffs backoffs, Mediator mediator, Broker broker,
                PoolMetrics metrics, Clock clock, Warnings warnings, BlockedSiblings siblingPolicy) {
        this.config = config;
        this.backoffs = backoffs;
        this.mediator = mediator;
        this.broker = broker;
        this.metrics = metrics;
        this.clock = clock;
        this.warnings = warnings;
        this.siblingPolicy = siblingPolicy;
        this.flushes = new GroupFlushRegistry(clock);
        this.limiter = new RateLimiter(config.requestsPerMinute());
        this.slots = new ResizableSemaphore(config.concurrency());
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

    /// Registers `listener` to run on the crossing back under capacity
    /// (`docs/spec/router.md` §3.2) — set once, by whatever registers this
    /// pool with a [io.flowcatalyst.router.manager.RouterManager].
    public void onCapacityFreed(Runnable listener) {
        this.capacityListener = listener;
    }

    /// Re-evaluates [#full] against the current [#queueSize] and runs
    /// [#capacityListener] exactly on the transition from full to not-full —
    /// never on every admission or completion, which at pool throughput would
    /// mean a lock/wake on every single message.
    ///
    /// Called after every event that can change [#queueSize] (an admission,
    /// a worker taking a slot, a completion, a re-queue). Each call is cheap
    /// — a read of two counters and an [AtomicBoolean#getAndSet] — and only
    /// the crossing itself pays for waking a parked consumer loop.
    ///
    /// Replaces the fixed 2 s poll [io.flowcatalyst.router.manager.ConsumerLoop]
    /// used to sleep whenever no pool had room: on a fast broker, several
    /// pollers filled a shared buffer in well under a second, the workers
    /// drained it in a fraction of that, and every poller then sat out the
    /// rest of a 2 s pause with the router mostly idle. Fixed 2026-09-07.
    private void capacityChanged() {
        boolean atCapacity = queueSize() >= config.queueCapacity();
        if (full.getAndSet(atCapacity) && !atCapacity) {
            capacityListener.run();
        }
    }

    /// Deliveries in progress right now.
    public int activeWorkers() {
        // Derived from the set rather than counted alongside it, so the number
        // an operator sees and the rows they drill into cannot disagree.
        return mediating.size();
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
        if (stopped || draining) {
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
            capacityChanged();
            if (!start(() -> runImmediate(message))) {
                // Raced with close(): the executor was already shutting
                // down when this reached it, so runImmediate never got the
                // chance to account for the message itself. Left alone, this
                // was silent loss — neither acked, nacked nor released, and
                // immediateWaiting never decremented — until the broker's
                // own visibility eventually lapsed. Reachable in practice:
                // evictIdleSynthesisedPools and a reconfigure removal both
                // close a pool a concurrent route() may be mid-submit on.
                immediateWaiting.decrementAndGet();
                capacityChanged();
                broker.nack(message, REJECTED_NACK_DELAY, "pool-closed");
            }
        }
    }

    /// Kicks a dead drainer back to life for `group`, if it still holds
    /// buffered work with nothing currently draining it (`docs/spec/router.md`
    /// §2.1). A redelivery of a message already buffered in an ordered group
    /// must not leave the group stalled forever just because its original
    /// drainer exited without finishing — an interrupted slot wait or a
    /// cancelled backoff releases the drainer flag but leaves the buffer
    /// re-fronted, and nothing else was going to notice.
    ///
    /// A no-op when a drainer is already running for the group, or the group
    /// holds nothing — [OrderedGroups#claimDrainer] answers both at once.
    public void resumeGroup(String group) {
        if (groups.claimDrainer(group)) {
            start(() -> runDrainer(group));
        }
    }

    private void submitOrdered(QueuedMessage message) {
        boolean mustDrain = groups.offer(message);
        capacityChanged();
        if (stopped) {
            // Raced with stop: the buffer is being flushed and nothing will
            // drain it, so hand the message back rather than stranding it.
            groups.drainAll().forEach(m -> broker.nack(m, REJECTED_NACK_DELAY));
            capacityChanged();
            return;
        }
        if (mustDrain) {
            start(() -> runDrainer(message.group()));
        }
    }

    /// One IMMEDIATE message, retried in place for as long as it takes.
    ///
    /// Most retryable outcomes **never touch the broker** here (§3.6): the
    /// message stays inside the pipeline, which is what keeps its position
    /// and its attempt count. That is the invariant Go's guardrail test pins,
    /// and it is deliberately *not* how ordered heads behave — see
    /// [#runDrainer]. Two outcomes are handed back at once instead: a target
    /// this process could not reach at all (unchanged, §3.6 item 5), and —
    /// owner ruling 2026-09-17, `docs/spec/router-deferral-handback.md` R1 —
    /// a deferral naming a delay, which goes straight to the broker with
    /// that exact delay rather than being retried in memory.
    private void runImmediate(QueuedMessage initial) {
        var message = initial;
        while (true) {
            var semaphore = slots;
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                immediateWaiting.decrementAndGet();
                capacityChanged();
                broker.nack(message, REJECTED_NACK_DELAY);
                return;
            }
            Attempt attempt;
            try {
                immediateWaiting.decrementAndGet();
                capacityChanged();
                attempt = deliverOnce(message);
            } finally {
                semaphore.release();
            }
            if (attempt instanceof Attempt.Settled) {
                return;
            }
            if (attempt instanceof Attempt.Rejected rejected) {
                // R-57: the app ran the message and answered with a
                // permanent application failure. Terminal on this one
                // attempt — no bounded retry — so it is ACKed straight to
                // the platform's review flow rather than looping.
                broker.ack(message, "rejected");
                return;
            }
            var failure = (Attempt.Failed) attempt;

            // R1 (owner ruling 2026-09-17, docs/spec/router-deferral-handback.md):
            // a deferral that named a delay goes straight back to the broker on
            // its first occurrence — the exact delay asked for, no RetryPolicy
            // curve, no 60 s cap. A deferral with no delay (delaySeconds == 0)
            // falls through unchanged to the existing in-memory DEFERRED curve
            // below: the target didn't ask for anything specific, so there is
            // nothing here to hand back early.
            if (failure.outcome() instanceof MediationOutcome.Deferred deferred && deferred.delaySeconds() > 0) {
                broker.nack(message, Duration.ofSeconds(deferred.delaySeconds()), "deferred");
                return;
            }
            var delay = backoffFor(message, failure.outcome());

            // Nothing was learned about the message — the target could not be
            // reached, or the breaker refused the call. Retrying it here just
            // holds it in this process while the outage runs; the broker is
            // where it belongs, and the backoff becomes its redelivery delay
            // so it does not come straight back to the pool that gave up.
            if (!failure.ourFault()
                    && failure.outcome().disposition() == MediationOutcome.Disposition.RETURN_TO_BROKER) {
                broker.nack(message, delay, "target-unavailable");
                return;
            }
            // An in-place retry never returns the message, so while it loops
            // the broker's expiry, redelivery count and dead-letter queue can
            // never act on it — and the stall detector deliberately leaves
            // retrying entries alone, so nothing warns either. Unbounded, a
            // target answering 429 or ack:false for ever pins the message and
            // its tracker entry for the life of the process, invisibly.
            if (message.attempts() + 1 >= MAX_IN_PIPELINE_ATTEMPTS) {
                broker.nack(message, delay, "retry-budget-exhausted");
                return;
            }
            broker.retrying(message);
            message = message.retrying();
            immediateWaiting.incrementAndGet();
            capacityChanged();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                immediateWaiting.decrementAndGet();
                capacityChanged();
                // No broker action: the message was never acknowledged, so
                // the broker's own redelivery brings it back. Nacking here
                // would race that redelivery with our own.
                //
                // Ownership MUST be released, though. Holding it means the
                // redelivery we are relying on is classified as a duplicate
                // and dropped, so the message waits for the reaper instead —
                // fifteen minutes of nothing happening, on the shutdown path.
                broker.release(message);
                return;
            }
        }
    }

    /// Delivers one group in order, for as long as it holds work.
    private void runDrainer(String group) {
        while (true) {
            var head = groups.pollHead(group);
            capacityChanged();
            if (head.isEmpty()) {
                return;
            }
            var message = head.get();
            var semaphore = slots;
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Undelivered: put it back at the front and let go of the
                // group, so a later submit or redelivery resumes in order.
                groups.reFront(message);
                capacityChanged();
                groups.releaseDrainer(group);
                return;
            }
            Attempt attempt;
            try {
                attempt = deliverOnce(message);
            } finally {
                semaphore.release();
            }
            switch (attempt) {
                case Attempt.Settled ignored -> {
                }
                case Attempt.Failed failed -> {
                    if (!handleHeadFailure(group, message, failed.outcome())) {
                        return;
                    }
                }
                // R-57: the app ran and rejected the message. Same per-mode
                // decision as any other head failure — the drainer does not
                // need to know REJECTED is terminal on the first attempt,
                // only that OrderedGroups has already decided what happens
                // to the group.
                case Attempt.Rejected rejected -> {
                    if (!handleHeadFailure(group, message, rejected.outcome())) {
                        return;
                    }
                }
            }
        }
    }

    /// Applies the Q1 ruling to a failed head.
    ///
    /// @return whether this drainer should keep going. Returning false means
    ///         the group has been released — by being returned to the broker
    ///         or blocked — and a fresh drainer will be started by whatever
    ///         brings work back.
    /// Records what the group decided, if anyone is recording.
    ///
    /// Emitted before the decision is acted on, so a recording that ends
    /// mid-shutdown still says what was about to happen.
    private static void decided(String group, QueuedMessage message,
                                MediationOutcome outcome, HeadFailure failure) {
        var event = new GroupDecisionEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.group = group;
        event.dispatchMode = String.valueOf(message.message().dispatchMode());
        event.decision = failure.getClass().getSimpleName();
        event.disposition = outcome.disposition().name();
        event.statusCode = outcome.statusCode();
        event.attempt = message.attempts();
        event.siblingsAffected = switch (failure) {
            case HeadFailure.ReturnGroup returned -> returned.siblings().size();
            case HeadFailure.BlockGroup blocked -> blocked.siblings().size();
            case HeadFailure.RetryHead ignored -> 0;
            case HeadFailure.Continue ignored -> 0;
        };
        event.commit();
    }

    /// Commits the dispatch event for a failure that never reached [#resolve]
    /// — the mediator threw, or the thread was interrupted mid-call.
    private Attempt failed(DispatchEvent event, QueuedMessage message, MediationOutcome outcome) {
        dispatched(event, message, outcome);
        return new Attempt.Failed(outcome, true);
    }

    private void dispatched(DispatchEvent event, QueuedMessage message, MediationOutcome outcome) {
        if (!event.shouldCommit()) {
            return;
        }
        event.pool = config.code();
        event.messageId = message.id();
        event.queue = message.queueId();
        event.group = message.group();
        event.attempt = message.attempts();
        event.outcome = outcome.getClass().getSimpleName();
        event.disposition = outcome.disposition().name();
        event.statusCode = outcome.statusCode();
        event.commit();
    }

    private boolean handleHeadFailure(String group, QueuedMessage message, MediationOutcome outcome) {
        var failure = groups.onHeadFailure(message, outcome);
        capacityChanged();
        decided(group, message, outcome, failure);
        return switch (failure) {
            case HeadFailure.RetryHead retry -> {
                broker.retrying(retry.head());
                var next = retry.head().retrying();
                groups.reFront(next);
                capacityChanged();
                yield sleepBackoff(group, backoffFor(retry.head(), outcome));
            }
            case HeadFailure.ReturnGroup returned -> {
                // R2 (owner ruling 2026-09-17, docs/spec/router-deferral-handback.md):
                // the head carries its REAL delay — the exact deferral it asked
                // for (R1), or the same backoff the unordered path would use
                // for this outcome — never the fixed REJECTED_NACK_DELAY.
                // Siblings are untried and carry no information about the
                // outcome, so they keep the fixed delay regardless.
                if (outcome instanceof MediationOutcome.Deferred deferred && deferred.delaySeconds() > 0) {
                    broker.nack(returned.head(), Duration.ofSeconds(deferred.delaySeconds()), "deferred");
                } else {
                    // The target is down. Nothing here is wrong; the broker
                    // holds it until it or the target gives way.
                    broker.nack(returned.head(), backoffFor(returned.head(), outcome), "target-unavailable");
                }
                returned.siblings().forEach(sibling ->
                        broker.nack(sibling, REJECTED_NACK_DELAY, "target-unavailable"));
                yield false;
            }
            case HeadFailure.Continue carryOn -> {
                broker.ack(carryOn.failed(), "rejected-group-continues");
                yield true;
            }
            case HeadFailure.BlockGroup blocked -> {
                // The head is ACKed unconditionally — it is done, one way or
                // another, the moment it is terminally REJECTED. What
                // happens to the untried siblings is the A-01 gate: they
                // were never delivered, so nothing is wrong with them, but
                // this pool decides whether the broker or the platform ends
                // up holding them next.
                broker.ack(blocked.failed(), "rejected-group-blocked");
                switch (siblingPolicy) {
                    case BlockedSiblings.Release ignored ->
                            // No platform to recover an ACKed sibling
                            // (router-specification.md §0's MUST): released
                            // back to the broker, exactly as the pre-ruling
                            // behaviour did.
                            blocked.siblings().forEach(sibling ->
                                    broker.nack(sibling, REJECTED_NACK_DELAY, "rejected-group-released"));
                    case BlockedSiblings.Settle settle -> {
                        // Every ACK first, the report only after — never the
                        // other order, or a crash between them could report
                        // a sibling the broker still thinks is live.
                        blocked.siblings().forEach(sibling -> broker.ack(sibling, "rejected-group-blocked"));
                        reportSettled(settle.reporter(), blocked);
                    }
                }
                yield false;
            }
        };
    }

    /// Builds and hands off the [SettledReport] for a `BlockGroup` failure's
    /// siblings (A-01 gate on). A sibling with no auth token never came from
    /// the platform scheduler — there is no dispatch-job row to mark — so it
    /// is skipped rather than reported; an empty result is not sent at all.
    private void reportSettled(SettledReporter reporter, HeadFailure.BlockGroup blocked) {
        var jobs = blocked.siblings().stream()
                .map(Pool::settledJob)
                .flatMap(Optional::stream)
                .toList();
        if (jobs.isEmpty()) {
            return;
        }
        reporter.report(new SettledReport(config.code(), blocked.failed().group(), SETTLED_REASON, jobs));
    }

    private static Optional<SettledJob> settledJob(QueuedMessage message) {
        var token = message.message().authToken();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SettledJob(message.id(), token));
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
        if (!broker.owns(message)) {
            // Layer 2 dedup backstop (`docs/spec/router.md` §2.1
            // EnsureTracked): a different broker copy now owns the
            // pipeline for this message — this attempt must ACK its own
            // copy as a duplicate and go no further, never deliver it.
            broker.ack(message, "duplicate");
            return new Attempt.Settled();
        }
        var group = message.group();
        if (!group.isEmpty() && flushes.suppressed(group)) {
            // Checked before the rate limiter, which is the point: a flushed
            // group spends neither a token nor a slot.
            metrics.recordSuppressed();
            broker.ack(message);
            return new Attempt.Settled();
        }
        // Reserve first, then record, then wait: one act, not a check
        // followed by a separate acquire. Read as "is the limiter busy?" and
        // then "take a token", every worker in a burst could see a free token
        // before any of them took it, and the metric and the warning would
        // describe a throttle nobody observed while the deliveries all waited.
        var throttle = limiter.reserve();
        if (!throttle.isZero()) {
            metrics.recordRateLimited();
            // INFO, once, on the transition into limiting — never once per
            // limited delivery, which would flood the store for the length
            // of any ordinary burst.
            if (rateLimitWarned.compareAndSet(false, true)) {
                warnings.raise(Warnings.Severity.INFO, RATE_LIMIT,
                        "pool " + config.code() + " is holding deliveries back for its own rate limit");
            }
        } else {
            // A delivery proceeded unlimited: the condition the warning
            // described no longer holds, so the next transition back into
            // limiting earns a fresh warning rather than staying silent.
            rateLimitWarned.set(false);
        }
        try {
            limiter.awaitReserved(throttle);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt.Failed(new MediationOutcome.ErrorConnection(
                    (int) RATE_LIMIT_CANCELLED_FLOOR.toSeconds(), "rate limit wait cancelled"), true);
        }

        var startedAt = clock.instant();
        var event = new DispatchEvent();
        MediationOutcome outcome;
        var worker = Thread.currentThread();
        mediating.put(worker, new Mediating(message.id(), config.code(), message.group(),
                message.queueId(), message.message().mediationTarget(), message.attempts(), startedAt));
        event.begin();
        try {
            outcome = mediator.deliver(message.message(), backoffs.delivery().endsBurst(message.attempts()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(event, message, new MediationOutcome.ErrorConnection(0, "interrupted"));
        } catch (RuntimeException e) {
            // The policy Go's panic recovery guarded, kept without the
            // scaffolding: an unexpected failure is a retry, not a lost
            // message. Reported as unavailability because we cannot claim the
            // target rejected anything.
            return failed(event, message, new MediationOutcome.ErrorConnection(
                    (int) UNEXPECTED_FAILURE_DELAY.toSeconds(), "unexpected failure: " + e));
        } finally {
            event.end();
            mediating.remove(worker);
        }
        var took = Duration.between(startedAt, clock.instant());
        // Committed BEFORE the broker action: the event describes the
        // attempt and its outcome, both known now, and anything watching
        // the broker (a test awaiting the ack, an operator correlating a
        // recording with a queue) must find the attempt already recorded
        // once the ack is visible — the other order raced exactly that.
        dispatched(event, message, outcome);
        return resolve(message, outcome, took);
    }

    /// The pool's own delivery metric — distinct from [PoolMetrics], which is
    /// the sink; this is the pure classification the conformance corpus's
    /// `metric` column pins case by case. Public because the conformance
    /// runner (a different package, by design — see `conformance/README.md`)
    /// asserts it directly rather than re-deriving the mapping.
    public enum Metric { SUCCESS, FAILURE, TRANSIENT, RATE_LIMITED, NONE }

    /// Classifies a mediation outcome for metrics, independent of what
    /// [#resolve] does with the message. Exhaustive, so a new outcome is a
    /// compile error here until someone decides what it counts as.
    public static Metric metricFor(MediationOutcome outcome) {
        return switch (outcome) {
            case MediationOutcome.Success ignored -> Metric.SUCCESS;
            // Both dispositions an ErrorConfig can carry — a permanent
            // rejection and a config-drop — read as a failed delivery.
            case MediationOutcome.ErrorConfig ignored -> Metric.FAILURE;
            case MediationOutcome.Deferred ignored -> Metric.TRANSIENT;
            case MediationOutcome.ErrorProcess ignored -> Metric.TRANSIENT;
            case MediationOutcome.ErrorConnection ignored -> Metric.FAILURE;
            case MediationOutcome.RateLimited ignored -> Metric.RATE_LIMITED;
            // No call was made, so there is nothing to say about the target
            // that the breaker is not already saying.
            case MediationOutcome.CircuitOpen ignored -> Metric.NONE;
        };
    }

    private void recordMetric(Metric metric, Duration took) {
        switch (metric) {
            case SUCCESS -> metrics.recordSuccess(took);
            case FAILURE -> metrics.recordFailure(took);
            case TRANSIENT -> metrics.recordTransient(took);
            case RATE_LIMITED -> metrics.recordRateLimited();
            case NONE -> {
            }
        }
    }

    private Attempt resolve(QueuedMessage message, MediationOutcome outcome, Duration took) {
        var metric = metricFor(outcome);
        return switch (outcome) {
            case MediationOutcome.Success success -> {
                if (success.flushGroup()) {
                    applyFlush(message, success.delaySeconds());
                }
                recordMetric(metric, took);
                broker.ack(message, "delivered");
                yield new Attempt.Settled();
            }
            case MediationOutcome.ErrorConfig config -> {
                recordMetric(metric, took);
                yield switch (config.disposition()) {
                    // The request was wrong, not the target. Retrying it
                    // unchanged cannot succeed, so it is dropped rather than
                    // kept forever.
                    case UNDELIVERABLE -> {
                        broker.ack(message, "undeliverable");
                        yield new Attempt.Settled();
                    }
                    // R-57: the app ran the message and answered badly.
                    // Terminal on this attempt — no bounded retry — so the
                    // caller decides what a rejection does to the message's
                    // group rather than this method acking it directly.
                    case REJECTED -> new Attempt.Rejected(config);
                    case DELIVERED, RETRY_IN_PLACE, RETURN_TO_BROKER -> throw new IllegalStateException(
                            "ErrorConfig disposition invariant violated: " + config.disposition());
                };
            }
            case MediationOutcome.Deferred deferred -> {
                recordMetric(metric, took);
                yield new Attempt.Failed(deferred, false);
            }
            case MediationOutcome.ErrorProcess process -> {
                recordMetric(metric, took);
                yield new Attempt.Failed(process, false);
            }
            case MediationOutcome.ErrorConnection connection -> {
                recordMetric(metric, took);
                yield new Attempt.Failed(connection, false);
            }
            case MediationOutcome.RateLimited rateLimited -> {
                // The target is throttling us, not failing: no breaker
                // impact, and counted apart from our own limiter.
                recordMetric(metric, took);
                yield new Attempt.Failed(rateLimited, false);
            }
            // No metric: no call was made, so there is nothing to say about
            // the target that the breaker is not already saying.
            case MediationOutcome.CircuitOpen circuitOpen -> new Attempt.Failed(circuitOpen, false);
        };
    }

    private void applyFlush(QueuedMessage message, int delaySeconds) {
        var group = message.group();
        if (group.isEmpty()) {
            // An ungrouped message has no siblings to suppress; honouring it
            // would mean flushing the shared empty bucket. Logged rather than
            // silently ignored (spec §4.5): a target setting flushGroup on an
            // ungrouped message is telling us something we cannot act on, and
            // silence there is indistinguishable from the feature working.
            log.atWarn().setMessage("flushGroup ignored: message has no group")
                    .addKeyValue("message_id", message.id())
                    .log();
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

    /// Everything inside a worker right now, newest-first order unspecified —
    /// the caller sorts.
    public List<Mediating> mediating() {
        return List.copyOf(mediating.values());
    }

    /// One message group's live state, for the blocked/held-groups
    /// monitoring surface (R-04, `docs/spec/router-completion.md` §2 ruling
    /// 6): joins [OrderedGroups#snapshot]'s buffer state with this pool's own
    /// [GroupFlushRegistry] — "how deep is it, is something draining it, and
    /// has a target asked us to go quiet on it" are one row, not three
    /// separate lookups an operator has to reconcile by hand.
    ///
    /// @param suppressedUntil `null` when the group is not currently
    ///                        suppressed — never a sentinel instant
    public record GroupSnapshot(String group, int depth, boolean draining, Instant suppressedUntil) {
    }

    /// Every message group this pool currently holds. Includes a group still
    /// draining after the pool itself was removed from routing — the caller
    /// (`GroupRoutes`) reads
    /// [io.flowcatalyst.router.manager.RouterManager#allPools] rather than
    /// [io.flowcatalyst.router.manager.RouterManager#pools] specifically so a
    /// draining pool's groups stay visible until it finishes (§5.1).
    public List<GroupSnapshot> groupSnapshot() {
        return groups.snapshot().stream()
                .map(g -> new GroupSnapshot(g.group(), g.depth(), g.draining(),
                        flushes.suppressedUntil(g.group()).orElse(null)))
                .toList();
    }

    /// Resizes concurrency, for the messages already queued as much as for
    /// the ones still to come.
    ///
    /// Raising takes effect at once. Lowering cannot evict a delivery already
    /// running, so it settles to `n` as those finish rather than the instant
    /// it is called; it never exceeds the higher of the two in the meantime.
    ///
    /// @return false when `n` is not positive — a zero-capacity pool would
    ///         accept messages and never deliver them
    public boolean updateConcurrency(int n) {
        if (n < 1) {
            return false;
        }
        slots.resize(n);
        return true;
    }

    /// Replaces the rate limit in place; zero is unlimited.
    public void updateRateLimit(int requestsPerMinute) {
        limiter.reconfigure(requestsPerMinute);
    }

    /// Hands back everything still queued **without** stopping the pool.
    ///
    /// This is the leadership-loss case: another instance is taking over, so
    /// buffered work must go back to the broker — but this pool has to stay
    /// usable, because leadership can return and rebuilding every pool on
    /// each transition would make a failover far more disruptive than it
    /// needs to be.
    ///
    /// Deliveries already in flight finish on their own.
    public void releaseBuffered() {
        handBack(groups.drainAll());
        capacityChanged();
    }

    /// Stops admitting new work but leaves everything already buffered alone
    /// (X-11, `docs/spec/router-completion.md` §2 ruling 6): a pool the
    /// configuration no longer wants must stop being routed to at once, but
    /// every group already queued keeps draining through its existing
    /// drainer, and a delivery already running finishes normally. Unlike
    /// [#stop], nothing is handed back here — that would abort work the spec
    /// requires to finish on its own (§5.1).
    ///
    /// [#drained] tells the caller when the buffer and every worker have
    /// emptied on their own; [#close] is the follow-up that then releases the
    /// worker executor. There is no resume: the only caller that drains a
    /// pool ([io.flowcatalyst.router.manager.RouterManager#applyPools]) is
    /// discarding it, same as [#stop].
    public void drain() {
        draining = true;
    }

    /// Whether this pool has nothing left to finish — no buffered work and no
    /// delivery in progress. A draining pool becomes safe to [#close] the
    /// moment this turns true; asked before [#drain] it means the same thing
    /// it always did, an idle pool.
    public boolean drained() {
        return queueSize() == 0 && activeWorkers() == 0;
    }

    /// Stops accepting work **permanently** and hands back everything still
    /// queued. A stopped pool nacks every later submission; there is no
    /// resume, because the only caller that stops a pool is discarding it.
    ///
    /// Buffered messages are **nacked**, not dropped: they were accepted but
    /// never delivered, and the broker is where they must go to be picked up
    /// by another instance. Deliveries already in flight finish on their own.
    public void stop() {
        stopped = true;
        handBack(groups.drainAll());
        capacityChanged();
    }

    private void handBack(List<QueuedMessage> buffered) {
        if (buffered.isEmpty()) {
            return;
        }
        // Each nack is a broker round-trip, and a pool can be holding
        // hundreds. In turn, that is hundreds of serial round-trips inside a
        // shutdown budget; at once, it is one.
        //
        // Bounded, because "at once" is not the same as "guaranteed to
        // finish". A broker that accepts the connection and then never
        // answers would otherwise hold this call — and with it stop(), the
        // leadership transition and the whole shutdown — open forever. The
        // messages that cannot be handed back are not lost: they were never
        // acknowledged, so the broker redelivers them on its own timer.
        Concurrently.forEach(buffered, message -> broker.nack(message, REJECTED_NACK_DELAY, "stood-down"),
                HANDBACK_TIMEOUT, "hand-back for pool " + config.code());
    }

    /// Stops, then waits briefly for in-flight deliveries to finish before
    /// interrupting them.
    @Override
    public void close() {
        stop();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                // Interrupting is not the end of it. A worker parked in a
                // backoff wakes up owing a broker call — releasing ownership,
                // or nacking — and returning here before it makes that call
                // loses exactly what the interrupt was supposed to preserve.
                // So wait for the unwind too; it is only ever the tail of a
                // catch block, hence the far shorter grace.
                workers.shutdownNow();
                if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.atWarn().setMessage("pool still had workers running after shutdown")
                            .addKeyValue("pool", config.code())
                            .log();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    /// @return whether `task` was actually handed to the executor — false
    ///         means the pool closed between the caller's `stopped`/`draining`
    ///         check and here, and the caller is responsible for whatever
    ///         accounting or broker action that leaves undone (see the
    ///         IMMEDIATE branch of [#submit]; [#submitOrdered] already
    ///         defends its own buffer against this race by re-checking
    ///         `stopped` after [OrderedGroups#offer]).
    private boolean start(Runnable task) {
        try {
            workers.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            // The pool closed between the stopped check and here.
            stopped = true;
            return false;
        }
    }

    /// The outcome of one delivery attempt, as the dispatch loops see it.
    private sealed interface Attempt {

        /// Acknowledged one way or another; nothing further to do.
        record Settled() implements Attempt {
        }

        /// Retryable. What happens next depends on whether the message is
        /// ordered, which is the caller's business, not this method's.
        /// @param ourFault whether the failure came from **inside this
        ///                  process** — the mediator threw, or a wait was
        ///                  interrupted — rather than from the target.
        ///
        ///                  It matters because Java models both as
        ///                  `ErrorConnection`, and they deserve opposite
        ///                  treatment: a target that cannot be reached should
        ///                  go back to the broker, where something outside
        ///                  this process can act on it, while our own
        ///                  exception says nothing about the target and is
        ///                  worth retrying right here. Handing the second one
        ///                  back would turn every transient bug of ours into
        ///                  broker churn.
        ///
        ///                  A required component, not a defaulted one: the
        ///                  compiler makes every construction site answer.
        record Failed(MediationOutcome outcome, boolean ourFault) implements Attempt {
        }

        /// R-57: the app ran the message and rejected it. Terminal on this
        /// one attempt — not retryable, and not [Settled] either, because
        /// what happens to the message's **group** still depends on the
        /// dispatch mode, which only the caller (an ordered head, or a
        /// standalone IMMEDIATE message) knows how to apply.
        record Rejected(MediationOutcome outcome) implements Attempt {
        }
    }
}
