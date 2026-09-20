package io.flowcatalyst.fnhost.metrics;

import io.flowcatalyst.fnhost.http.InvocationObserver;
import io.flowcatalyst.fnhost.http.Permits;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.reconcile.ReconcileObserver;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/// The function host's own series (`docs/spec/function-host-process.md` §2's
/// table) on one [PrometheusRegistry] — the ONLY class in this module that
/// imports a Prometheus type outside `JvmMetricsRegistration`'s own
/// registration call. Implements both host-side observer contracts
/// ([InvocationObserver], [ReconcileObserver]) so [io.flowcatalyst.fnhost.FnHost]
/// can wire one object into both [io.flowcatalyst.fnhost.http.FnHttpServer]
/// and [io.flowcatalyst.fnhost.reconcile.Reconciler] without either of them
/// knowing what a "metric" is.
public final class FnMetrics implements InvocationObserver, ReconcileObserver {

    // 5ms .. 60s (spec §2's own range for fc_fn_duration_seconds).
    private static final double[] DURATION_BUCKETS =
            {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60};

    private final Counter invocations;
    private final Histogram duration;
    private final Gauge active;
    private final Counter loadErrors;
    private final Counter reconcileTotal;
    private final Gauge lastReconcileSuccessTimestamp;

    /// Set once, from [#permitsReady] — `null` until [FnHttpServer#start]
    /// has run. `fc_fn_permits_available`'s callback and [#sweepDeadAddresses]'s
    /// [Permits#forget] both no-op gracefully before then.
    private volatile Permits permits;

    /// What [#sweepDeadAddresses] diffs against — every address this
    /// instance has seen desired, as of the last reconcile it was told
    /// about.
    private final AtomicReference<Set<FunctionAddress>> knownAddresses = new AtomicReference<>(Set.of());

    public FnMetrics(PrometheusRegistry prometheusRegistry, FunctionRegistry registry) {
        Objects.requireNonNull(prometheusRegistry, "prometheusRegistry");
        Objects.requireNonNull(registry, "registry");

        this.invocations = Counter.builder()
                .name("fc_fn_invocations_total")
                .help("Function invocations, by outcome and listener entry")
                .labelNames("address", "version", "outcome", "entry")
                .register(prometheusRegistry);
        this.duration = Histogram.builder()
                .name("fc_fn_duration_seconds")
                .help("Invocation time, only when the function was entered")
                .labelNames("address")
                .classicUpperBounds(DURATION_BUCKETS)
                .register(prometheusRegistry);
        this.active = Gauge.builder()
                .name("fc_fn_active")
                .help("Invocations in flight")
                .labelNames("address")
                .register(prometheusRegistry);
        this.loadErrors = Counter.builder()
                .name("fc_fn_load_errors_total")
                .help("LoadOutcome.Refused reasons and prepare failures")
                .labelNames("reason")
                .register(prometheusRegistry);
        this.reconcileTotal = Counter.builder()
                .name("fc_fn_reconcile_total")
                .help("Reconcile cycles, by outcome")
                .labelNames("outcome")
                .register(prometheusRegistry);
        this.lastReconcileSuccessTimestamp = Gauge.builder()
                .name("fc_fn_last_reconcile_success_timestamp_seconds")
                .help("Unix time of the last successful reconcile")
                .register(prometheusRegistry);

        GaugeWithCallback.builder()
                .name("fc_fn_loaded")
                .help("Loaded functions")
                .callback(cb -> cb.call(registry.snapshot().size()))
                .register(prometheusRegistry);
        GaugeWithCallback.builder()
                .name("fc_fn_warm")
                .help("Loaded functions exempt from LRU eviction")
                .callback(cb -> {
                    long warm = registry.snapshot().stream().filter(FunctionRegistry.Snapshot::warm).count();
                    cb.call(warm);
                })
                .register(prometheusRegistry);
        GaugeWithCallback.builder()
                .name("fc_fn_permits_available")
                .help("Invocation permits currently available")
                .labelNames("scope", "address")
                .callback(cb -> {
                    Permits p = this.permits;
                    if (p == null) {
                        return;
                    }
                    cb.call(p.hostAvailable(), "host", "");
                    for (FunctionAddress address : p.knownAddresses()) {
                        int available = p.functionAvailable(address);
                        if (available >= 0) {
                            cb.call(available, "function", address.render());
                        }
                    }
                })
                .register(prometheusRegistry);
    }

    // ── InvocationObserver ──────────────────────────────────────────────

    @Override
    public void permitsReady(Permits permits) {
        this.permits = Objects.requireNonNull(permits, "permits");
    }

    @Override
    public void refused(String outcome, FunctionAddress address) {
        refused(outcome, address, InvocationObserver.Entry.PRIVATE);
    }

    @Override
    public void refused(String outcome, FunctionAddress address, InvocationObserver.Entry entry) {
        String label = address == null ? "-" : address.render();
        invocations.labelValues(label, "-", outcome, entry.wireValue()).inc();
    }

    @Override
    public void entered(FunctionAddress address) {
        active.labelValues(address.render()).inc();
    }

    @Override
    public void exited(FunctionAddress address) {
        active.labelValues(address.render()).dec();
    }

    @Override
    public void completed(FunctionAddress address, int version, String outcome, Duration elapsed) {
        completed(address, version, outcome, elapsed, InvocationObserver.Entry.PRIVATE);
    }

    @Override
    public void completed(FunctionAddress address, int version, String outcome, Duration elapsed,
                           InvocationObserver.Entry entry) {
        String rendered = address.render();
        invocations.labelValues(rendered, String.valueOf(version), outcome, entry.wireValue()).inc();
        duration.labelValues(rendered).observe(elapsed.toNanos() / 1_000_000_000.0);
    }

    // ── ReconcileObserver ────────────────────────────────────────────────

    @Override
    public void loadError(String reason) {
        loadErrors.labelValues(reason).inc();
    }

    @Override
    public void reconciled(String outcome, boolean success, Instant now) {
        reconcileTotal.labelValues(outcome).inc();
        if (success) {
            lastReconcileSuccessTimestamp.set(now.toEpochMilli() / 1000.0);
        }
    }

    // ── cardinality: an address leaving desired state drops its series ──

    /// Called from [io.flowcatalyst.fnhost.FnHost]'s wiring of
    /// [io.flowcatalyst.fnhost.reconcile.Reconciler#addPostReconcileListener],
    /// with [io.flowcatalyst.fnhost.reconcile.Reconciler#desiredAddresses]'s
    /// current answer — spec §2: "address series are removed when a function
    /// leaves desired state".
    public void sweepDeadAddresses(Set<FunctionAddress> currentlyDesired) {
        Objects.requireNonNull(currentlyDesired, "currentlyDesired");
        Set<FunctionAddress> previous = knownAddresses.getAndSet(Set.copyOf(currentlyDesired));
        for (FunctionAddress address : previous) {
            if (!currentlyDesired.contains(address)) {
                removeSeriesFor(address);
            }
        }
    }

    private void removeSeriesFor(FunctionAddress address) {
        String rendered = address.render();
        active.remove(rendered);
        duration.remove(rendered);
        invocations.removeIf(labels -> !labels.isEmpty() && labels.get(0).equals(rendered));
        Permits p = this.permits;
        if (p != null) {
            p.forget(address);
        }
    }
}
