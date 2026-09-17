package io.flowcatalyst.platform.auth.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.authadmin.IdpRoleMapping;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.PrincipalType;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.seed.PlatformEventSchemas;
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
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_LOGIN_ATTEMPTS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDP_ROLE_MAPPINGS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_LOGIN_STATES;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static org.assertj.core.api.Assertions.assertThat;

/// The OIDC bridge end to end (`docs/spec/auth-identity.md` §4 with the
/// §0.5 rulings) against a fake identity provider served by a second
/// Javalin: real discovery, a real JWKS, a real code exchange, a real
/// signed id_token — nothing about the handshake is stubbed inside the
/// bridge.
class OidcBridgeTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final Encryption ENC = Encryption.withKey(Encryption.generateKey());
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final String ISSUER = "http://localhost:8080";
    private static final String CLIENT_ID = "rp-" + RUN;
    private static final String CLIENT_SECRET = "rp-secret-" + RUN;

    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final EmailDomainMappingRepository MAPPINGS = new EmailDomainMappingRepository(DS);
    private static final IdentityProviderRepository IDPS = new IdentityProviderRepository(DS);
    private static final RoleRepository ROLES = new RoleRepository(DS);
    private static final IdpRoleMappingRepository ROLE_MAPPINGS = new IdpRoleMappingRepository(DS);
    private static final OAuthClientRepository OAUTH_CLIENTS = new OAuthClientRepository(DS, new ApplicationRepository(DS));
    private static final LoginStateRepository STATES = new LoginStateRepository(DS);
    private static final LoginAttemptRepository ATTEMPTS = new LoginAttemptRepository(DS);
    private static final JwtVerifier VERIFIER = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));

    // ── the fake identity provider ────────────────────────────────────────
    private static RSAKey idpKey;
    private static RSAKey rogueKey;
    private static TestHttp idp;
    private static String idpBase;
    /// The claims the next id_token carries (the test sets `nonce` from the authorize redirect).
    private static final AtomicReference<JWTClaimsSet.Builder> NEXT_CLAIMS = new AtomicReference<>();
    private static final AtomicReference<Boolean> SIGN_WITH_ROGUE_KEY = new AtomicReference<>(false);
    private static final AtomicReference<Boolean> OMIT_ID_TOKEN = new AtomicReference<>(false);
    private static final AtomicReference<Map<String, String>> LAST_TOKEN_FORM = new AtomicReference<>();
    private static final AtomicReference<String> LAST_TOKEN_AUTH = new AtomicReference<>();
    /// The next `access_token` the fake token endpoint returns; `null` keeps
    /// the default opaque `"x"` (spec `docs/spec/oidc-logged-in-event.md` T3).
    private static final AtomicReference<String> NEXT_ACCESS_TOKEN = new AtomicReference<>();

    // ── fixtures ──────────────────────────────────────────────────────────
    private static String oidcDomain;        // single-tenant, ANCHOR, role sync on, secret set
    private static String tenantDomain;      // requires tid "tid-" + RUN
    private static String internalDomain;
    private static String oidcIdpId;
    private static String multiIdpId;        // multi-tenant, issuer pattern
    private static String multiDomain;
    private static String roleAllowed;       // platform role names
    private static String roleNotAllowed;
    private static String appId;
    private static OAuthClient rp;
    private static final List<String> mappingIds = new ArrayList<>();
    private static final List<String> idpIds = new ArrayList<>();
    private static final List<String> roleMappingIds = new ArrayList<>();
    private static final List<String> emails = new ArrayList<>();
    private static TestHttp http;

    @BeforeAll
    static void start() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var kp = gen.generateKeyPair();
        idpKey = new RSAKey.Builder((RSAPublicKey) kp.getPublic()).privateKey((RSAPrivateKey) kp.getPrivate()).keyID("idp-" + RUN).build();
        var rogue = gen.generateKeyPair();
        rogueKey = new RSAKey.Builder((RSAPublicKey) rogue.getPublic()).privateKey((RSAPrivateKey) rogue.getPrivate()).keyID("rogue").build();
        idp = TestHttp.routes(routes -> {
            routes.get("/.well-known/openid-configuration", ctx -> ctx.json(Map.of(
                    "issuer", idpBase, "authorization_endpoint", idpBase + "/authorize",
                    "token_endpoint", idpBase + "/token", "jwks_uri", idpBase + "/jwks")));
            routes.get("/jwks", ctx -> ctx.contentType("application/json").result(new JWKSet(idpKey.toPublicJWK()).toString()));
            routes.post("/token", ctx -> {
                var form = new LinkedHashMap<String, String>();
                for (String pair : ctx.body().split("&")) {
                    int eq = pair.indexOf('=');
                    form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
                LAST_TOKEN_FORM.set(form);
                LAST_TOKEN_AUTH.set(ctx.header("Authorization"));
                String accessToken = NEXT_ACCESS_TOKEN.get() == null ? "x" : NEXT_ACCESS_TOKEN.get();
                if (OMIT_ID_TOKEN.get()) {
                    ctx.json(Map.of("access_token", accessToken, "token_type", "Bearer"));
                    return;
                }
                var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGN_WITH_ROGUE_KEY.get() ? "rogue" : idpKey.getKeyID()).build(),
                        NEXT_CLAIMS.get().build());
                jwt.sign(new RSASSASigner(SIGN_WITH_ROGUE_KEY.get() ? rogueKey : idpKey));
                ctx.json(Map.of("access_token", accessToken, "token_type", "Bearer", "id_token", jwt.serialize()));
            });
        });
        idpBase = "http://localhost:" + idp.port();

        appId = app("bridge-" + RUN);
        roleAllowed = role("bridge-" + RUN, "reader");
        roleNotAllowed = role("bridge-" + RUN, "admin");
        String allowedRoleId = DB.select(IAM_ROLES.ID).from(IAM_ROLES).where(IAM_ROLES.NAME.eq(roleAllowed)).fetchOne(IAM_ROLES.ID);

        oidcDomain = "oidc-" + RUN + ".example";
        tenantDomain = "tenant-" + RUN + ".example";
        internalDomain = "internal-" + RUN + ".example";
        multiDomain = "multi-" + RUN + ".example";
        var oidcIdp = IdentityProvider.create("idp-oidc-" + RUN, "OIDC " + RUN, IdentityProviderType.OIDC)
                .withOidc(idpBase, CLIENT_ID, ENC.encryptSecretRef(CLIENT_SECRET), false, null)
                .withRoleSync(true, List.of(allowedRoleId));
        oidcIdpId = oidcIdp.id();
        var multiIdp = IdentityProvider.create("idp-multi-" + RUN, "Multi " + RUN, IdentityProviderType.OIDC)
                .withOidc(idpBase, CLIENT_ID, null, true, "^http://tenant-[a-z]+\\.example$");
        multiIdpId = multiIdp.id();
        var internalIdp = IdentityProvider.create("idp-int-" + RUN, "Internal " + RUN, IdentityProviderType.INTERNAL);
        persistIdp(oidcIdp);
        persistIdp(multiIdp);
        persistIdp(internalIdp);
        persistMapping(EmailDomainMapping.create(EmailDomain.parse(oidcDomain), oidcIdpId, ScopeType.ANCHOR));
        persistMapping(EmailDomainMapping.create(EmailDomain.parse(tenantDomain), oidcIdpId, ScopeType.ANCHOR)
                .update(new EmailDomainMapping.Changes(null, null, null, "tid-" + RUN, null, null, null, null)));
        persistMapping(EmailDomainMapping.create(EmailDomain.parse(multiDomain), multiIdpId, ScopeType.ANCHOR));
        persistMapping(EmailDomainMapping.create(EmailDomain.parse(internalDomain), internalIdp.id(), ScopeType.ANCHOR));
        persistRoleMapping(IdpRoleMapping.create("OIDC", "Reader-" + RUN, roleAllowed));
        persistRoleMapping(IdpRoleMapping.create("OIDC", "Admin-" + RUN, roleNotAllowed));

        rp = OAuthClient.create("spa-" + RUN, "SPA " + RUN, ClientType.PUBLIC)
                .withRedirectUris(List.of("https://spa-" + RUN + ".example/cb"))
                .withPostLogoutRedirectUris(List.of("https://spa-" + RUN + ".example/bye", "https://*.spa-" + RUN + ".example/bye"))
                .withGrantTypes(List.of("authorization_code"));
        UOW.inTransaction(tx -> { OAUTH_CLIENTS.persist(rp, tx.dbTx()); return null; });

        var clients = new OidcClients(IDPS, MAPPINGS, Optional.of(ENC),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Clock.systemUTC(), Duration.ofMinutes(10));
        var state = new OidcBridgeApi.State(clients, STATES, PRINCIPALS, MAPPINGS, IDPS, ATTEMPTS, ROLE_MAPPINGS, ROLES, OAUTH_CLIENTS, UOW,
                new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER)), new SessionCookie(false, (int) TokenIssuer.SESSION_TTL_SECONDS), OidcBridgeApi.PortalSink.disabled(),
                ISSUER, Clock.systemUTC());
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            OidcBridgeApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        idp.close();
        for (String e : emails) {
            PRINCIPALS.findByEmail(e).ifPresent(p -> {
                DB.deleteFrom(IAM_PRINCIPAL_ROLES).where(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID.eq(p.id())).execute();
                DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(p.id())).execute();
            });
        }
        UOW.inTransaction(tx -> { OAUTH_CLIENTS.delete(rp, tx.dbTx()); return null; });
        DB.deleteFrom(OAUTH_OIDC_LOGIN_STATES).where(OAUTH_OIDC_LOGIN_STATES.IDENTITY_PROVIDER_ID.in(idpIds)).execute();
        DB.deleteFrom(OAUTH_IDP_ROLE_MAPPINGS).where(OAUTH_IDP_ROLE_MAPPINGS.ID.in(roleMappingIds)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.in(mappingIds)).execute();
        DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.in(idpIds)).execute();
        DB.deleteFrom(IAM_ROLES).where(IAM_ROLES.APPLICATION_ID.eq(appId)).execute();
        DB.deleteFrom(APP_APPLICATIONS).where(APP_APPLICATIONS.ID.eq(appId)).execute();
    }

    // ── /auth/oidc/login ───────────────────────────────────────────────────

    @Test
    void loginNeedsADomainOrAProviderAndRefusesAnInternalOrUnmappedDomain() {
        var none = http.get("/auth/oidc/login");
        assertThat(none.statusCode()).isEqualTo(400);
        assertThat(json(none).get("error").asString()).isEqualTo("DOMAIN_REQUIRED");

        var internal = http.get("/auth/oidc/login?domain=" + internalDomain);
        assertThat(internal.statusCode()).isEqualTo(400);
        assertThat(json(internal).get("error").asString()).isEqualTo("OIDC_NOT_CONFIGURED");

        var unmapped = http.get("/auth/oidc/login?domain=nobody-" + RUN + ".example");
        assertThat(unmapped.statusCode()).isEqualTo(500);
        assertThat(json(unmapped).get("error").asString()).isEqualTo("OIDC_RESOLVE_FAILED");
    }

    @Test
    void loginRedirectsToTheProviderWithPkceStateAndNonceAndStoresATenMinuteState() {
        var r = http.get("/auth/oidc/login?email=Someone@" + oidcDomain.toUpperCase(Locale.ROOT) + "&returnUrl=/reports");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
        String loc = location(r);
        assertThat(loc).startsWith(idpBase + "/authorize?");
        Map<String, String> q = query(loc);
        assertThat(q).containsEntry("client_id", CLIENT_ID).containsEntry("response_type", "code")
                .containsEntry("scope", "openid profile email").containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", ISSUER + "/auth/oidc/callback");
        assertThat(q.get("state")).hasSize(43);
        assertThat(q.get("nonce")).hasSize(43);
        var stored = STATES.consume(q.get("state")).orElseThrow();
        assertThat(stored.emailDomain()).as("lower-cased, derived from the legacy email param").isEqualTo(oidcDomain);
        assertThat(stored.nonce()).isEqualTo(q.get("nonce"));
        assertThat(OidcProvider.s256(stored.codeVerifier())).isEqualTo(q.get("code_challenge"));
        assertThat(stored.returnUrl()).isEqualTo("/reports");
        assertThat(Duration.between(stored.createdAt(), stored.expiresAt())).isEqualTo(Duration.ofMinutes(10));
        assertThat(STATES.consume(q.get("state"))).as("consumed exactly once").isEmpty();
    }

    // ── /auth/oidc/callback ────────────────────────────────────────────────

    @Test
    void aFirstLoginProvisionsThePrincipalSyncsMappedRolesSetsTheCookieAndLands() throws Exception {
        String email = "New.Person-" + RUN + "@" + oidcDomain;
        emails.add(email.toLowerCase(Locale.ROOT));
        Map<String, String> q = begin("domain=" + oidcDomain + "&return_url=/reports");
        idTokenFor(q, email).claim("name", "New Person").claim("roles", List.of("Reader-" + RUN, "Admin-" + RUN, "Unknown-" + RUN));

        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=code-" + RUN);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
        assertThat(location(r)).isEqualTo("/reports");
        String cookie = r.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).startsWith("fc_session=").contains("HttpOnly").contains("SameSite=Lax");
        String token = cookie.substring("fc_session=".length(), cookie.indexOf(';'));
        assertThat(VERIFIER.verify(token)).isInstanceOf(JwtVerifier.Verified.class);

        // The exchange carried the PKCE verifier and the client secret as Basic auth.
        assertThat(LAST_TOKEN_FORM.get()).containsEntry("grant_type", "authorization_code").containsEntry("code", "code-" + RUN)
                .containsEntry("redirect_uri", ISSUER + "/auth/oidc/callback").containsEntry("client_id", CLIENT_ID)
                .containsKey("code_verifier");
        assertThat(OidcProvider.s256(LAST_TOKEN_FORM.get().get("code_verifier"))).isEqualTo(q.get("code_challenge"));
        assertThat(LAST_TOKEN_AUTH.get()).startsWith("Basic ");

        Principal p = PRINCIPALS.findByEmail(email.toLowerCase(Locale.ROOT)).orElseThrow();
        assertThat(p.type()).isEqualTo(PrincipalType.USER);
        assertThat(p.scope()).as("the mapping's scope").isEqualTo(UserScope.ANCHOR);
        assertThat(p.isFederated()).as("provisioned as OIDC: no password ever").isTrue();
        assertThat(p.roles()).extracting(RoleAssignment::role).as("mapped + allow-listed only").containsExactly(roleAllowed);
        assertThat(p.roles().getFirst().assignmentSource()).isEqualTo(RoleAssignment.IDP_SYNC);
        assertThat(((JwtVerifier.Verified) VERIFIER.verify(token)).claims().subject()).isEqualTo(p.id());

        // Replaying the callback: the state is burned.
        var replay = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=code-" + RUN);
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(json(replay).get("error").asString()).isEqualTo("INVALID_STATE");
    }

    @Test
    void aReturningUserKeepsAdminRolesLosesDroppedIdpRolesAndHasTheEmailCaseHealed() {
        String email = "returning-" + RUN + "@" + oidcDomain;
        emails.add(email);
        String pid = userRow("Returning-" + RUN + "@" + oidcDomain);
        DB.insertInto(IAM_PRINCIPAL_ROLES).set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, pid).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, roleNotAllowed)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "ADMIN_ASSIGNED").set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, NOW).execute();
        DB.insertInto(IAM_PRINCIPAL_ROLES).set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, pid).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, roleAllowed)
                .set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "IDP_SYNC").set(IAM_PRINCIPAL_ROLES.ASSIGNED_AT, NOW).execute();

        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email).claim("roles", List.of()); // the IdP now sends no roles
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
        assertThat(location(r)).isEqualTo("/dashboard");

        Principal p = PRINCIPALS.findById(pid).orElseThrow();
        assertThat(p.email()).as("self-healed to lower case").isEqualTo(email);
        assertThat(p.roles()).extracting(RoleAssignment::role).as("the admin-assigned role survives; the synced one is dropped")
                .containsExactly(roleNotAllowed);
    }

    @Test
    void theLandingIsTheChainedAuthorizeRequestOrASafeRelativeUrl() {
        String email = "landing-" + RUN + "@" + oidcDomain;
        emails.add(email);
        Map<String, String> q = begin("domain=" + oidcDomain + "&oauth_client_id=spa&oauth_redirect_uri=https://spa.example/cb&oauth_state=st&oauth_scope=openid&oauth_code_challenge=ch");
        idTokenFor(q, email);
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(location(r)).isEqualTo("/oauth/authorize?response_type=code&client_id=spa&redirect_uri=https%3A%2F%2Fspa.example%2Fcb&scope=openid&state=st&code_challenge=ch");

        for (String bad : List.of("https://evil.example", "//evil.example", "/\\evil.example", "")) {
            Map<String, String> q2 = begin("domain=" + oidcDomain + "&return_url=" + URLEncoder.encode(bad, StandardCharsets.UTF_8));
            idTokenFor(q2, email);
            var r2 = http.get("/auth/oidc/callback?state=" + q2.get("state") + "&code=c");
            assertThat(location(r2)).as("return_url " + bad).isEqualTo("/dashboard");
        }
    }

    @Test
    void theIdTokenIsBoundToTheStateTheDomainAndTheTenant() {
        String email = "bound-" + RUN + "@" + oidcDomain;
        emails.add(email);

        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email).claim("nonce", "not-the-nonce");
        assertThat(json(http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c")).get("error").asString()).isEqualTo("NONCE_MISMATCH");

        q = begin("domain=" + oidcDomain);
        idTokenFor(q, "someone@other-" + RUN + ".example");
        var domain = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(domain.statusCode()).isEqualTo(403);
        assertThat(json(domain).get("error").asString()).isEqualTo("EMAIL_DOMAIN_MISMATCH");

        q = begin("domain=" + oidcDomain);
        idTokenFor(q, "guest_outlook.com#EXT#@" + oidcDomain);
        assertThat(json(http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c")).get("error").asString()).isEqualTo("EXTERNAL_GUEST");

        q = begin("domain=" + oidcDomain);
        idTokenFor(q, null);
        assertThat(json(http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c")).get("error").asString()).isEqualTo("NO_EMAIL");

        // preferred_username stands in for email.
        q = begin("domain=" + oidcDomain);
        idTokenFor(q, null).claim("preferred_username", "pref-" + RUN + "@" + oidcDomain);
        emails.add("pref-" + RUN + "@" + oidcDomain);
        assertThat(http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c").statusCode()).isEqualTo(302);

        // The tenant-pinned domain needs a matching tid.
        String tenantEmail = "t-" + RUN + "@" + tenantDomain;
        emails.add(tenantEmail);
        q = begin("domain=" + tenantDomain);
        idTokenFor(q, tenantEmail);
        var noTid = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(noTid.statusCode()).isEqualTo(403);
        assertThat(json(noTid).get("message").asString()).contains("no tenant id");
        q = begin("domain=" + tenantDomain);
        idTokenFor(q, tenantEmail).claim("tid", "other");
        assertThat(json(http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c")).get("error").asString()).isEqualTo("TENANT_MISMATCH");
        q = begin("domain=" + tenantDomain);
        idTokenFor(q, tenantEmail).claim("tid", "tid-" + RUN);
        assertThat(http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c").statusCode()).isEqualTo(302);
    }

    @Test
    void aTokenFromAnotherKeyOrWithoutAnIdTokenIsRefusedWithAFixedMessage() {
        String email = "rogue-" + RUN + "@" + oidcDomain;
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email);
        SIGN_WITH_ROGUE_KEY.set(true);
        try {
            var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(json(r).get("error").asString()).isEqualTo("OIDC_VERIFY");
            assertThat(json(r).get("message").asString()).as("ruling Q3: no library text on the wire").isEqualTo("id_token verification failed");
        } finally {
            SIGN_WITH_ROGUE_KEY.set(false);
        }
        assertThat(PRINCIPALS.findByEmail(email)).as("nothing provisioned").isEmpty();

        q = begin("domain=" + oidcDomain);
        idTokenFor(q, email).expirationTime(Date.from(Instant.now().minusSeconds(600)));
        assertThat(json(http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c")).get("error").asString()).isEqualTo("OIDC_VERIFY");

        q = begin("domain=" + oidcDomain);
        idTokenFor(q, email).audience("someone-else");
        assertThat(json(http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c")).get("error").asString()).isEqualTo("OIDC_VERIFY");

        q = begin("domain=" + oidcDomain);
        idTokenFor(q, email);
        OMIT_ID_TOKEN.set(true);
        try {
            var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(json(r).get("error").asString()).isEqualTo("NO_ID_TOKEN");
        } finally {
            OMIT_ID_TOKEN.set(false);
        }
        var missing = http.get("/auth/oidc/callback?state=abc");
        assertThat(json(missing).get("error").asString()).isEqualTo("MISSING_PARAM");
    }

    @Test
    void aMultiTenantProviderAcceptsAnIssuerMatchingThePatternAndNothingElse() {
        String email = "multi-" + RUN + "@" + multiDomain;
        emails.add(email);
        Map<String, String> q = begin("domain=" + multiDomain);
        idTokenFor(q, email).issuer("http://tenant-acme.example");
        var ok = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(302);

        q = begin("domain=" + multiDomain);
        idTokenFor(q, email).issuer("http://evil.example");
        var bad = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(bad.statusCode()).isEqualTo(403);
        assertThat(json(bad).get("error").asString()).isEqualTo("OIDC_VERIFY");
    }

    @Test
    void providerDirectLoginBindsToTheProvidersDomainsAndProvisionsAnInertUser() {
        var r = http.get("/auth/oidc/login?provider_id=" + oidcIdpId);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
        Map<String, String> q = query(location(r));
        String outside = "outsider-" + RUN + "@elsewhere-" + RUN + ".example";
        idTokenFor(q, outside);
        var refused = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(json(refused).get("message").asString()).contains("not allowed for this identity provider");

        r = http.get("/auth/oidc/login?provider_id=" + oidcIdpId);
        q = query(location(r));
        String inside = "direct-" + RUN + "@" + tenantDomain; // routed to this provider by its mapping
        emails.add(inside);
        idTokenFor(q, inside).claim("roles", List.of("Reader-" + RUN));
        var ok = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(302);
        Principal p = PRINCIPALS.findByEmail(inside).orElseThrow();
        assertThat(p.scope()).as("an inert portal-style user").isEqualTo(UserScope.CLIENT);
        assertThat(p.clientId()).isNull();
        assertThat(p.allApplications()).isFalse();
        assertThat(p.roles()).as("no role sync on the provider-direct path").isEmpty();

        var notOidc = http.get("/auth/oidc/login?provider_id=" + DB.select(OAUTH_IDENTITY_PROVIDERS.ID).from(OAUTH_IDENTITY_PROVIDERS)
                .where(OAUTH_IDENTITY_PROVIDERS.CODE.eq("idp-int-" + RUN)).fetchOne(OAUTH_IDENTITY_PROVIDERS.ID));
        assertThat(notOidc.statusCode()).isEqualTo(500);
        assertThat(json(notOidc).get("error").asString()).isEqualTo("OIDC_RESOLVE_FAILED");
    }

    @Test
    void anExpiredStateIsAsGoodAsUnknownAndTheBridgeIsThrottledPerAddress() {
        var expired = new LoginState("expired-" + RUN, oidcDomain, oidcIdpId, "edm", "n", "v", null, null, null,
                Instant.now().minus(Duration.ofMinutes(11)), Instant.now().minusSeconds(1));
        STATES.insert(expired);
        var r = http.get("/auth/oidc/callback?state=expired-" + RUN + "&code=c");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("INVALID_STATE");
        assertThat(DB.fetchCount(OAUTH_OIDC_LOGIN_STATES, OAUTH_OIDC_LOGIN_STATES.STATE.eq("expired-" + RUN)))
                .as("an expired row is left for the purger, not consumed").isEqualTo(1);

        try (var throttled = TestHttp.routes(routes -> {
            HttpError.install(routes);
            OidcIpLimit.register(routes, new io.flowcatalyst.platform.auth.ratelimit.Governor(new io.flowcatalyst.platform.auth.ratelimit.Governor.Config(1, 1)));
            routes.get("/auth/oidc/session/end", ctx -> ctx.json(Map.of("message", "Session ended")));
            routes.get("/portal/authorize", ctx -> ctx.result("ok"));
            routes.get("/auth/login", ctx -> ctx.result("untouched"));
        })) {
            assertThat(throttled.get("/auth/oidc/session/end").statusCode()).isEqualTo(200);
            var second = throttled.get("/portal/authorize");
            assertThat(second.statusCode()).as("one bucket for both prefixes").isEqualTo(429);
            assertThat(second.headers().firstValue("Retry-After")).isPresent();
            assertThat(json(second).get("error").asString()).isEqualTo("TOO_MANY_REQUESTS");
            assertThat(throttled.get("/auth/login").statusCode()).as("other routes are outside the bucket").isEqualTo(200);
        }
    }

    // ── SSO login-attempt rows (spec docs/spec/sso-login-attempts.md) ───────
    //
    // Every callback in this section sends the spec's fixed
    // X-Forwarded-For / User-Agent pair; ClientIp.of keeps the rightmost
    // hop, so the recorded ip_address is always "198.51.100.7".

    private static final String ATTEMPT_XFF = "203.0.113.9, 198.51.100.7";
    private static final String ATTEMPT_UA = "fc-test/1.0";
    private static final String ATTEMPT_IP = "198.51.100.7";

    @Test
    void t1SuccessfulSsoLoginWritesExactlyOneSuccessRow() {
        String email = "attempt-ok-" + RUN + "@" + oidcDomain;
        emails.add(email);
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email);
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c", "X-Forwarded-For", ATTEMPT_XFF, "User-Agent", ATTEMPT_UA);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);

        String principalId = PRINCIPALS.findByEmail(email).orElseThrow().id();
        List<LoginAttempt> rows = ATTEMPTS.findRecentByIdentifier(email, 10);
        assertThat(rows).as("exactly one row for this identifier").hasSize(1);
        LoginAttempt row = rows.getFirst();
        assertThat(row.attemptType()).isEqualTo(AttemptType.USER_LOGIN);
        assertThat(row.outcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(row.identifier()).isEqualTo(email);
        assertThat(row.principalId()).isEqualTo(principalId);
        assertThat(row.ipAddress()).isEqualTo(ATTEMPT_IP);
        assertThat(row.userAgent()).isEqualTo(ATTEMPT_UA);
    }

    @Test
    void t2AnEmailDomainMismatchWritesOneFailureRowWithTheVerifiedIdentifierAndNoPrincipal() {
        String email = "attempt-mismatch-" + RUN + "@other-" + RUN + ".example";
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email);
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c", "X-Forwarded-For", ATTEMPT_XFF, "User-Agent", ATTEMPT_UA);
        assertThat(json(r).get("error").asString()).isEqualTo("EMAIL_DOMAIN_MISMATCH");

        List<LoginAttempt> rows = ATTEMPTS.findRecentByIdentifier(email, 10);
        assertThat(rows).as("exactly one row for this identifier").hasSize(1);
        LoginAttempt row = rows.getFirst();
        assertThat(row.outcome()).isEqualTo(AttemptOutcome.FAILURE);
        assertThat(row.failureReason()).isEqualTo("SSO: email domain not allowed");
        assertThat(row.identifier()).isEqualTo(email);
        assertThat(row.principalId()).isNull();
        assertThat(row.ipAddress()).isEqualTo(ATTEMPT_IP);
        assertThat(row.userAgent()).isEqualTo(ATTEMPT_UA);
        assertThat(PRINCIPALS.findByEmail(email)).as("no principal provisioned for a refused login").isEmpty();
    }

    @Test
    void t3ABadSignatureIdTokenWritesOneFailureRowWithNoIdentifier() {
        String email = "attempt-badsig-" + RUN + "@" + oidcDomain;
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email);
        SIGN_WITH_ROGUE_KEY.set(true);
        try {
            var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c", "X-Forwarded-For", ATTEMPT_XFF, "User-Agent", ATTEMPT_UA);
            assertThat(json(r).get("error").asString()).isEqualTo("OIDC_VERIFY");
        } finally {
            SIGN_WITH_ROGUE_KEY.set(false);
        }

        // OIDC_VERIFY never carries an identifier (the token is unverified), so the
        // row can't be found by identifier — it is found by the fixed reason + ip
        // instead, which no other OIDC_VERIFY test in this class sets (they send no
        // X-Forwarded-For, so their rows carry the loopback address).
        var rows = DB.selectFrom(IAM_LOGIN_ATTEMPTS)
                .where(IAM_LOGIN_ATTEMPTS.FAILURE_REASON.eq("SSO: id_token verification failed"))
                .and(IAM_LOGIN_ATTEMPTS.IP_ADDRESS.eq(ATTEMPT_IP))
                .fetch();
        assertThat(rows).as("exactly one OIDC_VERIFY row for this test's ip").hasSize(1);
        var row = rows.getFirst();
        assertThat(row.get(IAM_LOGIN_ATTEMPTS.OUTCOME)).isEqualTo(AttemptOutcome.FAILURE.name());
        assertThat(row.get(IAM_LOGIN_ATTEMPTS.IDENTIFIER)).as("never the unverified token's email").isNull();
        assertThat(row.get(IAM_LOGIN_ATTEMPTS.PRINCIPAL_ID)).isNull();
        assertThat(row.get(IAM_LOGIN_ATTEMPTS.USER_AGENT)).isEqualTo(ATTEMPT_UA);
    }

    @Test
    void t4AnUnknownOrExpiredStateWritesNoRow() {
        int before = DB.fetchCount(IAM_LOGIN_ATTEMPTS, IAM_LOGIN_ATTEMPTS.IP_ADDRESS.eq(ATTEMPT_IP).and(IAM_LOGIN_ATTEMPTS.USER_AGENT.eq(ATTEMPT_UA)));
        var r = http.get("/auth/oidc/callback?state=unknown-" + RUN + "&code=c", "X-Forwarded-For", ATTEMPT_XFF, "User-Agent", ATTEMPT_UA);
        assertThat(json(r).get("error").asString()).isEqualTo("INVALID_STATE");
        int after = DB.fetchCount(IAM_LOGIN_ATTEMPTS, IAM_LOGIN_ATTEMPTS.IP_ADDRESS.eq(ATTEMPT_IP).and(IAM_LOGIN_ATTEMPTS.USER_AGENT.eq(ATTEMPT_UA)));
        assertThat(after).as("an unknown/expired state writes nothing").isEqualTo(before);
    }

    @Test
    void t5APortalFlowStateWritesNoRowOnEitherASuccessfulOrARefusedIdentityCheck() {
        // A portal-plane handshake (docs/spec/auth-identity.md §5.6): provider-direct
        // (emailDomainMappingId "") with portalClientId set. oidcIdpId already allows
        // oidcDomain (the mapping fixture routes it there), so a matching-domain login
        // reaches state.portal() — a portal sink call, not an identity refusal — without
        // any portal-specific fixtures. portal_client_id is a real TSID column
        // (varchar(17)); rp's id is any already-persisted OAuthClient — PortalSink
        // never reads it.
        String okEmail = "attempt-portal-ok-" + RUN + "@" + oidcDomain;
        var okState = new LoginState("portal-ok-" + RUN, "", oidcIdpId, "", "portal-nonce-ok-" + RUN, "verifier-" + RUN,
                null, null, rp.id(), Instant.now(), Instant.now().plus(Duration.ofMinutes(10)));
        STATES.insert(okState);
        idTokenFor(Map.of("nonce", "portal-nonce-ok-" + RUN), okEmail);
        var ok = http.get("/auth/oidc/callback?state=portal-ok-" + RUN + "&code=c", "X-Forwarded-For", ATTEMPT_XFF, "User-Agent", ATTEMPT_UA);
        assertThat(ok.statusCode()).as("PortalSink.disabled(): the identity checks passed, only the sink is unwired").isEqualTo(500);
        assertThat(json(ok).get("error").asString()).isEqualTo("PORTAL_DISABLED");
        assertThat(ATTEMPTS.findRecentByIdentifier(okEmail, 10)).as("a successful portal-plane login writes no row").isEmpty();

        // A portal-flow state refused by an ordinary identity check (EMAIL_DOMAIN_MISMATCH,
        // reached before state.portal() is ever consulted): this is what actually exercises
        // the plane check, since record() is called here with a known identifier while
        // state.portal() is true — a passing identity check never calls record() at all.
        String refusedEmail = "attempt-portal-refused-" + RUN + "@other-" + RUN + ".example";
        var refusedState = new LoginState("portal-refused-" + RUN, "", oidcIdpId, "", "portal-nonce-refused-" + RUN, "verifier-" + RUN,
                null, null, rp.id(), Instant.now(), Instant.now().plus(Duration.ofMinutes(10)));
        STATES.insert(refusedState);
        idTokenFor(Map.of("nonce", "portal-nonce-refused-" + RUN), refusedEmail);
        var refused = http.get("/auth/oidc/callback?state=portal-refused-" + RUN + "&code=c", "X-Forwarded-For", ATTEMPT_XFF, "User-Agent", ATTEMPT_UA);
        assertThat(json(refused).get("error").asString()).isEqualTo("EMAIL_DOMAIN_MISMATCH");
        assertThat(ATTEMPTS.findRecentByIdentifier(refusedEmail, 10)).as("a refused portal-plane login writes no row either").isEmpty();
    }

    // ── UserLoggedIn event (spec docs/spec/oidc-logged-in-event.md) ─────────

    @Test
    void aSuccessfulOidcLoginEmitsExactlyOneLoggedInEventWithTheMethodAndProviderCode() {
        String email = "login-evt-" + RUN + "@" + oidcDomain;
        emails.add(email);
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email);
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);

        String userId = PRINCIPALS.findByEmail(email).orElseThrow().id();
        JsonNode data = onlyLoggedInEvent(userId);
        assertThat(data.path("userId").asString()).isEqualTo(userId);
        assertThat(data.path("loginMethod").asString()).isEqualTo("OIDC");
        assertThat(data.path("identityProviderCode").asString()).isEqualTo("idp-oidc-" + RUN);
    }

    @Test
    void federatedClaimsIdTokenCarriesACustomClaimAndNeverTheNonce() {
        String email = "claim-" + RUN + "@" + oidcDomain;
        emails.add(email);
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email).claim("department", "engineering");
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);

        String userId = PRINCIPALS.findByEmail(email).orElseThrow().id();
        JsonNode idToken = onlyLoggedInEvent(userId).path("federatedClaims").path("idToken");
        assertThat(idToken.path("department").asString()).as("a custom claim the fake IdP issued").isEqualTo("engineering");
        assertThat(idToken.has("nonce")).as("nonce is JOSE plumbing, never carried into the event").isFalse();
    }

    @Test
    void federatedClaimsAccessTokenIsTheJwtPayloadOrEmptyForAnOpaqueToken() throws Exception {
        String jwtEmail = "at-jwt-" + RUN + "@" + oidcDomain;
        emails.add(jwtEmail);
        var jwtAccessToken = new PlainJWT(new JWTClaimsSet.Builder().claim("scope", "widgets:read").build());
        NEXT_ACCESS_TOKEN.set(jwtAccessToken.serialize());
        try {
            Map<String, String> q = begin("domain=" + oidcDomain);
            idTokenFor(q, jwtEmail);
            var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
            assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
            String userId = PRINCIPALS.findByEmail(jwtEmail).orElseThrow().id();
            JsonNode accessToken = onlyLoggedInEvent(userId).path("federatedClaims").path("accessToken");
            assertThat(accessToken.path("scope").asString()).isEqualTo("widgets:read");
        } finally {
            NEXT_ACCESS_TOKEN.set(null);
        }

        String opaqueEmail = "at-opaque-" + RUN + "@" + oidcDomain;
        emails.add(opaqueEmail);
        Map<String, String> q2 = begin("domain=" + oidcDomain);
        idTokenFor(q2, opaqueEmail); // the fixture's default access_token, "x", is opaque
        var r2 = http.get("/auth/oidc/callback?state=" + q2.get("state") + "&code=c");
        assertThat(r2.statusCode()).as(r2.body()).isEqualTo(302);
        String opaqueUserId = PRINCIPALS.findByEmail(opaqueEmail).orElseThrow().id();
        JsonNode accessToken = onlyLoggedInEvent(opaqueUserId).path("federatedClaims").path("accessToken");
        assertThat(accessToken.properties()).as("an opaque access token decodes to {}").isEmpty();
    }

    @Test
    void flowcatalystClaimsRolesAndApplicationsReflectThisLoginsIdpRoleSync() {
        String email = "claims-role-" + RUN + "@" + oidcDomain;
        emails.add(email);
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email).claim("roles", List.of("Reader-" + RUN));
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);

        String userId = PRINCIPALS.findByEmail(email).orElseThrow().id();
        JsonNode claims = onlyLoggedInEvent(userId).path("flowcatalystClaims");
        assertThat(strings(claims.path("roles"))).as("granted by this login's own IdP role sync").contains(roleAllowed);
        assertThat(strings(claims.path("applications"))).as("the prefix before ':' in " + roleAllowed)
                .contains(roleAllowed.substring(0, roleAllowed.indexOf(':')));
        assertThat(strings(claims.path("clients"))).as("ANCHOR scope ⇒ the wildcard").containsExactly("*");
    }

    @Test
    void aFailedLoginEmitsNoLoggedInEvent() {
        String email = "nomatch-" + RUN + "@" + oidcDomain;
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email).claim("nonce", "not-the-nonce");
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(json(r).get("error").asString()).isEqualTo("NONCE_MISMATCH");

        assertThat(DB.fetch("SELECT id FROM msg_events WHERE type = ? AND data::text LIKE ?",
                "platform:iam:user:logged-in", "%" + email + "%"))
                .as("no login event for a login that never succeeded")
                .isEmpty();
    }

    @Test
    void theStoredEventValidatesAgainstTheSeededSchema() {
        String email = "schema-" + RUN + "@" + oidcDomain;
        emails.add(email);
        Map<String, String> q = begin("domain=" + oidcDomain);
        idTokenFor(q, email).claim("department", "engineering").claim("roles", List.of("Reader-" + RUN));
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);

        String userId = PRINCIPALS.findByEmail(email).orElseThrow().id();
        JsonNode data = onlyLoggedInEvent(userId);
        JsonNode schema = PlatformEventSchemas.all().get("platform:iam:user:logged-in");
        assertThat(schema).as("platform:iam:user:logged-in must be seeded").isNotNull();
        assertThat(schemaErrors(schema, data, "$")).as("stored event data vs its seeded schema").isEmpty();
    }

    // ── /auth/oidc/session/end ─────────────────────────────────────────────

    @Test
    void sessionEndClearsTheCookieAndOnlyRedirectsToARegisteredPostLogoutUri() throws Exception {
        var plain = http.get("/auth/oidc/session/end");
        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(json(plain).get("message").asString()).isEqualTo("Session ended");
        assertThat(plain.headers().firstValue("set-cookie").orElseThrow()).startsWith("fc_session=;");

        String bye = "https://spa-" + RUN + ".example/bye";
        var noClient = http.get("/auth/oidc/session/end?post_logout_redirect_uri=" + enc(bye));
        assertThat(noClient.statusCode()).isEqualTo(400);
        assertThat(json(noClient).get("error").asString()).isEqualTo("invalid_request");
        assertThat(json(noClient).get("error_description").asString()).contains("id_token_hint or client_id is required");

        var ok = http.get("/auth/oidc/session/end?client_id=" + rp.clientId() + "&post_logout_redirect_uri=" + enc(bye) + "&state=s1");
        assertThat(ok.statusCode()).isEqualTo(303);
        assertThat(location(ok)).isEqualTo(bye + "?state=s1");

        var wildcard = http.get("/auth/oidc/session/end?client_id=" + rp.clientId() + "&post_logout_redirect_uri=" + enc("https://acme.spa-" + RUN + ".example/bye"));
        assertThat(wildcard.statusCode()).isEqualTo(303);

        var unregistered = http.get("/auth/oidc/session/end?client_id=" + rp.clientId() + "&post_logout_redirect_uri=" + enc("https://evil.example/bye"));
        assertThat(unregistered.statusCode()).isEqualTo(400);
        assertThat(json(unregistered).get("error_description").asString()).contains("not in the client's registered");

        // The client can come from an id_token_hint's aud, unverified.
        var hint = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().audience(rp.clientId()).subject("x").build());
        hint.sign(new RSASSASigner(rogueKey));
        var viaHint = http.get("/auth/oidc/session/end?id_token_hint=" + hint.serialize() + "&post_logout_redirect_uri=" + enc(bye));
        assertThat(viaHint.statusCode()).isEqualTo(303);

        var unknown = http.get("/auth/oidc/session/end?client_id=nope&post_logout_redirect_uri=" + enc(bye));
        assertThat(json(unknown).get("error_description").asString()).contains("does not match any registered client");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /// Starts a login and returns the authorize redirect's query.
    private static Map<String, String> begin(String query) {
        var r = http.get("/auth/oidc/login?" + query);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
        return query(location(r));
    }

    /// The next id_token: valid for our client, nonce from the redirect, email as given (null omits it).
    private static JWTClaimsSet.Builder idTokenFor(Map<String, String> authorizeQuery, String email) {
        var b = new JWTClaimsSet.Builder().issuer(idpBase).audience(CLIENT_ID).subject("sub-" + UUID.randomUUID())
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce", authorizeQuery.get("nonce"));
        if (email != null) {
            b.claim("email", email);
        }
        NEXT_CLAIMS.set(b);
        return b;
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

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    /// The one `platform:iam:user:logged-in` event stored for `userId` —
    /// fails loudly if there isn't exactly one (T1's own assertion doubles
    /// as the fixture every other UserLoggedIn test builds on).
    private static JsonNode onlyLoggedInEvent(String userId) {
        var rows = DB.fetch("SELECT data::text AS data FROM msg_events WHERE type = ? AND subject = ?",
                "platform:iam:user:logged-in", "platform.user." + userId);
        assertThat(rows).as("exactly one platform:iam:user:logged-in event for " + userId).hasSize(1);
        return Json.MAPPER.readTree(rows.getFirst().get("data", String.class));
    }

    private static List<String> strings(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    // ── a minimal structural draft-07 checker (T6) ──────────────────────────
    //
    // Only the keywords PlatformEventSchemas actually uses — type (plain or
    // the [t, "null"] nullable pair), properties, required,
    // additionalProperties, items, enum, oneOf — read straight off the seeded
    // schema JsonNode, never a hand-duplicated list of expected fields, so a
    // schema that starts requiring a different key genuinely fails this.

    private static List<String> schemaErrors(JsonNode schema, JsonNode data, String path) {
        var errors = new ArrayList<String>();
        if (schema.has("oneOf")) {
            for (JsonNode sub : schema.get("oneOf")) {
                if (schemaErrors(sub, data, path).isEmpty()) {
                    return List.of();
                }
            }
            errors.add(path + ": matched none of oneOf");
            return errors;
        }
        JsonNode typeNode = schema.get("type");
        if (typeNode != null) {
            var types = new ArrayList<String>();
            if (typeNode.isArray()) {
                typeNode.forEach(t -> types.add(t.stringValue()));
            } else {
                types.add(typeNode.stringValue());
            }
            if (types.stream().noneMatch(t -> matchesType(t, data))) {
                errors.add(path + ": expected type " + types + ", got " + data.getNodeType());
                return errors;
            }
        }
        if (schema.has("enum")) {
            boolean ok = false;
            for (JsonNode e : schema.get("enum")) {
                if (e.equals(data)) ok = true;
            }
            if (!ok) errors.add(path + ": value not in enum");
        }
        if (data.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null) {
                for (JsonNode req : required) {
                    if (!data.has(req.stringValue())) {
                        errors.add(path + "." + req.stringValue() + ": required property missing");
                    }
                }
            }
            JsonNode props = schema.get("properties");
            var known = new HashSet<String>();
            if (props != null) {
                for (var e : props.properties()) {
                    known.add(e.getKey());
                    if (data.has(e.getKey())) {
                        errors.addAll(schemaErrors(e.getValue(), data.get(e.getKey()), path + "." + e.getKey()));
                    }
                }
            }
            JsonNode additional = schema.get("additionalProperties");
            if (additional != null && additional.isBoolean() && !additional.booleanValue()) {
                for (var e : data.properties()) {
                    if (!known.contains(e.getKey())) {
                        errors.add(path + "." + e.getKey() + ": additional property not allowed");
                    }
                }
            }
        } else if (data.isArray() && schema.has("items")) {
            JsonNode items = schema.get("items");
            int i = 0;
            for (JsonNode item : data) {
                errors.addAll(schemaErrors(items, item, path + "[" + i + "]"));
                i++;
            }
        }
        return errors;
    }

    private static boolean matchesType(String t, JsonNode n) {
        return switch (t) {
            case "object" -> n.isObject();
            case "array" -> n.isArray();
            case "string" -> n.isString();
            case "boolean" -> n.isBoolean();
            case "integer", "number" -> n.isNumber();
            case "null" -> n.isNull();
            default -> false;
        };
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static void persistIdp(IdentityProvider idp) {
        idpIds.add(idp.id());
        UOW.inTransaction(tx -> { IDPS.persist(idp, tx.dbTx()); return null; });
    }

    private static void persistMapping(EmailDomainMapping m) {
        mappingIds.add(m.id());
        UOW.inTransaction(tx -> { MAPPINGS.persist(m, tx.dbTx()); return null; });
    }

    private static void persistRoleMapping(IdpRoleMapping m) {
        roleMappingIds.add(m.id());
        UOW.inTransaction(tx -> { ROLE_MAPPINGS.persist(m, tx.dbTx()); return null; });
    }

    private static String app(String code) {
        String id = EntityType.APPLICATION.generate();
        DB.insertInto(APP_APPLICATIONS).set(APP_APPLICATIONS.ID, id).set(APP_APPLICATIONS.TYPE, "APPLICATION")
                .set(APP_APPLICATIONS.CODE, code).set(APP_APPLICATIONS.NAME, code).set(APP_APPLICATIONS.ACTIVE, true)
                .set(APP_APPLICATIONS.CREATED_AT, NOW).set(APP_APPLICATIONS.UPDATED_AT, NOW).execute();
        return id;
    }

    /// A role row; returns its full name.
    private static String role(String applicationCode, String shortName) {
        Role r = Role.create(applicationCode, shortName, shortName);
        UOW.inTransaction(tx -> { ROLES.persist(r.withApplicationId(appId), tx.dbTx()); return null; });
        return r.name();
    }

    private static String userRow(String email) {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.NAME, "R " + RUN).set(IAM_PRINCIPALS.ACTIVE, true).set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT))
                .set(IAM_PRINCIPALS.IDP_TYPE, "OIDC")
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW).execute();
        return id;
    }
}
