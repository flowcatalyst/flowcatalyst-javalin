package io.flowcatalyst.router.api.dashboard;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("an incomplete template is refused, not served as an empty 200")
    void refusesAnIncompleteTemplate() throws Exception {
        // This is the failure that was mistaken for a flaky test. The resource
        // is copied into target/classes by the build, so a second Maven run
        // over the same target/ can catch it mid-copy; readAllBytes returns
        // the partial content without complaint. The handler then answers 200,
        // Content-Type text/html, empty body — indistinguishable downstream
        // from a dashboard that legitimately renders nothing, and diagnosed
        // three directories away as "the dashboard test is flaky".
        var complete = template();

        assertThatThrownBy(() -> DashboardHandler.validated(""))
                .as("an empty read is the shape a mid-copy resource actually has")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("__FC_API_BASE__");

        // Truncation past the token is the case a token check alone misses:
        // the token sits in the first 1% of a ~91 KB file, so it survives
        // almost any truncation while the page itself does not.
        String truncated = complete.substring(0, complete.length() / 2);
        assertThat(truncated).as("the token survives this cut; only the closing tag catches it")
                .contains("__FC_API_BASE__");
        assertThatThrownBy(() -> DashboardHandler.validated(truncated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truncated");

        assertThatCode(() -> DashboardHandler.validated(complete)).doesNotThrowAnyException();
    }

    private static String template() throws Exception {
        try (var in = DashboardHandler.class.getResourceAsStream("/router/dashboard.html")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
