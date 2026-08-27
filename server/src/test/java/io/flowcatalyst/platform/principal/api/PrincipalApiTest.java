package io.flowcatalyst.platform.principal.api;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.AnchorDomains;
import io.flowcatalyst.platform.principal.operations.DeveloperSecrets;
import io.flowcatalyst.platform.principal.InviteEmailer;
import io.flowcatalyst.platform.principal.MfaService;
import io.flowcatalyst.platform.principal.Notifier;
import io.flowcatalyst.platform.principal.PasswordResetEmailer;
import io.flowcatalyst.platform.role.RoleRepository;
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
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The `/api/principals` surface (`docs/spec/principal.md` §3) — the
/// aggregate that landed without an API test, and the one where a missing
/// check is a security bug rather than a wrong number.
///
/// The emphasis is therefore on **who may do what to whom**: the coarse
/// gates, the self-read exemption, client scoping, and the two open
/// questions in §11 whose current answers are recorded here so that changing
/// them has to be deliberate.
@SuppressWarnings("deprecation")
class PrincipalApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static final UnitOfWork UOW = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));
    private static final PrincipalRepository REPO = new PrincipalRepository(TestPg.dataSource());
    private static final ClientRepository CLIENTS = new ClientRepository(TestPg.dataSource());

    private static String clientA;
    private static String clientB;
    private static String userInA;
    private static String userInB;
    private static TestHttp http;

    private static String[] anchor() {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR"};
    }

    /// A CLIENT-scoped caller bound to `clientId`, holding `permissions`.
    private static String[] client(String clientId, String permissions) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId,
                Authenticator.TEST_PERMISSIONS, permissions};
    }

    /// A caller who *is* `principalId` — for the self-read exemption.
    private static String[] self(String principalId, String permissions) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, principalId,
                Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientA,
                Authenticator.TEST_PERMISSIONS, permissions};
    }

    @BeforeAll
    static void start() {
        clientA = seedClient("pa" + RUN);
        clientB = seedClient("pb" + RUN);

        var state = new PrincipalApi.State(REPO,
                new ClientAccessGrantRepository(TestPg.dataSource()),
                new RoleRepository(TestPg.dataSource()),
                new ApplicationRepository(TestPg.dataSource()),
                new ClientConfigRepository(TestPg.dataSource()),
                CLIENTS,
                new EmailDomainMappingRepository(TestPg.dataSource()),
                new IdentityProviderRepository(TestPg.dataSource()),
                AnchorDomains.inDatabase(TestPg.dataSource()),
                PasswordResetEmailer.notConfigured(), InviteEmailer.logging(), Notifier.logging(),
                MfaService.notConfigured(), DeveloperSecrets.unconfigured(), UOW);

        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before("/api/*", auth);
            PrincipalApi.register(cfg.routes, state);
        });

        userInA = createUser("ua", clientA);
        userInB = createUser("ub", clientB);
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static String seedClient(String tag) {
        var c = Client.create("Client " + tag, ClientIdentifier.parse(tag));
        UOW.inTransaction(tx -> {
            CLIENTS.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static String createUser(String tag, String clientId) {
        var r = http.post("/api/principals",
                "{\"email\":\"" + tag + RUN + "@example.test\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientId + "\"}",
                anchor());
        assertThat(r.statusCode()).as("seed failed: %s", r.body()).isEqualTo(201);
        return json(r).get("id").asText();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    // ── Gates ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("anonymous is refused on every shape of route")
    void anonymousIsRefused() {
        assertThat(http.get("/api/principals").statusCode()).isEqualTo(403);
        assertThat(http.get("/api/principals/" + userInA).statusCode()).isEqualTo(403);
        assertThat(http.post("/api/principals/" + userInA + "/deactivate", null).statusCode()).isEqualTo(403);
        assertThat(http.delete("/api/principals/" + userInA).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("the read gate is USER_VIEW and the write gate is distinct from it")
    void readAndWriteGatesAreDistinct() {
        var viewer = client(clientA, "platform:iam:user:view");
        assertThat(http.get("/api/principals", viewer).statusCode()).isEqualTo(200);

        // A viewer must not be able to write. If the write routes shared the
        // read gate this would pass, which is the whole point of asserting it.
        assertThat(http.put("/api/principals/" + userInA, "{\"name\":\"nope\"}", viewer).statusCode()).isEqualTo(403);
        assertThat(http.post("/api/principals/" + userInA + "/deactivate", null, viewer).statusCode()).isEqualTo(403);
        assertThat(http.delete("/api/principals/" + userInA, viewer).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("DELETE needs USER_DELETE specifically, not any write permission")
    void deleteNeedsItsOwnPermission() {
        var updater = client(clientA, "platform:iam:user:view,platform:iam:user:update");
        assertThat(http.delete("/api/principals/" + userInA, updater).statusCode())
                .as("update must not imply delete").isEqualTo(403);
    }

    // ── The self-read exemption ────────────────────────────────────────────

    @Test
    @DisplayName("a principal may read itself with no permission at all, but not read anyone else")
    void selfReadNeedsNoPermission() {
        var me = self(userInA, "");
        assertThat(http.get("/api/principals/" + userInA, me).statusCode())
                .as("self-read is exempt from USER_VIEW (§3)").isEqualTo(200);

        // The exemption is for SELF only — the same caller with no permission
        // must not be able to read another principal.
        assertThat(http.get("/api/principals/" + userInB, me).statusCode())
                .as("the exemption must not generalise to other principals").isEqualTo(403);
    }

    // ── Client scoping (§11 Q4: the by-id leak) ────────────────────────────

    @Test
    @DisplayName("the list hides other clients' principals")
    void listIsClientScoped() {
        var body = json(http.get("/api/principals", client(clientA, "platform:iam:user:view")));
        var ids = new java.util.ArrayList<String>();
        body.get("principals").forEach(p -> ids.add(p.get("id").asText()));
        assertThat(ids).contains(userInA).doesNotContain(userInB);
    }

    @Test
    @DisplayName("the by-id read IS client-scoped: a clientA admin cannot read a clientB principal")
    void byIdReadIsClientScoped() {
        // §3: "non-self + clientId != null + no access -> 403 FORBIDDEN".
        // This is the safe half and is worth pinning, because §11 Q4's
        // wording ("read routes under /{id}/...") is easy to read as covering
        // this route too — it does not, and a future "fix" that removes this
        // check would be a cross-tenant leak.
        var r = http.get("/api/principals/" + userInB, client(clientA, "platform:iam:user:view"));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("§11 Q4, UNRULED: the /{id}/roles sub-route is NOT client-scoped, so it reads across clients")
    void subRoutesAreNotClientScoped() {
        // Q4 is about the routes UNDER /{id}/, not the by-id read. `GET
        // /{id}/roles` checks USER_VIEW and loads — with no client check — so
        // a clientA administrator can enumerate a clientB principal's roles
        // even though byIdReadIsClientScoped denies reading the principal
        // itself, and the list route hides it entirely.
        //
        // Pinned as current behaviour, awaiting a ruling. If it is scoped,
        // this flips to 403 and the change is visible rather than silent.
        var r = http.get("/api/principals/" + userInB + "/roles", client(clientA, "platform:iam:user:view"));
        assertThat(r.statusCode())
                .as("cross-client role read currently succeeds — spec §11 Q4")
                .isEqualTo(200);
    }

    // ── §11 Q3: the existence oracle on role mutations ─────────────────────

    @Test
    @DisplayName("§11 Q3, UNRULED: role mutation has no coarse gate, so 404 vs 403 tells an outsider which ids exist")
    void roleMutationIsAnExistenceOracle() {
        // §3: "PUT /{id}/roles — **no coarse gate**; load (404)". The load
        // happens before any authorisation, so a caller with NO user
        // permissions gets a different status for a real id than a fake one.
        // That is an existence oracle over the principal table.
        var outsider = client(clientA, "platform:messaging:process:view");

        var unknown = http.put("/api/principals/prn_doesnotexist99/roles", "{\"roles\":[]}", outsider);
        var real = http.put("/api/principals/" + userInB + "/roles", "{\"roles\":[]}", outsider);

        assertThat(unknown.statusCode()).as("unknown id").isEqualTo(404);
        assertThat(real.statusCode()).as("real id — a DIFFERENT status, which is the leak").isNotEqualTo(404);
    }

    // ── The tenant boundary on the ungated mutations ───────────────────────

    @Test
    @DisplayName("§11 Q3 is about ORDERING only: the mutations still refuse a target in another client")
    void ungatedMutationsStillEnforceTheTenantBoundary() {
        // The role / application-access / developer-credential routes have no
        // coarse handler gate and load before authorising, which is the
        // existence oracle in Q3. What they do NOT lack is the check itself:
        // Access.requireUserAdmin runs post-load and tests the TARGET's home
        // client, so a clientA administrator cannot reach a clientB principal.
        //
        // Asserted rather than read, because "the check exists somewhere
        // downstream" is exactly the belief that turns into a tenant breach
        // when someone moves it.
        var adminOfA = client(clientA, "platform:iam:user:view,platform:iam:user:update,platform:iam:user:assign-roles");

        var roles = http.put("/api/principals/" + userInB + "/roles", "{\"roles\":[]}", adminOfA);
        assertThat(roles.statusCode()).as("roles, body was: %s", roles.body()).isEqualTo(403);

        var apps = http.put("/api/principals/" + userInB + "/application-access",
                "{\"applicationIds\":[]}", adminOfA);
        assertThat(apps.statusCode()).as("application access, body was: %s", apps.body()).isEqualTo(403);

        var cred = http.post("/api/principals/" + userInB + "/developer-credential", null, adminOfA);
        assertThat(cred.statusCode()).as("developer credential, body was: %s", cred.body()).isEqualTo(403);

        // ...and the same administrator CAN do it inside its own client, so
        // the 403s above are the boundary and not a blanket denial.
        var ownClient = http.put("/api/principals/" + userInA + "/roles", "{\"roles\":[]}", adminOfA);
        assertThat(ownClient.statusCode()).as("own client, body was: %s", ownClient.body()).isEqualTo(200);
    }

    // ── Roles ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("assigning roles reports what actually changed, and is a set operation")
    void assignRolesReportsTheDelta() {
        var target = createUser("roles", clientA);
        var r = http.put("/api/principals/" + target + "/roles", "{\"roles\":[]}", anchor());
        assertThat(r.statusCode()).as("body was: %s", r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.has("roles")).isTrue();
        assertThat(body.has("added")).isTrue();
        assertThat(body.has("removed")).isTrue();
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("check-email-domain validates before it looks anything up")
    void checkEmailDomainValidates() {
        var viewer = client(clientA, "platform:iam:user:view");
        assertThat(json(http.get("/api/principals/check-email-domain", viewer)).get("error").asText())
                .isEqualTo("EMAIL_REQUIRED");
        assertThat(json(http.get("/api/principals/check-email-domain?email=nope", viewer)).get("error").asText())
                .isEqualTo("INVALID_EMAIL");
    }

    @Test
    @DisplayName("an unknown principal is 404 Principal_NOT_FOUND, including on the version read (D1)")
    void unknownPrincipalIsNotFound() {
        var a = anchor();
        assertThat(http.get("/api/principals/prn_nosuchid0000/version", a).statusCode()).isEqualTo(404);
        assertThat(json(http.get("/api/principals/prn_nosuchid0000", a)).get("error").asText())
                .isEqualTo("Principal_NOT_FOUND");
    }
}
