package io.flowcatalyst.server.transport;

import io.flowcatalyst.http.vertx.VertxListener;
import io.flowcatalyst.server.Env;

import java.util.Optional;

/// Resolves the API listener's TLS configuration from [Env]
/// (`docs/spec/http-transport.md` §1) into the framework-neutral shape
/// [VertxListener.Tls] wants — this class exists so
/// [io.flowcatalyst.server.Server] (which must not import `io.vertx.*`;
/// `NoFrameworkLeakTest` enforces it) never has to reach past [TlsMaterial]
/// for the conversion.
///
/// HTTP/3 (`FC_HTTP3_ENABLED`) was Jetty/quiche-only
/// (`org.eclipse.jetty.quic`, `jetty-quic-quiche-foreign`) and is dropped
/// with the Vert.x cutover (`docs/vertx-plan.md` Q6, closed 2026-09-08):
/// Vert.x 5.1 serves HTTP/3 only through Netty's incubator QUIC native
/// codec, and nothing in this platform needs it — production sits behind an
/// ALB that terminates TLS and never speaks h3 to the target, so h3 was
/// always a client-edge feature of the deployment, not the process. Rather
/// than silently keep accepting the knob and doing nothing,
/// `FC_HTTP3_ENABLED=true` is now a startup error, the same way
/// [TlsMaterial#resolve] already fails loud for a malformed/partial/doubled
/// TLS configuration instead of quietly staying HTTP/1.1.
public final class Listeners {

    private Listeners() {
    }

    /// `Optional.empty()` when no TLS material is configured
    /// ([TlsMaterial#resolve]); throws the same startup errors that class
    /// already throws for a malformed/partial/doubled configuration, plus
    /// its own for `FC_HTTP3_ENABLED=true` (class doc above).
    public static Optional<VertxListener.Tls> resolve(Env env) {
        if (env.http3Enabled()) {
            throw new IllegalStateException("FC_HTTP3_ENABLED=true, but HTTP/3 was dropped in the Vert.x cutover "
                    + "(docs/vertx-plan.md Q6): Vert.x 5 has no HTTP/3 support without Netty's incubator QUIC "
                    + "native codec, which nothing in this platform needs — unset FC_HTTP3_ENABLED");
        }
        return TlsMaterial.resolve(env)
                .map(material -> new VertxListener.Tls(env.tlsPort(), material.keyStore(), material.keyPassword()));
    }
}
