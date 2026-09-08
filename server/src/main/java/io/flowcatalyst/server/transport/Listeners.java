package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Env;
import io.javalin.config.JettyConfig;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.util.Optional;

/// Builds the API listener's connectors from [Env] (`docs/spec/http-transport.md`
/// §1): the plain h2c connector (always) and, when TLS material is configured
/// (§2, [TlsMaterial]), the TLS+ALPN connector for h2/http1.1. HTTP/3 (QUIC)
/// was dropped (owner ruling 2026-09-08, `docs/vertx-plan.md` closing
/// section): `FC_HTTP3_ENABLED=true` is now a startup error rather than a
/// silent no-op — see [#install].
///
/// Installed through `cfg.jetty.addConnector`
/// ([io.javalin.config.JettyConfig#addConnector]) from
/// [io.flowcatalyst.server.Server#buildApiAndReaper]. Adding at least one
/// connector this way is what makes Javalin skip the connector it would
/// otherwise build itself from `cfg.jetty.host`/`cfg.jetty.port` — decompiling
/// `io.javalin.jetty.JettyServer#start` (Javalin 7.2.3, no sources jar
/// published) shows `if (connectors.isEmpty()) { connectors = arrayOf(...) }`
/// guarding the default `ServerConnector`, so once [#install] registers ours
/// that branch never runs. No `cfg.jetty.port = -1` trick is needed:
/// `Javalin#start(int)` still runs afterward ([io.flowcatalyst.server.Server#start]
/// calls `api.start(env.apiPort())` unchanged), it just sets
/// `state.jetty.port`, a field nothing reads once custom connectors exist.
public final class Listeners {

    private Listeners() {
    }

    /// Registers every connector the configured [Env] calls for. The h2c
    /// connector is added FIRST: `JettyServer#port()` (and therefore
    /// [io.flowcatalyst.server.Server.Running#apiPort]) reads
    /// `connectors[0].getLocalPort()`, so the plain listener has to stay
    /// connector zero for `apiPort()` to keep meaning what it always has.
    public static void install(JettyConfig jetty, Env env) {
        if (env.http3Enabled()) {
            // HTTP/3 (QUIC) was dropped (owner ruling 2026-09-08): no quiche
            // dependency is on the classpath any more, so honouring this
            // silently would just mean "the flag does nothing" — a startup
            // error is louder and correct.
            throw new IllegalStateException(
                    "FC_HTTP3_ENABLED=true but HTTP/3 support was removed (owner ruling 2026-09-08, "
                            + "docs/vertx-plan.md closing section); unset FC_HTTP3_ENABLED");
        }

        Optional<TlsMaterial> tls = TlsMaterial.resolve(env);

        jetty.addConnector((server, httpConfig) -> h2c(server, httpConfig, env));

        tls.ifPresent(material -> jetty.addConnector((server, httpConfig) -> tls(server, httpConfig, env, material)));
    }

    private static ServerConnector h2c(Server server, HttpConfiguration httpConfig, Env env) {
        var connector = new ServerConnector(server,
                new HttpConnectionFactory(httpConfig),
                new HTTP2CServerConnectionFactory(httpConfig));
        connector.setPort(env.apiPort());
        return connector;
    }

    /// TLS 1.2/1.3 with ALPN -> h2, http/1.1 (§1). `httpConfig` is the SAME
    /// [HttpConfiguration] instance Javalin hands to every connector
    /// builder — mutating it here would leak `SecureRequestCustomizer` (and,
    /// on the HTTP/3 path, `Alt-Svc`) onto the plain h2c connector too, so a
    /// copy is customized instead and the shared instance is left alone.
    private static ServerConnector tls(Server server, HttpConfiguration httpConfig, Env env, TlsMaterial material) {
        var sslContextFactory = sslContextFactory(material);

        var tlsHttpConfig = new HttpConfiguration(httpConfig);
        // SecureRequestCustomizer marks this connector's requests secure —
        // Http3#altSvcHandler relies on exactly that flag to know which
        // listener it is answering for.
        tlsHttpConfig.addCustomizer(new SecureRequestCustomizer());

        var connector = new ServerConnector(server,
                new SslConnectionFactory(sslContextFactory, "alpn"),
                new ALPNServerConnectionFactory("h2", "http/1.1"),
                new HTTP2ServerConnectionFactory(tlsHttpConfig),
                new HttpConnectionFactory(tlsHttpConfig));
        connector.setPort(env.tlsPort());
        return connector;
    }

    static SslContextFactory.Server sslContextFactory(TlsMaterial material) {
        var scf = new SslContextFactory.Server();
        scf.setKeyStore(material.keyStore());
        scf.setKeyManagerPassword(new String(material.keyPassword()));
        return scf;
    }
}
