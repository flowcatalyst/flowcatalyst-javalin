package io.flowcatalyst.platform.function.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.TriggerSync;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
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
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(TestPg.dataSource());
    private static final TriggerObjectRepository triggerObjects = new TriggerObjectRepository(TestPg.dataSource());
    private static final SubscriptionRepository subscriptions = new SubscriptionRepository(TestPg.dataSource());
    private static final DispatchPoolRepository dispatchPools = new DispatchPoolRepository(TestPg.dataSource());
    private static final ScheduledJobRepository scheduledJobs = new ScheduledJobRepository(TestPg.dataSource());
    private static final FunctionSettingsRepository settings =
            new FunctionSettingsRepository(TestPg.dataSource(), java.util.Optional.empty());
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
            FunctionApi.register(routes, new FunctionApi.State(functions, applications, clients, uow, versions, hosts,
                    policies, FunctionLimits.defaults(), new Signatures.Off(), TriggerSync.none(), triggerObjects,
                    subscriptions, dispatchPools, scheduledJobs, settings, java.util.Optional.empty(), java.util.Optional.empty()));
            // §4.3: needed for P10's real PUT /api/function-policies/{owner} route.
            io.flowcatalyst.platform.function.api.FunctionPolicyApi.register(routes,
                    new io.flowcatalyst.platform.function.api.FunctionPolicyApi.State(
                            policies, clients, uow, FunctionLimits.defaults()));
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

    /// R-a (review fix, slice B3): status is gated like every other by-address
    /// read — the owning client sees its own function's status; another
    /// client gets 404 (never 403, which would confirm the address exists).
    @Test
    void statusReachesTheOwningClientButNotAnotherClient() {
        String clientId = testClient("status-anchor");
        testApplication("status-anchor", "statusanchor-" + RUN);
        create("statusanchor-" + RUN, "svc", "fn", clientId);

        var owner = http.get("/api/functions/statusanchor-" + RUN + ".svc.fn/status", clientViewer(clientId));
        assertThat(owner.statusCode()).as("mutant: drop the reach check — the owning client must see its own function's status")
                .isEqualTo(200);

        var stranger = http.get("/api/functions/statusanchor-" + RUN + ".svc.fn/status", clientViewer("clt_someoneelse_" + RUN));
        assertThat(stranger.statusCode()).as("mutant: drop the reach check — another client must not").isEqualTo(404);
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

    // ── §5: versions and aliases (slice B3) ──────────────────────────────────

    private static final String MINIMAL_MANIFEST = """
            {"runtime":"jvm","entrypoint":"com.acme.Fn"}""";

    private static String digestHex(String suffix) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((RUN + ":" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> publishHttp(String address, String digestSuffix, String manifestJson) {
        String body = "{\"artifactRef\":\"oci://artifact/" + digestSuffix + "\",\"digest\":\"" + digestHex(digestSuffix)
                + "\",\"manifest\":" + manifestJson + "}";
        return http.post("/api/functions/" + address + "/versions", body, ANCHOR);
    }

    @Test
    void publishRouteReturns201WithTheStoredVersion() {
        testApplication("publish", "publish-" + RUN);
        create("publish-" + RUN, "svc", "fn", null);
        String address = "publish-" + RUN + ".svc.fn";

        var r = publishHttp(address, "http1", MINIMAL_MANIFEST);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode body = json(r);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("state").asString()).isEqualTo("PUBLISHED");
        assertThat(body.get("digest").asString()).isEqualTo(digestHex("http1"));
        assertThat(body.has("signer")).as("Signatures.Off in this harness -> no signer").isFalse();
    }

    @Test
    void publishRequiresTheFunctionPublishPermission() {
        testApplication("publishperm", "publishperm-" + RUN);
        create("publishperm-" + RUN, "svc", "fn", null);
        String address = "publishperm-" + RUN + ".svc.fn";

        String body = "{\"artifactRef\":\"oci://artifact/x\",\"digest\":\"" + digestHex("permx") + "\",\"manifest\":"
                + MINIMAL_MANIFEST + "}";
        var r = http.post("/api/functions/" + address + "/versions", body, manage());
        assertThat(r.statusCode()).isEqualTo(403);
    }

    @Test
    void listVersionsAndGetVersionRoutesWork() {
        testApplication("versions", "versions-" + RUN);
        create("versions-" + RUN, "svc", "fn", null);
        String address = "versions-" + RUN + ".svc.fn";
        publishHttp(address, "lv1", MINIMAL_MANIFEST);
        publishHttp(address, "lv2", MINIMAL_MANIFEST);

        var list = json(http.get("/api/functions/" + address + "/versions", ANCHOR));
        assertThat(list).as("newest first").hasSize(2);
        assertThat(list.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(list.get(0).has("manifest")).as("list omits manifest").isFalse();

        var one = json(http.get("/api/functions/" + address + "/versions/1", ANCHOR));
        assertThat(one.get("version").asInt()).isEqualTo(1);
        assertThat(one.get("manifest").get("entrypoint").asString()).as("GET one adds manifest")
                .isEqualTo("com.acme.Fn");

        var notInteger = http.get("/api/functions/" + address + "/versions/abc", ANCHOR);
        assertThat(notInteger.statusCode()).isEqualTo(400);
        assertThat(json(notInteger).get("error").asString()).isEqualTo("VERSION_INVALID");
    }

    @Test
    void retireRouteReturns200WithTheVersion() {
        testApplication("retire", "retire-" + RUN);
        create("retire-" + RUN, "svc", "fn", null);
        String address = "retire-" + RUN + ".svc.fn";
        publishHttp(address, "ret1", MINIMAL_MANIFEST);

        var r = http.post("/api/functions/" + address + "/versions/1/retire", null, ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("state").asString()).isEqualTo("RETIRED");

        var retireAgain = http.post("/api/functions/" + address + "/versions/1/retire", null, ANCHOR);
        assertThat(retireAgain.statusCode()).isEqualTo(409);
    }

    @Test
    void promoteAliasInvalidAndListAliasesRoutesWork() {
        testApplication("promote", "promote-" + RUN);
        create("promote-" + RUN, "svc", "fn", null);
        String address = "promote-" + RUN + ".svc.fn";
        publishHttp(address, "pr1", MINIMAL_MANIFEST);

        // Review fix, slice B3 (carried into function-zones-and-aliases.md §2): alias-name
        // validity is checked BEFORE version state, in PromoteVersion's own `validate`
        // phase — an ill-formed alias on a version that is not yet ready is 400
        // ALIAS_INVALID, never 409 VERSION_NOT_READY.
        var badAliasNotReady = http.put("/api/functions/" + address + "/aliases/BAD_NAME", "{\"version\":1}", ANCHOR);
        assertThat(badAliasNotReady.statusCode())
                .as("mutant: check version state before alias validity").isEqualTo(400);
        assertThat(json(badAliasNotReady).get("error").asString()).isEqualTo("ALIAS_INVALID");

        // A well-formed NAMED alias, still not ready, reaches the version-state guard exactly
        // like `live` does (spec §2: "the same R3 rule").
        var namedNotReady = http.put("/api/functions/" + address + "/aliases/qa", "{\"version\":1}", ANCHOR);
        assertThat(namedNotReady.statusCode()).isEqualTo(409);
        assertThat(json(namedNotReady).get("error").asString()).isEqualTo("VERSION_NOT_READY");

        var notReady = http.put("/api/functions/" + address + "/aliases/live", "{\"version\":1}", ANCHOR);
        assertThat(notReady.statusCode()).isEqualTo(409);
        assertThat(json(notReady).get("error").asString()).isEqualTo("VERSION_NOT_READY");

        // Mark ready directly (control-plane heartbeat is package B2's own surface).
        var f = functions.findByAddress(io.flowcatalyst.platform.function.FunctionAddress.parse(address)).orElseThrow();
        var v1 = versions.findByFunctionAndVersion(f.id(), 1).orElseThrow();
        uow.inTransaction(tx -> {
            versions.persist(v1.markReady(java.time.Instant.now()), tx.dbTx());
            return null;
        });

        // An ill-formed alias name is still 400 ALIAS_INVALID once ready —
        // Function.requireValidAliasName is the rule's one home, reached both ways.
        var badAlias = http.put("/api/functions/" + address + "/aliases/BAD_NAME", "{\"version\":1}", ANCHOR);
        assertThat(badAlias.statusCode()).isEqualTo(400);
        assertThat(json(badAlias).get("error").asString()).isEqualTo("ALIAS_INVALID");

        var promoted = http.put("/api/functions/" + address + "/aliases/live", "{\"version\":1}", ANCHOR);
        assertThat(promoted.statusCode()).as(promoted.body()).isEqualTo(200);
        JsonNode body = json(promoted);
        assertThat(body.get("alias").asString()).isEqualTo("live");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.has("previousVersion")).as("first promotion has none").isFalse();

        // Now the same READY version as a NAMED alias too.
        var qaPromoted = http.put("/api/functions/" + address + "/aliases/qa", "{\"version\":1}", ANCHOR);
        assertThat(qaPromoted.statusCode()).as(qaPromoted.body()).isEqualTo(200);
        assertThat(json(qaPromoted).get("alias").asString()).isEqualTo("qa");

        var aliases = json(http.get("/api/functions/" + address + "/aliases", ANCHOR));
        assertThat(aliases).as("both live and qa are listed").hasSize(2);
        assertThat(aliases).extracting(a -> a.get("alias").asString()).containsExactlyInAnyOrder("live", "qa");

        // FunctionResponse.live.version is a wire INTEGER (review fix, slice B3), not a string.
        var fn = json(http.get("/api/functions/" + address, ANCHOR));
        assertThat(fn.get("live").get("version").isNumber())
                .as("mutant: emit live.version as a string on the wire").isTrue();
        assertThat(fn.get("live").get("version").asInt()).isEqualTo(1);
        assertThat(fn.get("live").get("versionId").asString()).isEqualTo(v1.id());
    }

    /// A2 (spec §2): `DELETE …/aliases/{alias}` — `live` is protected, an
    /// unknown alias is 404, and a real named alias is removed (204) and
    /// gone from the list afterward.
    @Test
    void deleteAliasProtectsLiveRefusesUnknownAndRemovesANamedAlias() {
        testApplication("deletealias", "deletealias-" + RUN);
        create("deletealias-" + RUN, "svc", "fn", null);
        String address = "deletealias-" + RUN + ".svc.fn";
        publishHttp(address, "da1", MINIMAL_MANIFEST);

        var f = functions.findByAddress(io.flowcatalyst.platform.function.FunctionAddress.parse(address)).orElseThrow();
        var v1 = versions.findByFunctionAndVersion(f.id(), 1).orElseThrow();
        uow.inTransaction(tx -> {
            versions.persist(v1.markReady(java.time.Instant.now()), tx.dbTx());
            return null;
        });
        http.put("/api/functions/" + address + "/aliases/live", "{\"version\":1}", ANCHOR);
        http.put("/api/functions/" + address + "/aliases/qa", "{\"version\":1}", ANCHOR);

        var protectedLive = http.delete("/api/functions/" + address + "/aliases/live", ANCHOR);
        assertThat(protectedLive.statusCode()).isEqualTo(409);
        assertThat(json(protectedLive).get("error").asString()).isEqualTo("ALIAS_PROTECTED");
        assertThat(json(http.get("/api/functions/" + address + "/aliases", ANCHOR)))
                .as("mutant: live removed anyway").hasSize(2);

        var unknown = http.delete("/api/functions/" + address + "/aliases/nosuch", ANCHOR);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").asString()).isEqualTo("Alias_NOT_FOUND");

        var deleted = http.delete("/api/functions/" + address + "/aliases/qa", ANCHOR);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);

        var aliasesAfter = json(http.get("/api/functions/" + address + "/aliases", ANCHOR));
        assertThat(aliasesAfter).as("mutant: qa still listed after deletion")
                .hasSize(1).extracting(a -> a.get("alias").asString()).containsExactly("live");
    }

    // ── P10: over-ceiling manifest, rejected using the OWNER's ceilings ──────

    @Test
    void overCeilingManifestUsesTheOwnersCeilingNotThePlatformDefault() {
        String clientA = testClient("p10a");
        String clientB = testClient("p10b");
        testApplication("p10", "p10-" + RUN);
        var fnA = create("p10-" + RUN, "svc", "a", clientA);
        var fnB = create("p10-" + RUN, "svc", "b", clientB);
        String addressA = "p10-" + RUN + ".svc.a";
        String addressB = "p10-" + RUN + ".svc.b";
        String overCeilingManifest = """
                {"runtime":"jvm","entrypoint":"com.acme.Fn","limits":{"maxConcurrency":64}}""";

        // Default ceiling is 32 — both over it.
        var beforeA = publishHttp(addressA, "p10a1", overCeilingManifest);
        assertThat(beforeA.statusCode()).as(beforeA.body()).isEqualTo(400);
        assertThat(json(beforeA).get("error").asString()).isEqualTo("LIMIT_OVER_CEILING");
        var beforeB = publishHttp(addressB, "p10b1", overCeilingManifest);
        assertThat(beforeB.statusCode()).isEqualTo(400);

        // Raise ONLY client A's ceiling via the real policy route.
        var putPolicy = http.put("/api/function-policies/" + clientA,
                "{\"signers\":[],\"ceilings\":{\"maxConcurrency\":100}}", ANCHOR);
        assertThat(putPolicy.statusCode()).as(putPolicy.body()).isEqualTo(200);

        var afterA = publishHttp(addressA, "p10a2", overCeilingManifest);
        assertThat(afterA.statusCode()).as("mutant: always use platform defaults — " + afterA.body()).isEqualTo(201);
        var afterB = publishHttp(addressB, "p10b2", overCeilingManifest);
        assertThat(afterB.statusCode()).as("mutant: use the OTHER owner's policy — " + afterB.body()).isEqualTo(400);
    }

    // ── config: GET/PUT round trip ────────────────────────────────────────

    @Test
    void configGetPutRoundTrips() {
        testApplication("cfg", "cfg-" + RUN);
        create("cfg-" + RUN, "svc", "fn", null);
        String address = "cfg-" + RUN + ".svc.fn";

        var empty = json(http.get("/api/functions/" + address + "/config", ANCHOR));
        assertThat(empty.get("values").isEmpty()).isTrue();
        assertThat(empty.get("declared")).isEmpty();
        assertThat(empty.get("missing")).isEmpty();

        var put = http.put("/api/functions/" + address + "/config", "{\"values\":{\"GREETING\":\"hi\"}}", ANCHOR);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(json(put).get("values").get("GREETING").asString()).isEqualTo("hi");

        var after = json(http.get("/api/functions/" + address + "/config", ANCHOR));
        assertThat(after.get("values").get("GREETING").asString()).isEqualTo("hi");

        // Full replacement: a second PUT without GREETING removes it.
        var replace = http.put("/api/functions/" + address + "/config", "{\"values\":{\"OTHER\":\"x\"}}", ANCHOR);
        assertThat(replace.statusCode()).isEqualTo(200);
        var afterReplace = json(http.get("/api/functions/" + address + "/config", ANCHOR));
        assertThat(afterReplace.get("values").has("GREETING")).as("mutant: merge instead of replace").isFalse();
        assertThat(afterReplace.get("values").get("OTHER").asString()).isEqualTo("x");
    }

    @Test
    void configInvalidKeyIs400SettingKeyInvalid() {
        testApplication("cfgbad", "cfgbad-" + RUN);
        create("cfgbad-" + RUN, "svc", "fn", null);
        String address = "cfgbad-" + RUN + ".svc.fn";

        var r = http.put("/api/functions/" + address + "/config", "{\"values\":{\"1bad\":\"x\"}}", ANCHOR);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("SETTING_KEY_INVALID");
    }

    @Test
    void configOverTheKeyCountLimitIs400SettingTooLarge() {
        testApplication("cfgbig", "cfgbig-" + RUN);
        create("cfgbig-" + RUN, "svc", "fn", null);
        String address = "cfgbig-" + RUN + ".svc.fn";

        var values = new StringBuilder("{\"values\":{");
        for (int i = 0; i <= io.flowcatalyst.platform.function.operations.SetFunctionConfig.MAX_KEYS; i++) {
            if (i > 0) values.append(',');
            values.append("\"K").append(i).append("\":\"v\"");
        }
        values.append("}}");

        var r = http.put("/api/functions/" + address + "/config", values.toString(), ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("SETTING_TOO_LARGE");
    }

    @Test
    void configValueOverTheByteLimitIs400SettingTooLarge() {
        testApplication("cfgval", "cfgval-" + RUN);
        create("cfgval-" + RUN, "svc", "fn", null);
        String address = "cfgval-" + RUN + ".svc.fn";
        String tooLong = "x".repeat(io.flowcatalyst.platform.function.operations.SetFunctionConfig.MAX_VALUE_BYTES + 1);

        var r = http.put("/api/functions/" + address + "/config", "{\"values\":{\"BIG\":\"" + tooLong + "\"}}", ANCHOR);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("SETTING_TOO_LARGE");
    }

    // ── S1: declared over the candidate, not only live ──────────────────────

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static Manifest manifestDeclaring(String configKey, String secretKey) {
        String json = "{\"runtime\":\"jvm\",\"entrypoint\":\"com.acme.Fn\""
                + (configKey == null ? "" : ",\"config\":[\"" + configKey + "\"]")
                + (secretKey == null ? "" : ",\"secrets\":[\"" + secretKey + "\"]")
                + "}";
        return Manifest.parseStrict(Json.MAPPER.readTree(json), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    /// Writes a `PUBLISHED` version straight through the repositories — no
    /// HTTP publish, no promote gate — so a test can set up "candidate not
    /// yet live" fixtures directly (`FunctionControlApiTest`'s own pattern).
    private static FunctionVersion publishVersionDirect(String address, int version, String configKey, String secretKey) {
        Function f = functions.findByAddress(io.flowcatalyst.platform.function.FunctionAddress.parse(address)).orElseThrow();
        String hex = Integer.toHexString((f.id() + version + configKey + secretKey).hashCode()) + "0".repeat(64);
        var digest = io.flowcatalyst.platform.function.Digest.parse("sha256:" + hex.substring(0, 64));
        FunctionVersion v = FunctionVersion.publish(f.id(), version, "oci://artifact", digest, null, null, null,
                manifestDeclaring(configKey, secretKey), "prn_publisher", Instant.now());
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
        return v;
    }

    private static void promoteDirect(String address, FunctionVersion v) {
        Function f = functions.findByAddress(io.flowcatalyst.platform.function.FunctionAddress.parse(address)).orElseThrow();
        Function.Promoted p = f.promote(Function.LIVE, v, "prn_promoter", Instant.now());
        uow.inTransaction(tx -> {
            functions.persist(p.function(), tx.dbTx());
            return null;
        });
    }

    /// S1a: no live version, one `PUBLISHED` version declaring `A` — the
    /// GET must pull `declared`/`declaredBy` from that candidate, not only
    /// from `live` (which does not exist yet). Pins the mutant "declared
    /// stays live-only": the OLD code answered `declared: []` here because
    /// there was no live version at all.
    @Test
    void s1aDeclaredComesFromTheCandidateBeforeAnyPromote() {
        testApplication("s1a", "s1a-" + RUN);
        create("s1a-" + RUN, "svc", "fn", null);
        String address = "s1a-" + RUN + ".svc.fn";
        publishVersionDirect(address, 1, "A", null);

        var body = json(http.get("/api/functions/" + address + "/config", ANCHOR));
        assertThat(body.get("declared")).as("mutant: declared stays live-only (empty before any promote)")
                .extracting(tools.jackson.databind.JsonNode::asString).containsExactly("A");
        assertThat(body.get("missing")).extracting(tools.jackson.databind.JsonNode::asString).containsExactly("A");
        assertThat(body.get("declaredBy")).hasSize(1);
        assertThat(body.get("declaredBy").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(body.get("declaredBy").get(0).get("keys")).extracting(tools.jackson.databind.JsonNode::asString)
                .containsExactly("A");
    }

    /// S1b: live declares `A`, a newer `PUBLISHED` candidate declares `B` —
    /// `declared` is the union with no param; `?version=1` narrows to just
    /// live's own keys. Pins "drop the union" (declared would stay `[A]`
    /// with no param) and "ignore the parameter" (`?version=1` would still
    /// answer `[A, B]`).
    @Test
    void s1bDeclaredUnionsLiveAndTheCandidateVersionParamNarrows() {
        testApplication("s1b", "s1b-" + RUN);
        create("s1b-" + RUN, "svc", "fn", null);
        String address = "s1b-" + RUN + ".svc.fn";
        FunctionVersion v1 = publishVersionDirect(address, 1, "A", null);
        promoteDirect(address, v1);
        publishVersionDirect(address, 2, "B", null);

        var noParam = json(http.get("/api/functions/" + address + "/config", ANCHOR));
        assertThat(noParam.get("declared")).as("mutant: drop the union — live's key alone")
                .extracting(tools.jackson.databind.JsonNode::asString).containsExactly("A", "B");
        assertThat(noParam.get("declaredBy")).hasSize(2);
        assertThat(noParam.get("declaredBy").get(1).get("version").asInt()).isEqualTo(2);

        var scoped = json(http.get("/api/functions/" + address + "/config?version=1", ANCHOR));
        assertThat(scoped.get("declared")).as("mutant: ignore the parameter — still [A, B]")
                .extracting(tools.jackson.databind.JsonNode::asString).containsExactly("A");
        assertThat(scoped.get("declaredBy")).as("live IS version 1 here — one entry, not two").hasSize(1);

        // A newer candidate that re-declares live's key: the union holds it ONCE
        // (mutant: drop the dedup — declared would be [A, A]), while declaredBy
        // still lists both manifests with their own full key lists.
        publishVersionDirect(address, 3, "A", null);
        var shared = json(http.get("/api/functions/" + address + "/config", ANCHOR));
        assertThat(shared.get("declared")).as("mutant: drop the dedup — [A, A]")
                .extracting(tools.jackson.databind.JsonNode::asString).containsExactly("A");
        assertThat(shared.get("declaredBy")).hasSize(2);
        assertThat(shared.get("declaredBy").get(1).get("version").asInt()).isEqualTo(3);
    }

    /// S1c: an unknown `?version=` is 404 `FunctionVersion_NOT_FOUND`; a
    /// non-integer is 400 `VERSION_INVALID`.
    @Test
    void s1cVersionParamUnknownIs404NonIntegerIs400() {
        testApplication("s1c", "s1c-" + RUN);
        create("s1c-" + RUN, "svc", "fn", null);
        String address = "s1c-" + RUN + ".svc.fn";

        var unknown = http.get("/api/functions/" + address + "/config?version=9", ANCHOR);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").asString()).isEqualTo("FunctionVersion_NOT_FOUND");

        var nonInteger = http.get("/api/functions/" + address + "/config?version=x", ANCHOR);
        assertThat(nonInteger.statusCode()).isEqualTo(400);
        assertThat(json(nonInteger).get("error").asString()).isEqualTo("VERSION_INVALID");
    }

    /// S1d (values, never `declared`/`declaredBy`) lives in
    /// `FunctionSettingsApiTest`, which is the harness with `FLOWCATALYST_APP_KEY`
    /// actually configured — this class's `settings`/`encryption` are
    /// `Optional.empty()` throughout (X4), so `/secrets` always 503s here.

    // ── X4 (function-context.md §1): no app key ⇒ secret routes 503, nothing stored ──
    // `encryption` is `Optional.empty()` for this whole test class (see `start()` above) —
    // exactly the "no FLOWCATALYST_APP_KEY" condition X4 pins.

    @Test
    void secretRoutesAre503WithNoAppKeyConfiguredAndNothingIsStored() {
        testApplication("x4", "x4-" + RUN);
        create("x4-" + RUN, "svc", "fn", null);
        String address = "x4-" + RUN + ".svc.fn";

        var put = http.put("/api/functions/" + address + "/secrets/API_KEY", "{\"value\":\"whatever\"}", ANCHOR);
        assertThat(put.statusCode()).as("mutant: fall back to plaintext storage instead of refusing — " + put.body())
                .isEqualTo(503);
        assertThat(json(put).get("error").asString()).isEqualTo("ENCRYPTION_UNCONFIGURED");

        var get = http.get("/api/functions/" + address + "/secrets", ANCHOR);
        assertThat(get.statusCode()).isEqualTo(503);
        assertThat(json(get).get("error").asString()).isEqualTo("ENCRYPTION_UNCONFIGURED");

        var del = http.delete("/api/functions/" + address + "/secrets/API_KEY", ANCHOR);
        assertThat(del.statusCode()).isEqualTo(503);
        assertThat(json(del).get("error").asString()).isEqualTo("ENCRYPTION_UNCONFIGURED");

        // The refused PUT must not have reached the repository at all.
        var f = functions.findByAddress(io.flowcatalyst.platform.function.FunctionAddress.parse(address)).orElseThrow();
        assertThat(settings.hasSecret(f.id(), "API_KEY"))
                .as("mutant: write the secret anyway before answering 503").isFalse();
        assertThat(settings.listSecrets(f.id())).isEmpty();
    }
}
