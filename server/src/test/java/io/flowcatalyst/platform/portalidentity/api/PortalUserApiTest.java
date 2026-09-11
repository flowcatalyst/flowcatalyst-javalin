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
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppCode;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalidentity.PortalAppGrant;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// `/api/portal-users*` end to end (spec `auth-identity.md` §5.7;
/// `portal-apps.md` §4.1-§4.3; lockfile shapes): the invite/SSO branches of
/// `POST`, the search/list shape, grant/revoke, and
/// activate/deactivate/delete's messages. `PortalIdentityOperationsTest`
/// covers the cross-client 404 rule and the event/audit envelope; this
/// class is about the HTTP surface, the mail-seam branching and pagination.
@SuppressWarnings("deprecation")
class PortalUserApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static final ClientRepository clientRepo = new ClientRepository(TestPg.dataSource());
    private static final OAuthClientRepository oauthClientRepo =
            new OAuthClientRepository(TestPg.dataSource(), new io.flowcatalyst.platform.application.ApplicationRepository(TestPg.dataSource()));
    private static final IdentityProviderRepository identityProviderRepo = new IdentityProviderRepository(TestPg.dataSource());
    private static final EmailDomainMappingRepository emailDomainMappingRepo = new EmailDomainMappingRepository(TestPg.dataSource());
    private static final PortalIdentityRepository portalIdentityRepo = new PortalIdentityRepository(TestPg.dataSource());
    private static final PortalAppRepository portalAppRepo = new PortalAppRepository(TestPg.dataSource());
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

    private static PortalApp testApp(String clientId, String code) {
        PortalApp a = PortalApp.create(clientId, PortalAppCode.parse(code), "App " + code, null);
        uow.inTransaction(tx -> {
            portalAppRepo.persist(a, tx.dbTx());
            return null;
        });
        return a;
    }

    /// A portal-flagged, active OAuth client (spec §5.7: `Ensure`'s redirect
    /// validation scans OAuth clients whose `portalClientId` is the target).
    private static OAuthClient portalOAuthClient(String tag, String portalClientId, List<String> redirectUris) {
        OAuthClient c = OAuthClient.create("oc-" + tag + "-" + RUN, "Portal entry " + tag, ClientType.PUBLIC)
                .withRedirectUris(redirectUris)
                .withPortalAndApiAccess(portalClientId, false);
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c;
    }

    /// Same as [#portalOAuthClient] but with an explicit `clientName` —
    /// `OAuthClientRepository.findAll` orders by `client_name`, so a test
    /// pinning the app-first REORDERING (rather than the DB's natural
    /// alphabetical order) must control the name itself: the naive helper's
    /// fixed "Portal entry " prefix would otherwise sort one client first no
    /// matter what the handler does, making the reordering mutant undetectable.
    /// `OAuthClient.portalAppId` has no wither yet (unit B's DTO/setter is
    /// not landed), so [#namedPortalOAuthClientForApp] rebuilds the record
    /// with it set, through the public canonical constructor, and persists
    /// that directly.
    private static OAuthClient namedPortalOAuthClient(String name, String tag, String portalClientId, List<String> redirectUris) {
        OAuthClient c = OAuthClient.create("oc-" + tag + "-" + RUN, name, ClientType.PUBLIC)
                .withRedirectUris(redirectUris)
                .withPortalAndApiAccess(portalClientId, false);
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c;
    }

    private static OAuthClient namedPortalOAuthClientForApp(String name, String tag, String portalClientId, String appId, List<String> redirectUris) {
        OAuthClient base = OAuthClient.create("oc-" + tag + "-" + RUN, name, ClientType.PUBLIC)
                .withRedirectUris(redirectUris)
                .withPortalAndApiAccess(portalClientId, false);
        OAuthClient withApp = new OAuthClient(base.id(), base.clientId(), base.clientName(), base.clientType(),
                base.secretRef(), base.previousSecretRef(), base.previousSecretExpiresAt(), base.previousSecretLastUsedAt(),
                base.redirectUris(), base.postLogoutRedirectUris(), base.grantTypes(), base.defaultScopes(),
                base.allowedOrigins(), base.applicationIds(), base.pkceRequired(), base.active(), base.principalId(),
                base.portalClientId(), appId, base.apiAccess(), base.createdAt(), base.updatedAt());
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(withApp, tx.dbTx());
            return null;
        });
        return withApp;
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

    private static String ensureUser(String clientId, String email) {
        return json(http.post("/api/portal-users", "{\"clientId\":\"" + clientId + "\",\"email\":\"" + email + "\"}", ANCHOR))
                .get("identityId").asText();
    }

    private static String[] clientScoped(String clientId, String permissions) {
        return new String[]{
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(), Authenticator.TEST_SCOPE, "CLIENT",
                Authenticator.TEST_CLIENTS, clientId, Authenticator.TEST_PERMISSIONS, permissions};
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
        assertThat(body.propertyNames()).as("no ssoManaged/portalAppCode on a plain non-SSO ensure")
                .containsExactlyInAnyOrder("identityId", "created", "invited", "hasPassword", "state");
        assertThat(body.get("created").asBoolean()).isTrue();
        assertThat(body.get("invited").asBoolean()).isTrue();
        assertThat(body.get("hasPassword").asBoolean()).isFalse();
        assertThat(body.get("state").asText()).isEqualTo("INVITED");
        String identityId = body.get("identityId").asText();
        assertThat(EMAILER.passwordInvites).contains(identityId);
        assertThat(EMAILER.ssoInvites).as("never an SSO invite here").doesNotContain(email);

        var list = json(http.get("/api/portal-users?clientId=" + clientId, ANCHOR));
        assertThat(list.get("portalUsers")).hasSize(1);
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("page").asInt()).isEqualTo(0);
        assertThat(list.get("size").asInt()).isEqualTo(100);
        var item = list.get("portalUsers").get(0);
        assertThat(item.get("identityId").asText()).isEqualTo(identityId);
        assertThat(item.get("email").asText()).isEqualTo(email);
        assertThat(item.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(item.get("state").asText()).isEqualTo("INVITED");
        assertThat(item.get("source").asText()).isEqualTo("INVITE");
        assertThat(item.get("hasPassword").asBoolean()).isFalse();
        assertThat(item.get("apps")).as("always an array, even empty").isEmpty();
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

    /// Mutant: the app-first ordering of a client's portal OAuth clients is
    /// dropped (registration order is used unmodified). Pins that the
    /// resolved app's OWN OAuth client wins the default even though it was
    /// registered SECOND — a mutant that just picks the first-registered
    /// client would answer the legacy client's origin instead.
    @Test
    void ensureOrdersTheResolvedAppsOwnOAuthClientsFirstWhenPickingTheDefaultRedirect() {
        String clientId = testClient("app-first");
        PortalApp app = testApp(clientId, "appfirst");
        // Names deliberately sort the LEGACY (non-app) client first alphabetically —
        // `OAuthClientRepository.findAll` orders by `client_name` — so only the
        // handler's own app-first reordering, not the DB's natural order, can make
        // the app-owned client win the default.
        namedPortalOAuthClient("AAA Legacy Entry", "legacy", clientId, List.of("https://legacy.example.com/cb"));
        namedPortalOAuthClientForApp("ZZZ App Owned Entry", "appowned", clientId, app.id(), List.of("https://app-specific.example.com/cb"));

        var body = json(http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"appfirst-" + RUN + "@example.com\","
                        + "\"portalAppCode\":\"" + app.code() + "\",\"returnInviteLink\":true}", ANCHOR));
        assertThat(body.get("inviteUrl").asText())
                .as("the app-owned client's origin wins even though it was registered second")
                .isEqualTo("https://app-specific.example.com/?invite=" + body.get("identityId").asText());
    }

    // ── POST /api/portal-users: portalAppCode (spec §9.4) ───────────────────

    /// §9.4 exactly.
    @Test
    void ensureWithAPortalAppCodeNormalisesGrantsItAndSetsA72HourInviteExpiry() {
        String clientId = testClient("suppliers");
        PortalApp app = testApp(clientId, "suppliers");
        Instant before = Instant.now();

        var body = json(http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"supplier-" + RUN + "@example.com\",\"portalAppCode\":\"SUPPLIERS\"}", ANCHOR));
        assertThat(body.get("portalAppCode").asText()).isEqualTo("suppliers");
        assertThat(body.get("state").asText()).isEqualTo("INVITED");
        String id = body.get("identityId").asText();

        PortalIdentity reloaded = portalIdentityRepo.findById(id).orElseThrow();
        assertThat(reloaded.apps()).extracting(PortalAppGrant::appId).containsExactly(app.id());
        assertThat(reloaded.inviteExpiresAt()).as("the password-branch 72h expiry").isNotNull();
        assertThat(reloaded.inviteExpiresAt()).isAfter(before.plus(Duration.ofHours(71)));
        assertThat(reloaded.inviteExpiresAt()).isBefore(before.plus(Duration.ofHours(73)));

        var listed = json(http.get("/api/portal-users?clientId=" + clientId, ANCHOR));
        var item = listed.get("portalUsers").get(0);
        assertThat(item.get("apps")).hasSize(1);
        assertThat(item.get("apps").get(0).get("code").asText()).isEqualTo("suppliers");
        assertThat(item.has("inviteExpiresAt")).isTrue();
    }

    @Test
    void ensureWithAnUnknownPortalAppCodeIs404() {
        String clientId = testClient("ensure-unknown-app");
        var res = http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"unknown-app-" + RUN + "@example.com\",\"portalAppCode\":\"doesnotexist\"}", ANCHOR);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(json(res).get("error").asText()).isEqualTo("PortalApp_NOT_FOUND");
    }

    /// Mutant: `markInvited` is skipped on the password branch. Pins that
    /// `invitedAt`/`inviteExpiresAt` are actually written to the row — the
    /// response alone can't catch a mutant that drops the repository call.
    @Test
    void ensureOnThePasswordBranchMarksTheRowInvitedWithA72HourExpiry() {
        String clientId = testClient("mark-invited");
        Instant before = Instant.now();
        String id = ensureUser(clientId, "mark-invited-" + RUN + "@example.com");

        PortalIdentity reloaded = portalIdentityRepo.findById(id).orElseThrow();
        assertThat(reloaded.invitedAt()).as("markInvited was actually called").isNotNull();
        assertThat(reloaded.inviteExpiresAt()).isNotNull();
        assertThat(reloaded.inviteExpiresAt()).isAfter(before.plus(Duration.ofHours(71)));
        assertThat(reloaded.inviteExpiresAt()).isBefore(before.plus(Duration.ofHours(73)));
    }

    // ── POST /api/portal-users: SSO branch (mutant 6) ───────────────────────

    /// Mutant 6: `POST /api/portal-users` sends a set-password invite on an
    /// SSO domain. Asserts BOTH the response shape (`ssoManaged`, never a
    /// password on the wire) AND — the part a mutant that merely fudges the
    /// response without touching the mail seam would still pass — that the
    /// set-password mailer was never invoked, only the SSO one. Also pins
    /// the no-expiry mark-invited (spec §4.1 step 4).
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
        PortalIdentity afterLink = portalIdentityRepo.findById(identityId).orElseThrow();
        assertThat(afterLink.invitedAt()).as("pending + a returned link ⇒ marked invited").isNotNull();
        assertThat(afterLink.inviteExpiresAt()).as("an SSO invite never expires").isNull();

        var mailed = json(http.post("/api/portal-users",
                "{\"clientId\":\"" + clientId + "\",\"email\":\"person2@" + domain + "\"}", ANCHOR));
        assertThat(mailed.get("ssoManaged").asBoolean()).isTrue();
        assertThat(mailed.get("invited").asBoolean()).isTrue();
        assertThat(EMAILER.ssoInvites).contains("person2@" + domain);
        assertThat(EMAILER.passwordInvites).doesNotContain(mailed.get("identityId").asText());
    }

    // ── GET /api/portal-users: search (spec §9.3) ───────────────────────────

    /// §9.3 exactly: prefix match, case-insensitive on email, LIKE-escaped
    /// underscore/percent, no substring match.
    @Test
    void searchMatchesByCaseInsensitivePrefixOnEmailAndNameWithLikeEscaping() {
        String clientId = testClient("search");
        ensureUser(clientId, "pat.jones@example.com");
        String jonasId = ensureUser(clientId, "person2@example.com");
        // Give person2 a display name via a re-Ensure with a name.
        json(http.post("/api/portal-users", "{\"clientId\":\"" + clientId + "\",\"email\":\"person2@example.com\",\"name\":\"Jonas Smith\"}", ANCHOR));
        ensureUser(clientId, "under_score@example.com");

        assertThat(searchEmails(clientId, "PAT")).as("email prefix, case-insensitive").containsExactly("pat.jones@example.com");
        assertThat(searchEmails(clientId, "jonas")).as("name prefix").containsExactly("person2@example.com");
        assertThat(json(searchRaw(clientId, "jones")).get("portalUsers")).as("substring, not prefix — no match").isEmpty();
        assertThat(searchEmails(clientId, "under_")).as("escaped underscore is literal").containsExactly("under_score@example.com");
        assertThat(json(searchRaw(clientId, "u%")).get("portalUsers")).as("escaped percent is literal, matches nothing").isEmpty();
        assertThat(jonasId).isNotBlank();
    }

    private static HttpResponse<String> searchRaw(String clientId, String q) {
        return http.get("/api/portal-users?clientId=" + clientId + "&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8), ANCHOR);
    }

    private static List<String> searchEmails(String clientId, String q) {
        var items = new ArrayList<String>();
        json(searchRaw(clientId, q)).get("portalUsers").forEach(n -> items.add(n.get("email").asText()));
        return items;
    }

    @Test
    void searchFiltersByPortalAppCodeAndPaginates() {
        String clientId = testClient("search-app");
        PortalApp app = testApp(clientId, "search-app");
        String granted = ensureUser(clientId, "granted-" + RUN + "@example.com");
        ensureUser(clientId, "ungranted-" + RUN + "@example.com");
        http.post("/api/portal-users/" + granted + "/apps", "{\"clientId\":\"" + clientId + "\",\"portalAppCode\":\"" + app.code() + "\"}", ANCHOR);

        var filtered = json(http.get("/api/portal-users?clientId=" + clientId + "&portalAppCode=" + app.code(), ANCHOR));
        assertThat(filtered.get("portalUsers")).hasSize(1);
        assertThat(filtered.get("portalUsers").get(0).get("identityId").asText()).isEqualTo(granted);

        var unknown = http.get("/api/portal-users?clientId=" + clientId + "&portalAppCode=doesnotexist", ANCHOR);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").asText()).isEqualTo("PortalApp_NOT_FOUND");
    }

    /// §9.3: page 1 / size 2 has 1 row, total 3.
    @Test
    void paginationClampsPageAndSizeAndReportsTotal() {
        String clientId = testClient("clamp");
        for (int i = 0; i < 3; i++) {
            ensureUser(clientId, "clamp" + i + "-" + RUN + "@example.com");
        }

        var page1 = json(http.get("/api/portal-users?clientId=" + clientId + "&page=1&size=2", ANCHOR));
        assertThat(page1.get("portalUsers")).hasSize(1);
        assertThat(page1.get("total").asLong()).isEqualTo(3);
        assertThat(page1.get("page").asInt()).isEqualTo(1);
        assertThat(page1.get("size").asInt()).isEqualTo(2);

        var negPage = json(http.get("/api/portal-users?clientId=" + clientId + "&page=-5", ANCHOR));
        assertThat(negPage.get("page").asInt()).as("negative page clamps to 0").isEqualTo(0);

        var zeroSize = json(http.get("/api/portal-users?clientId=" + clientId + "&size=0", ANCHOR));
        assertThat(zeroSize.get("size").asInt()).as("size <= 0 clamps to 100").isEqualTo(100);

        var hugeSize = json(http.get("/api/portal-users?clientId=" + clientId + "&size=5000", ANCHOR));
        assertThat(hugeSize.get("size").asInt()).as("size caps at 1000").isEqualTo(1000);

        var defaultSize = json(http.get("/api/portal-users?clientId=" + clientId, ANCHOR));
        assertThat(defaultSize.get("size").asInt()).as("default size is 100, never 20 or 50").isEqualTo(100);
    }

    /// State `INVITE_EXPIRED`: an identity whose `inviteExpiresAt` is in the past.
    @Test
    void listReportsInviteExpiredWhenTheInviteHasLapsed() {
        String clientId = testClient("expired");
        String id = ensureUser(clientId, "expired-" + RUN + "@example.com");
        portalIdentityRepo.markInvited(id, Instant.now().minus(Duration.ofDays(4)), Instant.now().minus(Duration.ofHours(1)));

        var list = json(http.get("/api/portal-users?clientId=" + clientId, ANCHOR));
        assertThat(list.get("portalUsers").get(0).get("state").asText()).isEqualTo("INVITE_EXPIRED");
    }

    /// `apps` falls back to the app id for `code`/`name` when the granted
    /// app can't be resolved (spec §4.2). The live schema FK-cascades a
    /// grant row away the moment its app is deleted, so this case cannot be
    /// reproduced end to end through the API — it is exercised directly
    /// against `PortalUserListItem.from`, the exact seam the fallback lives in.
    @Test
    void listItemFallsBackToTheAppIdWhenAGrantedAppIsNotInTheResolvedMap() {
        Instant now = Instant.now();
        PortalIdentity identity = PortalIdentity.create("clt_dangling", "dangling@example.com", null,
                        io.flowcatalyst.platform.portalidentity.PortalIdentitySource.INVITE)
                .grant("pta_missing00000000", io.flowcatalyst.platform.portalidentity.PortalAppGrantSource.ADMIN);

        var item = PortalUserApi.PortalUserListItem.from(identity, java.util.Map.of(), now);
        assertThat(item.apps()).hasSize(1);
        var ref = item.apps().get(0);
        assertThat(ref.id()).isEqualTo("pta_missing00000000");
        assertThat(ref.code()).as("falls back to the app id").isEqualTo("pta_missing00000000");
        assertThat(ref.name()).as("falls back to the app id").isEqualTo("pta_missing00000000");
    }

    // ── Grant / revoke (spec §4.3) ───────────────────────────────────────────

    @Test
    void grantRoutePersistsAndRevokeRouteRemovesTheGrant() {
        String clientId = testClient("grant-route");
        PortalApp app = testApp(clientId, "grant-route");
        String id = ensureUser(clientId, "grant-route-" + RUN + "@example.com");

        var grant = http.post("/api/portal-users/" + id + "/apps",
                "{\"clientId\":\"" + clientId + "\",\"portalAppCode\":\"" + app.code() + "\"}", ANCHOR);
        assertThat(grant.statusCode()).isEqualTo(200);
        assertThat(json(grant).get("message").asText()).isEqualTo("Portal app access granted");
        assertThat(portalIdentityRepo.findById(id).orElseThrow().apps())
                .extracting(PortalAppGrant::appId).containsExactly(app.id());

        // Mutant: revoke doesn't persist — this reload is the assertion that
        // actually kills it; the HTTP 200 alone would still pass.
        var revoke = http.delete("/api/portal-users/" + id + "/apps/" + app.code() + "?clientId=" + clientId, ANCHOR);
        assertThat(revoke.statusCode()).isEqualTo(200);
        assertThat(json(revoke).get("message").asText()).isEqualTo("Portal app access revoked");
        assertThat(portalIdentityRepo.findById(id).orElseThrow().apps()).as("the grant row is gone").isEmpty();
    }

    @Test
    void grantRouteRejectsAnInactiveAppButRevokeDoesNotCheckActive() {
        String clientId = testClient("grant-inactive");
        PortalApp app = testApp(clientId, "grant-inactive").update(null, null, false);
        uow.inTransaction(tx -> {
            portalAppRepo.persist(app, tx.dbTx());
            return null;
        });
        String id = ensureUser(clientId, "grant-inactive-" + RUN + "@example.com");

        var res = http.post("/api/portal-users/" + id + "/apps",
                "{\"clientId\":\"" + clientId + "\",\"portalAppCode\":\"" + app.code() + "\"}", ANCHOR);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(json(res).get("error").asText()).isEqualTo("PORTAL_APP_INACTIVE");
        assertThat(portalIdentityRepo.findById(id).orElseThrow().apps()).isEmpty();

        // Revoking an inactive (or never-granted) app is fine — no active check on revoke.
        var revoke = http.delete("/api/portal-users/" + id + "/apps/" + app.code() + "?clientId=" + clientId, ANCHOR);
        assertThat(revoke.statusCode()).isEqualTo(200);
    }

    @Test
    void grantAndRevokeOfAnUnknownAppCodeAre404() {
        String clientId = testClient("grant-revoke-404");
        String id = ensureUser(clientId, "grant-revoke-404-" + RUN + "@example.com");

        var grant = http.post("/api/portal-users/" + id + "/apps",
                "{\"clientId\":\"" + clientId + "\",\"portalAppCode\":\"doesnotexist\"}", ANCHOR);
        assertThat(grant.statusCode()).isEqualTo(404);
        assertThat(json(grant).get("error").asText()).isEqualTo("PortalApp_NOT_FOUND");

        var revoke = http.delete("/api/portal-users/" + id + "/apps/doesnotexist?clientId=" + clientId, ANCHOR);
        assertThat(revoke.statusCode()).isEqualTo(404);
        assertThat(json(revoke).get("error").asText()).isEqualTo("PortalApp_NOT_FOUND");
    }

    /// A view-only caller may list but not grant/revoke; a manage caller can do both.
    @Test
    void viewOnlyCallerCanListButNotGrantOrRevoke() {
        String clientId = testClient("view-only");
        PortalApp app = testApp(clientId, "view-only");
        String id = ensureUser(clientId, "view-only-" + RUN + "@example.com");

        String[] viewOnly = clientScoped(clientId, "platform:iam:portal-user:view");
        var list = http.get("/api/portal-users?clientId=" + clientId, viewOnly);
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);

        var grant = http.post("/api/portal-users/" + id + "/apps",
                "{\"clientId\":\"" + clientId + "\",\"portalAppCode\":\"" + app.code() + "\"}", viewOnly);
        assertThat(grant.statusCode()).isEqualTo(403);

        var revoke = http.delete("/api/portal-users/" + id + "/apps/" + app.code() + "?clientId=" + clientId, viewOnly);
        assertThat(revoke.statusCode()).isEqualTo(403);

        String[] manage = clientScoped(clientId, "platform:iam:portal-user:manage");
        var grantOk = http.post("/api/portal-users/" + id + "/apps",
                "{\"clientId\":\"" + clientId + "\",\"portalAppCode\":\"" + app.code() + "\"}", manage);
        assertThat(grantOk.statusCode()).isEqualTo(200);
    }

    // ── activate / deactivate / delete ──────────────────────────────────────

    @Test
    void activateDeactivateAndDeleteReturnTheirMessagesAndPersist() {
        String clientId = testClient("lifecycle");
        String id = ensureUser(clientId, "lifecycle-" + RUN + "@example.com");

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
        String[] otherClientScoped = clientScoped("clt_someone_else", "platform:iam:portal-user:manage,platform:iam:portal-user:view");
        var forbidden = http.get("/api/portal-users?clientId=" + clientId, otherClientScoped);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(json(forbidden).get("error").asText()).isEqualTo("SCOPE_FORBIDDEN");
    }
}
