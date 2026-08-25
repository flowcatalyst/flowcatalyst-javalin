package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.queue.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/// Pulls from one queue and hands each batch to the router
/// (`docs/spec/router.md` §3.2).
///
/// One loop per queue, on its own virtual thread. **Interruption is how it
/// stops** — there is no cancellation flag to thread through, and every
/// blocking point restores the flag and exits.
///
/// ### The pacing rules are not arbitrary
///
/// Each pause answers a different question, which is why there are four of
/// them rather than one:
///
/// - **All pools full** → [#ALL_FULL_PAUSE]. Pulling messages now would only
///   mean handing them straight back, so the loop waits for room instead of
///   churning the broker's visibility windows.
/// - **Poll failed** → [#POLL_ERROR_PAUSE], and deliberately **no
///   heartbeat**: a queue whose polls are failing is not alive, and saying
///   otherwise would hide it from the stall detector.
/// - **Empty batch** → [#EMPTY_POLL_PAUSE]. Nothing to do; for a long-polling
///   backend this stacks on top of a wait the poll already did.
/// - **Partial batch** → [#PARTIAL_BATCH_PAUSE]. Fewer than a full batch
///   suggests the queue is draining, so a brief pause lets it refill rather
///   than spinning on ones and twos. A *full* batch re-polls immediately —
///   there is evidently more work.
public final class ConsumerLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLoop.class);

    /// Messages per poll (spec constant 5). Matches the SQS hard limit and
    /// the NATS default batch, so no backend has to split a request.
    public static final int MAX_POLL = 10;

    static final Duration ALL_FULL_PAUSE = Duration.ofSeconds(2);
    static final Duration POLL_ERROR_PAUSE = Duration.ofSeconds(1);
    static final Duration EMPTY_POLL_PAUSE = Duration.ofSeconds(1);
    static final Duration PARTIAL_BATCH_PAUSE = Duration.ofMillis(500);

    private final Consumer consumer;
    private final RouterManager manager;
    private final Warnings warnings;
    private final Clock clock;

    /// When this loop last completed a poll, successfully. Read by the stall
    /// detector; never advanced by a failed poll.
    private final AtomicReference<Instant> lastPoll = new AtomicReference<>();

    /// Whether the loop is currently paused for capacity. Tracked so the
    /// warning fires on the *transition* into "all full" rather than once per
    /// two seconds — a warning store holding a thousand entries would
    /// otherwise be flooded by a single busy period.
    private boolean pausedForCapacity;

    public ConsumerLoop(Consumer consumer, RouterManager manager, Warnings warnings, Clock clock) {
        this.consumer = consumer;
        this.manager = manager;
        this.warnings = warnings;
        this.clock = clock;
    }

    public String queueId() {
        return consumer.identifier();
    }

    /// The last successful poll, or empty if there has not been one.
    public java.util.Optional<Instant> lastPoll() {
        return java.util.Optional.ofNullable(lastPoll.get());
    }

    @Override
    public void run() {
        log.info("consumer loop started for queue {}", queueId());
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
            log.info("consumer loop stopped for queue {}", queueId());
        }
    }

    /// @return whether there is room to poll now; false means the caller
    ///         should loop round and check again
    private boolean awaitCapacity() throws InterruptedException {
        if (manager.anyPoolHasCapacity()) {
            if (pausedForCapacity) {
                log.info("capacity returned; resuming queue {}", queueId());
                pausedForCapacity = false;
            }
            return true;
        }
        if (!pausedForCapacity) {
            pausedForCapacity = true;
            warnings.raise(Warnings.Severity.WARNING, "POOL_CAPACITY",
                    "all pools at capacity; pausing " + queueId());
        }
        Thread.sleep(ALL_FULL_PAUSE);
        return false;
    }

    /// @return whether the loop should continue; false means the consumer is
    ///         stopped and this loop is finished
    private boolean pollOnce() throws InterruptedException {
        Consumer.PollResult result;
        try {
            result = consumer.poll(MAX_POLL);
        } catch (InterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            // No heartbeat: a queue whose polls are failing is not alive, and
            // recording one here would hide it from the stall detector.
            log.warn("poll failed on queue {}", queueId(), e);
            Thread.sleep(POLL_ERROR_PAUSE);
            return true;
        }

        return switch (result) {
            case Consumer.PollResult.Stopped ignored -> {
                // Terminal. The restart watchdog rebuilds the consumer; this
                // loop does not try to resurrect itself.
                log.info("queue {} reported stopped; ending its loop", queueId());
                yield false;
            }
            case Consumer.PollResult.Delivered delivered -> {
                lastPoll.set(clock.instant());
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
        manager.route(batch, consumer);
        if (batch.size() < MAX_POLL) {
            Thread.sleep(PARTIAL_BATCH_PAUSE);
        }
        return true;
    }
}
