package io.flowcatalyst.server;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router-api-auth.md` rule 7, at the composition root: the mock,
/// benchmark and seed routes are mounted only when `FLOWCATALYST_DEV_MODE` is on.
/// Read off the registrations `Server` actually makes for a router-only instance.
class RouterDevRoutesWiringTest {

    private static List<String> routerPaths(boolean devMode) {
        Env env = Env.load(Map.of(
                "FC_ROUTER_ENABLED", "true",
                "FC_PLATFORM_ENABLED", "false",
                "FLOWCATALYST_DEV_MODE", Boolean.toString(devMode)));
        try (Router router = Router.build(env, null, Clock.systemUTC())) {
            var server = new Server(env, new Server.Mode.RouterOnly(null), Server.Spa.none(), new PrometheusRegistry());
            return server.buildApiAndReaper(router).registry().registrations().stream()
                    .map(r -> r.method() + " " + r.path()).toList();
        }
    }

    @Test
    @DisplayName("outside dev mode the router has /messages but no mock, benchmark or seed routes")
    void noDevRoutesOutsideDevMode() {
        List<String> paths = routerPaths(false);
        assertThat(paths).anyMatch(p -> p.endsWith("/messages") && p.startsWith("POST"));
        assertThat(paths).noneMatch(p -> p.contains("/api/test/") || p.contains("/api/benchmark/")
                || p.endsWith("/api/seed/messages"));
    }

    @Test
    @DisplayName("in dev mode they are mounted")
    void devRoutesInDevMode() {
        List<String> paths = routerPaths(true);
        assertThat(paths).anyMatch(p -> p.endsWith("/api/test/fast"));
        assertThat(paths).anyMatch(p -> p.endsWith("/api/seed/messages"));
    }
}
