package io.flowcatalyst.platform.portalauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.grant.RefreshRotation;
import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.oauth.AccessTokenReader;
import io.flowcatalyst.platform.auth.oauth.OAuthState;
import io.flowcatalyst.platform.auth.oauth.OAuthTokenApi;
import io.flowcatalyst.platform.auth.oidc.LoginStateRepository;
import io.flowcatalyst.platform.auth.oidc.OidcBridgeApi;
import io.flowcatalyst.platform.auth.oidc.OidcClients;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.auth.token.ClaimLabels;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
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
import io.flowcatalyst.platform.portalidentity.PortalIdentityAccess;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentitySource;
import io.flowcatalyst.platform.portalidentity.PortalIdentityStatus;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleRepository;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.db.generated.Tables.IAM_AUTHORIZATION_CODES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_LOGIN_STATES;
import static io.flowcatalyst.db.generated.Tables.PORTAL_IDENTITIES;
import static io.flowcatalyst.db.generated.Tables.PORTAL_LOGIN_FLOWS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static org.assertj.core.api.Assertions.assertThat;

/// Portal SSO end to end (`docs/spec/auth-identity.md` §5.6, §5.8 with
/// ruling Q10): the parked flow, the start redirect through a fake
/// identity provider, the bridge's callback landing in the portal sink,
/// the `ptu_` code redeemed at the token endpoint.
class PortalSsoTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final Encryption ENC = Encryption.withKey(Encryption.generateKey());
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final String ISSUER = "http://localhost:8080";
    private static final String CLIENT_ID = "rp-" + RUN;

    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final EmailDomainMappingRepository MAPPINGS = new EmailDomainMappingRepository(DS);
    private static final IdentityProviderRepository IDPS = new IdentityProviderRepository(DS);
    private static final OAuthClientRepository OAUTH_CLIENTS = new OAuthClientRepository(DS, new ApplicationRepository(DS));
    private static final PortalIdentityRepository IDENTITIES = new PortalIdentityRepository(DS);
    private static final PortalLoginFlowRepository FLOWS = new PortalLoginFlowRepository(DS);
    private static final io.flowcatalyst.platform.portalapp.PortalAppRepository PORTAL_APPS =
            new io.flowcatalyst.platform.portalapp.PortalAppRepository(DS);
    private static final GrantStore GRANTS = new GrantStore(DS);
    private static final TokenIssuer TOKEN_ISSUER = new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER));
    private static final JwtVerifier VERIFIER = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));

    private static RSAKey idpKey;
    private static TestHttp idp;
    private static String idpBase;
    private static final AtomicReference<JWTClaimsSet.Builder> NEXT_CLAIMS = new AtomicReference<>();

    private static String domain;
    private static String idpId;
    private static String mappingId;
    private static String clientId;      // tnt_clients row — the portal's client
    private static OAuthClient portalClient;
    private static TestHttp http;

    @BeforeAll
    static void start() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var kp = gen.generateKeyPair();
        idpKey = new RSAKey.Builder((RSAPublicKey) kp.getPublic()).privateKey((RSAPrivateKey) kp.getPrivate()).keyID("idp-" + RUN).build();
        idp = TestHttp.routes(routes -> {
            routes.get("/.well-known/openid-configuration", ctx -> ctx.json(Map.of("issuer", idpBase,
                    "authorization_endpoint", idpBase + "/authorize", "token_endpoint", idpBase + "/token", "jwks_uri", idpBase + "/jwks")));
            routes.get("/jwks", ctx -> ctx.contentType("application/json").result(new JWKSet(idpKey.toPublicJWK()).toString()));
            routes.post("/token", ctx -> {
                var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(idpKey.getKeyID()).build(), NEXT_CLAIMS.get().build());
                jwt.sign(new RSASSASigner(idpKey));
                ctx.json(Map.of("access_token", "x", "token_type", "Bearer", "id_token", jwt.serialize()));
            });
        });
        idpBase = "http://localhost:" + idp.port();

        domain = "portal-" + RUN + ".example";
        var provider = IdentityProvider.create("idp-portal-" + RUN, "Portal IdP " + RUN, IdentityProviderType.OIDC)
                .withOidc(idpBase, CLIENT_ID, null, false, null);
        idpId = provider.id();
        var mapping = EmailDomainMapping.create(EmailDomain.parse(domain), idpId, ScopeType.ANCHOR);
        mappingId = mapping.id();
        clientId = tenantClient("portal-tenant-" + RUN);
        portalClient = OAuthClient.create("portal-" + RUN, "Portal " + RUN, ClientType.PUBLIC)
                .withRedirectUris(List.of("https://portal-" + RUN + ".example/cb"))
                .withGrantTypes(List.of("authorization_code"))
                .withPortalAndApiAccess(clientId, false);
        UOW.inTransaction(tx -> {
            IDPS.persist(provider, tx.dbTx());
            MAPPINGS.persist(mapping, tx.dbTx());
            OAUTH_CLIENTS.persist(portalClient, tx.dbTx());
            return null;
        });

        var oidcClients = new OidcClients(IDPS, MAPPINGS, Optional.of(ENC),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Clock.systemUTC(), Duration.ofMinutes(10));
        var states = new LoginStateRepository(DS);
        var sinkHolder = new AtomicReference<PortalSso>();
        var bridge = new OidcBridgeApi.State(oidcClients, states, PRINCIPALS, MAPPINGS, IDPS, null, new IdpRoleMappingRepository(DS),
                new RoleRepository(DS), OAUTH_CLIENTS, UOW, TOKEN_ISSUER, new SessionCookie(false, (int) TokenIssuer.SESSION_TTL_SECONDS),
                (ctx, st, claims) -> sinkHolder.get().complete(ctx, st, claims), ISSUER, Clock.systemUTC());
        var sso = new PortalSso(new PortalSso.State(FLOWS, IDENTITIES, new ClientRepository(DS), PORTAL_APPS, UOW, GRANTS, oidcClients, states,
                bridge, Clock.systemUTC()));
        sinkHolder.set(sso);
        var access = new PortalIdentityAccess(IDENTITIES, UOW);
        var oauth = new OAuthState(OAUTH_CLIENTS, PRINCIPALS, null, GRANTS, new RefreshRotation(GRANTS, Clock.systemUTC(), RefreshToken.TTL_SECONDS),
                TOKEN_ISSUER, new AccessTokenReader(VERIFIER), new DbClaimsResolver(PRINCIPALS, new RoleRepository(DS)),
                ClaimLabels.none(), Optional.of(ENC), null, null,
                RateLimit.Policies.fromEnv(new io.flowcatalyst.server.EnvReader(Map.of())), null, KEYS, ISSUER, Clock.systemUTC(), access,
                PORTAL_APPS, RefreshToken.TTL_SECONDS);
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            OidcBridgeApi.register(routes, bridge);
            sso.register(routes);
            OAuthTokenApi.register(routes, oauth);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        idp.close();
        DB.deleteFrom(IAM_AUTHORIZATION_CODES).where(IAM_AUTHORIZATION_CODES.PRINCIPAL_ID.like("ptu_%")).and(IAM_AUTHORIZATION_CODES.CLIENT_ID.eq(portalClient.clientId())).execute();
        DB.deleteFrom(PORTAL_IDENTITIES).where(PORTAL_IDENTITIES.CLIENT_ID.eq(clientId)).execute();
        DB.deleteFrom(PORTAL_LOGIN_FLOWS).where(PORTAL_LOGIN_FLOWS.PORTAL_CLIENT_ID.eq(clientId)).execute();
        DB.deleteFrom(OAUTH_OIDC_LOGIN_STATES).where(OAUTH_OIDC_LOGIN_STATES.IDENTITY_PROVIDER_ID.eq(idpId)).execute();
        // Covers portalClient plus every app-linked OAuth client the §5.2/§5.3 tests created.
        var oauthClientsTable = io.flowcatalyst.db.generated.Tables.OAUTH_CLIENTS;
        DB.deleteFrom(oauthClientsTable).where(oauthClientsTable.PORTAL_CLIENT_ID.eq(clientId)).execute();
        var portalAppsTable = io.flowcatalyst.db.generated.Tables.PORTAL_APPS;
        DB.deleteFrom(portalAppsTable).where(portalAppsTable.CLIENT_ID.eq(clientId)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.eq(mappingId)).execute();
        DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.eq(idpId)).execute();
        DB.deleteFrom(TNT_CLIENTS).where(TNT_CLIENTS.ID.eq(clientId)).execute();
    }

    @Test
    void theStartConsumesTheFlowAndTheSinkProvisionsTheIdentityAndIssuesAPortalCode() throws Exception {
        String redirect = "https://portal-" + RUN + ".example/cb";
        PortalLoginFlow flow = PortalLoginFlow.start(portalClient.clientId(), clientId, redirect, "openid", "st-" + RUN, "n-" + RUN, null, null, Instant.now());
        FLOWS.insert(flow);

        var missing = http.get("/portal/auth/oidc/login?flow=" + flow.id());
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json(missing).get("error").asString()).isEqualTo("MISSING_PARAM");

        var start = http.get("/portal/auth/oidc/login?flow=" + flow.id() + "&provider_id=" + idpId);
        assertThat(start.statusCode()).as(start.body()).isEqualTo(302);
        String loc = location(start);
        assertThat(loc).startsWith(idpBase + "/authorize?");
        Map<String, String> q = query(loc);
        assertThat(FLOWS.findLive(flow.id())).as("ruling Q10: the flow is consumed at SSO start").isEmpty();
        var again = http.get("/portal/auth/oidc/login?flow=" + flow.id() + "&provider_id=" + idpId);
        assertThat(json(again).get("error").asString()).isEqualTo("FLOW_EXPIRED");

        String email = "Jane-" + RUN + "@" + domain;
        NEXT_CLAIMS.set(new JWTClaimsSet.Builder().issuer(idpBase).audience(CLIENT_ID).subject("sub-" + RUN)
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce", q.get("nonce")).claim("email", email).claim("name", "Jane Doe"));
        var cb = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(cb.statusCode()).as(cb.body()).isEqualTo(302);
        Map<String, String> back = query(location(cb));
        assertThat(location(cb)).startsWith(redirect + "?code=");
        assertThat(back.get("state")).isEqualTo("st-" + RUN);
        assertThat(cb.headers().firstValue("set-cookie")).as("never an fc_session for the portal plane").isEmpty();

        PortalIdentity identity = IDENTITIES.findByClientAndEmail(clientId, email.toLowerCase(Locale.ROOT)).orElseThrow();
        assertThat(identity.source()).isEqualTo(PortalIdentitySource.JIT);
        assertThat(identity.status()).isEqualTo(PortalIdentityStatus.ACTIVE);
        assertThat(identity.name()).isEqualTo("Jane Doe");
        assertThat(identity.lastLoginAt()).isNotNull();
        var code = GRANTS.findCode(back.get("code")).orElseThrow();
        assertThat(code.principalId()).isEqualTo(identity.id());
        assertThat(code.nonce()).isEqualTo("n-" + RUN);

        // §5.8: the token endpoint redeems the ptu_ code into an identity token and a roleless id_token, no refresh token.
        var t = http.post("/oauth/token", "grant_type=authorization_code&code=" + enc(back.get("code")) + "&redirect_uri=" + enc(redirect)
                + "&client_id=" + portalClient.clientId(), "Content-Type", "application/x-www-form-urlencoded");
        assertThat(t.statusCode()).as(t.body()).isEqualTo(200);
        JsonNode tok = json(t);
        assertThat(tok.has("refresh_token")).isFalse();
        var access = SignedJWT.parse(tok.get("access_token").asString()).getPayload().toJSONObject();
        assertThat(access.get("sub")).isEqualTo(identity.id());
        assertThat(access.get("token_use")).isEqualTo("identity");
        var id = SignedJWT.parse(tok.get("id_token").asString()).getPayload().toJSONObject();
        assertThat(id.get("sub")).isEqualTo(identity.id());
        assertThat(id.get("email")).isEqualTo(email.toLowerCase(Locale.ROOT));
        assertThat(String.valueOf(id.get("roles"))).isEqualTo("[]");
        assertThat(id.get("nonce")).isEqualTo("n-" + RUN);

        // A suspended identity is bounced back with access_denied, and its code is refused at the token endpoint.
        UOW.inTransaction(tx -> { IDENTITIES.persist(identity.deactivate(), tx.dbTx()); return null; });
        PortalLoginFlow flow2 = PortalLoginFlow.start(portalClient.clientId(), clientId, redirect, "openid", "st2-" + RUN, null, null, null, Instant.now());
        FLOWS.insert(flow2);
        Map<String, String> q2 = query(location(http.get("/portal/auth/oidc/login?flow=" + flow2.id() + "&provider_id=" + idpId)));
        NEXT_CLAIMS.set(new JWTClaimsSet.Builder().issuer(idpBase).audience(CLIENT_ID).subject("sub-" + RUN)
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce", q2.get("nonce")).claim("email", email));
        var denied = http.get("/auth/oidc/callback?state=" + q2.get("state") + "&code=c");
        assertThat(denied.statusCode()).isEqualTo(302);
        Map<String, String> d = query(location(denied));
        assertThat(d.get("error")).isEqualTo("access_denied");
        assertThat(d.get("error_description")).isEqualTo("This account is suspended for this portal");
        assertThat(d.get("state")).isEqualTo("st2-" + RUN);
        assertThat(IDENTITIES.findById(identity.id()).orElseThrow().status()).as("SSO never self-reactivates").isEqualTo(PortalIdentityStatus.DISABLED);

        var stale = io.flowcatalyst.platform.auth.grant.AuthorizationCode.issue(portalClient.clientId(), identity.id(), redirect, Instant.now());
        GRANTS.insert(stale);
        var refused = http.post("/oauth/token", "grant_type=authorization_code&code=" + enc(stale.code()) + "&redirect_uri=" + enc(redirect)
                + "&client_id=" + portalClient.clientId(), "Content-Type", "application/x-www-form-urlencoded");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).get("error_description").asString()).isEqualTo("Portal identity not found or suspended");
    }

    @Test
    void anAccountOutsideTheProvidersDomainsIsRefusedBeforeTheSink() {
        String redirect = "https://portal-" + RUN + ".example/cb";
        PortalLoginFlow flow = PortalLoginFlow.start(portalClient.clientId(), clientId, redirect, null, "st3-" + RUN, null, null, null, Instant.now());
        FLOWS.insert(flow);
        Map<String, String> q = query(location(http.get("/portal/auth/oidc/login?flow=" + flow.id() + "&provider_id=" + idpId)));
        NEXT_CLAIMS.set(new JWTClaimsSet.Builder().issuer(idpBase).audience(CLIENT_ID).subject("s")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce", q.get("nonce")).claim("email", "outsider-" + RUN + "@elsewhere.example"));
        var r = http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asString()).isEqualTo("EMAIL_DOMAIN_MISMATCH");
        assertThat(IDENTITIES.findByClientAndEmail(clientId, "outsider-" + RUN + "@elsewhere.example")).isEmpty();
    }

    @Test
    void thePasswordSeamWritesTheHashOnlyForAnExistingIdentity() {
        var access = new PortalIdentityAccess(IDENTITIES, UOW);
        assertThat(access.setPasswordHash("ptu_missing", "h")).isFalse();
        assertThat(access.find("ptu_missing")).isEmpty();
        PortalIdentity pi = PortalIdentity.create(clientId, "seam-" + RUN + "@" + domain, "Seam", PortalIdentitySource.INVITE);
        UOW.inTransaction(tx -> { IDENTITIES.persist(pi, tx.dbTx()); return null; });
        assertThat(access.find(pi.id()).orElseThrow().active()).isTrue();
        assertThat(access.setPasswordHash(pi.id(), "hash-" + RUN)).isTrue();
        assertThat(IDENTITIES.findById(pi.id()).orElseThrow().passwordHash()).isEqualTo("hash-" + RUN);
        assertThat(access.findSubject(pi.id()).orElseThrow().email()).isEqualTo("seam-" + RUN + "@" + domain);
    }

    // ── App-linked SSO gate (portal-apps.md §5.2, §5.3, §5.4) ───────────────

    /// §5.2 step 2 first bullet ("first login grants it") + §5.4 (the
    /// id_token's three portal claims) + §5.3 (revoke, then a fresh code is
    /// `invalid_grant`). One flow, because the grant from step 1 is exactly
    /// what step 3 then revokes.
    @Test
    void firstSsoLoginJitGrantsTheLinkedAppAndTheIdTokenCarriesItsClaimsThenARevokedGrantIsRefusedAtRedemption() throws Exception {
        var app = io.flowcatalyst.platform.portalapp.PortalApp.create(clientId,
                io.flowcatalyst.platform.portalapp.PortalAppCode.parse("linked-" + RUN), "Linked App", null);
        UOW.inTransaction(tx -> { PORTAL_APPS.persist(app, tx.dbTx()); return null; });
        String redirect = "https://portal-app-" + RUN + ".example/cb";
        OAuthClient appClient = appLinkedClient("first", redirect, app.id());

        String email = "applinked-" + RUN + "@" + domain;
        var cb = ssoLogin(appClient, redirect, "st-app1-" + RUN, "n-app1-" + RUN, email, "App Linked");
        assertThat(cb.statusCode()).as(cb.body()).isEqualTo(302);
        Map<String, String> back = query(location(cb));
        assertThat(location(cb)).startsWith(redirect + "?code=");

        PortalIdentity identity = IDENTITIES.findByClientAndEmail(clientId, email).orElseThrow();
        assertThat(identity.source()).isEqualTo(PortalIdentitySource.JIT);
        assertThat(identity.hasApp(app.id())).as("first login grants the linked app").isTrue();

        var t = http.post("/oauth/token", "grant_type=authorization_code&code=" + enc(back.get("code")) + "&redirect_uri=" + enc(redirect)
                + "&client_id=" + appClient.clientId(), "Content-Type", "application/x-www-form-urlencoded");
        assertThat(t.statusCode()).as(t.body()).isEqualTo(200);
        var id = SignedJWT.parse(json(t).get("id_token").asString()).getPayload().toJSONObject();
        assertThat(id.get("portal_client_id")).isEqualTo(clientId);
        assertThat(id.get("portal_app_code")).isEqualTo(app.code());
        assertThat(id.get("portal_app_id")).isEqualTo(app.id());

        // §5.3: revoke, then a fresh code for the same identity is invalid_grant.
        UOW.inTransaction(tx -> { IDENTITIES.persist(identity.revoke(app.id()), tx.dbTx()); return null; });
        var freshCode = io.flowcatalyst.platform.auth.grant.AuthorizationCode.issue(appClient.clientId(), identity.id(), redirect, Instant.now());
        GRANTS.insert(freshCode);
        var refused = http.post("/oauth/token", "grant_type=authorization_code&code=" + enc(freshCode.code()) + "&redirect_uri=" + enc(redirect)
                + "&client_id=" + appClient.clientId(), "Content-Type", "application/x-www-form-urlencoded");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).get("error").asString()).isEqualTo("invalid_grant");
        assertThat(json(refused).get("error_description").asString()).isEqualTo("Portal identity has no access to this portal");
    }

    /// §5.2 step 2 last bullet: an EXISTING identity that lacks the app's
    /// grant is refused outright — no JIT grant is added for it.
    @Test
    void anExistingIdentityWithoutTheGrantIsRefusedAndGetsNoJitGrant() throws Exception {
        var app = io.flowcatalyst.platform.portalapp.PortalApp.create(clientId,
                io.flowcatalyst.platform.portalapp.PortalAppCode.parse("existing-" + RUN), "Existing App", null);
        UOW.inTransaction(tx -> { PORTAL_APPS.persist(app, tx.dbTx()); return null; });
        String redirect = "https://portal-app-existing-" + RUN + ".example/cb";
        OAuthClient appClient = appLinkedClient("existing", redirect, app.id());

        String email = "ungranted-" + RUN + "@" + domain;
        PortalIdentity pre = PortalIdentity.create(clientId, email, "Pre Existing", PortalIdentitySource.JIT);
        UOW.inTransaction(tx -> { IDENTITIES.persist(pre, tx.dbTx()); return null; });

        var cb = ssoLogin(appClient, redirect, "st-existing-" + RUN, "n-existing-" + RUN, email, null);
        assertThat(cb.statusCode()).isEqualTo(302);
        Map<String, String> back = query(location(cb));
        assertThat(back.get("error")).isEqualTo("access_denied");
        assertThat(back.get("error_description")).isEqualTo("You don't have access to this portal");

        assertThat(IDENTITIES.findById(pre.id()).orElseThrow().hasApp(app.id()))
                .as("an existing identity is never JIT-granted by the gate refusal").isFalse();
    }

    /// §5.2 step 1: an inactive linked app is refused before the identity is
    /// even looked up — no identity row appears for a brand-new email.
    @Test
    void anInactiveLinkedAppIsRefusedBeforeAnyIdentityLookup() throws Exception {
        var app = io.flowcatalyst.platform.portalapp.PortalApp.create(clientId,
                io.flowcatalyst.platform.portalapp.PortalAppCode.parse("inactive-" + RUN), "Inactive App", null);
        UOW.inTransaction(tx -> { PORTAL_APPS.persist(app.update(null, null, false), tx.dbTx()); return null; });
        String redirect = "https://portal-app-inactive-" + RUN + ".example/cb";
        OAuthClient appClient = appLinkedClient("inactive", redirect, app.id());

        String email = "neverseen-" + RUN + "@" + domain;
        var cb = ssoLogin(appClient, redirect, "st-inactive-" + RUN, "n-inactive-" + RUN, email, null);
        assertThat(cb.statusCode()).isEqualTo(302);
        Map<String, String> back = query(location(cb));
        assertThat(back.get("error")).isEqualTo("access_denied");
        assertThat(back.get("error_description")).isEqualTo("This portal is not currently available");
        assertThat(IDENTITIES.findByClientAndEmail(clientId, email)).as("refused before any identity lookup").isEmpty();
    }

    /// A legacy client-wide portal client (`portalClient`, no app link) is
    /// never gated — the existing happy-path test above already proves the
    /// grant and claims for it; this pins the id_token's absent app claims.
    @Test
    void legacyClientWideLoginsCarryOnlyThePortalClientIdClaim() throws Exception {
        String redirect = "https://portal-" + RUN + ".example/cb";
        String email = "legacyclaims-" + RUN + "@" + domain;
        var cb = ssoLogin(portalClient, redirect, "st-legacy-" + RUN, "n-legacy-" + RUN, email, "Legacy Claims");
        assertThat(cb.statusCode()).as(cb.body()).isEqualTo(302);
        Map<String, String> back = query(location(cb));

        var t = http.post("/oauth/token", "grant_type=authorization_code&code=" + enc(back.get("code")) + "&redirect_uri=" + enc(redirect)
                + "&client_id=" + portalClient.clientId(), "Content-Type", "application/x-www-form-urlencoded");
        assertThat(t.statusCode()).as(t.body()).isEqualTo(200);
        var id = SignedJWT.parse(json(t).get("id_token").asString()).getPayload().toJSONObject();
        assertThat(id.get("portal_client_id")).isEqualTo(clientId);
        assertThat(id).doesNotContainKey("portal_app_code").doesNotContainKey("portal_app_id");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /// Parks a flow against `oc`, drives the fake IdP round trip and lands
    /// on the sink's callback — the raw response, so callers can inspect
    /// either a success redirect (`code=…`) or an `access_denied` one.
    private static HttpResponse<String> ssoLogin(OAuthClient oc, String redirect, String state, String nonce, String email, String name) {
        PortalLoginFlow flow = PortalLoginFlow.start(oc.clientId(), clientId, redirect, "openid", state, nonce, null, null, Instant.now());
        FLOWS.insert(flow);
        Map<String, String> q = query(location(http.get("/portal/auth/oidc/login?flow=" + flow.id() + "&provider_id=" + idpId)));
        var claims = new JWTClaimsSet.Builder().issuer(idpBase).audience(CLIENT_ID).subject("sub-" + RUN)
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce", q.get("nonce")).claim("email", email);
        if (name != null) {
            claims.claim("name", name);
        }
        NEXT_CLAIMS.set(claims);
        return http.get("/auth/oidc/callback?state=" + q.get("state") + "&code=c");
    }

    /// A portal-flagged, active OAuth client linked to `appId` — the
    /// wire-level setter is unit B's (spec `portal-apps.md` §4.5); this test
    /// only needs the stored row `PortalAppRepository#findByOAuthClientId`
    /// resolves.
    private static OAuthClient appLinkedClient(String tag, String redirect, String appId) {
        OAuthClient oc = OAuthClient.create("portal-app-" + tag + "-" + RUN, "Portal App " + tag, ClientType.PUBLIC)
                .withRedirectUris(List.of(redirect))
                .withGrantTypes(List.of("authorization_code"))
                .withPortalAndApiAccess(clientId, false);
        OAuthClient linked = new OAuthClient(oc.id(), oc.clientId(), oc.clientName(), oc.clientType(), oc.secretRef(),
                oc.previousSecretRef(), oc.previousSecretExpiresAt(), oc.previousSecretLastUsedAt(), oc.redirectUris(),
                oc.postLogoutRedirectUris(), oc.grantTypes(), oc.defaultScopes(), oc.allowedOrigins(), oc.applicationIds(),
                oc.pkceRequired(), oc.active(), oc.principalId(), oc.portalClientId(), appId, oc.apiAccess(),
                oc.createdAt(), oc.updatedAt());
        UOW.inTransaction(tx -> { OAUTH_CLIENTS.persist(linked, tx.dbTx()); return null; });
        return linked;
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

    private static String tenantClient(String identifier) {
        String id = EntityType.CLIENT.generate();
        DB.insertInto(TNT_CLIENTS).set(TNT_CLIENTS.ID, id).set(TNT_CLIENTS.NAME, identifier).set(TNT_CLIENTS.IDENTIFIER, identifier)
                .set(TNT_CLIENTS.STATUS, "ACTIVE").set(TNT_CLIENTS.CREATED_AT, NOW).set(TNT_CLIENTS.UPDATED_AT, NOW).execute();
        return id;
    }
}
