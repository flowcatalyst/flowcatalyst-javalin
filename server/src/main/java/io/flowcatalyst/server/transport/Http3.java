package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Env;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/// HTTP/3 (QUIC) on `FC_HTTP3_PORT` (`docs/spec/http-transport.md` §1, §3):
/// the connector ([#connector]), the `Alt-Svc` customizer for the TLS
/// listener ([#altSvcCustomizer]), and a side-effect-free probe for whether
/// the quiche native library (`jetty-quic-quiche-foreign`, FFM/Java 22+) can
/// actually load on this machine ([#quicheLoadFailure]) — [Listeners] uses
/// the probe to decide whether the QUIC connector is safe to hand to the
/// live [Server] at all: a `QuicheServerConnector` whose native library is
/// missing would fail inside Jetty's own connector startup, which aborts
/// the WHOLE server (the plain and TLS listeners too), not just HTTP/3. The
/// `Alt-Svc` header is advertised only when the QUIC connector is actually
/// installed — a server that cannot speak h3 must not say it can (§1).
public final class Http3 {

    private static final Logger LOG = LoggerFactory.getLogger(Http3.class);

    private Http3() {
    }

    /// Forces [org.eclipse.jetty.quic.quiche.Quiche]'s static initializer to
    /// run: it iterates every `QuicheBinding` found by `ServiceLoader`
    /// (`jetty-quic-quiche-foreign` on this classpath provides
    /// `ForeignQuicheBinding`) and calls `initialize()` on each, throwing if
    /// none succeed — which on the JDK's class-initialization rules means
    /// the outcome is decided (and cached) the first time ANY code touches
    /// `Quiche`, before a real connector is ever added to a live [Server].
    /// Doing this probe first, and only calling [#connector] when it comes
    /// back empty, is what keeps a missing native library from taking the
    /// plain and TLS listeners down with it.
    public static Optional<Throwable> quicheLoadFailure() {
        try {
            Class.forName("org.eclipse.jetty.quic.quiche.Quiche", true, Http3.class.getClassLoader());
            return Optional.empty();
        } catch (Throwable t) {
            return Optional.of(t);
        }
    }

    /// The QUIC connector on `FC_HTTP3_PORT` (UDP). Callable directly by a
    /// test even when [#quicheLoadFailure] is non-empty — the spec (§4) and
    /// the brief both call for the connector to still be BUILT in that case,
    /// only the end-to-end fetch is what gets skipped.
    public static QuicheServerConnector connector(Server server, Env env, SslContextFactory.Server sslContextFactory) {
        var httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new org.eclipse.jetty.server.SecureRequestCustomizer());
        // HTTP/3 needs more QUIC streams than quiche's defaults allow: the client opens three
        // unidirectional streams (control, QPACK encoder, QPACK decoder) before its first request,
        // and the third one hit QUICHE_ERR_STREAM_LIMIT until Jetty's HTTP/3 preset was applied.
        var quicConfig = org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration.configure(
                new QuicheServerQuicConfiguration(pemWorkDirectory()));
        // Known limitation (docs/backlog.md "HTTP/3 sessions hold the graceful stop"): after an
        // h3 exchange whose client simply went away, Jetty's Server.doStop waits the full stop
        // timeout (SHUTDOWN_GRACE, measured 31 s on 2026-09-06) before closing this connector —
        // 0 s idle, 0 s after an h2 exchange. Overriding the connector's Graceful shutdown() or
        // closing its connected end points did not shorten it; the waiting Graceful is deeper
        // in Jetty's QUIC/HTTP3 session stack.
        var connector = new QuicheServerConnector(server, sslContextFactory, quicConfig,
                new HTTP3ServerConnectionFactory(httpConfig));
        connector.setPort(env.http3Port());
        return connector;
    }

    /// `<java.io.tmpdir>/fc-quic-<pid>`, created here with owner-only
    /// permissions (§1): quiche reads the certificate and key from files in
    /// this directory ([QuicheServerQuicConfiguration] writes and deletes
    /// them across start/stop), so the directory itself must not be
    /// world-readable. Deletion at stop is [QuicheServerQuicConfiguration]'s
    /// own `deconfigure` responsibility for the files it wrote; the
    /// directory is left for the OS's normal tmp cleanup, same as any other
    /// `java.io.tmpdir` user — Jetty's own class offers no stop hook to hang
    /// a directory-removal off, and inventing one here would duplicate
    /// exactly the file lifecycle `QuicheServerQuicConfiguration` already
    /// owns.
    private static Path pemWorkDirectory() {
        try {
            var dir = Path.of(System.getProperty("java.io.tmpdir"), "fc-quic-" + ProcessHandle.current().pid());
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException e) {
                LOG.debug("non-POSIX filesystem; leaving {} at its default permissions", dir, e);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// `Alt-Svc: h3=":<FC_HTTP3_PORT>"; ma=86400` (§1), for secure requests
    /// only (`Request#isSecure` — true on the TLS listener, thanks to its
    /// `SecureRequestCustomizer`; false on the plain h2c one, which carries
    /// no such customizer). A [org.eclipse.jetty.server.Handler.Wrapper],
    /// not an `HttpConfiguration.Customizer`: `// SPEC?` — §1 offers either,
    /// but a plain customizer loses this fight. Jetty 12.1 auto-installs its
    /// OWN `HTTP2ServerConnectionFactory.AltSvcCustomizer` on the TLS
    /// listener's `HttpConfiguration` the moment a QUIC connector shares the
    /// `Server` (observed directly: `Alt-Svc: h3=":<port>"` with no `ma`
    /// parameter, appended after any customizer this class registered at
    /// connector-build time, since Jetty's own injection happens later, at
    /// the HTTP/3 connector's own startup) — customizer registration order
    /// therefore cannot be controlled from here. A `Handler.Wrapper`,
    /// installed as the OUTERMOST handler ([#install] below), runs
    /// `Server#handle` — strictly after Jetty finishes the ENTIRE
    /// `HttpConfiguration.customize` phase for the exchange, own injected
    /// customizer included — so `put`ting the header there always has the
    /// last word, and a Javalin `after` filter (Jetty request handling, not
    /// route dispatch) is not a candidate here anyway per §1.
    public static org.eclipse.jetty.server.Handler.Wrapper altSvcHandler(Env env) {
        var value = "h3=\":" + env.http3Port() + "\"; ma=86400";
        return new org.eclipse.jetty.server.Handler.Wrapper() {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response,
                                   org.eclipse.jetty.util.Callback callback) throws Exception {
                if (request.isSecure()) {
                    response.getHeaders().put("Alt-Svc", value);
                } else {
                    // Jetty's own auto-injection (see the class javadoc) is
                    // not scoped to the TLS connector alone: observed adding
                    // a bare Alt-Svc to the PLAIN h2c listener's responses
                    // too, since HTTP2CServerConnectionFactory extends the
                    // same HTTP2ServerConnectionFactory it keys off. Strip
                    // it here rather than merely skip adding — §1 forbids
                    // the header on the plain listener outright.
                    response.getHeaders().remove("Alt-Svc");
                }
                return super.handle(request, response, callback);
            }
        };
    }

    /// Installs [#altSvcHandler] as the outermost [org.eclipse.jetty.server.Server]
    /// handler, ahead of Javalin's own `ServletContextHandler` — see the
    /// javadoc above for why it has to be a handler and not a customizer.
    /// Called from [Listeners#install] via `cfg.jetty.modifyServer`, the
    /// same hook [io.flowcatalyst.server.Server#buildApiAndReaper] already
    /// uses for `setStopTimeout`; both consumers run, in registration order,
    /// before Javalin attaches its own handler underneath whatever is here.
    public static void install(Server server, Env env) {
        server.insertHandler(altSvcHandler(env));
    }
}
