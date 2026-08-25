package io.flowcatalyst.router.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// What the router should be running (`docs/spec/router.md` §2.5, §8).
///
/// @param processingPools pools to run, keyed by code
/// @param queues          queues to consume, keyed by URI
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouterConfig(List<PoolSpec> processingPools, List<QueueConfig> queues) {

    public static final RouterConfig EMPTY = new RouterConfig(List.of(), List.of());

    public RouterConfig {
        processingPools = processingPools == null ? List.of() : List.copyOf(processingPools);
        queues = queues == null ? List.of() : List.copyOf(queues);
    }

    /// Merges several sources into one, **first definition wins**.
    ///
    /// Sources are independent documents that may overlap. Taking the first
    /// makes the merge deterministic in source order and — more usefully —
    /// makes a source's position its precedence, so an operator can reason
    /// about which document is authoritative without reading all of them.
    ///
    /// A later duplicate that *disagrees* is reported through `onConflict`
    /// rather than raised as an operator warning: it is a config-authoring
    /// problem, visible in logs, and promoting it to the warning store would
    /// let a misconfigured document flood a surface meant for runtime
    /// conditions. An identical duplicate is silently dropped — two sources
    /// agreeing is not a problem.
    public static RouterConfig merge(List<RouterConfig> sources, ConflictReporter onConflict) {
        Map<String, PoolSpec> pools = new LinkedHashMap<>();
        Map<String, QueueConfig> queues = new LinkedHashMap<>();
        for (var source : sources) {
            source.processingPools().forEach(pool -> keepFirst(pools, pool.code(), pool, "pool", onConflict));
            source.queues().forEach(queue -> keepFirst(queues, queue.queueUri(), queue, "queue", onConflict));
        }
        return new RouterConfig(List.copyOf(pools.values()), List.copyOf(queues.values()));
    }

    private static <T> void keepFirst(Map<String, T> into, String key, T candidate,
                                      String kind, ConflictReporter onConflict) {
        var existing = into.putIfAbsent(key, candidate);
        if (existing != null && !existing.equals(candidate)) {
            onConflict.report(kind + " \"" + key + "\" is defined more than once with conflicting values; keeping the first");
        }
    }

    @FunctionalInterface
    public interface ConflictReporter {
        void report(String message);

        ConflictReporter IGNORE = message -> {
        };
    }
}
