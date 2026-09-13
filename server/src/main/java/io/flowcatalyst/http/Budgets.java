package io.flowcatalyst.http;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/// Tier-2 local group bulkheads (`docs/spec/admission.md` §2): one semaphore
/// per [Group] that carries a budget, acquired **untimed** on the request's
/// virtual thread before its handler and released after it. A group without
/// a budget is unlimited. Budgets are derived, never configured:
/// [#derived()] gives `LOGIN` and `OIDC` the processor count, because
/// password4j's Argon2 executor is a fixed pool of that size and a login
/// beyond it only queues on the hash pool; `DISPATCH` (which merged the old
/// `INGEST` group, `docs/spec/admission.md` §11.7) carries no budget (owner
/// rulings 2026-09-06).
public final class Budgets {
    /// A held group permit; closing releases it exactly once.
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private static final Permit UNLIMITED = () -> { };

    private final Map<Group, Semaphore> semaphores = new EnumMap<>(Group.class);
    private final Map<Group, Integer> sizes = new EnumMap<>(Group.class);
    private final Map<Group, AtomicInteger> waiting = new EnumMap<>(Group.class);
    private final Map<Group, AtomicInteger> held = new EnumMap<>(Group.class);

    private Budgets(Map<Group, Integer> budgets) {
        budgets.forEach((g, n) -> {
            if (n <= 0) throw new IllegalArgumentException("budget for " + g + " must be positive: " + n);
            semaphores.put(g, new Semaphore(n));
            sizes.put(g, n);
        });
        for (Group g : Group.values()) {
            waiting.put(g, new AtomicInteger());
            held.put(g, new AtomicInteger());
        }
    }

    /// The production budgets: `LOGIN` = `OIDC` = `availableProcessors()`.
    public static Budgets derived() {
        int hashLanes = Runtime.getRuntime().availableProcessors();
        return new Budgets(Map.of(Group.LOGIN, hashLanes, Group.OIDC, hashLanes));
    }

    /// Explicit budgets, for tests. Groups absent from the map are unlimited.
    public static Budgets of(Map<Group, Integer> budgets) {
        return new Budgets(budgets);
    }

    /// No budgets at all — every group unlimited.
    public static Budgets none() {
        return new Budgets(Map.of());
    }

    /// The budget for `group`, empty when unlimited.
    public Optional<Integer> budget(Group group) {
        return Optional.ofNullable(sizes.get(group));
    }

    /// Waits (untimed) for a permit of `group`; a no-op permit when the group
    /// is unlimited or `group` is `null`.
    public Permit acquire(Group group) throws InterruptedException {
        if (group == null) return UNLIMITED;
        Semaphore s = semaphores.get(group);
        if (s == null) return UNLIMITED;
        var w = waiting.get(group);
        var h = held.get(group);
        w.incrementAndGet();
        try {
            s.acquire();
        } finally {
            w.decrementAndGet();
        }
        h.incrementAndGet();
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                h.decrementAndGet();
                s.release();
            }
        };
    }

    public int waiting(Group group) {
        return waiting.get(group).get();
    }

    public int held(Group group) {
        return held.get(group).get();
    }

    /// `fc_bulkhead_waiting{group}` and `fc_bulkhead_held{group}` for the
    /// budgeted groups.
    public MultiCollector collector() {
        return () -> {
            var w = GaugeSnapshot.builder().name("fc_bulkhead_waiting")
                    .help("Requests parked (untimed) on a group bulkhead, by group.");
            var h = GaugeSnapshot.builder().name("fc_bulkhead_held")
                    .help("Requests holding a group bulkhead permit, by group.");
            for (Group g : sizes.keySet()) {
                var labels = Labels.of("group", g.name());
                w.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labels).value(waiting(g)).build());
                h.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labels).value(held(g)).build());
            }
            return MetricSnapshots.builder().metricSnapshot(w.build()).metricSnapshot(h.build()).build();
        };
    }
}
