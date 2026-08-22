package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Boots `fcdev start` programmatically: embedded PostgreSQL in a temp data
/// dir on a free port, API + metrics on ephemeral ports, the embedded SPA,
/// then `/health` on both listeners and `/` for the SPA shell. Skipped when
/// the bundled PostgreSQL cannot start here (no binaries for this platform,
/// noexec tmp, …).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Path root;
    private Path dataPath;
    private Path pidFile;
    private StartCommand.Started started;

    @BeforeAll
    void boot() throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        root = Files.createTempDirectory("fcdev-it");
        dataPath = root.resolve("flowcatalyst/embedded-pg");
        pidFile = root.resolve("flowcatalyst/fcdev.pid");
        var cache = root.resolve("cache");
        var env = DevEnv.of(Map.of(
                "FC_EMBEDDED_DB_PATH", dataPath.toString(),
                "FC_DEV_PID_FILE", pidFile.toString(),
                "XDG_CACHE_HOME", cache.toString()));
        var sub = new StartCommand.Sub(env);
        new CommandLine(sub, new EnvFactory(env)).parseArgs("--api-port", "0", "--metrics-port", "0", "--embedded-db-port", "0",
                "--router=false", "--stream=false", "--scheduler=false", "--scheduled-job=false");
        var paths = new DevPaths(root, cache);
        try {
            started = new StartCommand(env, paths, sub.opts).launch();
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(StartIntegrationTest.class).warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
        }
    }

    @AfterAll
    void shutdown() throws Exception {
        if (started != null) started.close();
        if (root != null) EmbeddedPg.deleteTree(root);
    }

    @Test
    void healthOnBothListeners() throws Exception {
        var api = get("http://localhost:" + started.apiPort() + "/health");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).contains("\"status\":\"UP\"");
        var metrics = get("http://localhost:" + started.metricsPort() + "/health");
        assertThat(metrics.statusCode()).isEqualTo(200);
        var ready = get("http://localhost:" + started.metricsPort() + "/ready");
        assertThat(ready.body()).contains("\"platform\":true").contains("\"router\":false");
    }

    @Test
    void servesTheEmbeddedSpa() throws Exception {
        var r = get("http://localhost:" + started.apiPort() + "/");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(r.body()).containsIgnoringCase("<html");
        var deep = get("http://localhost:" + started.apiPort() + "/some/spa/route");
        assertThat(deep.statusCode()).isEqualTo(200);
        assertThat(deep.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
    }

    @Test
    void embeddedClusterIsMigratedAndPersistent() throws Exception {
        var pg = started.embeddedPg().orElseThrow();
        assertThat(EmbeddedPg.clusterDir(dataPath).resolve("PG_VERSION")).exists();
        assertThat(EmbeddedPg.dataMajor(dataPath)).hasValue(EmbeddedPg.pinnedMajor());
        assertThat(pg.url()).startsWith("postgresql://postgres:postgres@localhost:" + pg.port() + "/flowcatalyst");
        var jdbc = io.flowcatalyst.platform.shared.database.Database.toJdbc(pg.url());
        try (Connection c = java.sql.DriverManager.getConnection(jdbc.url(), jdbc.user(), jdbc.password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isGreaterThan(0);
        }
        // the seeder ran with the dev defaults: the bootstrap admin exists
        try (Connection c = java.sql.DriverManager.getConnection(jdbc.url(), jdbc.user(), jdbc.password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM iam_principals WHERE email = '" + DevBootstrap.DEV_ADMIN_EMAIL + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void stateFilesAndPidFile() throws Exception {
        assertThat(PidFile.read(pidFile)).hasValue(ProcessHandle.current().pid());
        assertThat(root.resolve("flowcatalyst/jwt-signing-key.pem")).exists();
        assertThat(root.resolve("flowcatalyst/app-key")).exists();
        // the PG binaries were extracted into the cache dir, not next to the data
        try (var s = Files.list(root.resolve("cache/flowcatalyst/embedded-pg"))) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("PG-")).findAny()).isPresent();
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
