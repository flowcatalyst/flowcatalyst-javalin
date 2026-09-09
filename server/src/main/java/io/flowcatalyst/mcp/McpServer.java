package io.flowcatalyst.mcp;

import io.javalin.Javalin;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/// The MCP server subsystem: streamable HTTP at `/mcp` (POST + GET-as-SSE +
/// DELETE) and `GET /health` → 200, on its own `FC_MCP_BIND:FC_MCP_PORT`
/// listener — a second, dedicated [Javalin] instance, the same shape as
/// [io.flowcatalyst.outbox.OutboxAdminApi] (`docs/spec/mcp.md` §1). One
/// [McpSyncServer] — and therefore one [PlatformClient] — is shared across
/// every request; the tool/resource catalogue is stateless.
///
/// The Java MCP SDK ships its streamable-HTTP server transport as a plain
/// [jakarta.servlet.http.HttpServlet]
/// ([HttpServletStreamableServerTransportProvider], living in `mcp-core` as
/// of SDK 2.0.1 — the once-separate `server-servlet` artifact stopped
/// publishing after 0.18.4, folded into `mcp-core` for the 2.x rewrite),
/// which mounts cleanly on the Jetty Javalin already embeds via
/// [io.javalin.config.JettyConfig#modifyServletContextHandler] — no second
/// HTTP stack, no reactive adapter.
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
        private final Javalin app;
        private final McpSyncServer mcpServer;

        private Running(Javalin app, McpSyncServer mcpServer) {
            this.app = app;
            this.mcpServer = mcpServer;
        }

        public int port() {
            return app.port();
        }

        /// Test-only visibility hook: the underlying [Javalin] app, so
        /// [McpServerTest] can inspect the bound Jetty connector directly.
        Javalin app() {
            return app;
        }

        /// Stops accepting new connections (Jetty drains in-flight requests
        /// within [#STOP_TIMEOUT], the same as every other listener — see
        /// [io.flowcatalyst.server.Server.Running#stop]), then closes the MCP
        /// server's sessions and the transport's keep-alive scheduler.
        public void stop() {
            app.stop();
            try {
                mcpServer.closeGracefully();
            } catch (RuntimeException e) {
                LOG.warn("closing the MCP server failed", e);
            }
        }
    }

    /// Builds the twelve-tool, nine-resource catalogue against `platform`
    /// (`docs/spec/mcp.md` §3/§4) and starts the listener on `host:port`.
    public static Running start(PlatformClient platform, String host, int port, String serverVersion) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(serverVersion, "serverVersion");

        var transportProvider = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer mcpServer = io.modelcontextprotocol.server.McpServer.sync(transportProvider)
                .serverInfo("flowcatalyst", serverVersion)
                .instructions(INSTRUCTIONS)
                .tools(McpTools.all(platform))
                .resources(McpResources.staticResources(platform))
                .resourceTemplates(McpResources.templates(platform))
                .build();

        var app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.jetty.modifyServletContextHandler(handler -> {
                var holder = handler.addServlet(transportProvider, "/mcp");
                // The transport provider declares @WebServlet(asyncSupported = true)
                // (it holds the streamable-HTTP connection open via AsyncContext),
                // but that annotation is only honoured by web.xml/annotation-driven
                // deployment — registering an existing instance programmatically
                // needs this set explicitly or every request 500s.
                holder.setAsyncSupported(true);
            });
            cfg.routes.get("/health", ctx -> ctx.status(200));
        });
        app.start(host, port);
        LOG.atInfo().setMessage("mcp server listening")
                .addKeyValue("addr", host + ":" + port)
                .log();
        return new Running(app, mcpServer);
    }
}
