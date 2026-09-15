package io.flowcatalyst.server;

import io.prometheus.metrics.instrumentation.jvm.JvmBufferPoolMetrics;
import io.prometheus.metrics.instrumentation.jvm.JvmGarbageCollectorMetrics;
import io.prometheus.metrics.instrumentation.jvm.JvmMemoryMetrics;
import io.prometheus.metrics.instrumentation.jvm.JvmMemoryPoolAllocationMetrics;
import io.prometheus.metrics.instrumentation.jvm.JvmThreadsMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Registers the JVM memory/GC/thread series on a [PrometheusRegistry].
/// `docs/spec/jvm-memory.md`: the container now fences the heap, so "when do
/// we need more memory" must be answerable from `jvm_memory_pool_collection_used_bytes`
/// (the post-GC live-data figure — the alarm input) and `jvm_gc_collection_seconds`,
/// not from an `OutOfMemoryError` stop reason that arrives after the fact.
///
/// Deliberately five named collectors, never `JvmMetrics.builder()`'s full
/// bundle: class-loading/compilation/runtime-info add scrape noise this
/// isn't the place to take on, and (being reflection-heavier MXBeans) more
/// surface to break under the opt-in GraalVM native build.
///
/// Each collector is registered independently and defensively:
/// [PrometheusRegistry] throws [IllegalArgumentException] when a name is
/// already registered (the same registry instance started twice — a real
/// case, not just a test artifact, since `Main` hands every `Server` the
/// shared [PrometheusRegistry#defaultRegistry]), and a platform missing the
/// underlying MXBean (native image) can throw other unchecked exceptions
/// from inside the collector constructor itself. Neither may fail startup —
/// this logs one WARN with the cause and moves on to the next collector.
public final class JvmMetricsRegistration {

    private static final Logger LOG = LoggerFactory.getLogger(JvmMetricsRegistration.class);

    private JvmMetricsRegistration() {
    }

    public static void register(PrometheusRegistry registry) {
        registerOne(registry, "JvmMemoryMetrics", r -> JvmMemoryMetrics.builder().register(r));
        registerOne(registry, "JvmMemoryPoolAllocationMetrics", r -> JvmMemoryPoolAllocationMetrics.builder().register(r));
        registerOne(registry, "JvmGarbageCollectorMetrics", r -> JvmGarbageCollectorMetrics.builder().register(r));
        registerOne(registry, "JvmThreadsMetrics", r -> JvmThreadsMetrics.builder().register(r));
        registerOne(registry, "JvmBufferPoolMetrics", r -> JvmBufferPoolMetrics.builder().register(r));
    }

    private static void registerOne(PrometheusRegistry registry, String name, Consumer<PrometheusRegistry> registrar) {
        try {
            registrar.accept(registry);
        } catch (RuntimeException e) {
            // IllegalArgumentException: already registered on this (shared/default)
            // registry. Anything else: no such MXBean on this platform (native image).
            // Either way the scrape just carries one fewer series — never a boot failure.
            LOG.atWarn().setMessage("skipping a JVM collector on the Prometheus registry")
                    .addKeyValue("collector", name)
                    .setCause(e)
                    .log();
        }
    }
}
