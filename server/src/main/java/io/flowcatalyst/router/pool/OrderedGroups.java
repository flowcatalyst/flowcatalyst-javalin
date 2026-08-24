package io.flowcatalyst.router.pool;

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
///   - `BLOCK_ON_ERROR` — the group stops, and its queued siblings are
///     **handed back to be ACKed off the broker** rather than held here. The spec's phrase is
///     "pending on the platform, **not in router memory**": a head entering
///     backoff may wait indefinitely (Q2 — the router never gives up), so
///     holding its siblings would pin unbounded memory and unbounded broker
///     visibility for an unbounded time. The platform re-sends the whole
///     group in order once a reviewer resolves the failure.
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

        /// `NEXT_ON_ERROR`: the group carries on without the failed message.
        /// The router does not retry it in front of its siblings; the
        /// platform surfaces it for review and re-queues it on resolution.
        record Continue(QueuedMessage failed) implements HeadFailure {
        }

        /// `BLOCK_ON_ERROR`: the group stops and `siblings` must be **ACKed
        /// off the broker** — they are not delivered, and the platform
        /// re-sends the whole group in order once the failure is resolved.
        record BlockGroup(QueuedMessage failed, List<QueuedMessage> siblings) implements HeadFailure {
        }

        /// The head is retried in place, ahead of its siblings. This is what
        /// both ordered modes do in Go, and after the ruling it is reachable
        /// only for a head whose mode is neither ordered value — i.e. never
        /// from the ordered drainer. Kept so the switch is total.
        record RetryHead(QueuedMessage head) implements HeadFailure {
        }
    }

    /// Decides — and applies — what a failed head does to its group.
    ///
    /// The head has already been polled, so `Continue` needs no state change;
    /// `BlockGroup` empties the group and releases it. Both are done under
    /// the lock with the read that chose between them, so a sibling arriving
    /// concurrently cannot be left behind in a group nobody drains.
    HeadFailure onHeadFailure(QueuedMessage head) {
        return switch (head.message().dispatchMode()) {
            case NEXT_ON_ERROR -> new HeadFailure.Continue(head);
            case BLOCK_ON_ERROR -> new HeadFailure.BlockGroup(head, blockAndTakeSiblings(head.group()));
            case IMMEDIATE -> new HeadFailure.RetryHead(head);
        };
    }

    /// Stops draining `group` and hands back every sibling still queued, so
    /// the caller can ACK them off the broker.
    ///
    /// The group is released, not merely paused: a later submit or redelivery
    /// starts a fresh drainer. Siblings come back in FIFO order, so the
    /// caller can account for them in the order they would have been
    /// delivered.
    private List<QueuedMessage> blockAndTakeSiblings(String group) {
        lock.lock();
        try {
            var entry = groups.remove(group);
            if (entry == null) {
                return List.of();
            }
            var siblings = List.copyOf(entry.queue);
            buffered -= siblings.size();
            return siblings;
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
}
