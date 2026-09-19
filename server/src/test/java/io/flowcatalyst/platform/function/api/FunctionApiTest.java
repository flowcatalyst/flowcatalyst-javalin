package io.flowcatalyst.platform.function.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.FunctionRepository;
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
            FunctionApi.register(routes, new FunctionApi.State(functions, applications, clients, uow));
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
}
