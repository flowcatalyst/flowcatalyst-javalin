package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.observability.WarningStore;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/// The operator warning surface (§9.1), mounted twice.
///
/// `/monitoring/warnings/*` is what the dashboard polls and `/warnings/*` is
/// the plain surface; they overlap deliberately and share these handlers, so
/// the two can never answer differently.
///
/// `DELETE /warnings` and `DELETE /warnings/old` are absent:
/// [WarningStore] has no bulk-remove, only `raise`/`acknowledge`/`cleanup`
/// and the read accessors.
final class WarningRoutes {

    /// Mounts this group. Called by [RouterApi#register].
    static void register(Routes routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/warnings", ctx -> monitoringWarnings(ctx, s));
        routes.get(p + "/monitoring/warnings/unacknowledged", ctx -> unacknowledgedWarnings(ctx, s));
        routes.get(p + "/monitoring/warnings/severity/{severity}", ctx -> warningsBySeverity(ctx, s));
        routes.post(p + "/monitoring/warnings/{id}/acknowledge", ctx -> acknowledgeWarning(ctx, s));
        
        // The plain surface. Same handlers, so the two cannot drift.
        routes.get(p + "/warnings", ctx -> listWarnings(ctx, s));
        routes.post(p + "/warnings/{id}/acknowledge", ctx -> acknowledgeWarning(ctx, s));
        routes.post(p + "/warnings/acknowledge-all", ctx -> acknowledgeAllWarnings(ctx, s));
        routes.get(p + "/warnings/critical", ctx -> criticalWarnings(ctx, s));
        routes.get(p + "/warnings/unacknowledged", ctx -> unacknowledgedWarnings(ctx, s));
        routes.get(p + "/warnings/severity/{severity}", ctx -> warningsBySeverity(ctx, s));
    }

    private static void monitoringWarnings(Exchange ctx, State s) {
        var list = new ArrayList<>(s.warnings().active(Duration.ofMinutes(30)));
        list.sort(Comparator.comparing(WarningStore.Notice::createdAt).reversed());
        ctx.json(list.stream().map(WarningRoutes::wire).toList());
    }

    private static void unacknowledgedWarnings(Exchange ctx, State s) {
        ctx.json(s.warnings().unacknowledged().stream().map(WarningRoutes::wire).toList());
    }

    private static void warningsBySeverity(Exchange ctx, State s) {
        String want = ctx.pathParam("severity");
        ctx.json(s.warnings().snapshot().warnings().stream()
                .filter(w -> matchesSeverity(w.severity(), want))
                .map(WarningRoutes::wire)
                .toList());
    }

    private static void criticalWarnings(Exchange ctx, State s) {
        // Every CRITICAL warning, acknowledged or not (spec: "acked or not") —
        // deliberately NOT WarningStore#critical(), which is unacked-only.
        ctx.json(s.warnings().snapshot().warnings().stream()
                .filter(w -> w.severity() == Warnings.Severity.CRITICAL)
                .map(WarningRoutes::wire)
                .toList());
    }

    private static void listWarnings(Exchange ctx, State s) {
        List<WarningStore.Notice> base = "false".equals(ctx.queryParam("acknowledged"))
                ? s.warnings().unacknowledged()
                : s.warnings().snapshot().warnings();
        var filtered = new ArrayList<>(base);
        String severity = Http.queryParam(ctx, "severity");
        if (!severity.isEmpty()) {
            filtered.removeIf(w -> !matchesSeverity(w.severity(), severity));
        }
        String category = Http.queryParam(ctx, "category");
        if (!category.isEmpty()) {
            filtered.removeIf(w -> !w.category().equalsIgnoreCase(category));
        }
        filtered.sort(Comparator.comparing(WarningStore.Notice::createdAt).reversed());
        ctx.json(filtered.stream().map(WarningRoutes::wire).toList());
    }

    private static void acknowledgeWarning(Exchange ctx, State s) {
        String idText = ctx.pathParam("id");
        UUID id;
        try {
            id = UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            Http.notFound(ctx, "Warning not found: " + idText);
            return;
        }
        if (s.warnings().acknowledge(id)) {
            ctx.json(new Wire.AcknowledgedResponse(true));
        } else {
            Http.notFound(ctx, "Warning not found: " + idText);
        }
    }

    private static void acknowledgeAllWarnings(Exchange ctx, State s) {
        long n = 0;
        for (var w : s.warnings().unacknowledged()) {
            if (s.warnings().acknowledge(w.id())) {
                n++;
            }
        }
        ctx.json(new Wire.AcknowledgedCountResponse(n));
    }

    /// `WARN` is an alias for `WARNING`; otherwise case-insensitive equality
    /// (Go `matchesSeverity`, `handlers_warnings.go:92-97`).
    private static boolean matchesSeverity(Warnings.Severity have, String want) {
        String w = want.toUpperCase(Locale.ROOT);
        if (w.equals("WARN")) {
            return have == Warnings.Severity.WARNING;
        }
        return have.name().equalsIgnoreCase(w);
    }

    private static Wire.WireWarning wire(WarningStore.Notice n) {
        return new Wire.WireWarning(n.id().toString(), n.category(), n.severity().name(), n.message(), n.source(),
                n.createdAt(), n.acknowledged(), n.acknowledgedAt());
    }

    private WarningRoutes() {
    }
}
