package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.CorruptFunctionVersionException;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionHost;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.RoutePattern;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.SecretValue;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static io.flowcatalyst.db.generated.Tables.FN_VERSIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `DesiredState.build` (spec `function-api.md` §6.1, §8 P13, P14). Every
/// clause of P13 pinned with its own fixture; P14's determinism pinned by
/// building twice and comparing bytes, and by seeding a 3-function fixture
/// in reverse-address order to prove the sort is on `address`, not
/// insertion order.
class DesiredStateTest {

    /// Its OWN database, migrated fresh — not the shared `TestPg.dataSource()`.
    /// `build()` reads EVERY `ACTIVE` function in the database (spec §6.1: it filters to
    /// `pool` only after loading each function's live/candidate versions), so on the
    /// shared instance a row left behind by an unrelated class — e.g. a `fn_versions`
    /// row with an unreadable manifest — poisons every fixture here, whichever pool it
    /// asserts on (`docs/STATUS.md`'s intermittent-failure investigation, 2026-09-20;
    /// same mechanism as `ef190de3`'s four classes). `DesiredState` itself also over-reads
    /// for this reason — it loads versions for every active function up front rather than
    /// scoping the initial read to `pool` — worth narrowing some day, not redesigned here.
    private static final DataSource DS = newDatabase();
    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(DS);
    private static final FunctionHostRepository hosts = new FunctionHostRepository(DS);
    private static final ApplicationRepository applications = new ApplicationRepository(DS);
    private static final ServiceAccountRepository serviceAccounts =
            new ServiceAccountRepository(DS, java.util.Optional.empty());
    private static final FunctionSettingsRepository settings =
            new FunctionSettingsRepository(DS, java.util.Optional.empty());
    private static final FunctionRouteRepository routes = new FunctionRouteRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final DesiredState DESIRED =
            new DesiredState(functions, versions, hosts, serviceAccounts, settings, routes);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static DataSource newDatabase() {
        DataSource ds = TestPg.newDatabase("desired_state_test");
        Migrator.migrate(ds);
        return ds;
    }

    private static Manifest manifestForPool(String pool, boolean warm) {
        String json = """
                {"runtime":"jvm","entrypoint":"com.acme.Fn","pool":"%s","warm":%s}
                """.formatted(pool, warm);
        return Manifest.parseStrict(Json.MAPPER.readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    private static Function createFunction(String tag) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("ds" + RUN), new DnsLabel("svc"), new DnsLabel(tag));
        Function f = Function.create("app_" + RUN, address, FunctionOwner.ofClientId("clt_" + RUN), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static void save(Function f) {
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
    }

    private static FunctionVersion publish(Function f, int version, Manifest manifest) {
        return publish(f, version, manifest, null);
    }

    private static FunctionVersion publish(Function f, int version, Manifest manifest, SignerIdentity signer) {
        FunctionVersion v = FunctionVersion.publish(f.id(), version, "oci://artifact", digest(f.id() + version),
                signer == null ? null : "bundle-json", null, signer, manifest, "prn_publisher", Instant.now());
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
        return v;
    }

    private static void save(FunctionVersion v) {
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
    }

    private static Digest digest(String seed) {
        String hex = Integer.toHexString(seed.hashCode()) + "0".repeat(64);
        return Digest.parse("sha256:" + hex.substring(0, 64));
    }

    /// Note (task): `Promote` does not exist yet (slice B3) — a live alias is
    /// set directly through `Function.promote` + `FunctionRepository.persist`.
    private static Function promote(Function f, FunctionVersion v) {
        Function.Promoted p = f.promote(Function.LIVE, v, "prn_promoter", Instant.now());
        save(p.function());
        return p.function();
    }

    private static String persistApplication(String code) {
        Application app = Application.create(ApplicationType.APPLICATION, code, code);
        uow.inTransaction(tx -> {
            applications.persist(app, tx.dbTx());
            return null;
        });
        return app.id();
    }

    private static void serviceAccount(String applicationId, String secret, boolean active, Instant createdAt) {
        DSLContext db = DSL.using(DS, SQLDialect.POSTGRES);
        db.insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, EntityType.SERVICE_ACCOUNT.generate())
                .set(IAM_SERVICE_ACCOUNTS.CODE, "ds-svc-" + fresh())
                .set(IAM_SERVICE_ACCOUNTS.NAME, "desired-state test service account")
                .set(IAM_SERVICE_ACCOUNTS.APPLICATION_ID, applicationId)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, active)
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TYPE, "BEARER_TOKEN")
                .set(IAM_SERVICE_ACCOUNTS.WH_AUTH_TOKEN_REF, "ds-token-" + fresh())
                .set(IAM_SERVICE_ACCOUNTS.WH_SIGNING_SECRET_REF, secret)
                .set(IAM_SERVICE_ACCOUNTS.CREATED_AT, createdAt.atOffset(ZoneOffset.UTC))
                .execute();
    }

    private static void serviceAccount(String applicationId, String secret, boolean active) {
        serviceAccount(applicationId, secret, active, Instant.now());
    }

    private static Function createFunctionForApp(String tag, String applicationId) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("ds" + RUN), new DnsLabel("svc"), new DnsLabel(tag));
        Function f = Function.create(applicationId, address, FunctionOwner.ofClientId("clt_" + RUN), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static Function createPlatformFunction(String tag, String applicationId) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("ds" + RUN), new DnsLabel("svc"), new DnsLabel(tag));
        Function f = Function.create(applicationId, address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    /// Raw-inserts a `fn_versions` row [Manifest#readStored] refuses (`runtime` is not
    /// `jvm`/`wasm`) — the same shape the blast-radius fix defends against
    /// (`FunctionSchemaTest`'s `digestCheckAcceptsSha256WithSixtyFourHexChars` leaves an
    /// equivalent row behind on the SHARED `TestPg` database, which is what broke this
    /// class before it got its own). `pool` IS still readable (`Manifest#peekStoredPool`
    /// never depends on `runtime`) — callers pick it to control whether
    /// [DesiredState#build] should treat the row as possibly theirs.
    private static FunctionVersion insertCorruptVersion(Function f, int version, String pool) {
        String id = "fnv_" + fresh();
        String manifestJson = """
                {"runtime":"not-a-runtime","entrypoint":"x","pool":"%s"}""".formatted(pool);
        DSLContext db = DSL.using(DS, SQLDialect.POSTGRES);
        db.insertInto(FN_VERSIONS)
                .set(FN_VERSIONS.ID, id)
                .set(FN_VERSIONS.FUNCTION_ID, f.id())
                .set(FN_VERSIONS.VERSION, version)
                .set(FN_VERSIONS.ARTIFACT_REF, "oci://corrupt")
                .set(FN_VERSIONS.DIGEST, digest(f.id() + version).value())
                .set(FN_VERSIONS.MANIFEST, JSONB.jsonb(manifestJson))
                .set(FN_VERSIONS.STATE, "PUBLISHED")
                .set(FN_VERSIONS.PUBLISHED_BY, "prn_test")
                .execute();
        // In-memory placeholder ONLY to satisfy Function#promote's own-function/state
        // checks (it never reads the manifest) — the persisted row above is what
        // DesiredState actually reads back; this placeholder's manifest is never stored.
        return new FunctionVersion(id, f.id(), version, "oci://corrupt", digest(f.id() + version), null, null, null,
                manifestForPool(pool, false), new FunctionVersion.VersionState.Published(), "prn_test", Instant.now());
    }

    private static Manifest manifestWebhook(String pool) {
        String json = """
                {"runtime":"jvm","entrypoint":"com.acme.Fn","pool":"%s",
                 "endpoints":[{"path":"/events/*","auth":"webhook"}]}
                """.formatted(pool);
        return Manifest.parseStrict(Json.MAPPER.readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void heartbeatHost(String hostId, DnsLabel pool, Instant lastHeartbeat, List<FunctionHost.LoadedVersion> loaded) {
        FunctionHost h = new FunctionHost(hostId, pool, FunctionHost.HostState.ACTIVE, loaded, lastHeartbeat, lastHeartbeat);
        uow.inTransaction(tx -> {
            hosts.persist(h, tx.dbTx());
            return null;
        });
    }

    // ── P13: live ─────────────────────────────────────────────────────────

    @Test
    void liveVersionIsIncludedWithRoleLive() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("live" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false));
        promote(f, v);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        DesiredState.FunctionEntry entry = doc.functions().getFirst();
        assertThat(entry.address()).isEqualTo(f.address().render());
        assertThat(entry.role()).isEqualTo("live");
        assertThat(entry.version()).isEqualTo(1);
        assertThat(entry.versionId()).isEqualTo(v.id());
        assertThat(entry.digest()).isEqualTo(v.digest().value());
        assertThat(entry.mode()).as("mutant: mode ignores manifest.warm() for a live entry").isEqualTo("lazy");
    }

    @Test
    void liveEntryModeIsWarmWhenTheManifestSaysSo() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("warm" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), true));
        promote(f, v);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        assertThat(doc.functions().getFirst().mode()).isEqualTo("warm");
    }

    /// spec `function-zones-and-aliases.md` §5: `[]`, never omitted, when no
    /// named alias points at this entry's version (mutant: drop the field
    /// entirely, which `additionalProperties: false` in the OpenAPI schema
    /// and the `required` list both also guard).
    @Test
    void liveEntryCarriesEmptyAliasesWhenNoneNamed() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("noalias" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false));
        promote(f, v);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions().getFirst().aliases()).isEmpty();
    }

    /// spec §5: every NAMED (non-`live`) alias pointing at an entry's
    /// version is carried, sorted — and a candidate's own (empty) alias list
    /// is unaffected by aliases pointing at `live`'s version (mutant: carry
    /// unsorted, or carry `live` itself, or leak them onto the wrong entry).
    @Test
    void liveEntryCarriesItsNamedAliasesSortedAndTheCandidateDoesNotInheritThem() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("aliassort" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        save(v1.markReady(Instant.now()));
        f = promote(f, v1);
        // Two named aliases on v1, promoted out of alphabetical order.
        f = f.promote("zz", v1, "prn_promoter", Instant.now()).function();
        f = f.promote("aa", v1, "prn_promoter", Instant.now()).function();
        save(f);
        FunctionVersion v2 = publish(f, 2, manifestForPool(pool.value(), false)); // newer candidate, no aliases of its own

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(2);
        DesiredState.FunctionEntry live = doc.functions().get(0);
        DesiredState.FunctionEntry candidate = doc.functions().get(1);
        assertThat(live.role()).isEqualTo("live");
        assertThat(live.aliases()).as("mutant: unsorted, or live itself included").containsExactly("aa", "zz");
        assertThat(candidate.role()).isEqualTo("candidate");
        assertThat(candidate.aliases()).as("mutant: aliases pointing at v1 leaked onto v2's entry").isEmpty();
    }

    // ── package J3 (function-zones-and-aliases.md §5): alias-only entries ────

    /// D1: a version pointed at ONLY by named aliases (not live, not the
    /// newest published candidate) appears with `role: alias`, `mode: lazy`
    /// regardless of its own manifest's `warm`, carries its alias names
    /// sorted, and — because it is still an entry of `functions` — a host
    /// that reports it loaded is never listed in `unload` (mutant: drop the
    /// entry from `functions` entirely, which would then surface it in
    /// `unload` since the heartbeat below still reports it).
    @Test
    void anAliasOnlyVersionAppearsWithRoleAliasIsNeverUnloadedAndCarriesItsAliasesSorted() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("aliasonly" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        save(v1.markReady(Instant.now()));
        f = promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestForPool(pool.value(), true)); // warm manifest — must still report lazy
        save(v2.markReady(Instant.now()));
        FunctionVersion v3 = publish(f, 3, manifestForPool(pool.value(), false)); // newest published => the candidate
        f = f.promote("staging", v2, "prn_promoter", Instant.now()).function();
        f = f.promote("qa", v2, "prn_promoter", Instant.now()).function();
        save(f);

        Instant now = Instant.now();
        heartbeatHost("host-" + fresh(), pool, now,
                List.of(new FunctionHost.LoadedVersion(f.address(), 2, new FunctionHost.LoadState.Loaded())));

        DesiredState.Document doc = DESIRED.build(pool, now);
        assertThat(doc.functions()).extracting(DesiredState.FunctionEntry::role)
                .as("mutant: an aliased-only version is dropped from functions entirely")
                .containsExactly("live", "alias", "candidate");
        DesiredState.FunctionEntry aliasEntry = doc.functions().get(1);
        assertThat(aliasEntry.version()).isEqualTo(2);
        assertThat(aliasEntry.versionId()).isEqualTo(v2.id());
        assertThat(aliasEntry.mode()).as("mutant: an alias-only entry is ever warm").isEqualTo("lazy");
        assertThat(aliasEntry.aliases()).as("mutant: unsorted, or an alias name dropped")
                .containsExactly("qa", "staging");
        assertThat(doc.unload())
                .as("mutant: dropping the alias entry from functions would surface the reported version here")
                .isEmpty();
    }

    /// D1 corollary: an aliased version whose OWN manifest names a DIFFERENT
    /// pool from the one being built never appears — the alias-only entry is
    /// filtered by its own version's pool exactly like live/candidate are
    /// (spec §5: "only if its own manifest's pool is the requested pool").
    @Test
    void anAliasOnlyVersionInADifferentPoolIsAbsentFromThisPoolsDocument() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        DnsLabel otherPool = new DnsLabel("otherpool" + fresh());
        Function f = createFunction("aliaspool" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        save(v1.markReady(Instant.now()));
        f = promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestForPool(otherPool.value(), false)); // a different pool entirely
        save(v2.markReady(Instant.now()));
        f = f.promote("qa", v2, "prn_promoter", Instant.now()).function();
        save(f);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).as("mutant: ignore the aliased version's own manifest pool")
                .extracting(DesiredState.FunctionEntry::role).containsExactly("live");
    }

    /// D1's other mutant: the top-level `publicRoutes[]` carries the route's
    /// `aliasPrefixes`, copied verbatim from `fn_routes` (mutant: drop the field).
    @Test
    void publicRoutesCarryAliasPrefixes() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("routealias" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        f = promote(f, v1);
        Hostname hostname = Hostname.parse("api" + fresh() + ".acme.com");
        FunctionRoute route = FunctionRoute.of(f.id(), hostname, RoutePattern.parse("/"), List.of("qa", "staging"),
                Instant.now());
        String functionId = f.id();
        uow.inTransaction(tx -> {
            routes.replaceForFunction(functionId, List.of(route), tx.dbTx());
            return null;
        });

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.publicRoutes()).hasSize(1);
        assertThat(doc.publicRoutes().getFirst().aliasPrefixes())
                .as("mutant: drop aliasPrefixes from the desired-state publicRoutes entry")
                .containsExactly("qa", "staging");
    }

    // ── P13: newer candidate ──────────────────────────────────────────────

    @Test
    void aNewerPublishedVersionIsIncludedAsAnAlwaysLazyCandidate() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("cand" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        save(v1.markReady(Instant.now())); // v1 leaves PUBLISHED so it is unambiguously "live", not also "newest published"
        f = promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestForPool(pool.value(), true)); // warm manifest — candidate must still be lazy

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(2);
        assertThat(doc.functions()).extracting(DesiredState.FunctionEntry::role).containsExactly("live", "candidate");
        DesiredState.FunctionEntry candidate = doc.functions().get(1);
        assertThat(candidate.version()).isEqualTo(2);
        assertThat(candidate.versionId()).isEqualTo(v2.id());
        assertThat(candidate.mode()).as("mutant: a candidate is never warm, regardless of its own manifest").isEqualTo("lazy");
    }

    /// R3 (`function-registry.md` §9): a function that has NEVER been
    /// promoted still delivers its first `PUBLISHED` version as a candidate —
    /// otherwise a first version could never become `READY` at all, since
    /// only a `READY` version may be promoted (B3).
    @Test
    void aFirstPublishedVersionWithNoLiveYetIsStillACandidate() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("nolive" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).as("mutant: require a live version before ever considering a candidate").hasSize(1);
        assertThat(doc.functions().getFirst().role()).isEqualTo("candidate");
        assertThat(doc.functions().getFirst().version()).isEqualTo(1);
        assertThat(doc.functions().getFirst().versionId()).isEqualTo(v1.id());
    }

    // ── P13: candidate OLDER than live is absent ─────────────────────────

    @Test
    void aPublishedVersionOlderThanLiveIsNotACandidate() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("older" + fresh());
        // Two versions published; only v2 ever gets marked ready and promoted (the
        // function-registry.md M10 scenario) — v1 is left dangling, forever PUBLISHED,
        // at a LOWER version number than live.
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        FunctionVersion v2 = publish(f, 2, manifestForPool(pool.value(), false));
        save(v2.markReady(Instant.now()));
        promote(f, v2);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).as("mutant: drop the > live version check").hasSize(1);
        assertThat(doc.functions().getFirst().role()).isEqualTo("live");
        assertThat(doc.functions().getFirst().version()).isEqualTo(2);
    }

    // ── P13: per-version pool filter ─────────────────────────────────────

    @Test
    void aFunctionWithLiveInOnePoolAndCandidateInAnotherAppearsOnceInEachPoolsDocument() {
        DnsLabel poolA = new DnsLabel("poola" + fresh());
        DnsLabel poolB = new DnsLabel("poolb" + fresh());
        Function f = createFunction("split" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(poolA.value(), false));
        save(v1.markReady(Instant.now()));
        f = promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestForPool(poolB.value(), false));

        DesiredState.Document docA = DESIRED.build(poolA, Instant.now());
        assertThat(docA.functions()).hasSize(1);
        assertThat(docA.functions().getFirst().role()).isEqualTo("live");
        assertThat(docA.functions().getFirst().version()).isEqualTo(1);

        DesiredState.Document docB = DESIRED.build(poolB, Instant.now());
        assertThat(docB.functions()).hasSize(1);
        assertThat(docB.functions().getFirst().role()).isEqualTo("candidate");
        assertThat(docB.functions().getFirst().version()).isEqualTo(2);
    }

    // ── P13: disabled absent ──────────────────────────────────────────────

    @Test
    void aDisabledFunctionContributesNothing() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("disabled" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false));
        f = promote(f, v);
        save(f.disable(Instant.now()));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).as("mutant: skip the ACTIVE filter").isEmpty();
    }

    // ── P13: unload — reported while a live host reports it, gone once stale ──

    @Test
    void unloadListsAVersionALiveHostStillReportsAndDropsItOnceTheHostIsStale() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("unload" + fresh());
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false));
        save(v1.markReady(Instant.now()));
        f = promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestForPool(pool.value(), false));
        save(v2.markReady(Instant.now()));
        promote(f, v2); // v1 is no longer live
        save(v1.retire(Instant.now()));

        Instant now = Instant.now();
        heartbeatHost("host-" + fresh(), pool, now,
                List.of(new FunctionHost.LoadedVersion(f.address(), 1, new FunctionHost.LoadState.Loaded())));

        DesiredState.Document live = DESIRED.build(pool, now);
        assertThat(live.functions()).extracting(DesiredState.FunctionEntry::version).containsExactly(2);
        assertThat(live.unload()).as("mutant: drop the unload computation entirely")
                .containsExactly(new DesiredState.UnloadEntry(f.address().render(), 1));

        // Driven past FunctionHost.LIVE_WINDOW (45s) with no new heartbeat — never sleeps.
        Instant later = now.plus(FunctionHost.LIVE_WINDOW).plusSeconds(1);
        DesiredState.Document staleDoc = DESIRED.build(pool, later);
        assertThat(staleDoc.unload()).as("mutant: ignore the host-liveness cutoff").isEmpty();
    }

    @Test
    void unloadNeverListsAVersionThatIsStillInFunctions() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("keep" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false));
        f = promote(f, v);

        Instant now = Instant.now();
        heartbeatHost("host-" + fresh(), pool, now,
                List.of(new FunctionHost.LoadedVersion(f.address(), 1, new FunctionHost.LoadState.Loaded())));

        DesiredState.Document doc = DESIRED.build(pool, now);
        assertThat(doc.functions()).hasSize(1);
        assertThat(doc.unload()).as("the live version is desired, not stray — must not be double-listed as unload").isEmpty();
    }

    // ── P14: deterministic ordering + byte-identical repeats ────────────────

    @Test
    void functionsAreSortedByAddressThenVersionRegardlessOfInsertionOrder() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String tag = fresh();
        // Inserted in REVERSE address order on purpose.
        Function c = createFunction("z" + tag);
        Function b = createFunction("m" + tag);
        Function a = createFunction("a" + tag);
        promote(c, publish(c, 1, manifestForPool(pool.value(), false)));
        promote(b, publish(b, 1, manifestForPool(pool.value(), false)));
        promote(a, publish(a, 1, manifestForPool(pool.value(), false)));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).extracting(DesiredState.FunctionEntry::address)
                .containsExactly(a.address().render(), b.address().render(), c.address().render());
    }

    @Test
    void twoConsecutiveBuildsOfTheSameStateProduceByteIdenticalJson() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f1 = createFunction("bytes1" + fresh());
        Function f2 = createFunction("bytes2" + fresh());
        promote(f1, publish(f1, 1, manifestForPool(pool.value(), false)));
        promote(f2, publish(f2, 1, manifestForPool(pool.value(), true)));

        Instant now = Instant.now();
        String first = Json.write(DESIRED.build(pool, now));
        String second = Json.write(DESIRED.build(pool, now));
        assertThat(second).as("mutant: any nondeterministic ordering breaks byte-identity").isEqualTo(first);
    }

    // ── R13 (function-host-reconciler.md §0): signer carried when recorded, omitted when not ──

    @Test
    void signerIsCarriedWhenTheVersionWasPublishedWithOneAndByteDeterminismStillHolds() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("signed" + fresh());
        SignerIdentity signer = new SignerIdentity("https://issuer.example", "subject-" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false), signer);
        promote(f, v);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        DesiredState.FunctionEntry entry = doc.functions().getFirst();
        assertThat(entry.signer()).as("mutant: drop the signer from the desired-state entry")
                .isEqualTo(new DesiredState.SignerView(signer.issuer(), signer.subject()));

        String json = Json.write(doc);
        assertThat(json).contains("\"issuer\":\"https://issuer.example\"");
        assertThat(json).contains("\"subject\":\"" + signer.subject() + "\"");

        // Byte-determinism still holds with a signer present (spec §0 / P14).
        Instant now = Instant.now();
        String first = Json.write(DESIRED.build(pool, now));
        String second = Json.write(DESIRED.build(pool, now));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void signerIsOmittedNotNullWhenTheVersionWasPublishedWithoutOne() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function f = createFunction("unsigned" + fresh());
        FunctionVersion v = publish(f, 1, manifestForPool(pool.value(), false)); // no signer
        promote(f, v);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        assertThat(doc.functions().getFirst().signer())
                .as("mutant: emit a signer for a version published with signatures off").isNull();

        String json = Json.write(doc);
        assertThat(json).as("mutant: write null instead of omitting the absent signer field")
                .doesNotContain("\"signer\"");
    }

    // ── V7: webhookSigningSecret (spec `function-invocation.md` §6, §10) ────

    @Test
    void webhookSigningSecretIsCarriedOnlyForAVersionWithAWebhookEndpoint() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("v7app" + fresh());
        String secret = "v7-secret-" + fresh();
        serviceAccount(appId, secret, true);

        Function withHook = createFunctionForApp("v7hook" + fresh(), appId);
        FunctionVersion vHook = publish(withHook, 1, manifestWebhook(pool.value()));
        promote(withHook, vHook);

        Function withoutHook = createFunctionForApp("v7nohook" + fresh(), appId);
        FunctionVersion vNoHook = publish(withoutHook, 1, manifestForPool(pool.value(), false));
        promote(withoutHook, vNoHook);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        var byAddress = doc.functions().stream()
                .collect(java.util.stream.Collectors.toMap(DesiredState.FunctionEntry::address, e -> e));

        assertThat(byAddress.get(withHook.address().render()).webhookSigningSecret())
                .as("mutant: never include it").isEqualTo(secret);
        assertThat(byAddress.get(withoutHook.address().render()).webhookSigningSecret())
                .as("mutant: always include it — no webhook endpoint means no secret").isNull();
    }

    /// Spec §6, "live-or-candidate" as built: the secret decision is made
    /// per ENTRY, from that entry's own version's manifest — [#signingSecretFor]
    /// is called once per `FunctionEntry` with that entry's own `v`, never
    /// the function's live version regardless of which entry is being built.
    /// So a candidate with a webhook endpoint carries the secret even when
    /// the function's CURRENT live version has none — pinned here rather
    /// than left as an open question, since the code already decides it.
    @Test
    void webhookSigningSecretIsCarriedByTheCandidateEvenWhenTheLiveVersionHasNoWebhookEndpoint() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("v7cand" + fresh());
        String secret = "v7-cand-secret-" + fresh();
        serviceAccount(appId, secret, true);

        Function f = createFunctionForApp("v7c" + fresh(), appId);
        FunctionVersion v1 = publish(f, 1, manifestForPool(pool.value(), false)); // no webhook endpoint
        promote(f, v1);
        FunctionVersion v2 = publish(f, 2, manifestWebhook(pool.value())); // webhook endpoint, candidate only

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).extracting(DesiredState.FunctionEntry::role)
                .containsExactly("live", "candidate");
        assertThat(doc.functions().get(0).webhookSigningSecret())
                .as("live has no webhook endpoint: no secret").isNull();
        assertThat(doc.functions().get(1).webhookSigningSecret())
                .as("mutant: decide the secret from the live version, not each entry's own")
                .isEqualTo(secret);
    }

    @Test
    void webhookSigningSecretIsAbsentWhenTheApplicationHasNoSigningSecret() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("v7nosecret" + fresh()); // no service account at all
        Function f = createFunctionForApp("v7ns" + fresh(), appId);
        FunctionVersion v = publish(f, 1, manifestWebhook(pool.value()));
        promote(f, v);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        assertThat(doc.functions().getFirst().webhookSigningSecret())
                .as("no active service account with a secret -> omitted, never \"\"").isNull();
    }

    @Test
    void rotatingTheWebhookSigningSecretChangesTheETagBytes() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("v7rot" + fresh());
        serviceAccount(appId, "before-" + fresh(), true);
        Function f = createFunctionForApp("v7r" + fresh(), appId);
        FunctionVersion v = publish(f, 1, manifestWebhook(pool.value()));
        promote(f, v);

        String before = Json.write(DESIRED.build(pool, Instant.now()));
        String beforeEtag = sha256Hex(before);

        // Rotate: a NEW active account (older accounts stay — "oldest active" would
        // still pick the first one; deactivate it explicitly to force the rotation).
        DSLContext db = DSL.using(DS, SQLDialect.POSTGRES);
        db.update(IAM_SERVICE_ACCOUNTS).set(IAM_SERVICE_ACCOUNTS.ACTIVE, false)
                .where(IAM_SERVICE_ACCOUNTS.APPLICATION_ID.eq(appId)).execute();
        serviceAccount(appId, "after-" + fresh(), true);

        String after = Json.write(DESIRED.build(pool, Instant.now()));
        String afterEtag = sha256Hex(after);

        assertThat(afterEtag).as("mutant: resolve the secret once and cache it for ever").isNotEqualTo(beforeEtag);
    }

    @Test
    void theSecretIsInNoLogLineAndNoToString() {
        var log = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.flowcatalyst");
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            DnsLabel pool = new DnsLabel("pool" + fresh());
            String appId = persistApplication("v7log" + fresh());
            String secret = "v7-must-never-be-logged-" + fresh();
            serviceAccount(appId, secret, true);
            Function f = createFunctionForApp("v7l" + fresh(), appId);
            FunctionVersion v = publish(f, 1, manifestWebhook(pool.value()));
            promote(f, v);

            DesiredState.Document doc = DESIRED.build(pool, Instant.now());
            DesiredState.FunctionEntry entry = doc.functions().stream()
                    .filter(e -> e.address().equals(f.address().render())).findFirst().orElseThrow();
            assertThat(entry.webhookSigningSecret()).isEqualTo(secret); // sanity: the secret really is there

            assertThat(entry.toString()).as("mutant: FunctionEntry#toString leaks the secret").doesNotContain(secret);
            assertThat(doc.toString()).as("mutant: Document#toString leaks the secret via an entry").doesNotContain(secret);
            assertThat(captured.list).as("no captured log line carries the secret")
                    .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(secret));
        } finally {
            log.detachAppender(captured);
        }
    }

    // ── V7 (function-invocation.md §6, R9): the webhook signing secret ─────

    @Test
    void webhookSigningSecretIsCarriedOnlyForAFunctionWithAWebhookEndpoint() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("ds-v7a-" + fresh());
        serviceAccount(appId, "ds-secret-" + fresh(), true);

        Function withHook = createFunctionForApp("v7hook" + fresh(), appId);
        promote(withHook, publish(withHook, 1, manifestWebhook(pool.value())));

        Function withoutHook = createFunctionForApp("v7plain" + fresh(), appId);
        promote(withoutHook, publish(withoutHook, 1, manifestForPool(pool.value(), false)));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        Map<String, DesiredState.FunctionEntry> byAddress = doc.functions().stream()
                .collect(Collectors.toMap(DesiredState.FunctionEntry::address, e -> e));

        assertThat(byAddress.get(withHook.address().render()).webhookSigningSecret())
                .as("mutant: never include the secret").isNotNull();
        assertThat(byAddress.get(withoutHook.address().render()).webhookSigningSecret())
                .as("mutant: always include, even with no webhook endpoint").isNull();
    }

    @Test
    void webhookSigningSecretIsAbsentWhenTheApplicationHasNoActiveServiceAccount() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("ds-v7b-" + fresh());
        // No service account persisted at all for this application.
        Function f = createFunctionForApp("v7nosecret" + fresh(), appId);
        promote(f, publish(f, 1, manifestWebhook(pool.value())));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        assertThat(doc.functions().getFirst().webhookSigningSecret())
                .as("mutant: fabricate a secret when none resolves").isNull();
    }

    @Test
    void rotatingTheSigningSecretChangesTheResolvedValueAndTheDocumentsETag() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("ds-v7c-" + fresh());
        Instant t0 = Instant.now().minusSeconds(120);
        String before = "ds-secret-before-" + fresh();
        serviceAccount(appId, before, true, t0);

        Function f = createFunctionForApp("v7rotate" + fresh(), appId);
        promote(f, publish(f, 1, manifestWebhook(pool.value())));

        Instant now = Instant.now();
        DesiredState.Document docBefore = DESIRED.build(pool, now);
        assertThat(docBefore.functions().getFirst().webhookSigningSecret()).isEqualTo(before);
        String bytesBefore = Json.write(docBefore);
        String etagBefore = sha256Hex(bytesBefore);

        // Rotation: a new ACTIVE account, OLDER than the current one — the
        // resolver's own "oldest active wins" rule (`OutboundCredentials`)
        // means this one, not the newer original, is what resolves now.
        String after = "ds-secret-after-" + fresh();
        serviceAccount(appId, after, true, t0.minusSeconds(60));

        DesiredState.Document docAfter = DESIRED.build(pool, now);
        assertThat(docAfter.functions().getFirst().webhookSigningSecret())
                .as("mutant: cache the resolved secret across builds").isEqualTo(after);
        String bytesAfter = Json.write(docAfter);
        String etagAfter = sha256Hex(bytesAfter);

        assertThat(etagAfter).as("mutant: the secret is not in the bytes — ETag must change on rotation")
                .isNotEqualTo(etagBefore);
    }

    @Test
    void webhookSigningSecretIsMaskedInToStringButPresentInTheJsonBody() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("ds-v7d-" + fresh());
        String secret = "super-secret-" + fresh();
        serviceAccount(appId, secret, true);
        Function f = createFunctionForApp("v7mask" + fresh(), appId);
        promote(f, publish(f, 1, manifestWebhook(pool.value())));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        DesiredState.FunctionEntry entry = doc.functions().getFirst();
        assertThat(entry.webhookSigningSecret()).isEqualTo(secret);

        assertThat(entry.toString()).as("mutant: FunctionEntry#toString leaks the secret")
                .doesNotContain(secret).contains("<redacted>");
        assertThat(doc.toString()).as("mutant: Document#toString leaks the secret via its entry list")
                .doesNotContain(secret);

        // The wire body DOES carry the real secret (the host needs it) — masking display only.
        String json = Json.write(doc);
        assertThat(json).contains(secret);
    }

    // ── function-host-listener.md §1: applicationId/clientId for reach ─────

    @Test
    void applicationIdAndClientIdAreCarriedForAClientOwnedFunction() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("ds-owner-" + fresh());
        Function f = createFunctionForApp("owned" + fresh(), appId);
        promote(f, publish(f, 1, manifestForPool(pool.value(), false)));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        DesiredState.FunctionEntry entry = doc.functions().getFirst();
        assertThat(entry.applicationId()).as("mutant: never carry applicationId").isEqualTo(appId);
        assertThat(entry.clientId()).as("mutant: never carry clientId").isEqualTo("clt_" + RUN);

        String json = Json.write(doc);
        assertThat(json).contains("\"applicationId\":\"" + appId + "\"");
        assertThat(json).contains("\"clientId\":\"clt_" + RUN + "\"");
    }

    /// `function-host-listener.md` §1: a platform-owned function still
    /// belongs to an application — `applicationId` is carried for it exactly
    /// as for a client-owned one; only `clientId` is omitted, since a
    /// platform function's reach check is "anchor, full stop" and never
    /// consults it.
    @Test
    void applicationIdIsCarriedButClientIdIsOmittedForAPlatformOwnedFunction() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        String appId = persistApplication("ds-plat-" + fresh());
        Function f = createPlatformFunction("plat" + fresh(), appId);
        promote(f, publish(f, 1, manifestForPool(pool.value(), false)));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());
        assertThat(doc.functions()).hasSize(1);
        DesiredState.FunctionEntry entry = doc.functions().getFirst();
        assertThat(entry.applicationId())
                .as("mutant: omit applicationId for a platform-owned function — it still belongs to one")
                .isEqualTo(appId);
        assertThat(entry.clientId())
                .as("mutant: carry clientId even for a platform-owned function").isNull();

        String json = Json.write(doc);
        assertThat(json).as("mutant: write null instead of the real applicationId")
                .contains("\"applicationId\":\"" + appId + "\"");
        assertThat(json).as("mutant: write null instead of omitting clientId")
                .doesNotContain("\"clientId\"");
    }

    // ── X2 (function-context.md §1, D4a) ─────────────────────────────────

    private static Manifest manifestWithConfigAndSecrets(String pool) {
        String json = """
                {"runtime":"jvm","entrypoint":"com.acme.Fn","pool":"%s",
                 "config":["FOO"],"secrets":["API_KEY"]}
                """.formatted(pool);
        return Manifest.parseStrict(Json.MAPPER.readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    /// X2: an entry's `config`/`secrets` carry ONLY the keys the manifest
    /// itself declares — an undeclared stored value never rides along —
    /// `missingSettings` names every declared key with no value yet, and
    /// setting a value moves the document's bytes (and so the ETag).
    @Test
    void x2DesiredStateRestrictsToDeclaredKeysNamesMissingSettingsAndMovesETagOnChange() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Encryption encryption = Encryption.withKey(Encryption.generateKey());
        FunctionSettingsRepository settingsWithKey =
                new FunctionSettingsRepository(DS, java.util.Optional.of(encryption));
        DesiredState desiredWithKey =
                new DesiredState(functions, versions, hosts, serviceAccounts, settingsWithKey, routes);

        Function f = createFunction("x2" + fresh());
        FunctionVersion v = publish(f, 1, manifestWithConfigAndSecrets(pool.value()));
        promote(f, v);

        // Nothing set yet: both declared keys are missing; config/secrets are empty.
        DesiredState.Document before = desiredWithKey.build(pool, Instant.now());
        var entryBefore = before.functions().stream()
                .filter(e -> e.address().equals(f.address().render())).findFirst().orElseThrow();
        assertThat(entryBefore.config()).isEmpty();
        assertThat(entryBefore.secrets()).isEmpty();
        assertThat(entryBefore.missingSettings()).as("mutant: omit missingSettings")
                .containsExactlyInAnyOrder("FOO", "API_KEY");
        String beforeEtag = sha256Hex(Json.write(before));

        // Set the declared config/secret AND an undeclared one of each — the undeclared
        // pair must never reach the document (mutant: send everything).
        uow.inTransaction(tx -> {
            settingsWithKey.replaceConfig(f.id(), Map.of("FOO", "bar", "UNDECLARED", "leak"), "prn_test", tx.dbTx());
            settingsWithKey.putSecret(f.id(), "API_KEY", new SecretValue("shh"), "prn_test", tx.dbTx());
            settingsWithKey.putSecret(f.id(), "UNDECLARED_SECRET", new SecretValue("also-leak"), "prn_test", tx.dbTx());
            return null;
        });

        DesiredState.Document after = desiredWithKey.build(pool, Instant.now());
        var entryAfter = after.functions().stream()
                .filter(e -> e.address().equals(f.address().render())).findFirst().orElseThrow();
        assertThat(entryAfter.config()).as("mutant: send everything, not restricted to declared keys")
                .containsExactly(Map.entry("FOO", "bar"));
        assertThat(entryAfter.secrets()).as("mutant: send everything, not restricted to declared keys")
                .containsExactly(Map.entry("API_KEY", "shh"));
        assertThat(entryAfter.missingSettings()).as("mutant: keep reporting a now-set key as missing").isEmpty();

        String afterEtag = sha256Hex(Json.write(after));
        assertThat(afterEtag).as("mutant: a settings change does not move the ETag").isNotEqualTo(beforeEtag);
    }

    // ── blast radius: a corrupt fn_versions.manifest must not take down reads about
    // ── OTHER functions/pools (function-registry.md §4.2, function-host-reconciler.md
    // ── §1.2 step 4) ──────────────────────────────────────────────────────────────

    /// A corrupt LIVE version's manifest peek ([Manifest#peekStoredPool]) says it does
    /// NOT belong to the pool under build — so the build must simply omit it and carry
    /// on for every OTHER function, never fail. Pins the "unaffected" half of "fails
    /// ONLY for the affected pool": a corrupt row elsewhere must not deny service to
    /// this pool.
    @Test
    void aCorruptLiveVersionInAnUnrelatedPoolIsOmittedWithoutFailingThisPoolsBuildOrOtherFunctions() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        DnsLabel otherPool = new DnsLabel("otherpool" + fresh());

        Function corruptFn = createFunction("corruptlive" + fresh());
        promote(corruptFn, insertCorruptVersion(corruptFn, 1, otherPool.value()));

        Function okFn = createFunction("okfn" + fresh());
        FunctionVersion okVersion = publish(okFn, 1, manifestForPool(pool.value(), false));
        promote(okFn, okVersion);

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());

        assertThat(doc.functions()).as("mutant: a corrupt row anywhere fails every pool's build")
                .extracting(DesiredState.FunctionEntry::address)
                .containsExactly(okFn.address().render());
    }

    /// A corrupt LIVE version's peeked pool DOES match (or can't be ruled out for) the
    /// pool under build — omitting it would leave the function's address out of
    /// `functions`, and `function-host-reconciler.md` §1.2 step 4 unloads any address
    /// that is "no longer a live entry". The smallest safe alternative: fail the WHOLE
    /// build with the house corrupt-row error (500 `CORRUPT_ROW`) so hosts treat it as a
    /// control-plane outage (§1.2 step 1: "a platform outage must never unload a
    /// function") and keep serving what they have, instead of silently dropping a
    /// running function.
    @Test
    void aCorruptLiveVersionWhosePeekedPoolMatchesFailsTheBuildWithTheHouseCorruptRowError() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function corruptFn = createFunction("corruptlive" + fresh());
        FunctionVersion corrupt = insertCorruptVersion(corruptFn, 1, pool.value());
        promote(corruptFn, corrupt);

        assertThatThrownBy(() -> DESIRED.build(pool, Instant.now()))
                .as("mutant: omit a same-pool corrupt live version instead of failing the build "
                        + "(a host would then unload the function it has loaded)")
                .isInstanceOf(CorruptFunctionVersionException.class)
                .hasMessageContaining(corrupt.id());
    }

    /// The fail-safe: when even the manifest's POOL cannot be read (the JSON is not an
    /// object at all), the corrupt live version could belong to ANY pool — so every
    /// pool's build must fail rather than let some host unload a function it is
    /// serving. Mutant: treat "pool unknown" as "not this pool".
    @Test
    void aCorruptLiveVersionWhosePoolCannotEvenBeReadFailsEveryPoolsBuild() {
        Function corruptFn = createFunction("corruptnopool" + fresh());
        FunctionVersion corrupt = insertCorruptVersion(corruptFn, 1, "whatever");
        DSL.using(DS, SQLDialect.POSTGRES).update(FN_VERSIONS)
                .set(FN_VERSIONS.MANIFEST, JSONB.jsonb("[]"))
                .where(FN_VERSIONS.ID.eq(corrupt.id())).execute();
        promote(corruptFn, corrupt);
        try {
            assertThatThrownBy(() -> DESIRED.build(new DnsLabel("anypool" + fresh()), Instant.now()))
                    .isInstanceOf(CorruptFunctionVersionException.class)
                    .hasMessageContaining(corrupt.id());
        } finally {
            // This row poisons EVERY pool's build by design — remove it so the class's
            // other tests (which share this class's own database) are unaffected.
            DSL.using(DS, SQLDialect.POSTGRES).deleteFrom(io.flowcatalyst.db.generated.Tables.FN_ALIASES)
                    .where(io.flowcatalyst.db.generated.Tables.FN_ALIASES.FUNCTION_ID.eq(corruptFn.id())).execute();
            DSL.using(DS, SQLDialect.POSTGRES).deleteFrom(FN_VERSIONS).where(FN_VERSIONS.ID.eq(corrupt.id())).execute();
        }
    }

    /// A corrupt CANDIDATE (never live) is always safe to just skip — a host never loads
    /// or routes a candidate (`function-host-reconciler.md` §1.2 step 3), so it unloads
    /// nothing when the document simply omits it. The build must succeed even when the
    /// corrupt candidate's own pool matches the one being built.
    @Test
    void aCorruptCandidateIsSkippedWithoutFailingTheBuildEvenInItsOwnPool() {
        DnsLabel pool = new DnsLabel("pool" + fresh());

        Function corruptFn = createFunction("corruptcand" + fresh());
        insertCorruptVersion(corruptFn, 1, pool.value()); // PUBLISHED, never promoted — a candidate only

        Function okFn = createFunction("okfn2" + fresh());
        promote(okFn, publish(okFn, 1, manifestForPool(pool.value(), false)));

        DesiredState.Document doc = DESIRED.build(pool, Instant.now());

        assertThat(doc.functions()).as("mutant: a corrupt candidate fails the build too")
                .extracting(DesiredState.FunctionEntry::address)
                .containsExactly(okFn.address().render());
    }

    /// One ERROR per build, naming the function and version ids as structured fields —
    /// never the manifest content.
    @Test
    void aCorruptVersionLogsOneErrorNamingTheIdsWithoutManifestContent() {
        DnsLabel pool = new DnsLabel("pool" + fresh());
        Function corruptFn = createFunction("corruptlog" + fresh());
        FunctionVersion corrupt = insertCorruptVersion(corruptFn, 1, "otherpool" + fresh());

        var log = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.flowcatalyst");
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            DESIRED.build(pool, Instant.now());

            // Filtered to THIS test's own version id: the class shares one database across
            // its methods (`newDatabase()`'s own javadoc — `build` over-reads every ACTIVE
            // function), so an EARLIER test's own corrupt row is legitimately logged again
            // on every later build() too. "Once per build" means once for THIS row in THIS
            // call, not that the whole class only ever logs one line.
            var errors = captured.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .filter(e -> e.getFormattedMessage().contains("unreadable manifest"))
                    .filter(e -> keyValue(e, "versionId").map(v -> v.equals(corrupt.id())).orElse(false))
                    .toList();
            assertThat(errors).as("mutant: log nothing, or log this row more than once per build").hasSize(1);

            var event = errors.get(0);
            assertThat(keyValue(event, "functionId")).as("mutant: don't name the offending function")
                    .contains(corruptFn.id());
            assertThat(event.getFormattedMessage()).as("mutant: leak the manifest into the log line")
                    .doesNotContain("not-a-runtime", "\"runtime\"");
        } finally {
            log.detachAppender(captured);
        }
    }

    private static java.util.Optional<Object> keyValue(ch.qos.logback.classic.spi.ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() == null) {
            return java.util.Optional.empty();
        }
        return event.getKeyValuePairs().stream().filter(kv -> kv.key.equals(key)).map(kv -> kv.value).findFirst();
    }
}
