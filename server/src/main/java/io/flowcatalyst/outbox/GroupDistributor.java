package io.flowcatalyst.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/// Per-group FIFO delivery (spec §5): the first [#submit] for an idle group
/// starts a drainer on its own virtual thread (mirrors
/// [io.flowcatalyst.router.pool.Pool]'s `runDrainer`/`OrderedGroups`
/// idiom); the drainer runs its group's items strictly in order and, when
/// `blockOnError` is on, aborts the rest of the group's queue the moment a
/// dispatch reports a permanent failure. `maxConcurrentGroups` (0 =
/// unbounded) gates how many drainers may be actively delivering at once via
/// a [Semaphore] acquired for the drainer's whole run — not per item — so a
/// drainer owns its slot until its queue empties.
public final class GroupDistributor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GroupDistributor.class);

    /// One item handed to a group (spec §4: "`dispatch` = send one item,
    /// `onAbort` = release"). `dispatch` performs the send and every
    /// repository/state-manager bookkeeping it implies, and reports whether
    /// the group may continue; `onAbort` runs instead of `dispatch` for
    /// every item still queued once a prior item in the same group returns
    /// `false` under `blockOnError`.
    public interface GroupTask {
        /// @return whether the drainer should continue with this group's
        /// next item — `false` only for a permanent failure.
        boolean dispatch();

        void onAbort();
    }

    private final boolean blockOnError;
    /// `null` = unbounded (`maxConcurrentGroups <= 0`, spec §5).
    private final Semaphore slots;
    private final ExecutorService drainers = Executors.newVirtualThreadPerTaskExecutor();

    /// Guards `queues` and `draining` together: "is this group being
    /// drained?" and "what is in it?" are one decision, exactly as
    /// [io.flowcatalyst.router.pool.OrderedGroups] documents for the same
    /// shape.
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Deque<GroupTask>> queues = new HashMap<>();
    private final Map<String, Boolean> draining = new HashMap<>();

    public GroupDistributor(int maxConcurrentGroups, boolean blockOnError) {
        this.blockOnError = blockOnError;
        this.slots = maxConcurrentGroups > 0 ? new Semaphore(maxConcurrentGroups) : null;
    }

    /// Enqueues `task` for `group`; starts a drainer when the group was idle.
    public void submit(String group, GroupTask task) {
        boolean mustDrain;
        lock.lock();
        try {
            queues.computeIfAbsent(group, g -> new ArrayDeque<>()).addLast(task);
            mustDrain = draining.putIfAbsent(group, Boolean.TRUE) == null;
        } finally {
            lock.unlock();
        }
        if (mustDrain) {
            drainers.execute(() -> runDrainer(group));
        }
    }

    private void runDrainer(String group) {
        if (slots != null) {
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Never dequeued anything yet — release every buffered item
                // (including this drainer's own head) back to PENDING rather
                // than stranding the group mid-shutdown.
                abortRemaining(group);
                return;
            }
        }
        try {
            drainLoop(group);
        } finally {
            if (slots != null) {
                slots.release();
            }
        }
    }

    private void drainLoop(String group) {
        while (true) {
            GroupTask task = pollHead(group);
            if (task == null) {
                return; // queue emptied itself in pollHead
            }
            boolean mayContinue;
            try {
                mayContinue = task.dispatch();
            } catch (RuntimeException e) {
                LOG.warn("outbox dispatch task failed for group {}", group, e);
                mayContinue = false;
            }
            if (!mayContinue && blockOnError) {
                abortRemaining(group);
                return;
            }
        }
    }

    private GroupTask pollHead(String group) {
        lock.lock();
        try {
            Deque<GroupTask> queue = queues.get(group);
            GroupTask head = queue == null ? null : queue.pollFirst();
            if (head == null) {
                queues.remove(group);
                draining.remove(group);
                return null;
            }
            return head;
        } finally {
            lock.unlock();
        }
    }

    /// Empties and releases `group`, then runs `onAbort` on everything that
    /// was still queued, in FIFO order (spec §5).
    private void abortRemaining(String group) {
        List<GroupTask> rest;
        lock.lock();
        try {
            Deque<GroupTask> queue = queues.remove(group);
            draining.remove(group);
            rest = queue == null ? List.of() : List.copyOf(queue);
        } finally {
            lock.unlock();
        }
        for (GroupTask task : rest) {
            try {
                task.onAbort();
            } catch (RuntimeException e) {
                LOG.warn("outbox onAbort failed for group {}", group, e);
            }
        }
    }

    /// Stops accepting new drainer work and interrupts whatever is running.
    /// Anything left queued was already claimed `IN_PROGRESS` in the
    /// database and self-heals via [OutboxProcessor]'s recovery tick on a
    /// later start; a drainer interrupted mid-[Semaphore#acquire] already
    /// releases its own queue via [#abortRemaining] above.
    @Override
    public void close() {
        drainers.shutdownNow();
    }
}
