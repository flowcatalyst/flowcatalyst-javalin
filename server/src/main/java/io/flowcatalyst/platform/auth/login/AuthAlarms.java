package io.flowcatalyst.platform.auth.login;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

import java.util.concurrent.atomic.LongAdder;

/// The counters an operator alarms on at the first increment (ruling
/// C-Q23): a backoff-store error means a login was refused with 503
/// rather than the lock being switched off — correct, and never silent.
/// Process-global by design, like every Prometheus counter; exposed
/// through [#collector()] on the shared registry.
public final class AuthAlarms {

    public static final String BACKOFF_STORE_ERRORS = "fc_auth_backoff_store_errors_total";

    private static final LongAdder BACKOFF_ERRORS = new LongAdder();

    private AuthAlarms() {
    }

    public static void backoffStoreError() {
        BACKOFF_ERRORS.increment();
    }

    public static long backoffStoreErrors() {
        return BACKOFF_ERRORS.sum();
    }

    public static MultiCollector collector() {
        return () -> MetricSnapshots.builder()
                .metricSnapshot(CounterSnapshot.builder()
                        .name(BACKOFF_STORE_ERRORS)
                        .help("Login backoff checks that failed because the attempt store could not be read; each one refused a login (503).")
                        .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder().value(BACKOFF_ERRORS.sum()).build())
                        .build())
                .build();
    }
}
