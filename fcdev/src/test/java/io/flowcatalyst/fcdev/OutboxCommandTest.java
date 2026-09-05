package io.flowcatalyst.fcdev;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.shared.database.Database;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `fcdev outbox` (`docs/spec/fcdev-commands.md` §3, `docs/fcdev.md` §4):
/// flag/env/dotenv precedence as a pure function, the required-source-url
/// error, and a real delivery run against an embedded Postgres "consumer
/// app" database and a stub platform (never the real network). Skips when
/// the bundled PostgreSQL cannot start here (see [StartIntegrationTest]).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxCommandTest {

    private Path root;
    private EmbeddedPg pg;
    private String sourceDbUrl;

    private HttpServer platformStub;
    private String platformBaseUrl;
    private final AtomicInteger batchHits = new AtomicInteger();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile String tokenResponseBody = """
            {"access_token":"stub-token","expires_in":3600}""";

    @BeforeAll
    void bootEmbeddedPg() throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        root = Files.createTempDirectory("fcdev-outbox-it");
        try {
            pg = EmbeddedPg.start(root.resolve("data"), 0, root.resolve("cache"));
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(OutboxCommandTest.class).warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
        }
        sourceDbUrl = pg.url();
    }

    @AfterAll
    void stopEmbeddedPg() throws Exception {
        if (pg != null) pg.close();
        if (root != null) EmbeddedPg.deleteTree(root);
    }

    @BeforeEach
    void startPlatformStub() throws IOException {
        batchHits.set(0);
        authHeaders.clear();
        platformStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platformStub.createContext("/api/events/batch", exchange -> {
            batchHits.incrementAndGet();
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"results":[{"id":"x","status":"SUCCESS"}]}""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        platformStub.createContext("/oauth/token", exchange -> {
            byte[] body = tokenResponseBody.getBytes(StandardCharsets.UTF_8);
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

    // ── flag > env > dotenv > default precedence (pure function) ──────────

    @Test
    void aFlagWinsOverEnvAndEnvWinsOverTheDotenvFile() throws Exception {
        var env = DevEnv.of(Map.of("FC_OUTBOX_SOURCE_DB_URL", "postgres://from-env/db"));
        var cmd = new OutboxCommand(env);
        new CommandLine(cmd).parseArgs("--source-db-url", "postgres://from-flag/db");

        String resolved = cmd.resolveStr("--source-db-url", cmd.sourceDbUrl, env, "FC_OUTBOX_SOURCE_DB_URL");

        assertThat(resolved).isEqualTo("postgres://from-flag/db");
    }

    /// Pins the mutant "dotenv overriding an already-set env var": the
    /// dotenv file's value must NEVER beat a real environment variable —
    /// [DotEnv#loadOver] layers the file's pairs UNDER `env`'s own.
    @Test
    void theRealEnvironmentVariableWinsOverTheDotenvFileEvenThoughTheFileIsLoadedFirst(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Path dotenv = tmp.resolve(".env");
        Files.writeString(dotenv, "FC_OUTBOX_SOURCE_DB_URL=postgres://from-dotenv/db\n");

        var realEnv = DevEnv.of(Map.of("FC_OUTBOX_SOURCE_DB_URL", "postgres://from-real-env/db"));
        var merged = DotEnv.loadOver(realEnv, dotenv.toString());

        assertThat(merged.get("FC_OUTBOX_SOURCE_DB_URL")).isEqualTo("postgres://from-real-env/db");
    }

    @Test
    void anUnsetEnvironmentVariableIsFilledFromTheDotenvFile(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path dotenv = tmp.resolve(".env");
        Files.writeString(dotenv, "FC_OUTBOX_SOURCE_DB_URL=postgres://from-dotenv/db\n");

        var realEnv = DevEnv.of(Map.of());
        var merged = DotEnv.loadOver(realEnv, dotenv.toString());

        assertThat(merged.get("FC_OUTBOX_SOURCE_DB_URL")).isEqualTo("postgres://from-dotenv/db");
    }

    @Test
    void clientIdFallsBackFromTheOutboxSpecificEnvNameToTheSharedFlowcatalystOne() throws Exception {
        var env = DevEnv.of(Map.of("FLOWCATALYST_CLIENT_ID", "shared-cid"));
        var cmd = new OutboxCommand(env);
        new CommandLine(cmd).parseArgs();

        String resolved = cmd.resolveStr("--client-id", cmd.clientId, env, "FC_OUTBOX_CLIENT_ID", "FLOWCATALYST_CLIENT_ID");

        assertThat(resolved).isEqualTo("shared-cid");
    }

    // ── required source url ────────────────────────────────────────────────

    @Test
    void missingSourceDbUrlFailsWithTheExactMessage() throws Exception {
        var cmd = new OutboxCommand(DevEnv.of(Map.of()));
        new CommandLine(cmd).parseArgs();

        assertThatThrownBy(cmd::launch).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--source-db-url (or FC_OUTBOX_SOURCE_DB_URL) is required");
    }

    // ── real delivery: the row is gone from the live table ────────────────

    @Test
    void aPendingRowIsDeliveredToTheStubPlatformAndDeletedWithNoAuth() throws Exception {
        var env = DevEnv.of(Map.of());
        var cmd = new OutboxCommand(env);
        new CommandLine(cmd).parseArgs("--source-db-url", sourceDbUrl, "--target-url", platformBaseUrl,
                "--poll-interval-ms", "50");

        OutboxCommand.Started started = cmd.launch();
        try {
            String id = insertPendingEventRow();

            waitUntilRowGone(id);
            assertThat(batchHits.get()).as("the stub platform's batch route was actually hit").isGreaterThanOrEqualTo(1);
            assertThat(authHeaders).as("no credentials configured — no Authorization header sent")
                    .allSatisfy(h -> assertThat(h).isNull());
        } finally {
            started.close();
        }
    }

    @Test
    void clientCredentialsMintsATokenAtTheStubOauthEndpointAndForwardsTheBearer() throws Exception {
        var env = DevEnv.of(Map.of());
        var cmd = new OutboxCommand(env);
        // A static token is configured too: client credentials must win
        // (spec §3 — the token source is preferred, the static token is the
        // fallback), so the static value must never reach the platform.
        new CommandLine(cmd).parseArgs("--source-db-url", sourceDbUrl, "--target-url", platformBaseUrl,
                "--client-id", "cid", "--client-secret", "csecret", "--auth-token", "static-loser",
                "--poll-interval-ms", "50");

        OutboxCommand.Started started = cmd.launch();
        try {
            String id = insertPendingEventRow();

            waitUntilRowGone(id);
            assertThat(authHeaders).as("the minted bearer token was forwarded on the batch request")
                    .anySatisfy(h -> assertThat(h).isEqualTo("Bearer stub-token"));
            assertThat(authHeaders).as("the static token is the fallback, never used alongside client credentials")
                    .noneSatisfy(h -> assertThat(h).isEqualTo("Bearer static-loser"));
        } finally {
            started.close();
        }
    }

    @Test
    void theStartedLogLineNeverCarriesTheSourcePassword() {
        assertThat(OutboxCommand.maskCredentials("postgresql://app:s3cr3t%40pw@db.internal:5432/app?sslmode=require"))
                .isEqualTo("postgresql://app:***@db.internal:5432/app?sslmode=require");
        assertThat(OutboxCommand.maskCredentials("postgresql://app@db.internal/app"))
                .as("no password → unchanged").isEqualTo("postgresql://app@db.internal/app");
        assertThat(OutboxCommand.maskCredentials("jdbc:postgresql://db.internal/app")).isEqualTo("jdbc:postgresql://db.internal/app");
    }

    @Test
    void aStaticAuthTokenIsForwardedVerbatimWithNoTokenMint() throws Exception {
        var env = DevEnv.of(Map.of());
        var cmd = new OutboxCommand(env);
        new CommandLine(cmd).parseArgs("--source-db-url", sourceDbUrl, "--target-url", platformBaseUrl,
                "--auth-token", "static-secret-token", "--poll-interval-ms", "50");

        OutboxCommand.Started started = cmd.launch();
        try {
            String id = insertPendingEventRow();

            waitUntilRowGone(id);
            assertThat(authHeaders).anySatisfy(h -> assertThat(h).isEqualTo("Bearer static-secret-token"));
        } finally {
            started.close();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String insertPendingEventRow() throws Exception {
        String id = "evt_test_" + System.nanoTime();
        var jdbc = Database.toJdbc(sourceDbUrl);
        try (Connection c = DriverManager.getConnection(jdbc.url(), jdbc.user(), jdbc.password());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO outbox_messages (id, type, payload) VALUES ('" + id + "', 'EVENT', '{}')");
        }
        return id;
    }

    private void waitUntilRowGone(String id) throws Exception {
        var jdbc = Database.toJdbc(sourceDbUrl);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection c = DriverManager.getConnection(jdbc.url(), jdbc.user(), jdbc.password());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM outbox_messages WHERE id = '" + id + "'")) {
                rs.next();
                if (rs.getInt(1) == 0) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("row " + id + " was never delivered (still present in outbox_messages)");
    }
}
