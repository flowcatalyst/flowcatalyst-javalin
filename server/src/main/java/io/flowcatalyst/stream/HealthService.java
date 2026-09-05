package io.flowcatalyst.stream;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// The stream subsystem's registry of per-projector [Health] objects (stream
/// spec §7) — the bridge the router's `/monitoring` stream section and
/// `/ready` are meant to read (not wired here; this class only exposes the
/// records).
///
/// `IsLive`/`IsReady` are both **false on an empty registry**: a stream
/// subsystem with nothing registered has nothing running and nothing ready,
/// never vacuously either.
public final class HealthService {

    private final Map<String, Health> registry = new ConcurrentHashMap<>();

    /// Registers `health` under its own name. Registering twice under the
    /// same name replaces the earlier entry.
    public void register(Health health) {
        Objects.requireNonNull(health, "health");
        registry.put(health.name(), health);
    }

    /// At least one registered projector is running. `false` when nothing is registered.
    public boolean isLive() {
        return registry.values().stream().anyMatch(Health::isRunning);
    }

    /// Every registered projector is healthy. `false` when nothing is registered.
    public boolean isReady() {
        return !registry.isEmpty() && registry.values().stream().allMatch(Health::isHealthy);
    }

    /// The stream spec §7 wire shape: `{healthy, totalStreams, healthyStreams,
    /// unhealthyStreams, streams[]}`. `healthy` is true only when there is at
    /// least one stream and every one of them is healthy — the same rule as
    /// [#isReady], restated as counts.
    public Aggregate aggregate() {
        List<Health.Snapshot> snapshots = registry.values().stream().map(Health::snapshot).toList();
        int total = snapshots.size();
        long healthyCount = snapshots.stream().filter(Health.Snapshot::healthy).count();
        boolean healthy = total > 0 && healthyCount == total;
        return new Aggregate(healthy, total, (int) healthyCount, total - (int) healthyCount, snapshots);
    }

    public record Aggregate(boolean healthy, int totalStreams, int healthyStreams, int unhealthyStreams,
                             List<Health.Snapshot> streams) {
        public Aggregate {
            streams = List.copyOf(streams);
        }
    }
}
