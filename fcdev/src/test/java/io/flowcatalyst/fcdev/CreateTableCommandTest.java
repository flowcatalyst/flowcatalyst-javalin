package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `fcdev outbox create-table` (`docs/spec/fcdev-commands.md` §3.1):
/// postgres against a real embedded Postgres (observable effect — the table
/// exists in `information_schema`, and a second run is a no-op), the mysql
/// URL conversion as a pure function (never a live MySQL server), and the
/// two special-cased outcomes: `mongodb` (exit 2, the exact message) and an
/// unknown `--db-type` (exit 1, the exact message).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreateTableCommandTest {

    private Path root;
    private EmbeddedPg pg;

    @BeforeAll
    void bootEmbeddedPg() throws Exception {
        io.flowcatalyst.server.Logging.init(Map.of("FC_LOG_LEVEL", "warn", "FC_LOG_FORMAT", "text"));
        root = Files.createTempDirectory("fcdev-createtable-it");
        try {
            pg = EmbeddedPg.start(root.resolve("data"), 0, root.resolve("cache"));
        } catch (Exception | ExceptionInInitializerError e) {
            LoggerFactory.getLogger(CreateTableCommandTest.class).warn("embedded PostgreSQL could not start here; skipping", e);
            Assumptions.abort("embedded PostgreSQL cannot start in this environment: " + e);
        }
    }

    @AfterAll
    void stopEmbeddedPg() throws Exception {
        if (pg != null) pg.close();
        if (root != null) EmbeddedPg.deleteTree(root);
    }

    private OutboxCommand.CreateTable build(PrintWriter out, java.io.PrintWriter err, String... args) throws Exception {
        var cmd = new OutboxCommand.CreateTable(DevEnv.of(Map.of()));
        var cl = new CommandLine(cmd);
        cl.setOut(out);
        cl.setErr(err);
        cl.parseArgs(args);
        return cmd;
    }

    // ── postgres: real, observable effect ────────────────────────────────

    @Test
    void postgresCreatesTheTableAndARerunIsANoOp() throws Exception {
        var out = new PrintWriter(new ByteArrayOutputStream());
        var cmd = build(out, out, "--db-type", "postgres", "--db-url", pg.url());

        Integer exitCode = cmd.call();
        assertThat(exitCode).isZero();
        assertThat(tableExists()).as("outbox_messages exists after create-table").isTrue();

        // Re-run: CREATE TABLE IF NOT EXISTS — no error, table still there.
        var cmd2 = build(out, out, "--db-type", "postgres", "--db-url", pg.url());
        Integer secondExitCode = cmd2.call();
        assertThat(secondExitCode).isZero();
        assertThat(tableExists()).isTrue();
    }

    @Test
    void pgAliasIsAcceptedForPostgres() throws Exception {
        var out = new PrintWriter(new ByteArrayOutputStream());
        var cmd = build(out, out, "--db-type", "pg", "--db-url", pg.url());

        Integer exitCode = cmd.call();

        assertThat(exitCode).isZero();
        assertThat(tableExists()).isTrue();
    }

    private boolean tableExists() throws Exception {
        var jdbc = io.flowcatalyst.platform.shared.database.Database.toJdbc(pg.url());
        try (Connection c = DriverManager.getConnection(jdbc.url(), jdbc.user(), jdbc.password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM information_schema.tables WHERE table_name = 'outbox_messages'")) {
            rs.next();
            return rs.getInt(1) == 1;
        }
    }

    // ── mysql: pure-function URL conversion, never a live server ──────────

    @Test
    void mysqlUrlConversionDefaultsThePortTo3306() {
        String jdbc = MysqlJdbcUrl.toJdbcUrl("mysql://user:pass@localhost/app");

        assertThat(jdbc).startsWith("jdbc:mysql://localhost:3306/app");
    }

    @Test
    void mysqlUrlConversionKeepsAnExplicitPort() {
        String jdbc = MysqlJdbcUrl.toJdbcUrl("mysql://user:pass@db-host:3307/app");

        assertThat(jdbc).startsWith("jdbc:mysql://db-host:3307/app");
    }

    @Test
    void aJdbcUrlPassesThroughVerbatim() {
        String raw = "jdbc:mysql://host:3306/app?useSSL=false";

        assertThat(MysqlJdbcUrl.toJdbcUrl(raw)).isEqualTo(raw);
    }

    // ── mongodb: exit 2, exact message ───────────────────────────────────

    @Test
    void mongodbExitsTwoWithTheExactNotSupportedMessage() throws Exception {
        var out = new PrintWriter(new ByteArrayOutputStream());
        var errBuf = new java.io.StringWriter();
        var err = new PrintWriter(errBuf);
        var cmd = build(out, err, "--db-type", "mongodb", "--db-url", "mongodb://localhost:27017");

        Integer exitCode = cmd.call();

        assertThat(exitCode).isEqualTo(2);
        assertThat(errBuf.toString()).contains(
                "fcdev outbox create-table: mongodb is not supported in the Java fcdev (Mongo outbox backend is on the backlog)");
    }

    // ── unknown --db-type: exit 1 via exception, exact message ───────────

    @Test
    void anUnknownDbTypeFailsWithTheExactMessage() throws Exception {
        var out = new PrintWriter(new ByteArrayOutputStream());
        var cmd = build(out, out, "--db-type", "oracle", "--db-url", "whatever://x");

        assertThatThrownBy(cmd::call).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown --db-type \"oracle\": want postgres, mysql, or mongodb");
    }

    // ── required db-url ───────────────────────────────────────────────────

    @Test
    void missingDbUrlFailsWithTheExactMessage() throws Exception {
        var out = new PrintWriter(new ByteArrayOutputStream());
        var cmd = build(out, out, "--db-type", "postgres");

        assertThatThrownBy(cmd::call).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--db-url (or FC_OUTBOX_SOURCE_DB_URL / FC_OUTBOX_DB_URL / FC_OUTBOX_MONGO_URI) is required");
    }
}
