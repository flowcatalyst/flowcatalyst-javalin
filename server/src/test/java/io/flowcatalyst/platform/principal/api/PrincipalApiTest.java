package io.flowcatalyst.platform.principal.api;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.passwordreset.ResetLinks;
import io.flowcatalyst.platform.passwordreset.ResetToken;
import io.flowcatalyst.platform.passwordreset.ResetTokenRepository;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.AnchorDomains;
import io.flowcatalyst.platform.principal.operations.DeveloperSecrets;
import io.flowcatalyst.platform.principal.InviteEmailer;
import io.flowcatalyst.platform.principal.MfaService;
import io.flowcatalyst.platform.principal.Notifier;
import io.flowcatalyst.platform.principal.PasswordResetEmailer;
import io.flowcatalyst.platform.publicapi.EmailTheme;
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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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

    /// A counting fake (`docs/spec/app-managed-invitations.md` §7): counts
    /// `sendInvite`/`inviteLink` calls separately so a test can assert which
    /// branch of the precedence fired, and can be told to throw on the next
    /// mint to pin the best-effort failure path. Also records the last
    /// redirect URI each method received, so a test can pin that a validated
    /// `inviteRedirectUri` (§1a) actually rides on the mint.
    private static final class CountingInviteEmailer implements InviteEmailer {
        final String link = "https://example.test/auth/set-password?token=fake-" + UUID.randomUUID();
        int sendInviteCalls;
        int inviteLinkCalls;
        RuntimeException throwOnInviteLink;
        String lastSendInviteRedirect;
        String lastInviteLinkRedirect;

        @Override
        public void sendInvite(Principal p, String redirectUri) {
            sendInviteCalls++;
            lastSendInviteRedirect = redirectUri;
        }

        @Override
        public String inviteLink(Principal p, String redirectUri) {
            inviteLinkCalls++;
            lastInviteLinkRedirect = redirectUri;
            if (throwOnInviteLink != null) throw throwOnInviteLink;
            return link;
        }

        void reset() {
            sendInviteCalls = 0;
            inviteLinkCalls = 0;
            throwOnInviteLink = null;
            lastSendInviteRedirect = null;
            lastInviteLinkRedirect = null;
        }
    }

    private static final class CountingNotifier implements Notifier {
        int accountCreatedCalls;

        @Override
        public void accountCreated(String email) {
            accountCreatedCalls++;
        }

        @Override
        public void twoFactorReset(String email) {
        }

        void reset() {
            accountCreatedCalls = 0;
        }
    }

    private static final CountingInviteEmailer INVITES = new CountingInviteEmailer();
    private static final CountingNotifier NOTIFIER = new CountingNotifier();

    private static String[] anchor() {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
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
                PasswordResetEmailer.notConfigured(), INVITES, NOTIFIER,
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

    /// T4 (`docs/spec/login-attempt-links.md`): a `SERVICE` principal's read
    /// carries `serviceAccountId` equal to the account it backs — the
    /// login-attempt detail dialog (F2) uses this to route a
    /// `DEVELOPER_TOKEN`/`SERVICE` row to the service-account detail screen —
    /// while a `USER` principal's read carries no such field at all. Mutant:
    /// drop the field, or read the wrong column.
    @Test
    @DisplayName("a SERVICE principal's read carries serviceAccountId; a USER principal's carries none")
    void serviceAccountIdIsCarriedOnlyByServicePrincipals() {
        String serviceAccountId = EntityType.SERVICE_ACCOUNT.generate();
        var service = Principal.newService(serviceAccountId, "Linked Service");
        UOW.inTransaction(tx -> { REPO.persist(service, tx.dbTx()); return null; });

        var serviceRead = json(http.get("/api/principals/" + service.id(), anchor()));
        assertThat(serviceRead.get("serviceAccountId").asText())
                .as("serviceAccountId must equal the linked account's own id").isEqualTo(serviceAccountId);

        var userRead = json(http.get("/api/principals/" + userInA, anchor()));
        assertThat(userRead.has("serviceAccountId")).as("a USER principal must not carry serviceAccountId").isFalse();
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

    // ── Security-fixes S1.1–S1.3: anchor is reach, never authority ──────────

    /// An anchor holding everything a user administrator might have EXCEPT a
    /// user-write code.
    private static String[] anchorWith(String permissions) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, permissions};
    }

    private static String seedRole(String app, String name, String... permissions) {
        var r = io.flowcatalyst.platform.role.Role.create(app, name, name).withPermissions(List.of(permissions));
        UOW.inTransaction(tx -> {
            new RoleRepository(TestPg.dataSource()).persist(r, tx.dbTx());
            return null;
        });
        return r.name();
    }

    private static List<String> rolesOf(String principalId) {
        return REPO.findById(principalId).orElseThrow().roleNames();
    }

    /// S1.1 (`Access.requireUserAdmin` no longer skips the permission for an
    /// anchor): every route that gates through it — roles PUT/POST/DELETE,
    /// application-access, send-password-reset, developer-credential set and
    /// revoke — refuses an anchor without a user-write code with
    /// `PERMISSION_REQUIRED`, and the target's roles are unchanged; the
    /// specific `USER_UPDATE` code (not the wildcard) admits it.
    @Test
    void anAnchorWithoutAUserWritePermissionIsRefusedEveryUserAdminRoute() {
        String role = seedRole("s11app" + RUN, "r");
        String target = createUser("s11", clientA);
        assertThat(http.post("/api/principals/" + target + "/roles", "{\"role\":\"" + role + "\"}", anchor()).statusCode()).isEqualTo(200);
        var noWrite = anchorWith("platform:iam:user:view,platform:iam:user:assign-roles,platform:iam:client-access:grant,"
                + "platform:iam:client-access:revoke,platform:iam:client-access:view,platform:iam:role:view");

        var calls = List.of(
                http.put("/api/principals/" + target + "/roles", "{\"roles\":[]}", noWrite),
                http.post("/api/principals/" + target + "/roles", "{\"role\":\"" + role + "\"}", noWrite),
                http.delete("/api/principals/" + target + "/roles/" + role, noWrite),
                http.put("/api/principals/" + target + "/application-access", "{\"applicationIds\":[]}", noWrite),
                http.post("/api/principals/" + target + "/send-password-reset", "", noWrite),
                http.post("/api/principals/" + target + "/developer-credential", "", noWrite),
                http.delete("/api/principals/" + target + "/developer-credential", noWrite));
        for (var r : calls) {
            assertThat(r.statusCode()).as(r.uri() + " " + r.body()).isEqualTo(403);
            assertThat(json(r).get("error").asText()).as(r.uri().toString()).isEqualTo("PERMISSION_REQUIRED");
        }
        assertThat(rolesOf(target)).as("neither the PUT nor the DELETE took effect").containsExactly(role);

        var put = http.put("/api/principals/" + target + "/roles", "{\"roles\":[]}", anchorWith("platform:iam:user:update"));
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(rolesOf(target)).isEmpty();
    }

    /// S1.2: the client-access routes and client association need their
    /// client-access permission at the anchor tier too. The observable
    /// effect: no grant row, the target's scope unchanged.
    @Test
    void clientAccessRoutesNeedTheirPermissionEvenForAnAnchor() {
        var partner = http.post("/api/principals",
                "{\"email\":\"s12p" + RUN + "@example.test\",\"scope\":\"PARTNER\",\"clientId\":\"" + clientA + "\"}", anchor());
        assertThat(partner.statusCode()).as(partner.body()).isEqualTo(201);
        String id = json(partner).get("id").asText();
        var noAccessPerms = anchorWith("platform:iam:user:view,platform:iam:user:create,platform:iam:user:update,"
                + "platform:iam:user:delete,platform:iam:user:assign-roles");
        var grants = new ClientAccessGrantRepository(TestPg.dataSource());

        var list = http.get("/api/principals/" + id + "/client-access", noAccessPerms);
        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(json(list).get("error").asText()).isEqualTo("PERMISSION_REQUIRED");
        var grant = http.post("/api/principals/" + id + "/client-access", "{\"clientId\":\"" + clientB + "\"}", noAccessPerms);
        assertThat(grant.statusCode()).as(grant.body()).isEqualTo(403);
        assertThat(grants.findByPrincipalAndClient(id, clientB)).as("no grant row").isEmpty();
        var assoc = http.put("/api/principals/" + id + "/client-association", "{\"clientId\":\"*\"}", noAccessPerms);
        assertThat(assoc.statusCode()).as(assoc.body()).isEqualTo(403);
        assertThat(REPO.findById(id).orElseThrow().scope().name()).as("not promoted to anchor").isEqualTo("PARTNER");

        assertThat(http.post("/api/principals/" + id + "/client-access", "{\"clientId\":\"" + clientB + "\"}",
                anchorWith("platform:iam:client-access:grant")).statusCode()).isEqualTo(200);
        assertThat(http.get("/api/principals/" + id + "/client-access", anchorWith("platform:iam:client-access:view")).statusCode())
                .isEqualTo(200);
        var revoke = http.delete("/api/principals/" + id + "/client-access/" + clientB, noAccessPerms);
        assertThat(revoke.statusCode()).isEqualTo(403);
        assertThat(grants.findByPrincipalAndClient(id, clientB)).as("revoke refused: grant still there").isPresent();
        assertThat(http.delete("/api/principals/" + id + "/client-access/" + clientB,
                anchorWith("platform:iam:client-access:revoke")).statusCode()).isEqualTo(204);
        assertThat(grants.findByPrincipalAndClient(id, clientB)).isEmpty();
    }

    /// S1.3 end to end through `POST /api/principals/sync`: a client admin of
    /// client A cannot sync a platform role onto itself, cannot set an anchor
    /// user's password hash, and cannot deactivate a principal in client B.
    @Test
    void aClientAdminsPrincipalSyncStaysInsideItsAuthority() {
        String superAdmin = seedRole("platform", "s13sa" + RUN, "platform:*:*:*");
        String self = createUser("s13self", clientA);
        String outOfReach = createUser("s13out", clientB);
        var anchorUser = http.post("/api/principals",
                "{\"email\":\"s13anchor" + RUN + "@example.test\",\"scope\":\"ANCHOR\",\"password\":\"correct-horse-battery\"}", anchor());
        assertThat(anchorUser.statusCode()).as(anchorUser.body()).isEqualTo(201);
        String anchorId = json(anchorUser).get("id").asText();
        String hashBefore = REPO.findById(anchorId).orElseThrow().userIdentity().passwordHash();
        var clientAdmin = self(self, "platform:iam:user:view,platform:iam:user:create,platform:iam:user:update,"
                + "platform:iam:user:delete,platform:iam:user:assign-roles");

        var escalate = http.post("/api/principals/sync", "{\"principals\":[{\"email\":\"s13self" + RUN + "@example.test\","
                + "\"name\":\"Me\",\"roles\":[\"" + superAdmin + "\"]}]}", clientAdmin);
        assertThat(escalate.statusCode()).as(escalate.body()).isEqualTo(403);
        assertThat(json(escalate).get("error").asText()).isEqualTo("PLATFORM_ROLE_FORBIDDEN");
        assertThat(rolesOf(self)).doesNotContain(superAdmin);

        var takeover = http.post("/api/principals/sync", "{\"principals\":[{\"email\":\"s13anchor" + RUN + "@example.test\","
                + "\"name\":\"Owned\",\"passwordHash\":\"$2y$10$attackercontrolledhashvalue\"}]}", clientAdmin);
        assertThat(takeover.statusCode()).as(takeover.body()).isEqualTo(403);
        assertThat(json(takeover).get("error").asText()).isEqualTo("SYNC_TARGET_FORBIDDEN");
        assertThat(REPO.findById(anchorId).orElseThrow().userIdentity().passwordHash()).isEqualTo(hashBefore);

        var deactivate = http.post("/api/principals/sync", "{\"principals\":[{\"email\":\"s13out" + RUN + "@example.test\","
                + "\"name\":\"Out\",\"active\":false}]}", clientAdmin);
        assertThat(deactivate.statusCode()).as(deactivate.body()).isEqualTo(403);
        assertThat(REPO.findById(outOfReach).orElseThrow().active()).isTrue();
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

    // ── App-managed invitations (spec app-managed-invitations.md §1, §7) ────

    @Test
    @DisplayName("default passwordless create-user sends the platform invite and returns no link")
    void defaultPasswordlessCreateUserSendsInvite() {
        INVITES.reset();
        NOTIFIER.reset();
        String email = "flag-default-" + RUN + "@example.test";
        var r = http.post("/api/principals/users",
                "{\"email\":\"" + email + "\",\"name\":\"Flag Default\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA + "\"}", anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(INVITES.sendInviteCalls).as("mutant: flags defaulting wrong way").isEqualTo(1);
        assertThat(INVITES.inviteLinkCalls).isZero();
        assertThat(NOTIFIER.accountCreatedCalls).isZero();
        assertThat(json(r).has("inviteLink")).as("mutant: inviteLink key present when nothing was minted").isFalse();
    }

    @Test
    @DisplayName("sendInvitation:false suppresses all platform mail, passwordless or with a password")
    void sendInvitationFalseSuppressesAllMail() {
        INVITES.reset();
        NOTIFIER.reset();
        String pwless = "flag-suppress-pwless-" + RUN + "@example.test";
        var r1 = http.post("/api/principals/users",
                "{\"email\":\"" + pwless + "\",\"name\":\"S1\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA + "\",\"sendInvitation\":false}", anchor());
        assertThat(r1.statusCode()).as(r1.body()).isEqualTo(200);
        assertThat(INVITES.sendInviteCalls).isZero();
        assertThat(NOTIFIER.accountCreatedCalls).as("mutant: the welcome escapes the suppression").isZero();

        String withPassword = "flag-suppress-pw-" + RUN + "@example.test";
        var r2 = http.post("/api/principals/users",
                "{\"email\":\"" + withPassword + "\",\"name\":\"S2\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                        + "\",\"password\":\"correct horse battery staple\",\"sendInvitation\":false}", anchor());
        assertThat(r2.statusCode()).as(r2.body()).isEqualTo(200);
        assertThat(INVITES.sendInviteCalls).isZero();
        assertThat(NOTIFIER.accountCreatedCalls).as("mutant: the welcome escapes the suppression").isZero();
    }

    @Test
    @DisplayName("returnInviteLink:true mints and returns the fake's link instead of mailing, with or without sendInvitation")
    void returnInviteLinkMintsInsteadOfMailing() {
        INVITES.reset();
        NOTIFIER.reset();
        String email1 = "flag-link-" + RUN + "@example.test";
        var r1 = http.post("/api/principals/users",
                "{\"email\":\"" + email1 + "\",\"name\":\"L1\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA + "\",\"returnInviteLink\":true}", anchor());
        assertThat(r1.statusCode()).as(r1.body()).isEqualTo(200);
        assertThat(json(r1).get("inviteLink").asText()).as("mutant: precedence inverted").isEqualTo(INVITES.link);
        assertThat(INVITES.sendInviteCalls).as("mutant: the platform mail also sent").isZero();
        assertThat(NOTIFIER.accountCreatedCalls).isZero();

        INVITES.reset();
        NOTIFIER.reset();
        String email2 = "flag-link-suppressed-" + RUN + "@example.test";
        var r2 = http.post("/api/principals/users",
                "{\"email\":\"" + email2 + "\",\"name\":\"L2\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                        + "\",\"returnInviteLink\":true,\"sendInvitation\":false}", anchor());
        assertThat(r2.statusCode()).as(r2.body()).isEqualTo(200);
        assertThat(json(r2).get("inviteLink").asText()).as("the platform's own invite is never sent on this branch, even when sendInvitation is true — and here it's false too")
                .isEqualTo(INVITES.link);
        assertThat(INVITES.sendInviteCalls).isZero();
        assertThat(NOTIFIER.accountCreatedCalls).isZero();
    }

    @Test
    @DisplayName("returnInviteLink:true with a password never mints; the welcome still sends")
    void returnInviteLinkWithPasswordNeverMints() {
        INVITES.reset();
        NOTIFIER.reset();
        String email = "flag-link-pw-" + RUN + "@example.test";
        var r = http.post("/api/principals/users",
                "{\"email\":\"" + email + "\",\"name\":\"LP\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                        + "\",\"password\":\"correct horse battery staple\",\"returnInviteLink\":true}", anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).has("inviteLink")).as("mutant: minting for a user with a password").isFalse();
        assertThat(INVITES.inviteLinkCalls).isZero();
        assertThat(NOTIFIER.accountCreatedCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("a mint failure is best-effort: 200, no link, and it never falls through to the platform's own mail")
    void mintFailureIsBestEffortAndNeverFallsThrough() {
        INVITES.reset();
        NOTIFIER.reset();
        INVITES.throwOnInviteLink = new RuntimeException("mint blew up");
        try {
            String email = "flag-link-fail-" + RUN + "@example.test";
            var r = http.post("/api/principals/users",
                    "{\"email\":\"" + email + "\",\"name\":\"LF\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA + "\",\"returnInviteLink\":true}", anchor());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            assertThat(json(r).has("inviteLink")).as("mutant: link leaks despite the mint failing").isFalse();
            assertThat(INVITES.sendInviteCalls).as("mutant: best-effort falling through to the mail").isZero();
        } finally {
            INVITES.throwOnInviteLink = null;
        }
    }

    @Test
    @DisplayName("POST /api/principals answers exactly {id} normally and {id, inviteLink} when the flag mints")
    void createPrincipalResponseShapeMatchesTheFlag() {
        INVITES.reset();
        NOTIFIER.reset();
        String email1 = "flag-cp-plain-" + RUN + "@example.test";
        var r1 = http.post("/api/principals", "{\"email\":\"" + email1 + "\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA + "\"}", anchor());
        assertThat(r1.statusCode()).isEqualTo(201);
        assertThat(json(r1).propertyNames()).as("mutant: null still emitted").containsExactly("id");

        INVITES.reset();
        NOTIFIER.reset();
        String email2 = "flag-cp-link-" + RUN + "@example.test";
        var r2 = http.post("/api/principals",
                "{\"email\":\"" + email2 + "\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA + "\",\"returnInviteLink\":true}", anchor());
        assertThat(r2.statusCode()).isEqualTo(201);
        assertThat(json(r2).propertyNames()).as("mutant: CreatedResponse still answered").containsExactly("id", "inviteLink");
        assertThat(json(r2).get("inviteLink").asText()).isEqualTo(INVITES.link);
    }

    @Test
    @DisplayName("returnInviteLink mints a real, redeemable 72h INVITE token via ResetLinks")
    void returnInviteLinkMintsARealRedeemableToken() {
        var tokens = new ResetTokenRepository(TestPg.dataSource());
        var links = new ResetLinks(tokens, mail -> { }, () -> EmailTheme.defaults("Acme"), "http://localhost:8080", Clock.systemUTC());
        var realState = new PrincipalApi.State(REPO,
                new ClientAccessGrantRepository(TestPg.dataSource()),
                new RoleRepository(TestPg.dataSource()),
                new ApplicationRepository(TestPg.dataSource()),
                new ClientConfigRepository(TestPg.dataSource()),
                CLIENTS,
                new EmailDomainMappingRepository(TestPg.dataSource()),
                new IdentityProviderRepository(TestPg.dataSource()),
                AnchorDomains.inDatabase(TestPg.dataSource()),
                PasswordResetEmailer.notConfigured(), links, Notifier.logging(),
                MfaService.notConfigured(), DeveloperSecrets.unconfigured(), UOW);
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        try (var h = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            PrincipalApi.register(routes, realState);
        })) {
            String email = "flag-real-link-" + RUN + "@example.test";
            var r = h.post("/api/principals/users",
                    "{\"email\":\"" + email + "\",\"name\":\"RealLink\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                            + "\",\"returnInviteLink\":true}", anchor());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            String link = json(r).get("inviteLink").asText();
            assertThat(link).as("mutant: interface method not minting").contains("/auth/set-password?token=");
            String raw = link.substring(link.indexOf("token=") + "token=".length());
            var token = tokens.findByHash(ResetToken.hash(raw)).orElseThrow(() -> new AssertionError("no token stored for the returned link"));
            assertThat(token.purpose()).as("mutant: interface method not minting").isEqualTo(ResetToken.Purpose.INVITE);
            assertThat(token.isExpired(Instant.now())).isFalse();
        }
    }

    // ── Bulk import: a per-row refusal is an outcome, never a thrown error ───

    @Test
    @DisplayName("bulk import by a client administrator: an unknown role fails that row with the role check's message and creates no user")
    void bulkImportRecordsAnUnassignableRoleAsARowError() {
        String email = "bulk-badrole-" + RUN + "@example.test";
        String role = "no-such-role-" + RUN;
        var r = http.post("/api/principals/bulk-import",
                "{\"clientId\":\"" + clientA + "\",\"users\":[{\"name\":\"Bulk\",\"email\":\"" + email
                        + "\",\"roles\":[\"" + role + "\"]}]}", client(clientA, "platform:*:*:*"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var body = json(r);
        assertThat(body.get("failed").asInt()).as("mutant: the row refusal escaped as a 400/403").isEqualTo(1);
        assertThat(body.get("created").asInt()).isZero();
        var row = body.get("results").get(0);
        assertThat(row.get("status").asText()).isEqualTo("error");
        assertThat(row.get("message").asText()).as("mutant: outcome message lost").isEqualTo("role not found: " + role);
        assertThat(REPO.findByEmail(email)).as("no user for a refused row").isEmpty();
    }

    // ── inviteRedirectUri validation (spec app-managed-invitations.md §1a, §7) ──

    @Test
    @DisplayName("an absolute https URL (the application's own page) rides on the minted link, trimmed")
    void inviteRedirectUriRidesOnTheMintedLink() {
        INVITES.reset();
        NOTIFIER.reset();
        String uri = "https://acme.app-" + RUN + ".test/";
        String email = "redirect-link-" + RUN + "@example.test";
        var r = http.post("/api/principals/users",
                "{\"email\":\"" + email + "\",\"name\":\"RW\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                        + "\",\"returnInviteLink\":true,\"inviteRedirectUri\":\"  " + uri + "  \"}", anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("inviteLink").asText()).as("mutant: redirect dropped on the mint path").isEqualTo(INVITES.link);
        assertThat(INVITES.lastInviteLinkRedirect).as("mutant: redirect not trimmed or dropped").isEqualTo(uri);
    }

    @Test
    @DisplayName("inviteRedirectUri also rides on the platform's own invite mail when returnInviteLink is not set")
    void inviteRedirectUriRidesOnThePlatformInviteMail() {
        INVITES.reset();
        NOTIFIER.reset();
        String uri = "http://localhost:5173/welcome?src=invite";
        String email = "redirect-mail-" + RUN + "@example.test";
        var r = http.post("/api/principals/users",
                "{\"email\":\"" + email + "\",\"name\":\"RM\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                        + "\",\"inviteRedirectUri\":\"" + uri + "\"}", anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(INVITES.sendInviteCalls).isEqualTo(1);
        assertThat(INVITES.lastSendInviteRedirect).as("mutant: redirect dropped on the mail path; plain http refused").isEqualTo(uri);
    }

    @Test
    @DisplayName("a real ResetLinks mint with a redirect stores it on the token row")
    void realResetLinksMintStoresTheRedirectOnTheToken() {
        var tokens = new ResetTokenRepository(TestPg.dataSource());
        var links = new ResetLinks(tokens, mail -> { }, () -> EmailTheme.defaults("Acme"), "http://localhost:8080", Clock.systemUTC());
        var realState = new PrincipalApi.State(REPO,
                new ClientAccessGrantRepository(TestPg.dataSource()),
                new RoleRepository(TestPg.dataSource()),
                new ApplicationRepository(TestPg.dataSource()),
                new ClientConfigRepository(TestPg.dataSource()),
                CLIENTS,
                new EmailDomainMappingRepository(TestPg.dataSource()),
                new IdentityProviderRepository(TestPg.dataSource()),
                AnchorDomains.inDatabase(TestPg.dataSource()),
                PasswordResetEmailer.notConfigured(), links, Notifier.logging(),
                MfaService.notConfigured(), DeveloperSecrets.unconfigured(), UOW);
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        try (var h = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            PrincipalApi.register(routes, realState);
        })) {
            String uri = "https://acme.app-" + RUN + ".test/";
            String email = "redirect-real-token-" + RUN + "@example.test";
            var r = h.post("/api/principals/users",
                    "{\"email\":\"" + email + "\",\"name\":\"RT\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                            + "\",\"returnInviteLink\":true,\"inviteRedirectUri\":\"" + uri + "\"}", anchor());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            String link = json(r).get("inviteLink").asText();
            String raw = link.substring(link.indexOf("token=") + "token=".length());
            var token = tokens.findByHash(ResetToken.hash(raw)).orElseThrow(() -> new AssertionError("no token stored for the returned link"));
            assertThat(token.redirectUri()).as("mutant: ResetLinks passing null instead of the redirect").isEqualTo(uri);
        }
    }

    @Test
    @DisplayName("every malformed shape is 400 INVITE_REDIRECT_URI_INVALID and creates no user")
    void malformedInviteRedirectUriIsRefusedBeforeAnyWrite() {
        record Case(String label, String uri) {
        }
        List<Case> cases = List.of(
                new Case("a relative path", "/dashboard"),
                new Case("no scheme", "app.example.test/home"),
                new Case("a scheme-relative URL", "//evil.test/home"),
                new Case("javascript:", "javascript:alert(1)"),
                new Case("data:", "data:text/html,hi"),
                new Case("a non-web scheme", "ftp://files.example.test/"),
                new Case("embedded credentials", "https://trusted@evil.test/"),
                new Case("a scheme without a host", "https:///path"));
        int i = 0;
        for (Case c : cases) {
            String email = "redirect-bad-" + (i++) + "-" + RUN + "@example.test";
            var r = http.post("/api/principals/users",
                    "{\"email\":\"" + email + "\",\"name\":\"Bad\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                            + "\",\"inviteRedirectUri\":\"" + c.uri() + "\"}", anchor());
            assertThat(r.statusCode()).as("%s: body was %s", c.label(), r.body()).isEqualTo(400);
            assertThat(json(r).get("error").asText()).as(c.label()).isEqualTo("INVITE_REDIRECT_URI_INVALID");
            assertThat(REPO.findByEmail(email)).as("%s: no user must be created", c.label()).isEmpty();
        }
    }

    @Test
    @DisplayName("a blank inviteRedirectUri is treated as absent, not as a URI")
    void blankInviteRedirectUriIsTreatedAsAbsent() {
        INVITES.reset();
        NOTIFIER.reset();
        String email = "redirect-blank-" + RUN + "@example.test";
        var r = http.post("/api/principals/users",
                "{\"email\":\"" + email + "\",\"name\":\"Blank\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                        + "\",\"returnInviteLink\":true,\"inviteRedirectUri\":\"  \"}", anchor());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(INVITES.lastInviteLinkRedirect).as("mutant: blank treated as a URI").isNull();
    }

    @Test
    @DisplayName("POST /api/principals also validates inviteRedirectUri: a javascript: URL is 400 and creates no user")
    void createPrincipalAlsoValidatesInviteRedirectUri() {
        String email = "redirect-createprincipal-bad-" + RUN + "@example.test";
        var r = http.post("/api/principals",
                "{\"email\":\"" + email + "\",\"scope\":\"CLIENT\",\"clientId\":\"" + clientA
                        + "\",\"returnInviteLink\":true,\"inviteRedirectUri\":\"javascript:alert(1)\"}", anchor());
        assertThat(r.statusCode()).as("mutant: the flag only wired on /users — body was %s", r.body()).isEqualTo(400);
        assertThat(json(r).get("error").asText()).isEqualTo("INVITE_REDIRECT_URI_INVALID");
        assertThat(REPO.findByEmail(email)).isEmpty();
    }
}
