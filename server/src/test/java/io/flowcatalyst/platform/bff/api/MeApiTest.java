package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `/api/me` end to end: the whoami shape from a test-header context (404
/// when the principal row no longer exists), applications filtered to the
/// accessible set, and clients 404 when inaccessible.
@SuppressWarnings("deprecation")
class MeApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final PrincipalRepository principalRepo = new PrincipalRepository(TestPg.dataSource());
    private static final ApplicationRepository applicationRepo = new ApplicationRepository(TestPg.dataSource());
    private static final ClientRepository clientRepo = new ClientRepository(TestPg.dataSource());
    private static final ClientConfigRepository clientConfigRepo = new ClientConfigRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            MeApi.register(routes, new MeApi.State(principalRepo, applicationRepo, clientRepo, clientConfigRepo));
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

    private static Principal persistUser(String email) throws Exception {
        Principal p = Principal.newUser(EmailAddress.parse(email), UserScope.CLIENT);
        uow.inTransaction(tx -> {
            principalRepo.persist(p, tx.dbTx());
            return null;
        });
        return p;
    }

    private static Application persistApplication(String code) {
        Application a = Application.create(ApplicationType.APPLICATION, code, "App " + code);
        uow.inTransaction(tx -> {
            applicationRepo.persist(a, tx.dbTx());
            return null;
        });
        return a;
    }

    private static Client persistClient(String name, String identifier) {
        Client c = Client.create(name, ClientIdentifier.parse(identifier));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c;
    }

    private static void persistClientConfig(String applicationId, String clientId, boolean enabled) {
        var config = ClientConfig.create(applicationId, clientId);
        if (!enabled) config = config.disable();
        var finalConfig = config;
        uow.inTransaction(tx -> {
            clientConfigRepo.persist(finalConfig, tx.dbTx());
            return null;
        });
    }

    // ── whoami ─────────────────────────────────────────────────────────────

    @Test
    void whoamiReflectsThePrincipalRowAndTheAuthContext() throws Exception {
        Principal p = persistUser("me-" + RUN + "@example.com");
        String[] headers = {
                Authenticator.TEST_PRINCIPAL, p.id(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, "cli_" + RUN + "_a,cli_" + RUN + "_b",
                Authenticator.TEST_ROLES, "role-a,role-b",
                Authenticator.TEST_PERMISSIONS, "platform:iam:role:view",
                Authenticator.TEST_APPLICATIONS, "app_" + RUN + "_1"};

        var r = http.get("/api/me", headers);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("principalId", "principalType", "scope", "name",
                "email", "active", "roles", "permissions", "accessibleClientIds", "accessibleApplicationIds", "allApplications");
        assertThat(body.get("principalId").asText()).isEqualTo(p.id());
        assertThat(body.get("principalType").asText()).isEqualTo("USER");
        assertThat(body.get("scope").asText()).isEqualTo("CLIENT");
        assertThat(body.get("name").asText()).isEqualTo(p.name());
        assertThat(body.get("email").asText()).isEqualTo("me-" + RUN + "@example.com");
        assertThat(body.get("active").asBoolean()).isTrue();
        var roles = new java.util.ArrayList<String>();
        body.get("roles").forEach(n -> roles.add(n.asText()));
        assertThat(roles).containsExactlyInAnyOrder("role-a", "role-b");
        var clients = new java.util.ArrayList<String>();
        body.get("accessibleClientIds").forEach(n -> clients.add(n.asText()));
        assertThat(clients).containsExactlyInAnyOrder("cli_" + RUN + "_a", "cli_" + RUN + "_b");
        assertThat(body.get("allApplications").asBoolean()).as("flips false once an applications list was given").isFalse();
        var apps = new java.util.ArrayList<String>();
        body.get("accessibleApplicationIds").forEach(n -> apps.add(n.asText()));
        assertThat(apps).containsExactly("app_" + RUN + "_1");
    }

    /// Load-bearing: bff spec §8 rules a 404 here — deliberately NOT Go's
    /// fallback-to-test-header-defaults behaviour.
    @Test
    void whoamiIs404WhenThePrincipalRowNoLongerExists() {
        String[] headers = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT"};
        var r = http.get("/api/me", headers);
        assertThat(r.statusCode()).isEqualTo(404);
    }

    @Test
    void whoamiIsRejectedWhenUnauthenticated() {
        assertThat(http.get("/api/me").statusCode()).isEqualTo(403);
    }

    // ── applications ─────────────────────────────────────────────────────

    @Test
    void applicationsAreFilteredToTheAccessibleSetUnlessAllApplications() {
        Application accessible = persistApplication("me-app-yes-" + RUN);
        Application inaccessible = persistApplication("me-app-no-" + RUN);
        String[] scoped = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_APPLICATIONS, accessible.id()};

        var body = json(http.get("/api/me/applications", scoped));
        assertThat(body.get("clientId").asString()).as("Go's zero value for the principal-scoped variant (parity S3)").isEmpty();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("applications", "total", "clientId");
        var ids = body.get("applications").findValuesAsString("id");
        assertThat(ids).contains(accessible.id());
        assertThat(ids).doesNotContain(inaccessible.id());
        assertThat(body.get("total").asInt()).isEqualTo(body.get("applications").size());

        String[] all = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_ALL_APPLICATIONS, "true"};
        var allBody = json(http.get("/api/me/applications", all));
        assertThat(allBody.get("applications").findValuesAsString("id")).contains(accessible.id(), inaccessible.id());
    }

    @Test
    void applicationSummaryKeySet() {
        Application a = persistApplication("me-app-shape-" + RUN);
        String[] all = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_ALL_APPLICATIONS, "true"};
        var app = streamOf(json(http.get("/api/me/applications", all)).get("applications"))
                .filter(n -> n.get("id").asText().equals(a.id())).findFirst().orElseThrow();
        assertThat(app.propertyNames()).containsExactlyInAnyOrder("id", "code", "name");
    }

    // ── clients ────────────────────────────────────────────────────────────

    @Test
    void clientsListedForAnchorAndScopedForNonAnchor() {
        Client mine = persistClient("Me Client " + RUN, "me-mine-" + RUN);
        Client theirs = persistClient("Me Other Client " + RUN, "me-other-" + RUN);

        String[] anchor = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR", Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
        var anchorBody = json(http.get("/api/me/clients", anchor));
        assertThat(anchorBody.propertyNames()).containsExactlyInAnyOrder("clients", "total");
        assertThat(anchorBody.get("clients").findValuesAsString("id")).contains(mine.id(), theirs.id());
        var clientShape = streamOf(anchorBody.get("clients")).filter(n -> n.get("id").asText().equals(mine.id())).findFirst().orElseThrow();
        assertThat(clientShape.propertyNames()).containsExactlyInAnyOrder("id", "name", "identifier", "status", "createdAt", "updatedAt");

        String[] scoped = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, mine.id()};
        var scopedBody = json(http.get("/api/me/clients", scoped));
        assertThat(scopedBody.get("clients").findValuesAsString("id")).contains(mine.id());
        assertThat(scopedBody.get("clients").findValuesAsString("id")).doesNotContain(theirs.id());
    }

    /// Load-bearing: bff spec §8 names 404 for an inaccessible client here too.
    @Test
    void singleClientIs404WhenInaccessible() {
        Client theirs = persistClient("Me Inaccessible Client " + RUN, "me-inacc-" + RUN);
        String[] scoped = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, "cli_" + RUN + "_unrelated"};
        assertThat(http.get("/api/me/clients/" + theirs.id(), scoped).statusCode()).as("404, not 403").isEqualTo(404);
        assertThat(http.get("/api/me/clients/" + theirs.id() + "/applications", scoped).statusCode()).isEqualTo(404);

        String[] owning = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, theirs.id()};
        assertThat(http.get("/api/me/clients/" + theirs.id(), owning).statusCode()).isEqualTo(200);
    }

    @Test
    void unknownClientIs404() {
        String[] anchor = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR", Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
        assertThat(http.get("/api/me/clients/clt_doesnotexist1", anchor).statusCode()).isEqualTo(404);
    }

    /// Load-bearing: only ENABLED configs surface, and `clientId` is present
    /// (not omitted) for this per-client variant — the opposite of the
    /// principal-scoped `/api/me/applications`.
    @Test
    void clientApplicationsShowOnlyEnabledConfigs() {
        Client c = persistClient("Me Config Client " + RUN, "me-cfg-" + RUN);
        Application enabled = persistApplication("me-cfg-enabled-" + RUN);
        Application disabled = persistApplication("me-cfg-disabled-" + RUN);
        persistClientConfig(enabled.id(), c.id(), true);
        persistClientConfig(disabled.id(), c.id(), false);

        String[] anchor = {Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR", Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
        var body = json(http.get("/api/me/clients/" + c.id() + "/applications", anchor));
        assertThat(body.get("clientId").asText()).as("present for the per-client variant").isEqualTo(c.id());
        var ids = body.get("applications").findValuesAsString("id");
        assertThat(ids).contains(enabled.id());
        assertThat(ids).doesNotContain(disabled.id());
    }

    private static java.util.stream.Stream<JsonNode> streamOf(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }
}
