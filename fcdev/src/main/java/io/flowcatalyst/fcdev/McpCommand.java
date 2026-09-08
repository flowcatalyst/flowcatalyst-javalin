package io.flowcatalyst.fcdev;

import io.flowcatalyst.mcp.McpConfig;
import io.flowcatalyst.mcp.McpResources;
import io.flowcatalyst.mcp.McpTools;
import io.flowcatalyst.mcp.PlatformClient;
import io.flowcatalyst.mcp.TokenManager;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/// `fcdev mcp` (Go `mcp.go`): the FlowCatalyst MCP server, run standalone
/// (as opposed to `fcdev start --mcp`, which runs it as one of the shared
/// [io.flowcatalyst.server.Server] subsystems — `docs/spec/mcp.md` §1).
/// Defaults to the stdio transport (the usual way an MCP client launches a
/// server as a subprocess: JSON-RPC on stdin/stdout, every log line on
/// stderr — stdout is reserved for the protocol) — unaffected by the Vert.x
/// cutover, since stdio needs no HTTP listener at all. `--http <bind>`
/// would switch to the streamable-HTTP transport, but that transport has no
/// Vert.x implementation ([io.flowcatalyst.mcp.McpServer]'s class doc,
/// `docs/vertx-plan.md` Q4) — `--http` fails fast with the same message
/// [io.flowcatalyst.server.Server#start] does for `FC_MCP_ENABLED=true`.
///
/// Config resolution mirrors [io.flowcatalyst.mcp.McpConfig#resolve]
/// exactly (env / credentials-file, `FLOWCATALYST_URL` → `FC_MCP_PLATFORM_URL`
/// → `http://localhost:<FC_API_PORT>`), with this command's own flags
/// overriding a field whenever given — the same precedence Go's `runMCP`
/// applies by assigning over `cfg` unconditionally when a flag is set.
@Command(name = "mcp", description = "Run the FlowCatalyst MCP server (stdio by default; --http to listen)",
        mixinStandardHelpOptions = true, sortOptions = false)
public final class McpCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(McpCommand.class);

    @Option(names = "--http", paramLabel = "<addr>",
            description = "listen for streamable-HTTP MCP at this bind address (e.g. 127.0.0.1:8090); empty = stdio")
    String http;

    @Option(names = "--platform-url", paramLabel = "<url>", description = "override FLOWCATALYST_URL (platform base URL)")
    String platformUrl;

    @Option(names = "--client-id", paramLabel = "<id>", description = "override FLOWCATALYST_CLIENT_ID")
    String clientId;

    @Option(names = "--client-secret", paramLabel = "<secret>", description = "override FLOWCATALYST_CLIENT_SECRET")
    String clientSecret;

    private final DevEnv env;

    /// The stdio transport's streams — real stdin/stdout in production;
    /// [McpCommandTest] replaces both with in-memory pipes so it can drive
    /// the protocol and capture output without touching the process's real
    /// streams, and separately redirects the REAL [System#out] to prove
    /// nothing but what the transport itself writes ever reaches it.
    InputStream stdioIn = System.in;
    OutputStream stdioOut = System.out;

    public McpCommand() {
        this(DevEnv.system());
    }

    public McpCommand(DevEnv env) {
        this.env = env;
    }

    @Override
    public Integer call() throws Exception {
        var config = resolveConfig();
        var platform = buildPlatformClient(config);
        if (http == null || http.isBlank()) {
            return blockUntilShutdown(startStdio(platform, config.baseUrl())::close);
        }
        Bind.parse(http); // validated even though unusable, so a malformed --http still fails on its own message first
        throw new IllegalStateException(io.flowcatalyst.mcp.McpServer.UNAVAILABLE);
    }

    // ── config / auth ────────────────────────────────────────────────────

    PlatformClient buildPlatformClient(McpConfig config) {
        try {
            config.requireCredentials();
        } catch (McpConfig.NoCredentialsException e) {
            // Warn (to stderr — stdout is reserved for JSON-RPC in stdio mode)
            // but proceed: a localhost platform may not require auth, and the
            // platform will reject the call if it does (Go `runMCP`).
            LOG.warn("starting MCP server without credentials: {}", e.getMessage());
        }
        var tokenManager = config.hasCredentials()
                ? new TokenManager(config.baseUrl(), config.clientId(), config.clientSecret())
                : null;
        // FC_MCP_PLATFORM_AUTH_TOKEN, falling back to FLOWCATALYST_AUTH_TOKEN
        // (docs/spec/fcdev-commands.md §2) — the interim static bearer while
        // the Java platform has no /oauth/token of its own.
        var staticToken = firstNonBlank(env.get("FC_MCP_PLATFORM_AUTH_TOKEN"), env.get("FLOWCATALYST_AUTH_TOKEN"));
        var auth = PlatformClient.AuthMode.resolve(config, tokenManager, staticToken.isBlank() ? null : staticToken);
        return new PlatformClient(config.baseUrl(), auth);
    }

    /// Same precedence as [McpConfig#resolve]: this command's flag wins when
    /// given, else the environment, else the credentials file, else
    /// `http://localhost:<FC_API_PORT>` for the base URL alone.
    McpConfig resolveConfig() {
        var baseUrl = firstNonBlank(platformUrl, firstNonBlank(env.get("FLOWCATALYST_URL"), env.get("FC_MCP_PLATFORM_URL")));
        var cid = firstNonBlank(clientId, env.get("FLOWCATALYST_CLIENT_ID"));
        var secret = firstNonBlank(clientSecret, env.get("FLOWCATALYST_CLIENT_SECRET"));
        int apiPort = env.integer("FC_API_PORT", 8080);
        return McpConfig.resolve(baseUrl, cid, secret, apiPort);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    // ── stdio transport ──────────────────────────────────────────────────

    /// A running stdio MCP server — [#close()] closes the MCP session and
    /// transport (Go's `srv.RunStdio(ctx)` returning).
    record StdioRunning(McpSyncServer server) implements AutoCloseable {
        @Override
        public void close() {
            server.closeGracefully();
        }
    }

    /// Builds and starts serving over [#stdioIn]/[#stdioOut] — separated from
    /// [#call()]'s indefinite block so [McpCommandTest] can drive the
    /// protocol directly. Every log line here goes through [#LOG] (stderr);
    /// nothing in this method may write to [System#out] — stdout is the
    /// JSON-RPC channel the moment the transport starts reading.
    StdioRunning startStdio(PlatformClient platform, String platformBaseUrl) {
        LOG.info("fcdev mcp serving over stdio platform_url={}", platformBaseUrl);
        var mapper = McpJsonDefaults.getMapper();
        var transport = new StdioServerTransportProvider(mapper, stdioIn, stdioOut);
        var server = io.modelcontextprotocol.server.McpServer.sync(transport)
                .serverInfo("flowcatalyst", Version.current())
                .tools(McpTools.all(platform))
                .resources(McpResources.staticResources(platform))
                .resourceTemplates(McpResources.templates(platform))
                .build();
        return new StdioRunning(server);
    }

    // ── shared shutdown ──────────────────────────────────────────────────

    /// Blocks the calling (main) thread until a shutdown signal (SIGINT /
    /// SIGTERM), then runs `onShutdown` — the same shape as
    /// [StartCommand#call()]: a shutdown hook does the stopping, the main
    /// thread just waits for it.
    private int blockUntilShutdown(Runnable onShutdown) throws InterruptedException {
        var latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("fcdev-mcp-shutdown").unstarted(() -> {
            LOG.info("shutdown signal received");
            onShutdown.run();
            latch.countDown();
        }));
        latch.await();
        return 0;
    }

    /// `host:port` for `--http` (Go passes the whole string straight to
    /// `http.Server{Addr: bind}`; [io.flowcatalyst.mcp.McpServer#start] wants
    /// them split). The host is everything before the LAST colon so an IPv6
    /// literal without brackets still fails loudly rather than silently
    /// mis-splitting — the documented form is `host:port` / `ip:port`.
    record Bind(String host, int port) {
        static Bind parse(String addr) {
            int i = addr.lastIndexOf(':');
            if (i < 0 || i == addr.length() - 1) {
                throw new IllegalArgumentException("--http must be host:port, got \"" + addr + "\"");
            }
            String host = addr.substring(0, i);
            int port;
            try {
                port = Integer.parseInt(addr.substring(i + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--http must be host:port, got \"" + addr + "\"", e);
            }
            return new Bind(host.isEmpty() ? "0.0.0.0" : host, port);
        }
    }
}
