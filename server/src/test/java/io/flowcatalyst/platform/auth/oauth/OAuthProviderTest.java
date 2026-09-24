package io.flowcatalyst.platform.auth.oauth;

import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.grant.RefreshRotation;
import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.ratelimit.Governor;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.auth.token.ClaimLabels;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_LOGIN_ATTEMPTS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_APPLICATION_ACCESS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLE_PERMISSIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;
import static io.flowcatalyst.db.generated.Tables.IAM_AUTHORIZATION_CODES;
import static io.flowcatalyst.db.generated.Tables.IAM_REFRESH_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;

/// The OAuth / OIDC provider over HTTP against the embedded Postgres
/// (`docs/spec/auth-core.md` §6.2, §6.2a, §6.2b, §7.3 and the §0.5 rulings):
/// real clients in `oauth_clients`, real principals with roles, real codes
/// and refresh tokens, tokens read back through the server's own verifier.
/// Each test names the ruling or spec line it pins.
class OAuthProviderTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final String ISSUER = "http://localhost:8080";
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final Encryption ENC = Encryption.withKey(Encryption.generateKey());
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final OAuthClientRepository CLIENTS = new OAuthClientRepository(DS, new ApplicationRepository(DS));
    private static final io.flowcatalyst.platform.portalapp.PortalAppRepository PORTAL_APPS =
            new io.flowcatalyst.platform.portalapp.PortalAppRepository(DS);
    private static final LoginAttemptRepository ATTEMPTS = new LoginAttemptRepository(DS);
    private static final GrantStore GRANTS = new GrantStore(DS);
    private static final TokenIssuer ISSUER_UNDER_TEST = new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER));
    private static final JwtVerifier VERIFIER = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));
    private static final DbClaimsResolver RESOLVER = new DbClaimsResolver(PRINCIPALS, new RoleRepository(DS));

    private static final String REDIRECT = "https://app-" + "x" + ".example/cb";
    private static final String SECRET = "svc-secret-" + UUID.randomUUID();
    private static final String OLD_SECRET = "svc-old-secret-" + UUID.randomUUID();
    private static final String DEV_SECRET = "dev-secret-" + UUID.randomUUID();
    private static final String VERIFIER_PKCE = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE_PKCE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private static String appA;
    private static String appB;
    private static String userId;          // roles in A and B
    private static String userEmail;
    private static String serviceAccountId;
    private static String servicePrincipalId;
    private static String developerId;
    private static String noRoleDeveloperId;
    private static OAuthClient web;         // PUBLIC, PKCE required, identity-only
    private static OAuthClient api;         // CONFIDENTIAL, apiAccess, confined to app A
    private static OAuthClient svc;         // CONFIDENTIAL, client_credentials, rotating secret
    private static OAuthClient noGrants;    // empty grant list (C-Q20)
    private static OAuthClient unbound;     // CONFIDENTIAL, client_credentials, no linked principal (ruling 2026-09-06 #11)
    private static OAuthClient portal;
    private static Governor governor;
    private static TestHttp http;

    @BeforeAll
    static void start() {
        appA = app("billing-" + RUN);
        appB = app("crm-" + RUN);
        String rA = role(appA, "billing-" + RUN, "billing-" + RUN + ":agent", List.of("billing:read", "billing:write"));
        String rB = role(appB, "crm-" + RUN, "crm-" + RUN + ":viewer", List.of("crm:read"));
        userEmail = "user-" + RUN + "@example.com";
        userId = principal("USER", userEmail, null, null);
        assignRole(userId, rA);
        assignRole(userId, rB);
        grantApplication(userId, appA);
        grantApplication(userId, appB);

        serviceAccountId = serviceAccount("svc-" + RUN);
        servicePrincipalId = principal("SERVICE", null, serviceAccountId, null);
        assignRole(servicePrincipalId, rA);

        developerId = principal("USER", "dev-" + RUN + "@example.com", null, ENC.encryptSecretRef(DEV_SECRET));
        assignRole(developerId, OAuthTokenApi.DEVELOPER_ROLE);
        assignRole(developerId, rB);
        noRoleDeveloperId = principal("USER", "nodev-" + RUN + "@example.com", null, ENC.encryptSecretRef(DEV_SECRET));

        web = OAuthClient.create("web-" + RUN, "Web " + RUN, ClientType.PUBLIC)
                .withRedirectUris(List.of(REDIRECT, "https://*.tenants-" + RUN + ".example/cb"))
                .withGrantTypes(List.of("authorization_code", "refresh_token"))
                .withDefaultScopes(List.of("custom:thing"))
                .withPkceRequired(true);
        api = OAuthClient.create("api-" + RUN, "Api " + RUN, ClientType.CONFIDENTIAL)
                .withSecretRef(ENC.encryptSecretRef(SECRET))
                .withRedirectUris(List.of(REDIRECT))
                .withGrantTypes(List.of("authorization_code", "refresh_token"))
                .withApplicationIds(List.of(appA))
                .withDefaultScopes(List.of("billing:write", "crm:read"))
                .withPkceRequired(false)
                .withPortalAndApiAccess(null, true);
        svc = OAuthClient.create("svc-" + RUN, "Svc " + RUN, ClientType.CONFIDENTIAL)
                .withSecretRef(ENC.encryptSecretRef(OLD_SECRET))
                .withGrantTypes(List.of("client_credentials"))
                .withPrincipalId(servicePrincipalId);
        svc = svc.rotateSecret(ENC.encryptSecretRef(SECRET), Duration.ofHours(1), Instant.now()).client();
        unbound = OAuthClient.create("unbound-" + RUN, "Unbound " + RUN, ClientType.CONFIDENTIAL)
                .withSecretRef(ENC.encryptSecretRef(SECRET))
                .withGrantTypes(List.of("client_credentials"));
        noGrants = OAuthClient.create("none-" + RUN, "None " + RUN, ClientType.PUBLIC)
                .withRedirectUris(List.of(REDIRECT))
                .withGrantTypes(List.of())
                .withPkceRequired(false);
        portal = OAuthClient.create("portal-" + RUN, "Portal " + RUN, ClientType.PUBLIC)
                .withRedirectUris(List.of(REDIRECT))
                .withGrantTypes(List.of("authorization_code"))
                .withPortalAndApiAccess("cli_" + RUN, false);
        for (OAuthClient c : List.of(web, api, svc, noGrants, portal, unbound)) {
            UOW.inTransaction(tx -> {
                CLIENTS.persist(c, tx.dbTx());
                return null;
            });
        }

        governor = new Governor(new Governor.Config(60, 1000));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            OAuthState s = state();
            OAuthAuthorizeApi.register(routes, s, new SessionCookie(false, (int) TokenIssuer.SESSION_TTL_SECONDS));
            OAuthTokenApi.register(routes, s);
            OAuthIntrospectionApi.register(routes, s);
            OAuthUserinfoApi.register(routes, s);
            OAuthDiscoveryApi.register(routes, s);
            AuthRefreshApi.register(routes, s);
        });
    }

    private static OAuthState state() {
        return new OAuthState(CLIENTS, PRINCIPALS, new ServiceAccountRepository(DS, Optional.of(ENC)), GRANTS,
                new RefreshRotation(GRANTS, Clock.systemUTC(), RefreshToken.TTL_SECONDS), ISSUER_UNDER_TEST, new AccessTokenReader(VERIFIER),
                RESOLVER, ClaimLabels.of(new ClientRepository(DS), new ApplicationRepository(DS)), Optional.of(ENC),
                ATTEMPTS, new RateLimit.NoopStore(), RateLimit.Policies.fromEnv(new io.flowcatalyst.server.EnvReader(Map.of())),
                governor, KEYS, ISSUER, Clock.systemUTC(), null, PORTAL_APPS, RefreshToken.TTL_SECONDS);
    }

    @AfterAll
    static void stop() {
        http.close();
        DB.deleteFrom(IAM_AUTHORIZATION_CODES).where(IAM_AUTHORIZATION_CODES.PRINCIPAL_ID.in(userId, developerId)).execute();
        DB.deleteFrom(IAM_REFRESH_TOKENS).where(IAM_REFRESH_TOKENS.PRINCIPAL_ID.in(userId, developerId)).execute();
        for (OAuthClient c : List.of(web, api, svc, noGrants, portal, unbound)) {
            UOW.inTransaction(tx -> {
                CLIENTS.delete(c, tx.dbTx());
                return null;
            });
        }
        DB.deleteFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.in(svc.clientId(), developerId, noRoleDeveloperId)).execute();
        List<String> principals = List.of(userId, servicePrincipalId, developerId, noRoleDeveloperId);
        DB.deleteFrom(IAM_PRINCIPAL_ROLES).where(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_PRINCIPAL_APPLICATION_ACCESS).where(IAM_PRINCIPAL_APPLICATION_ACCESS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(principals)).execute();
        DB.deleteFrom(IAM_SERVICE_ACCOUNTS).where(IAM_SERVICE_ACCOUNTS.ID.eq(serviceAccountId)).execute();
        DB.deleteFrom(IAM_ROLE_PERMISSIONS).where(IAM_ROLE_PERMISSIONS.ROLE_ID.in(
                DB.select(IAM_ROLES.ID).from(IAM_ROLES).where(IAM_ROLES.APPLICATION_ID.in(appA, appB)))).execute();
        DB.deleteFrom(IAM_ROLES).where(IAM_ROLES.APPLICATION_ID.in(appA, appB)).execute();
        DB.deleteFrom(APP_APPLICATIONS).where(APP_APPLICATIONS.ID.in(appA, appB)).execute();
    }

    // ── /oauth/authorize: direct errors before the redirect_uri is trusted ──

    @Test
    void authorizeWithoutStateIsADirectInvalidRequest() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("invalid_request");
        assertThat(r.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void anUnknownClientOrUnregisteredRedirectNeverBouncesTheBrowser() {
        var unknown = authorize(Map.of("response_type", "code", "client_id", "nope-" + RUN, "redirect_uri", REDIRECT, "state", "s"));
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(json(unknown).get("error").asString()).isEqualTo("unauthorized_client");

        var badUri = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", "https://evil.example/cb", "state", "s"));
        assertThat(badUri.statusCode()).isEqualTo(400);
        assertThat(json(badUri).get("error").asString()).isEqualTo("invalid_request");
        assertThat(badUri.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void aWildcardRedirectPatternAdmitsATenantSubdomain() {
        String tenant = "https://acme.tenants-" + RUN + ".example/cb";
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", tenant,
                "state", "s", "code_challenge", CHALLENGE_PKCE), sessionCookie(userId));
        assertThat(r.statusCode()).isEqualTo(307);
        assertThat(location(r)).startsWith(tenant + "?code=");
    }

    @Test
    void aPortalClientIsRefusedAtTheEmployeeAuthorizeEndpoint() {
        var r = authorize(Map.of("response_type", "code", "client_id", portal.clientId(), "redirect_uri", REDIRECT, "state", "s"));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error_description").asString()).contains("/portal/authorize");
    }

    // ── /oauth/authorize: redirect errors once the client is trusted ───────

    @Test
    void stateOver116CharactersIsInvalidRequestBeforeAnyWrite() {
        String state = "x".repeat(117);
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT,
                "state", state, "code_challenge", CHALLENGE_PKCE));
        assertThat(r.statusCode()).isEqualTo(307);
        Map<String, String> q = query(location(r));
        assertThat(q.get("error")).isEqualTo("invalid_request");
        assertThat(q.get("error_description")).contains("116");
        assertThat(GRANTS.consumePendingAuth(state)).as("nothing was stashed").isEmpty();
    }

    @Test
    void aPkceRequiredClientWithoutAChallengeIsBouncedBackWithInvalidRequest() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s"));
        assertThat(r.statusCode()).isEqualTo(307);
        Map<String, String> q = query(location(r));
        assertThat(q.get("error")).isEqualTo("invalid_request");
        assertThat(q.get("error_description")).contains("PKCE");
        assertThat(q.get("state")).isEqualTo("s");
    }

    @Test
    void plainChallengeMethodIsRefusedAndOnlyS256Accepted() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE, "code_challenge_method", "plain"));
        assertThat(query(location(r)).get("error")).isEqualTo("invalid_request");
        assertThat(query(location(r)).get("error_description")).contains("S256");
    }

    @Test
    void aScopeOutsideTheStandardSetAndTheClientDefaultsIsInvalidScope() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE, "scope", "openid custom:thing billing:read"));
        Map<String, String> q = query(location(r));
        assertThat(q.get("error")).isEqualTo("invalid_scope");
        assertThat(q.get("error_description")).contains("billing:read").doesNotContain("custom:thing");
    }

    @Test
    void anEmptyGrantListPermitsNoGrantAtAll() {
        // Ruling C-Q20: empty ⇒ none, at authorize and at the token endpoint.
        var r = authorize(Map.of("response_type", "code", "client_id", noGrants.clientId(), "redirect_uri", REDIRECT, "state", "s"));
        assertThat(query(location(r)).get("error")).isEqualTo("unauthorized_client");

        var t = token(Map.of("grant_type", "authorization_code", "code", "whatever", "redirect_uri", REDIRECT, "client_id", noGrants.clientId()));
        assertThat(t.statusCode()).isEqualTo(400);
        assertThat(json(t).get("error").asString()).isEqualTo("unauthorized_client");
    }

    @Test
    void unsupportedResponseTypeGoesBackToTheClient() {
        var r = authorize(Map.of("response_type", "token", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE));
        assertThat(query(location(r)).get("error")).isEqualTo("unsupported_response_type");
    }

    // ── /oauth/authorize: session handling ─────────────────────────────────

    @Test
    void withoutASessionTheBrowserGoesToLoginAndTheRequestIsStashed() {
        String state = "st-" + UUID.randomUUID();
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", state,
                "code_challenge", CHALLENGE_PKCE, "scope", "openid", "nonce", "n1", "client", "acme"));
        assertThat(r.statusCode()).isEqualTo(307);
        String loc = location(r);
        assertThat(loc).startsWith("/auth/login?oauth=true&response_type=code");
        Map<String, String> q = query(loc);
        assertThat(q).containsEntry("client_id", web.clientId()).containsEntry("redirect_uri", REDIRECT)
                .containsEntry("state", state).containsEntry("code_challenge", CHALLENGE_PKCE)
                .containsEntry("scope", "openid").containsEntry("nonce", "n1").containsEntry("client", "acme");
        var stashed = GRANTS.consumePendingAuth(state);
        assertThat(stashed).isPresent();
        assertThat(stashed.get().clientId()).isEqualTo(web.clientId());
        assertThat(stashed.get().codeChallenge()).isEqualTo(CHALLENGE_PKCE);
    }

    @Test
    void aMalformedClientHintIsDroppedFromTheLoginRedirect() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE, "client", "<script>"));
        assertThat(query(location(r))).doesNotContainKey("client");
    }

    @Test
    void promptNoneWithoutASessionIsLoginRequired() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE, "prompt", "none"));
        assertThat(query(location(r)).get("error")).isEqualTo("login_required");
    }

    @Test
    void aSessionCookieMintsACodeCarryingTheSessionIssueTimeAsAuthTime() throws Exception {
        String session = ISSUER_UNDER_TEST.sessionToken(userId, userEmail);
        long iat = SignedJWT.parse(session).getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond();
        String state = "st-" + UUID.randomUUID();
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", state,
                "code_challenge", CHALLENGE_PKCE, "scope", "openid offline_access", "nonce", "n-" + RUN), "Cookie", "fc_session=" + session);
        assertThat(r.statusCode()).isEqualTo(307);
        Map<String, String> q = query(location(r));
        assertThat(location(r)).startsWith(REDIRECT + "?code=");
        assertThat(q.get("state")).isEqualTo(state);
        var stored = GRANTS.findCode(q.get("code")).orElseThrow();
        assertThat(stored.principalId()).isEqualTo(userId);
        assertThat(stored.authTime().getEpochSecond()).as("ruling C-Q1: auth_time is the session's iat").isEqualTo(iat);
        assertThat(stored.codeChallenge()).isEqualTo(CHALLENGE_PKCE);
        assertThat(stored.codeChallengeMethod()).as("absent method persists as S256").isEqualTo("S256");
        assertThat(stored.nonce()).isEqualTo("n-" + RUN);
    }

    @Test
    void aBearerHeaderIsTheFallbackWhenThereIsNoCookie() {
        String session = ISSUER_UNDER_TEST.sessionToken(userId, userEmail);
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE), "Authorization", "Bearer " + session);
        assertThat(location(r)).startsWith(REDIRECT + "?code=");
    }

    /// S2.2: whichever header carries it, the authorize session must be a
    /// session token. An API or identity access token for the same user —
    /// what an OAuth client holds after the user delegated to it — is no
    /// sign-in: the browser is sent to log in and no code is minted.
    @Test
    void anAccessTokenAsBearerIsNoSessionAtAuthorize() {
        var p = PRINCIPALS.findById(userId).orElseThrow();
        String api = ISSUER_UNDER_TEST.accessToken(p, new TokenIssuer.Authority(List.of(), List.of(), List.of(), false, List.of()), "oac_x");
        String identity = ISSUER_UNDER_TEST.identityAccessToken(p, "oac_x");
        for (String token : List.of(api, identity)) {
            var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                    "code_challenge", CHALLENGE_PKCE), "Authorization", "Bearer " + token);
            assertThat(location(r)).as("mutant: any verified token is a session").startsWith("/auth/login?oauth=true");
            var none = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                    "code_challenge", CHALLENGE_PKCE, "prompt", "none"), "Authorization", "Bearer " + token);
            assertThat(query(location(none)).get("error")).isEqualTo("login_required");
        }
    }

    /// S2.2: a deactivated principal's still-unexpired session mints no code
    /// (the cookie is identity only and cannot be revoked), and a code minted
    /// before the deactivation is refused at redemption.
    @Test
    void aDeactivatedPrincipalGetsNoCodeAndCannotRedeemOne() {
        String pid = principal("USER", "deact-" + RUN + "@example.com", null, null);
        try {
            var params = new LinkedHashMap<String, String>();
            params.put("response_type", "code");
            params.put("client_id", web.clientId());
            params.put("redirect_uri", REDIRECT);
            params.put("state", "st-" + UUID.randomUUID());
            params.put("scope", "openid");
            params.put("code_challenge", CHALLENGE_PKCE);
            String[] cookie = {"Cookie", "fc_session=" + ISSUER_UNDER_TEST.sessionToken(pid, "deact-" + RUN + "@example.com")};
            String code = query(location(authorize(params, cookie))).get("code");
            assertThat(code).as("active: the control").isNotNull();

            DB.update(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ACTIVE, false).where(IAM_PRINCIPALS.ID.eq(pid)).execute();

            var again = authorize(params, cookie);
            assertThat(location(again)).as("mutant: no active check at authorize").startsWith("/auth/login?oauth=true");

            var redeem = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                    "client_id", web.clientId(), "code_verifier", VERIFIER_PKCE));
            assertThat(redeem.statusCode()).as("mutant: no active check at redemption — " + redeem.body()).isEqualTo(400);
            assertThat(json(redeem).get("error").asString()).isEqualTo("invalid_grant");
            assertThat(json(redeem).has("access_token")).isFalse();
        } finally {
            DB.deleteFrom(IAM_AUTHORIZATION_CODES).where(IAM_AUTHORIZATION_CODES.PRINCIPAL_ID.eq(pid)).execute();
            DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(pid)).execute();
        }
    }

    @Test
    void maxAgeZeroForcesAFreshLoginEvenWithASession() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE, "max_age", "0"), sessionCookie(userId));
        assertThat(location(r)).startsWith("/auth/login?oauth=true");

        var none = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE, "max_age", "0", "prompt", "none"), sessionCookie(userId));
        assertThat(query(location(none)).get("error")).isEqualTo("login_required");
    }

    @Test
    void promptLoginIgnoresTheSession() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE, "prompt", "login"), sessionCookie(userId));
        assertThat(location(r)).startsWith("/auth/login?oauth=true");
    }

    @Test
    void aProviderParameterChainsIntoTheOidcBridge() {
        var r = authorize(Map.of("response_type", "code", "client_id", web.clientId(), "redirect_uri", REDIRECT, "state", "s",
                "code_challenge", CHALLENGE_PKCE, "provider", "idp_1"));
        String loc = location(r);
        assertThat(loc).startsWith("/auth/oidc/login?provider_id=idp_1");
        assertThat(query(loc)).containsEntry("oauth_client_id", web.clientId()).containsEntry("oauth_state", "s")
                .containsEntry("oauth_code_challenge", CHALLENGE_PKCE);
    }

    // ── /oauth/token: authorization_code ───────────────────────────────────

    @Test
    void theCodeExchangeMintsAnIdentityTokenIdTokenAndRefreshTokenForAPublicClient() throws Exception {
        String code = mintCode(web, "openid offline_access", "nonce-" + RUN, true);
        var r = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", web.clientId(), "code_verifier", VERIFIER_PKCE));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode j = json(r);
        assertThat(j.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(j.get("expires_in").asLong()).as("ruling A-23: the configured TTL").isEqualTo(TokenIssuer.ACCESS_TTL_SECONDS);
        assertThat(j.get("scope").asString()).isEqualTo("openid offline_access");

        var access = SignedJWT.parse(j.get("access_token").asString()).getPayload().toJSONObject();
        assertThat(access.get("azp")).isEqualTo(web.clientId());
        assertThat(access.get("token_use")).as("identity-only unless apiAccess").isEqualTo("identity");
        assertThat(VERIFIER.verify(j.get("access_token").asString())).isInstanceOf(JwtVerifier.Verified.class);

        var id = SignedJWT.parse(j.get("id_token").asString()).getPayload().toJSONObject();
        assertThat(id.get("aud")).isEqualTo(web.clientId());
        assertThat(id.get("nonce")).isEqualTo("nonce-" + RUN);
        assertThat(id.get("auth_time")).isNotNull();

        String refresh = j.get("refresh_token").asString();
        var stored = GRANTS.findByHash(RefreshToken.hash(refresh)).orElseThrow();
        assertThat(stored.oauthClientId()).as("the family is bound to the code's client").isEqualTo(web.clientId());
        assertThat(stored.tokenFamily()).as("a new family rooted at its first token").isEqualTo(stored.id());
        assertThat(stored.scopes()).containsExactly("openid", "offline_access");
    }

    @Test
    void noOpenidMeansNoIdTokenAndNoOfflineAccessMeansNoRefreshToken() {
        String code = mintCode(web, "profile", null, true);
        var r = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", web.clientId(), "code_verifier", VERIFIER_PKCE));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).has("id_token")).isFalse();
        assertThat(json(r).has("refresh_token")).isFalse();
    }

    @Test
    void aCodeIsSingleUseAndAWrongVerifierOrRedirectIsInvalidGrant() {
        String code = mintCode(web, "openid", null, true);
        var wrongVerifier = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", web.clientId(), "code_verifier", "not-the-verifier-" + RUN + "-padded-to-43-characters-x"));
        assertThat(wrongVerifier.statusCode()).isEqualTo(400);
        assertThat(json(wrongVerifier).get("error").asString()).isEqualTo("invalid_grant");

        // The failed exchange consumed the code: the correct verifier now fails too.
        var replay = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", web.clientId(), "code_verifier", VERIFIER_PKCE));
        assertThat(json(replay).get("error").asString()).isEqualTo("invalid_grant");

        String code2 = mintCode(web, "openid", null, true);
        var wrongRedirect = token(Map.of("grant_type", "authorization_code", "code", code2, "redirect_uri", REDIRECT + "/other",
                "client_id", web.clientId(), "code_verifier", VERIFIER_PKCE));
        assertThat(json(wrongRedirect).get("error_description").asString()).contains("Redirect URI");
    }

    @Test
    void aCodeIsBoundToTheAuthenticatedClientNotTheBodyClientId() {
        String code = mintCode(web, "openid", null, true);
        var r = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", web.clientId(), "code_verifier", VERIFIER_PKCE), basic(api.clientId(), SECRET));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error_description").asString()).contains("client_id does not match");

        var asApi = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "code_verifier", VERIFIER_PKCE), basic(api.clientId(), SECRET));
        assertThat(json(asApi).get("error").asString()).isEqualTo("invalid_grant");
        assertThat(json(asApi).get("error_description").asString()).contains("Client ID mismatch");
    }

    @Test
    void aPublicClientPresentingASecretIsRefused() {
        var r = token(Map.of("grant_type", "authorization_code", "code", "c", "redirect_uri", REDIRECT,
                "client_id", web.clientId(), "client_secret", "anything"));
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("error").asString()).isEqualTo("invalid_client");
    }

    @Test
    void anApiAccessClientGetsAuthorityConfinedToItsOwnApplications() throws Exception {
        String code = mintCode(api, "openid billing:write crm:read", null, false);
        var r = token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT), basic(api.clientId(), SECRET));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var access = SignedJWT.parse(json(r).get("access_token").asString()).getPayload().toJSONObject();
        assertThat(access.get("token_use")).isEqualTo("api");
        assertThat(String.valueOf(access.get("roles"))).contains("billing-" + RUN + ":agent").doesNotContain("crm-");
        assertThat(String.valueOf(access.get("applications"))).contains(appA).doesNotContain(appB);
        assertThat(access.get("all_applications")).isEqualTo(false);
        assertThat(access.get("scope")).as("crm:read is outside the client's applications").isEqualTo("billing:write");

        var id = SignedJWT.parse(json(r).get("id_token").asString()).getPayload().toJSONObject();
        assertThat(String.valueOf(id.get("roles"))).doesNotContain("crm-");
    }

    @Test
    void aPortalSubjectIsRefusedWhileThePortalPlaneIsNotWired() {
        // This state has no PortalSubjects: a ptu_ code is refused, not misminted.
        var code = io.flowcatalyst.platform.auth.grant.AuthorizationCode.issue(web.clientId(), "ptu_" + RUN, REDIRECT, Instant.now())
                .withPkce(CHALLENGE_PKCE, "S256");
        GRANTS.insert(code);
        var r = token(Map.of("grant_type", "authorization_code", "code", code.code(), "redirect_uri", REDIRECT,
                "client_id", web.clientId(), "code_verifier", VERIFIER_PKCE));
        assertThat(json(r).get("error_description").asString()).contains("Portal subjects");
    }

    // ── /oauth/token: refresh_token ────────────────────────────────────────

    @Test
    void refreshRotatesTheTokenAndTheOldOneIsDeadAfterwards() throws Exception {
        String first = exchange(web, "openid offline_access").get("refresh_token").asString();
        var r = token(Map.of("grant_type", "refresh_token", "refresh_token", first, "client_id", web.clientId()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode j = json(r);
        String second = j.get("refresh_token").asString();
        assertThat(second).isNotEqualTo(first);
        assertThat(j.get("scope").asString()).isEqualTo("openid offline_access");
        assertThat(j.has("id_token")).as("openid & client-bound ⇒ id_token on refresh").isTrue();
        var access = SignedJWT.parse(j.get("access_token").asString()).getPayload().toJSONObject();
        assertThat(access.get("azp")).isEqualTo(web.clientId());

        // Rotated out longer ago than RefreshRotation.REPLAY_LEEWAY (backdated rather than
        // slept): presenting it now is a replay, not the client racing or retrying itself.
        DB.execute("update oauth_oidc_payloads set payload = jsonb_set(payload, '{revokedAt}', to_jsonb(?::text)) "
                + "where payload ->> 'tokenHash' = ?", Instant.now().minusSeconds(60).toString(),
                io.flowcatalyst.platform.auth.grant.RefreshToken.hash(first));
        var replay = token(Map.of("grant_type", "refresh_token", "refresh_token", first, "client_id", web.clientId()));
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(json(replay).get("error").asString()).isEqualTo("invalid_grant");
        // Reuse detection revoked the whole family, the fresh token included.
        var afterReplay = token(Map.of("grant_type", "refresh_token", "refresh_token", second, "client_id", web.clientId()));
        assertThat(json(afterReplay).get("error").asString()).isEqualTo("invalid_grant");
    }

    /// Inside the replay leeway a second presentation of a just-rotated token is
    /// the client itself (two requests refreshing at once, a lost response) —
    /// it gets a working token, and the first replacement keeps working.
    @Test
    void aRetryInsideTheLeewayGetsAWorkingTokenAndSignsNothingOut() throws Exception {
        String first = exchange(web, "openid offline_access").get("refresh_token").asString();
        var a = token(Map.of("grant_type", "refresh_token", "refresh_token", first, "client_id", web.clientId()));
        var b = token(Map.of("grant_type", "refresh_token", "refresh_token", first, "client_id", web.clientId()));
        assertThat(a.statusCode()).as(a.body()).isEqualTo(200);
        assertThat(b.statusCode()).as(b.body()).isEqualTo(200);
        for (var r : List.of(a, b)) {
            var next = token(Map.of("grant_type", "refresh_token", "refresh_token",
                    json(r).get("refresh_token").asString(), "client_id", web.clientId()));
            assertThat(next.statusCode()).as("each replacement still works: " + next.body()).isEqualTo(200);
        }
    }

    @Test
    void aRefreshTokenCannotBeUsedByAnotherClientNorBySlashAuthRefresh() {
        String refresh = exchange(web, "offline_access").get("refresh_token").asString();
        var other = token(Map.of("grant_type", "refresh_token", "refresh_token", refresh), basic(api.clientId(), SECRET));
        assertThat(other.statusCode()).isEqualTo(400);
        assertThat(json(other).get("error_description").asString()).isEqualTo("Token was not issued to this client");

        var spa = http.post("/auth/refresh", Json.write(Map.of("refreshToken", refresh)));
        assertThat(spa.statusCode()).isEqualTo(401);
        assertThat(spa.body()).contains("Token was not issued to this client");

        // Neither attempt consumed it: the owning client can still rotate.
        var own = token(Map.of("grant_type", "refresh_token", "refresh_token", refresh, "client_id", web.clientId()));
        assertThat(own.statusCode()).as(own.body()).isEqualTo(200);
    }

    @Test
    void slashAuthRefreshMintsAFullAuthorityApiTokenForAnUnboundToken() throws Exception {
        var issued = RefreshToken.issue(userId, Instant.now(), RefreshToken.TTL_SECONDS);
        GRANTS.insert(issued.token().withFamily(issued.token().id()));
        var r = http.post("/auth/refresh", Json.write(Map.of("refreshToken", issued.raw())));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode j = json(r);
        assertThat(j.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(j.get("expiresIn").asLong()).isEqualTo(TokenIssuer.ACCESS_TTL_SECONDS);
        assertThat(j.get("refreshToken").asString()).isNotEqualTo(issued.raw());
        var access = SignedJWT.parse(j.get("accessToken").asString()).getPayload().toJSONObject();
        assertThat(access.get("token_use")).isEqualTo("api");
        // Go's GenerateAccessToken(p) advertises no scope on a full-authority mint;
        // authority is re-derived from the roles claim per request (auth-core §3.1;
        // parity S2 pinned the switch token the same way).
        assertThat(access.get("scope")).as("no scope claim on a full-authority token").isNull();
        assertThat(String.valueOf(access.get("roles"))).contains("billing-" + RUN);

        var bad = http.post("/auth/refresh", "{not json");
        assertThat(bad.statusCode()).isEqualTo(400);
        var unknown = http.post("/auth/refresh", Json.write(Map.of("refreshToken", "nope")));
        assertThat(unknown.statusCode()).isEqualTo(401);
    }

    // ── /oauth/token: client_credentials ───────────────────────────────────

    @Test
    void aConfidentialClientMintsAnApiTokenAndTheSuccessIsRecordedAndStamped() throws Exception {
        int before = attempts(svc.clientId(), "SUCCESS");
        var r = token(Map.of("grant_type", "client_credentials"), basic(svc.clientId(), SECRET));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode j = json(r);
        assertThat(j.has("refresh_token")).isFalse();
        assertThat(j.has("id_token")).isFalse();
        assertThat(j.get("scope").asString()).isEqualTo("billing:read billing:write");
        var access = SignedJWT.parse(j.get("access_token").asString()).getPayload().toJSONObject();
        assertThat(access.get("sub")).isEqualTo(servicePrincipalId);
        assertThat(access.get("token_use")).isEqualTo("api");
        assertThat(attempts(svc.clientId(), "SUCCESS")).isEqualTo(before + 1);
        assertThat(DB.select(IAM_SERVICE_ACCOUNTS.LAST_USED_AT).from(IAM_SERVICE_ACCOUNTS)
                .where(IAM_SERVICE_ACCOUNTS.ID.eq(serviceAccountId)).fetchOne(IAM_SERVICE_ACCOUNTS.LAST_USED_AT)).isNotNull();
    }

    @Test
    void theSecretIsAcceptedFromTheBodyTooAndAWrongOneIsAFailureRow() {
        var body = token(Map.of("grant_type", "client_credentials", "client_id", svc.clientId(), "client_secret", SECRET));
        assertThat(body.statusCode()).as(body.body()).isEqualTo(200);

        int before = attempts(svc.clientId(), "FAILURE");
        var wrong = token(Map.of("grant_type", "client_credentials", "client_id", svc.clientId(), "client_secret", "wrong"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(json(wrong).get("error").asString()).isEqualTo("invalid_client");
        assertThat(attempts(svc.clientId(), "FAILURE")).isEqualTo(before + 1);
    }

    @Test
    void thePreviousSecretStillWorksInsideItsOverlapAndLeavesTheRotationSignal() {
        assertThat(CLIENTS.findById(svc.id()).orElseThrow().previousSecretLastUsedAt()).isNull();
        var r = token(Map.of("grant_type", "client_credentials"), basic(svc.clientId(), OLD_SECRET));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(CLIENTS.findById(svc.id()).orElseThrow().previousSecretLastUsedAt())
                .as("ruling A-22: the superseded secret's use is visible").isNotNull();

        // The current secret leaves the stamp alone.
        Instant stamp = CLIENTS.findById(svc.id()).orElseThrow().previousSecretLastUsedAt();
        token(Map.of("grant_type", "client_credentials"), basic(svc.clientId(), SECRET));
        assertThat(CLIENTS.findById(svc.id()).orElseThrow().previousSecretLastUsedAt()).isEqualTo(stamp);
    }

    // ── /oauth/token: login-attempt IP + user agent (owner ruling 2026-09-17) ──

    /// T1: a successful client_credentials grant stores the rightmost
    /// `X-Forwarded-For` hop and the `User-Agent` header on the SUCCESS row.
    /// Mutant: a call site (or [OAuthState#recordAttempt]) that passes null
    /// for either would leave the stored columns empty — asserting the
    /// exact non-null values, not merely that a row exists, pins this.
    @Test
    void aSuccessfulTokenRequestRecordsTheCallersIpAndUserAgent() {
        var r = token(Map.of("grant_type", "client_credentials"), basic(svc.clientId(), SECRET)[0], basic(svc.clientId(), SECRET)[1],
                "X-Forwarded-For", "203.0.113.9, 198.51.100.7", "User-Agent", "fc-test/1.0");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);

        var row = latestAttempt(svc.clientId(), "SUCCESS");
        assertThat(row.get(IAM_LOGIN_ATTEMPTS.IP_ADDRESS)).as("the rightmost X-Forwarded-For hop").isEqualTo("198.51.100.7");
        assertThat(row.get(IAM_LOGIN_ATTEMPTS.USER_AGENT)).isEqualTo("fc-test/1.0");
    }

    /// T2: the same treatment applies to a failure row — wiring the IP/UA
    /// derivation into only the success call site
    /// ([#mintClientCredentials]) while leaving the failure call sites
    /// ([#clientCredentials]) on the old signature would pass T1 but fail
    /// here.
    @Test
    void aFailedTokenRequestAlsoRecordsTheCallersIpAndUserAgent() {
        var r = token(Map.of("grant_type", "client_credentials", "client_id", svc.clientId(), "client_secret", "wrong"),
                "X-Forwarded-For", "203.0.113.9, 198.51.100.7", "User-Agent", "fc-test/1.0");
        assertThat(r.statusCode()).isEqualTo(401);

        var row = latestAttempt(svc.clientId(), "FAILURE");
        assertThat(row.get(IAM_LOGIN_ATTEMPTS.IP_ADDRESS)).isEqualTo("198.51.100.7");
        assertThat(row.get(IAM_LOGIN_ATTEMPTS.USER_AGENT)).isEqualTo("fc-test/1.0");
    }

    /// T3: with no `X-Forwarded-For`, the stored IP falls back to the
    /// request's own remote address (the test harness's loopback client),
    /// never left empty. Mutant: deriving the IP from the header alone
    /// (skipping [ClientIp]'s remote-address fallback) would store null here.
    @Test
    void withNoForwardedForHeaderTheIpFallsBackToTheRemoteAddress() {
        var r = token(Map.of("grant_type", "client_credentials"), basic(svc.clientId(), SECRET)[0], basic(svc.clientId(), SECRET)[1],
                "User-Agent", "fc-test/1.0");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);

        var row = latestAttempt(svc.clientId(), "SUCCESS");
        String ip = row.get(IAM_LOGIN_ATTEMPTS.IP_ADDRESS);
        assertThat(ip).as("must fall back to the remote address, never empty").isNotNull().isNotBlank().doesNotContain(":");
    }

    // ── keyed hashing migration (docs/spec/encryption.md §3) ───────────────

    /// A client provisioned with a legacy `encrypted:` ref still authenticates,
    /// and a successful match rewrites the row's ref to the hashed form — the
    /// load-bearing assertion is on the PERSISTED VALUE after reload, not on
    /// any method having been invoked.
    @Test
    void successfulClientCredentialsAuthMigratesTheCurrentSecretRefToTheHashedForm() {
        String secret = "migrate-secret-" + UUID.randomUUID();
        String principalId = principal("SERVICE", null, serviceAccount("migrate-" + RUN), null);
        OAuthClient c = OAuthClient.create("migrate-" + RUN, "Migrate " + RUN, ClientType.CONFIDENTIAL)
                .withSecretRef(ENC.encryptSecretRef(secret))
                .withGrantTypes(List.of("client_credentials"))
                .withPrincipalId(principalId);
        UOW.inTransaction(tx -> {
            CLIENTS.persist(c, tx.dbTx());
            return null;
        });
        try {
            assertThat(CLIENTS.findById(c.id()).orElseThrow().secretRef()).startsWith("encrypted:");

            var wrongBefore = token(Map.of("grant_type", "client_credentials"), basic(c.clientId(), "not-it"));
            assertThat(wrongBefore.statusCode()).as("a wrong secret against the legacy shape still fails").isEqualTo(401);

            var ok = token(Map.of("grant_type", "client_credentials"), basic(c.clientId(), secret));
            assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);

            String storedAfter = CLIENTS.findById(c.id()).orElseThrow().secretRef();
            assertThat(storedAfter).as("the successful match rewrote the ref to the hashed form")
                    .startsWith("hashed:v1:");

            // Transparent: the same plaintext still authenticates against the now-hashed
            // ref, and a wrong one still fails against it.
            var again = token(Map.of("grant_type", "client_credentials"), basic(c.clientId(), secret));
            assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
            var wrongAfter = token(Map.of("grant_type", "client_credentials"), basic(c.clientId(), "not-it"));
            assertThat(wrongAfter.statusCode()).isEqualTo(401);
        } finally {
            UOW.inTransaction(tx -> {
                CLIENTS.delete(c, tx.dbTx());
                return null;
            });
            DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(principalId)).execute();
        }
    }

    /// The mirror case for the previous (in-grace) secret: only the ref that
    /// actually matched is rewritten, and the current ref — which never
    /// matched — is left exactly as it was.
    @Test
    void successfulAuthOnAPreviousSecretMigratesOnlyThePreviousRef() {
        String secret = "migrate2-secret-" + UUID.randomUUID();
        String oldSecret = "migrate2-old-" + UUID.randomUUID();
        String principalId = principal("SERVICE", null, serviceAccount("migrate2-" + RUN), null);
        OAuthClient seed = OAuthClient.create("migrate2-" + RUN, "Migrate2 " + RUN, ClientType.CONFIDENTIAL)
                .withSecretRef(ENC.encryptSecretRef(oldSecret))
                .withGrantTypes(List.of("client_credentials"))
                .withPrincipalId(principalId);
        OAuthClient c = seed.rotateSecret(ENC.encryptSecretRef(secret), Duration.ofHours(1), Instant.now()).client();
        UOW.inTransaction(tx -> {
            CLIENTS.persist(c, tx.dbTx());
            return null;
        });
        try {
            var r = token(Map.of("grant_type", "client_credentials"), basic(c.clientId(), oldSecret));
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);

            OAuthClient reloaded = CLIENTS.findById(c.id()).orElseThrow();
            assertThat(reloaded.previousSecretRef()).as("the ref that matched (previous) is migrated")
                    .startsWith("hashed:v1:");
            assertThat(reloaded.secretRef()).as("the ref that did NOT match (current) is untouched")
                    .startsWith("encrypted:");

            var again = token(Map.of("grant_type", "client_credentials"), basic(c.clientId(), oldSecret));
            assertThat(again.statusCode()).as("still authenticates now that the previous ref is hashed").isEqualTo(200);
        } finally {
            UOW.inTransaction(tx -> {
                CLIENTS.delete(c, tx.dbTx());
                return null;
            });
            DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(principalId)).execute();
        }
    }

    /// With no app key configured, verification fails closed exactly as
    /// decryption does today — even the RIGHT secret is rejected, because
    /// there is no key to check it (or write against) with.
    @Test
    void withNoEncryptionConfiguredTheRightSecretStillFailsClosed() {
        String secret = "noenc-secret-" + UUID.randomUUID();
        String principalId = principal("SERVICE", null, serviceAccount("noenc-" + RUN), null);
        OAuthClient c = OAuthClient.create("noenc-" + RUN, "NoEnc " + RUN, ClientType.CONFIDENTIAL)
                .withSecretRef(ENC.encryptSecretRef(secret))
                .withGrantTypes(List.of("client_credentials"))
                .withPrincipalId(principalId);
        UOW.inTransaction(tx -> {
            CLIENTS.persist(c, tx.dbTx());
            return null;
        });
        try {
            OAuthState noEnc = new OAuthState(CLIENTS, PRINCIPALS, null, GRANTS,
                    new RefreshRotation(GRANTS, Clock.systemUTC(), RefreshToken.TTL_SECONDS), ISSUER_UNDER_TEST, new AccessTokenReader(VERIFIER),
                    RESOLVER, ClaimLabels.of(new ClientRepository(DS), new ApplicationRepository(DS)), Optional.empty(),
                    ATTEMPTS, new RateLimit.NoopStore(), RateLimit.Policies.fromEnv(new io.flowcatalyst.server.EnvReader(Map.of())),
                    new Governor(new Governor.Config(60, 1000)), KEYS, ISSUER, Clock.systemUTC(), null, PORTAL_APPS, RefreshToken.TTL_SECONDS);
            try (var h = TestHttp.routes(routes -> {
                HttpError.install(routes);
                OAuthTokenApi.register(routes, noEnc);
            })) {
                var form = "grant_type=client_credentials";
                var creds = basic(c.clientId(), secret);
                var r = h.post("/oauth/token", form, "Content-Type", "application/x-www-form-urlencoded", creds[0], creds[1]);
                assertThat(r.statusCode()).as("no app key configured -> the right secret still fails closed").isEqualTo(401);
            }
        } finally {
            UOW.inTransaction(tx -> {
                CLIENTS.delete(c, tx.dbTx());
                return null;
            });
            DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(principalId)).execute();
        }
    }

    @Test
    void anExplicitScopeOutsideTheCeilingIsInvalidScopeAndInsideItNarrows() {
        var outside = token(Map.of("grant_type", "client_credentials", "scope", "crm:read"), basic(svc.clientId(), SECRET));
        assertThat(outside.statusCode()).isEqualTo(400);
        assertThat(json(outside).get("error").asString()).isEqualTo("invalid_scope");

        var inside = token(Map.of("grant_type", "client_credentials", "scope", "billing:read crm:read"), basic(svc.clientId(), SECRET));
        assertThat(inside.statusCode()).isEqualTo(200);
        assertThat(json(inside).get("scope").asString()).isEqualTo("billing:read");
    }

    @Test
    void aPublicClientAndAClientWithoutTheGrantCannotUseClientCredentials() {
        var pub = token(Map.of("grant_type", "client_credentials", "client_id", web.clientId(), "client_secret", "x"));
        assertThat(pub.statusCode()).isEqualTo(401);
        assertThat(json(pub).get("error").asString()).isEqualTo("unauthorized_client");

        var notGranted = token(Map.of("grant_type", "client_credentials"), basic(api.clientId(), SECRET));
        assertThat(notGranted.statusCode()).isEqualTo(401);
        assertThat(json(notGranted).get("error").asString()).isEqualTo("unauthorized_client");
    }

    /// S2.3: client_credentials mints only for an active SERVICE principal. A
    /// client linked to a USER — however it came to be — would hand that
    /// user's full authority to whoever holds the client secret.
    @Test
    void aClientLinkedToAUserPrincipalMintsNothing() {
        String userPid = principal("USER", "linked-" + RUN + "@example.com", null, null);
        var userBound = OAuthClient.create("userbound-" + RUN, "UserBound " + RUN, ClientType.CONFIDENTIAL)
                .withSecretRef(ENC.encryptSecretRef(SECRET))
                .withGrantTypes(List.of("client_credentials"))
                .withPrincipalId(userPid);
        UOW.inTransaction(tx -> {
            CLIENTS.persist(userBound, tx.dbTx());
            return null;
        });
        try {
            int before = attempts(userBound.clientId(), "FAILURE");
            var r = token(Map.of("grant_type", "client_credentials"), basic(userBound.clientId(), SECRET));
            assertThat(r.statusCode()).as("mutant: any principal type mints — " + r.body()).isEqualTo(400);
            assertThat(json(r).get("error").asString()).isEqualTo("unauthorized_client");
            assertThat(json(r).has("access_token")).isFalse();
            assertThat(attempts(userBound.clientId(), "FAILURE")).as("the refusal is recorded").isEqualTo(before + 1);
        } finally {
            UOW.inTransaction(tx -> {
                CLIENTS.delete(userBound, tx.dbTx());
                return null;
            });
            DB.deleteFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.eq(userBound.clientId())).execute();
            DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(userPid)).execute();
        }
    }

    /// Owner ruling 2026-09-06 #11 (RFC 6749 §5.2): a confidential client with no
    /// linked principal is the client's misconfiguration — 400 unauthorized_client,
    /// not 500 — and the refusal is recorded like every other failure on this grant.
    @Test
    void aClientWithoutALinkedPrincipalIsUnauthorizedClientNotAServerError() {
        int before = attempts(unbound.clientId(), "FAILURE");
        var r = token(Map.of("grant_type", "client_credentials"), basic(unbound.clientId(), SECRET));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("unauthorized_client");
        assertThat(json(r).get("error_description").asString()).isEqualTo("Client is not configured for this grant");
        assertThat(attempts(unbound.clientId(), "FAILURE")).as("the attempt row Go writes too").isEqualTo(before + 1);
    }

    @Test
    void basicAndBodyClientIdsMustAgree() {
        var r = token(Map.of("grant_type", "client_credentials", "client_id", web.clientId()), basic(svc.clientId(), SECRET));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("invalid_request");
    }

    @Test
    void aGrantTheClientDoesNotListIsUnauthorizedClientBeforeDispatch() {
        // §6.2a step 4 runs before step 6: the allow-list answers first, even
        // for a grant the server does not implement at all.
        var r = token(Map.of("grant_type", "password", "client_id", web.clientId()));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("unauthorized_client");
        assertThat(json(r).get("error_description").asString()).contains("'password'");
    }

    @Test
    void aFloodFromOneClientIdIsAnRfc6749RateLimitAnswer() {
        Governor tight = new Governor(new Governor.Config(1, 1));
        try (var h = TestHttp.routes(routes -> {
            HttpError.install(routes);
            OAuthState s = new OAuthState(CLIENTS, PRINCIPALS, null, GRANTS, new RefreshRotation(GRANTS, Clock.systemUTC(), RefreshToken.TTL_SECONDS),
                    ISSUER_UNDER_TEST, new AccessTokenReader(VERIFIER), RESOLVER, ClaimLabels.none(), Optional.of(ENC), null,
                    null, RateLimit.Policies.fromEnv(new io.flowcatalyst.server.EnvReader(Map.of())), tight, KEYS, ISSUER, Clock.systemUTC(), null, PORTAL_APPS, RefreshToken.TTL_SECONDS);
            OAuthTokenApi.register(routes, s);
        })) {
            String form = "grant_type=client_credentials";
            var first = h.post("/oauth/token", form, "Content-Type", "application/x-www-form-urlencoded", basic(svc.clientId(), SECRET)[0], basic(svc.clientId(), SECRET)[1]);
            assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
            var second = h.post("/oauth/token", form, "Content-Type", "application/x-www-form-urlencoded", basic(svc.clientId(), SECRET)[0], basic(svc.clientId(), SECRET)[1]);
            assertThat(second.statusCode()).isEqualTo(429);
            assertThat(second.headers().firstValue("Retry-After")).isPresent();
            assertThat(json(second).get("error").asString()).as("ruling C-Q27: RFC shape").isEqualTo("rate_limit_exceeded");
        }
    }

    // ── /oauth/token: developer credential ─────────────────────────────────

    @Test
    void aDeveloperMintsWithTheirPrincipalIdAndDeveloperSecret() throws Exception {
        int before = attempts(developerId, "SUCCESS");
        var r = token(Map.of("grant_type", "client_credentials", "client_id", developerId, "client_secret", DEV_SECRET));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var access = SignedJWT.parse(json(r).get("access_token").asString()).getPayload().toJSONObject();
        assertThat(access.get("sub")).isEqualTo(developerId);
        assertThat(String.valueOf(access.get("scope"))).contains("crm:read");
        assertThat(attempts(developerId, "SUCCESS")).isEqualTo(before + 1);

        // `developerId` was seeded with a legacy `encrypted:` ref (`@BeforeAll`); the
        // successful match above must have rewritten it to the hashed form
        // (`docs/spec/encryption.md` §3 — asserts the PERSISTED VALUE changed, not
        // merely that a method was called). Holds regardless of test execution
        // order: once migrated it stays migrated, and a later run of this same
        // assertion still finds it hashed.
        String storedRef = DB.select(IAM_PRINCIPALS.DEV_CLIENT_SECRET_REF).from(IAM_PRINCIPALS)
                .where(IAM_PRINCIPALS.ID.eq(developerId)).fetchOne(IAM_PRINCIPALS.DEV_CLIENT_SECRET_REF);
        assertThat(storedRef).as("a successful developer-credential match migrates the ref to hashed:v1:")
                .startsWith("hashed:v1:");

        int failures = attempts(developerId, "FAILURE");
        var wrong = token(Map.of("grant_type", "client_credentials", "client_id", developerId, "client_secret", "wrong"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(attempts(developerId, "FAILURE")).isEqualTo(failures + 1);

        // Transparent: the real secret still authenticates now that the ref is hashed.
        var again = token(Map.of("grant_type", "client_credentials", "client_id", developerId, "client_secret", DEV_SECRET));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    }

    @Test
    void withoutTheDeveloperRoleTheSecretIsUselessAndNothingIsRecorded() {
        int before = attempts(noRoleDeveloperId, "FAILURE");
        var r = token(Map.of("grant_type", "client_credentials", "client_id", noRoleDeveloperId, "client_secret", DEV_SECRET));
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("error").asString()).isEqualTo("invalid_client");
        assertThat(attempts(noRoleDeveloperId, "FAILURE")).as("a role miss is not a credential attempt").isEqualTo(before);
    }

    // ── /oauth/introspect, /oauth/revoke ───────────────────────────────────

    @Test
    void introspectionReportsTheMintingClientAsClientId() throws Exception {
        JsonNode t = exchange(web, "openid");
        String access = t.get("access_token").asString();
        long exp = SignedJWT.parse(access).getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond();
        var r = http.post("/oauth/introspect", "token=" + URLEncoder.encode(access, StandardCharsets.UTF_8),
                "Content-Type", "application/x-www-form-urlencoded", "Authorization", "Bearer " + access);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode j = json(r);
        assertThat(j.get("active").asBoolean()).isTrue();
        assertThat(j.get("sub").asString()).isEqualTo(userId);
        assertThat(j.get("client_id").asString()).as("ruling C-Q26: azp").isEqualTo(web.clientId());
        assertThat(j.get("exp").asLong()).isEqualTo(exp);
        assertThat(j.get("iss").asString()).isEqualTo(ISSUER);
        assertThat(j.get("token_type").asString()).isEqualTo("Bearer");

        var garbage = http.post("/oauth/introspect", "token=garbage", "Content-Type", "application/x-www-form-urlencoded",
                "Authorization", "Bearer " + access);
        assertThat(json(garbage).get("active").asBoolean()).isFalse();
        assertThat(json(garbage).has("sub")).isFalse();

        var unauthenticated = http.post("/oauth/introspect", "token=" + access, "Content-Type", "application/x-www-form-urlencoded");
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        var byClient = http.post("/oauth/introspect", "token=" + URLEncoder.encode(access, StandardCharsets.UTF_8),
                "Content-Type", "application/x-www-form-urlencoded", basic(svc.clientId(), SECRET)[0], basic(svc.clientId(), SECRET)[1]);
        assertThat(json(byClient).get("active").asBoolean()).isTrue();
    }

    @Test
    void revocationKillsARefreshTokenAndAlwaysAnswers200() {
        JsonNode t = exchange(web, "openid offline_access");
        String refresh = t.get("refresh_token").asString();
        var r = http.post("/oauth/revoke", "token=" + URLEncoder.encode(refresh, StandardCharsets.UTF_8),
                "Content-Type", "application/x-www-form-urlencoded", "Authorization", "Bearer " + t.get("access_token").asString());
        assertThat(r.statusCode()).isEqualTo(200);
        var afterwards = token(Map.of("grant_type", "refresh_token", "refresh_token", refresh, "client_id", web.clientId()));
        assertThat(json(afterwards).get("error").asString()).isEqualTo("invalid_grant");

        var unknown = http.post("/oauth/revoke", "token=nothing", "Content-Type", "application/x-www-form-urlencoded",
                "Authorization", "Bearer " + t.get("access_token").asString());
        assertThat(unknown.statusCode()).isEqualTo(200);
    }

    // ── /oauth/userinfo ────────────────────────────────────────────────────

    @Test
    void userinfoIsConfinedToTheRelyingPartyLikeTheIdToken() {
        String access = exchange(api, "openid").get("access_token").asString();
        var r = http.get("/oauth/userinfo", "Authorization", "Bearer " + access);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode j = json(r);
        assertThat(j.get("sub").asString()).isEqualTo(userId);
        assertThat(j.get("email").asString()).isEqualTo(userEmail);
        assertThat(j.get("roles").toString()).contains("billing-" + RUN + ":agent").doesNotContain("crm-");
        assertThat(j.get("applications").toString()).contains(appA).doesNotContain(appB);

        String unscoped = exchange(web, "openid").get("access_token").asString();
        var full = http.get("/oauth/userinfo", "Authorization", "Bearer " + unscoped);
        assertThat(json(full).get("roles").toString()).as("an unscoped client sees everything").contains("crm-");

        assertThat(http.get("/oauth/userinfo").statusCode()).isEqualTo(401);
        assertThat(http.get("/oauth/userinfo", "Authorization", "Bearer nope").statusCode()).isEqualTo(401);
    }

    // ── discovery ──────────────────────────────────────────────────────────

    @Test
    void discoveryAdvertisesRs256AndJwksCarriesTheSigningKey() {
        var d = json(http.get("/.well-known/openid-configuration"));
        assertThat(d.get("issuer").asString()).isEqualTo(ISSUER);
        assertThat(d.get("token_endpoint").asString()).isEqualTo(ISSUER + "/oauth/token");
        assertThat(d.get("id_token_signing_alg_values_supported").toString()).as("ruling A-16").isEqualTo("[\"RS256\"]");
        assertThat(d.get("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");

        var jwks = json(http.get("/.well-known/jwks.json"));
        JsonNode key = jwks.get("keys").get(0);
        assertThat(key.get("kid").asString()).isEqualTo(KEYS.kid());
        assertThat(key.get("alg").asString()).isEqualTo("RS256");
        BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(key.get("n").asString()));
        assertThat(n).isEqualTo(KEYS.publicKey().getModulus());
        assertThat(key.get("n").asString()).doesNotStartWith("A").doesNotContain("=");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static HttpResponse<String> authorize(Map<String, String> params, String... headers) {
        var q = new StringBuilder();
        params.forEach((k, v) -> q.append(q.isEmpty() ? "?" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return http.get("/oauth/authorize" + q, headers);
    }

    private static HttpResponse<String> token(Map<String, String> form, String... headers) {
        var b = new StringBuilder();
        form.forEach((k, v) -> b.append(b.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        String[] all = new String[headers.length + 2];
        all[0] = "Content-Type";
        all[1] = "application/x-www-form-urlencoded";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return http.post("/oauth/token", b.toString(), all);
    }

    private static String[] basic(String clientId, String secret) {
        String raw = URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return new String[] {"Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8))};
    }

    private static String[] sessionCookie(String principalId) {
        return new String[] {"Cookie", "fc_session=" + ISSUER_UNDER_TEST.sessionToken(principalId, userEmail)};
    }

    /// A code minted through the real authorize endpoint with a session cookie.
    private static String mintCode(OAuthClient client, String scope, String nonce, boolean pkce) {
        var params = new LinkedHashMap<String, String>();
        params.put("response_type", "code");
        params.put("client_id", client.clientId());
        params.put("redirect_uri", REDIRECT);
        params.put("state", "st-" + UUID.randomUUID());
        params.put("scope", scope);
        if (nonce != null) params.put("nonce", nonce);
        if (pkce) params.put("code_challenge", CHALLENGE_PKCE);
        var r = authorize(params, sessionCookie(userId));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(307);
        String code = query(location(r)).get("code");
        assertThat(code).as(location(r)).isNotNull();
        return code;
    }

    private static JsonNode exchange(OAuthClient client, String scope) {
        boolean pkce = client.pkceRequired();
        String code = mintCode(client, scope, null, pkce);
        var form = new HashMap<String, String>(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT));
        HttpResponse<String> r;
        if (client.secretRef() == null) {
            form.put("client_id", client.clientId());
            if (pkce) form.put("code_verifier", VERIFIER_PKCE);
            r = token(form);
        } else {
            r = token(form, basic(client.clientId(), SECRET));
        }
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r);
    }

    private static String location(HttpResponse<String> r) {
        return r.headers().firstValue("Location").orElseThrow(() -> new AssertionError("no Location: " + r.statusCode() + " " + r.body()));
    }

    private static Map<String, String> query(String url) {
        String q = URI.create(url.startsWith("/") ? "http://x" + url : url).getRawQuery();
        var out = new LinkedHashMap<String, String>();
        if (q == null) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static int attempts(String identifier, String outcome) {
        return DB.fetchCount(IAM_LOGIN_ATTEMPTS, IAM_LOGIN_ATTEMPTS.IDENTIFIER.eq(identifier).and(IAM_LOGIN_ATTEMPTS.OUTCOME.eq(outcome)));
    }

    /// The most recently written attempt row for `identifier`/`outcome` —
    /// read right after this test's own request, so no other test's row can
    /// be the newest one yet.
    private static org.jooq.Record latestAttempt(String identifier, String outcome) {
        return DB.selectFrom(IAM_LOGIN_ATTEMPTS)
                .where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.eq(identifier).and(IAM_LOGIN_ATTEMPTS.OUTCOME.eq(outcome)))
                .orderBy(IAM_LOGIN_ATTEMPTS.ATTEMPTED_AT.desc())
                .limit(1)
                .fetchOne();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static String app(String code) {
        String id = EntityType.APPLICATION.generate();
        DB.insertInto(APP_APPLICATIONS)
                .set(APP_APPLICATIONS.ID, id).set(APP_APPLICATIONS.TYPE, "APPLICATION")
                .set(APP_APPLICATIONS.CODE, code).set(APP_APPLICATIONS.NAME, code)
                .set(APP_APPLICATIONS.ACTIVE, true)
                .set(APP_APPLICATIONS.CREATED_AT, NOW).set(APP_APPLICATIONS.UPDATED_AT, NOW)
                .execute();
        return id;
    }

    private static String role(String applicationId, String applicationCode, String name, List<String> permissions) {
        String id = EntityType.ROLE.generate();
        DB.insertInto(IAM_ROLES)
                .set(IAM_ROLES.ID, id).set(IAM_ROLES.APPLICATION_ID, applicationId)
                .set(IAM_ROLES.APPLICATION_CODE, applicationCode)
                .set(IAM_ROLES.NAME, name).set(IAM_ROLES.DISPLAY_NAME, name)
                .set(IAM_ROLES.SOURCE, "DATABASE").set(IAM_ROLES.CLIENT_MANAGED, false)
                .set(IAM_ROLES.CREATED_AT, NOW).set(IAM_ROLES.UPDATED_AT, NOW)
                .execute();
        for (String p : permissions) {
            DB.insertInto(IAM_ROLE_PERMISSIONS).set(IAM_ROLE_PERMISSIONS.ROLE_ID, id).set(IAM_ROLE_PERMISSIONS.PERMISSION, p).execute();
        }
        return name;
    }

    private static String principal(String type, String email, String serviceAccountId, String devSecretRef) {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, type).set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.NAME, "P " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, email == null ? null : "example.com")
                .set(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID, serviceAccountId)
                .set(IAM_PRINCIPALS.DEV_CLIENT_SECRET_REF, devSecretRef)
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW)
                .execute();
        return id;
    }

    private static String serviceAccount(String code) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        DB.insertInto(IAM_SERVICE_ACCOUNTS)
                .set(IAM_SERVICE_ACCOUNTS.ID, id).set(IAM_SERVICE_ACCOUNTS.CODE, code).set(IAM_SERVICE_ACCOUNTS.NAME, code)
                .set(IAM_SERVICE_ACCOUNTS.ACTIVE, true).set(IAM_SERVICE_ACCOUNTS.SCOPE, "ANCHOR")
                .set(IAM_SERVICE_ACCOUNTS.CREATED_AT, NOW).set(IAM_SERVICE_ACCOUNTS.UPDATED_AT, NOW)
                .execute();
        return id;
    }

    private static void assignRole(String principalId, String roleName) {
        DB.insertInto(IAM_PRINCIPAL_ROLES)
                .set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, principalId).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, roleName)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "ADMIN_ASSIGNED").set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, NOW)
                .execute();
    }

    private static void grantApplication(String principalId, String applicationId) {
        DB.insertInto(IAM_PRINCIPAL_APPLICATION_ACCESS)
                .set(IAM_PRINCIPAL_APPLICATION_ACCESS.PRINCIPAL_ID, principalId)
                .set(IAM_PRINCIPAL_APPLICATION_ACCESS.APPLICATION_ID, applicationId)
                .set(IAM_PRINCIPAL_APPLICATION_ACCESS.GRANTED_AT, NOW)
                .execute();
    }
}
