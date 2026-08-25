package io.flowcatalyst.router.api.dashboard;

import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end HTTP coverage of [DashboardHandler] (`docs/spec/router.md`
/// §9.6) — the copied `dashboard.html` served with `__FC_API_BASE__`
/// substituted for the real mount prefix.
class DashboardHandlerTest {

    private static TestHttp prefixed;

    @BeforeAll
    static void start() {
        prefixed = new TestHttp(cfg -> DashboardHandler.register(cfg.routes, "/router"));
    }

    @AfterAll
    static void stop() {
        prefixed.close();
    }

    @Test
    @DisplayName("GET <prefix>/monitoring/dashboard serves the dashboard HTML with the prefix substituted")
    void servesAtMonitoringDashboard() {
        var r = prefixed.get("/router/monitoring/dashboard");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("text/html"));
        assertThat(r.body()).contains("window.__API_BASE__ = \"/router\";");
        assertThat(r.body()).doesNotContain("__FC_API_BASE__");
    }

    @Test
    @DisplayName("GET <prefix>/dashboard.html serves the same substituted page")
    void servesAtDashboardHtml() {
        var r = prefixed.get("/router/dashboard.html");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("window.__API_BASE__ = \"/router\";");
    }

    @Test
    @DisplayName("a root mount substitutes an empty API base")
    void rootMountSubstitutesEmptyPrefix() {
        try (var root = new TestHttp(cfg -> DashboardHandler.register(cfg.routes, null))) {
            var r = root.get("/monitoring/dashboard");
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("window.__API_BASE__ = \"\";");
        }
    }

    @Test
    @DisplayName("the page still carries its dashboard chrome, not just the injected script")
    void servesRealDashboardContent() {
        var r = prefixed.get("/router/monitoring/dashboard");
        assertThat(r.body()).contains("Queue Statistics").contains("Pool Statistics")
                .contains("Warnings").contains("In-Flight Messages").contains("Mediating");
    }
}
