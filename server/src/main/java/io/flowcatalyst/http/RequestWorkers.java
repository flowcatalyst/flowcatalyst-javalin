package io.flowcatalyst.http;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicInteger;

/// Request-level admission (owner design, 2026-09-06; `bench/real/RESULTS.md` rounds 4–8):
/// per group, a FIFO queue and N long-lived virtual-thread workers. The listener pushes
/// a parsed request; a worker pops it, runs the whole chain, hands the response back,
/// and pops the next. Ordering is first-in first-out by construction (the tail of the
/// fair gate), and a worker that finishes is already running when it takes the next
/// request, so there is no wake-to-use gap (the throughput of the unfair gate). With the
/// main group's N equal to the pool's ordinary permits, each worker holds at most one
/// connection and the pool gate never waits; the semaphore is no longer on the hot path.
///
/// Sizes are derived, never configured ([#derived]): the main group is the pool's
/// ordinary permits; `LOGIN` and `OIDC` are the processor count (password4j's Argon2
/// pool); `DISPATCH` gets its own workers so its long customer waits never occupy a
/// main-group slot. Probes and metrics do not come through here (own listener).
public final class RequestWorkers implements AutoCloseable {
    /// The group every ungrouped registration runs in.
    public static final String MAIN = "MAIN";

    private static final class Pool {
        final String name;
        final int size;
        final LinkedTransferQueue<Runnable> queue = new LinkedTransferQueue<>();
        final List<Thread> workers = new ArrayList<>();
        final AtomicInteger busy = new AtomicInteger();
        final AtomicInteger queued = new AtomicInteger();

        Pool(String name, int size) {
            this.name = name;
            this.size = size;
        }
    }

    private final Pool main;
    private final Map<Group, Pool> groups = new EnumMap<>(Group.class);
    private volatile boolean closed;

    private RequestWorkers(int mainSize, Map<Group, Integer> groupSizes) {
        this.main = start(new Pool(MAIN, mainSize));
        groupSizes.forEach((g, n) -> groups.put(g, start(new Pool(g.name(), n))));
    }

    /// The production sizing: `mainWorkers` = the pool's ordinary permits.
    public static RequestWorkers derived(int mainWorkers) {
        int hashLanes = Runtime.getRuntime().availableProcessors();
        return new RequestWorkers(mainWorkers, Map.of(
                Group.LOGIN, hashLanes,
                Group.OIDC, hashLanes,
                Group.DISPATCH, mainWorkers));
    }

    /// Explicit sizes, for tests. Groups absent from the map run in the main pool.
    public static RequestWorkers of(int mainWorkers, Map<Group, Integer> groupSizes) {
        return new RequestWorkers(mainWorkers, groupSizes);
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
            Runnable task;
            try {
                task = p.queue.take();
            } catch (InterruptedException e) {
                return;
            }
            p.queued.decrementAndGet();
            p.busy.incrementAndGet();
            try {
                task.run();
            } catch (Throwable t) {
                // the task owns its own error handling; a worker never dies on one
            } finally {
                p.busy.decrementAndGet();
                Thread.interrupted();
            }
        }
    }

    private Pool poolFor(Group group) {
        Pool p = group == null ? null : groups.get(group);
        return p == null ? main : p;
    }

    /// Queues `task` for `group`'s workers (the main pool when the group has none).
    /// `NO_DB` never queues: a fresh virtual thread per request, unbounded.
    public void submit(Group group, Runnable task) {
        if (closed) throw new IllegalStateException("request workers are closed");
        if (group == Group.NO_DB) {
            Thread.ofVirtual().name("fc-nodb").start(task);
            return;
        }
        Pool p = poolFor(group);
        p.queued.incrementAndGet();
        p.queue.add(task);
    }

    public int size(Group group) {
        return poolFor(group).size;
    }

    public int queued(Group group) {
        return poolFor(group).queued.get();
    }

    public int busy(Group group) {
        return poolFor(group).busy.get();
    }

    /// `fc_request_workers_busy{pool}` and `fc_request_queue_depth{pool}`.
    public MultiCollector collector() {
        return () -> {
            var b = GaugeSnapshot.builder().name("fc_request_workers_busy").help("Request workers running a request, by pool.");
            var q = GaugeSnapshot.builder().name("fc_request_queue_depth").help("Requests queued for a worker, by pool.");
            List<Pool> all = new ArrayList<>();
            all.add(main);
            all.addAll(groups.values());
            for (Pool p : all) {
                var labels = Labels.of("pool", p.name);
                b.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labels).value(p.busy.get()).build());
                q.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labels).value(p.queued.get()).build());
            }
            return MetricSnapshots.builder().metricSnapshot(b.build()).metricSnapshot(q.build()).build();
        };
    }

    /// Stops taking work; workers finish their current request and exit.
    @Override
    public void close() {
        closed = true;
        main.workers.forEach(Thread::interrupt);
        groups.values().forEach(p -> p.workers.forEach(Thread::interrupt));
    }
}
