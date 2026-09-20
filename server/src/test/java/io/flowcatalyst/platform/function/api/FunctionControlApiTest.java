package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
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
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.TriggerSync;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

/// `/control/functions/*` end to end (spec `function-api.md` §6, §8 P6, P14,
/// P15, P3's heartbeat clause). `FunctionApi` is registered in the SAME
/// harness so P15's "a `platform:function-host` principal can do NOTHING
/// under `/api/functions`" is a real cross-check, not an assumption.
@SuppressWarnings("deprecation")
class FunctionControlApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ApplicationRepository applications = new ApplicationRepository(TestPg.dataSource());
    private static final ClientRepository clients = new ClientRepository(TestPg.dataSource());
    private static final FunctionRepository functions = new FunctionRepository(TestPg.dataSource());
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(TestPg.dataSource());
    private static final FunctionHostRepository hosts = new FunctionHostRepository(TestPg.dataSource());
    private static final FunctionRouteRepository routes = new FunctionRouteRepository(TestPg.dataSource());
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(TestPg.dataSource());
    private static final TriggerObjectRepository triggerObjects = new TriggerObjectRepository(TestPg.dataSource());
    private static final SubscriptionRepository subscriptions = new SubscriptionRepository(TestPg.dataSource());
    private static final DispatchPoolRepository dispatchPools = new DispatchPoolRepository(TestPg.dataSource());
    private static final ScheduledJobRepository scheduledJobs = new ScheduledJobRepository(TestPg.dataSource());
    private static final ServiceAccountRepository serviceAccounts =
            new ServiceAccountRepository(TestPg.dataSource(), java.util.Optional.empty());
    private static final FunctionSettingsRepository settings =
            new FunctionSettingsRepository(TestPg.dataSource(), java.util.Optional.empty());
    private static final EventTypeRepository eventTypes = new EventTypeRepository(TestPg.dataSource());
    private static final EventRepository events = new EventRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static TestHttp http;

    private static final String[] NO_ROLE = {
            Authenticator.TEST_PRINCIPAL, "usr_norole_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};

    private static final String[] HOST = {
            Authenticator.TEST_PRINCIPAL, "prn_host_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:host:control"};

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before(auth);
            FunctionApi.register(routes, new FunctionApi.State(functions, applications, clients, uow, versions, hosts,
                    policies, DEFAULTS, new Signatures.Off(), TriggerSync.none(), triggerObjects, subscriptions,
                    dispatchPools, scheduledJobs, settings, java.util.Optional.empty()));
            FunctionControlApi.register(routes,
                    new FunctionControlApi.State(functions, versions, hosts, uow, serviceAccounts, settings,
                            applications, eventTypes, events, FunctionControlApiTest.routes));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static Manifest manifestForPool(String pool) {
        String json = """
                {"runtime":"jvm","entrypoint":"com.acme.Fn","pool":"%s"}
                """.formatted(pool);
        return Manifest.parseStrict(Json.MAPPER.readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    /// spec `function-public-routes.md` §2 (F10): a manifest with exactly one
    /// `public[]` entry, for the desired-state top-level `publicRoutes` tests.
    private static FunctionVersion publishWithPublic(Function f, int version, String pool, String hostname,
            String pathPrefix) {
        String json = """
                {"runtime":"jvm","entrypoint":"com.acme.Fn","pool":"%s",
                 "endpoints":[{"path":"/","auth":"none"}],
                 "public":[{"hostname":"%s","pathPrefix":"%s"}]}
                """.formatted(pool, hostname, pathPrefix);
        Manifest manifest = Manifest.parseStrict(Json.MAPPER.readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED);
        String hex = Integer.toHexString((f.id() + version + hostname + pathPrefix).hashCode()) + "0".repeat(64);
        io.flowcatalyst.platform.function.Digest digest =
                io.flowcatalyst.platform.function.Digest.parse("sha256:" + hex.substring(0, 64));
        FunctionVersion v = FunctionVersion.publish(f.id(), version, "oci://artifact", digest, null, null, null,
                manifest, "prn_publisher", Instant.now());
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
        return v;
    }

    /// The desired-state read is the fn_routes table as it stands — this
    /// test file bypasses `FunctionTriggerSync` entirely (it writes
    /// `Function`/`FunctionVersion` rows directly, spec §7's own doc), so it
    /// writes the materialised route directly too, matching what a real
    /// promote would have written.
    private static void persistRoute(Function f, String hostname, String pathPrefix) {
        FunctionRoute route =
                FunctionRoute.of(f.id(), Hostname.parse(hostname), RoutePattern.parse(pathPrefix), Instant.now());
        uow.inTransaction(tx -> {
            routes.replaceForFunction(f.id(), List.of(route), tx.dbTx());
            return null;
        });
    }

    private static Function testFunction(String tag) {
        String appCode = "fc-" + RUN + "-" + tag;
        Application a = Application.create(ApplicationType.APPLICATION, appCode, "Function Control " + tag);
        uow.inTransaction(tx -> {
            applications.persist(a, tx.dbTx());
            return null;
        });
        FunctionAddress address = FunctionAddress.of(new DnsLabel(appCode), new DnsLabel("svc"), new DnsLabel("fn"));
        Function f = Function.create(a.id(), address, FunctionOwner.ofClientId("clt_" + RUN), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static FunctionVersion publish(Function f, int version, String pool) {
        String hex = Integer.toHexString((f.id() + version).hashCode()) + "0".repeat(64);
        io.flowcatalyst.platform.function.Digest digest =
                io.flowcatalyst.platform.function.Digest.parse("sha256:" + hex.substring(0, 64));
        FunctionVersion v = FunctionVersion.publish(f.id(), version, "oci://artifact", digest, null, null, null,
                manifestForPool(pool), "prn_publisher", Instant.now());
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
        return v;
    }

    private static Function promote(Function f, FunctionVersion v) {
        Function.Promoted p = f.promote(Function.LIVE, v, "prn_promoter", Instant.now());
        uow.inTransaction(tx -> {
            functions.persist(p.function(), tx.dbTx());
            return null;
        });
        return p.function();
    }

    private static Result<Record> versionReadyEventsFor(String functionId) {
        return DB.fetch("SELECT type FROM msg_events WHERE subject = ? AND type = ?",
                "platform.function." + functionId, "platform:function:version:ready");
    }

    // ── P15: 401 / 403 / 200, and the host role can do NOTHING under /api/functions ──

    @Test
    void desiredStateWithNoCredentialIs401() {
        var r = http.get("/control/functions/desired-state?pool=default");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void heartbeatWithNoCredentialIs401() {
        var r = http.post("/control/functions/heartbeat", "{}");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void desiredStateWithoutTheHostRoleIs403() {
        var r = http.get("/control/functions/desired-state?pool=default", NO_ROLE);
        assertThat(r.statusCode()).isEqualTo(403);
    }

    @Test
    void desiredStateWithTheHostRoleIs200() {
        var r = http.get("/control/functions/desired-state?pool=default", HOST);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    @Test
    void theHostRoleCanDoNothingUnderApiFunctions() {
        var get = http.get("/api/functions", HOST);
        assertThat(get.statusCode()).as("mutant: grant the host role FUNCTION_VIEW").isEqualTo(403);

        var post = http.post("/api/functions",
                "{\"applicationCode\":\"nope\",\"serviceName\":\"svc\",\"name\":\"fn\",\"runtime\":\"jvm\"}", HOST);
        assertThat(post.statusCode()).isEqualTo(403);
    }

    // ── GET desired-state: pool validation, ETag / 304 / no-body ─────────────

    @Test
    void missingOrInvalidPoolIs400PoolInvalid() {
        var missing = http.get("/control/functions/desired-state", HOST);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json(missing).get("error").asString()).isEqualTo("POOL_INVALID");

        var invalid = http.get("/control/functions/desired-state?pool=Not_A_Label", HOST);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json(invalid).get("error").asString()).isEqualTo("POOL_INVALID");
    }

    @Test
    void ifNoneMatchWithTheCurrentETagIs304WithNoBody() {
        Function f = testFunction("etag");
        FunctionVersion v = publish(f, 1, "etagpool" + RUN);
        promote(f, v);

        var first = http.get("/control/functions/desired-state?pool=etagpool" + RUN, HOST);
        assertThat(first.statusCode()).isEqualTo(200);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(first.body()).isNotBlank();

        var second = http.get("/control/functions/desired-state?pool=etagpool" + RUN,
                concat(HOST, new String[]{"If-None-Match", etag}));
        assertThat(second.statusCode()).isEqualTo(304);
        assertThat(second.body()).as("spec §6.1: 304 has no body").isEmpty();
        assertThat(second.headers().firstValue("ETag")).as("ETag is repeated on 304").contains(etag);
    }

    @Test
    void theETagChangesAfterTheLiveVersionChanges() {
        Function f = testFunction("etagchange");
        FunctionVersion v1 = publish(f, 1, "etagpool2" + RUN);
        f = promote(f, v1);

        var before = http.get("/control/functions/desired-state?pool=etagpool2" + RUN, HOST);
        String etagBefore = before.headers().firstValue("ETag").orElseThrow();

        FunctionVersion v2 = publish(f, 2, "etagpool2" + RUN);
        promote(f, v2);

        var after = http.get("/control/functions/desired-state?pool=etagpool2" + RUN, HOST);
        String etagAfter = after.headers().firstValue("ETag").orElseThrow();
        assertThat(etagAfter).as("mutant: hash something that does not reflect the promote").isNotEqualTo(etagBefore);
        assertThat(after.body()).as("the body still returns on a genuine change").isNotBlank();
    }

    // ── F10 (spec `function-public-routes.md` §2, §6): publicRoutes sorted, per
    // pool, deterministic; ETag moves on a route change ─────────────────────

    @Test
    void desiredStatePublicRoutesAreSortedPerPoolAndMoveTheETagOnAChangeAlone() {
        String pool = "f10pool" + RUN;
        String otherPool = "f10other" + RUN;
        Function f1 = testFunction("f10a");
        Function f2 = testFunction("f10b");
        Function f3 = testFunction("f10c");
        String hostZ = "zzz-" + RUN + ".example.com";
        String hostA = "aaa-" + RUN + ".example.com";
        String hostOther = "other-" + RUN + ".example.com";

        FunctionVersion v1 = publishWithPublic(f1, 1, pool, hostZ, "/b");
        FunctionVersion v2 = publishWithPublic(f2, 1, pool, hostA, "/a");
        // f3's LIVE version is in a DIFFERENT pool — its route must never appear
        // in THIS pool's publicRoutes (spec §2: "per pool").
        FunctionVersion v3 = publishWithPublic(f3, 1, otherPool, hostOther, "/");
        promote(f1, v1);
        promote(f2, v2);
        promote(f3, v3);
        persistRoute(f1, hostZ, "/b");
        persistRoute(f2, hostA, "/a");
        persistRoute(f3, hostOther, "/");

        var r = http.get("/control/functions/desired-state?pool=" + pool, HOST);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode pr = json(r).get("publicRoutes");
        assertThat(pr).as("mutant: ignore the per-pool filter").hasSize(2);
        // Sorted (hostname, pathPrefix): "aaa..." before "zzz...".
        assertThat(pr.get(0).get("hostname").asString()).isEqualTo(hostA);
        assertThat(pr.get(0).get("pathPrefix").asString()).isEqualTo("/a");
        assertThat(pr.get(0).get("address").asString()).isEqualTo(f2.address().render());
        assertThat(pr.get(1).get("hostname").asString()).isEqualTo(hostZ);
        assertThat(pr.get(1).get("pathPrefix").asString()).isEqualTo("/b");

        String etagBefore = r.headers().firstValue("ETag").orElseThrow();

        // A route CHANGE alone (no version/alias change at all) must move the ETag.
        persistRoute(f2, hostA, "/a-changed");
        var after = http.get("/control/functions/desired-state?pool=" + pool, HOST);
        assertThat(after.statusCode()).as(after.body()).isEqualTo(200);
        String etagAfter = after.headers().firstValue("ETag").orElseThrow();
        assertThat(etagAfter).as("mutant: ETag ignores publicRoutes").isNotEqualTo(etagBefore);
        JsonNode prAfter = json(after).get("publicRoutes");
        assertThat(prAfter.get(0).get("pathPrefix").asString()).isEqualTo("/a-changed");
    }

    private static String[] concat(String[] a, String[] b) {
        var out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ── POST heartbeat: wire validation ──────────────────────────────────────

    @Test
    void hostIdMustBeOneToOneHundredAllowedCharacters() {
        var blank = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"\",\"pool\":\"default\",\"state\":\"ACTIVE\",\"loaded\":[]}", HOST);
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(json(blank).get("error").asString()).isEqualTo("HOST_ID_INVALID");

        var tooLong = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"" + "a".repeat(101) + "\",\"pool\":\"default\",\"state\":\"ACTIVE\",\"loaded\":[]}", HOST);
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(json(tooLong).get("error").asString()).isEqualTo("HOST_ID_INVALID");

        var badChar = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"bad host!\",\"pool\":\"default\",\"state\":\"ACTIVE\",\"loaded\":[]}", HOST);
        assertThat(badChar.statusCode()).isEqualTo(400);
        assertThat(json(badChar).get("error").asString()).isEqualTo("HOST_ID_INVALID");

        var ok = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"host_" + RUN + "\",\"pool\":\"default\",\"state\":\"ACTIVE\",\"loaded\":[]}", HOST);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(204);
    }

    @Test
    void poolMustBeADnsLabel() {
        var r = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"host-poolbad-" + RUN + "\",\"pool\":\"Not_Valid\",\"state\":\"ACTIVE\",\"loaded\":[]}", HOST);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("POOL_INVALID");
    }

    @Test
    void stateMustBeActiveOrDraining() {
        var r = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"host-statebad-" + RUN + "\",\"pool\":\"default\",\"state\":\"BOGUS\",\"loaded\":[]}", HOST);
        assertThat(r.statusCode()).isEqualTo(400);
    }

    @Test
    void anUnreadableLoadedEntryIs400NamingTheIndex() {
        var badAddress = http.post("/control/functions/heartbeat", """
                {"hostId":"host-loadedbad-%s","pool":"default","state":"ACTIVE",
                 "loaded":[{"address":"not.valid","version":1,"state":"LOADED"}]}""".formatted(RUN), HOST);
        assertThat(badAddress.statusCode()).isEqualTo(400);
        assertThat(json(badAddress).get("error").asString()).isEqualTo("LOADED_INVALID");
        assertThat(json(badAddress).get("message").asString()).as("names the index").contains("loaded[0]");

        var badVersion = http.post("/control/functions/heartbeat", """
                {"hostId":"host-loadedbad2-%s","pool":"default","state":"ACTIVE",
                 "loaded":[{"address":"a.b.c","version":0,"state":"LOADED"}]}""".formatted(RUN), HOST);
        assertThat(badVersion.statusCode()).isEqualTo(400);
        assertThat(json(badVersion).get("error").asString()).isEqualTo("LOADED_INVALID");

        var badState = http.post("/control/functions/heartbeat", """
                {"hostId":"host-loadedbad3-%s","pool":"default","state":"ACTIVE",
                 "loaded":[{"address":"a.b.c","version":1,"state":"WEIRD"}]}""".formatted(RUN), HOST);
        assertThat(badState.statusCode()).isEqualTo(400);
        assertThat(json(badState).get("error").asString()).isEqualTo("LOADED_INVALID");
    }

    // ── Heartbeat semantics: upsert, MarkVersionReady, FAILED, unknowns ──────

    @Test
    void heartbeatUpsertsTheHostRowNoEventNoAudit() {
        // Scoped to this test's own fresh hostId (CONVENTIONS.md §6), never a
        // table-wide count: no code path ever writes an aud_logs/msg_events row
        // naming a bare hostId as its entity, or embedding it in event data,
        // EXCEPT the `version:ready` MarkVersionReady path this test never
        // triggers (no `loaded` entries) — so any row surfacing here is exactly
        // the "route the host upsert through an Operation" mutant.
        String hostId = "host-upsert-" + RUN;

        var first = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"" + hostId + "\",\"pool\":\"upsertpool" + RUN + "\",\"state\":\"ACTIVE\",\"loaded\":[]}", HOST);
        assertThat(first.statusCode()).isEqualTo(204);
        FunctionHost afterFirst = hosts.findById(hostId).orElseThrow();
        assertThat(afterFirst.pool()).isEqualTo(new DnsLabel("upsertpool" + RUN));
        assertThat(afterFirst.state()).isEqualTo(FunctionHost.HostState.ACTIVE);

        var second = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"" + hostId + "\",\"pool\":\"upsertpool" + RUN + "\",\"state\":\"DRAINING\",\"loaded\":[]}", HOST);
        assertThat(second.statusCode()).isEqualTo(204);
        FunctionHost afterSecond = hosts.findById(hostId).orElseThrow();
        assertThat(afterSecond.state()).as("mutant: register on every beat instead of updating").isEqualTo(FunctionHost.HostState.DRAINING);
        assertThat(afterSecond.pool()).as("pool never changes after register").isEqualTo(new DnsLabel("upsertpool" + RUN));

        assertThat(DB.fetchCount(DB.selectFrom(io.flowcatalyst.db.generated.Tables.AUD_LOGS)
                        .where(io.flowcatalyst.db.generated.Tables.AUD_LOGS.ENTITY_ID.eq(hostId))))
                .as("spec §0: the host upsert alone writes no audit").isZero();
        assertThat(DB.fetchCount(DB.selectFrom(io.flowcatalyst.db.generated.Tables.MSG_EVENTS)
                        .where(org.jooq.impl.DSL.field("data::text", String.class).like("%" + hostId + "%"))))
                .as("spec §0: the host upsert alone writes no event").isZero();
    }

    @Test
    void anOkEntryForAPublishedVersionMarksItReadyOnceNotTwice() {
        Function f = testFunction("p6");
        FunctionVersion v = publish(f, 1, "p6pool" + RUN);
        String hostId = "host-p6-" + RUN;
        String body = """
                {"hostId":"%s","pool":"p6pool%s","state":"ACTIVE",
                 "loaded":[{"address":"%s","version":1,"state":"LOADED"}]}""".formatted(hostId, RUN, f.address().render());

        var first = http.post("/control/functions/heartbeat", body, HOST);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(204);
        assertThat(versions.findById(v.id()).orElseThrow().state()).isInstanceOf(FunctionVersion.VersionState.Ready.class);
        assertThat(versionReadyEventsFor(f.id())).as("exactly one version:ready the first time").hasSize(1);

        var secondSameHost = http.post("/control/functions/heartbeat", body, HOST);
        assertThat(secondSameHost.statusCode()).isEqualTo(204);
        assertThat(versionReadyEventsFor(f.id())).as("mutant: drop the Published guard -> a second identical beat re-emits")
                .hasSize(1);

        // P3's heartbeat clause: the function's own identity is unchanged.
        Function reloaded = functions.findById(f.id()).orElseThrow();
        assertThat(reloaded.address()).isEqualTo(f.address());
        assertThat(reloaded.applicationId()).isEqualTo(f.applicationId());
        assertThat(reloaded.owner()).isEqualTo(f.owner());
        assertThat(reloaded.runtime()).isEqualTo(f.runtime());
    }

    /// R-b (review fix, slice B3): a real race between two hosts' heartbeats
    /// for the SAME not-yet-ready version must never surface as a 500 — the
    /// loser's `VERSION_NOT_PUBLISHED` conflict is caught and ignored by
    /// `FunctionControlApi` — AND must produce EXACTLY ONE `version:ready`
    /// event, never two. `MarkVersionReady` is now a `TxOperation` that takes
    /// `SELECT … FOR UPDATE` on the version row
    /// (`FunctionVersionRepository#lockById`) inside its own transaction and
    /// guards `Published` on that locked, freshly-hydrated read — the loser
    /// sees the winner's committed `Ready` state, not a pre-lock snapshot
    /// both heartbeats could have read as `Published`. Run 20× with a fresh
    /// version each time: the mutant that drops `FOR UPDATE` lets both
    /// heartbeats read `Published` before either commits and both write,
    /// which does not reproduce on every interleaving — a race that only
    /// SOMETIMES loses still shows across 20 rounds.
    @Test
    void twoHostsRacingToMarkTheSameVersionReadyNeverProduceA500() throws Exception {
        for (int i = 0; i < 20; i++) {
            Function f = testFunction("p6race" + i);
            String pool = "p6racepool" + i + RUN;
            publish(f, 1, pool);
            String hostA = "host-p6race-a-" + i + "-" + RUN;
            String hostB = "host-p6race-b-" + i + "-" + RUN;
            String bodyA = """
                    {"hostId":"%s","pool":"%s","state":"ACTIVE",
                     "loaded":[{"address":"%s","version":1,"state":"LOADED"}]}""".formatted(hostA, pool, f.address().render());
            String bodyB = """
                    {"hostId":"%s","pool":"%s","state":"ACTIVE",
                     "loaded":[{"address":"%s","version":1,"state":"LOADED"}]}""".formatted(hostB, pool, f.address().render());

            var startingLine = new java.util.concurrent.CountDownLatch(2);
            var go = new java.util.concurrent.CountDownLatch(1);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var beatA = executor.submit(() -> {
                    startingLine.countDown();
                    go.await();
                    return http.post("/control/functions/heartbeat", bodyA, HOST);
                });
                var beatB = executor.submit(() -> {
                    startingLine.countDown();
                    go.await();
                    return http.post("/control/functions/heartbeat", bodyB, HOST);
                });
                assertThat(startingLine.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                go.countDown();

                assertThat(beatA.get(20, java.util.concurrent.TimeUnit.SECONDS).statusCode())
                        .as("iteration " + i + ": mutant: catch something other than exactly VERSION_NOT_PUBLISHED").isEqualTo(204);
                assertThat(beatB.get(20, java.util.concurrent.TimeUnit.SECONDS).statusCode())
                        .as("iteration " + i + ": mutant: catch something other than exactly VERSION_NOT_PUBLISHED").isEqualTo(204);
            } finally {
                executor.shutdown();
            }

            assertThat(versions.findById(versions.findByFunctionAndVersion(f.id(), 1).orElseThrow().id()).orElseThrow().state())
                    .isInstanceOf(FunctionVersion.VersionState.Ready.class);
            assertThat(versionReadyEventsFor(f.id()))
                    .as("iteration " + i + ": mutant: drop FOR UPDATE, or guard on the pre-lock read — exactly one version:ready, never two")
                    .hasSize(1);
        }
    }

    @Test
    void aFailedEntryNeverChangesTheVersionsStateAndIsVisibleAsAnErrorOnTheHost() {
        Function f = testFunction("failed");
        FunctionVersion v = publish(f, 1, "failedpool" + RUN);
        String hostId = "host-failed-" + RUN;
        String longError = "x".repeat(1500);
        String body = """
                {"hostId":"%s","pool":"failedpool%s","state":"ACTIVE",
                 "loaded":[{"address":"%s","version":1,"state":"FAILED","error":"%s"}]}"""
                .formatted(hostId, RUN, f.address().render(), longError);

        var r = http.post("/control/functions/heartbeat", body, HOST);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(204);

        assertThat(versions.findById(v.id()).orElseThrow().state())
                .as("mutant: a FAILED report changes the version's state").isInstanceOf(FunctionVersion.VersionState.Published.class);
        assertThat(versionReadyEventsFor(f.id())).as("a FAILED entry is never ok(), never marked ready").isEmpty();

        FunctionHost host = hosts.findById(hostId).orElseThrow();
        assertThat(host.loaded()).hasSize(1);
        var loaded = host.loaded().getFirst();
        assertThat(loaded.state()).isInstanceOf(FunctionHost.LoadState.Failed.class);
        String storedError = ((FunctionHost.LoadState.Failed) loaded.state()).error();
        assertThat(storedError).as("spec §6.2: error truncated to 1000 chars").hasSize(1000);
    }

    @Test
    void anEntryNamingAnUnknownAddressOrVersionIsIgnored() {
        Function f = testFunction("unknown");
        publish(f, 1, "unkpool" + RUN);
        String hostId = "host-unknown-" + RUN;
        String body = """
                {"hostId":"%s","pool":"unkpool%s","state":"ACTIVE",
                 "loaded":[{"address":"nosuch.svc.fn","version":1,"state":"LOADED"},
                           {"address":"%s","version":99,"state":"LOADED"}]}"""
                .formatted(hostId, RUN, f.address().render());

        var r = http.post("/control/functions/heartbeat", body, HOST);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(204);
        assertThat(versionReadyEventsFor(f.id())).isEmpty();
    }

    // ── POST /control/functions/events (spec §3, X9) ─────────────────────────

    private static void registerHost(String hostId, String pool) {
        var r = http.post("/control/functions/heartbeat",
                "{\"hostId\":\"" + hostId + "\",\"pool\":\"" + pool + "\",\"state\":\"ACTIVE\",\"loaded\":[]}", HOST);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(204);
    }

    private static EventType eventType(String code) {
        EventType et = EventType.create(code, "X9 " + code);
        uow.inTransaction(tx -> {
            eventTypes.persist(et, tx.dbTx());
            return null;
        });
        return et;
    }

    private static void archive(EventType et) {
        EventType archived = et.archive();
        uow.inTransaction(tx -> {
            eventTypes.persist(archived, tx.dbTx());
            return null;
        });
    }

    private static String eventJson(String type, String dedupId, String dataJson) {
        return "{\"type\":\"" + type + "\",\"dedupId\":\"" + dedupId + "\",\"data\":" + dataJson + "}";
    }

    private static String emitBody(String hostId, String address, int version, String eventsArrayJson) {
        return "{\"hostId\":\"" + hostId + "\",\"address\":\"" + address + "\",\"version\":" + version
                + ",\"events\":" + eventsArrayJson + "}";
    }

    private static org.jooq.Record eventRowByDedup(String dedupId) {
        return DB.selectFrom(MSG_EVENTS).where(MSG_EVENTS.DEDUPLICATION_ID.eq(dedupId)).fetchOne();
    }

    @Test
    void emitEventsWithNoCredentialIs401() {
        var r = http.post("/control/functions/events", "{}");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void emitEventsWithoutTheHostRoleIs403() {
        var r = http.post("/control/functions/events", "{}", NO_ROLE);
        assertThat(r.statusCode()).isEqualTo(403);
    }

    @Test
    void anUnknownOrStaleHostIsHostUnknown409() {
        Function f = testFunction("emithostunknown");
        FunctionVersion v = publish(f, 1, "emithupool" + RUN);
        promote(f, v);

        var rUnknownHost = http.post("/control/functions/events",
                emitBody("no-such-host-" + RUN, f.address().render(), 1,
                        "[" + eventJson("whatever:sub:agg:evt", "dd-hostunknown-1-" + RUN, "{}") + "]"), HOST);
        assertThat(rUnknownHost.statusCode()).isEqualTo(409);
        assertThat(json(rUnknownHost).get("error").asString()).isEqualTo("HOST_UNKNOWN");

        // Absence, not just a nonsense id: a REAL host row whose heartbeat is outside
        // LIVE_WINDOW must fail exactly the same way (mutant: check only that the row exists).
        String staleHostId = "host-stale-" + RUN;
        Instant longAgo = Instant.now().minus(FunctionHost.LIVE_WINDOW).minusSeconds(30);
        FunctionHost stale = FunctionHost.register(staleHostId, new DnsLabel("emithupool" + RUN), longAgo);
        uow.inTransaction(tx -> {
            hosts.persist(stale, tx.dbTx());
            return null;
        });
        var rStaleHost = http.post("/control/functions/events",
                emitBody(staleHostId, f.address().render(), 1,
                        "[" + eventJson("whatever:sub:agg:evt", "dd-hostunknown-2-" + RUN, "{}") + "]"), HOST);
        assertThat(rStaleHost.statusCode()).as("mutant: skip the LIVE_WINDOW check on hostId").isEqualTo(409);
        assertThat(json(rStaleHost).get("error").asString()).isEqualTo("HOST_UNKNOWN");
    }

    @Test
    void aFunctionOrVersionThisHostDoesNotServeIsFunctionNotServedByHost409() {
        String hostId = "host-fnsb-" + RUN;
        String pool = "fnsbpool" + RUN;
        registerHost(hostId, pool);

        // unknown address entirely
        var rUnknownAddr = http.post("/control/functions/events",
                emitBody(hostId, "nosuch." + RUN + ".fn", 1,
                        "[" + eventJson("whatever:sub:agg:evt", "dd-fnsb-1-" + RUN, "{}") + "]"), HOST);
        assertThat(rUnknownAddr.statusCode()).isEqualTo(409);
        assertThat(json(rUnknownAddr).get("error").asString()).isEqualTo("FUNCTION_NOT_SERVED_BY_HOST");

        // known function, but no version was ever published
        Function f = testFunction("fnsb");
        var rNoVersion = http.post("/control/functions/events",
                emitBody(hostId, f.address().render(), 1,
                        "[" + eventJson("whatever:sub:agg:evt", "dd-fnsb-2-" + RUN, "{}") + "]"), HOST);
        assertThat(rNoVersion.statusCode()).as("mutant: treat a never-published version as served").isEqualTo(409);
        assertThat(json(rNoVersion).get("error").asString()).isEqualTo("FUNCTION_NOT_SERVED_BY_HOST");

        // published + promoted, but in a DIFFERENT pool than this host's own
        FunctionVersion v = publish(f, 1, "otherpool" + RUN);
        f = promote(f, v);
        var rWrongPool = http.post("/control/functions/events",
                emitBody(hostId, f.address().render(), 1,
                        "[" + eventJson("whatever:sub:agg:evt", "dd-fnsb-3-" + RUN, "{}") + "]"), HOST);
        assertThat(rWrongPool.statusCode()).as("mutant: ignore pool when deciding what a host serves").isEqualTo(409);
        assertThat(json(rWrongPool).get("error").asString()).isEqualTo("FUNCTION_NOT_SERVED_BY_HOST");

        // disabled function, live version IS in this host's pool
        Function f2 = testFunction("fnsbdisabled");
        FunctionVersion v2 = publish(f2, 1, pool);
        f2 = promote(f2, v2);
        Function disabled = f2.disable(Instant.now());
        uow.inTransaction(tx -> {
            functions.persist(disabled, tx.dbTx());
            return null;
        });
        var rDisabled = http.post("/control/functions/events",
                emitBody(hostId, f2.address().render(), 1,
                        "[" + eventJson("whatever:sub:agg:evt", "dd-fnsb-4-" + RUN, "{}") + "]"), HOST);
        assertThat(rDisabled.statusCode()).as("mutant: skip the ACTIVE check").isEqualTo(409);
        assertThat(json(rDisabled).get("error").asString()).isEqualTo("FUNCTION_NOT_SERVED_BY_HOST");

        // the positive case: live version, matching pool -> checks 1+2 both pass (this
        // request still fails later, at ownership, since no event type is registered for
        // it — but NOT with FUNCTION_NOT_SERVED_BY_HOST, proving checks 1+2 passed).
        Function f3 = testFunction("fnsbok");
        FunctionVersion v3 = publish(f3, 1, pool);
        f3 = promote(f3, v3);
        var rServed = http.post("/control/functions/events",
                emitBody(hostId, f3.address().render(), 1,
                        "[" + eventJson("whatever:sub:agg:evt", "dd-fnsb-5-" + RUN, "{}") + "]"), HOST);
        assertThat(rServed.statusCode()).as("must clear checks 1+2 once the pool matches the live version").isNotEqualTo(409);
    }

    @Test
    void batchValidationRequiresOneToOneHundredUniqueDedupIdsAndBoundedObjectData() {
        String hostId = "host-batch-" + RUN;
        String pool = "batchpool" + RUN;
        registerHost(hostId, pool);
        Function f = testFunction("batch");
        FunctionVersion v = publish(f, 1, pool);
        f = promote(f, v);
        String address = f.address().render();

        var rEmpty = http.post("/control/functions/events", emitBody(hostId, address, 1, "[]"), HOST);
        assertThat(rEmpty.statusCode()).as(rEmpty.body()).isEqualTo(400);
        assertThat(json(rEmpty).get("error").asString()).isEqualTo("BATCH_SIZE_INVALID");

        var rMissingDedup = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[{\"type\":\"whatever:sub:agg:evt\",\"data\":{}}]"), HOST);
        assertThat(rMissingDedup.statusCode()).as(rMissingDedup.body()).isEqualTo(400);
        assertThat(json(rMissingDedup).get("error").asString()).isEqualTo("DEDUP_ID_REQUIRED");

        var rDupDedup = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[" + eventJson("whatever:sub:agg:evt", "same-dd-" + RUN, "{}") + ","
                        + eventJson("whatever:sub:agg:evt2", "same-dd-" + RUN, "{}") + "]"), HOST);
        assertThat(rDupDedup.statusCode()).as(rDupDedup.body()).isEqualTo(400);
        assertThat(json(rDupDedup).get("error").asString()).isEqualTo("DEDUP_ID_DUPLICATE");

        String bigValue = "x".repeat(300_000);
        var rTooBig = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[{\"type\":\"whatever:sub:agg:evt\",\"dedupId\":\"dd-big-" + RUN + "\",\"data\":{\"v\":\""
                        + bigValue + "\"}}]"), HOST);
        assertThat(rTooBig.statusCode()).as(rTooBig.body()).isEqualTo(400);
        assertThat(json(rTooBig).get("error").asString()).isEqualTo("EVENT_DATA_TOO_LARGE");
    }

    @Test
    void ownershipGatesEmitUnknownAndArchivedAreBoth403AndTheBatchIsAllOrNothing() {
        String hostId = "host-own-" + RUN;
        String pool = "ownpool" + RUN;
        registerHost(hostId, pool);
        Function f = testFunction("own");
        FunctionVersion v = publish(f, 1, pool);
        f = promote(f, v);
        String address = f.address().render();
        String appCode = "fc-" + RUN + "-own"; // testFunction's own naming (see #testFunction)

        String ownedType = appCode + ":orders:order:created";
        eventType(ownedType);

        String otherAppCode = "fc-" + RUN + "-notmine";
        String notOwnedType = otherAppCode + ":orders:order:created";
        eventType(notOwnedType);

        String archivedTypeCode = appCode + ":orders:order:archived";
        archive(eventType(archivedTypeCode));

        // unknown type -> 403, not 404
        var rUnknown = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[" + eventJson(appCode + ":no:such:type", "dd-own-unknown-" + RUN, "{}") + "]"), HOST);
        assertThat(rUnknown.statusCode()).as("mutant: unknown type -> 404 instead of 403").isEqualTo(403);
        assertThat(json(rUnknown).get("error").asString()).isEqualTo("EVENT_TYPE_NOT_OWNED");

        // archived type (owned application, but archived) -> 403
        var rArchived = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[" + eventJson(archivedTypeCode, "dd-own-archived-" + RUN, "{}") + "]"), HOST);
        assertThat(rArchived.statusCode()).as("mutant: allow an archived type").isEqualTo(403);
        assertThat(json(rArchived).get("error").asString()).isEqualTo("EVENT_TYPE_NOT_OWNED");

        // type owned by ANOTHER application -> 403
        var rOther = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[" + eventJson(notOwnedType, "dd-own-other-" + RUN, "{}") + "]"), HOST);
        assertThat(rOther.statusCode()).isEqualTo(403);
        assertThat(json(rOther).get("error").asString()).isEqualTo("EVENT_TYPE_NOT_OWNED");

        // ALL-OR-NOTHING: a batch of [owned, not-owned] writes NO row for the owned one either
        String goodDedup = "dd-allornothing-good-" + RUN;
        var rMixed = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[" + eventJson(ownedType, goodDedup, "{}") + ","
                        + eventJson(notOwnedType, "dd-allornothing-bad-" + RUN, "{}") + "]"), HOST);
        assertThat(rMixed.statusCode()).isEqualTo(403);
        assertThat(eventRowByDedup(goodDedup))
                .as("mutant: validate every event but write the passing ones anyway").isNull();

        // success: source, clientId
        String successDedup = "dd-success-" + RUN;
        var rOk = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[" + eventJson(ownedType, successDedup, "{\"k\":1}") + "]"), HOST);
        assertThat(rOk.statusCode()).as(rOk.body()).isEqualTo(201);
        assertThat(json(rOk).get("results").get(0).get("status").asString()).isEqualTo("SUCCESS");
        var row = eventRowByDedup(successDedup);
        assertThat(row).isNotNull();
        assertThat(row.get(MSG_EVENTS.SOURCE)).as("mutant: wrong/omitted source").isEqualTo("function:" + address);
        assertThat(row.get(MSG_EVENTS.CLIENT_ID)).as("mutant: omit/misresolve clientId for a client-owned function")
                .isEqualTo(f.owner().clientIdOrNull());

        // Idempotent repeat: same as the ingest routes (IngestApiTest#aRepeatedDeduplicationIdStillReportsSuccessOnTheWire)
        // — a repeated dedupId is never an error, reported SUCCESS exactly as a fresh one is.
        // The composite (deduplication_id, created_at) conflict target only actually drops the
        // duplicate row when both share the same instant (proven for the shared writer path by
        // IngestApiTest#aRepeatedDeduplicationIdWritesExactlyOneRow, with an explicit shared
        // timestamp) — two real, wall-clock-separated HTTP calls are not guaranteed to land on
        // the same microsecond, so this asserts what the wire path actually guarantees: no error.
        var rRepeat = http.post("/control/functions/events", emitBody(hostId, address, 1,
                "[" + eventJson(ownedType, successDedup, "{\"k\":1}") + "]"), HOST);
        assertThat(rRepeat.statusCode()).as(rRepeat.body()).isEqualTo(201);
        assertThat(json(rRepeat).get("results").get(0).get("status").asString())
                .as("mutant: a repeated dedupId becomes an error instead of the ingest path's own idempotent SUCCESS")
                .isEqualTo("SUCCESS");
    }

    @Test
    void aPlatformOwnedFunctionsEmitCarriesNoClientId() {
        String hostId = "host-plat-" + RUN;
        String pool = "platpool" + RUN;
        registerHost(hostId, pool);

        String appCode = "fc-" + RUN + "-plat";
        Application a = Application.create(ApplicationType.APPLICATION, appCode, "Platform Owned " + RUN);
        uow.inTransaction(tx -> {
            applications.persist(a, tx.dbTx());
            return null;
        });
        FunctionAddress address = FunctionAddress.of(new DnsLabel(appCode), new DnsLabel("svc"), new DnsLabel("fn"));
        Function f = Function.create(a.id(), address, new FunctionOwner.Platform(), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        FunctionVersion v = publish(f, 1, pool);
        promote(f, v);

        String type = appCode + ":orders:order:created";
        eventType(type);

        String dedup = "dd-platform-" + RUN;
        var r = http.post("/control/functions/events", emitBody(hostId, address.render(), 1,
                "[" + eventJson(type, dedup, "{}") + "]"), HOST);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        var row = eventRowByDedup(dedup);
        assertThat(row).isNotNull();
        assertThat(row.get(MSG_EVENTS.CLIENT_ID)).as("mutant: a platform-owned function's emit carries a clientId").isNull();
    }
}
