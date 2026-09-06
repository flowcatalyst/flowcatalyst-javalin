package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.Server;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

/// Shared plumbing for the HTTP/2 and HTTP/3 transport tests
/// (`docs/spec/http-transport.md` §4): a DB-free [Server] (router-only mode,
/// no SPA — the transport surface under test is `/health`) on explicit free
/// ports above 20000, since the [io.flowcatalyst.server.transport.Http3]
/// `Alt-Svc` header advertises `FC_HTTP3_PORT` as a literal number baked in
/// at connector-build time, which an ephemeral `0` port cannot supply ahead
/// of binding.
final class TransportTestSupport {

    private TransportTestSupport() {
    }

    /// A free TCP port `> 20000`, released before returning: good enough for
    /// a test that binds it milliseconds later (the tiny race is the same one
    /// every "pick a free port, then start a server there" test accepts).
    static int freePort() {
        while (true) {
            try (var socket = new ServerSocket(0)) {
                var port = socket.getLocalPort();
                if (port > 20000) {
                    return port;
                }
            } catch (IOException e) {
                throw new UncheckedTransportTestException(e);
            }
        }
    }

    static Server.Running start(Map<String, String> overrides) {
        var env = new HashMap<String, String>();
        env.put("FC_PLATFORM_ENABLED", "false");
        env.put("FC_ROUTER_ENABLED", "false");
        env.put("FC_METRICS_PORT", String.valueOf(freePort()));
        env.putAll(overrides);
        return new Server(Env.load(env), Server.Mode.routerOnly(), Server.Spa.none(), new PrometheusRegistry()).start();
    }

    static final class UncheckedTransportTestException extends RuntimeException {
        UncheckedTransportTestException(Throwable cause) {
            super(cause);
        }
    }
}
