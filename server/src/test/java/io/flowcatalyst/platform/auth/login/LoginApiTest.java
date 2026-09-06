package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static io.flowcatalyst.db.generated.Tables.IAM_LOGIN_ATTEMPTS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static org.assertj.core.api.Assertions.assertThat;

/// auth-core §6.1 / §7.1 over HTTP against the embedded Postgres: a real
/// Argon2id password, a real cookie read back through the authenticator,
/// and the login-attempt rows each path must (or must not) write.
class LoginApiTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String ISSUER = "http://localhost:8080";
    private static final String PASSWORD = "correct horse battery staple";
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();

    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final LoginAttemptRepository ATTEMPTS = new LoginAttemptRepository(DS);
    private static final EmailDomainMappingRepository MAPPINGS = new EmailDomainMappingRepository(DS);
    private static final IdentityProviderRepository IDPS = new IdentityProviderRepository(DS);
    private static final DbClaimsResolver RESOLVER = new DbClaimsResolver(PRINCIPALS, new RoleRepository(DS));
    private static final TokenIssuer ISSUER_UNDER_TEST = new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER));

    private static String userEmail;      // password login works
    private static String userId;
    private static String ssoEmail;       // domain mapped to an OIDC provider
    private static String ssoDomain;
    private static String idpId;
    private static String mappingId;
    private static String legacyEmail;    // off-params hash → rehashed on login
    private static String legacyId;
    private static TestHttp http;

    @BeforeAll
    static void start() {
        userEmail = "login-" + RUN + "@example.com";
        userId = principal(userEmail, PasswordHash.hash(PASSWORD));
        legacyEmail = "legacy-" + RUN + "@example.com";
        legacyId = principal(legacyEmail, PasswordHash.hashWithParams(PASSWORD, new PasswordHash.Params(16 * 1024, 2, 1, 32, 16)));
        ssoDomain = "sso-" + RUN + ".example.com";
        ssoEmail = "someone@" + ssoDomain;
        var idp = IdentityProvider.create("idp-" + RUN, "Okta " + RUN, IdentityProviderType.OIDC)
                .withOidc("https://okta." + RUN + ".example.com", "client-" + RUN, null, false, null);
        idpId = idp.id();
        var mapping = EmailDomainMapping.create(EmailDomain.parse(ssoDomain), idpId, ScopeType.ANCHOR);
        mappingId = mapping.id();
        inTx(tx -> {
            IDPS.persist(idp, tx);
            MAPPINGS.persist(mapping, tx);
        });
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/auth/me", authenticator());
            routes.before("/auth/login-history", authenticator());
            LoginApi.register(routes, state(ATTEMPTS, new BackoffCheck(ATTEMPTS, BackoffPolicy.DEFAULT), MfaChallenge.none()));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        DB.deleteFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.in(userEmail, legacyEmail, ssoEmail, "nobody-" + RUN + "@example.com")).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(userId, legacyId)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.eq(mappingId)).execute();
        DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.eq(idpId)).execute();
    }

    private static Authenticator authenticator() {
        var verifier = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));
        return new Authenticator(verifier, RESOLVER, Authenticator.Config.of(false));
    }

    private static LoginApi.State state(LoginAttemptRepository attempts, BackoffCheck backoff, MfaChallenge mfa) {
        return new LoginApi.State(PRINCIPALS, MAPPINGS, IDPS, attempts, backoff, ISSUER_UNDER_TEST, RESOLVER, mfa,
                new SessionCookie(false), DS, Clock.systemUTC());
    }

    // ── login ──────────────────────────────────────────────────────────────

    @Test
    void aCorrectPasswordSetsTheSessionCookieAndAnswersTheLoginResponse() {
        int before = attempts(userEmail);
        var r = login(userEmail, PASSWORD);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var j = json(r);
        assertThat(j.get("status").asString()).isEqualTo("ok");
        assertThat(j.get("principalId").asString()).isEqualTo(userId);
        assertThat(j.get("email").asString()).isEqualTo(userEmail);
        assertThat(j.get("roles").isArray()).isTrue();
        assertThat(j.get("permissions").isArray()).isTrue();
        assertThat(j.has("clientId")).as("clientId is always present, null when none").isTrue();
        assertThat(j.get("clientId").isNull()).isTrue();
        assertThat(j.get("ssoManaged").asBoolean()).isFalse();
        assertThat(j.has("recoveryCodes")).isFalse();

        String cookie = setCookie(r);
        assertThat(cookie).startsWith("fc_session=").contains("Path=/").contains("HttpOnly").contains("SameSite=Lax")
                .contains("Max-Age=86400").doesNotContain("Secure");
        assertThat(attempts(userEmail) - before).as("one SUCCESS row").isEqualTo(1);
        assertThat(lastAttempt(userEmail).get(IAM_LOGIN_ATTEMPTS.OUTCOME)).isEqualTo("SUCCESS");
        assertThat(lastAttempt(userEmail).get(IAM_LOGIN_ATTEMPTS.PRINCIPAL_ID)).isEqualTo(userId);
    }

    @Test
    void aWrongPasswordIs401InTheConstantShapeAndRecordsAFailure() {
        int before = attempts(userEmail);
        var r = login(userEmail, "not it");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(json(r).get("message").asString()).isEqualTo("Invalid credentials");
        assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Cookie realm=\"fc_session\"");
        assertThat(r.headers().firstValue("set-cookie")).isEmpty();
        assertThat(attempts(userEmail) - before).isEqualTo(1);
        assertThat(lastAttempt(userEmail).get(IAM_LOGIN_ATTEMPTS.FAILURE_REASON)).isEqualTo("Invalid credentials");
    }

    @Test
    void anUnknownEmailGetsTheSameShapeAndABlankFieldRecordsNothing() {
        String nobody = "nobody-" + RUN + "@example.com";
        var r = login(nobody, "whatever");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("message").asString()).isEqualTo("Invalid credentials");
        assertThat(attempts(nobody)).as("a failure is recorded for an unknown identifier too").isEqualTo(1);

        int before = attempts(userEmail);
        assertThat(login(userEmail, "").statusCode()).isEqualTo(401);
        assertThat(login("", PASSWORD).statusCode()).isEqualTo(401);
        assertThat(attempts(userEmail) - before).as("a blank field is rejected before anything is recorded").isZero();
    }

    @Test
    void anEmailOnAnSsoDomainIsRefusedWithSsoRequiredAndRecorded() {
        var r = login(ssoEmail, PASSWORD);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("error").asString()).isEqualTo("SSO_REQUIRED");
        assertThat(lastAttempt(ssoEmail).get(IAM_LOGIN_ATTEMPTS.FAILURE_REASON)).isEqualTo("SSO required");
    }

    @Test
    void aLegacyHashIsReplacedByTheNativeSchemeAfterASuccessfulLogin() {
        String before = DB.select(IAM_PRINCIPALS.PASSWORD_HASH).from(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(legacyId)).fetchOne(IAM_PRINCIPALS.PASSWORD_HASH);
        assertThat(PasswordHash.needsRehash(before)).isTrue();
        assertThat(login(legacyEmail, PASSWORD).statusCode()).isEqualTo(200);
        String after = DB.select(IAM_PRINCIPALS.PASSWORD_HASH).from(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(legacyId)).fetchOne(IAM_PRINCIPALS.PASSWORD_HASH);
        assertThat(after).isNotEqualTo(before);
        assertThat(PasswordHash.needsRehash(after)).isFalse();
        assertThat(PasswordHash.matches(PASSWORD, after)).isTrue();
    }

    @Test
    void backoffDeniesWith429AndRecordsNothingForTheDeniedAttempt() {
        // A private endpoint with a tight policy so the test does not need
        // many failures: one free attempt, base 300 s.
        var policy = new BackoffPolicy(1, 300, 300, 3600, 100, 900);
        String email = "backoff-" + RUN + "@example.com";
        String id = principal(email, PasswordHash.hash(PASSWORD));
        try (var tight = TestHttp.routes(routes -> {
            HttpError.install(routes);
            LoginApi.register(routes, state(ATTEMPTS, new BackoffCheck(ATTEMPTS, policy), MfaChallenge.none()));
        })) {
            assertThat(tight.post("/auth/login", body(email, "wrong"), "Content-Type", "application/json").statusCode()).isEqualTo(401);
            assertThat(tight.post("/auth/login", body(email, "wrong"), "Content-Type", "application/json").statusCode()).isEqualTo(401);
            int recorded = attempts(email);
            var denied = tight.post("/auth/login", body(email, PASSWORD), "Content-Type", "application/json");
            assertThat(denied.statusCode()).as("even the right password is refused while backed off").isEqualTo(429);
            assertThat(json(denied).get("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
            assertThat(Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 300L);
            assertThat(attempts(email)).as("a denied attempt is never recorded (spec §4)").isEqualTo(recorded);
        } finally {
            DB.deleteFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.eq(email)).execute();
            DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(id)).execute();
        }
    }

    @Test
    void aBackoffStoreErrorFailsClosedWith503() {
        DataSource broken = brokenDataSource();
        var brokenAttempts = new LoginAttemptRepository(broken);
        try (var closed = TestHttp.routes(routes -> {
            HttpError.install(routes);
            LoginApi.register(routes, state(brokenAttempts, new BackoffCheck(brokenAttempts, BackoffPolicy.DEFAULT), MfaChallenge.none()));
        })) {
            long alarmsBefore = AuthAlarms.backoffStoreErrors();
            var r = closed.post("/auth/login", body(userEmail, PASSWORD), "Content-Type", "application/json");
            assertThat(r.statusCode()).as("ruling C-Q23: the lock is never switched off by a store error").isEqualTo(503);
            assertThat(json(r).get("code").asString()).isEqualTo("BACKOFF_UNAVAILABLE");
            assertThat(r.headers().firstValue("set-cookie")).isEmpty();
            assertThat(AuthAlarms.backoffStoreErrors()).as("the refusal is counted, so an operator can alarm on the first one").isEqualTo(alarmsBefore + 1);
        }
    }

    @Test
    void anMfaChallengeStandsInForTheSessionAndAnEvaluationErrorFailsClosed() {
        MfaChallenge challenging = (_, _) -> Optional.of(new MfaChallenge.Challenge(Map.of("status", "mfa_required", "mfaToken", "t")));
        try (var t = TestHttp.routes(routes -> {
            HttpError.install(routes);
            LoginApi.register(routes, state(ATTEMPTS, new BackoffCheck(ATTEMPTS, BackoffPolicy.DEFAULT), challenging));
        })) {
            var r = t.post("/auth/login", body(userEmail, PASSWORD), "Content-Type", "application/json");
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json(r).get("status").asString()).isEqualTo("mfa_required");
            assertThat(r.headers().firstValue("set-cookie")).as("no session until the second factor").isEmpty();
        }
        MfaChallenge failing = (_, _) -> { throw new IllegalStateException("store down"); };
        try (var t = TestHttp.routes(routes -> {
            HttpError.install(routes);
            LoginApi.register(routes, state(ATTEMPTS, new BackoffCheck(ATTEMPTS, BackoffPolicy.DEFAULT), failing));
        })) {
            var r = t.post("/auth/login", body(userEmail, PASSWORD), "Content-Type", "application/json");
            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(json(r).get("code").asString()).isEqualTo("MFA_EVAL_FAILED");
            assertThat(r.headers().firstValue("set-cookie")).isEmpty();
        }
    }

    @Test
    void malformedJsonIs400InvalidJson() {
        var r = http.post("/auth/login", "{not json", "Content-Type", "application/json");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("INVALID_JSON");
    }

    // ── me / logout / history ──────────────────────────────────────────────

    @Test
    void theCookieAuthenticatesMeAndLogoutExpiresIt() {
        String cookie = setCookie(login(userEmail, PASSWORD));
        String pair = cookie.substring(0, cookie.indexOf(';'));

        var me = http.get("/auth/me", "Cookie", pair);
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(json(me).get("status").asString()).as("ruling C-Q24").isEqualTo("ok");
        assertThat(json(me).get("principalId").asString()).isEqualTo(userId);
        assertThat(json(me).has("clientId")).isTrue();

        var anon = http.get("/auth/me");
        assertThat(anon.statusCode()).isEqualTo(401);
        assertThat(json(anon).get("message").asString()).isEqualTo("Not authenticated");

        var out = http.post("/auth/logout", null);
        assertThat(out.statusCode()).isEqualTo(204);
        // Jetty renders a zero max-age as the epoch Expires; either form expires the cookie.
        assertThat(setCookie(out)).startsWith("fc_session=;").containsAnyOf("Max-Age=0", "Expires=Thu, 01 Jan 1970");
    }

    @Test
    void loginHistoryListsTheCallersRecentAttempts() {
        login(userEmail, "wrong");
        String cookie = setCookie(login(userEmail, PASSWORD));
        var r = http.get("/auth/login-history", "Cookie", cookie.substring(0, cookie.indexOf(';')));
        assertThat(r.statusCode()).isEqualTo(200);
        var attempts = json(r).get("attempts");
        assertThat(attempts.size()).isBetween(2, LoginApi.HISTORY_LIMIT);
        assertThat(attempts.get(0).get("attemptType").asString()).isEqualTo("USER_LOGIN");
        assertThat(attempts.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(attempts.get(0).has("attemptedAt")).isTrue();
        assertThat(http.get("/auth/login-history").statusCode()).isEqualTo(401);
    }

    // ── check-domain ───────────────────────────────────────────────────────

    @Test
    void checkDomainAnswersInternalForUnknownAndExternalForAnOidcDomain() {
        var internal = http.post("/auth/check-domain", "{\"email\":\"" + userEmail + "\"}", "Content-Type", "application/json");
        assertThat(internal.statusCode()).isEqualTo(200);
        assertThat(json(internal).get("authMethod").asString()).isEqualTo("internal");
        assertThat(json(internal).has("loginUrl")).isFalse();

        var external = http.post("/auth/check-domain", "{\"email\":\"Some.One@" + ssoDomain.toUpperCase(Locale.ROOT) + "\"}", "Content-Type", "application/json");
        assertThat(json(external).get("authMethod").asString()).isEqualTo("external");
        assertThat(json(external).get("loginUrl").asString()).as("the domain, lower-cased, never the local part").isEqualTo("/auth/oidc/login?domain=" + ssoDomain);
        assertThat(json(external).get("idpIssuer").asString()).isEqualTo("https://okta." + RUN + ".example.com");

        var malformed = http.post("/auth/check-domain", "{\"email\":\"no-at-sign\"}", "Content-Type", "application/json");
        assertThat(json(malformed).get("authMethod").asString()).as("deliberately no leak").isEqualTo("internal");
        var missing = http.post("/auth/check-domain", "{\"email\":\"  \"}", "Content-Type", "application/json");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json(missing).get("error").asString()).isEqualTo("EMAIL_REQUIRED");
    }

    @Test
    void legacyCheckDomainKeepsItsShapeButNeverFabricatesAnAuthorizationUrl() {
        var r = http.get("/auth/check-domain?email=x@" + ssoDomain);
        assertThat(r.statusCode()).isEqualTo(200);
        var j = json(r);
        assertThat(j.get("domain").asString()).isEqualTo(ssoDomain);
        assertThat(j.get("authMethod").asString()).isEqualTo("OIDC");
        assertThat(j.get("providerId").asString()).isEqualTo(idpId);
        assertThat(j.has("authorizationUrl")).as("rulings I-Q9 / C-Q29").isFalse();

        var unknown = json(http.get("/auth/check-domain?email=x@nowhere-" + RUN + ".test"));
        assertThat(unknown.get("authMethod").asString()).isEqualTo("INTERNAL");
        assertThat(unknown.has("providerId")).isFalse();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static HttpResponse<String> login(String email, String password) {
        return http.post("/auth/login", body(email, password), "Content-Type", "application/json");
    }

    private static String body(String email, String password) {
        return Json.writeLine(Map.of("email", email, "password", password));
    }

    private static String setCookie(HttpResponse<String> r) {
        return r.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("fc_session=")).findFirst()
                .orElseThrow(() -> new AssertionError("no fc_session cookie in " + r.headers().map()));
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static int attempts(String identifier) {
        return DB.fetchCount(IAM_LOGIN_ATTEMPTS, IAM_LOGIN_ATTEMPTS.IDENTIFIER.eq(identifier));
    }

    private static org.jooq.Record lastAttempt(String identifier) {
        return DB.selectFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.eq(identifier))
                .orderBy(IAM_LOGIN_ATTEMPTS.ATTEMPTED_AT.desc(), IAM_LOGIN_ATTEMPTS.ID.desc()).limit(1).fetchOne();
    }

    private static String principal(String email, String passwordHash) {
        String id = EntityType.PRINCIPAL.generate();
        var now = Instant.now().atOffset(ZoneOffset.UTC);
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "CLIENT")
                .set(IAM_PRINCIPALS.NAME, "Login " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, email.substring(email.indexOf('@') + 1))
                .set(IAM_PRINCIPALS.PASSWORD_HASH, passwordHash).set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.CREATED_AT, now).set(IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();
        return id;
    }

    private static void inTx(Consumer<DbTx> body) {
        try (Connection conn = DS.getConnection()) {
            conn.setAutoCommit(false);
            try {
                body.accept(DbTx.wrapForBootstrap(conn));
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /// A data source whose every connection attempt fails — the backoff store outage.
    private static DataSource brokenDataSource() {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) throw new SQLException("simulated outage");
                    if (method.getName().equals("toString")) return "broken";
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
