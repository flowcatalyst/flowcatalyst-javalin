package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionHost;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
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
import java.util.Locale;
import java.util.UUID;

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
            FunctionApi.register(routes, new FunctionApi.State(functions, applications, clients, uow, versions, hosts));
            FunctionControlApi.register(routes, new FunctionControlApi.State(functions, versions, hosts, uow));
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
}
