package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.util.LinkedHashMap;
import java.util.Map;

/// Circuit-breaker inspection and reset (§9.1).
///
/// A null [BreakerRegistry] is the "provider not configured" state: the list
/// answers `{}` and every lookup or reset answers 503.
final class BreakerRoutes {

    /// Mounts this group. Called by [RouterApi#register].
    static void register(Routes routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/circuit-breakers", ctx -> circuitBreakers(ctx, s));
        routes.get(p + "/monitoring/circuit-breakers/{name}/state", ctx -> circuitBreakerState(ctx, s));
        routes.post(p + "/monitoring/circuit-breakers/{name}/reset", ctx -> resetBreaker(ctx, s));
        routes.post(p + "/monitoring/circuit-breakers/reset-all", ctx -> resetAllBreakers(ctx, s));
    }

    private static void circuitBreakers(Exchange ctx, State s) {
        if (s.breakers() == null) {
            ctx.json(Map.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        Map<String, Wire.DashboardCircuitBreaker> out = new LinkedHashMap<>();
        s.breakers().snapshot().forEach((name, stats) -> out.put(name, toDashboard(name, stats)));
        ctx.json(out);
    }

    private static Wire.DashboardCircuitBreaker toDashboard(String name, CircuitBreaker.Stats st) {
        long total = st.successes() + st.failures();
        double rate = total > 0 ? (double) st.failures() / total : 0.0;
        // rejectedCalls and bufferSize are always 0 — spec §9.1: the Java
        // CircuitBreaker never rejects a buffered-call count separately, and
        // has no notion of "buffer size" distinct from its fixed window.
        return new Wire.DashboardCircuitBreaker(name, st.state().wireValue(), st.successes(), st.failures(), 0, rate,
                st.recentFailures(), 0);
    }

    private static void circuitBreakerState(Exchange ctx, State s) {
        if (s.breakers() == null) {
            Http.serviceUnavailable(ctx, "breakers not configured");
            return;
        }
        String name = ctx.pathParam("name"); // Javalin already URL-decodes the segment
        var stats = s.breakers().snapshot().get(name);
        if (stats == null) {
            Http.notFound(ctx, "breaker not found: " + name);
            return;
        }
        ctx.json(new Wire.CircuitBreakerStateResponse(name, stats.state().wireValue(), stats.successes(), stats.failures(),
                stats.recentFailures()));
    }

    private static void resetBreaker(Exchange ctx, State s) {
        if (s.breakers() == null) {
            Http.serviceUnavailable(ctx, "breakers not configured");
            return;
        }
        String name = ctx.pathParam("name");
        if (!s.breakers().reset(name)) {
            Http.notFound(ctx, "breaker not found: " + name);
            return;
        }
        ctx.json(new Wire.BreakerResetResponse(true, name));
    }

    private static void resetAllBreakers(Exchange ctx, State s) {
        if (s.breakers() == null) {
            Http.serviceUnavailable(ctx, "breakers not configured");
            return;
        }
        ctx.json(new Wire.BreakerResetAllResponse(s.breakers().resetAll()));
    }

    private BreakerRoutes() {
    }
}
