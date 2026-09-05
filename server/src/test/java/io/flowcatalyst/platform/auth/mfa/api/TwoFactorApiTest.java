package io.flowcatalyst.platform.auth.mfa.api;

import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.login.BackoffCheck;
import io.flowcatalyst.platform.auth.login.BackoffPolicy;
import io.flowcatalyst.platform.auth.login.LoginApi;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.mfa.DomainPolicy;
import io.flowcatalyst.platform.auth.mfa.LoginMfaGate;
import io.flowcatalyst.platform.auth.mfa.Mfa;
import io.flowcatalyst.platform.auth.mfa.MfaRepository;
import io.flowcatalyst.platform.auth.mfa.MfaToken;
import io.flowcatalyst.platform.auth.mfa.Totp;
import io.flowcatalyst.platform.auth.mfa.TrustedDeviceCookie;
import io.flowcatalyst.platform.auth.mfa.TwoFactorNotifier;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.TwoFactorPolicy;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static io.flowcatalyst.db.generated.Tables.AUD_LOGS;
import static io.flowcatalyst.db.generated.Tables.IAM_LOGIN_ATTEMPTS;
import static io.flowcatalyst.db.generated.Tables.IAM_MFA_EMAIL_PINS;
import static io.flowcatalyst.db.generated.Tables.IAM_MFA_TRUSTED_DEVICES;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_METHODS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_RECOVERY_CODES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS;
import static org.assertj.core.api.Assertions.assertThat;

/// The `/auth/2fa/*` HTTP surface (`docs/spec/auth-identity.md` §6.3, §6.4,
/// §6.6, §6.7 and the §0.5 rulings) end to end over the embedded Postgres,
/// with a real `LoginApi` wired into the same harness so the token-gated
/// routes are driven by genuine pending / enrol tokens minted by the login
/// decision, exactly as a browser would see them.
class TwoFactorApiTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final String ISSUER = "http://localhost:8080";
    private static final String PASSWORD = "correct horse battery staple";
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final io.flowcatalyst.platform.shared.encryption.Encryption ENC =
            io.flowcatalyst.platform.shared.encryption.Encryption.withKey(io.flowcatalyst.platform.shared.encryption.Encryption.generateKey());

    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final LoginAttemptRepository ATTEMPTS = new LoginAttemptRepository(DS);
    private static final EmailDomainMappingRepository MAPPINGS = new EmailDomainMappingRepository(DS);
    private static final IdentityProviderRepository IDPS = new IdentityProviderRepository(DS);
    private static final MfaRepository MFA_REPO = new MfaRepository(DS);
    private static final List<String> MAIL_SENT = new ArrayList<>();
    private static final Mfa MFA = new Mfa(MFA_REPO, Optional.of(ENC),
            (to, subject, html) -> MAIL_SENT.add(to + "|" + subject + "|" + html), Mfa.Config.DEFAULT, Clock.systemUTC());
    private static final MfaToken TOKENS = new MfaToken(KEYS.privateKey(), ISSUER);
    private static final TrustedDeviceCookie DEVICE_COOKIE = new TrustedDeviceCookie(false);
    private static final AuditLogRepository AUDIT = new AuditLogRepository(DS);
    private static final DbClaimsResolver RESOLVER = new DbClaimsResolver(PRINCIPALS, new RoleRepository(DS));
    private static final TokenIssuer TOKEN_ISSUER = new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER));
    private static final DomainPolicy.Evaluator POLICY = new DomainPolicy.Evaluator(MAPPINGS);

    private static final List<String> NOTICES = new ArrayList<>();
    private static final TwoFactorNotifier NOTIFIER = new TwoFactorNotifier() {
        @Override
        public void twoFactorEnrolled(String email, MfaMethod method) {
            NOTICES.add("enrolled:" + email + ":" + method);
        }

        @Override
        public void twoFactorMethodRemoved(String email, MfaMethod method) {
            NOTICES.add("removed:" + email + ":" + method);
        }

        @Override
        public void recoveryCodesRegenerated(String email) {
            NOTICES.add("regen:" + email);
        }

        @Override
        public void recoveryCodeUsed(String email) {
            NOTICES.add("recoveryUsed:" + email);
        }

        @Override
        public void newTrustedDevice(String email, String label) {
            NOTICES.add("newDevice:" + email + ":" + label);
        }

        @Override
        public void passwordChanged(String email) {
            NOTICES.add("pwchanged:" + email);
        }
    };

    // requires TOTP only, remember-device on, 7-day ttl
    private static final String STRICT = "strict-" + RUN + ".example";
    // no requirement, remember-device on
    private static final String LOOSE = "loose-" + RUN + ".example";
    // requires TOTP only, remember-device OFF
    private static final String NOREMEMBER = "norem-" + RUN + ".example";

    private static final List<String> principals = new ArrayList<>();
    private static final List<String> mappingIds = new ArrayList<>();
    private static final List<String> idpIds = new ArrayList<>();
    private static TestHttp http;

    @BeforeAll
    static void start() {
        mapping(STRICT, new TwoFactorPolicy(true, List.of(MfaMethod.TOTP), true, 7));
        mapping(LOOSE, new TwoFactorPolicy(false, List.of(), true, 0));
        mapping(NOREMEMBER, new TwoFactorPolicy(true, List.of(MfaMethod.TOTP), false, 7));

        var mfaGate = new LoginMfaGate(MFA, POLICY, TOKENS, DEVICE_COOKIE);
        var backoff = new BackoffCheck(ATTEMPTS, BackoffPolicy.DEFAULT);
        var loginState = new LoginApi.State(PRINCIPALS, MAPPINGS, IDPS, ATTEMPTS, backoff, TOKEN_ISSUER, RESOLVER, mfaGate,
                new SessionCookie(false), DS, Clock.systemUTC());

        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            cfg.routes.before(authenticator());
            LoginApi.register(cfg.routes, loginState);
            TwoFactorApi.register(cfg.routes, new TwoFactorApi.State(loginState, MFA, POLICY, TOKENS, DEVICE_COOKIE, AUDIT, NOTIFIER));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        DB.deleteFrom(IAM_USER_MFA_METHODS).where(IAM_USER_MFA_METHODS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_USER_MFA_RECOVERY_CODES).where(IAM_USER_MFA_RECOVERY_CODES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_MFA_EMAIL_PINS).where(IAM_MFA_EMAIL_PINS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_MFA_TRUSTED_DEVICES).where(IAM_MFA_TRUSTED_DEVICES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(AUD_LOGS).where(AUD_LOGS.ENTITY_ID.in(principals)).execute();
        DB.deleteFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.likeIgnoreCase("%" + RUN + "%")).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(principals)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS).where(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS.EMAIL_DOMAIN_MAPPING_ID.in(mappingIds)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.in(mappingIds)).execute();
        DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.in(idpIds)).execute();
    }

    private static Authenticator authenticator() {
        var verifier = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));
        return new Authenticator(verifier, RESOLVER, Authenticator.Config.of(false));
    }

    // ── the end-to-end flow the brief calls out ─────────────────────────────

    @Test
    void enrolAndCompleteThenVerifyWithARememberedDeviceSkipsTheNextChallenge() {
        String email = "flow-" + RUN + "@" + STRICT;
        principal(email);

        var first = login(email);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var body1 = json(first);
        assertThat(body1.get("status").asString()).isEqualTo("enrollment_required");
        assertThat(body1.get("allowedMethods").toString()).isEqualTo("[\"TOTP\"]");
        String enrollToken = body1.get("enrollToken").asString();

        var begin = http.post("/auth/2fa/enroll/totp/begin", Json.writeLine(Map.of("enrollToken", enrollToken)),
                "Content-Type", "application/json");
        assertThat(begin.statusCode()).as(begin.body()).isEqualTo(200);
        String secret = json(begin).get("secret").asString();
        assertThat(json(begin).get("uri").asString()).contains("secret=" + secret);

        String enrolCode = Totp.code(secret, Totp.stepOf(Instant.now()));
        var confirm = http.post("/auth/2fa/enroll/totp/confirm",
                Json.writeLine(Map.of("enrollToken", enrollToken, "code", enrolCode)), "Content-Type", "application/json");
        assertThat(confirm.statusCode()).as(confirm.body()).isEqualTo(200);
        var confirmBody = json(confirm);
        assertThat(confirmBody.get("status").asString()).isEqualTo("ok");
        assertThat(confirmBody.get("recoveryCodes").size()).as("the first recovery-code set is shown once").isEqualTo(10);
        assertThat(setCookie(confirm, "fc_session")).as("enrol-and-complete signs the user in").isPresent();
        assertThat(NOTICES).contains("enrolled:" + email + ":TOTP");

        var second = login(email);
        var body2 = json(second);
        assertThat(body2.get("status").asString()).as("TOTP is now confirmed and usable").isEqualTo("mfa_required");
        String mfaToken = body2.get("mfaToken").asString();

        String verifyCode = Totp.code(secret, Totp.stepOf(Instant.now()) + 1); // past the enrolment's accepted step
        var verify = http.post("/auth/2fa/verify",
                Json.writeLine(Map.of("mfaToken", mfaToken, "method", "TOTP", "code", verifyCode, "rememberDevice", true)),
                "Content-Type", "application/json");
        assertThat(verify.statusCode()).as(verify.body()).isEqualTo(200);
        Optional<String> td = setCookie(verify, "fc_td");
        assertThat(td).as("STRICT allows remembering a device").isPresent();
        assertThat(NOTICES).anyMatch(n -> n.startsWith("newDevice:" + email));

        String tdPair = td.get().substring(0, td.get().indexOf(';'));
        var third = http.post("/auth/login", Json.writeLine(Map.of("email", email, "password", PASSWORD)),
                "Content-Type", "application/json", "Cookie", tdPair);
        assertThat(json(third).get("status").asString()).as("the remembered device skips the challenge").isEqualTo("ok");
    }

    // ── mutant 1: verify enforces the domain's allowed-method list (I-Q12) ──

    @Test
    void verifyRejectsAConfirmedMethodOutsideTheDomainsAllowedListWith403() {
        String email = "policy-" + RUN + "@" + STRICT; // STRICT permits TOTP only
        String pid = principal(email);
        DB.insertInto(IAM_USER_MFA_METHODS).set(IAM_USER_MFA_METHODS.ID, EntityType.MFA_METHOD.generate())
                .set(IAM_USER_MFA_METHODS.PRINCIPAL_ID, pid).set(IAM_USER_MFA_METHODS.METHOD, "EMAIL_PIN")
                .set(IAM_USER_MFA_METHODS.CONFIRMED_AT, NOW).set(IAM_USER_MFA_METHODS.CREATED_AT, NOW).execute();

        String mfaToken = TOKENS.mint(pid, MfaToken.Purpose.PENDING);
        var r = http.post("/auth/2fa/verify",
                Json.writeLine(Map.of("mfaToken", mfaToken, "method", "EMAIL_PIN", "code", "000000")),
                "Content-Type", "application/json");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
        assertThat(json(r).get("error").asString()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void recoveryCodesAreNeverRestrictedByTheDomainsAllowedMethodList() {
        String email = "rec-" + RUN + "@" + STRICT;
        String pid = principal(email);
        enrolTotpDirect(pid);
        List<String> codes = MFA.generateRecoveryCodes(pid);

        String mfaToken = TOKENS.mint(pid, MfaToken.Purpose.PENDING);
        var r = http.post("/auth/2fa/verify",
                Json.writeLine(Map.of("mfaToken", mfaToken, "method", "RECOVERY_CODE", "code", codes.getFirst())),
                "Content-Type", "application/json");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(NOTICES).contains("recoveryUsed:" + email);
    }

    // ── mutant 2: DELETE answers 404, not 200, when nothing was deleted ────

    @Test
    void deletingA2FaMethodNeverEnrolledIs404NotAlways200() {
        String email = "del404-" + RUN + "@" + LOOSE;
        String pid = principal(email);
        var r = http.delete("/auth/2fa/methods/EMAIL_PIN", "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
        assertThat(json(r).get("error").asString()).isEqualTo("TwoFactorMethod_NOT_FOUND");
    }

    @Test
    void revokingATrustedDeviceThatDoesNotExistIs404() {
        String email = "tdnf-" + RUN + "@" + LOOSE;
        String pid = principal(email);
        var r = http.delete("/auth/2fa/trusted-devices/does-not-exist", "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
        assertThat(json(r).get("error").asString()).isEqualTo("TrustedDevice_NOT_FOUND");
    }

    // ── mutant 3: no cookie when the domain disallows remembering (I-Q11) ──

    @Test
    void verifyNeverSetsTheTrustedDeviceCookieWhenTheDomainDisallowsRemembering() {
        String email = "norem-" + RUN + "@" + NOREMEMBER;
        String pid = principal(email);
        String secret = enrolTotpDirect(pid);
        String mfaToken = TOKENS.mint(pid, MfaToken.Purpose.PENDING);
        String code = Totp.code(secret, Totp.stepOf(Instant.now()) + 1);
        var r = http.post("/auth/2fa/verify",
                Json.writeLine(Map.of("mfaToken", mfaToken, "method", "TOTP", "code", code, "rememberDevice", true)),
                "Content-Type", "application/json");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().allValues("set-cookie").stream().anyMatch(c -> c.startsWith("fc_td=") || c.startsWith("__Host-fc_td=")))
                .as("NOREMEMBER's mapping disallows remembering").isFalse();
    }

    // ── mutant 4: removing the last confirmed factor on a requiring domain ─

    @Test
    void removingTheLastConfirmedFactorOnARequiringDomainIs409LastFactor() {
        String email = "last-" + RUN + "@" + STRICT;
        String pid = principal(email);
        enrolTotpDirect(pid);
        var r = http.delete("/auth/2fa/methods/TOTP", "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
        assertThat(json(r).get("error").asString()).isEqualTo("LAST_FACTOR");
        assertThat(MFA.confirmed(pid)).as("the factor survives the rejected removal").containsExactly(MfaMethod.TOTP);
    }

    @Test
    void removingAMethodIsAllowedWhenAnotherConfirmedFactorRemains() {
        String email = "notlast-" + RUN + "@" + LOOSE; // domain does not require 2FA at all
        String pid = principal(email);
        enrolTotpDirect(pid);
        var r = http.delete("/auth/2fa/methods/TOTP", "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(MFA.confirmed(pid)).isEmpty();
        assertThat(NOTICES).contains("removed:" + email + ":TOTP");
        assertThat(auditCount(pid, "2FA_METHOD_REMOVED")).isEqualTo(1);
    }

    // ── review: no factor at all on a requiring domain is a 404, not LAST_FACTOR ──

    @Test
    void removingAMethodAUserNeverHadOnARequiringDomainIs404NotLastFactor() {
        String email = "nofactor-" + RUN + "@" + STRICT;
        String pid = principal(email);
        var r = http.delete("/auth/2fa/methods/TOTP", "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
        assertThat(json(r).get("error").asString()).isNotEqualTo("LAST_FACTOR");
    }

    // ── review: a backoff-store failure at verify refuses and is counted (C-Q23) ──

    @Test
    void aBackoffStoreFailureAtVerifyRefusesWith503AndCountsTheAlarm() {
        String email = "outage-" + RUN + "@" + LOOSE;
        String pid = principal(email);
        enrolTotpDirect(pid);
        String mfaToken = TOKENS.mint(pid, MfaToken.Purpose.PENDING);
        var brokenAttempts = new LoginAttemptRepository(brokenDataSource());
        var brokenLogin = new LoginApi.State(PRINCIPALS, MAPPINGS, IDPS, brokenAttempts,
                new BackoffCheck(brokenAttempts, BackoffPolicy.DEFAULT), TOKEN_ISSUER, RESOLVER,
                new LoginMfaGate(MFA, POLICY, TOKENS, DEVICE_COOKIE), new SessionCookie(false), DS, Clock.systemUTC());
        try (var closed = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            TwoFactorApi.register(cfg.routes, new TwoFactorApi.State(brokenLogin, MFA, POLICY, TOKENS, DEVICE_COOKIE, AUDIT, NOTIFIER));
        })) {
            long before = io.flowcatalyst.platform.auth.login.AuthAlarms.backoffStoreErrors();
            var r = closed.post("/auth/2fa/verify",
                    Json.writeLine(Map.of("mfaToken", mfaToken, "method", "TOTP", "code", "000000")),
                    "Content-Type", "application/json");
            assertThat(r.statusCode()).as("ruling C-Q23: the lock is never switched off by a store error").isEqualTo(503);
            assertThat(json(r).get("error").asString()).isEqualTo("BACKOFF_UNAVAILABLE");
            assertThat(io.flowcatalyst.platform.auth.login.AuthAlarms.backoffStoreErrors()).isEqualTo(before + 1);
        }
    }

    private static DataSource brokenDataSource() {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) throw new java.sql.SQLException("simulated outage");
                    if (method.getName().equals("toString")) return "broken";
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    // ── mutant 6: a wrong code records a FAILURE attempt row ────────────────

    @Test
    void aWrongVerifyCodeRecordsAFailureAttemptRowAndAnsweredsUnauthorized() {
        String email = "wrong-" + RUN + "@" + LOOSE;
        String pid = principal(email);
        enrolTotpDirect(pid);
        String mfaToken = TOKENS.mint(pid, MfaToken.Purpose.PENDING);
        int before = attemptCount(email);

        var r = http.post("/auth/2fa/verify",
                Json.writeLine(Map.of("mfaToken", mfaToken, "method", "TOTP", "code", "000000")),
                "Content-Type", "application/json");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Cookie realm=\"fc_session\"");
        assertThat(attemptCount(email) - before).as("a FAILURE row for the wrong code").isEqualTo(1);
        var last = lastAttempt(email);
        assertThat(last.get(IAM_LOGIN_ATTEMPTS.OUTCOME)).isEqualTo("FAILURE");
        assertThat(last.get(IAM_LOGIN_ATTEMPTS.FAILURE_REASON)).isEqualTo("Invalid 2FA code");
    }

    // ── other behaviour worth pinning ───────────────────────────────────────

    @Test
    void anUnknownMethodIs400InvalidMethod() {
        String email = "invmethod-" + RUN + "@" + LOOSE;
        String pid = principal(email);
        String mfaToken = TOKENS.mint(pid, MfaToken.Purpose.PENDING);
        var r = http.post("/auth/2fa/verify",
                Json.writeLine(Map.of("mfaToken", mfaToken, "method", "PASSKEY", "code", "000000")),
                "Content-Type", "application/json");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("INVALID_METHOD");
    }

    @Test
    void anExpiredOrForgedMfaTokenIs401WithTheSessionRealm() {
        var r = http.post("/auth/2fa/verify",
                Json.writeLine(Map.of("mfaToken", "not-a-token", "method", "TOTP", "code", "000000")),
                "Content-Type", "application/json");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("error").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(json(r).get("message").asString()).isEqualTo("Invalid or expired session");
        assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Cookie realm=\"fc_session\"");
    }

    @Test
    void aSessionTokenCannotSubstituteForAPendingMfaToken() {
        // A token minted for the wrong purpose (ENROLL, not PENDING) must be
        // rejected exactly like garbage — purposes don't cross.
        String email = "wrongpurpose-" + RUN + "@" + LOOSE;
        String pid = principal(email);
        String enrollToken = TOKENS.mint(pid, MfaToken.Purpose.ENROLL);
        var r = http.post("/auth/2fa/verify",
                Json.writeLine(Map.of("mfaToken", enrollToken, "method", "TOTP", "code", "000000")),
                "Content-Type", "application/json");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void statusReportsConfirmedMethodsThePolicyAndTheCounts() {
        String email = "status-" + RUN + "@" + STRICT;
        String pid = principal(email);
        enrolTotpDirect(pid);
        MFA.generateRecoveryCodes(pid);
        var r = http.get("/auth/2fa/status", "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var j = json(r);
        assertThat(j.get("methods").toString()).isEqualTo("[\"TOTP\"]");
        assertThat(j.get("required").asBoolean()).isTrue();
        assertThat(j.get("allowedMethods").toString()).isEqualTo("[\"TOTP\"]");
        assertThat(j.get("recoveryCodesLeft").asInt()).isEqualTo(10);
        assertThat(j.get("rememberDeviceEnabled").asBoolean()).isTrue();
        assertThat(j.get("trustedDeviceCount").asInt()).isZero();
    }

    @Test
    void regeneratingRecoveryCodesRequiresAConfirmedTotp() {
        String email = "nototp-" + RUN + "@" + LOOSE;
        String pid = principal(email);
        var r = http.post("/auth/2fa/recovery-codes/regenerate", null, "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("NO_TOTP");

        enrolTotpDirect(pid);
        var ok = http.post("/auth/2fa/recovery-codes/regenerate", null, "Cookie", sessionCookieFor(pid, email));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(json(ok).get("recoveryCodes").size()).isEqualTo(10);
        assertThat(auditCount(pid, "2FA_RECOVERY_REGENERATED")).isEqualTo(1);
    }

    @Test
    void trustedDeviceListItemsCarryNoPrincipalId() {
        String email = "tdlist-" + RUN + "@" + STRICT;
        String pid = principal(email);
        MFA.issueTrustedDevice(pid, "Mozilla/5.0", java.time.Duration.ofDays(7));
        var r = http.get("/auth/2fa/trusted-devices", "Cookie", sessionCookieFor(pid, email));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var device = json(r).get("devices").get(0);
        assertThat(device.has("principalId")).as("ruling I-Q21").isFalse();
        assertThat(device.get("label").asString()).isEqualTo("Mozilla/5.0");
        assertThat(device.has("id")).isTrue();
        assertThat(device.has("expiresAt")).isTrue();
        assertThat(device.has("createdAt")).isTrue();
    }

    @Test
    void selfServiceEmailEnrolmentSendsAPinAndNotifiesOnConfirm() {
        String email = "selfemail-" + RUN + "@" + LOOSE;
        String pid = principal(email);
        MAIL_SENT.clear();
        var begin = http.post("/auth/2fa/methods/email/begin", null, "Cookie", sessionCookieFor(pid, email));
        assertThat(begin.statusCode()).as(begin.body()).isEqualTo(200);
        assertThat(MAIL_SENT).hasSize(1);
        String pin = pinIn(MAIL_SENT.getLast());

        var confirm = http.post("/auth/2fa/methods/email/confirm", Json.writeLine(Map.of("code", pin)),
                "Cookie", sessionCookieFor(pid, email));
        assertThat(confirm.statusCode()).as(confirm.body()).isEqualTo(200);
        assertThat(json(confirm).get("recoveryCodes").isEmpty()).as("recovery codes back TOTP only").isTrue();
        assertThat(NOTICES).contains("enrolled:" + email + ":EMAIL_PIN");
        assertThat(auditCount(pid, "2FA_EMAIL_ENROLLED")).isEqualTo(1);
        assertThat(MFA.confirmed(pid)).containsExactly(MfaMethod.EMAIL_PIN);
    }

    @Test
    void malformedJsonIs400InvalidJson() {
        var r = http.post("/auth/2fa/verify", "{not json", "Content-Type", "application/json");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("INVALID_JSON");
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static HttpResponse<String> login(String email) {
        return http.post("/auth/login", Json.writeLine(Map.of("email", email, "password", PASSWORD)),
                "Content-Type", "application/json");
    }

    private static String enrolTotpDirect(String pid) {
        var e = MFA.beginTotpEnrollment(pid, "x@example.com");
        assertThat(MFA.confirmTotpEnrollment(pid, Totp.code(e.secret(), Totp.stepOf(Instant.now())))).isTrue();
        return e.secret();
    }

    private static String sessionCookieFor(String pid, String email) {
        return SessionCookie.NAME + "=" + TOKEN_ISSUER.sessionToken(pid, email);
    }

    private static Optional<String> setCookie(HttpResponse<String> r, String name) {
        return r.headers().allValues("set-cookie").stream().filter(c -> c.startsWith(name + "=")).findFirst();
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static int attemptCount(String identifier) {
        return DB.fetchCount(IAM_LOGIN_ATTEMPTS, IAM_LOGIN_ATTEMPTS.IDENTIFIER.eq(identifier.toLowerCase(Locale.ROOT)));
    }

    private static org.jooq.Record lastAttempt(String identifier) {
        return DB.selectFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.IDENTIFIER.eq(identifier.toLowerCase(Locale.ROOT)))
                .orderBy(IAM_LOGIN_ATTEMPTS.ATTEMPTED_AT.desc(), IAM_LOGIN_ATTEMPTS.ID.desc()).limit(1).fetchOne();
    }

    private static int auditCount(String principalId, String operation) {
        return DB.fetchCount(AUD_LOGS, AUD_LOGS.ENTITY_ID.eq(principalId).and(AUD_LOGS.OPERATION.eq(operation)));
    }

    /// The six digits between the bold `<p>` tags of the rendered mail (mirrors `MfaServiceTest`).
    private static String pinIn(String sent) {
        int i = sent.indexOf("letter-spacing:3px\">") + "letter-spacing:3px\">".length();
        return sent.substring(i, i + 6);
    }

    private static String principal(String email) {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "CLIENT")
                .set(IAM_PRINCIPALS.NAME, "TwoFA " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, email.substring(email.indexOf('@') + 1))
                .set(IAM_PRINCIPALS.PASSWORD_HASH, PasswordHash.hash(PASSWORD))
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW)
                .execute();
        principals.add(id);
        return id;
    }

    private static void mapping(String domain, TwoFactorPolicy tf) {
        var idp = IdentityProvider.create("idp-" + domain, "IdP " + domain, IdentityProviderType.INTERNAL);
        var m = EmailDomainMapping.create(EmailDomain.parse(domain), idp.id(), ScopeType.ANCHOR).withTwoFactor(tf);
        idpIds.add(idp.id());
        mappingIds.add(m.id());
        inTx(tx -> {
            IDPS.persist(idp, tx);
            MAPPINGS.persist(m, tx);
        });
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
}
