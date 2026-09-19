package io.flowcatalyst.platform.function.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `/api/functions*` end to end (spec `function-api.md` §4.1, §4.2, §8):
/// P16 (the dotted `{address}` routes; a two-part address is 400 not 404),
/// P2 (out of reach is 404 on a read AND absent from the list, for all
/// three clauses — `AccessTest` covers the predicate itself, this covers
/// the HTTP wiring), P3 (`PUT` with an immutable field is 400), and the
/// coarse permission gate. `FunctionOperationsTest` covers validation and
/// the event/audit envelope.
@SuppressWarnings("deprecation")
class FunctionApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ApplicationRepository applications = new ApplicationRepository(TestPg.dataSource());
    private static final ClientRepository clients = new ClientRepository(TestPg.dataSource());
    private static final FunctionRepository functions = new FunctionRepository(TestPg.dataSource());
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(TestPg.dataSource());
    private static final FunctionHostRepository hosts = new FunctionHostRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, "usr_anchor_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            FunctionApi.register(routes, new FunctionApi.State(functions, applications, clients, uow, versions, hosts));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String testApplication(String tag, String code) {
        Application a = Application.create(ApplicationType.APPLICATION, code, "Function Api " + tag);
        uow.inTransaction(tx -> {
            applications.persist(a, tx.dbTx());
            return null;
        });
        return a.id();
    }

    private static String testClient(String tag) {
        Client c = Client.create("Function Api " + tag, ClientIdentifier.parse("fna-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clients.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static String[] view(String... extra) {
        var base = new String[]{Authenticator.TEST_PRINCIPAL, "usr_view_" + RUN, Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};
        return concat(base, extra);
    }

    private static String[] manage(String... extra) {
        var base = new String[]{Authenticator.TEST_PRINCIPAL, "usr_manage_" + RUN, Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_PERMISSIONS, "platform:function:function:view,platform:function:function:manage"};
        return concat(base, extra);
    }

    private static String[] concat(String[] a, String[] b) {
        var out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static JsonNode create(String applicationCode, String serviceName, String name, String clientId) {
        String body = "{\"applicationCode\":\"" + applicationCode + "\",\"serviceName\":\"" + serviceName
                + "\",\"name\":\"" + name + "\",\"runtime\":\"jvm\""
                + (clientId == null ? "" : ",\"clientId\":\"" + clientId + "\"") + "}";
        var r = http.post("/api/functions", body, ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return json(r);
    }

    // ── Create / Get / List round trip ───────────────────────────────────────

    @Test
    void createThenGetThenListRoundTrips() {
        String appId = testApplication("roundtrip", "roundtrip-" + RUN);
        var created = create("roundtrip-" + RUN, "svc", "fn", null);
        assertThat(created.get("address").asText()).isEqualTo("roundtrip-" + RUN + ".svc.fn");
        assertThat(created.get("applicationId").asText()).isEqualTo(appId);
        assertThat(created.get("runtime").asText()).isEqualTo("jvm");
        assertThat(created.has("clientId")).as("absent for a platform-owned function").isFalse();

        var got = json(http.get("/api/functions/roundtrip-" + RUN + ".svc.fn", ANCHOR));
        assertThat(got.get("id").asText()).isEqualTo(created.get("id").asText());

        var list = json(http.get("/api/functions?address=roundtrip-" + RUN + ".svc.*", ANCHOR));
        assertThat(list.get("data")).hasSize(1);
        assertThat(list.get("data").get(0).get("id").asText()).isEqualTo(created.get("id").asText());
    }

    @Test
    void getOfAnUnknownAddressIs404() {
        var r = http.get("/api/functions/nosuch-" + RUN + ".svc.fn", ANCHOR);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Function_NOT_FOUND");
    }

    // ── P16: dotted {address} routing ────────────────────────────────────────

    @Test
    void addressRoutesWithDotsIntact() {
        testApplication("p16", "p16-" + RUN);
        create("p16-" + RUN, "svc", "fn", null);
        var r = http.get("/api/functions/p16-" + RUN + ".svc.fn", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    @Test
    void aTwoPartAddressIs400NotFound404() {
        var r = http.get("/api/functions/only-two-parts", ANCHOR);
        assertThat(r.statusCode()).as("wrong segment count must be a validation 400, not a 404").isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("ADDRESS_INVALID");
    }

    // ── P2: out of reach is 404, read side ───────────────────────────────────

    @Test
    void clientScopedPrincipalCannotReadAnotherClientsFunction() {
        String owner = testClient("p2-1");
        testApplication("p2-1", "p2client-" + RUN);
        create("p2client-" + RUN, "svc", "fn", owner);

        var r = http.get("/api/functions/p2client-" + RUN + ".svc.fn",
                view(Authenticator.TEST_CLIENTS, "clt_someoneelse"));
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Function_NOT_FOUND");

        var list = json(http.get("/api/functions?address=p2client-" + RUN + ".svc.*",
                view(Authenticator.TEST_CLIENTS, "clt_someoneelse")));
        assertThat(list.get("data")).as("absent from the list too").isEmpty();
    }

    @Test
    void nonAnchorCannotReadAPlatformOwnedFunction() {
        testApplication("p2-2", "p2platform-" + RUN);
        create("p2platform-" + RUN, "svc", "fn", null);

        var r = http.get("/api/functions/p2platform-" + RUN + ".svc.fn", view());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Function_NOT_FOUND");
    }

    @Test
    void applicationScopedPrincipalCannotReadAnotherApplicationsFunction() {
        String owner = testClient("p2-3");
        String appId = testApplication("p2-3", "p2app-" + RUN);
        create("p2app-" + RUN, "svc", "fn", owner);

        // Reachable client, but scoped to a DIFFERENT (nonexistent) application id —
        // X-FC-Test-Applications non-empty with no X-FC-Test-All-Applications ⇒ allApplications=false.
        var r = http.get("/api/functions/p2app-" + RUN + ".svc.fn",
                view(Authenticator.TEST_CLIENTS, owner, Authenticator.TEST_APPLICATIONS, "app_not_" + appId));
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Function_NOT_FOUND");

        // The SAME principal, scoped to the RIGHT application, reaches it.
        var ok = http.get("/api/functions/p2app-" + RUN + ".svc.fn",
                view(Authenticator.TEST_CLIENTS, owner, Authenticator.TEST_APPLICATIONS, appId));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
    }

    // ── P2: out of reach is 404 on a read AND absent from the list — every reach clause ──

    private static java.util.List<String> idsOf(JsonNode list) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        list.get("data").forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    /// A client-scoped principal's list: its own client's function is
    /// present; another client's and a platform-owned function are absent —
    /// from both `data` and `total`. Repository-level coverage of the same
    /// clauses lives in `FunctionRepositoryTest`; this pins the HTTP wiring
    /// (query params → [io.flowcatalyst.platform.function.FunctionRepository.PageFilter]).
    @Test
    void listExcludesAnotherClientsAndAPlatformOwnedFunctionForAClientScopedPrincipal() {
        String appCode = "p2list-" + RUN + "-a";
        testApplication("p2list-a", appCode);
        String own = testClient("p2list-own");
        String other = testClient("p2list-other");
        var ownFn = create(appCode, "svc1", "reach", own);
        var otherClientFn = create(appCode, "svc2", "other-client", other);
        var platformFn = create(appCode, "svc3", "platform", null);

        var list = json(http.get("/api/functions?address=" + appCode + ".*",
                view(Authenticator.TEST_CLIENTS, own)));
        assertThat(idsOf(list)).as("sees its own client's function").contains(ownFn.get("id").asText());
        assertThat(idsOf(list)).as("not another client's function").doesNotContain(otherClientFn.get("id").asText());
        assertThat(idsOf(list)).as("not a platform-owned function").doesNotContain(platformFn.get("id").asText());
        assertThat(list.get("total").asLong()).as("total excludes both hidden rows").isEqualTo(1);
    }

    /// An anchor's list under the SAME address pattern reaches every owner —
    /// the counterpart to the client-scoped case above, both seeded together
    /// so a mutant that widens `Visibility.Tenants` cannot be masked by one
    /// that narrows `Visibility.Everything`, or vice versa.
    @Test
    void listIncludesEveryOwnerForAnAnchor() {
        String appCode = "p2list-" + RUN + "-c";
        testApplication("p2list-c", appCode);
        String clientA = testClient("p2list-anchor-a");
        String clientB = testClient("p2list-anchor-b");
        var fnA = create(appCode, "svc1", "a", clientA);
        var fnB = create(appCode, "svc2", "b", clientB);
        var fnPlatform = create(appCode, "svc3", "platform", null);

        var list = json(http.get("/api/functions?address=" + appCode + ".*", ANCHOR));
        assertThat(idsOf(list)).as("anchor sees every owner").containsExactlyInAnyOrder(
                fnA.get("id").asText(), fnB.get("id").asText(), fnPlatform.get("id").asText());
        assertThat(list.get("total").asLong()).isEqualTo(3);
    }

    /// An application-scoped principal (an explicit `X-FC-Test-Applications`
    /// header) does not reach another application's function even within its
    /// own reachable client — scoped by `clientId=` (a fresh, unique client
    /// id) rather than an address pattern, since the two functions live
    /// under two different application codes.
    @Test
    void listExcludesAnotherApplicationsFunctionForAnApplicationScopedPrincipalEvenWithinItsOwnClient() {
        String client = testClient("p2list-app");
        String ownAppCode = "p2list-" + RUN + "-own";
        String otherAppCode = "p2list-" + RUN + "-other";
        String ownAppId = testApplication("p2list-app-own", ownAppCode);
        testApplication("p2list-app-other", otherAppCode);
        var ownAppFn = create(ownAppCode, "svc", "fn", client);
        var otherAppFn = create(otherAppCode, "svc", "fn", client);

        var list = json(http.get("/api/functions?clientId=" + client,
                view(Authenticator.TEST_CLIENTS, client, Authenticator.TEST_APPLICATIONS, ownAppId)));
        assertThat(idsOf(list)).as("sees its own application's function").contains(ownAppFn.get("id").asText());
        assertThat(idsOf(list)).as("not another application's function, same client")
                .doesNotContain(otherAppFn.get("id").asText());
        assertThat(list.get("total").asLong()).as("total excludes the other application's row").isEqualTo(1);
    }

    // ── P3: PUT with an immutable field is 400 ───────────────────────────────

    @Test
    void putWithAnImmutableFieldIs400() {
        testApplication("p3", "p3-" + RUN);
        create("p3-" + RUN, "svc", "fn", null);

        var r = http.put("/api/functions/p3-" + RUN + ".svc.fn", "{\"name\":\"renamed\"}", ANCHOR);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("FUNCTION_IMMUTABLE_FIELD");

        // The legitimate fields still work.
        var ok = http.put("/api/functions/p3-" + RUN + ".svc.fn", "{\"description\":\"new\"}", ANCHOR);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(204);
    }

    // ── Permission gate ───────────────────────────────────────────────────────

    @Test
    void writesRequireManageReadsOnlyRequireView() {
        testApplication("perm", "perm-" + RUN);
        // Anchor-scoped so the permission gate, not reach, is what is under test.
        String[] anchorViewOnly = {Authenticator.TEST_PRINCIPAL, "usr_anchorview_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};
        var viewOnlyCreate = http.post("/api/functions",
                "{\"applicationCode\":\"perm-" + RUN + "\",\"serviceName\":\"svc\",\"name\":\"fn\",\"runtime\":\"jvm\"}",
                anchorViewOnly);
        assertThat(viewOnlyCreate.statusCode()).isEqualTo(403);

        create("perm-" + RUN, "svc", "fn", null);
        var viewOnlyGet = http.get("/api/functions/perm-" + RUN + ".svc.fn", anchorViewOnly);
        assertThat(viewOnlyGet.statusCode()).as(viewOnlyGet.body()).isEqualTo(200);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheFunction() {
        testApplication("delete", "delete-" + RUN);
        create("delete-" + RUN, "svc", "fn", null);

        var del = http.delete("/api/functions/delete-" + RUN + ".svc.fn", ANCHOR);
        assertThat(del.statusCode()).isEqualTo(204);

        var get = http.get("/api/functions/delete-" + RUN + ".svc.fn", ANCHOR);
        assertThat(get.statusCode()).isEqualTo(404);
    }

    // ── Status / pools (spec §6.3) ───────────────────────────────────────────

    private static final io.flowcatalyst.platform.function.FunctionLimits STATUS_DEFAULTS =
            io.flowcatalyst.platform.function.FunctionLimits.defaults();
    private static final io.flowcatalyst.platform.function.ClientCeilings STATUS_UNRESTRICTED =
            io.flowcatalyst.platform.function.ClientCeilings.of(STATUS_DEFAULTS);

    /// A CLIENT-scoped principal with `FUNCTION_VIEW` — reaches its own
    /// function under the ordinary `/api/functions/{address}` rule, but
    /// status/pools require anchor (this slice's instruction).
    private static String[] clientViewer(String clientId) {
        return new String[]{Authenticator.TEST_PRINCIPAL, "usr_clientview_" + RUN, Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId, Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};
    }

    private static final String[] ANCHOR_VIEW_ONLY = {
            Authenticator.TEST_PRINCIPAL, "usr_anchorviewonly_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:function:view"};

    private static io.flowcatalyst.platform.function.Manifest statusManifest(String pool) {
        String json = """
                {"runtime":"jvm","entrypoint":"com.acme.Fn","pool":"%s"}
                """.formatted(pool);
        return io.flowcatalyst.platform.function.Manifest.parseStrict(Json.MAPPER.readTree(json),
                io.flowcatalyst.platform.function.Runtime.JVM, STATUS_DEFAULTS, STATUS_UNRESTRICTED);
    }

    private static io.flowcatalyst.platform.function.FunctionVersion publishVersion(
            io.flowcatalyst.platform.function.Function f, int version, String pool) {
        String hex = Integer.toHexString((f.id() + version).hashCode()) + "0".repeat(64);
        var digest = io.flowcatalyst.platform.function.Digest.parse("sha256:" + hex.substring(0, 64));
        var v = io.flowcatalyst.platform.function.FunctionVersion.publish(f.id(), version, "oci://artifact", digest,
                null, null, null, statusManifest(pool), "prn_publisher", java.time.Instant.now());
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
        return v;
    }

    private static io.flowcatalyst.platform.function.Function promoteVersion(
            io.flowcatalyst.platform.function.Function f, io.flowcatalyst.platform.function.FunctionVersion v) {
        var promoted = f.promote(io.flowcatalyst.platform.function.Function.LIVE, v, "prn_promoter", java.time.Instant.now());
        uow.inTransaction(tx -> {
            functions.persist(promoted.function(), tx.dbTx());
            return null;
        });
        return promoted.function();
    }

    @Test
    void statusRequiresAnchorEvenForTheOwningClient() {
        String clientId = testClient("status-anchor");
        testApplication("status-anchor", "statusanchor-" + RUN);
        create("statusanchor-" + RUN, "svc", "fn", clientId);

        var r = http.get("/api/functions/statusanchor-" + RUN + ".svc.fn/status", clientViewer(clientId));
        assertThat(r.statusCode()).as("mutant: apply the ordinary reach rule instead of requireAnchor").isEqualTo(403);
    }

    @Test
    void statusRequiresTheViewPermission() {
        testApplication("status-perm", "statusperm-" + RUN);
        create("statusperm-" + RUN, "svc", "fn", null);
        String[] anchorNoPermission = {Authenticator.TEST_PRINCIPAL, "usr_anp_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, ""};
        var r = http.get("/api/functions/statusperm-" + RUN + ".svc.fn/status", anchorNoPermission);
        assertThat(r.statusCode()).isEqualTo(403);
    }

    @Test
    void statusReportsLiveVersionsAndHostsReportingThisAddressOnly() {
        testApplication("status", "status-" + RUN);
        var created = create("status-" + RUN, "svc", "fn", null);
        var other = create("status-" + RUN, "svc", "other", null);
        io.flowcatalyst.platform.function.Function f = functions.findById(created.get("id").asString()).orElseThrow();
        io.flowcatalyst.platform.function.Function otherFn = functions.findById(other.get("id").asString()).orElseThrow();

        var v1 = publishVersion(f, 1, "statuspool" + RUN);
        f = promoteVersion(f, v1);
        var v2 = publishVersion(f, 2, "statuspool" + RUN); // a candidate, still PUBLISHED

        // One host reports THIS function's address; a second host reports only the OTHER function.
        var hostSame = io.flowcatalyst.platform.function.FunctionHost.register("host-status-a-" + RUN,
                new io.flowcatalyst.platform.function.DnsLabel("statuspool" + RUN), java.time.Instant.now());
        var loadedSame = hostSame.heartbeat(io.flowcatalyst.platform.function.FunctionHost.HostState.ACTIVE,
                java.util.List.of(new io.flowcatalyst.platform.function.FunctionHost.LoadedVersion(
                        f.address(), 1, new io.flowcatalyst.platform.function.FunctionHost.LoadState.Loaded())),
                java.time.Instant.now());
        var hostOther = io.flowcatalyst.platform.function.FunctionHost.register("host-status-b-" + RUN,
                new io.flowcatalyst.platform.function.DnsLabel("statuspool" + RUN), java.time.Instant.now());
        var loadedOther = hostOther.heartbeat(io.flowcatalyst.platform.function.FunctionHost.HostState.ACTIVE,
                java.util.List.of(new io.flowcatalyst.platform.function.FunctionHost.LoadedVersion(
                        otherFn.address(), 1, new io.flowcatalyst.platform.function.FunctionHost.LoadState.Loaded())),
                java.time.Instant.now());
        uow.inTransaction(tx -> {
            hosts.persist(loadedSame, tx.dbTx());
            hosts.persist(loadedOther, tx.dbTx());
            return null;
        });

        var r = http.get("/api/functions/status-" + RUN + ".svc.fn/status", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("address").asString()).isEqualTo("status-" + RUN + ".svc.fn");
        assertThat(body.get("live").get("version").asInt()).isEqualTo(1);
        assertThat(body.get("versions")).hasSize(2);

        JsonNode hostsNode = body.get("hosts");
        assertThat(hostsNode).as("only the host reporting THIS address").hasSize(1);
        assertThat(hostsNode.get(0).get("hostId").asString()).isEqualTo("host-status-a-" + RUN);
        assertThat(hostsNode.get(0).get("loaded")).as("only its entries for THIS address").hasSize(1);
        assertThat(hostsNode.get(0).get("loaded").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(hostsNode.get(0).get("stale").asBoolean()).as("just heartbeated").isFalse();
    }

    @Test
    void statusMarksAHostStaleOutsideTheLiveWindow() {
        testApplication("status-stale", "statusstale-" + RUN);
        var created = create("statusstale-" + RUN, "svc", "fn", null);
        io.flowcatalyst.platform.function.Function f = functions.findById(created.get("id").asString()).orElseThrow();
        var v1 = publishVersion(f, 1, "stalepool" + RUN);
        promoteVersion(f, v1);

        java.time.Instant longAgo = java.time.Instant.now()
                .minus(io.flowcatalyst.platform.function.FunctionHost.LIVE_WINDOW).minusSeconds(60);
        var staleHost = new io.flowcatalyst.platform.function.FunctionHost("host-status-stale-" + RUN,
                new io.flowcatalyst.platform.function.DnsLabel("stalepool" + RUN),
                io.flowcatalyst.platform.function.FunctionHost.HostState.ACTIVE,
                java.util.List.of(new io.flowcatalyst.platform.function.FunctionHost.LoadedVersion(
                        f.address(), 1, new io.flowcatalyst.platform.function.FunctionHost.LoadState.Loaded())),
                longAgo, longAgo);
        uow.inTransaction(tx -> {
            hosts.persist(staleHost, tx.dbTx());
            return null;
        });

        var r = json(http.get("/api/functions/statusstale-" + RUN + ".svc.fn/status", ANCHOR));
        assertThat(r.get("hosts")).hasSize(1);
        assertThat(r.get("hosts").get(0).get("stale").asBoolean()).as("mutant: never mark a host stale").isTrue();
    }

    @Test
    void poolsRequiresAnchorAndCountsOnlyLiveHosts() {
        String pool = "poolsview" + RUN;
        var live = io.flowcatalyst.platform.function.FunctionHost.register("host-pools-live-" + RUN,
                new io.flowcatalyst.platform.function.DnsLabel(pool), java.time.Instant.now());
        java.time.Instant longAgo = java.time.Instant.now()
                .minus(io.flowcatalyst.platform.function.FunctionHost.LIVE_WINDOW).minusSeconds(60);
        var stale = new io.flowcatalyst.platform.function.FunctionHost("host-pools-stale-" + RUN,
                new io.flowcatalyst.platform.function.DnsLabel(pool),
                io.flowcatalyst.platform.function.FunctionHost.HostState.ACTIVE, java.util.List.of(), longAgo, longAgo);
        uow.inTransaction(tx -> {
            hosts.persist(live, tx.dbTx());
            hosts.persist(stale, tx.dbTx());
            return null;
        });

        var forbidden = http.get("/api/function-pools", clientViewer("clt_irrelevant"));
        assertThat(forbidden.statusCode()).isEqualTo(403);

        var r = json(http.get("/api/function-pools", ANCHOR));
        boolean found = false;
        for (JsonNode entry : r) {
            if (entry.get("pool").asString().equals(pool)) {
                found = true;
                assertThat(entry.get("hosts").asInt()).as("mutant: count the stale host too").isEqualTo(1);
            }
        }
        assertThat(found).as("the live host's pool is listed").isTrue();
    }
}
