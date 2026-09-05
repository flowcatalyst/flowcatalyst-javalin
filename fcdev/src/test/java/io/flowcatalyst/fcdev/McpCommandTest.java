package io.flowcatalyst.fcdev;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.mcp.McpConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// `fcdev mcp` (`docs/fcdev.md` §4, Go `mcp.go`): config resolution
/// precedence, and the stdio transport's one load-bearing housekeeping rule
/// — every log line goes to stderr because stdout IS the JSON-RPC channel
/// the moment the transport starts reading. Never hits the real network:
/// the "platform" is a local [HttpServer] stub.
class McpCommandTest {

    private HttpServer platformStub;
    private String platformBaseUrl;

    @BeforeAll
    static void initLogging() {
        // Logback's ConsoleAppender is bound to the real System.err at this
        // point, once — so redirecting System.out later in a test cannot
        // accidentally swallow or fake out logging, and logging cannot
        // accidentally land on the redirected System.out either.
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "info", "FC_LOG_FORMAT", "text"));
    }

    @BeforeEach
    void startPlatformStub() throws Exception {
        platformStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platformStub.createContext("/api/me", exchange -> {
            byte[] body = "{\"principalId\":\"prn_test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        platformStub.start();
        platformBaseUrl = "http://127.0.0.1:" + platformStub.getAddress().getPort();
    }

    @AfterEach
    void stopPlatformStub() {
        platformStub.stop(0);
    }

    // ── config resolution ────────────────────────────────────────────────

    @Test
    void aFlagOverridesTheEnvironmentAndTheCredentialsFileFallsBackForWhatIsStillBlank() {
        var env = DevEnv.of(Map.of("FLOWCATALYST_URL", "http://from-env:9999"));
        var cmd = new McpCommand(env);
        cmd.platformUrl = "http://from-flag:1234";

        var config = cmd.resolveConfig();

        assertThat(config.baseUrl()).isEqualTo("http://from-flag:1234");
    }

    /// The `FC_MCP_PLATFORM_URL` alias — used, in [McpCommand]'s own
    /// resolution, exactly like `FLOWCATALYST_URL` when the latter is unset.
    /// (Deliberately does not exercise the credentials-file / local-port
    /// fallback baked into [McpConfig#resolve] itself — that always reads
    /// the REAL OS cache dir, which is not injectable from this module and
    /// may already hold a real `mcp-credentials.json` on a developer
    /// machine; [io.flowcatalyst.mcp.McpConfigTest] pins that fallback
    /// hermetically via the package-private path override.)
    @Test
    void fcMcpPlatformUrlAliasUsedWhenFlowcatalystUrlIsAbsent() {
        var env = DevEnv.of(Map.of("FC_MCP_PLATFORM_URL", "http://from-alias:4321"));
        var cmd = new McpCommand(env);

        var config = cmd.resolveConfig();

        assertThat(config.baseUrl()).isEqualTo("http://from-alias:4321");
    }

    @Test
    void missingCredentialsIsWarnedNotThrown() {
        var cmd = new McpCommand(DevEnv.of(Map.of()));
        // Built directly (not through resolveConfig/McpConfig#resolve) so
        // this stays hermetic regardless of any real credentials file.
        var config = new McpConfig(platformBaseUrl, "", "");

        // buildPlatformClient must not throw even though neither client id
        // nor secret is configured — the server "still starts and every
        // tool answers the error" (docs/spec/mcp.md §1).
        var platform = cmd.buildPlatformClient(config);
        assertThat(platform).isNotNull();
    }

    // ── stdio: nothing but JSON-RPC on stdout ────────────────────────────

    /// Pins the "nothing but JSON-RPC on stdout" rule (docs/fcdev.md §4):
    /// [McpCommand#startStdio] logs "fcdev mcp serving over stdio …", and
    /// that line must land on stderr, not on the transport's stdout. Break
    /// this by changing the LOG.info call to System.out.println and this
    /// test fails: the real (redirected) System.out stops being empty.
    @Test
    void stdioModeWritesOnlyJsonRpcToStdoutAndLogsToStderr() throws Exception {
        var env = DevEnv.of(Map.of("FLOWCATALYST_URL", platformBaseUrl,
                "FLOWCATALYST_CLIENT_ID", "cid", "FLOWCATALYST_CLIENT_SECRET", "csecret"));
        var cmd = new McpCommand(env);

        var clientToServer = new PipedOutputStream();
        var serverIn = new PipedInputStream(clientToServer, 1 << 16);
        var serverOutCapture = new ByteArrayOutputStream();
        cmd.stdioIn = serverIn;
        cmd.stdioOut = serverOutCapture;

        var config = cmd.resolveConfig();
        var platform = cmd.buildPlatformClient(config);

        var realOut = System.out;
        var sysOutCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(sysOutCapture, true, StandardCharsets.UTF_8));
        McpCommand.StdioRunning running = null;
        try {
            running = cmd.startStdio(platform, config.baseUrl());

            String initializeRequest = """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05",\
                    "capabilities":{},"clientInfo":{"name":"mcp-command-test","version":"1.0.0"}}}
                    """;
            clientToServer.write(initializeRequest.getBytes(StandardCharsets.UTF_8));
            clientToServer.flush();

            // The transport processes on its own (reactor) threads; poll
            // for the response line instead of a fixed sleep.
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (serverOutCapture.size() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        } finally {
            System.setOut(realOut);
            if (running != null) running.close();
            clientToServer.close();
        }

        String protocolOutput = serverOutCapture.toString(StandardCharsets.UTF_8);
        assertThat(protocolOutput).as("the JSON-RPC response reached the INJECTED stdio stream")
                .contains("\"jsonrpc\"");

        assertThat(sysOutCapture.toByteArray())
                .as("nothing — the server's own log line went to stderr, not the real System.out")
                .isEmpty();
    }
}
