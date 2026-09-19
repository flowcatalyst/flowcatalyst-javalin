package io.flowcatalyst.fnhost.metrics;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// [FnMetrics] on its own (`docs/spec/function-host-process.md` §2, tests
/// P2-P6) — direct calls on the [io.flowcatalyst.fnhost.http.InvocationObserver] /
/// [io.flowcatalyst.fnhost.reconcile.ReconcileObserver] methods, asserted
/// against the real Prometheus text scrape of the SAME registry passed to the
/// constructor. `FnHttpServerObserverWiringTest` (package `.http`) is the
/// other half: that `FnHttpServer` calls the right method at the right point
/// for the right HTTP scenario, with a recording fake instead of this real
/// registry.
class FnMetricsTest {

    private static final FunctionAddress ADDR_A = FunctionAddress.parse("m.svc.a");
    private static final FunctionAddress ADDR_B = FunctionAddress.parse("m.svc.b");

    private static PrometheusRegistry registry() {
        return new PrometheusRegistry();
    }

    private static String scrape(PrometheusRegistry registry) {
        try {
            var out = new ByteArrayOutputStream();
            ExpositionFormats.init().getPrometheusTextFormatWriter().write(out, registry.scrape());
            return out.toString(StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// One `metric{k="v",...} value` line, parsed into an order-independent
    /// label map — the Prometheus text writer sorts labels alphabetically by
    /// name, not by declaration order, so a test must compare label SETS,
    /// never a hand-built label string.
    private record MetricLine(java.util.Map<String, String> labels, double value) {
    }

    private static java.util.List<MetricLine> linesFor(String scrape, String metric) {
        Pattern line = Pattern.compile("^" + Pattern.quote(metric) + "\\{([^}]*)}\\s+([0-9.eE+-]+)$", Pattern.MULTILINE);
        Pattern label = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"");
        Matcher m = line.matcher(scrape);
        java.util.List<MetricLine> out = new java.util.ArrayList<>();
        while (m.find()) {
            java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
            Matcher lm = label.matcher(m.group(1));
            while (lm.find()) {
                labels.put(lm.group(1), lm.group(2));
            }
            out.add(new MetricLine(labels, Double.parseDouble(m.group(2))));
        }
        return out;
    }

    private static double valueOf(String scrape, String metric, java.util.Map<String, String> labels) {
        for (MetricLine l : linesFor(scrape, metric)) {
            if (l.labels().equals(labels)) {
                return l.value();
            }
        }
        throw new AssertionError("no line for " + metric + labels + " in:\n" + scrape);
    }

    private static boolean hasLine(String scrape, String metric, java.util.Map<String, String> labels) {
        return linesFor(scrape, metric).stream().anyMatch(l -> l.labels().equals(labels));
    }

    /// A metric with NO label names renders `metric value` — no braces at
    /// all in the Prometheus text format, unlike a labelled metric.
    private static double valueOfNoLabels(String scrape, String metric) {
        Pattern p = Pattern.compile("(?m)^" + Pattern.quote(metric) + "\\s+([0-9.eE+-]+)$");
        Matcher m = p.matcher(scrape);
        if (!m.find()) {
            throw new AssertionError("no line for " + metric + " in:\n" + scrape);
        }
        return Double.parseDouble(m.group(1));
    }

    // ── P2: each outcome label is produced by exactly the call that defines it ──

    @Test
    void p2_eachOutcomeIncrementsOnlyItsOwnSeries() {
        PrometheusRegistry registry = registry();
        FnMetrics metrics = new FnMetrics(registry, new FunctionRegistry(10));

        metrics.refused("busy", ADDR_A);
        metrics.refused("unauthorized", ADDR_A);
        metrics.refused("unavailable", ADDR_A);
        metrics.refused("not_found", null);
        metrics.completed(ADDR_A, 1, "ok", Duration.ofMillis(1));
        metrics.completed(ADDR_A, 1, "client_error", Duration.ofMillis(1));
        metrics.completed(ADDR_A, 1, "retry", Duration.ofMillis(1));
        metrics.completed(ADDR_A, 1, "error", Duration.ofMillis(1));
        metrics.completed(ADDR_A, 1, "timeout", Duration.ofMillis(1));

        String scrape = scrape(registry);
        String rendered = ADDR_A.render();
        assertThat(valueOf(scrape, "fc_fn_invocations_total", java.util.Map.of("address", rendered, "version", "-", "outcome", "busy")))
                .isEqualTo(1);
        assertThat(valueOf(scrape, "fc_fn_invocations_total", java.util.Map.of("address", rendered, "version", "-", "outcome", "unauthorized")))
                .isEqualTo(1);
        assertThat(valueOf(scrape, "fc_fn_invocations_total", java.util.Map.of("address", rendered, "version", "-", "outcome", "unavailable")))
                .isEqualTo(1);
        assertThat(valueOf(scrape, "fc_fn_invocations_total", java.util.Map.of("address", "-", "version", "-", "outcome", "not_found")))
                .isEqualTo(1);
        for (String outcome : new String[] {"ok", "client_error", "retry", "error", "timeout"}) {
            assertThat(valueOf(scrape, "fc_fn_invocations_total", java.util.Map.of("address", rendered, "version", "1", "outcome", outcome)))
                    .as("outcome " + outcome).isEqualTo(1);
        }
        // Absence: exactly 9 series exist for fc_fn_invocations_total — no collapsing,
        // no stray series (e.g. counting a refusal as "ok").
        assertThat(linesFor(scrape, "fc_fn_invocations_total")).hasSize(9);
    }

    // ── P3: duration observed only when the function was entered ──

    @Test
    void p3_durationObservedOnlyForCompletedNeverForRefused() {
        PrometheusRegistry registry = registry();
        FnMetrics metrics = new FnMetrics(registry, new FunctionRegistry(10));

        metrics.refused("busy", ADDR_A);
        metrics.refused("unauthorized", ADDR_A);
        String afterRefusals = scrape(registry);
        assertThat(hasLine(afterRefusals, "fc_fn_duration_seconds_count", java.util.Map.of("address", ADDR_A.render())))
                .as("mutant: observe always — a refusal must add no observation").isFalse();

        metrics.completed(ADDR_A, 1, "ok", Duration.ofMillis(20));
        String afterCompleted = scrape(registry);
        assertThat(valueOf(afterCompleted, "fc_fn_duration_seconds_count", java.util.Map.of("address", ADDR_A.render())))
                .isEqualTo(1);
        assertThat(valueOf(afterCompleted, "fc_fn_duration_seconds_sum", java.util.Map.of("address", ADDR_A.render())))
                .isCloseTo(0.02, org.assertj.core.data.Offset.offset(0.005));
    }

    // ── P4: fc_fn_active rises on entry, falls on exit — independently per address ──

    @Test
    void p4_activeRisesOnEntryFallsOnExit() {
        PrometheusRegistry registry = registry();
        FnMetrics metrics = new FnMetrics(registry, new FunctionRegistry(10));

        metrics.entered(ADDR_A);
        assertThat(valueOf(scrape(registry), "fc_fn_active", java.util.Map.of("address", ADDR_A.render()))).isEqualTo(1);

        metrics.entered(ADDR_A); // a second concurrent call to the SAME address
        assertThat(valueOf(scrape(registry), "fc_fn_active", java.util.Map.of("address", ADDR_A.render()))).isEqualTo(2);

        metrics.entered(ADDR_B);
        assertThat(valueOf(scrape(registry), "fc_fn_active", java.util.Map.of("address", ADDR_B.render())))
                .as("mutant: decrement only on success — a DIFFERENT address must not be touched").isEqualTo(1);

        metrics.exited(ADDR_A);
        assertThat(valueOf(scrape(registry), "fc_fn_active", java.util.Map.of("address", ADDR_A.render()))).isEqualTo(1);
        metrics.exited(ADDR_A);
        assertThat(valueOf(scrape(registry), "fc_fn_active", java.util.Map.of("address", ADDR_A.render())))
                .as("mutant: decrement only on success — this path is throw/timeout too").isEqualTo(0);
        assertThat(valueOf(scrape(registry), "fc_fn_active", java.util.Map.of("address", ADDR_B.render())))
                .as("ADDR_B must be untouched by ADDR_A's exits").isEqualTo(1);
    }

    // ── P5: an address leaving desired state drops its series; unknown addresses never appear as a label ──

    @Test
    void p5_seriesDisappearWhenAddressLeavesDesiredStateAndUnknownAddressesNeverAppear() {
        PrometheusRegistry registry = registry();
        FnMetrics metrics = new FnMetrics(registry, new FunctionRegistry(10));
        var permits = new io.flowcatalyst.fnhost.http.Permits(5);
        metrics.permitsReady(permits);
        permits.tryAcquire(ADDR_A, 3); // sizes a per-function semaphore for ADDR_A

        metrics.entered(ADDR_A);
        metrics.completed(ADDR_A, 1, "ok", Duration.ofMillis(5));
        metrics.exited(ADDR_A);
        String before = scrape(registry);
        assertThat(hasLine(before, "fc_fn_active", java.util.Map.of("address", ADDR_A.render()))).isTrue();
        assertThat(hasLine(before, "fc_fn_duration_seconds_count", java.util.Map.of("address", ADDR_A.render()))).isTrue();
        assertThat(linesFor(before, "fc_fn_invocations_total")).isNotEmpty();
        assertThat(hasLine(before, "fc_fn_permits_available",
                java.util.Map.of("scope", "function", "address", ADDR_A.render())))
                .as("Permits sized a semaphore for ADDR_A — the gauge must see it").isTrue();

        // ADDR_A is still desired: a sweep against a set that still contains it changes nothing.
        metrics.sweepDeadAddresses(Set.of(ADDR_A));
        assertThat(hasLine(scrape(registry), "fc_fn_active", java.util.Map.of("address", ADDR_A.render())))
                .as("still desired — must not be swept").isTrue();

        // ADDR_A leaves desired state.
        metrics.sweepDeadAddresses(Set.of());
        String after = scrape(registry);
        assertThat(hasLine(after, "fc_fn_active", java.util.Map.of("address", ADDR_A.render())))
                .as("mutant: keep series").isFalse();
        assertThat(hasLine(after, "fc_fn_duration_seconds_count", java.util.Map.of("address", ADDR_A.render())))
                .isFalse();
        assertThat(linesFor(after, "fc_fn_invocations_total"))
                .as("mutant: label by requested address").isEmpty();
        assertThat(hasLine(after, "fc_fn_permits_available",
                java.util.Map.of("scope", "function", "address", ADDR_A.render())))
                .as("Permits#forget must have dropped ADDR_A's semaphore too").isFalse();

        // A 404 for an unknown address is address="-", never the requested (nonexistent) address.
        metrics.refused("not_found", null);
        String afterUnknown = scrape(registry);
        assertThat(hasLine(afterUnknown, "fc_fn_invocations_total", java.util.Map.of("address", "-", "version", "-", "outcome", "not_found")))
                .isTrue();
        for (MetricLine line : linesFor(afterUnknown, "fc_fn_invocations_total")) {
            assertThat(line.labels().values()).as("mutant: label by requested address")
                    .doesNotContain(ADDR_A.render());
        }
    }

    // ── P6: fc_fn_last_reconcile_success_timestamp_seconds moves on success, not on failure ──

    @Test
    void p6_reconcileTimestampMovesOnSuccessNotOnFailure() {
        PrometheusRegistry registry = registry();
        FnMetrics metrics = new FnMetrics(registry, new FunctionRegistry(10));

        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        metrics.reconciled("changed", true, t1);
        assertThat(valueOfNoLabels(scrape(registry), "fc_fn_last_reconcile_success_timestamp_seconds"))
                .isEqualTo(t1.getEpochSecond());
        assertThat(valueOf(scrape(registry), "fc_fn_reconcile_total", java.util.Map.of("outcome", "changed"))).isEqualTo(1);

        Instant t2 = t1.plusSeconds(3600);
        metrics.reconciled("failed", false, t2);
        assertThat(valueOfNoLabels(scrape(registry), "fc_fn_last_reconcile_success_timestamp_seconds"))
                .as("mutant: move on failure").isEqualTo(t1.getEpochSecond());
        assertThat(valueOf(scrape(registry), "fc_fn_reconcile_total", java.util.Map.of("outcome", "failed"))).isEqualTo(1);

        Instant t3 = t2.plusSeconds(60);
        metrics.reconciled("not_modified", true, t3);
        assertThat(valueOfNoLabels(scrape(registry), "fc_fn_last_reconcile_success_timestamp_seconds"))
                .as("P6 pinned: not-modified IS a success — the platform answered").isEqualTo(t3.getEpochSecond());
        assertThat(valueOf(scrape(registry), "fc_fn_reconcile_total", java.util.Map.of("outcome", "not_modified"))).isEqualTo(1);
    }

    // ── fc_fn_load_errors_total / fc_fn_loaded / fc_fn_warm / fc_fn_permits_available sanity ──

    @Test
    void loadErrorsCountedByReason() {
        PrometheusRegistry registry = registry();
        FnMetrics metrics = new FnMetrics(registry, new FunctionRegistry(10));

        metrics.loadError("ARTIFACT:DigestMismatch");
        metrics.loadError("ARTIFACT:DigestMismatch");
        metrics.loadError("LOAD:ENTRYPOINT_NOT_FOUND");

        String scrape = scrape(registry);
        assertThat(valueOf(scrape, "fc_fn_load_errors_total", java.util.Map.of("reason", "ARTIFACT:DigestMismatch"))).isEqualTo(2);
        assertThat(valueOf(scrape, "fc_fn_load_errors_total", java.util.Map.of("reason", "LOAD:ENTRYPOINT_NOT_FOUND"))).isEqualTo(1);
    }

    @Test
    void loadedAndWarmGaugesReflectTheRegistryLive() {
        PrometheusRegistry registry = registry();
        FunctionRegistry functionRegistry = new FunctionRegistry(10);
        new FnMetrics(registry, functionRegistry);
        assertThat(valueOfNoLabels(scrape(registry), "fc_fn_loaded")).isEqualTo(0);
        assertThat(valueOfNoLabels(scrape(registry), "fc_fn_warm")).isEqualTo(0);
    }

    @Test
    void permitsAvailableReportsHostScopeOnceWired() {
        PrometheusRegistry registry = registry();
        FnMetrics metrics = new FnMetrics(registry, new FunctionRegistry(10));
        assertThat(hasLine(scrape(registry), "fc_fn_permits_available", java.util.Map.of("scope", "host", "address", "")))
                .as("no Permits wired yet — the callback must not fail the whole scrape").isFalse();

        var permits = new io.flowcatalyst.fnhost.http.Permits(5);
        metrics.permitsReady(permits);
        assertThat(valueOf(scrape(registry), "fc_fn_permits_available", java.util.Map.of("scope", "host", "address", ""))).isEqualTo(5);
    }
}
