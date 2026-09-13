package io.flowcatalyst.http;

import io.flowcatalyst.platform.shared.database.Pools;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.ClassicHistogramBuckets;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/// Request-level admission (owner design, 2026-09-06; `bench/real/RESULTS.md` rounds 4–8;
/// wired for real 2026-09-13, `docs/spec/admission.md` §11.7): per [Group], a FIFO queue
/// and N long-lived virtual-thread workers. The listener pushes a parsed request; a worker
/// pops it, runs the whole chain, hands the response back, and pops the next. Ordering is
/// first-in first-out by construction, and a worker that finishes is already running when
/// it takes the next request, so there is no wake-to-use gap.
///
/// Every real route carries a [Group] (there is no more "MAIN" fallback bucket — the
/// listener resolves an ungrouped registration's effective group itself, §11.7 part B);
/// [#submit] refuses a group this instance was not built with. `NO_DB` never queues here
/// at all: a fresh, unbounded virtual thread per request.
///
/// Sizes are derived, never configured ([#derived]) from the four physical [Pools]
/// (§11.3a): `API_WRITE` = the API pool's ordinary permits (a worker always finds a
/// connection, the gate never waits on the request path); `API_READ` = 2× that (§11.3a
/// item 3, reads release between statements so a worker spends part of its time off the
/// connection); `BFF` = 2× the BFF pool's ordinary permits; `DISPATCH` = the DISPATCH
/// pool's ordinary permits; `LOGIN`/`OIDC` = the processor count (password4j's Argon2
/// pool). Each group's queue holds at most [#QUEUE_MULTIPLIER] × its worker count; a
/// request arriving at a full queue is refused (`#submit` returns `false`) rather than
/// queued, so the caller can answer `503` from the event loop without ever starting a
/// worker (§11.7 part B item 4).
public final class RequestWorkers implements AutoCloseable {
    /// A queue holds at most this many times its worker count before a new
    /// arrival is refused (`docs/spec/admission.md` §11.7 part B item 4).
    public static final int QUEUE_MULTIPLIER = 8;

    /// Classic histogram bucket boundaries (seconds) for `fc_request_queue_wait_seconds`
    /// — the library's own default buckets, which already span sub-millisecond wins
    /// through the 30 s product deadline.
    private static final double[] WAIT_BOUNDS_SECONDS =
            {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30};

    private static final class Pool {
        final String name;
        final int size;
        final int queueBound;
        final LinkedTransferQueue<QueuedTask> queue = new LinkedTransferQueue<>();
        final List<Thread> workers = new ArrayList<>();
        final AtomicInteger busy = new AtomicInteger();
        final AtomicInteger queued = new AtomicInteger();
        final AtomicLong rejected = new AtomicLong();
        final DoubleAdder waitSumSeconds = new DoubleAdder();
        final AtomicLong[] waitBuckets = new AtomicLong[WAIT_BOUNDS_SECONDS.length + 1];

        Pool(String name, int size) {
            if (size < 1) throw new IllegalArgumentException("worker pool size must be positive: " + name + "=" + size);
            this.name = name;
            this.size = size;
            this.queueBound = size * QUEUE_MULTIPLIER;
            for (int i = 0; i < waitBuckets.length; i++) waitBuckets[i] = new AtomicLong();
        }
    }

    private record QueuedTask(long enqueuedAtNanos, Runnable task) {
    }

    private final Map<Group, Pool> groups = new EnumMap<>(Group.class);
    private volatile boolean closed;

    private RequestWorkers(Map<Group, Integer> groupSizes) {
        groupSizes.forEach((g, n) -> groups.put(g, start(new Pool(g.name(), n))));
    }

    /// The production sizing (`docs/spec/admission.md` §11.7 "Workers"), derived from the
    /// four physical [Pools] — `NO_DB` is deliberately absent: it never queues (§9).
    public static RequestWorkers derived(Pools pools) {
        int cores = Runtime.getRuntime().availableProcessors();
        int apiWorkers = pools.api().ordinaryPermits();
        int bffWorkers = pools.bff().ordinaryPermits();
        int dispatchWorkers = pools.dispatch().ordinaryPermits();
        return new RequestWorkers(Map.of(
                Group.API_WRITE, apiWorkers,
                Group.API_READ, 2 * apiWorkers,
                Group.BFF, 2 * bffWorkers,
                Group.DISPATCH, dispatchWorkers,
                Group.LOGIN, cores,
                Group.OIDC, cores));
    }

    /// Explicit sizes, for tests. A group absent from `groupSizes` has no worker pool at
    /// all — [#submit] for it (other than `NO_DB`, always unbounded) throws.
    public static RequestWorkers of(Map<Group, Integer> groupSizes) {
        return new RequestWorkers(groupSizes);
    }

    private static Pool start(Pool p) {
        for (int i = 0; i < p.size; i++) {
            Thread t = Thread.ofVirtual().name("fc-worker-" + p.name + "-" + i).unstarted(() -> loop(p));
            p.workers.add(t);
            t.start();
        }
        return p;
    }

    private static void loop(Pool p) {
        while (true) {
            QueuedTask qt;
            try {
                qt = p.queue.take();
            } catch (InterruptedException e) {
                return;
            }
            p.queued.decrementAndGet();
            recordWait(p, (System.nanoTime() - qt.enqueuedAtNanos()) / 1_000_000_000.0);
            p.busy.incrementAndGet();
            try {
                qt.task().run();
            } catch (Throwable t) {
                // the task owns its own error handling; a worker never dies on one
            } finally {
                p.busy.decrementAndGet();
                Thread.interrupted();
            }
        }
    }

    private static void recordWait(Pool p, double seconds) {
        p.waitSumSeconds.add(seconds);
        int idx = p.waitBuckets.length - 1;
        for (int i = 0; i < WAIT_BOUNDS_SECONDS.length; i++) {
            if (seconds <= WAIT_BOUNDS_SECONDS[i]) {
                idx = i;
                break;
            }
        }
        p.waitBuckets[idx].incrementAndGet();
    }

    private Pool poolOrThrow(Group group) {
        Pool p = groups.get(group);
        if (p == null) {
            throw new IllegalStateException("no worker pool configured for group " + group);
        }
        return p;
    }

    /// Queues `task` for `group`'s workers, or runs it at once, unbounded, for `NO_DB`
    /// (never queues, always returns `true`). For every other group: `true` once queued
    /// (FIFO — this instance's own queue for that group, bounded at [#QUEUE_MULTIPLIER] ×
    /// its worker count); `false`, `task` never run, if the queue was already at its
    /// bound — the caller must answer the request itself (§11.7 part B item 4).
    public boolean submit(Group group, Runnable task) {
        if (closed) throw new IllegalStateException("request workers are closed");
        if (group == Group.NO_DB) {
            Thread.ofVirtual().name("fc-nodb").start(task);
            return true;
        }
        Pool p = poolOrThrow(group);
        int now = p.queued.incrementAndGet();
        if (now > p.queueBound) {
            p.queued.decrementAndGet();
            p.rejected.incrementAndGet();
            return false;
        }
        p.queue.add(new QueuedTask(System.nanoTime(), task));
        return true;
    }

    /// Records a `503` refusal for `group` that this instance did not itself decide (the
    /// Vert.x adapter's queued-request deadline, §4 — a request that was accepted but
    /// whose deadline fired before a worker ever took it, so it is answered from the loop
    /// without running). Counted in the same `fc_request_rejected_total{group}` series as
    /// a queue-full refusal from [#submit]. A no-op for `NO_DB`, which never queues.
    public void markRejected(Group group) {
        if (group == Group.NO_DB) return;
        poolOrThrow(group).rejected.incrementAndGet();
    }

    public int size(Group group) {
        return poolOrThrow(group).size;
    }

    public int queued(Group group) {
        return poolOrThrow(group).queued.get();
    }

    public int busy(Group group) {
        return poolOrThrow(group).busy.get();
    }

    public long rejected(Group group) {
        return poolOrThrow(group).rejected.get();
    }

    /// `fc_request_workers_busy{group}`, `fc_request_queue_depth{group}`,
    /// `fc_request_queue_wait_seconds{group}` (histogram, enqueue → a worker taking the
    /// request) and `fc_request_rejected_total{group}` (`docs/spec/admission.md` §11.7).
    public MultiCollector collector() {
        return () -> {
            var busy = GaugeSnapshot.builder().name("fc_request_workers_busy")
                    .help("Request workers running a request, by group.");
            var depth = GaugeSnapshot.builder().name("fc_request_queue_depth")
                    .help("Requests queued for a worker, by group.");
            var rejected = CounterSnapshot.builder().name("fc_request_rejected_total")
                    .help("Requests answered 503 without a worker because a group's queue was full "
                            + "or its queued-request deadline fired first, by group.");
            var wait = HistogramSnapshot.builder().name("fc_request_queue_wait_seconds")
                    .help("Time a request waited in a group's queue before a worker took it.");
            for (Pool p : groups.values()) {
                var labels = Labels.of("group", p.name);
                busy.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labels).value(p.busy.get()).build());
                depth.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labels).value(p.queued.get()).build());
                rejected.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder().labels(labels).value(p.rejected.get()).build());
                wait.dataPoint(HistogramSnapshot.HistogramDataPointSnapshot.builder()
                        .classicHistogramBuckets(classicBuckets(p))
                        .sum(p.waitSumSeconds.sum())
                        .labels(labels)
                        .build());
            }
            return MetricSnapshots.builder().metricSnapshot(busy.build()).metricSnapshot(depth.build())
                    .metricSnapshot(rejected.build()).metricSnapshot(wait.build()).build();
        };
    }

    private static ClassicHistogramBuckets classicBuckets(Pool p) {
        double[] bounds = new double[WAIT_BOUNDS_SECONDS.length + 1];
        System.arraycopy(WAIT_BOUNDS_SECONDS, 0, bounds, 0, WAIT_BOUNDS_SECONDS.length);
        bounds[WAIT_BOUNDS_SECONDS.length] = Double.POSITIVE_INFINITY;
        long[] deltas = new long[bounds.length];
        for (int i = 0; i < deltas.length; i++) deltas[i] = p.waitBuckets[i].get();
        return ClassicHistogramBuckets.of(bounds, deltas);
    }

    /// Stops taking work; workers finish their current request and exit.
    @Override
    public void close() {
        closed = true;
        groups.values().forEach(p -> p.workers.forEach(Thread::interrupt));
    }
}
