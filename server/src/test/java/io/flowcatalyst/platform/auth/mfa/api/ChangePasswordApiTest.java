package io.flowcatalyst.platform.auth.mfa.api;

import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.auth.login.LoginApi;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.mfa.Mfa;
import io.flowcatalyst.platform.auth.mfa.MfaRepository;
import io.flowcatalyst.platform.auth.mfa.Totp;
import io.flowcatalyst.platform.auth.mfa.TrustedDeviceCookie;
import io.flowcatalyst.platform.auth.mfa.TwoFactorNotifier;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Encryption;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_MFA_EMAIL_PINS;
import static io.flowcatalyst.db.generated.Tables.IAM_MFA_TRUSTED_DEVICES;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_METHODS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_RECOVERY_CODES;
import static org.assertj.core.api.Assertions.assertThat;

/// `/auth/change-password` and `/auth/change-password/send-email-code`
/// (`docs/spec/auth-identity.md` §6.8) over the embedded Postgres: a real
/// Argon2id password, real confirmed factors, and the post-change hygiene
/// (trusted devices, refresh tokens, the remember-device cookie).
class ChangePasswordApiTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final String ISSUER = "http://localhost:8080";
    private static final String PASSWORD = "correct horse battery staple";
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final Encryption ENC = Encryption.withKey(Encryption.generateKey());

    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final EmailDomainMappingRepository MAPPINGS = new EmailDomainMappingRepository(DS);
    private static final IdentityProviderRepository IDPS = new IdentityProviderRepository(DS);
    private static final MfaRepository MFA_REPO = new MfaRepository(DS);
    private static final List<String> MAIL_SENT = new ArrayList<>();
    private static final Mfa MFA = new Mfa(MFA_REPO, Optional.of(ENC),
            (to, subject, html) -> MAIL_SENT.add(to + "|" + subject + "|" + html), Mfa.Config.DEFAULT, Clock.systemUTC());
    private static final TrustedDeviceCookie DEVICE_COOKIE = new TrustedDeviceCookie(false);
    private static final GrantStore GRANTS = new GrantStore(DS);
    private static final DbClaimsResolver RESOLVER = new DbClaimsResolver(PRINCIPALS, new RoleRepository(DS));
    private static final TokenIssuer TOKEN_ISSUER = new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER));

    private static final List<String> NOTICES = new ArrayList<>();
    private static final TwoFactorNotifier NOTIFIER = new TwoFactorNotifier() {
        @Override
        public void twoFactorEnrolled(String email, MfaMethod method) {
        }

        @Override
        public void twoFactorMethodRemoved(String email, MfaMethod method) {
        }

        @Override
        public void recoveryCodesRegenerated(String email) {
        }

        @Override
        public void recoveryCodeUsed(String email) {
        }

        @Override
        public void newTrustedDevice(String email, String label) {
        }

        @Override
        public void passwordChanged(String email) {
            NOTICES.add("pwchanged:" + email);
        }
    };

    private static final List<String> principals = new ArrayList<>();
    private static TestHttp http;

    @BeforeAll
    static void start() {
        // ChangePasswordApi only reaches into LoginApi.State for principals/writes/clock;
        // attempts and backoff are unused here, so both are left null together.
        var loginState = new LoginApi.State(PRINCIPALS, MAPPINGS, IDPS, null, null, TOKEN_ISSUER, RESOLVER,
                io.flowcatalyst.platform.auth.login.MfaChallenge.none(), new SessionCookie(false, (int) TokenIssuer.SESSION_TTL_SECONDS), DS, Clock.systemUTC());
        var state = new ChangePasswordApi.State(loginState, MFA, DEVICE_COOKIE, GRANTS, NOTIFIER);

        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before(authenticator());
            ChangePasswordApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        DB.deleteFrom(IAM_USER_MFA_METHODS).where(IAM_USER_MFA_METHODS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_USER_MFA_RECOVERY_CODES).where(IAM_USER_MFA_RECOVERY_CODES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_MFA_EMAIL_PINS).where(IAM_MFA_EMAIL_PINS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_MFA_TRUSTED_DEVICES).where(IAM_MFA_TRUSTED_DEVICES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(principals)).execute();
    }

    private static Authenticator authenticator() {
        var verifier = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));
        return new Authenticator(verifier, RESOLVER, Authenticator.Config.of(false));
    }

    // ── the happy path + post-change hygiene ────────────────────────────────

    @Test
    void changingThePasswordRevokesDevicesAndRefreshTokensAndNotifies() {
        String email = "happy-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        MFA.issueTrustedDevice(pid, "Mozilla", java.time.Duration.ofDays(7));
        assertThat(MFA.listTrustedDevices(pid)).isNotEmpty();
        var issuedRefresh = RefreshToken.issue(pid, Instant.now(), RefreshToken.TTL_SECONDS);
        GRANTS.insert(issuedRefresh.token());
        assertThat(GRANTS.findValidByHash(issuedRefresh.token().tokenHash())).isPresent();

        String newPassword = "a whole new passphrase 42";
        var r = changePassword(pid, email, PASSWORD, newPassword, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("message").asString()).isEqualTo("Your password has been changed.");

        String stored = DB.select(IAM_PRINCIPALS.PASSWORD_HASH).from(IAM_PRINCIPALS)
                .where(IAM_PRINCIPALS.ID.eq(pid)).fetchOne(IAM_PRINCIPALS.PASSWORD_HASH);
        assertThat(PasswordHash.matches(newPassword, stored)).as("the new password verifies").isTrue();
        assertThat(PasswordHash.matches(PASSWORD, stored)).as("the old password no longer verifies").isFalse();

        assertThat(MFA.listTrustedDevices(pid)).as("post-change hygiene revokes trusted devices").isEmpty();
        assertThat(GRANTS.findValidByHash(issuedRefresh.token().tokenHash()))
                .as("post-change hygiene revokes refresh tokens").isEmpty();
        assertThat(r.headers().allValues("set-cookie").stream().anyMatch(c -> c.startsWith("fc_td=")))
                .as("the remember-device cookie is cleared").isTrue();
        assertThat(NOTICES).contains("pwchanged:" + email);
    }

    // ── mutant 5: change-password must not skip the MFA_REQUIRED gate ──────

    @Test
    void aConfirmedFactorDemandsACodeBeforeThePasswordChangesAndRejectsAMissingOrWrongOne() {
        String email = "mfareq-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        String secret = enrolTotpDirect(pid);

        var noCode = changePassword(pid, email, PASSWORD, "a whole new passphrase 43", null);
        assertThat(noCode.statusCode()).as(noCode.body()).isEqualTo(400);
        var body = json(noCode);
        assertThat(body.get("code").asString()).isEqualTo("MFA_REQUIRED");
        assertThat(body.get("methods").toString()).isEqualTo("[\"TOTP\"]");
        String stillOld = DB.select(IAM_PRINCIPALS.PASSWORD_HASH).from(IAM_PRINCIPALS)
                .where(IAM_PRINCIPALS.ID.eq(pid)).fetchOne(IAM_PRINCIPALS.PASSWORD_HASH);
        assertThat(PasswordHash.matches(PASSWORD, stillOld)).as("no code, no change").isTrue();

        var wrongCode = changePassword(pid, email, PASSWORD, "a whole new passphrase 43", "000000");
        assertThat(wrongCode.statusCode()).isEqualTo(400);
        assertThat(json(wrongCode).get("code").asString()).isEqualTo("INVALID_CODE");

        String rightCode = Totp.code(secret, Totp.stepOf(Instant.now()) + 1);
        var ok = changePassword(pid, email, PASSWORD, "a whole new passphrase 43", rightCode);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        String after = DB.select(IAM_PRINCIPALS.PASSWORD_HASH).from(IAM_PRINCIPALS)
                .where(IAM_PRINCIPALS.ID.eq(pid)).fetchOne(IAM_PRINCIPALS.PASSWORD_HASH);
        assertThat(PasswordHash.matches("a whole new passphrase 43", after)).isTrue();
    }

    @Test
    void aRecoveryCodeChangesThePasswordWhenTotpIsConfirmed() {
        String email = "recovery-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        enrolTotpDirect(pid);
        List<String> codes = MFA.generateRecoveryCodes(pid);

        var r = changePassword(pid, email, PASSWORD, "a whole new passphrase 44", codes.getFirst());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    // ── other gates, each pinning one branch ────────────────────────────────

    @Test
    void aWrongCurrentPasswordIs401() {
        String email = "wrongcurrent-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        var r = changePassword(pid, email, "not the password", "a whole new passphrase 45", null);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_CURRENT_PASSWORD");
    }

    @Test
    void aFederatedPrincipalIsRefusedWithSsoManaged() {
        String email = "sso-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        DB.update(IAM_PRINCIPALS).set(IAM_PRINCIPALS.IDP_TYPE, "OIDC").where(IAM_PRINCIPALS.ID.eq(pid)).execute();
        var r = changePassword(pid, email, PASSWORD, "a whole new passphrase 46", null);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("SSO_MANAGED");
    }

    @Test
    void aPasswordUserWhoseDomainMovedToSsoIsRefusedWithSsoManagedToo() {
        // Go's ssoManaged (and LoginApi's): a domain routed to an OIDC provider
        // closes change-password even for an account that still holds a hash.
        String domain = "moved-" + RUN + ".example";
        var idp = io.flowcatalyst.platform.identityprovider.IdentityProvider.create("idp-cp-" + RUN, "IdP " + RUN,
                io.flowcatalyst.platform.identityprovider.IdentityProviderType.OIDC)
                .withOidc("https://idp-" + RUN + ".example", "client-" + RUN, null, false, null);
        var mapping = io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping.create(
                io.flowcatalyst.platform.emaildomainmapping.EmailDomain.parse(domain), idp.id(),
                io.flowcatalyst.platform.emaildomainmapping.ScopeType.ANCHOR);
        var uow = new io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork(DS, new io.flowcatalyst.platform.shared.platformsink.PlatformSink(Json.MAPPER));
        uow.inTransaction(tx -> { IDPS.persist(idp, tx.dbTx()); MAPPINGS.persist(mapping, tx.dbTx()); return null; });
        try {
            String email = "moved-" + RUN + "@" + domain;
            String pid = principal(email, PasswordHash.hash(PASSWORD));
            var r = changePassword(pid, email, PASSWORD, "a whole new passphrase 48", null);
            assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
            assertThat(json(r).get("code").asString()).isEqualTo("SSO_MANAGED");
        } finally {
            uow.inTransaction(tx -> { MAPPINGS.delete(mapping, tx.dbTx()); IDPS.delete(idp, tx.dbTx()); return null; });
        }
    }

    @Test
    void anAccountWithNoPasswordIs400NoPassword() {
        String email = "nopw-" + RUN + "@example.com";
        String pid = principal(email, null);
        var r = changePassword(pid, email, "whatever", "a whole new passphrase 47", null);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("NO_PASSWORD");
    }

    @Test
    void aWeakNewPasswordSurfacesThePasswordPolicysCode() {
        String email = "weak-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        var r = changePassword(pid, email, PASSWORD, "short", null);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("PASSWORD_TOO_SHORT");
    }

    // ── send-email-code ──────────────────────────────────────────────────

    @Test
    void sendEmailCodeRequiresAConfirmedFactorThenSpecificallyEmail() {
        String email = "sendcode-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));

        var noMfa = http.post("/auth/change-password/send-email-code", null, "Cookie", sessionCookieFor(pid, email));
        assertThat(noMfa.statusCode()).isEqualTo(400);
        assertThat(json(noMfa).get("code").asString()).isEqualTo("NO_MFA");

        enrolTotpDirect(pid);
        var noEmail2fa = http.post("/auth/change-password/send-email-code", null, "Cookie", sessionCookieFor(pid, email));
        assertThat(noEmail2fa.statusCode()).isEqualTo(400);
        assertThat(json(noEmail2fa).get("code").asString()).isEqualTo("NO_EMAIL_2FA");

        MFA.beginEmailEnrollment(pid, email);
        MAIL_SENT.clear();
        var ok = http.post("/auth/change-password/send-email-code", null, "Cookie", sessionCookieFor(pid, email));
        // Email 2FA is only "confirmed" once the enrolment PIN is confirmed —
        // still pending here, so this is NO_EMAIL_2FA too.
        assertThat(ok.statusCode()).isEqualTo(400);
        assertThat(json(ok).get("code").asString()).isEqualTo("NO_EMAIL_2FA");
    }

    @Test
    void sendEmailCodeSucceedsOnceEmailPinIsConfirmed() {
        String email = "sendcode2-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        MFA.beginEmailEnrollment(pid, email);
        String pin = pinIn(MAIL_SENT.getLast());
        assertThat(MFA.confirmEmailEnrollment(pid, pin)).isTrue();

        MAIL_SENT.clear();
        var r = http.post("/auth/change-password/send-email-code", null, "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("message").asString()).isEqualTo("A code has been sent to your email.");
        assertThat(MAIL_SENT).hasSize(1);
    }

    @Test
    void malformedJsonIs400InvalidJson() {
        String email = "badjson-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        var r = http.post("/auth/change-password", "{not json", "Content-Type", "application/json",
                "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_JSON");
    }

    @Test
    void noSessionIs401() {
        var r = http.post("/auth/change-password",
                Json.writeLine(Map.of("currentPassword", PASSWORD, "newPassword", "a whole new passphrase 48")),
                "Content-Type", "application/json");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("message").asString()).isEqualTo("Not authenticated");
    }

    /// S2.4: changing the password is factor management — an API bearer for
    /// the same principal (knowing the current password, even) changes
    /// nothing; the stored hash is the observable effect.
    @Test
    void anApiBearerChangesNoPassword() {
        String email = "bearer-" + RUN + "@example.com";
        String pid = principal(email, PasswordHash.hash(PASSWORD));
        String bearer = "Bearer " + TOKEN_ISSUER.accessToken(PRINCIPALS.findById(pid).orElseThrow(),
                new TokenIssuer.Authority(List.of(), List.of(), List.of(), false, List.of()), null);
        var r = http.post("/auth/change-password",
                Json.writeLine(Map.of("currentPassword", PASSWORD, "newPassword", "a whole new passphrase 49")),
                "Content-Type", "application/json", "Authorization", bearer);
        assertThat(r.statusCode()).as("mutant: any AuthContext accepted — " + r.body()).isEqualTo(401);
        String stored = DB.select(IAM_PRINCIPALS.PASSWORD_HASH).from(IAM_PRINCIPALS)
                .where(IAM_PRINCIPALS.ID.eq(pid)).fetchOne(IAM_PRINCIPALS.PASSWORD_HASH);
        assertThat(PasswordHash.matches(PASSWORD, stored)).as("the password is unchanged").isTrue();
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static HttpResponse<String> changePassword(String pid, String email, String current, String next, String code) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("currentPassword", current);
        body.put("newPassword", next);
        if (code != null) {
            body.put("code", code);
        }
        return http.post("/auth/change-password", Json.writeLine(body), "Content-Type", "application/json",
                "Cookie", sessionCookieFor(pid, email));
    }

    private static String enrolTotpDirect(String pid) {
        var e = MFA.beginTotpEnrollment(pid, "x@example.com");
        assertThat(MFA.confirmTotpEnrollment(pid, Totp.code(e.secret(), Totp.stepOf(Instant.now())))).isTrue();
        return e.secret();
    }

    private static String sessionCookieFor(String pid, String email) {
        // This suite's Authenticator/LoginApi.State both run insecure (SessionCookie(false, ...) below).
        return SessionCookie.INSECURE_NAME + "=" + TOKEN_ISSUER.sessionToken(pid, email);
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static String pinIn(String sent) {
        int i = sent.indexOf("letter-spacing:3px\">") + "letter-spacing:3px\">".length();
        return sent.substring(i, i + 6);
    }

    private static String principal(String email, String passwordHash) {
        String id = EntityType.PRINCIPAL.generate();
        var row = DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "CLIENT")
                .set(IAM_PRINCIPALS.NAME, "ChangePw " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, email.substring(email.indexOf('@') + 1))
                .set(IAM_PRINCIPALS.PASSWORD_HASH, passwordHash)
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW);
        row.execute();
        principals.add(id);
        return id;
    }
}
