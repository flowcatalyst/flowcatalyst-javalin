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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
        return start(dataPath, port, cacheDir, null);
    }

    /// As [#start(Path, int, Path)], with `offlineBinaryOverride` — an
    /// operator-supplied `.txz` (`--embedded-db-binary` / `FC_EMBEDDED_DB_BINARY`)
    /// used verbatim instead of resolving a binary from the classpath or
    /// Maven Central. `null` for the normal resolution order.
    public static EmbeddedPg start(Path dataPath, int port, Path cacheDir, Path offlineBinaryOverride) throws IOException {
        Files.createDirectories(dataPath);
        Files.createDirectories(cacheDir);
        var pg = EmbeddedPostgres.builder()
                .setDataDirectory(clusterDir(dataPath))
                .setCleanDataDirectory(false)
                .setRegisterShutdownHook(false)      // fcdev owns the stop ordering (server → pool → pg)
                .setOverrideWorkingDirectory(cacheDir.toFile())
                .setPort(port)
                // Every connection zonky makes itself — the startup readiness
                // check, ensureDatabase, the extension check — goes through a
                // PGSimpleDataSource built from this connectConfig. Without a
                // password they are sent unauthenticated, which is fine on a
                // cluster this binary initialised (`initdb -A trust`) and fails
                // on one initialised by the **Go** fcdev, whose pg_hba.conf is
                // `password` for every host and local line. The two binaries
                // deliberately share one cluster (DevPaths), so adopting Go's
                // is the normal case, not the exotic one: Postgres came up and
                // reported "ready to accept connections", then zonky's own
                // readiness probe was refused and it gave up after 60s. Go
                // passes the same pair (`cmd/fcdev/embedded.go`: Username
                // "postgres", Password "postgres"); a trust cluster ignores it.
                .setConnectConfig("password", PASSWORD)
                .setPGStartupWait(START_TIMEOUT)
                .setPgBinaryResolver(new MavenCentralPgBinaryResolver(cacheDir, Version.embeddedPgVersion(), offlineBinaryOverride))
                .start();
        var handle = new EmbeddedPg(pg, dataPath);
        try {
            handle.ensureDatabase();
            handle.provisionPostgis(cacheDir);
            handle.verifyExtensionsServable();
        } catch (SQLException e) {
            handle.close();
            throw new IOException("prepare embedded database: " + e.getMessage(), e);
        }
        return handle;
    }

    /// Step 1 of the PostGIS story ([PgExtensions] javadoc): copy the family
    /// into this fcdev's *own* Postgres tree if the running server doesn't
    /// already have it, resolving that tree via `pg_config()` rather than
    /// guessing zonky's `PG-<md5>` directory name — this only works after
    /// the server has started, since extension files are read lazily.
    ///
    /// Deliberately non-fatal: this provisions an *optional* extension, and
    /// a developer without PostGIS on their machine (no donor found) is the
    /// common case, not an error. Any failure here — a locked-down
    /// filesystem, a donor tree that turns out to be unreadable — is logged
    /// and swallowed so it can never keep the database from coming up.
    private void provisionPostgis(Path cacheDir) {
        try {
            var dirs = pgConfigDirs();
            var pkglibdir = Path.of(dirs.get("PKGLIBDIR"));
            var sharedir = Path.of(dirs.get("SHAREDIR"));
            var extensionDir = sharedir.resolve("extension");
            if (Files.isRegularFile(extensionDir.resolve("postgis.control"))) return;

            var donor = PgExtensions.firstUsable(PgExtensions.donorCandidates(pinnedMajor(), cacheDir));
            if (donor.isEmpty()) return;

            var copied = PgExtensions.mirror(donor.get(), pkglibdir, extensionDir);
            LOG.atInfo().setMessage("provisioned PostGIS file(s)")
                    .addKeyValue("count", copied.size())
                    .addKeyValue("path", sharedir)
                    .addKeyValue("source", donor.get().extensions())
                    .log();
        } catch (Exception e) {
            LOG.atWarn().setMessage("provisioning PostGIS into the embedded Postgres tree failed")
                    .setCause(e)
                    .log();
        }
    }

    /// `SELECT name, setting FROM pg_config() WHERE name IN ('PKGLIBDIR','SHAREDIR')`
    /// — the running server's own module directory (`$libdir`) and share
    /// directory, straight from Postgres rather than reconstructed from
    /// `cacheDir`.
    private Map<String, String> pgConfigDirs() throws SQLException {
        var dirs = new HashMap<String, String>();
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name, setting FROM pg_config() WHERE name IN ('PKGLIBDIR','SHAREDIR')")) {
            while (rs.next()) {
                dirs.put(rs.getString("name"), rs.getString("setting"));
            }
        }
        return dirs;
    }

    /// Step 2: fail loudly, at startup, if the cluster needs an extension
    /// this Postgres tree cannot supply. The cluster directory is *shared*
    /// with the Go `fcdev` ([EmbeddedPg] javadoc) and Go's tree carries a
    /// hand-transplanted PostGIS the Java tree does not — so a cluster
    /// created or extended under Go can legitimately contain extensions
    /// this tree can't serve. Catching that here, with the reason, beats
    /// discovering it later inside an unrelated query.
    private void verifyExtensionsServable() throws SQLException, IOException {
        var dirs = pgConfigDirs();
        var extensionDir = Path.of(dirs.get("SHAREDIR")).resolve("extension");

        var registered = new HashSet<String>();
        try (Connection c = pg.getDatabase(USER, DATABASE).getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT extname FROM pg_extension")) {
            while (rs.next()) {
                registered.add(rs.getString("extname"));
            }
        }

        var missing = PgExtensions.missingControlFiles(registered, extensionDir);
        if (missing.isEmpty()) return;

        close();
        throw new IOException(
                "embedded Postgres cluster at " + dataPath + " has extension(s) " + missing
                        + " installed, but this fcdev's Postgres tree at " + extensionDir
                        + " does not provide them, so any query touching those objects will fail; "
                        + "install the matching PostGIS for PG" + pinnedMajor()
                        + " (on macOS 'brew install postgis') and start again");
    }

    private void ensureDatabase() throws SQLException {
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            boolean exists;
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                st.execute("CREATE DATABASE " + DATABASE);
                // Matches the Go distribution's credentials. On a cluster this
                // binary initialised the auth is `trust` and this only matters
                // for tools that insist on sending a password; on an adopted
                // Go cluster the hba is `password` and it is load-bearing.
                st.execute("ALTER ROLE " + USER + " WITH PASSWORD '" + PASSWORD + "'");
                LOG.atInfo().setMessage("created embedded database")
                        .addKeyValue("name", DATABASE)
                        .log();
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
            LOG.atWarn().setMessage("stopping embedded postgres failed")
                    .setCause(e)
                    .log();
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
