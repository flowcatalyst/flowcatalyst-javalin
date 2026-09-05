package io.flowcatalyst.platform.passwordreset;

import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.auth.mfa.DomainPolicy;
import io.flowcatalyst.platform.auth.mfa.MailSender;
import io.flowcatalyst.platform.auth.mfa.Mfa;
import io.flowcatalyst.platform.auth.mfa.MfaRepository;
import io.flowcatalyst.platform.auth.mfa.MfaToken;
import io.flowcatalyst.platform.auth.mfa.Totp;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.TwoFactorPolicy;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.mail.Mail;
import io.flowcatalyst.platform.notify.Notifications;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.publicapi.EmailTheme;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
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
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.db.generated.Tables.IAM_MFA_TRUSTED_DEVICES;
import static io.flowcatalyst.db.generated.Tables.IAM_PASSWORD_RESET_TOKENS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_REFRESH_TOKENS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_METHODS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_RECOVERY_CODES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS;
import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/auth-identity.md` §8.1–§8.5 over HTTP against the embedded
/// Postgres, with a capturing mail transport so the link in the message is
/// the one the confirm step redeems.
class PasswordResetApiTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final Encryption ENC = Encryption.withKey(Encryption.generateKey());
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final String BASE = "http://localhost:8080";
    private static final String OLD_PASSWORD = "old-password-" + RUN;
    private static final String NEW_PASSWORD = "Correct-Horse-Battery-Staple-77";

    private static final AtomicReference<Instant> CLOCK = new AtomicReference<>(Instant.now());
    private static final Clock MOVABLE = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return CLOCK.get(); }
    };

    private static final List<Mail> SENT = new ArrayList<>();
    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final ResetTokenRepository TOKENS = new ResetTokenRepository(DS);
    private static final EmailDomainMappingRepository MAPPINGS = new EmailDomainMappingRepository(DS);
    private static final IdentityProviderRepository IDPS = new IdentityProviderRepository(DS);
    private static final MfaRepository MFA_REPO = new MfaRepository(DS);
    private static final Mfa MFA = new Mfa(MFA_REPO, Optional.of(ENC), MailSender.logging(), Mfa.Config.DEFAULT, Clock.systemUTC());
    private static final GrantStore GRANTS = new GrantStore(DS);
    private static final ResetLinks LINKS = new ResetLinks(TOKENS, SENT::add, () -> EmailTheme.defaults("Acme"), BASE, MOVABLE);
    private static final Notifications NOTICES = new Notifications(SENT::add, () -> "Acme");
    private static final MfaToken MFA_TOKENS = new MfaToken(KEYS.privateKey(), BASE);

    private static String strictDomain;
    private static String mappingId;
    private static String idpId;
    private static final List<String> principals = new ArrayList<>();
    private static final AtomicReference<PortalPasswords> PORTAL = new AtomicReference<>(PortalPasswords.notWired());
    private static TestHttp http;

    @BeforeAll
    static void start() {
        strictDomain = "strict-" + RUN + ".example";
        var idp = IdentityProvider.create("idp-pr-" + RUN, "Internal " + RUN, IdentityProviderType.INTERNAL);
        idpId = idp.id();
        var m = EmailDomainMapping.create(EmailDomain.parse(strictDomain), idpId, ScopeType.ANCHOR)
                .withTwoFactor(new TwoFactorPolicy(true, List.of(MfaMethod.TOTP), false, 30));
        mappingId = m.id();
        UOW.inTransaction(tx -> { IDPS.persist(idp, tx.dbTx()); MAPPINGS.persist(m, tx.dbTx()); return null; });
        var state = new PasswordResetApi.State(LINKS, TOKENS, PRINCIPALS, UOW, MFA, MFA_TOKENS, new DomainPolicy.Evaluator(MAPPINGS),
                GRANTS, NOTICES, new PortalPasswords() {
                    @Override public Optional<Identity> find(String id) { return PORTAL.get().find(id); }
                    @Override public boolean setPasswordHash(String id, String hash) { return PORTAL.get().setPasswordHash(id, hash); }
                }, ApprovalQueue.none(), false, MOVABLE);
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            PasswordResetApi.register(cfg.routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        DB.deleteFrom(IAM_PASSWORD_RESET_TOKENS).where(IAM_PASSWORD_RESET_TOKENS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_REFRESH_TOKENS).where(IAM_REFRESH_TOKENS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_USER_MFA_METHODS).where(IAM_USER_MFA_METHODS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_USER_MFA_RECOVERY_CODES).where(IAM_USER_MFA_RECOVERY_CODES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_MFA_TRUSTED_DEVICES).where(IAM_MFA_TRUSTED_DEVICES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(principals)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS).where(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS.EMAIL_DOMAIN_MAPPING_ID.eq(mappingId)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.eq(mappingId)).execute();
        DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.eq(idpId)).execute();
    }

    // ── request ────────────────────────────────────────────────────────────

    @Test
    void requestAlwaysAnswersTheSameAndOnlyAnEligibleUserGetsAToken() {
        String email = "req-" + RUN + "@example.com";
        String pid = user(email, PasswordHash.hash(OLD_PASSWORD), null);
        SENT.clear();
        var r = http.post("/auth/password-reset/request", Json.write(Map.of("email", email.toUpperCase(Locale.ROOT))));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("message").asString()).isEqualTo("If an account exists, a reset email has been sent.");
        assertThat(SENT).hasSize(1);
        assertThat(SENT.getFirst().subject()).isEqualTo("Reset your password");
        assertThat(SENT.getFirst().html()).contains(BASE + "/auth/reset-password?token=");
        var token = TOKENS.findByHash(ResetToken.hash(linkToken(SENT.getFirst()))).orElseThrow();
        assertThat(token.principalId()).isEqualTo(pid);
        assertThat(token.requiresFactor()).as("no TOTP ⇒ no factor gate").isFalse();
        assertThat(token.purpose()).isEqualTo(ResetToken.Purpose.RESET);

        // A second request replaces the first token: one live token per subject.
        http.post("/auth/password-reset/request", Json.write(Map.of("email", email)));
        assertThat(TOKENS.findByHash(token.tokenHash())).isEmpty();
        assertThat(DB.fetchCount(IAM_PASSWORD_RESET_TOKENS, IAM_PASSWORD_RESET_TOKENS.PRINCIPAL_ID.eq(pid))).isEqualTo(1);

        SENT.clear();
        for (String body : List.of(Json.write(Map.of("email", "nobody-" + RUN + "@example.com")), Json.write(Map.of("email", "")), "{}")) {
            var same = http.post("/auth/password-reset/request", body);
            assertThat(same.statusCode()).isEqualTo(200);
            assertThat(json(same).get("message").asString()).isEqualTo("If an account exists, a reset email has been sent.");
        }
        assertThat(SENT).as("unknown or blank addresses send nothing").isEmpty();
        assertThat(http.post("/auth/password-reset/request", "not json").statusCode()).isEqualTo(400);

        String federated = "fed-" + RUN + "@example.com";
        user(federated, null, "OIDC");
        http.post("/auth/password-reset/request", Json.write(Map.of("email", federated)));
        assertThat(SENT).as("a federated identity is ineligible").isEmpty();
    }

    @Test
    void aUserWithTotpGetsAFactorGatedTokenThatValidateReportsWithoutConsuming() {
        String email = "totp-" + RUN + "@example.com";
        String pid = user(email, PasswordHash.hash(OLD_PASSWORD), null);
        enrolTotp(pid);
        SENT.clear();
        http.post("/auth/password-reset/request", Json.write(Map.of("email", email)));
        String raw = linkToken(SENT.getFirst());
        var v = http.get("/auth/password-reset/validate?token=" + raw);
        assertThat(v.statusCode()).isEqualTo(200);
        assertThat(v.body().trim()).isEqualTo("{\"valid\":true,\"reason\":null,\"requiresFactor\":true}");
        assertThat(http.get("/auth/password-reset/validate?token=" + raw).body()).as("validate does not consume").contains("\"valid\":true");
        assertThat(http.get("/auth/password-reset/validate?token=nope").body().trim())
                .isEqualTo("{\"valid\":false,\"reason\":\"not_found\",\"requiresFactor\":false}");
    }

    // ── confirm ────────────────────────────────────────────────────────────

    @Test
    void confirmSetsThePasswordRevokesDevicesAndRefreshTokensAndNotifies() throws Exception {
        String email = "confirm-" + RUN + "@example.com";
        String pid = user(email, PasswordHash.hash(OLD_PASSWORD), null);
        MFA.issueTrustedDevice(pid, "laptop", Duration.ofDays(7));
        var issued = RefreshToken.issue(pid, Instant.now());
        GRANTS.insert(issued.token().withFamily(issued.token().id()));
        SENT.clear();
        http.post("/auth/password-reset/request", Json.write(Map.of("email", email)));
        String raw = linkToken(SENT.getFirst());
        SENT.clear();

        var r = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw, "password", NEW_PASSWORD)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("status").asString()).isEqualTo("ok");
        assertThat(json(r).get("message").asString()).isEqualTo("Password reset successfully.");
        assertThat(PasswordHash.matches(NEW_PASSWORD, storedHash(pid))).isTrue();
        assertThat(MFA.countTrustedDevices(pid)).as("trusted devices revoked").isZero();
        assertThat(GRANTS.findValidByHash(issued.token().tokenHash())).as("refresh tokens revoked").isEmpty();
        assertThat(SENT).extracting(Mail::subject).containsExactly("Your password was changed");
        assertThat(TOKENS.findByHash(ResetToken.hash(raw))).as("consumed").isEmpty();

        var replay = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw, "password", NEW_PASSWORD)));
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(json(replay).get("error").asString()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void aWeakPasswordIsRefusedWithThePolicyCodeAndTheTokenSurvives() {
        String email = "weak-" + RUN + "@example.com";
        user(email, PasswordHash.hash(OLD_PASSWORD), null);
        SENT.clear();
        http.post("/auth/password-reset/request", Json.write(Map.of("email", email)));
        String raw = linkToken(SENT.getFirst());
        var r = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw, "password", "short")));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("error").asString()).isEqualTo("PASSWORD_TOO_SHORT");
        assertThat(TOKENS.findByHash(ResetToken.hash(raw))).isPresent();
    }

    @Test
    void anExpiredTokenBurnsTheSubjectsTokens() {
        String email = "expired-" + RUN + "@example.com";
        String pid = user(email, PasswordHash.hash(OLD_PASSWORD), null);
        Instant t0 = Instant.now();
        CLOCK.set(t0);
        SENT.clear();
        http.post("/auth/password-reset/request", Json.write(Map.of("email", email)));
        String raw = linkToken(SENT.getFirst());
        CLOCK.set(t0.plus(Duration.ofMinutes(15)));
        try {
            assertThat(http.get("/auth/password-reset/validate?token=" + raw).body()).contains("\"reason\":\"expired\"");
            var r = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw, "password", NEW_PASSWORD)));
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(json(r).get("error").asString()).isEqualTo("EXPIRED_TOKEN");
            assertThat(DB.fetchCount(IAM_PASSWORD_RESET_TOKENS, IAM_PASSWORD_RESET_TOKENS.PRINCIPAL_ID.eq(pid))).isZero();
        } finally {
            CLOCK.set(Instant.now());
        }
    }

    @Test
    void aFactorGatedTokenTakesFiveWrongCodesThenBurns() {
        String email = "gated-" + RUN + "@example.com";
        String pid = user(email, PasswordHash.hash(OLD_PASSWORD), null);
        String secret = enrolTotp(pid);
        SENT.clear();
        http.post("/auth/password-reset/request", Json.write(Map.of("email", email)));
        String raw = linkToken(SENT.getFirst());

        var noCode = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw, "password", NEW_PASSWORD)));
        assertThat(noCode.statusCode()).isEqualTo(400);
        assertThat(json(noCode).get("error").asString()).isEqualTo("INVALID_FACTOR");
        for (int i = 0; i < 3; i++) {
            assertThat(json(http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw, "password", NEW_PASSWORD, "factorCode", "000000"))))
                    .get("error").asString()).isEqualTo("INVALID_FACTOR");
        }
        assertThat(TOKENS.findByHash(ResetToken.hash(raw)).orElseThrow().factorAttempts()).isEqualTo(4);
        var fifth = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw, "password", NEW_PASSWORD, "factorCode", "000000")));
        assertThat(json(fifth).get("error").asString()).as("the fifth wrong code burns the token").isEqualTo("INVALID_TOKEN");
        assertThat(TOKENS.findByHash(ResetToken.hash(raw))).isEmpty();
        assertThat(PasswordHash.matches(OLD_PASSWORD, storedHash(pid))).as("the password is untouched").isTrue();

        // A fresh token with the right code goes through.
        SENT.clear();
        http.post("/auth/password-reset/request", Json.write(Map.of("email", email)));
        String raw2 = linkToken(SENT.getFirst());
        String code = Totp.code(secret, Totp.stepOf(Instant.now()) + 1); // ahead of the enrolment step's guard
        var ok = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw2, "password", NEW_PASSWORD, "factorCode", code)));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(PasswordHash.matches(NEW_PASSWORD, storedHash(pid))).isTrue();
    }

    @Test
    void anAdminResetWithReset2faClearsTheFactorsAndARequiringDomainDemandsEnrolment() {
        String email = "admin-" + RUN + "@" + strictDomain;
        String pid = user(email, PasswordHash.hash(OLD_PASSWORD), null);
        enrolTotp(pid);
        assertThat(MFA.confirmed(pid)).containsExactly(MfaMethod.TOTP);
        SENT.clear();
        LINKS.sendResetEmail(PRINCIPALS.findById(pid).orElseThrow(), true);
        assertThat(SENT).hasSize(1);
        String raw = linkToken(SENT.getFirst());
        assertThat(TOKENS.findByHash(ResetToken.hash(raw)).orElseThrow().requiresFactor()).as("ruling I-Q14: an admin link never asks for the factor").isFalse();
        SENT.clear();

        var r = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", raw, "password", NEW_PASSWORD)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode j = json(r);
        assertThat(MFA.confirmed(pid)).as("reset_2fa cleared the factors").isEmpty();
        assertThat(j.get("status").asString()).as("the domain requires a factor the user no longer has").isEqualTo("enrollment_required");
        assertThat(j.get("allowedMethods").toString()).isEqualTo("[\"TOTP\"]");
        assertThat(MFA_TOKENS.parse(j.get("enrollToken").asString(), MfaToken.Purpose.ENROLL).orElseThrow().subject()).isEqualTo(pid);
        assertThat(SENT).extracting(Mail::subject).containsExactly("Two-factor authentication was reset", "Your password was changed");
    }

    @Test
    void invitesAreSeventyTwoHourSetPasswordLinksAndAPortalTokenNeedsThePortalPlane() {
        String email = "invite-" + RUN + "@example.com";
        String pid = user(email, null, null);
        SENT.clear();
        LINKS.sendInvite(PRINCIPALS.findById(pid).orElseThrow());
        assertThat(SENT.getFirst().subject()).isEqualTo("Set your password");
        assertThat(SENT.getFirst().html()).contains("Welcome to Acme").contains(BASE + "/auth/set-password?token=");
        var t = TOKENS.findByHash(ResetToken.hash(linkToken(SENT.getFirst()))).orElseThrow();
        assertThat(t.purpose()).isEqualTo(ResetToken.Purpose.INVITE);
        assertThat(Duration.between(t.createdAt(), t.expiresAt())).isEqualTo(Duration.ofHours(72));
        assertThat(LINKS.inviteLink(PRINCIPALS.findById(pid).orElseThrow())).startsWith(BASE + "/auth/set-password?token=");
        assertThat(DB.fetchCount(IAM_PASSWORD_RESET_TOKENS, IAM_PASSWORD_RESET_TOKENS.PRINCIPAL_ID.eq(pid))).as("one live token").isEqualTo(1);

        // A portal subject: validate says portal; confirm needs the portal plane.
        String identityId = "ptu_" + RUN + "x";
        SENT.clear();
        LINKS.sendPortalInvite(identityId, "p@example.com", "https://portal.example/");
        assertThat(SENT.getFirst().subject()).isEqualTo("Join the portal");
        assertThat(SENT.getFirst().html()).contains("Portal").doesNotContain("Acme").contains("This is an automated message");
        String praw = linkToken(SENT.getFirst());
        assertThat(http.get("/auth/password-reset/validate?token=" + praw).body()).contains("\"portal\":true");
        var notWired = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", praw, "password", NEW_PASSWORD)));
        assertThat(json(notWired).get("error").asString()).isEqualTo("INVALID_TOKEN");

        var stored = new AtomicReference<String>();
        PORTAL.set(new PortalPasswords() {
            @Override public Optional<Identity> find(String id) { return Optional.of(new Identity(id, "p@example.com", "Pat", true)); }
            @Override public boolean setPasswordHash(String id, String hash) { stored.set(hash); return true; }
        });
        try {
            var weak = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", praw, "password", "pat")));
            assertThat(json(weak).get("error").asString()).isEqualTo("PASSWORD_TOO_SHORT");
            SENT.clear();
            var ok = http.post("/auth/password-reset/confirm", Json.write(Map.of("token", praw, "password", NEW_PASSWORD)));
            assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
            assertThat(json(ok).get("portal").asBoolean()).isTrue();
            assertThat(json(ok).get("redirectUri").asString()).isEqualTo("https://portal.example/");
            assertThat(PasswordHash.matches(NEW_PASSWORD, stored.get())).isTrue();
            assertThat(SENT).extracting(Mail::subject).containsExactly("Your portal password was changed");
            assertThat(TOKENS.findByHash(ResetToken.hash(praw))).isEmpty();
        } finally {
            PORTAL.set(PortalPasswords.notWired());
            DB.deleteFrom(IAM_PASSWORD_RESET_TOKENS).where(IAM_PASSWORD_RESET_TOKENS.PRINCIPAL_ID.eq(identityId)).execute();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String linkToken(Mail m) {
        int i = m.html().indexOf("token=");
        assertThat(i).as(m.html()).isPositive();
        int end = m.html().indexOf('"', i);
        return m.html().substring(i + "token=".length(), end);
    }

    private static String enrolTotp(String pid) {
        var e = MFA.beginTotpEnrollment(pid, "x@example.com");
        assertThat(MFA.confirmTotpEnrollment(pid, Totp.code(e.secret(), Totp.stepOf(Instant.now())))).isTrue();
        return e.secret();
    }

    private static String storedHash(String pid) {
        return DB.select(IAM_PRINCIPALS.PASSWORD_HASH).from(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(pid)).fetchOne(IAM_PRINCIPALS.PASSWORD_HASH);
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static String user(String email, String hash, String idpType) {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.NAME, "Reset " + RUN).set(IAM_PRINCIPALS.ACTIVE, true).set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, email.substring(email.indexOf('@') + 1))
                .set(IAM_PRINCIPALS.PASSWORD_HASH, hash).set(IAM_PRINCIPALS.IDP_TYPE, idpType)
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW).execute();
        principals.add(id);
        return id;
    }
}
