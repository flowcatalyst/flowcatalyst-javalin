package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.db.generated.Tables.IAM_MFA_EMAIL_PINS;
import static io.flowcatalyst.db.generated.Tables.IAM_MFA_TRUSTED_DEVICES;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_METHODS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_RECOVERY_CODES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/auth-identity.md` §6.5 and §11.4–11.7 against the embedded
/// Postgres, under a controllable clock. Each test names the guarantee
/// it pins; the replay guard, the PIN budget and the single-use recovery
/// code are the ones a mutant must not survive.
class MfaServiceTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final Encryption ENC = Encryption.withKey(Encryption.generateKey());
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    /// A clock the tests move.
    private static final AtomicReference<Instant> CLOCK = new AtomicReference<>(T0);
    private static final Clock MOVABLE = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return CLOCK.get();
        }
    };

    /// Captures what the mail seam would have sent.
    private static final List<String> SENT = new ArrayList<>();
    private static final MailSender MAIL = (to, subject, html) -> SENT.add(to + "|" + subject + "|" + html);

    private static final MfaRepository REPO = new MfaRepository(DS, MOVABLE);
    private static final Mfa MFA = new Mfa(REPO, Optional.of(ENC), MAIL, Mfa.Config.DEFAULT, MOVABLE);

    private static final List<String> PRINCIPALS = new ArrayList<>();

    @BeforeAll
    static void start() {
        CLOCK.set(T0);
    }

    @AfterAll
    static void stop() {
        DB.deleteFrom(IAM_USER_MFA_METHODS).where(IAM_USER_MFA_METHODS.PRINCIPAL_ID.in(PRINCIPALS)).execute();
        DB.deleteFrom(IAM_USER_MFA_RECOVERY_CODES).where(IAM_USER_MFA_RECOVERY_CODES.PRINCIPAL_ID.in(PRINCIPALS)).execute();
        DB.deleteFrom(IAM_MFA_EMAIL_PINS).where(IAM_MFA_EMAIL_PINS.PRINCIPAL_ID.in(PRINCIPALS)).execute();
        DB.deleteFrom(IAM_MFA_TRUSTED_DEVICES).where(IAM_MFA_TRUSTED_DEVICES.PRINCIPAL_ID.in(PRINCIPALS)).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(PRINCIPALS)).execute();
    }

    // ── TOTP ───────────────────────────────────────────────────────────────

    /// A replay is its own outcome, not a mismatch — a valid code presented again
    /// is the signal a relayed or shoulder-surfed code gives. Mutant: a replay
    /// answered as Mismatch.
    @Test
    void aReplayedCodeIsToldApartFromAWrongOne() {
        String pid = principal();
        CLOCK.set(T0);
        var enrolment = MFA.beginTotpEnrollment(pid, "replay@example.com");
        assertThat(MFA.checkTotp(pid, Totp.code(enrolment.secret(), Totp.stepOf(T0))))
                .as("unconfirmed").isInstanceOf(Mfa.TotpCheck.NotEnrolled.class);
        String code = Totp.code(enrolment.secret(), Totp.stepOf(T0));
        assertThat(MFA.confirmTotpEnrollment(pid, code)).isTrue();

        String next = Totp.code(enrolment.secret(), Totp.stepOf(T0) + 1);
        assertThat(MFA.checkTotp(pid, next)).isInstanceOf(Mfa.TotpCheck.Accepted.class);
        assertThat(MFA.checkTotp(pid, next)).isInstanceOf(Mfa.TotpCheck.Replayed.class);
        assertThat(MFA.checkTotp(pid, code)).as("the enrolment code, spent").isInstanceOf(Mfa.TotpCheck.Replayed.class);
        assertThat(MFA.checkTotp(pid, "123456")).isInstanceOf(Mfa.TotpCheck.Mismatch.class);
    }

    @Test
    void totpEnrolmentConfirmsWithTheCurrentCodeAndThatCodeCannotBeReplayedAtLogin() {
        String pid = principal();
        CLOCK.set(T0);
        var enrolment = MFA.beginTotpEnrollment(pid, "user@example.com");
        assertThat(enrolment.uri()).startsWith("otpauth://totp/FlowCatalyst:user@example.com?").contains("secret=" + enrolment.secret());
        assertThat(enrolment.qr()).isPresent();
        assertThat(DB.select(IAM_USER_MFA_METHODS.SECRET_ENCRYPTED).from(IAM_USER_MFA_METHODS)
                .where(IAM_USER_MFA_METHODS.PRINCIPAL_ID.eq(pid)).fetchOne().value1())
                .as("the secret is not stored in the clear").isNotEqualTo(enrolment.secret()).isNotEmpty();
        assertThat(MFA.confirmed(pid)).as("pending never counts").isEmpty();
        assertThat(MFA.verifyTotp(pid, Totp.code(enrolment.secret(), Totp.stepOf(T0)))).as("unconfirmed never verifies").isFalse();

        assertThat(MFA.confirmTotpEnrollment(pid, "000000")).isFalse();
        String code = Totp.code(enrolment.secret(), Totp.stepOf(T0));
        assertThat(MFA.confirmTotpEnrollment(pid, code)).isTrue();
        assertThat(MFA.confirmed(pid)).containsExactly(MfaMethod.TOTP);

        // The enrolment code is spent: the replay guard sits at its step. The
        // guard has two halves — the in-memory step compare and the guarded
        // UPDATE (last_used_at < step) — and only weakening BOTH lets a
        // replay through (mutation-checked); each alone is redundant defence.
        assertThat(MFA.verifyTotp(pid, code)).as("§6.5: the enrolment code cannot be replayed at login").isFalse();
        // The previous step's code is inside the skew but behind the guard.
        assertThat(MFA.verifyTotp(pid, Totp.code(enrolment.secret(), Totp.stepOf(T0) - 1))).isFalse();
        // The next step's code is ahead of the guard: accepted once.
        String next = Totp.code(enrolment.secret(), Totp.stepOf(T0) + 1);
        assertThat(MFA.verifyTotp(pid, next)).isTrue();
        assertThat(MFA.verifyTotp(pid, next)).as("replay of an accepted login code").isFalse();
        assertThat(MFA.verifyTotp(pid, "123456")).isFalse();

        assertThatThrownBy(() -> MFA.beginTotpEnrollment(pid, "user@example.com")).isInstanceOf(Mfa.AlreadyEnrolled.class);
        assertThatThrownBy(() -> MFA.confirmTotpEnrollment(pid, next)).isInstanceOf(Mfa.AlreadyEnrolled.class);
    }

    @Test
    void beginningAgainReplacesAPendingSecretAndConfirmingNothingIsAnError() {
        String pid = principal();
        var first = MFA.beginTotpEnrollment(pid, "a@example.com");
        var second = MFA.beginTotpEnrollment(pid, "a@example.com");
        assertThat(second.secret()).isNotEqualTo(first.secret());
        assertThat(MFA.confirmTotpEnrollment(pid, Totp.code(first.secret(), Totp.stepOf(CLOCK.get())))).as("the old secret is gone").isFalse();
        assertThat(MFA.confirmTotpEnrollment(pid, Totp.code(second.secret(), Totp.stepOf(CLOCK.get())))).isTrue();

        String fresh = principal();
        assertThatThrownBy(() -> MFA.confirmTotpEnrollment(fresh, "123456")).isInstanceOf(Mfa.NoPendingEnrollment.class);
    }

    @Test
    void withoutAnEncryptionServiceEveryTotpPathFailsClosed() {
        Mfa bare = new Mfa(REPO, Optional.empty(), MAIL, Mfa.Config.DEFAULT, MOVABLE);
        String pid = principal();
        assertThatThrownBy(() -> bare.beginTotpEnrollment(pid, "x@example.com")).isInstanceOf(Mfa.EncryptionUnavailable.class);
        assertThat(DB.fetchCount(IAM_USER_MFA_METHODS, IAM_USER_MFA_METHODS.PRINCIPAL_ID.eq(pid))).as("nothing stored").isZero();
        MFA.beginTotpEnrollment(pid, "x@example.com");
        assertThatThrownBy(() -> bare.confirmTotpEnrollment(pid, "123456")).isInstanceOf(Mfa.EncryptionUnavailable.class);
    }

    // ── e-mail PIN ─────────────────────────────────────────────────────────

    @Test
    void anEmailPinIsSingleUseExpiresAndHasFiveAttempts() {
        String pid = principal();
        CLOCK.set(T0);
        SENT.clear();
        MFA.sendLoginEmailPin(pid, "pin@example.com");
        assertThat(SENT).hasSize(1);
        assertThat(SENT.getFirst()).startsWith("pin@example.com|Your verification code|<p>Your verification code is:</p>");
        String pin = pinIn(SENT.getFirst());
        assertThat(pin).matches("[0-9]{6}");
        assertThat(DB.select(IAM_MFA_EMAIL_PINS.PIN_HASH).from(IAM_MFA_EMAIL_PINS).where(IAM_MFA_EMAIL_PINS.PRINCIPAL_ID.eq(pid))
                .fetchOne().value1()).as("hashed at rest").isEqualTo(RecoveryCodes.sha256Hex(pin));

        assertThat(MFA.verifyLoginEmailPin(pid, "000000")).isFalse();
        assertThat(MFA.verifyLoginEmailPin(pid, " " + pin + " ")).as("trimmed").isTrue();
        assertThat(MFA.verifyLoginEmailPin(pid, pin)).as("consumed").isFalse();

        // Four wrong guesses leave the PIN usable; the fifth deletes it.
        MFA.sendLoginEmailPin(pid, "pin@example.com");
        String pin2 = pinIn(SENT.getLast());
        for (int i = 0; i < 4; i++) {
            assertThat(MFA.verifyLoginEmailPin(pid, "999999")).isFalse();
        }
        assertThat(DB.fetchCount(IAM_MFA_EMAIL_PINS, IAM_MFA_EMAIL_PINS.PRINCIPAL_ID.eq(pid))).isEqualTo(1);
        assertThat(MFA.verifyLoginEmailPin(pid, "999999")).isFalse();
        assertThat(DB.fetchCount(IAM_MFA_EMAIL_PINS, IAM_MFA_EMAIL_PINS.PRINCIPAL_ID.eq(pid))).as("deleted on the fifth").isZero();
        assertThat(MFA.verifyLoginEmailPin(pid, pin2)).as("the right PIN after the budget is gone").isFalse();

        // Expiry: ten minutes, exclusive.
        MFA.sendLoginEmailPin(pid, "pin@example.com");
        String pin3 = pinIn(SENT.getLast());
        CLOCK.set(T0.plus(Duration.ofMinutes(10)));
        assertThat(MFA.verifyLoginEmailPin(pid, pin3)).as("at exactly +10 min still valid (now > expires_at is false)").isTrue();
        MFA.sendLoginEmailPin(pid, "pin@example.com");
        String pin4 = pinIn(SENT.getLast());
        CLOCK.set(T0.plus(Duration.ofMinutes(20)).plusSeconds(1));
        assertThat(MFA.verifyLoginEmailPin(pid, pin4)).isFalse();
        assertThat(DB.fetchCount(IAM_MFA_EMAIL_PINS, IAM_MFA_EMAIL_PINS.PRINCIPAL_ID.eq(pid))).as("expired row deleted").isZero();
        CLOCK.set(T0);
    }

    @Test
    void reissuingReplacesTheOutstandingPinOfThatPurposeOnly() {
        String pid = principal();
        SENT.clear();
        MFA.sendLoginEmailPin(pid, "p@example.com");
        String login1 = pinIn(SENT.getLast());
        MFA.beginEmailEnrollment(pid, "p@example.com");
        String enroll = pinIn(SENT.getLast());
        MFA.sendLoginEmailPin(pid, "p@example.com");
        String login2 = pinIn(SENT.getLast());
        assertThat(MFA.verifyLoginEmailPin(pid, login1)).as("replaced").isFalse();
        assertThat(MFA.confirmEmailEnrollment(pid, login2)).as("a login PIN does not confirm an enrolment").isFalse();
        assertThat(MFA.confirmEmailEnrollment(pid, enroll)).isTrue();
        assertThat(MFA.confirmed(pid)).containsExactly(MfaMethod.EMAIL_PIN);
        assertThat(MFA.verifyLoginEmailPin(pid, login2)).isTrue();
        assertThatThrownBy(() -> MFA.beginEmailEnrollment(pid, "p@example.com")).isInstanceOf(Mfa.AlreadyEnrolled.class);
    }

    @Test
    void aMailFailurePropagatesSoTheCallerAnswers502() {
        Mfa broken = new Mfa(REPO, Optional.of(ENC), (to, s, h) -> { throw new IllegalStateException("smtp down"); },
                Mfa.Config.DEFAULT, MOVABLE);
        String pid = principal();
        assertThatThrownBy(() -> broken.sendLoginEmailPin(pid, "x@example.com")).hasMessage("smtp down");
    }

    // ── recovery codes ─────────────────────────────────────────────────────

    @Test
    void recoveryCodesAreSingleUseAcceptTheSpacedFormAndRegenerateAsAWholeSet() {
        String pid = principal();
        List<String> codes = MFA.generateRecoveryCodes(pid);
        assertThat(codes).hasSize(10);
        assertThat(MFA.remainingRecoveryCodes(pid)).isEqualTo(10);
        String c = codes.getFirst();
        String spaced = "  " + c.toLowerCase(Locale.ROOT).replace("-", " ") + " ";
        assertThat(MFA.verifyRecoveryCode(pid, spaced)).isTrue();
        assertThat(MFA.verifyRecoveryCode(pid, c)).as("single use").isFalse();
        assertThat(MFA.remainingRecoveryCodes(pid)).isEqualTo(9);
        assertThat(MFA.verifyRecoveryCode(pid, "ZZZZZ-ZZZZZ")).isFalse();
        assertThat(MFA.verifyRecoveryCode(pid, "")).isFalse();
        assertThat(MFA.verifyRecoveryCode(principal(), codes.get(1))).as("another principal's code").isFalse();

        List<String> fresh = MFA.generateRecoveryCodes(pid);
        assertThat(MFA.remainingRecoveryCodes(pid)).isEqualTo(10);
        assertThat(MFA.verifyRecoveryCode(pid, codes.get(2))).as("the old set is gone").isFalse();
        assertThat(MFA.verifyRecoveryCode(pid, fresh.get(2))).isTrue();
    }

    @Test
    void theFirstRecoverySetAppearsOnlyOnceTotpIsConfirmedAndNoneRemain() {
        String pid = principal();
        assertThat(MFA.ensureRecoveryCodes(pid)).as("no TOTP").isEmpty();
        var e = MFA.beginTotpEnrollment(pid, "r@example.com");
        assertThat(MFA.ensureRecoveryCodes(pid)).as("pending TOTP").isEmpty();
        MFA.confirmTotpEnrollment(pid, Totp.code(e.secret(), Totp.stepOf(CLOCK.get())));
        List<String> first = MFA.ensureRecoveryCodes(pid);
        assertThat(first).hasSize(10);
        assertThat(MFA.ensureRecoveryCodes(pid)).as("codes remain").isEmpty();
        for (String c : first) {
            MFA.verifyRecoveryCode(pid, c);
        }
        assertThat(MFA.ensureRecoveryCodes(pid)).as("all spent → a new set").hasSize(10);
    }

    // ── trusted devices ────────────────────────────────────────────────────

    @Test
    void aTrustedDeviceVerifiesByHashUntilItExpiresAndOnlyForItsOwner() {
        String pid = principal();
        CLOCK.set(T0);
        String raw = MFA.issueTrustedDevice(pid, "  Mozilla/5.0 " + "x".repeat(300), Duration.ZERO);
        var stored = MFA.listTrustedDevices(pid);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().label()).hasSize(250);
        assertThat(stored.getFirst().expiresAt()).as("ttl ≤ 0 → 30 d").isEqualTo(T0.plus(Duration.ofDays(30)));
        assertThat(stored.getFirst().lastUsedAt()).isNull();
        assertThat(DB.select(IAM_MFA_TRUSTED_DEVICES.TOKEN_HASH).from(IAM_MFA_TRUSTED_DEVICES)
                .where(IAM_MFA_TRUSTED_DEVICES.ID.eq(stored.getFirst().id())).fetchOne().value1())
                .as("only the hash is stored").isEqualTo(RecoveryCodes.sha256Hex(raw));

        assertThat(MFA.verifyTrustedDevice(pid, "")).isFalse();
        assertThat(MFA.verifyTrustedDevice(pid, raw + "x")).isFalse();
        assertThat(MFA.verifyTrustedDevice(principal(), raw)).as("owner-scoped").isFalse();
        CLOCK.set(T0.plusSeconds(60));
        assertThat(MFA.verifyTrustedDevice(pid, raw)).isTrue();
        assertThat(MFA.listTrustedDevices(pid).getFirst().lastUsedAt()).as("the verify stamps last_used_at").isEqualTo(T0.plusSeconds(60));
        CLOCK.set(T0.plus(Duration.ofDays(30)));
        assertThat(MFA.verifyTrustedDevice(pid, raw)).as("expires_at > now fails at the boundary").isFalse();
        CLOCK.set(T0);

        assertThat(MFA.revokeTrustedDevice(principal(), stored.getFirst().id())).as("another owner deletes nothing").isFalse();
        assertThat(MFA.revokeTrustedDevice(pid, stored.getFirst().id())).isTrue();
        assertThat(MFA.revokeTrustedDevice(pid, stored.getFirst().id())).as("ruling defect 10: nothing deleted").isFalse();
    }

    // ── reset and purge ────────────────────────────────────────────────────

    @Test
    void resetAllClearsEveryCredentialAndThePurgeSweepsOnlyExpiredRows() {
        String pid = principal();
        CLOCK.set(T0);
        var e = MFA.beginTotpEnrollment(pid, "z@example.com");
        MFA.confirmTotpEnrollment(pid, Totp.code(e.secret(), Totp.stepOf(T0)));
        MFA.generateRecoveryCodes(pid);
        MFA.sendLoginEmailPin(pid, "z@example.com");
        MFA.beginEmailEnrollment(pid, "z@example.com");
        MFA.issueTrustedDevice(pid, "d", Duration.ofDays(1));
        MFA.resetAll(pid);
        assertThat(MFA.confirmed(pid)).isEmpty();
        assertThat(MFA.methods(pid)).isEmpty();
        assertThat(MFA.remainingRecoveryCodes(pid)).isZero();
        assertThat(DB.fetchCount(IAM_MFA_EMAIL_PINS, IAM_MFA_EMAIL_PINS.PRINCIPAL_ID.eq(pid))).isZero();
        assertThat(MFA.countTrustedDevices(pid)).isZero();

        String other = principal();
        MFA.sendLoginEmailPin(other, "o@example.com");
        MFA.issueTrustedDevice(other, "live", Duration.ofDays(2));
        MFA.issueTrustedDevice(other, "stale", Duration.ofHours(1));
        assertThat(MFA.purgeExpired(T0.plus(Duration.ofHours(2)))).as("the 10-minute PIN and the 1-hour device are expired at +2 h; the 2-day device is not").isEqualTo(2);
        assertThat(MFA.listTrustedDevices(other)).extracting(TrustedDevice::label).containsExactly("live");
        assertThat(MFA.removeMethod(other, MfaMethod.TOTP)).as("nothing to remove").isFalse();
    }

    @Test
    void anAdminResetWipesEverythingAndAuditsTheAdministratorAsTheActor() {
        String pid = principal();
        var e = MFA.beginTotpEnrollment(pid, "a@example.com");
        MFA.confirmTotpEnrollment(pid, Totp.code(e.secret(), Totp.stepOf(CLOCK.get())));
        var audited = new Mfa(REPO, Optional.of(ENC), MAIL, () -> "X", Mfa.Config.DEFAULT, MOVABLE,
                new io.flowcatalyst.platform.audit.AuditLogRepository(DS));
        audited.resetAllByAdmin(pid, "prn_admin_" + RUN, "Ada Admin");
        assertThat(MFA.confirmed(pid)).isEmpty();
        var row = DB.selectFrom(io.flowcatalyst.db.generated.Tables.AUD_LOGS)
                .where(io.flowcatalyst.db.generated.Tables.AUD_LOGS.ENTITY_ID.eq(pid)).fetchOne();
        assertThat(row).as("the 2FA_RESET_BY_ADMIN row").isNotNull();
        assertThat(row.getOperation()).isEqualTo("2FA_RESET_BY_ADMIN");
        assertThat(row.getPrincipalId()).as("the administrator is the actor").isEqualTo("prn_admin_" + RUN);
        assertThat(row.getEntityType()).isEqualTo("PRINCIPAL");
        DB.deleteFrom(io.flowcatalyst.db.generated.Tables.AUD_LOGS).where(io.flowcatalyst.db.generated.Tables.AUD_LOGS.ENTITY_ID.eq(pid)).execute();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static String principal() {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.NAME, "MFA " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.EMAIL, "mfa-" + RUN + "-" + PRINCIPALS.size() + "@example.com")
                .set(IAM_PRINCIPALS.EMAIL_DOMAIN, "example.com")
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW)
                .execute();
        PRINCIPALS.add(id);
        return id;
    }

    /// The six digits between the bold `<p>` tags of the rendered mail.
    private static String pinIn(String sent) {
        int i = sent.indexOf("letter-spacing:3px\">") + "letter-spacing:3px\">".length();
        return sent.substring(i, i + 6);
    }
}
