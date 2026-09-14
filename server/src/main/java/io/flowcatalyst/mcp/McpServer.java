package io.flowcatalyst.mcp;

import io.flowcatalyst.platform.shared.json.Json;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/// The MCP server subsystem: streamable HTTP at `/mcp` (POST + GET-as-SSE +
/// DELETE) and `GET /health` → 200, on its own `FC_MCP_BIND:FC_MCP_PORT`
/// listener — its own, single-event-loop [Vertx] instance and [HttpServer],
/// the same shape as [io.flowcatalyst.http.vertx.VertxMediationClient]'s
/// "own instance, never the API listener's" (`docs/vertx-migration-brief.md`
/// phase 3 — neither the API listener nor the mediation client exposes its
/// `Vertx`, so there is nothing to share; see the migration report's "shared
/// Vertx instance" note). One [McpSyncServer] — and therefore one
/// [PlatformClient] — is shared across every request; the tool/resource
/// catalogue is stateless.
///
/// The transport is [VertxStreamableServerTransportProvider], this repo's own
/// implementation of the MCP SDK's `McpStreamableServerTransportProvider`
/// over vertx-web (`docs/spec/mcp.md` §1) — replacing the SDK's
/// servlet-based `HttpServletStreamableServerTransportProvider`, which needed
/// Javalin/Jetty on the classpath for MCP alone. That dependency is gone from
/// the tree as of this phase.
///
/// The transport's security validator (Origin/Host — `docs/spec/mcp.md` §1)
/// is **derived**, never a knob (`CLAUDE.md` "no tuning; defaults are the
/// product"): [#deriveSecurityValidator] builds it from nothing but the
/// listener's own bound address — `localhost`, `127.0.0.1`, `[::1]`, and
/// whatever `host` (`FC_MCP_BIND`) resolved to, each with the listener's
/// actual bound port, `http://` and `https://` both. Those are every address
/// a client on this same machine — including a browser-based MCP client
/// sending a real `Origin` header — could legitimately use to reach this
/// exact listener; anything else with an `Origin` header stays refused
/// (`403`), and a request with no `Origin` header at all is unaffected
/// either way. The provider's own default (`DefaultServerTransportSecurityValidator`
/// with empty allow-lists — see [VertxStreamableServerTransportProvider.Builder])
/// would refuse a legitimate same-machine browser client outright, which is
/// what this method exists to fix.
///
/// `io.modelcontextprotocol.server.McpServer` (the SDK's builder entry
/// point) shares this class's simple name; every reference to it below is
/// fully qualified rather than imported; that is the one deliberate
/// exception to CONVENTIONS §8's "import it" rule in this file.
public final class McpServer {

    private static final Logger LOG = LoggerFactory.getLogger(McpServer.class);

    /// The graceful-stop bound (`docs/spec/mcp.md` §1): [Running#stop] closes
    /// the listener, then the MCP session/transport, within this long.
    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    /// Copied verbatim from Go's `internal/mcp/server.go` `instructions`.
    private static final String INSTRUCTIONS = """
            FlowCatalyst MCP server — read-only access to the platform's metadata.

            Start with "whoami" to learn your identity, scope, and accessible clients/apps,
            then "list_my_applications" for what you can act on. Use the list_* tools to
            browse event types, subscriptions, applications, and roles; the get_* tools to
            fetch one by id; "get_schema" for an event type's JSON Schema; "get_openapi"
            and "get_application_capabilities" for an application's API surface. All
            responses are JSON.""";

    private McpServer() {
    }

    /// A started MCP listener.
    public static final class Running {
        private final Vertx vertx;
        private final HttpServer httpServer;
        private final McpSyncServer mcpServer;
        private final int port;

        private Running(Vertx vertx, HttpServer httpServer, McpSyncServer mcpServer, int port) {
            this.vertx = vertx;
            this.httpServer = httpServer;
            this.mcpServer = mcpServer;
            this.port = port;
        }

        public int port() {
            return port;
        }

        /// Stops accepting new connections (the listener drains in-flight
        /// requests within [#STOP_TIMEOUT], the same as every other listener —
        /// see [io.flowcatalyst.server.Server.Running#stop]), then closes the
        /// MCP server's sessions/transport (which shuts down the transport
        /// provider's virtual-thread executor too — see
        /// [VertxStreamableServerTransportProvider#closeGracefully]), then
        /// this listener's own [Vertx] instance.
        public void stop() {
            try {
                httpServer.shutdown(STOP_TIMEOUT).toCompletionStage().toCompletableFuture()
                        .get(STOP_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                LOG.warn("closing the MCP listener did not complete cleanly", e);
            }
            try {
                mcpServer.closeGracefully();
            } catch (RuntimeException e) {
                LOG.warn("closing the MCP server failed", e);
            }
            try {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                LOG.warn("closing the MCP listener's Vert.x instance did not complete cleanly", e);
            }
        }
    }

    /// Builds the twelve-tool, nine-resource catalogue against `platform`
    /// (`docs/spec/mcp.md` §3/§4) and starts the listener on `host:port`.
    public static Running start(PlatformClient platform, String host, int port, String serverVersion) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(serverVersion, "serverVersion");

        // One event loop, the same sizing io.flowcatalyst.http.vertx.VertxListener
        // and VertxMediationClient use for their own, equally dedicated instances.
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));

        // The actual bound port is not known until httpServer.listen() succeeds
        // below (this overload is also called with port 0 — an ephemeral port —
        // by every test); deriveSecurityValidator reads this lazily on every
        // request rather than baking a port in now, so the validator installed
        // into the provider below is already correct for whatever port the
        // listener ends up bound to. No request can arrive before #listen()
        // completes, so by the time boundPort.set(actualPort) below has NOT
        // yet run, nothing has validated headers against it either.
        var boundPort = new AtomicInteger(port);
        var transportProvider = VertxStreamableServerTransportProvider.builder(vertx)
                // The platform's one configured Jackson 3 mapper
                // (platform/shared/json/Json#MAPPER) — never Vert.x's own
                // Jackson-2-based JsonObject/Json on this wire path.
                .jsonMapper(new JacksonMcpJsonMapper((JsonMapper) Json.MAPPER))
                .securityValidator(deriveSecurityValidator(host, boundPort::get))
                .build();
        McpSyncServer mcpServer = io.modelcontextprotocol.server.McpServer.sync(transportProvider)
                .serverInfo("flowcatalyst", serverVersion)
                .instructions(INSTRUCTIONS)
                .tools(McpTools.all(platform))
                .resources(McpResources.staticResources(platform))
                .resourceTemplates(McpResources.templates(platform))
                .build();

        Router router = Router.router(vertx);
        transportProvider.mount(router);
        router.route(HttpMethod.GET, "/health").handler(rc -> rc.response().setStatusCode(200).end());

        // h2c off: MCP serves plain HTTP/1.1 only (server/pom.xml's dependency
        // comment already says so), and HTTP/2 has no literal Host header (only
        // the :authority pseudo-header, which Vert.x does not synthesize one
        // from) — deriveSecurityValidator's Host check would see every request
        // as missing its Host header and refuse it with 421 otherwise. Found by
        // VertxStreamableServerTransportProviderTest/McpServerTest: the JDK
        // HttpClient's default HTTP_2 version upgrades a cleartext connection
        // to h2c whenever the server accepts it (Vert.x's own default).
        HttpServer httpServer = vertx.createHttpServer(new HttpServerOptions().setHost(host).setPort(port)
                        .setHttp2ClearTextEnabled(false))
                .requestHandler(router);
        try {
            httpServer.listen().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while binding the MCP listener", e);
        } catch (ExecutionException | TimeoutException e) {
            vertx.close();
            throw new IllegalStateException("binding the MCP listener on " + host + ":" + port,
                    e.getCause() != null ? e.getCause() : e);
        }

        int actualPort = httpServer.actualPort();
        boundPort.set(actualPort);
        LOG.atInfo().setMessage("mcp server listening")
                .addKeyValue("addr", host + ":" + actualPort)
                .log();
        return new Running(vertx, httpServer, mcpServer, actualPort);
    }

    /// Derives the transport's Origin/Host allow-list from the listener's own
    /// bound address (`docs/spec/mcp.md` §1) — never a separate knob. The only
    /// inputs are what the listener already knows: `bindHost` (`FC_MCP_BIND`)
    /// and `boundPort` (the listener's actual bound port, read lazily since it
    /// is not known until after `httpServer.listen()` succeeds — every real
    /// request necessarily arrives after that, so the lazy read is never
    /// stale for one). `localhost`, `127.0.0.1` and `[::1]` are always
    /// included alongside `bindHost` (deduplicated when `bindHost` is already
    /// one of those, e.g. the default `127.0.0.1`) — every address a
    /// same-machine client, including a browser-based MCP client sending a
    /// real `Origin` header, could legitimately use to reach this exact
    /// listener, both `http://` and `https://`. Anything else carrying an
    /// `Origin` header is refused (`403`); a request with no `Origin` header
    /// is unaffected either way (`ServerTransportSecurityValidator`'s own
    /// rule, unchanged). The same host set becomes the allowed `Host` values
    /// too, since [DefaultServerTransportSecurityValidator] enforces a `Host`
    /// check once any are configured.
    ///
    /// Package-private: [VertxStreamableServerTransportProviderTest] calls
    /// this directly to exercise the same derivation against the transport
    /// provider without going through the platform-client/tool-catalogue
    /// machinery the rest of [#start] needs.
    static ServerTransportSecurityValidator deriveSecurityValidator(String bindHost, IntSupplier boundPort) {
        Objects.requireNonNull(bindHost, "bindHost");
        Objects.requireNonNull(boundPort, "boundPort");
        return headers -> derivedValidatorFor(bindHost, boundPort.getAsInt()).validateHeaders(headers);
    }

    private static ServerTransportSecurityValidator derivedValidatorFor(String bindHost, int port) {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add("localhost");
        hosts.add("127.0.0.1");
        hosts.add("[::1]");
        hosts.add(bindHost);

        var builder = DefaultServerTransportSecurityValidator.builder();
        for (String host : hosts) {
            builder.allowedOrigin("http://" + host + ":" + port);
            builder.allowedOrigin("https://" + host + ":" + port);
            builder.allowedHost(host + ":" + port);
        }
        return builder.build();
    }
}
