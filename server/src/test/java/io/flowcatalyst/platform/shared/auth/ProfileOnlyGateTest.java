package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.login.BackoffCheck;
import io.flowcatalyst.platform.auth.login.BackoffPolicy;
import io.flowcatalyst.platform.auth.login.LoginApi;
import io.flowcatalyst.platform.auth.login.MfaChallenge;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static org.assertj.core.api.Assertions.assertThat;

/// The profile-only gate (`docs/spec/portal-apps.md` §6): a `before` filter
/// registered right after the [Authenticator]. Two halves:
///
/// - a decision-table sweep over synthetic (test-header) contexts — fast,
///   covers every branch the spec calls out, including the allowlist's exact
///   shape (`/auth/*`, `/portal/*`, `GET /api/me` only — not every method,
///   not a prefix match on `/api/me`);
/// - **the integration that actually matters** (spec §9.9's own words): a
///   REAL session-cookie login of a role-less user, through the real
///   [Authenticator] + [DbClaimsResolver] pipeline, never test headers.
class ProfileOnlyGateTest {

    // ── Decision table, over test-header contexts ───────────────────────────

    private static TestHttp unit;

    @BeforeAll
    static void startUnit() {
        var keys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config("http://localhost", new JwtVerifier.RsaKeys(keys.publicKey())));
        var authenticator = new Authenticator(verifier, ClaimsResolver.none(), Authenticator.Config.of(true));
        unit = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before(authenticator);
            routes.before(ProfileOnlyGate.INSTANCE);
            routes.get("/api/whatever", ctx -> ctx.result("ok"));
            routes.get("/bff/roles", ctx -> ctx.result("ok"));
            routes.get("/api/me", ctx -> ctx.result("ok"));
            routes.post("/api/me", ctx -> ctx.result("ok"));
            routes.get("/api/me/clients", ctx -> ctx.result("ok"));
            routes.get("/auth/me", ctx -> ctx.result("ok"));
            routes.get("/portal/auth/check-domain", ctx -> ctx.result("ok"));
        });
    }

    @AfterAll
    static void stopUnit() {
        unit.close();
    }

    @Test
    void roleLessUserContextIsGatedInThePlatformEnvelope() {
        var r = unit.get("/api/whatever", "X-FC-Test-Principal", "prn_x", "X-FC-Test-Principal-Type", "USER");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asString()).isEqualTo("NO_PLATFORM_ROLE");
        assertThat(json(r).get("message").asString())
                .isEqualTo("Your account has no platform access. Only your profile is available.");
    }

    @Test
    void aHeldRoleOrAHeldPermissionExemptsFromTheGate() {
        var withRole = unit.get("/api/whatever", "X-FC-Test-Principal", "prn_x", "X-FC-Test-Principal-Type", "USER",
                "X-FC-Test-Roles", "platform:some-role");
        assertThat(withRole.statusCode()).isEqualTo(200);

        var withPermission = unit.get("/api/whatever", "X-FC-Test-Principal", "prn_x", "X-FC-Test-Principal-Type", "USER",
                "X-FC-Test-Permissions", "platform:x:y:z");
        assertThat(withPermission.statusCode()).isEqualTo(200);
    }

    @Test
    void serviceAndUnknownTypeContextsPassThroughEvenWhenRoleLess() {
        var service = unit.get("/api/whatever", "X-FC-Test-Principal", "prn_x", "X-FC-Test-Principal-Type", "SERVICE");
        assertThat(service.statusCode()).as("a SERVICE principal is never gated here").isEqualTo(200);

        // No X-FC-Test-Principal-Type header at all: PrincipalType.parse("") -> null -> exempt.
        var unknownType = unit.get("/api/whatever", "X-FC-Test-Principal", "prn_x");
        assertThat(unknownType.statusCode()).as("an unknown/absent type is exempt, not gated").isEqualTo(200);
    }

    @Test
    void anAnonymousRequestPassesThroughUntouched() {
        assertThat(unit.get("/api/whatever").statusCode()).isEqualTo(200);
    }

    @Test
    void theAllowlistIsAuthStarAndPortalStarAndExactlyGetApiMe() {
        String[] roleLessUser = {"X-FC-Test-Principal", "prn_x", "X-FC-Test-Principal-Type", "USER"};
        assertThat(unit.get("/auth/me", roleLessUser).statusCode()).isEqualTo(200);
        assertThat(unit.get("/portal/auth/check-domain", roleLessUser).statusCode()).isEqualTo(200);
        assertThat(unit.get("/api/me", roleLessUser).statusCode()).as("GET /api/me is allowlisted").isEqualTo(200);

        // Mutant: allowlisting /api/me for every method, not just GET.
        assertThat(unit.post("/api/me", "", roleLessUser).statusCode())
                .as("the allowlist is method-specific: only GET /api/me").isEqualTo(403);
        // Mutant: a prefix match on /api/me instead of an exact one.
        assertThat(unit.get("/api/me/clients", roleLessUser).statusCode())
                .as("/api/me/clients is NOT /api/me — still gated").isEqualTo(403);
        assertThat(unit.get("/bff/roles", roleLessUser).statusCode()).isEqualTo(403);
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    // ── The real thing: a genuine session-cookie login of a role-less user ──

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String ISSUER = "http://localhost:8080";
    private static final String PASSWORD = "correct horse battery staple";
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final LoginAttemptRepository ATTEMPTS = new LoginAttemptRepository(DS);
    private static final TokenIssuer TOKEN_ISSUER = new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER));

    private static String roleLessEmail;
    private static String roleLessId;
    private static TestHttp real;

    @BeforeAll
    static void startReal() {
        roleLessEmail = "gate-" + RUN + "@example.com";
        roleLessId = principalWithNoRoles(roleLessEmail, PasswordHash.hash(PASSWORD));

        var mappings = new EmailDomainMappingRepository(DS);
        var idps = new IdentityProviderRepository(DS);
        var resolver = new DbClaimsResolver(PRINCIPALS, new RoleRepository(DS));
        var verifier = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));
        var authenticator = new Authenticator(verifier, resolver, Authenticator.Config.PRODUCTION);
        // Config.PRODUCTION is secure — the login route's own SessionCookie must mint
        // under the SAME decision (`docs/spec/cookie-hardening.md` §3: "one decision,
        // passed to both") or the Authenticator below would never recognise the cookie
        // this route just minted.
        var loginState = new LoginApi.State(PRINCIPALS, mappings, idps, ATTEMPTS,
                new BackoffCheck(ATTEMPTS, BackoffPolicy.DEFAULT), TOKEN_ISSUER, resolver, MfaChallenge.none(),
                new SessionCookie(true, (int) TokenIssuer.SESSION_TTL_SECONDS), DS, Clock.systemUTC());

        real = TestHttp.routes(routes -> {
            HttpError.install(routes);
            // LoginApi registers GET /auth/me itself (Auth.scoped) — reused
            // here rather than re-registered, which Javalin refuses as a
            // duplicate route.
            LoginApi.register(routes, loginState);
            routes.before(authenticator);
            routes.before(ProfileOnlyGate.INSTANCE);
            routes.get("/bff/roles", ctx -> ctx.result("ok"));
            routes.get("/api/me", ctx -> ctx.result("ok"));
        });
    }

    @AfterAll
    static void stopReal() {
        real.close();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(roleLessId)).execute();
    }

    /// §9.9's own words: a real session-cookie login of a role-less user (no
    /// test headers anywhere in this test) gets 403 `NO_PLATFORM_ROLE` on
    /// `/bff/roles`, and 200 on `GET /api/me` and `/auth/me`. If the
    /// Authenticator ever stopped stamping `PrincipalType.USER` on a session
    /// context (the mutant `AuthenticatorTest` pins directly), this gate
    /// would silently stop firing for every browser session in the platform
    /// — this is the end-to-end proof that the two pieces are wired together.
    @Test
    void aRealRoleLessSessionCookieLoginIsGatedOnBffRolesAndPassesOnApiMeAndAuthMe() {
        var login = real.post("/auth/login", Json.writeLine(Map.of("email", roleLessEmail, "password", PASSWORD)),
                "Content-Type", "application/json");
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String cookie = login.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("__Host-fc_session="))
                .findFirst().orElseThrow().split(";", 2)[0];

        var roles = real.get("/bff/roles", "Cookie", cookie);
        assertThat(roles.statusCode()).isEqualTo(403);
        assertThat(json(roles).get("error").asString()).isEqualTo("NO_PLATFORM_ROLE");

        assertThat(real.get("/api/me", "Cookie", cookie).statusCode()).isEqualTo(200);
        assertThat(real.get("/auth/me", "Cookie", cookie).statusCode()).isEqualTo(200);
    }

    private static String principalWithNoRoles(String email, String passwordHash) {
        String id = EntityType.PRINCIPAL.generate();
        var now = Instant.now().atOffset(ZoneOffset.UTC);
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "CLIENT")
                .set(IAM_PRINCIPALS.NAME, "Gate " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, email.substring(email.indexOf('@') + 1))
                .set(IAM_PRINCIPALS.PASSWORD_HASH, passwordHash).set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.CREATED_AT, now).set(IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();
        return id;
    }
}
