package io.flowcatalyst.platform.portalauth.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.ratelimit.PostgresRateLimitStore;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
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
import io.flowcatalyst.platform.portalauth.PortalLoginFlow;
import io.flowcatalyst.platform.portalauth.PortalLoginFlowRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentitySource;
import io.flowcatalyst.platform.portalidentity.PortalInviteEmailer;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// `/portal/authorize`, `/portal/auth/check-domain`, `/portal/auth/login`,
/// `/portal/auth/password-reset` end to end (spec `auth-identity.md` §3.2,
/// §5.1–§5.5). Entirely public routes — no auth headers anywhere in this
/// class, matching `Platform.isPublicPath`.
@SuppressWarnings("deprecation")
class PortalAuthApiTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);

    private static final ClientRepository clientRepo = new ClientRepository(TestPg.dataSource());
    private static final OAuthClientRepository oauthClientRepo =
            new OAuthClientRepository(TestPg.dataSource(), new ApplicationRepository(TestPg.dataSource()));
    private static final IdentityProviderRepository identityProviderRepo = new IdentityProviderRepository(TestPg.dataSource());
    private static final EmailDomainMappingRepository emailDomainMappingRepo = new EmailDomainMappingRepository(TestPg.dataSource());
    private static final PortalIdentityRepository identityRepo = new PortalIdentityRepository(TestPg.dataSource());
    private static final PortalLoginFlowRepository flowRepo = new PortalLoginFlowRepository(TestPg.dataSource());
    private static final GrantStore grantStore = new GrantStore(TestPg.dataSource());
    private static final UnitOfWork uow = new UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER));

    private static final class RecordingEmailer implements PortalInviteEmailer {
        final List<String> resets = new CopyOnWriteArrayList<>();

        @Override
        public void sendPortalInvite(PortalIdentity identity, String target) {
        }

        @Override
        public void sendPortalSsoInvite(String email, String target) {
        }

        @Override
        public void sendPortalReset(String identityId, String email, String origin) {
            resets.add(email);
        }

        @Override
        public String inviteLink(PortalIdentity identity, String target) {
            return target;
        }
    }

    private static final RecordingEmailer EMAILER = new RecordingEmailer();
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var envReader = io.flowcatalyst.server.EnvReader.system();
        var state = new PortalAuthApi.State(flowRepo, oauthClientRepo, identityRepo, identityProviderRepo, grantStore,
                new PostgresRateLimitStore(TestPg.dataSource()), RateLimit.Policies.fromEnv(envReader), EMAILER);
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            PortalAuthApi.register(routes, state);
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
        Client c = Client.create("Portal Auth Api " + tag, ClientIdentifier.parse("paa-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    /// An active, PKCE-optional, portal-flagged OAuth client with `redirectUri` registered.
    private static OAuthClient portalOAuthClient(String tag, String portalClientId, String redirectUri) {
        OAuthClient c = OAuthClient.create("pac-" + tag + "-" + RUN, "Portal client " + tag, ClientType.PUBLIC)
                .withRedirectUris(List.of(redirectUri))
                .withPkceRequired(false)
                .withPortalAndApiAccess(portalClientId, false);
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(c, tx.dbTx());
            return null;
        });
        return c;
    }

    private static PortalLoginFlow liveFlow(OAuthClient oauthClient, String portalClientId, String redirectUri, String state) {
        PortalLoginFlow f = PortalLoginFlow.start(oauthClient.clientId(), portalClientId, redirectUri, null, state,
                null, null, null, Instant.now());
        flowRepo.insert(f);
        return f;
    }

    private static PortalIdentity identityWithPassword(String clientId, String email, String rawPassword) {
        PortalIdentity p = PortalIdentity.create(clientId, email, null, PortalIdentitySource.INVITE);
        uow.inTransaction(tx -> {
            identityRepo.persist(p, tx.dbTx());
            return null;
        });
        uow.inTransaction(tx -> {
            identityRepo.setPasswordHash(p.id(), PasswordHash.hash(rawPassword), tx.dbTx());
            return null;
        });
        return identityRepo.findById(p.id()).orElseThrow();
    }

    private static void disable(PortalIdentity identity) {
        uow.inTransaction(tx -> {
            identityRepo.persist(identity.deactivate(), tx.dbTx());
            return null;
        });
    }

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

    private static String queryParam(String url, String name) {
        String query = URI.create(url).getRawQuery();
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (part.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ── GET /portal/authorize ────────────────────────────────────────────────

    @Test
    void authorizeStartsALiveFlowAndRedirectsToThePortalLoginPage() {
        String clientId = testClient("authz-ok");
        OAuthClient oc = portalOAuthClient("authz-ok", clientId, "https://portal.example.com/cb");

        var res = http.get("/portal/authorize?state=st1&client_id=" + oc.clientId()
                + "&redirect_uri=" + enc("https://portal.example.com/cb") + "&response_type=code");
        assertThat(res.statusCode()).isEqualTo(307);
        String location = res.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith("/portal/login?flow=");
        String flowId = location.substring("/portal/login?flow=".length());
        assertThat(flowRepo.findLive(flowId)).isPresent();
    }

    /// Mutant 5: `/portal/authorize` accepts a non-portal client.
    @Test
    void authorizeRejectsAClientThatIsNotFlaggedAsAPortalClient() {
        OAuthClient notPortal = OAuthClient.create("pac-notportal-" + RUN, "Not a portal client", ClientType.PUBLIC)
                .withRedirectUris(List.of("https://not-portal.example.com/cb"));
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(notPortal, tx.dbTx());
            return null;
        });

        var res = http.get("/portal/authorize?state=st1&client_id=" + notPortal.clientId()
                + "&redirect_uri=" + enc("https://not-portal.example.com/cb") + "&response_type=code");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(json(res).get("error").asText()).isEqualTo("unauthorized_client");
        assertThat(json(res).get("error_description").asText()).isEqualTo("Client is not a portal client");
        assertThat(res.headers().firstValue("Location")).as("a direct failure, never a redirect").isEmpty();
    }

    @Test
    void authorizeRejectsABlankStateAnUnknownClientAndAMismatchedRedirectUri() {
        var blankState = http.get("/portal/authorize?client_id=x&redirect_uri=https://x&response_type=code");
        assertThat(blankState.statusCode()).isEqualTo(400);
        assertThat(json(blankState).get("error").asText()).isEqualTo("invalid_request");
        assertThat(json(blankState).get("error_description").asText()).contains("state");

        var unknownClient = http.get("/portal/authorize?state=s&client_id=oac_doesnotexist1&redirect_uri=https://x&response_type=code");
        assertThat(unknownClient.statusCode()).isEqualTo(400);
        assertThat(json(unknownClient).get("error").asText()).isEqualTo("unauthorized_client");

        String clientId = testClient("authz-badredirect");
        OAuthClient oc = portalOAuthClient("authz-badredirect", clientId, "https://portal.example.com/cb");
        var badRedirect = http.get("/portal/authorize?state=s&client_id=" + oc.clientId() + "&redirect_uri=" + enc("https://evil.example.com/cb") + "&response_type=code");
        assertThat(badRedirect.statusCode()).isEqualTo(400);
        assertThat(json(badRedirect).get("error").asText()).isEqualTo("invalid_request");
    }

    @Test
    void authorizeRedirectsPostValidationFailuresToTheClientsRedirectUri() {
        String clientId = testClient("authz-postval");
        OAuthClient oc = portalOAuthClient("authz-postval", clientId, "https://portal.example.com/cb");

        var badResponseType = http.get("/portal/authorize?state=st&client_id=" + oc.clientId()
                + "&redirect_uri=" + enc("https://portal.example.com/cb") + "&response_type=token");
        assertThat(badResponseType.statusCode()).isEqualTo(307);
        String loc = badResponseType.headers().firstValue("Location").orElseThrow();
        assertThat(loc).startsWith("https://portal.example.com/cb?");
        assertThat(queryParam(loc, "error")).isEqualTo("unsupported_response_type");
        assertThat(queryParam(loc, "state")).isEqualTo("st");

        OAuthClient pkceRequired = OAuthClient.create("pac-pkce-" + RUN, "PKCE client", ClientType.PUBLIC)
                .withRedirectUris(List.of("https://pkce.example.com/cb"))
                .withPkceRequired(true)
                .withPortalAndApiAccess(clientId, false);
        uow.inTransaction(tx -> {
            oauthClientRepo.persist(pkceRequired, tx.dbTx());
            return null;
        });
        var noChallenge = http.get("/portal/authorize?state=st&client_id=" + pkceRequired.clientId()
                + "&redirect_uri=" + enc("https://pkce.example.com/cb") + "&response_type=code");
        assertThat(noChallenge.statusCode()).isEqualTo(307);
        assertThat(queryParam(noChallenge.headers().firstValue("Location").orElseThrow(), "error")).isEqualTo("invalid_request");
    }

    // ── POST /portal/auth/check-domain ──────────────────────────────────────

    @Test
    void checkDomainReportsSsoForAnSsoOwnedDomainAndPasswordOtherwise() {
        String clientId = testClient("check-domain");
        OAuthClient oc = portalOAuthClient("check-domain", clientId, "https://portal.example.com/cb");
        PortalLoginFlow flow = liveFlow(oc, clientId, "https://portal.example.com/cb", "st");
        String domain = "checkdomain-" + RUN + ".example.com";
        ssoOwnedDomain("check-domain", domain);

        var sso = json(http.post("/portal/auth/check-domain", "{\"flowId\":\"" + flow.id() + "\",\"email\":\"a@" + domain + "\"}"));
        assertThat(sso.get("method").asText()).isEqualTo("SSO");
        assertThat(sso.get("redirectUrl").asText()).contains("flow=" + flow.id()).contains("provider_id=");

        var password = json(http.post("/portal/auth/check-domain",
                "{\"flowId\":\"" + flow.id() + "\",\"email\":\"a@not-sso-" + RUN + ".example.com\"}"));
        assertThat(password.get("method").asText()).isEqualTo("PASSWORD");

        var expired = http.post("/portal/auth/check-domain", "{\"flowId\":\"ff_doesnotexist\",\"email\":\"a@x.com\"}");
        assertThat(expired.statusCode()).isEqualTo(400);
        assertThat(json(expired).get("error").asText()).isEqualTo("FLOW_EXPIRED");
    }

    // ── POST /portal/auth/login ──────────────────────────────────────────────

    @Test
    void loginJourneyRedeemsAnAuthorizationCodeWhoseSubjectIsThePortalIdentity() {
        String clientId = testClient("journey");
        OAuthClient oc = portalOAuthClient("journey", clientId, "https://portal.example.com/cb");
        String email = "journey-" + RUN + "@example.com";
        PortalIdentity identity = identityWithPassword(clientId, email, "correct-horse-battery");
        PortalLoginFlow flow = liveFlow(oc, clientId, "https://portal.example.com/cb", "st-journey");

        var res = http.post("/portal/auth/login",
                "{\"flowId\":\"" + flow.id() + "\",\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        String redirectUrl = json(res).get("redirectUrl").asText();
        assertThat(redirectUrl).startsWith("https://portal.example.com/cb?");
        assertThat(queryParam(redirectUrl, "state")).isEqualTo("st-journey");
        String code = queryParam(redirectUrl, "code");
        assertThat(code).isNotBlank();

        var grant = grantStore.findCode(code).orElseThrow(() -> new AssertionError("code not found in the grant store"));
        assertThat(grant.principalId()).as("the subject is the ptu_ portal identity id").isEqualTo(identity.id());
        assertThat(grant.clientId()).isEqualTo(oc.clientId());
        assertThat(grant.redirectUri()).isEqualTo("https://portal.example.com/cb");

        assertThat(flowRepo.findLive(flow.id())).as("the flow is single-use").isEmpty();
        assertThat(identityRepo.findById(identity.id()).orElseThrow().lastLoginAt()).as("touched on success").isNotNull();
    }

    /// Mutant 3: `/portal/auth/login` consumes the flow before the password
    /// check. A suspended identity's *correct* password must still be a
    /// uniform 401 with the flow left live — if the flow were consumed
    /// first, this assertion on `findLive` would fail even though the 401
    /// still came back.
    @Test
    void loginWithASuspendedIdentityIsAUniform401AndLeavesTheFlowLive() {
        String clientId = testClient("suspended");
        OAuthClient oc = portalOAuthClient("suspended", clientId, "https://portal.example.com/cb");
        String email = "suspended-" + RUN + "@example.com";
        PortalIdentity identity = identityWithPassword(clientId, email, "right-password");
        disable(identity);
        PortalLoginFlow flow = liveFlow(oc, clientId, "https://portal.example.com/cb", "st-susp");

        var res = http.post("/portal/auth/login",
                "{\"flowId\":\"" + flow.id() + "\",\"email\":\"" + email + "\",\"password\":\"right-password\"}");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json(res).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");

        assertThat(flowRepo.findLive(flow.id())).as("a rejected attempt must not burn the flow").isPresent();
    }

    /// Mutant 4: `/portal/auth/login` skips the SSO-domain refusal.
    @Test
    void loginOnAnSsoOwnedDomainRefusesEvenTheCorrectPasswordWith401SsoRequired() {
        String clientId = testClient("sso-login");
        OAuthClient oc = portalOAuthClient("sso-login", clientId, "https://portal.example.com/cb");
        String domain = "ssologin-" + RUN + ".example.com";
        ssoOwnedDomain("sso-login", domain);
        String email = "person@" + domain;
        PortalIdentity identity = identityWithPassword(clientId, email, "right-password");
        PortalLoginFlow flow = liveFlow(oc, clientId, "https://portal.example.com/cb", "st-sso");

        var res = http.post("/portal/auth/login",
                "{\"flowId\":\"" + flow.id() + "\",\"email\":\"" + email + "\",\"password\":\"right-password\"}");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json(res).get("code").asText()).isEqualTo("SSO_REQUIRED");
        assertThat(flowRepo.findLive(flow.id())).as("SSO refusal must not burn the flow either").isPresent();
        assertThat(identityRepo.findById(identity.id()).orElseThrow().lastLoginAt()).as("never touched").isNull();
    }

    @Test
    void loginRejectsAWrongPasswordUniformly() {
        String clientId = testClient("wrongpw");
        OAuthClient oc = portalOAuthClient("wrongpw", clientId, "https://portal.example.com/cb");
        String email = "wrongpw-" + RUN + "@example.com";
        identityWithPassword(clientId, email, "the-real-password");
        PortalLoginFlow flow = liveFlow(oc, clientId, "https://portal.example.com/cb", "st-wrong");

        var res = http.post("/portal/auth/login",
                "{\"flowId\":\"" + flow.id() + "\",\"email\":\"" + email + "\",\"password\":\"nope\"}");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(json(res).get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(flowRepo.findLive(flow.id())).isPresent();
    }

    /// The 15-minute portal-login rate limit (spec §5.3 row 2, §13 #14):
    /// 10 attempts per (portalClientId, email); the 11th is 429.
    @Test
    void theEleventhLoginAttemptInFifteenMinutesIs429() {
        String clientId = testClient("ratelimit");
        OAuthClient oc = portalOAuthClient("ratelimit", clientId, "https://portal.example.com/cb");
        String email = "ratelimit-" + RUN + "@example.com";
        identityWithPassword(clientId, email, "the-real-password");
        PortalLoginFlow flow = liveFlow(oc, clientId, "https://portal.example.com/cb", "st-rl");

        HttpResponse<String> last = null;
        for (int i = 0; i < 10; i++) {
            last = http.post("/portal/auth/login",
                    "{\"flowId\":\"" + flow.id() + "\",\"email\":\"" + email + "\",\"password\":\"nope\"}");
            assertThat(last.statusCode()).as("attempt " + (i + 1)).isEqualTo(401);
        }
        var eleventh = http.post("/portal/auth/login",
                "{\"flowId\":\"" + flow.id() + "\",\"email\":\"" + email + "\",\"password\":\"nope\"}");
        assertThat(eleventh.statusCode()).isEqualTo(429);
        assertThat(json(eleventh).get("error").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(eleventh.headers().firstValue("Retry-After")).isPresent();
    }

    // ── POST /portal/auth/password-reset ────────────────────────────────────

    @Test
    void passwordResetIsASilentTwoHundredForUnknownAndSsoAddressesButMailsAnEligibleOne() {
        String clientId = testClient("reset");
        OAuthClient oc = portalOAuthClient("reset", clientId, "https://portal.example.com/cb");
        String email = "reset-" + RUN + "@example.com";
        identityWithPassword(clientId, email, "whatever");
        String domain = "ssoreset-" + RUN + ".example.com";
        ssoOwnedDomain("reset", domain);
        // The SSO guard only matters when such an identity exists: give the
        // SSO-owned address a live identity with a password, so a missing
        // guard would mail it (review mutant).
        identityWithPassword(clientId, "a@" + domain, "whatever");

        PortalLoginFlow flow1 = liveFlow(oc, clientId, "https://portal.example.com/cb", "st1");
        var unknown = http.post("/portal/auth/password-reset", "{\"flowId\":\"" + flow1.id() + "\",\"email\":\"nobody-" + RUN + "@example.com\"}");
        assertThat(unknown.statusCode()).isEqualTo(200);
        assertThat(json(unknown).get("message").asText()).isEqualTo("If an account exists, a reset email has been sent.");
        assertThat(EMAILER.resets).isEmpty();

        PortalLoginFlow flow2 = liveFlow(oc, clientId, "https://portal.example.com/cb", "st2");
        var sso = http.post("/portal/auth/password-reset", "{\"flowId\":\"" + flow2.id() + "\",\"email\":\"a@" + domain + "\"}");
        assertThat(sso.statusCode()).isEqualTo(200);
        assertThat(json(sso).get("message").asText()).isEqualTo(json(unknown).get("message").asText());
        assertThat(EMAILER.resets).as("an SSO-owned address is never mailed a password reset").isEmpty();

        PortalLoginFlow flow3 = liveFlow(oc, clientId, "https://portal.example.com/cb", "st3");
        var eligible = http.post("/portal/auth/password-reset", "{\"flowId\":\"" + flow3.id() + "\",\"email\":\"" + email + "\"}");
        assertThat(eligible.statusCode()).isEqualTo(200);
        assertThat(EMAILER.resets).contains(email);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
