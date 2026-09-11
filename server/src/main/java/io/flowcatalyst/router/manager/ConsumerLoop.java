package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.queue.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/// Pulls from one queue and hands each batch to the router
/// (`docs/spec/router.md` §3.2).
///
/// One loop per queue, on its own virtual thread. **Interruption is how it
/// stops** — there is no cancellation flag to thread through, and every
/// blocking point restores the flag and exits.
///
/// ### The pacing rules are not arbitrary
///
/// - **No pool has room** → parks, **untimed**, on
///   [RouterManager#capacityGate] until a pool signals room has returned or a
///   reconfigure changes what pools exist ([Pool#onCapacityFreed]). This
///   replaced a fixed `Thread.sleep(2s)` here (defect fixed 2026-09-07): on a
///   fast broker (NATS, fetch ≈ 1 ms) several pollers filled a shared pool
///   buffer in well under a second, the workers drained it in a fraction of
///   that, and every poller then sat out the rest of the 2 s pause with the
///   router mostly idle — eight queues together fell to 1,312 deliveries/s at
///   22% CPU where one queue alone reached 7,916/s at 99% CPU. See
///   `docs/spec/admission.md` §0 for why a park on this path must be untimed.
/// - **No pool exists at all** → [#NO_POOLS_PAUSE]. The one case this design
///   cannot make event-driven in general: there is no pool to raise the
///   event. In practice [RouterManager#reconfigure] always creates
///   `DEFAULT-POOL` before any [ConsumerLoop] is started
///   (`docs/spec/router.md` §3.3), so this fires only if something starts a
///   loop against a manager with zero pools registered — a bounded,
///   deliberately rare fallback, not a knob.
/// - **Poll failed** → [#POLL_ERROR_PAUSE], and deliberately **no
///   heartbeat**: a queue whose polls are failing is not alive, and saying
///   otherwise would hide it from the stall detector.
/// - **Empty batch** → [#EMPTY_POLL_PAUSE]. Nothing to do; for a long-polling
///   backend this stacks on top of a wait the poll already did.
/// - **Partial or full batch** → re-polls immediately. Owner ruling
///   2026-09-07: a partial batch used to pause 500 ms on the theory that the
///   queue was draining, but that pause is exactly the same throughput bug
///   as the capacity one — it holds the loop back from work the broker may
///   already have ready. There is no partial-batch pause any more.
public final class ConsumerLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLoop.class);

    /// Messages per poll (spec constant 5). Matches the SQS hard limit and
    /// the NATS default batch, so no backend has to split a request.
    public static final int MAX_POLL = 10;

    /// Fallback for the "no pool exists at all" branch only — see the class
    /// doc. Every other pacing pause but this one, [#POLL_ERROR_PAUSE] and
    /// [#EMPTY_POLL_PAUSE] is event-driven.
    static final Duration NO_POOLS_PAUSE = Duration.ofSeconds(2);
    static final Duration POLL_ERROR_PAUSE = Duration.ofSeconds(1);
    static final Duration EMPTY_POLL_PAUSE = Duration.ofSeconds(1);

    private final Consumer consumer;
    private final RouterManager manager;
    private final Warnings warnings;
    private final Clock clock;

    /// When this loop was built — essentially when its queue started being
    /// polled. Read by [io.flowcatalyst.router.manager.RouterServer#stalledConsumers]
    /// so a loop that has never once polled successfully is not judged
    /// stalled until it has actually had time to (R-36).
    private final Instant startedAt;

    /// When this loop last completed a poll, successfully. Read by the stall
    /// detector; never advanced by a failed poll.
    private final AtomicReference<Instant> lastPoll = new AtomicReference<>();

    /// When this loop most recently *entered* a capacity pause. Together with
    /// [#lastPoll], the fallback source for [#lastAlive] once the loop has
    /// left the pause — while it is still paused, [#lastAlive] reports the
    /// current instant instead (see there): a parked loop has no periodic
    /// tick any more to keep this fresh.
    private final AtomicReference<Instant> lastCapacityPause = new AtomicReference<>();

    /// The pool codes this loop's own last **non-empty** batch was submitted
    /// to (`docs/spec/router.md` §2.4, §6) — what [#awaitCapacity] judges
    /// readiness against instead of the whole process. Empty until the first
    /// batch routes anywhere; an empty poll never touches this, so it keeps
    /// naming the last batch that actually fed something.
    private volatile Set<String> lastFedPools = Set.of();

    /// Whether the loop is currently paused for capacity. Tracked so the
    /// warning fires on the *transition* into "all full" rather than once per
    /// wake — a warning store holding a thousand entries would otherwise be
    /// flooded by a single busy period. **Volatile**: [#lastAlive] reads it
    /// from the stall detector's thread, not this loop's own.
    private volatile boolean pausedForCapacity;

    /// Whether the loop is currently in a run of failing polls. Tracked the
    /// same way as [#pausedForCapacity]: the CONNECTION warning fires once on
    /// the transition into a failing streak, not on every failed poll, and
    /// clears with an INFO on the first poll that succeeds again (§7.3).
    private boolean pollFailing;

    /// Whether [Consumer#poll] is currently on the stack — set immediately
    /// before the call and cleared in a `finally` around it, nothing more.
    /// Read by [#lastAlive] on another thread (the stall watchdog), hence
    /// **volatile**: a backend whose `poll()` blocks untimed waiting on its
    /// broker (`NatsQueue`, `docs/spec/router.md` §3.2, §5 row 47) has no
    /// heartbeat to offer for however long that call runs, and without this
    /// flag [#lastAlive] would have no way to know a poll is even in
    /// progress, let alone ask [Consumer#lastBrokerActivity] whether it is a
    /// legitimate wait or a hang.
    private volatile boolean pollInProgress;

    public ConsumerLoop(Consumer consumer, RouterManager manager, Warnings warnings, Clock clock) {
        this.consumer = consumer;
        this.manager = manager;
        this.warnings = warnings;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    public String queueId() {
        return consumer.identifier();
    }

    /// When this loop started polling. Never changes over the loop's life.
    public Instant startedAt() {
        return startedAt;
    }

    /// The last successful poll, or empty if there has not been one.
    public Optional<Instant> lastPoll() {
        return Optional.ofNullable(lastPoll.get());
    }

    /// The instant the stall detector should treat as this loop's most
    /// recent sign of life, or empty if it has never had one.
    ///
    /// What the stall detector should read instead of [#lastPoll] alone
    /// (`docs/spec/router.md` §2.4, §6): a loop deliberately idle because
    /// every pool it feeds is full is making a decision, not stuck. While
    /// [#pausedForCapacity] is true this reports the **current** instant: the
    /// loop parks untimed on [RouterManager#capacityGate] with no periodic
    /// tick to keep a stored instant fresh, but it is nonetheless
    /// provably alive for as long as this flag reads true — the only two
    /// things it can be doing are that untimed park or (no pools at all) a
    /// bounded sleep, and both clear the flag on the way out, including on
    /// interruption. Once it leaves the pause, this falls back to the later
    /// of [#lastPoll] and the instant the *last* pause began, unchanged from
    /// before.
    ///
    /// ### While a poll is in progress
    ///
    /// [#pollInProgress] alone says nothing about whether *this particular*
    /// call is healthy — a poll that blocks for its own reasons (the
    /// request/response backends' bounded wait) is no different from one
    /// that has actually hung. What distinguishes them is
    /// [Consumer#lastBrokerActivity]: a poll in progress on a consumer that
    /// also has *recent* broker activity is alive on that evidence alone,
    /// even if it is far outside [ConsumerSupervisor#STALL_THRESHOLD] since
    /// the last successful *return* from [Consumer#poll] — exactly the shape
    /// of `NatsQueue`'s continuous subscription sitting idle
    /// (`docs/spec/router.md` §3.2, §5 row 47). A backend with no such
    /// evidence to offer ([Consumer#lastBrokerActivity] empty — every
    /// backend but `NatsQueue` today) falls straight through to the
    /// poll/pause logic above, unchanged. The broker signal only ever makes
    /// this report a **later** instant than the poll/pause logic alone
    /// would — it can rescue a loop that logic would call stale, never hide
    /// one that logic would call fresh.
    public Optional<Instant> lastAlive() {
        if (pausedForCapacity) {
            return Optional.of(clock.instant());
        }
        var poll = lastPoll.get();
        var pause = lastCapacityPause.get();
        Instant fromPollOrPause;
        if (poll == null) {
            fromPollOrPause = pause;
        } else if (pause == null) {
            fromPollOrPause = poll;
        } else {
            fromPollOrPause = poll.isAfter(pause) ? poll : pause;
        }
        if (pollInProgress) {
            var broker = consumer.lastBrokerActivity();
            if (broker.isPresent() && (fromPollOrPause == null || broker.get().isAfter(fromPollOrPause))) {
                return broker;
            }
        }
        return Optional.ofNullable(fromPollOrPause);
    }

    @Override
    public void run() {
        log.atInfo().setMessage("consumer loop started")
                .addKeyValue("queue", queueId())
                .log();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (!awaitCapacity()) {
                    continue;
                }
                if (!pollOnce()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pausedForCapacity = false;
            log.atInfo().setMessage("consumer loop stopped")
                    .addKeyValue("queue", queueId())
                    .log();
        }
    }

    /// @return whether there is room to poll now; false means the caller
    ///         should loop round and check again
    private boolean awaitCapacity() throws InterruptedException {
        // Snapshotted BEFORE the room check, and waited on afterwards: a
        // pool freeing up (or a reconfigure changing what pools exist)
        // between this line and the park below still advances the
        // generation, so the park below returns at once instead of missing
        // it (see CapacityGate).
        var gate = manager.capacityGate();
        var generation = gate.generation();
        if (hasRoom()) {
            if (pausedForCapacity) {
                log.atInfo().setMessage("capacity returned; resuming")
                        .addKeyValue("queue", queueId())
                        .log();
                pausedForCapacity = false;
            }
            return true;
        }
        if (!pausedForCapacity) {
            pausedForCapacity = true;
            warnings.raise(Warnings.Severity.WARNING, "POOL_CAPACITY",
                    "all pools at capacity; pausing " + queueId());
        }
        lastCapacityPause.set(clock.instant());
        if (manager.pools().isEmpty()) {
            // Nobody exists to ever signal the gate — see the class doc.
            Thread.sleep(NO_POOLS_PAUSE);
        } else {
            gate.awaitChangeSince(generation);
        }
        return false;
    }

    /// Whether this consumer should keep polling right now (`docs/spec/router.md`
    /// §2.4, §6): judged against the pools its own last non-empty batch fed,
    /// not the whole process — a consumer that has never fed a pool, or
    /// whose entire remembered set has since been removed by a reconfigure,
    /// falls back to [RouterManager#anyPoolHasCapacity] rather than being
    /// stuck on a set that can no longer answer anything.
    private boolean hasRoom() {
        var fed = lastFedPools;
        if (fed.isEmpty()) {
            return manager.anyPoolHasCapacity();
        }
        var known = manager.pools().keySet();
        var stillTracked = fed.stream().filter(known::contains).collect(Collectors.toSet());
        if (stillTracked.isEmpty()) {
            return manager.anyPoolHasCapacity();
        }
        return manager.poolsHaveCapacity(stillTracked);
    }

    /// @return whether the loop should continue; false means the consumer is
    ///         stopped and this loop is finished
    private boolean pollOnce() throws InterruptedException {
        Consumer.PollResult result;
        pollInProgress = true;
        try {
            result = consumer.poll(MAX_POLL);
        } catch (InterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            // No heartbeat: a queue whose polls are failing is not alive, and
            // recording one here would hide it from the stall detector.
            // One stack trace per outage, not one per poll. An unreachable
            // broker fails every iteration for as long as it is down, and a
            // trace each time is volume rather than information — the first
            // carries the cause, the rest carry its `toString`. `pollFailing`
            // already dates the streak: it is set here and cleared by the
            // first poll that succeeds.
            var event = log.atWarn().setMessage("poll failed").addKeyValue("queue", queueId());
            if (pollFailing) {
                event.addKeyValue("reason", String.valueOf(e)).log();
            } else {
                pollFailing = true;
                event.setCause(e).log();
                warnings.raise(Warnings.Severity.WARNING, "CONNECTION",
                        "poll failed on queue " + queueId() + ": " + e.getMessage());
            }
            Thread.sleep(POLL_ERROR_PAUSE);
            return true;
        } finally {
            pollInProgress = false;
        }

        return switch (result) {
            case Consumer.PollResult.Stopped ignored -> {
                // Terminal. The restart watchdog rebuilds the consumer; this
                // loop does not try to resurrect itself.
                log.atInfo().setMessage("queue reported stopped; ending its loop")
                        .addKeyValue("queue", queueId())
                        .log();
                yield false;
            }
            case Consumer.PollResult.QueueMissing ignored -> {
                // Not a connection failure and not a warning: Integral creates
                // queues on first send, so a listed-but-absent queue is normal
                // (owner ruling 2026-09-11). One INFO, then the manager
                // detaches it and the next config apply rechecks it.
                log.atInfo().setMessage(RouterManager.MISSING_QUEUE_MESSAGE)
                        .addKeyValue("queue", queueId())
                        .log();
                manager.detachMissingConsumer(queueId());
                yield false;
            }
            case Consumer.PollResult.Delivered delivered -> {
                lastPoll.set(clock.instant());
                if (pollFailing) {
                    pollFailing = false;
                    warnings.raise(Warnings.Severity.INFO, "CONNECTION",
                            "queue " + queueId() + " is polling again");
                }
                yield handleBatch(delivered);
            }
        };
    }

    private boolean handleBatch(Consumer.PollResult.Delivered delivered) throws InterruptedException {
        var batch = delivered.messages();
        if (batch.isEmpty()) {
            Thread.sleep(EMPTY_POLL_PAUSE);
            return true;
        }
        lastFedPools = manager.route(batch, consumer);
        // A partial batch re-polls immediately, exactly like a full one
        // (owner ruling 2026-09-07): pausing here on the theory that the
        // queue was draining cost the same throughput the capacity pause
        // did, for the same reason — it held the loop back from work that
        // may already be sitting on the broker.
        return true;
    }
}
