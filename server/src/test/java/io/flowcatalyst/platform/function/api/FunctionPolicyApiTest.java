package io.flowcatalyst.platform.function.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
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

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `/api/function-policies/{owner}` end to end (spec `function-api.md` §4.3,
/// §8 P17): no-row default, the `PUT`→`GET` round trip, the platform
/// reachable as the literal `platform`, and its audit row's non-null entity
/// id.
@SuppressWarnings("deprecation")
class FunctionPolicyApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final ClientRepository clients = new ClientRepository(TestPg.dataSource());
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();

    private static final String[] ANCHOR_POLICY_MANAGE = {
            Authenticator.TEST_PRINCIPAL, "usr_policy_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR",
            Authenticator.TEST_PERMISSIONS, "platform:function:policy:manage"};

    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            FunctionPolicyApi.register(routes, new FunctionPolicyApi.State(policies, clients, uow, DEFAULTS));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static String testClient(String tag) {
        Client c = Client.create("Function Policy Api " + tag, ClientIdentifier.parse("fnp-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clients.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_id FROM aud_logs WHERE entity_id = ? AND operation = ?", entityId, operation);
    }

    // ── P17 ────────────────────────────────────────────────────────────────

    @Test
    void getWithNoRowReturnsThePlatformDefaultsWithStoredFalse() {
        String clientId = testClient("default");
        var body = json(http.get("/api/function-policies/" + clientId, ANCHOR_POLICY_MANAGE));
        assertThat(body.get("owner").asText()).isEqualTo(clientId);
        assertThat(body.get("signers")).isEmpty();
        assertThat(body.get("stored").asBoolean()).isFalse();
        assertThat(body.get("ceilings").get("maxDurationMs").asInt()).isEqualTo(DEFAULTS.maxDurationMs());
        assertThat(body.get("ceilings").get("maxConcurrency").asInt()).isEqualTo(DEFAULTS.maxConcurrency());
        assertThat(body.get("ceilings").get("maxWasmMemoryMb").asInt()).isEqualTo(DEFAULTS.wasmMemoryMb());
        assertThat(body.get("ceilings").get("maxDbPoolSize").asInt()).isEqualTo(DEFAULTS.dbPoolSize());
    }

    @Test
    void putThenGetRoundTrips() {
        String clientId = testClient("roundtrip");
        var put = http.put("/api/function-policies/" + clientId,
                "{\"signers\":[{\"issuer\":\"https://issuer\",\"subject\":\"sub-1\",\"runtimes\":[\"jvm\"]}],"
                        + "\"ceilings\":{\"maxDurationMs\":9000}}",
                ANCHOR_POLICY_MANAGE);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        var putBody = json(put);
        assertThat(putBody.get("stored").asBoolean()).isTrue();
        assertThat(putBody.get("signers")).hasSize(1);
        assertThat(putBody.get("signers").get(0).get("issuer").asText()).isEqualTo("https://issuer");
        assertThat(putBody.get("signers").get(0).get("runtimes").get(0).asText()).isEqualTo("jvm");
        assertThat(putBody.get("ceilings").get("maxDurationMs").asInt()).isEqualTo(9000);
        assertThat(putBody.get("ceilings").get("maxConcurrency").asInt()).as("unset ceiling still resolves to the platform default")
                .isEqualTo(DEFAULTS.maxConcurrency());

        var get = json(http.get("/api/function-policies/" + clientId, ANCHOR_POLICY_MANAGE));
        assertThat(get.get("stored").asBoolean()).isTrue();
        assertThat(get.get("signers")).hasSize(1);
        assertThat(get.get("ceilings").get("maxDurationMs").asInt()).isEqualTo(9000);
    }

    /// The platform's own policy is reachable as the literal `platform`, and
    /// its audit row has a non-null entity id (`FunctionOwner.PLATFORM_KEY`,
    /// spec §4.3).
    @Test
    void platformIsReachableAsTheLiteralPlatformAndAuditsWithANonNullEntityId() {
        var put = http.put("/api/function-policies/platform",
                "{\"signers\":[],\"ceilings\":{}}", ANCHOR_POLICY_MANAGE);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(json(put).get("owner").asText()).isEqualTo("platform");

        var get = json(http.get("/api/function-policies/platform", ANCHOR_POLICY_MANAGE));
        assertThat(get.get("owner").asText()).isEqualTo("platform");
        assertThat(get.get("stored").asBoolean()).isTrue();

        assertThat(auditsFor("PLATFORM", "PutPolicyCommand")).as("audit entity id is never null for the platform")
                .isNotEmpty();
    }

    @Test
    void putForAMissingClientIs404() {
        var r = http.put("/api/function-policies/clt_doesnotexist" + RUN, "{\"signers\":[],\"ceilings\":{}}", ANCHOR_POLICY_MANAGE);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Client_NOT_FOUND");
    }

    /// No credential and a missing role both render the platform's ordinary
    /// 403 envelope here (unlike `/control/functions/*`, spec §2's 401
    /// override — B2, not this slice): `Checks.requireAnchor`/`require`
    /// treat a `null` context as `UNAUTHENTICATED`, an authorization-kind
    /// error, which is always 403.
    @Test
    void noCredentialAndMissingRoleAreBoth403() {
        String clientId = testClient("gate");
        var noCred = http.get("/api/function-policies/" + clientId);
        assertThat(noCred.statusCode()).isEqualTo(403);

        String[] anchorNoPolicy = {Authenticator.TEST_PRINCIPAL, "usr_nopolicy_" + RUN, Authenticator.TEST_SCOPE, "ANCHOR"};
        var noPermission = http.get("/api/function-policies/" + clientId, anchorNoPolicy);
        assertThat(noPermission.statusCode()).isEqualTo(403);

        String[] nonAnchor = {Authenticator.TEST_PRINCIPAL, "usr_nonanchor_" + RUN, Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId, Authenticator.TEST_PERMISSIONS, "platform:function:policy:manage"};
        var notAnchor = http.get("/api/function-policies/" + clientId, nonAnchor);
        assertThat(notAnchor.statusCode()).isEqualTo(403);
    }
}
