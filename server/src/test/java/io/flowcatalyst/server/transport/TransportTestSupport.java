package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.Server;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.util.HashMap;
import java.util.Map;

/// Shared plumbing for the HTTP/2 transport tests
/// (`docs/spec/http-transport.md` §4): a DB-free [Server] (router-only mode,
/// no SPA — the transport surface under test is `/health`), every port
/// ephemeral (`0`) and read back from [Server.Running] once bound —
/// never probed-and-released, which races anything else in this JVM (or
/// another process) for the same number in the gap. HTTP/3 was dropped
/// (owner ruling 2026-09-08, `docs/vertx-plan.md` closing section).
final class TransportTestSupport {

    private TransportTestSupport() {
    }

    static Server.Running start(Map<String, String> overrides) {
        var env = new HashMap<String, String>();
        env.put("FC_PLATFORM_ENABLED", "false");
        env.put("FC_ROUTER_ENABLED", "false");
        env.put("FC_METRICS_PORT", "0");
        env.putAll(overrides);
        return new Server(Env.load(env), Server.Mode.routerOnly(), Server.Spa.none(), new PrometheusRegistry()).start();
    }
}
