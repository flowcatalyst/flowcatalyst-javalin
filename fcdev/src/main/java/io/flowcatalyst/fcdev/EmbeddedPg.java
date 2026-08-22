package io.flowcatalyst.fcdev;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/// The embedded developer PostgreSQL (Go `embedded.go`), on top of zonky's
/// `embedded-postgres`. Shared by `start`, `fresh` and `db upgrade`.
///
///   - the cluster lives in `<dataPath>/data` and is kept between runs
///     (`initdb` only when there is no `postgresql.conf` yet);
///   - the server binaries bundled in the jar (one `.txz` per platform) are
///     extracted once into `<cacheDir>/PG-<md5>` — a re-creatable cache, so it
///     survives a data wipe and is never mixed with the data;
///   - superuser `postgres` / `postgres` (trust auth on localhost, as zonky's
///     `initdb -A trust`), database `flowcatalyst`, port 15432 by default;
///   - the major is pinned by the `zonky-binaries.version` the jar was built
///     with ([Version#embeddedPgVersion()]); a cluster of another major is
///     refused with an actionable message (`fcdev db upgrade`), because a
///     PostgreSQL major upgrade is not in-place.
public final class EmbeddedPg implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedPg.class);

    public static final String USER = "postgres";
    public static final String PASSWORD = "postgres";
    public static final String DATABASE = "flowcatalyst";
    public static final int DEFAULT_PORT = 15432;
    public static final Duration START_TIMEOUT = Duration.ofSeconds(60);

    private final EmbeddedPostgres pg;
    private final Path dataPath;

    private EmbeddedPg(EmbeddedPostgres pg, Path dataPath) {
        this.pg = pg;
        this.dataPath = dataPath;
    }

    // ── version pinning ────────────────────────────────────────────────────

    /// `pinnedPGMajor`: the major of the bundled binaries, e.g. `18`.
    public static String pinnedMajor() {
        return majorOf(Version.embeddedPgVersion());
    }

    static String majorOf(String v) {
        int i = v.indexOf('.');
        return i >= 0 ? v.substring(0, i) : v;
    }

    /// `<dataPath>/data` — the cluster directory.
    public static Path clusterDir(Path dataPath) {
        return dataPath.resolve("data");
    }

    /// `embeddedDataMajor`: the major of the cluster already on disk, read
    /// from `<dataPath>/data/PG_VERSION` (which holds just the major for PG
    /// 10+). Empty when no cluster has been initialised yet.
    public static Optional<String> dataMajor(Path dataPath) throws IOException {
        try {
            return Optional.of(Files.readString(clusterDir(dataPath).resolve("PG_VERSION"), StandardCharsets.UTF_8).strip());
        } catch (NoSuchFileException _) {
            return Optional.empty();
        }
    }

    /// `assertEmbeddedVersionCompatible`: turn the cryptic "database files are
    /// incompatible with server" into an actionable message. No-op without a
    /// cluster or when the majors match.
    public static void assertCompatible(Path dataPath) throws IOException {
        var have = dataMajor(dataPath);
        var want = pinnedMajor();
        if (have.isEmpty() || have.get().equals(want)) return;
        throw new IllegalStateException(
                "embedded Postgres data dir is PG" + have.get() + " but this fcdev embeds PG" + want + "; a major "
                        + "upgrade is not in-place. Run 'fcdev db upgrade' (backs up the old cluster, "
                        + "re-initialises PG" + want + ", re-runs migrations + seed) or "
                        + "'fcdev start --embedded-db-reset' to wipe it");
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    /// `newEmbeddedPG` + `Start()`: boot (initialising on first run) the
    /// cluster in `<dataPath>/data` on `port` (0 = any free port), with the
    /// binaries cached under `cacheDir`. Ensures the `flowcatalyst` database
    /// exists. The caller owns [#close()].
    public static EmbeddedPg start(Path dataPath, int port, Path cacheDir) throws IOException {
        Files.createDirectories(dataPath);
        Files.createDirectories(cacheDir);
        var pg = EmbeddedPostgres.builder()
                .setDataDirectory(clusterDir(dataPath))
                .setCleanDataDirectory(false)
                .setRegisterShutdownHook(false)      // fcdev owns the stop ordering (server → pool → pg)
                .setOverrideWorkingDirectory(cacheDir.toFile())
                .setPort(port)
                .setPGStartupWait(START_TIMEOUT)
                .start();
        var handle = new EmbeddedPg(pg, dataPath);
        try {
            handle.ensureDatabase();
        } catch (SQLException e) {
            handle.close();
            throw new IOException("prepare embedded database: " + e.getMessage(), e);
        }
        return handle;
    }

    private void ensureDatabase() throws SQLException {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            boolean exists;
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                st.execute("CREATE DATABASE " + DATABASE);
                // Matches the Go distribution's credentials; local auth is `trust`, so this
                // only matters for tools that insist on sending a password.
                st.execute("ALTER ROLE " + USER + " WITH PASSWORD '" + PASSWORD + "'");
                LOG.info("created embedded database {}", DATABASE);
            }
        }
    }

    /// The bound port (the requested one, or the free port picked for 0).
    public int port() {
        return pg.getPort();
    }

    public Path dataPath() {
        return dataPath;
    }

    /// The libpq URL fcdev hands to the server: `postgresql://postgres:postgres@localhost:<port>/flowcatalyst?sslmode=disable`.
    public String url() {
        return url(port());
    }

    public static String url(int port) {
        return "postgresql://" + USER + ":" + PASSWORD + "@localhost:" + port + "/" + DATABASE + "?sslmode=disable";
    }

    /// `pg.Stop()`: `pg_ctl stop -m fast`. A failure to stop is logged, not
    /// thrown — this runs on the shutdown path where nothing can act on it.
    @Override
    public void close() {
        LOG.info("stopping embedded postgres");
        try {
            pg.close();
        } catch (IOException e) {
            LOG.warn("stopping embedded postgres: {}", e.toString());
        }
    }

    /// `os.RemoveAll`: delete `root` and everything under it; a missing root
    /// is fine. Used for `--embedded-db-reset` and `db upgrade --no-backup`.
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
