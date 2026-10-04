package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.platform.scheduler.jfr.LanePublishEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/// The dispatchers of the dispatch scheduler (`docs/spec/dispatch-seam.md`
/// §3): N independent lanes that publish what the poller claimed and mark it
/// `QUEUED` in bulk, while the poller is already claiming the next rows.
///
/// ```
/// poller --claim--> lanes[hash(group) % N] --publish--> broker
///   ^  permits (bufferCapacity)    |  bulk UPDATE status = 'QUEUED'
///   +------------ released --------+
/// ```
///
/// No transaction and no row lock spans the publish. Rows the poller has
/// claimed and a lane has not yet settled are kept out of the next claim by
/// their ids ([#inFlightSnapshot] -> `id <> ALL(...)`), not by a status. A
/// double publish is acceptable (the router drops a copy whose original is in
/// its pipeline; the delivery callback is status-guarded).
///
/// ### Ordering
///
/// A group lives in one lane (hash of its name) and a lane publishes in
/// claim order, so a group's order holds while everything succeeds. When job
/// `j` of group `g` is NOT published, jobs of `g` that were claimed AFTER `j`
/// but are still in a channel, or in a claim the poller is running right now,
/// must not be published ahead of `j`, which is back to `PENDING` and will be
/// claimed again. Each lane therefore keeps `poison[g] = generation`:
///
///  1. The poller increments [#claimGeneration] BEFORE it snapshots the
///     in-flight set (see [#nextGeneration]); every job it submits carries
///     that generation.
///  2. When a lane leaves a job of `g` unpublished it first removes the whole
///     batch from the in-flight set, THEN reads the current generation `P`,
///     THEN records `poison[g] = P`.
///  3. A job of `g` whose generation is `<= poison[g]` is dropped when it
///     reaches the lane (not published, removed from the in-flight set, its
///     permit released). A claim that incremented the counter after `P` was
///     read snapshotted the in-flight set after `j` left it, so it contains `j`
///     again, in order; its jobs have a generation `> P`, pass, and the first
///     one that passes clears the entry.
///  4. **A dropped job poisons its group again** (and the rest of its group in
///     the same batch is dropped with it). The spec's rule 1-3 alone leave a
///     hole: with `j1 < j2 < j3` and `j2` already in a channel, a claim taken
///     right after `j1`'s failure snapshots `j2` as in flight, so it returns
///     `j1` and `j3` but not `j2`; those pass the generation test, `j2` is
///     then dropped, and `j3` is published ahead of `j2`. Re-poisoning at the
///     generation read after `j2` has left the in-flight set closes it by the
///     same argument as 2-3, one level down.
///
/// Ungrouped jobs are never poisoned. Each lane's poison map is touched only
/// by that lane's thread. Entries for groups not seen for
/// [#POISON_TTL] are evicted.
final class DispatchLanes implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchLanes.class);

    /// A poisoned group nobody has claimed for this long is forgotten.
    static final Duration POISON_TTL = Duration.ofMinutes(10);
    private static final long POISON_SWEEP_NANOS = Duration.ofMinutes(1).toNanos();
    /// How often an idle lane looks at `closed`.
    private static final long IDLE_POLL_MILLIS = 50;
    /// How long [#close] waits for a lane to finish its current batch.
    static final Duration CLOSE_JOIN = Duration.ofSeconds(15);

    /// One claimed row on its way to a lane, stamped with the claim's generation.
    record Job(ClaimRow row, long generation) {
    }

    private final SchedulerConfig config;
    private final DispatchJobRepository repository;
    private final DispatchPublisher publisher;
    private final Function<ClaimRow, PublishedMessage> messageBuilder;
    private final SchedulerMetrics metrics;
    private final LongSupplier nanoClock;

    private final Semaphore permits;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong claimGeneration = new AtomicLong();
    /// Set by a lane that left something unpublished or failed its status
    /// update; the poller reads and clears it to back off.
    private final AtomicBoolean laneFailed = new AtomicBoolean();
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final Lane[] lanes;
    private final Thread[] threads;
    private volatile boolean closed;

    /// Test seams for the two interleavings the generation rule rests on (both
    /// null in production): run on the poller's thread right after
    /// [#inFlightSnapshot] took its snapshot, and on a lane's thread between
    /// removing a batch from the in-flight set and reading the generation to
    /// poison with. A test blocks in one to run the OTHER side of the race in
    /// exactly that window.
    volatile Runnable afterSnapshotHook;
    volatile Runnable betweenRemovalAndPoisonHook;

    DispatchLanes(SchedulerConfig config, DispatchJobRepository repository, DispatchPublisher publisher,
                  Function<ClaimRow, PublishedMessage> messageBuilder, SchedulerMetrics metrics) {
        this(config, repository, publisher, messageBuilder, metrics, System::nanoTime);
    }

    DispatchLanes(SchedulerConfig config, DispatchJobRepository repository, DispatchPublisher publisher,
                  Function<ClaimRow, PublishedMessage> messageBuilder, SchedulerMetrics metrics,
                  LongSupplier nanoClock) {
        this.config = Objects.requireNonNull(config, "config");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.messageBuilder = Objects.requireNonNull(messageBuilder, "messageBuilder");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.permits = new Semaphore(config.bufferCapacity());
        this.lanes = new Lane[config.dispatchers()];
        this.threads = new Thread[config.dispatchers()];
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = new Lane(i, config.bufferCapacity());
        }
        metrics.gauges(() -> config.bufferCapacity() - permits.availablePermits(), inFlight::size);
    }

    /// Starts the lane threads. Blocking publisher and JDBC calls run on
    /// virtual threads, as everywhere else in this codebase that does blocking
    /// I/O off the request path (outbox, mail).
    void start() {
        for (int i = 0; i < lanes.length; i++) {
            threads[i] = Thread.ofVirtual().name("dispatch-lane-" + i).start(lanes[i]);
        }
    }

    // ── what the poller uses ────────────────────────────────────────────────

    /// Blocks for one permit, then takes up to `max - 1` more without
    /// blocking. Returns how many it holds.
    int acquirePermits(int max) throws InterruptedException {
        permits.acquire();
        int held = 1;
        int more = Math.min(permits.availablePermits(), max - 1);
        // Only the poller acquires, so what is available can only grow
        // between the read and this call.
        if (more > 0 && permits.tryAcquire(more)) {
            held += more;
        }
        return held;
    }

    void releasePermits(int n) {
        if (n > 0) permits.release(n);
    }

    /// Step 3a of the poller loop: MUST be called before [#inFlightSnapshot]
    /// (the ordering rule in the class doc rests on it).
    long nextGeneration() {
        return claimGeneration.incrementAndGet();
    }

    /// The ids claimed and not yet settled, at this instant.
    List<String> inFlightSnapshot() {
        List<String> snapshot = new ArrayList<>(inFlight);
        Runnable hook = afterSnapshotHook;
        if (hook != null) hook.run();
        return snapshot;
    }

    /// Hands claimed rows (claim order) to their lanes: adds them to the
    /// in-flight set and stamps them with `generation`. Never blocks: permits
    /// bound what is in the system to the buffer capacity, which is each
    /// channel's capacity.
    void submit(List<ClaimRow> rows, long generation) {
        for (ClaimRow row : rows) {
            inFlight.add(row.id());
            lane(row).queue.add(new Job(row, generation));
        }
    }

    /// Whether a lane reported a failure since this was last called.
    boolean takeFailure() {
        return laneFailed.getAndSet(false);
    }

    /// True once every permit is back: nothing is claimed-and-unsettled and
    /// every lane is done. (A lane releases its permits last.)
    boolean idle() {
        return permits.availablePermits() == config.bufferCapacity();
    }

    /// Waits up to `timeout` for [#idle].
    boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!idle()) {
            if (System.nanoTime() > deadline) return false;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return idle();
            }
        }
        return true;
    }

    int inFlightCount() {
        return inFlight.size();
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    long generation() {
        return claimGeneration.get();
    }

    int poisonedGroups() {
        int n = 0;
        for (Lane l : lanes) n += l.poisonSize;
        return n;
    }

    private Lane lane(ClaimRow row) {
        String group = groupOf(row);
        int index = group != null
                ? Math.floorMod(group.hashCode(), lanes.length)
                : Math.floorMod(roundRobin.getAndIncrement(), lanes.length);
        return lanes[index];
    }

    /// The row's group, or `null` when it has none (empty counts as none, as
    /// in the published message).
    static String groupOf(ClaimRow row) {
        String g = row.messageGroup();
        return g == null || g.isEmpty() ? null : g;
    }

    /// Stops the lanes: each finishes the batch it is sending and its status
    /// update, then exits. Whatever is still in a channel is left `PENDING`.
    @Override
    public void close() {
        closed = true;
        long deadline = System.nanoTime() + CLOSE_JOIN.toNanos();
        for (Thread t : threads) {
            if (t == null) continue;
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) t.join(Duration.ofNanos(remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ── one lane ────────────────────────────────────────────────────────────

    private static final class Poison {
        final long generation;
        final long setAtNanos;

        Poison(long generation, long setAtNanos) {
            this.generation = generation;
            this.setAtNanos = setAtNanos;
        }
    }

    private final class Lane implements Runnable {
        final int index;
        final LinkedBlockingQueue<Job> queue;
        /// Touched only by this lane's thread.
        private final Map<String, Poison> poison = new HashMap<>();
        private long lastSweepNanos = nanoClock.getAsLong();
        /// A mirror of `poison.size()` for tests and gauges.
        volatile int poisonSize;

        Lane(int index, int capacity) {
            this.index = index;
            this.queue = new LinkedBlockingQueue<>(capacity);
        }

        @Override
        public void run() {
            while (!closed) {
                Job first;
                try {
                    first = queue.poll(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                sweepPoison();
                if (first == null) continue;
                List<Job> batch = new ArrayList<>(Math.min(config.laneBatch(), 128));
                batch.add(first);
                queue.drainTo(batch, config.laneBatch() - 1);
                try {
                    process(batch);
                } catch (RuntimeException e) {
                    LOG.error("dispatch lane {} failed a batch; its jobs stay PENDING", index, e);
                }
            }
        }

        private void sweepPoison() {
            long now = nanoClock.getAsLong();
            if (now - lastSweepNanos < POISON_SWEEP_NANOS) return;
            lastSweepNanos = now;
            long ttl = POISON_TTL.toNanos();
            poison.values().removeIf(p -> now - p.setAtNanos >= ttl);
            poisonSize = poison.size();
        }

        private void process(List<Job> batch) {
            var event = new LanePublishEvent();
            event.begin();
            long startNanos = System.nanoTime();
            Set<String> poisonGroups = new HashSet<>();
            List<Job> keep = new ArrayList<>(batch.size());
            int dropped = dropPoisoned(batch, keep, poisonGroups);

            int published = 0;
            int unpublished = 0;
            int notUpdated = 0;
            boolean failure = false;
            try {
                if (!keep.isEmpty()) {
                    Outcome outcome = publishAndMark(keep, poisonGroups);
                    published = outcome.published;
                    unpublished = outcome.unpublished;
                    notUpdated = outcome.notUpdated;
                    failure = outcome.markFailed || unpublished > 0;
                }
            } catch (RuntimeException e) {
                // Building a message or the publisher itself blew up: nothing
                // here is known to be published, so every kept job stays PENDING.
                LOG.warn("dispatch lane {} could not publish a batch of {}; the jobs stay PENDING",
                        index, keep.size(), e);
                for (Job j : keep) {
                    String g = groupOf(j.row);
                    if (g != null) poisonGroups.add(g);
                }
                published = 0;
                unpublished = keep.size();
                notUpdated = 0;
                failure = true;
            } finally {
                // Counted and recorded BEFORE the permits come back: a caller
                // that waits for the buffer to go idle then sees them.
                metrics.published.add(published);
                metrics.unpublished.add(unpublished);
                metrics.droppedPoisoned.add(dropped);
                metrics.markNotUpdated.add(notUpdated);
                metrics.lanePublish(index, System.nanoTime() - startNanos);
                if (event.shouldCommit()) {
                    event.lane = index;
                    event.taken = batch.size();
                    event.dropped = dropped;
                    event.published = published;
                    event.unpublished = unpublished;
                    event.notUpdated = notUpdated;
                    event.commit();
                }
                settle(batch, poisonGroups, failure);
            }
        }

        /// Spec step 2: splits the batch into what to publish (`keep`) and
        /// what to drop. A dropped job also poisons its group again, and the
        /// rest of its group in this batch goes with it (class doc, point 4).
        private int dropPoisoned(List<Job> batch, List<Job> keep, Set<String> poisonGroups) {
            Set<String> blockedInBatch = new HashSet<>();
            int dropped = 0;
            for (Job j : batch) {
                String g = groupOf(j.row);
                if (g != null) {
                    if (blockedInBatch.contains(g)) {
                        dropped++;
                        continue;
                    }
                    Poison p = poison.get(g);
                    if (p != null) {
                        if (j.generation <= p.generation) {
                            blockedInBatch.add(g);
                            poisonGroups.add(g);
                            dropped++;
                            continue;
                        }
                        // A claim taken after the failure: it includes the
                        // failed job again, in order. Every later job has a
                        // generation at least as high, so the entry is done.
                        poison.remove(g);
                        poisonSize = poison.size();
                    }
                }
                keep.add(j);
            }
            return dropped;
        }

        private record Outcome(int published, int unpublished, int notUpdated, boolean markFailed) {
        }

        /// Spec steps 3-5: publishes through the existing publisher, marks the
        /// published ids `QUEUED` in one update, and records the groups of the
        /// unpublished jobs in `poisonGroups`.
        private Outcome publishAndMark(List<Job> keep, Set<String> poisonGroups) {
            List<PublishedMessage> messages = new ArrayList<>(keep.size());
            for (Job j : keep) {
                messages.add(messageBuilder.apply(j.row));
            }
            Set<String> unpublishedIds = Set.of();
            try {
                publisher.publish(messages);
            } catch (DispatchPublisher.PublishException e) {
                // Exactly the jobs the publisher reports unpublished stay
                // PENDING (ruling O2); one it omits was accepted by the broker.
                // An exception that names none is read as "the whole call
                // failed": QUEUED without a message is the one outcome with no
                // recovery, a duplicate publish is a harmless one.
                unpublishedIds = e.unpublishedJobIds().isEmpty()
                        ? keep.stream().map(j -> j.row.id()).collect(java.util.stream.Collectors.toSet())
                        : Set.copyOf(e.unpublishedJobIds());
                LOG.atWarn().setMessage("batch publish failed; the unpublished job(s) stay PENDING")
                        .addKeyValue("lane", index)
                        .addKeyValue("count", unpublishedIds.size())
                        .addKeyValue("batch", keep.size())
                        .setCause(e)
                        .log();
            }
            List<ClaimRow> ids = new ArrayList<>(keep.size());
            for (Job j : keep) {
                ClaimRow c = j.row;
                if (unpublishedIds.contains(c.id())) {
                    String g = groupOf(c);
                    if (g != null) poisonGroups.add(g);
                    continue;
                }
                ids.add(c);
            }
            int notUpdated = 0;
            boolean markFailed = false;
            if (!ids.isEmpty()) {
                try {
                    int updated = repository.markQueued(ids);
                    notUpdated = ids.size() - updated;
                } catch (RuntimeException e) {
                    // Published, still PENDING: the next claim publishes it
                    // again (a harmless duplicate). Not poisoned: it was
                    // published, so its group's later jobs are not overtaking.
                    markFailed = true;
                    LOG.atWarn().setMessage("mark QUEUED failed after publishing; the job(s) stay PENDING "
                                    + "and will be published again")
                            .addKeyValue("lane", index)
                            .addKeyValue("count", ids.size())
                            .setCause(e)
                            .log();
                }
            }
            return new Outcome(ids.size(), unpublishedIds.size(), notUpdated, markFailed);
        }

        /// Spec step 6, in this order: leave the in-flight set, THEN record
        /// poison (reading the generation after the ids are gone), THEN
        /// release the permits.
        private void settle(List<Job> batch, Set<String> poisonGroups, boolean failure) {
            for (Job j : batch) {
                inFlight.remove(j.row.id());
            }
            Runnable hook = betweenRemovalAndPoisonHook;
            if (hook != null) hook.run();
            if (!poisonGroups.isEmpty()) {
                long p = claimGeneration.get();
                long now = nanoClock.getAsLong();
                for (String g : poisonGroups) {
                    poison.put(g, new Poison(p, now));
                }
                poisonSize = poison.size();
            }
            if (failure) {
                laneFailed.set(true);
            }
            permits.release(batch.size());
        }
    }
}
