package io.flowcatalyst.platform.portalidentity.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalInviteEmailer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// `/api/portal-users*` end to end (spec `auth-identity.md` §5.7; lockfile
/// shapes): the invite/SSO branches of `POST`, the list shape, and
/// activate/deactivate/delete's messages. `PortalIdentityOperationsTest`
/// covers the cross-client 404 rule and the event/audit envelope; this
/// class is about the HTTP surface and the mail-seam branching.
@SuppressWarnings("deprecation")
class PortalUserApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static final ClientRepository clientRepo = new ClientRepository(TestPg.dataSource());
    private static final OAuthClientRepository oauthClientRepo =
            new OAuthClientRepository(TestPg.dataSource(), new io.flowcatalyst.platform.application.ApplicationRepository(TestPg.dataSource()));
    private static final IdentityProviderRepository identityProviderRepo = new IdentityProviderRepository(TestPg.dataSource());
    private static final EmailDomainMappingRepository emailDomainMappingRepo = new EmailDomainMappingRepository(TestPg.dataSource());
    private static final PortalIdentityRepository portalIdentityRepo = new PortalIdentityRepository(TestPg.dataSource());
    private static final io.flowcatalyst.platform.portalapp.PortalAppRepository portalAppRepo =
            new io.flowcatalyst.platform.portalapp.PortalAppRepository(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));

    /// Records every call instead of just logging, so a test can assert
    /// exactly which branch the handler took (spec §5.7's SSO vs. non-SSO
    /// split) — a `PortalInviteEmailer.logging()` fake would let mutant 6
    /// (a set-password invite sent on an SSO domain) through silently.
    private static final class RecordingEmailer implements PortalInviteEmailer {
        final List<String> ssoInvites = new CopyOnWriteArrayList<>();
        final List<String> passwordInvites = new CopyOnWriteArrayList<>();
        final List<String> inviteLinkCalls = new CopyOnWriteArrayList<>();

        @Override
        public void sendPortalInvite(PortalIdentity identity, String target) {
            passwordInvites.add(identity.id());
        }

        @Override
        public void sendPortalSsoInvite(String email, String target) {
            ssoInvites.add(email);
        }

        @Override
        public void sendPortalReset(String identityId, String email, String origin) {
        }

        @Override
        public String inviteLink(PortalIdentity identity, String target) {
            inviteLinkCalls.add(identity.id());
            return target + "?invite=" + identity.id();
        }
    }

    private static final RecordingEmailer EMAILER = new RecordingEmailer();
    private static TestHttp http;

    private static final String[] ANCHOR = {
            Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "ANCHOR"};

    @BeforeAll
    static void start() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost:8080", new JwtVerifier.RsaKeys(keys.publicKey())));
        var auth = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/api/*", auth);
            PortalUserApi.register(routes, new PortalUserApi.State(portalIdentityRepo, clientRepo, oauthClientRepo,
                    identityProviderRepo, portalAppRepo, uow, EMAILER));
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

    private static String testClient(String tag) {
        Client c = Client.create("Portal User Api " + tag, ClientIdentifier.parse("pua-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    /// A portal-flagged, active OAuth client (spec §5.7: `Ensure`'s redirect
    /// validation scans OAuth clients whose `portalClientId` is the target).
    private static void portalOAuthClient(String tag, String portalClientId, List<String> redirectUris) {
        OAuthClient c = OAuthClient.create("oc-" + tag + "-" + RUN, "Portal entry " + tag, ClientType.PUBLIC)
                .withRedirectUris(redirectUris)
                .withPortalAndApiAccess(portalClientId, false);
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(c, tx.dbTx());
            return null;
        });
    }

    /// An OIDC identity provider whose derived `allowedEmailDomains` covers
    /// `domain` (via a real `tnt_email_domain_mappings` row, spec §5.7).
    private static void ssoOwnedDomain(String tag, String domain) {
        IdentityProvider idp = IdentityProvider.create("idp-" + tag + "-" + RUN, "SSO " + tag, IdentityProviderType.OIDC);
        uow.inTransaction(tx -> {
            identityProviderRepo.persist(idp, tx.dbTx());
            return null;
        });
        EmailDomainMapping mapping = EmailDomainMapping.create(EmailDomain.parse(domain), idp.id(), ScopeType.ANCHOR);
        uow.inTransaction(tx -> {
            emailDomainMappingRepo.persist(mapping, tx.dbTx());
            return null;
        });
    }

    // ── POST /api/portal-users: non-SSO branch ──────────────────────────────

    @Test
    void ensureOnANonSsoDomainSendsASetPasswordInviteAndListsTheUser() {
        String clientId = testClient("invite");
        String email = "invite-" + RUN + "@example.com";

        var res = http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"" + email + "\"}", ANCHOR);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        var body = json(res);
        assertThat(body.propertyNames()).as("no ssoManaged on a non-SSO ensure")
                .containsExactlyInAnyOrder("identityId", "created", "invited", "hasPassword");
        assertThat(body.get("created").asBoolean()).isTrue();
        assertThat(body.get("invited").asBoolean()).isTrue();
        assertThat(body.get("hasPassword").asBoolean()).isFalse();
        String identityId = body.get("identityId").asText();
        assertThat(EMAILER.passwordInvites).contains(identityId);
        assertThat(EMAILER.ssoInvites).as("never an SSO invite here").doesNotContain(email);

        var list = json(http.get("/api/portal-users?clientId=" + clientId, ANCHOR));
        assertThat(list.get("portalUsers")).hasSize(1);
        var item = list.get("portalUsers").get(0);
        assertThat(item.get("identityId").asText()).isEqualTo(identityId);
        assertThat(item.get("email").asText()).isEqualTo(email);
        assertThat(item.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(item.get("source").asText()).isEqualTo("INVITE");
        assertThat(item.get("hasPassword").asBoolean()).isFalse();
        assertThat(item.has("lastLoginAt")).as("never logged in").isFalse();
    }

    @Test
    void ensureWithReturnInviteLinkOnANonSsoDomainReturnsALinkAndNeverSendsMail() {
        String clientId = testClient("invitelink");
        String email = "invitelink-" + RUN + "@example.com";

        var body = json(http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"" + email + "\",\"returnInviteLink\":true}", ANCHOR));
        String identityId = body.get("identityId").asText();
        assertThat(body.get("invited").asBoolean()).as("a link, not a sent mail").isFalse();
        assertThat(body.get("inviteUrl").asText()).as("no portal redirect registered, so target is null; the link path is still testable")
                .isEqualTo("null?invite=" + identityId);
        assertThat(EMAILER.inviteLinkCalls).contains(identityId);
        assertThat(EMAILER.passwordInvites).as("returnInviteLink never also sends mail").doesNotContain(identityId);
    }

    @Test
    void ensureValidatesTheRedirectUriAgainstThePortalClientsRegisteredUris() {
        String clientId = testClient("redirect");
        portalOAuthClient("redirect", clientId, List.of("https://portal.example.com/callback"));

        var ok = http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"redir-ok-" + RUN + "@example.com\","
                        + "\"redirectUri\":\"https://portal.example.com/callback\",\"returnInviteLink\":true}", ANCHOR);
        assertThat(ok.statusCode()).isEqualTo(200);
        String okId = json(ok).get("identityId").asText();
        assertThat(json(ok).get("inviteUrl").asText()).isEqualTo("https://portal.example.com/callback?invite=" + okId);

        var bad = http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"redir-bad-" + RUN + "@example.com\","
                        + "\"redirectUri\":\"https://evil.example.com/callback\"}", ANCHOR);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error").asText()).isEqualTo("REDIRECT_URI_INVALID");
    }

    @Test
    void ensureWithoutARedirectUriDefaultsToTheFirstNonWildcardPortalOrigin() {
        String clientId = testClient("default");
        portalOAuthClient("default", clientId, List.of("https://*.wild.example.com/cb", "https://app.example.com:8443/cb/deep"));

        var body = json(http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"default-" + RUN + "@example.com\",\"returnInviteLink\":true}", ANCHOR));
        String id = body.get("identityId").asText();
        assertThat(body.get("inviteUrl").asText()).as("the wildcard URI is skipped")
                .isEqualTo("https://app.example.com:8443/?invite=" + id);
    }

    // ── POST /api/portal-users: SSO branch (mutant 6) ───────────────────────

    /// Mutant 6: `POST /api/portal-users` sends a set-password invite on an
    /// SSO domain. Asserts BOTH the response shape (`ssoManaged`, never a
    /// password on the wire) AND — the part a mutant that merely fudges the
    /// response without touching the mail seam would still pass — that the
    /// set-password mailer was never invoked, only the SSO one.
    @Test
    void ensureOnAnSsoOwnedDomainNeverSendsASetPasswordInviteAndTheLinkIsThePortalOrigin() {
        String clientId = testClient("sso");
        String domain = "sso-" + RUN + ".example.com";
        String email = "person@" + domain;
        ssoOwnedDomain("sso", domain);
        portalOAuthClient("sso", clientId, List.of("https://sso-portal.example.com/cb"));

        var withLink = json(http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"" + email + "\",\"returnInviteLink\":true}", ANCHOR));
        assertThat(withLink.get("ssoManaged").asBoolean()).isTrue();
        assertThat(withLink.get("hasPassword").asBoolean()).as("SSO identities never get a password").isFalse();
        assertThat(withLink.get("inviteUrl").asText()).as("the portal origin, not a set-password link")
                .isEqualTo("https://sso-portal.example.com/");
        String identityId = withLink.get("identityId").asText();
        assertThat(EMAILER.passwordInvites).as("no set-password invite, ever").doesNotContain(identityId);
        assertThat(EMAILER.inviteLinkCalls).as("the set-password link builder was never called either").doesNotContain(identityId);

        var mailed = json(http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"person2@" + domain + "\"}", ANCHOR));
        assertThat(mailed.get("ssoManaged").asBoolean()).isTrue();
        assertThat(mailed.get("invited").asBoolean()).isTrue();
        assertThat(EMAILER.ssoInvites).contains("person2@" + domain);
        assertThat(EMAILER.passwordInvites).doesNotContain(mailed.get("identityId").asText());
    }

    // ── activate / deactivate / delete ──────────────────────────────────────

    @Test
    void activateDeactivateAndDeleteReturnTheirMessagesAndPersist() {
        String clientId = testClient("lifecycle");
        var created = json(http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"lifecycle-" + RUN + "@example.com\"}", ANCHOR));
        String id = created.get("identityId").asText();

        var deactivate = http.post("/api/portal-users/" + id + "/deactivate", "{\"clientId\":\"" + clientId + "\"}", ANCHOR);
        assertThat(deactivate.statusCode()).isEqualTo(200);
        assertThat(json(deactivate).get("message").asText()).isEqualTo("Portal user deactivated");
        assertThat(portalIdentityRepo.findById(id).orElseThrow().status().name()).isEqualTo("DISABLED");

        var activate = http.post("/api/portal-users/" + id + "/activate", "{\"clientId\":\"" + clientId + "\"}", ANCHOR);
        assertThat(activate.statusCode()).isEqualTo(200);
        assertThat(json(activate).get("message").asText()).isEqualTo("Portal user activated");
        assertThat(portalIdentityRepo.findById(id).orElseThrow().status().name()).isEqualTo("ACTIVE");

        var delete = http.delete("/api/portal-users/" + id + "?clientId=" + clientId, ANCHOR);
        assertThat(delete.statusCode()).isEqualTo(200);
        assertThat(json(delete).get("message").asText()).isEqualTo("Portal user deleted");
        assertThat(portalIdentityRepo.findById(id)).isEmpty();
    }

    // ── Authorization ────────────────────────────────────────────────────────

    @Test
    void everyRouteRequiresClientIdAndScope() {
        var noClientId = http.get("/api/portal-users", ANCHOR);
        assertThat(noClientId.statusCode()).isEqualTo(400);
        assertThat(json(noClientId).get("error").asText()).isEqualTo("CLIENT_ID_REQUIRED");

        var unauthenticated = http.get("/api/portal-users?clientId=clt_x");
        assertThat(unauthenticated.statusCode()).isEqualTo(403);
        assertThat(json(unauthenticated).get("error").asText()).isEqualTo("UNAUTHENTICATED");

        String clientId = testClient("authz");
        String[] otherClientScoped = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, "clt_someone_else",
                Authenticator.TEST_PERMISSIONS, "platform:iam:portal-user:manage,platform:iam:portal-user:view"};
        var forbidden = http.get("/api/portal-users?clientId=" + clientId, otherClientScoped);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json(forbidden).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");
    }
}
