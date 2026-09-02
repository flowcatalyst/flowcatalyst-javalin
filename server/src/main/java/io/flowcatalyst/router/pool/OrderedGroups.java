package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.wire.MediationOutcome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/// The per-group FIFO buffers behind ordered delivery, and the drainer
/// bookkeeping that keeps exactly one worker per group.
///
/// This is where the **Q1 ruling** lives (`docs/spec/router.md` §2.6) — the
/// largest deliberate deviation in the port. The Go router blocks the group
/// for *both* ordered modes; here the two differ:
///
///   - `NEXT_ON_ERROR` — a failed head does not hold its siblings: the
///     group continues with the next message, and the failed one is left to
///     the platform to surface for review and re-queue.
///   - `BLOCK_ON_ERROR` — the group stops, and its queued siblings **leave
///     this buffer** rather than being held here — the spec's phrase is
///     "pending on the platform, **not in router memory**": a head entering
///     backoff may wait indefinitely (Q2 — the router never gives up), so
///     holding its siblings would pin unbounded memory and unbounded broker
///     visibility for an unbounded time. What happens to them *next* — ACKed
///     off the broker and reported to the platform for re-send, or simply
///     NACKed back — is decided by [Pool], not here: that is the A-01 gate
///     (`docs/spec/router-completion.md` §2 ruling 3), and it depends on
///     whether a platform exists to receive the report. This class's job
///     stops at handing every buffered sibling to the caller in FIFO order.
///
/// A single lock guards the buffers and the working flags together, because
/// "is this group being drained?" and "what is in it?" are one decision:
/// answering them separately is how a group ends up with two drainers, or
/// with none while it still holds work.
final class OrderedGroups {

    /// One group's buffer plus whether a drainer currently owns it.
    private static final class Group {
        final Deque<QueuedMessage> queue = new ArrayDeque<>();
        boolean draining;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Group> groups = new HashMap<>();
    private int buffered;

    /// Appends to the back of `group`'s queue.
    ///
    /// @return whether the caller must start a drainer — true only when this
    ///         message found the group idle. Claiming the group and appending
    ///         happen under one lock, so two concurrent submits cannot both
    ///         be told to start one.
    boolean offer(QueuedMessage message) {
        lock.lock();
        try {
            var group = groups.computeIfAbsent(message.group(), ignored -> new Group());
            group.queue.addLast(message);
            buffered++;
            if (group.draining) {
                return false;
            }
            group.draining = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /// Takes the next message for `group`, or empty when the group is done.
    ///
    /// Emptying releases the group entirely — the drainer must exit, and a
    /// later submit starts a fresh one. Holding an empty group would leak one
    /// entry per group id ever seen.
    Optional<QueuedMessage> pollHead(String group) {
        lock.lock();
        try {
            var entry = groups.get(group);
            if (entry == null) {
                return Optional.empty();
            }
            var head = entry.queue.pollFirst();
            if (head == null) {
                groups.remove(group);
                return Optional.empty();
            }
            buffered--;
            return Optional.of(head);
        } finally {
            lock.unlock();
        }
    }

    /// Puts `message` back at the **front**, where it must be to keep FIFO
    /// across a retry. Used when a head is going to be attempted again, and
    /// when a drainer gives up its slot before delivering.
    void reFront(QueuedMessage message) {
        lock.lock();
        try {
            groups.computeIfAbsent(message.group(), ignored -> new Group()).queue.addFirst(message);
            buffered++;
        } finally {
            lock.unlock();
        }
    }

    /// What happens to a group when its head fails — the Q1 ruling as one
    /// decision, so a caller switches exhaustively instead of re-deriving the
    /// per-mode rule at each site.
    sealed interface HeadFailure {

        /// Try the head again, in place, ahead of its siblings. Produced only
        /// from `RETRY_IN_PLACE` — a target that is fine and asked us to
        /// wait — before [Pool#MAX_IN_PIPELINE_ATTEMPTS] is reached. A
        /// REJECTED head (R-57) never produces this: it is terminal on its
        /// first attempt.
        record RetryHead(QueuedMessage head) implements HeadFailure {
        }

        /// The target is **unavailable**, so nothing is wrong with these
        /// messages. Hand the head and every buffered sibling back to be
        /// **NACKed to the broker**, which then owns the retry for as long as
        /// it keeps them (Q2). The group is released, so nothing waits in
        /// memory on an outage of unknown length.
        record ReturnGroup(QueuedMessage head, List<QueuedMessage> siblings) implements HeadFailure {
        }

        /// `NEXT_ON_ERROR`, a REJECTED head (R-57, terminal on its first
        /// attempt): **ACK the failed message** off the broker and carry on
        /// with the next. The router does not retry it in front of its
        /// siblings; the platform surfaces it for review and re-queues it on
        /// resolution.
        record Continue(QueuedMessage failed) implements HeadFailure {
        }

        /// `BLOCK_ON_ERROR`, a REJECTED head (R-57, terminal on its first
        /// attempt): the group stops, and every buffered sibling leaves this
        /// buffer in FIFO order alongside the failed head. What happens to
        /// them at the broker — ACKed and reported for the platform's
        /// re-send, or NACKed back — is [Pool]'s decision (the A-01 gate),
        /// not this record's: it only carries who is affected.
        record BlockGroup(QueuedMessage failed, List<QueuedMessage> siblings) implements HeadFailure {
        }
    }

    /// Decides — and applies — what a failed head does to its group.
    ///
    /// @param head    the message just attempted, carrying the attempts
    ///                already made
    /// @param outcome what the delivery reported
    ///
    /// Unavailability skips straight to returning the group: no number of
    /// retries makes a down target reachable, and the broker is the better
    /// place to wait. A REJECTED outcome (R-57: the app ran the message and
    /// answered badly) is terminal on its **first** attempt and goes
    /// straight to the per-mode decision below — there is no retry-then-
    /// give-up budget here any more, because the classifier now decides
    /// "retry" (ErrorProcess, 502/503/504) versus "give up" (ErrorConfig,
    /// REJECTED) before the pool ever sees the outcome, rather than this
    /// method re-deciding it by counting attempts.
    HeadFailure onHeadFailure(QueuedMessage head, MediationOutcome outcome) {
        return switch (outcome.disposition()) {
            // Nothing was learned about the message, or the target is fine
            // and asked us to wait. The group keeps its head — but not for
            // ever. An in-place retry never returns the message, so while it
            // loops the broker's expiry, redelivery count and dead-letter
            // queue can never act on it, and the stall detector deliberately
            // leaves retrying entries alone. Unbounded, an endpoint answering
            // 429 or ack:false for ever pins the head, EVERY MESSAGE BEHIND
            // IT, and their tracker entries for the life of the process,
            // invisibly.
            //
            // Past the budget the group goes back to the broker — the same
            // treatment an unreachable target gets, and the only outcome that
            // restores the broker's authority over it. This is not the Q1
            // ruling in disguise: BLOCK_ON_ERROR and NEXT_ON_ERROR govern what
            // a TERMINAL failure does to the group, and a deferral is not one.
            case RETRY_IN_PLACE -> head.attempts() + 1 >= Pool.MAX_IN_PIPELINE_ATTEMPTS
                    ? new HeadFailure.ReturnGroup(head, takeAndReleaseGroup(head.group()))
                    : new HeadFailure.RetryHead(head);
            case RETURN_TO_BROKER ->
                    new HeadFailure.ReturnGroup(head, takeAndReleaseGroup(head.group()));
            // R-57: terminal on the first attempt. NEXT_ON_ERROR carries on
            // without the head; BLOCK_ON_ERROR stops and hands the siblings
            // to the platform's re-send; IMMEDIATE never reaches here (it
            // never enters a group) but the switch stays total.
            case REJECTED -> switch (head.message().dispatchMode()) {
                case NEXT_ON_ERROR -> new HeadFailure.Continue(head);
                case BLOCK_ON_ERROR -> new HeadFailure.BlockGroup(head, takeAndReleaseGroup(head.group()));
                case IMMEDIATE -> new HeadFailure.Continue(head);
            };
            // A delivered or undeliverable head is not a failure the group
            // has to react to; the caller has already acted on it.
            case DELIVERED, UNDELIVERABLE -> new HeadFailure.Continue(head);
        };
    }

    /// Empties `group`, releases it, and returns what was queued in FIFO
    /// order so the caller can act on the messages in the order they would
    /// have been delivered.
    ///
    /// Releasing rather than parking is deliberate: a later submit or
    /// redelivery starts a fresh drainer, which is how both the platform's
    /// re-send and the broker's redelivery get picked back up.
    private List<QueuedMessage> takeAndReleaseGroup(String group) {
        lock.lock();
        try {
            var entry = groups.remove(group);
            if (entry == null) {
                return List.of();
            }
            var queued = List.copyOf(entry.queue);
            buffered -= queued.size();
            return queued;
        } finally {
            lock.unlock();
        }
    }

    /// Releases `group` without touching its contents, so a later submit or
    /// redelivery respawns a drainer.
    ///
    /// Used when a drainer exits without finishing — a cancelled slot
    /// acquisition, a cancelled backoff — having already re-fronted whatever
    /// it held. Leaving the flag set would strand the group: it holds work
    /// and nothing is draining it.
    ///
    /// @return whether the group still holds work, so the caller can decide
    ///         whether a fresh drainer is needed right away
    boolean releaseDrainer(String group) {
        lock.lock();
        try {
            var entry = groups.get(group);
            if (entry == null) {
                return false;
            }
            if (entry.queue.isEmpty()) {
                groups.remove(group);
                return false;
            }
            entry.draining = false;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /// Claims `group` for a drainer if one is not already running.
    /// @return whether the caller now owns it
    boolean claimDrainer(String group) {
        lock.lock();
        try {
            var entry = groups.get(group);
            if (entry == null || entry.draining) {
                return false;
            }
            entry.draining = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /// Removes and returns everything buffered, releasing every group — the
    /// pool is stopping.
    List<QueuedMessage> drainAll() {
        lock.lock();
        try {
            var all = new ArrayList<QueuedMessage>(buffered);
            groups.values().forEach(group -> all.addAll(group.queue));
            groups.clear();
            buffered = 0;
            return List.copyOf(all);
        } finally {
            lock.unlock();
        }
    }

    /// Messages waiting in all groups. Counted rather than summed on demand,
    /// because it is read on the submit path to decide backpressure.
    int buffered() {
        lock.lock();
        try {
            return buffered;
        } finally {
            lock.unlock();
        }
    }

    int groupCount() {
        lock.lock();
        try {
            return groups.size();
        } finally {
            lock.unlock();
        }
    }

    /// One group's live buffer state, for the blocked-groups monitoring
    /// surface (R-04, `docs/spec/router-completion.md` §2 ruling 6).
    ///
    /// @param group    the message group id
    /// @param depth    messages currently queued for this group — behind
    ///                 whatever is being attempted right now, since
    ///                 [#pollHead] removes the head from the buffer before a
    ///                 drainer attempts it
    /// @param draining whether a drainer currently owns the group
    record GroupSnapshot(String group, int depth, boolean draining) {
    }

    /// Every group currently held, for [Pool#groupSnapshot]. Counts nothing
    /// and evicts nothing — a read-only view, same contract as
    /// [io.flowcatalyst.router.policy.GroupFlushRegistry#active].
    List<GroupSnapshot> snapshot() {
        lock.lock();
        try {
            return groups.entrySet().stream()
                    .map(e -> new GroupSnapshot(e.getKey(), e.getValue().queue.size(), e.getValue().draining))
                    .toList();
        } finally {
            lock.unlock();
        }
    }
}
