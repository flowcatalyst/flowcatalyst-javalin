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
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            PrincipalApi.register(routes, state);
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

    // ── Client scoping (ledger PR-3/PR-4, ruled 2026-09-01) ────────────────

    @Test
    @DisplayName("the list hides other clients' principals")
    void listIsClientScoped() {
        var body = json(http.get("/api/principals", client(clientA, "platform:iam:user:view")));
        var ids = new java.util.ArrayList<String>();
        body.get("principals").forEach(p -> ids.add(p.get("id").asText()));
        assertThat(ids).contains(userInA).doesNotContain(userInB);
    }

    /// The core PR-4 assertion, pinning `PrincipalApi#requireReadable` /
    /// `Access#requireReadable`: an out-of-scope target answers the SAME
    /// 404 a genuinely missing id would, never a distinguishing 403 — a 403
    /// here is an existence oracle over the principal table. Compares the
    /// out-of-scope body to a truly-missing-id body at the SAME route,
    /// normalising the id substring each message names (the only part that
    /// legitimately differs — the caller already knows both ids).
    @Test
    @DisplayName("the by-id read answers the same not-found for an out-of-scope target as for a missing id")
    void byIdReadIsClientScoped() {
        var viewer = client(clientA, "platform:iam:user:view");
        var outOfScope = http.get("/api/principals/" + userInB, viewer);
        var missing = http.get("/api/principals/prn_doesnotexist99", viewer);

        assertThat(outOfScope.statusCode()).as("out-of-scope, mutant: restore 403 FORBIDDEN here").isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(outOfScope).get("error").asText()).isEqualTo("Principal_NOT_FOUND");
        assertThat(normalisedBody(outOfScope, userInB)).as("byte-identical to a missing id, id substring aside")
                .isEqualTo(normalisedBody(missing, "prn_doesnotexist99"));
    }

    /// `<resource>_NOT_FOUND` with the id substring replaced by a fixed
    /// placeholder, so two different (but both absent-to-the-caller) ids
    /// compare equal — the whole point of PR-4.
    private static String normalisedBody(java.net.http.HttpResponse<String> r, String id) {
        return r.body().replace(id, "<ID>");
    }

    @Test
    @DisplayName("PR-4: the /{id}/roles sub-route now answers the same not-found as the by-id read, not 200")
    void rolesSubRouteIsNowClientScoped() {
        var r = http.get("/api/principals/" + userInB + "/roles", client(clientA, "platform:iam:user:view"));
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(json(r).get("error").asText()).isEqualTo("Principal_NOT_FOUND");
    }

    @Test
    @DisplayName("PR-4: /{id}/version, /application-access and /available-applications are also client-scoped")
    void otherReadSubRoutesAreClientScoped() {
        var viewer = client(clientA, "platform:iam:user:view");
        assertThat(http.get("/api/principals/" + userInB + "/version", viewer).statusCode()).isEqualTo(404);
        assertThat(http.get("/api/principals/" + userInB + "/application-access", viewer).statusCode()).isEqualTo(404);
        assertThat(http.get("/api/principals/" + userInB + "/available-applications", viewer).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the self-read exemption still works for the by-id read and for /version")
    void selfExemptionStillWorksAfterThePr4Change() {
        var me = self(userInA, "");
        assertThat(http.get("/api/principals/" + userInA, me).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/principals/" + userInA + "/version", me).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a caller with no permission at all still gets 403 on a principal in its own scope")
    void noPermissionAtAllStillMeans403WithinScope() {
        // Same client as userInA (in scope), zero principal permissions —
        // this must stay a permission 403, not a scope 404: PR-4 only
        // changes the answer for a target the caller CANNOT reach.
        var noPermission = client(clientA, "");
        var r = http.get("/api/principals/" + userInA, noPermission);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
    }

    // ── The former existence oracle on role mutations, now closed ──────────

    @Test
    @DisplayName("PR-4 closes the role-mutation existence oracle: a real out-of-scope id now answers the same 404 as a fake one")
    void roleMutationNoLongerDistinguishesRealFromFakeIds() {
        // Before the ruling, a caller with NO user permissions got a
        // DIFFERENT status for a real (but out-of-scope) id than a fake one
        // — an existence oracle over the principal table. The per-resource
        // scope check now runs (via Access#requireUserAdmin) before either
        // the load-vs-missing distinction or a permission check can leak
        // anything: both answer 404 Principal_NOT_FOUND.
        var outsider = client(clientA, "platform:messaging:process:view");

        var unknown = http.put("/api/principals/prn_doesnotexist99/roles", "{\"roles\":[]}", outsider);
        var real = http.put("/api/principals/" + userInB + "/roles", "{\"roles\":[]}", outsider);

        assertThat(unknown.statusCode()).as("unknown id").isEqualTo(404);
        assertThat(real.statusCode()).as("real, out-of-scope id — mutant: revert Access#requireUserAdmin's scope check").isEqualTo(404);
        assertThat(json(real).get("error").asText()).isEqualTo(json(unknown).get("error").asText());
    }

    @Test
    @DisplayName("PR-4: add/removeRole check scope BEFORE the idempotent early-return, closing the read-back IDOR")
    void addAndRemoveRoleCheckScopeBeforeTheIdempotentSkip() {
        // Before the fix, addRole/removeRole's idempotent skip (role already
        // held / never held) never reached AssignRoles' own scope check, so
        // a cross-tenant caller could "add" a role userInB already didn't
        // have and read back userInB's full PrincipalResponse for free.
        var adminOfA = client(clientA, "platform:iam:user:view,platform:iam:user:update,platform:iam:user:assign-roles");
        var add = http.post("/api/principals/" + userInB + "/roles", "{\"role\":\"platform:developer\"}", adminOfA);
        assertThat(add.statusCode()).as("mutant: remove the pre-idempotent-check Access#requireUserAdmin call").isEqualTo(404);
        var remove = http.delete("/api/principals/" + userInB + "/roles/platform:developer", adminOfA);
        assertThat(remove.statusCode()).isEqualTo(404);
    }

    // ── The tenant boundary on the mutations ────────────────────────────────

    @Test
    @DisplayName("PR-4: out-of-scope mutations answer 404, and the same administrator can still act within its own client")
    void mutationsAnswerNotFoundOutOfScopeAndStillWorkInScope() {
        // The role / application-access / developer-credential routes have no
        // coarse handler gate and load before authorising (a separate,
        // unruled-here ordering concern). What matters for PR-4: the
        // per-resource check (Access.requireUserAdmin) runs post-load and
        // tests the TARGET's home client — out of scope now answers the same
        // 404 a missing id would, never a distinguishing 403.
        //
        // Asserted rather than read, because "the check exists somewhere
        // downstream" is exactly the belief that turns into a tenant breach
        // when someone moves it.
        var adminOfA = client(clientA, "platform:iam:user:view,platform:iam:user:update,platform:iam:user:assign-roles");

        var roles = http.put("/api/principals/" + userInB + "/roles", "{\"roles\":[]}", adminOfA);
        assertThat(roles.statusCode()).as("roles, body was: %s", roles.body()).isEqualTo(404);
        assertThat(json(roles).get("error").asText()).isEqualTo("Principal_NOT_FOUND");

        var apps = http.put("/api/principals/" + userInB + "/application-access",
                "{\"applicationIds\":[]}", adminOfA);
        assertThat(apps.statusCode()).as("application access, body was: %s", apps.body()).isEqualTo(404);
        assertThat(json(apps).get("error").asText()).isEqualTo("Principal_NOT_FOUND");

        // developer-credential's own missing-id path never pre-loads (it
        // loads only inside the operation via Access.loadUser), so its own
        // spelling is "User_NOT_FOUND" — see Access#requireUserAdmin(Principal).
        var cred = http.post("/api/principals/" + userInB + "/developer-credential", null, adminOfA);
        assertThat(cred.statusCode()).as("developer credential, body was: %s", cred.body()).isEqualTo(404);
        assertThat(json(cred).get("error").asText()).isEqualTo("User_NOT_FOUND");

        // ...and the same administrator CAN do it inside its own client, so
        // the 404s above are the boundary and not a blanket denial.
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
