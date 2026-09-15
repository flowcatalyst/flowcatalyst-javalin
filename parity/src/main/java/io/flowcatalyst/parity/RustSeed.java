package io.flowcatalyst.parity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// Builds `seed_rust` for a Rust-vs-Java harness run (L1 lane,
/// `docs/java-parity-plan.md` §3): `fc-dev init` (Rust: migrate + seed
/// built-in roles/platform application/default processes + anchor admin +
/// default client + application — see `bin/fc-dev/src/init.rs`), then a
/// `CREATE DATABASE parity_rust TEMPLATE seed_rust` clone. Deliberately
/// separate from [Seed] — that class's Go/Java pipeline (and its seeder
/// workaround) is untouched by this lane; `seed_rust` is Rust's own schema,
/// populated by Rust's own migrator, and is never used as a Go/Java
/// template or vice versa.
///
/// Unlike [Seed], there is no known-defect workaround here: `fc-dev init`
/// either exits 0 or the run is a hard stop — there is no equivalent of the
/// Go seeder's schema-constraint bug to route around (yet; if one turns up,
/// it gets the same treatment `Seed` gives Go's).
public final class RustSeed {

    private static final Logger LOG = LoggerFactory.getLogger(RustSeed.class);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(120);

    /// @param ids ids read from `seed_rust` before the `parity_rust` clone was made — this
    ///            side's own client/app/admin, distinct from [Seed.Result#ids()] (spec §"ids
    ///            need not match across a Rust-vs-Java run" — see `docs/parity/l1.md`)
    public record Result(String rustUrl, Seed.Ids ids, Duration initDuration) {
    }

    private RustSeed() {
    }

    /// @param jwtKeyPath        the RSA-2048 PKCS#8 private key PEM shared with Java's side
    /// @param jwtPublicKeyPath  its X.509 SubjectPublicKeyInfo public counterpart — Rust's
    ///                          `fc-dev`/`fc-server` read `FC_JWT_PUBLIC_KEY_PATH` explicitly
    ///                          (pre-L0: L0 lands `FC_JWT_SIGNING_KEY_PATH` deriving the public
    ///                          key itself, at which point this parameter becomes redundant but
    ///                          harmless — both env vars are set)
    public static Result build(RustBinaries binaries, EmbeddedPg pg, Path scratchDir, Path jwtKeyPath,
                                Path jwtPublicKeyPath, String appKeyBase64, Path logDir) {
        pg.createDatabase("seed_rust");
        String seedUrl = pg.url("seed_rust");

        Instant t0 = Instant.now();
        runFcdevInit(binaries.fcdev(), seedUrl, scratchDir, appKeyBase64, jwtKeyPath, jwtPublicKeyPath);
        Duration initDuration = Duration.between(t0, Instant.now());
        LOG.info("rust fc-dev init against seed_rust took {}", initDuration);

        pg.createDatabaseFromTemplate("parity_rust", "seed_rust");

        Seed.Ids ids = readIds(pg.dataSource("seed_rust"));
        LOG.info("seed_rust ids client={} app={} admin={}", ids.clientId(), ids.appId(), ids.adminId());

        return new Result(pg.url("parity_rust"), ids, initDuration);
    }

    private static void runFcdevInit(Path fcdevBinary, String seedUrl, Path root, String appKeyBase64,
                                      Path jwtKeyPath, Path jwtPublicKeyPath) {
        List<String> cmd = List.of(fcdevBinary.toString(), "init",
                "--yes",
                "--database-url", seedUrl,
                "--admin-email", Seed.ADMIN_EMAIL,
                "--admin-password", Seed.ADMIN_PASSWORD,
                "--code", Seed.APP_CODE,
                "--name", Seed.APP_NAME,
                "--root", root.toString());
        // No --no-oauth-client: the java side of every parity run —
        // including RUST_JAVA — is seeded by Go's fcdev init (Seed.build,
        // untouched by this lane), not by Java's own InitCommand, and Go's
        // fcdev init unconditionally mints a service account + confidential
        // OAuth client (flowcatalyst-go/cmd/fcdev/init.go, no flag gates
        // it). Passing --no-oauth-client here left the Rust fixture with
        // neither — an asymmetry the harness itself introduced, not a real
        // product difference. Rust's init now runs its full default path,
        // matching Go's shape.
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("FLOWCATALYST_APP_KEY", appKeyBase64);
        // Rust's `init` runs against a plain --database-url, not the
        // embedded-pg path fc-dev's own `start` defaults to — matches the
        // e2e runner's own `initArgs` convention (no --embedded-db-* flags
        // on `init`, ever).
        env.put("FC_EMBEDDED_DB", "false");
        env.put("FC_JWT_PRIVATE_KEY_PATH", jwtKeyPath.toString());
        env.put("FC_JWT_PUBLIC_KEY_PATH", jwtPublicKeyPath.toString());
        // Pre-L0 fallback: once FC_JWT_SIGNING_KEY_PATH is read by Rust
        // (plan §3 L0), this is redundant with the two vars above but
        // harmless to also set.
        env.put("FC_JWT_SIGNING_KEY_PATH", jwtKeyPath.toString());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("start rust fc-dev init", e);
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("read rust fc-dev init output", e);
        }
        boolean finished;
        try {
            finished = process.waitFor(INIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for rust fc-dev init", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("rust fc-dev init timed out after " + INIT_TIMEOUT + ":\n" + output);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("rust fc-dev init failed (exit " + process.exitValue() + "):\n" + output);
        }
        LOG.debug("rust fc-dev init output:\n{}", output);
    }

    /// Plain JDBC, not jOOQ: `seed_rust` is Rust's own schema
    /// (`bin/fc-dev/src/init.rs`, `crates/fc-platform/migrations/`), not
    /// the Java repo's generated `io.flowcatalyst.db.generated.Tables` —
    /// the column names line up (`id`/`code` on `app_applications`,
    /// `identifier` on `tnt_clients`, `email` on `iam_principals`) but the
    /// jOOQ codegen does not cover this schema.
    private static Seed.Ids readIds(DataSource seedRustDataSource) {
        try (Connection c = seedRustDataSource.getConnection()) {
            String appId = queryOne(c, "SELECT id FROM app_applications WHERE code = ?", Seed.APP_CODE)
                    .orElseThrow(() -> new IllegalStateException("seed_rust has no application coded '" + Seed.APP_CODE + "'"));
            String clientId = queryOne(c, "SELECT id FROM tnt_clients WHERE identifier = ?", Seed.CLIENT_IDENTIFIER)
                    .orElseThrow(() -> new IllegalStateException("seed_rust has no client identified '" + Seed.CLIENT_IDENTIFIER + "'"));
            String adminId = queryOne(c, "SELECT id FROM iam_principals WHERE email = ?",
                    Seed.ADMIN_EMAIL.toLowerCase(Locale.ROOT))
                    .orElseThrow(() -> new IllegalStateException("seed_rust has no principal with email " + Seed.ADMIN_EMAIL));
            return new Seed.Ids(clientId, appId, adminId);
        } catch (SQLException e) {
            throw new IllegalStateException("read ids from seed_rust", e);
        }
    }

    private static java.util.Optional<String> queryOne(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? java.util.Optional.ofNullable(rs.getString(1)) : java.util.Optional.empty();
            }
        }
    }
}
