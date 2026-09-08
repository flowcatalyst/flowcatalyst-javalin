package io.flowcatalyst.mcp;

import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.Server;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FC_MCP_ENABLED=true` fails the server at startup rather than silently
/// starting nothing ([McpServer] class doc: the MCP HTTP transport has no
/// Vert.x implementation, `docs/vertx-plan.md` Q4).
class McpServerTest {

    @Test
    void mcpEnabledFailsFastAtStartupRatherThanSilentlyServingNothing() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0", "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "false",
                "FC_MCP_ENABLED", "true"));
        var server = new Server(env, Server.Mode.routerOnly(), Server.Spa.none(), new PrometheusRegistry());

        assertThatThrownBy(server::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(McpServer.UNAVAILABLE);
    }
}
