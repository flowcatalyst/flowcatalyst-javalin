package io.flowcatalyst.parity;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;

import io.flowcatalyst.platform.seed.Seeder;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// Builds `seed` (parity-harness spec §1): `fcdev init` (Go: migrate + system
/// seed + anchor admin + default client + application), a Go `fc-server`
/// start-then-stop against it to prove the day-one boot path, then two
/// `CREATE DATABASE … TEMPLATE seed` clones. Everything here runs once per
/// harness run, before either side's scenario traffic starts.
///
/// ## The Go-seeder workaround (§9 closure 2, ruled by the orchestrator)
///
/// **Go HEAD `cb83fd5` cannot bootstrap a fresh database.**
/// `internal/platform/seed/event_types.go`'s `seedPlatformEventTypes` inserts
/// every spec version with the literal `schema_type = 'JSON'`, but migration
/// `051_x06_enum_check_constraints.sql` (applied earlier in the very same
/// `fcdev init`) added `chk_msg_event_type_spec_versions_schema_type`, which
/// only accepts `JSON_SCHEMA` / `XSD` / `XML_SCHEMA` / `PROTO` / `PROTOBUF`.
/// The insert violates its own schema's constraint (SQLSTATE 23514) on the
/// first event type carrying a schema, and Go's seed loop aborts on the
/// first error — so every event type after that one in catalogue order, and
/// every `iam_principals` / `tnt_clients` / `app_applications` row `fcdev
/// init` creates *after* the system seed, never gets written either. This
/// is a pre-existing Go defect (`docs/backlog.md`), not introduced by this
/// harness, and already flagged in this repo's own [Seeder]'s comment; Java
/// never emits the bad value ([io.flowcatalyst.platform.eventtype.SchemaType]
/// has no `JSON` constant). The Go repository is read-only to this harness,
/// so the fix lives here, not there.
///
/// **The workaround** exploits the fact that both seeders are idempotent
/// over the same rows: Go's loop looks up each event type by `code` and
/// each spec version by `(event_type_id, version)` before writing, so
/// finding either already present is a skip, not a failure.
///
///  1. Run `fcdev init` once, as a plain flag-driven subprocess.
///  2. If it exits 0, Go HEAD bootstraps cleanly this run — done; go straight
///     to starting `fc-server` (step 4). (Kept as the fast path in case a
///     future Go HEAD fixes the seeder; the harness should stop taking the
///     workaround the day that happens, and this branch is how it notices.)
///  3. If it exits non-zero and its stderr names
///     [#KNOWN_DEFECT_CONSTRAINT], this is the known defect: run *Java's*
///     [Seeder] directly against the `seed` `DataSource` — **not**
///     [io.flowcatalyst.platform.shared.database.Migrator]; the schema is
///     Go's own (goose, migration 052), and Flyway must never touch a
///     database it did not baseline. Java's seeder is idempotent over rows
///     Go already wrote (upsert-by-code, `SchemaType.JSON_SCHEMA` for every
///     spec version) and fills in every event type Go's abort left missing.
///     Then run `fcdev init` again with the identical flags: `migrate.Run`
///     is a no-op (already at goose 52), `seed.NewSeeder` finds every event
///     type and its `v1` spec version already present and skips every one,
///     and the admin/tenant/application bootstrap — which never ran the
///     first time, because Go's `runInit` returns as soon as the system
///     seed errors — proceeds normally. This second attempt must exit 0;
///     anything else is a hard stop.
///  4. Any other non-zero exit (not naming the known constraint) is a hard
///     stop on attempt one — an unrelated failure is the orchestrator's to
///     debug, not something to paper over with the same workaround.
///  5. Start `fc-server` against `seed` (its own startup seed pass also
///     finds everything present and skips), wait for `/health`, stop it,
///     then clone.
public final class Seed {

    private static final Logger LOG = LoggerFactory.getLogger(Seed.class);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(60);

    /// The CHECK constraint name that marks the known Go seeder defect (see the class doc).
    public static final String KNOWN_DEFECT_CONSTRAINT = "chk_msg_event_type_spec_versions_schema_type";

    public static final String ADMIN_EMAIL = "parity-admin@example.com";
    /// No identity words (`PASSWORD_CONTAINS_IDENTITY` — CONVENTIONS.md).
    public static final String ADMIN_PASSWORD = "Correct-Harness-Battery-9147";
    public static final String APP_CODE = "parity";
    public static final String APP_NAME = "Parity";
    public static final String CLIENT_IDENTIFIER = "default";

    /// @param ids               `${client.id}` / `${app.id}` / `${admin.id}`, read from `seed`
    ///                          before either clone was made — identical on both sides by
    ///                          construction (spec §1)
    /// @param workedAroundGoSeederDefect whether attempt one hit the known defect and the
    ///                          Java-seeder-then-retry path (class doc) was taken
    /// @param goInitDuration    how long `fcdev init` took in total (every attempt, plus the
    ///                          Java seeder pass when the workaround fired)
    /// @param seedStartDuration how long Go's health-proving start against `seed` took
    /// @param seedStopDuration  how long that same Go instance took to stop
    public record Ids(String clientId, String appId, String adminId) {
    }

    public record Result(String goUrl, String javaUrl, Ids ids, boolean workedAroundGoSeederDefect,
                          Duration goInitDuration, Duration seedStartDuration, Duration seedStopDuration) {
    }

    private record InitOutcome(int exitCode, String output) {
        boolean succeeded() {
            return exitCode == 0;
        }

        boolean isKnownSeederDefect() {
            return output.contains(KNOWN_DEFECT_CONSTRAINT);
        }
    }

    private Seed() {
    }

    public static Result build(GoBinaries binaries, EmbeddedPg pg, Path scratchDir, Path jwtKeyPath,
                                String appKeyBase64, Path logDir) {
        pg.createDatabase("seed");
        String seedUrl = pg.url("seed");

        Instant t0 = Instant.now();
        boolean workedAround = runFcdevInitWithWorkaround(binaries.fcdev(), seedUrl, scratchDir, appKeyBase64, pg);
        Duration initDuration = Duration.between(t0, Instant.now());
        LOG.info("fcdev init against seed took {} (workaround for the Go seeder defect taken: {})",
                initDuration, workedAround);

        Map<String, String> env = ParityEnv.baseEnv(jwtKeyPath, appKeyBase64, ADMIN_EMAIL, ADMIN_PASSWORD);
        env.put("FC_DATABASE_URL", seedUrl);
        SubprocessSide seedServer = SubprocessSide.start(binaries.fcServer(), env, logDir.resolve("go-seed.log"));
        Duration seedStartDuration = seedServer.startDuration();
        seedServer.stop();
        Duration seedStopDuration = seedServer.stopDuration();
        LOG.info("Go fc-server against seed: start {} stop {}", seedStartDuration, seedStopDuration);

        pg.createDatabaseFromTemplate("parity_go", "seed");
        pg.createDatabaseFromTemplate("parity_java", "seed");

        Ids ids = readIds(pg.dataSource("seed"));
        LOG.info("seed ids client={} app={} admin={}", ids.clientId(), ids.appId(), ids.adminId());

        return new Result(pg.url("parity_go"), pg.url("parity_java"), ids, workedAround,
                initDuration, seedStartDuration, seedStopDuration);
    }

    /// Runs the sequence in the class doc. Returns whether the workaround
    /// path (steps 3+) was taken.
    ///
    /// @throws IllegalStateException attempt one failed for a reason other than the known
    ///                                defect, or the post-workaround retry still failed
    private static boolean runFcdevInitWithWorkaround(Path fcdevBinary, String seedUrl, Path root,
                                                        String appKeyBase64, EmbeddedPg pg) {
        InitOutcome first = runFcdevInit(fcdevBinary, seedUrl, root, appKeyBase64);
        if (first.succeeded()) {
            LOG.info("fcdev init succeeded on the first attempt — Go HEAD bootstraps cleanly this run");
            return false;
        }
        if (!first.isKnownSeederDefect()) {
            throw new IllegalStateException("fcdev init failed (exit " + first.exitCode() + "):\n" + first.output());
        }
        LOG.warn("fcdev init hit the known Go seeder defect ({}, docs/backlog.md) — filling the event-type "
                + "catalogue with the Java seeder, then re-running fcdev init", KNOWN_DEFECT_CONSTRAINT);

        // NOT Migrator.migrate: the schema is Go's own (goose migration 052); Flyway must
        // never touch a database it did not baseline (Migrator's own class doc).
        new Seeder(pg.dataSource("seed")).run();

        InitOutcome second = runFcdevInit(fcdevBinary, seedUrl, root, appKeyBase64);
        if (!second.succeeded()) {
            throw new IllegalStateException("fcdev init failed even after the Java-seeder workaround (exit "
                    + second.exitCode() + "):\n" + second.output());
        }
        LOG.info("fcdev init succeeded on the second attempt (workaround path)");
        return true;
    }

    private static InitOutcome runFcdevInit(Path fcdevBinary, String seedUrl, Path root, String appKeyBase64) {
        List<String> cmd = List.of(fcdevBinary.toString(), "init",
                "--yes",
                "--database-url", seedUrl,
                "--admin-email", ADMIN_EMAIL,
                "--admin-password", ADMIN_PASSWORD,
                "--code", APP_CODE,
                "--name", APP_NAME,
                "--root", root.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("FLOWCATALYST_APP_KEY", appKeyBase64);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("start fcdev init", e);
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("read fcdev init output", e);
        }
        boolean finished;
        try {
            finished = process.waitFor(INIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for fcdev init", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("fcdev init timed out after " + INIT_TIMEOUT + ":\n" + output);
        }
        LOG.debug("fcdev init (exit {}) output:\n{}", process.exitValue(), output);
        return new InitOutcome(process.exitValue(), output);
    }

    private static Ids readIds(DataSource seedDataSource) {
        DSLContext db = DSL.using(seedDataSource, SQLDialect.POSTGRES);
        String appId = db.select(APP_APPLICATIONS.ID).from(APP_APPLICATIONS)
                .where(APP_APPLICATIONS.CODE.eq(APP_CODE))
                .fetchOptional(APP_APPLICATIONS.ID)
                .orElseThrow(() -> new IllegalStateException("seed has no application coded '" + APP_CODE + "'"));
        String clientId = db.select(TNT_CLIENTS.ID).from(TNT_CLIENTS)
                .where(TNT_CLIENTS.IDENTIFIER.eq(CLIENT_IDENTIFIER))
                .fetchOptional(TNT_CLIENTS.ID)
                .orElseThrow(() -> new IllegalStateException("seed has no client identified '" + CLIENT_IDENTIFIER + "'"));
        String adminId = db.select(IAM_PRINCIPALS.ID).from(IAM_PRINCIPALS)
                .where(IAM_PRINCIPALS.EMAIL.eq(ADMIN_EMAIL.toLowerCase(Locale.ROOT)))
                .fetchOptional(IAM_PRINCIPALS.ID)
                .orElseThrow(() -> new IllegalStateException("seed has no principal with email " + ADMIN_EMAIL));
        return new Ids(clientId, appId, adminId);
    }
}
