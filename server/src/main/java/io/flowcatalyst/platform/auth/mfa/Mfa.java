package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.platform.shared.SecureTokens;
import io.flowcatalyst.platform.audit.AuditLog;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.platform.principal.MfaService;
import io.flowcatalyst.platform.shared.encryption.Decryption;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/// The second-factor service (`docs/spec/auth-identity.md` §6.5, §11.4–11.7;
/// Go `mfa.Service`): TOTP enrolment and verification with the replay
/// guard, e-mail PINs with their attempt budget, single-use recovery codes,
/// trusted devices, and the reset that clears them all. Every rule the
/// spec calls load-bearing is a method here, so the HTTP layer only routes.
///
/// TOTP secrets are encrypted at rest under the app key; without an
/// encryption service every TOTP path fails with [EncryptionUnavailable]
/// rather than storing or reading plaintext.
public final class Mfa implements MfaService {

    private static final Logger LOG = LoggerFactory.getLogger(Mfa.class);

    /// Go's defaults (`mfa/service.go:48-57`).
    public record Config(String issuer, int pinDigits, Duration pinTtl, int pinMaxAttempts, int recoveryCodeCount,
                         Duration trustedDeviceTtl) {
        public static final Config DEFAULT = new Config("FlowCatalyst", 6, Duration.ofMinutes(10), 5, 10, Duration.ofDays(30));

        public Config withIssuer(String newIssuer) {
            return new Config(newIssuer, pinDigits, pinTtl, pinMaxAttempts, recoveryCodeCount, trustedDeviceTtl);
        }
    }

    /// What `begin TOTP` hands the user: the base32 secret, the
    /// `otpauth://` URI and, best-effort, the QR as a data URI.
    public record TotpEnrollment(String secret, String uri, Optional<String> qr) {
    }

    public static final class AlreadyEnrolled extends RuntimeException {
        public AlreadyEnrolled(MfaMethod m) {
            super("mfa: " + m + " already enrolled");
        }
    }

    public static final class NoPendingEnrollment extends RuntimeException {
        public NoPendingEnrollment(MfaMethod m) {
            super("mfa: no pending " + m + " enrolment");
        }
    }

    public static final class EncryptionUnavailable extends RuntimeException {
        public EncryptionUnavailable() {
            super("mfa: encryption not configured (set FLOWCATALYST_APP_KEY)");
        }
    }

    private final MfaRepository repo;
    private final Optional<Encryption> encryption;
    private final MailSender mail;
    private final Supplier<String> issuerName;
    private final Config config;
    private final Clock clock;
    private final AuditLogRepository audit;

    public Mfa(MfaRepository repo, Optional<Encryption> encryption, MailSender mail, Config config, Clock clock) {
        this(repo, encryption, mail, config::issuer, config, clock, null);
    }

    /// @param issuerName the live platform name the TOTP label carries (overrides `config.issuer`)
    public Mfa(MfaRepository repo, Optional<Encryption> encryption, MailSender mail, Supplier<String> issuerName,
               Config config, Clock clock) {
        this(repo, encryption, mail, issuerName, config, clock, null);
    }

    /// @param audit where the admin reset's `2FA_RESET_BY_ADMIN` row goes; nullable ⇒ not written (logged)
    public Mfa(MfaRepository repo, Optional<Encryption> encryption, MailSender mail, Supplier<String> issuerName,
               Config config, Clock clock, AuditLogRepository audit) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
        this.mail = Objects.requireNonNull(mail, "mail");
        this.issuerName = Objects.requireNonNull(issuerName, "issuerName");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = audit;
    }

    public Config config() {
        return config;
    }

    // ── principal.MfaService (the admin surface) ───────────────────────────

    @Override
    public List<String> confirmedMethods(String principalId) {
        return repo.confirmedMethods(principalId).stream().map(MfaMethod::name).toList();
    }

    public List<MfaMethod> confirmed(String principalId) {
        return repo.confirmedMethods(principalId);
    }

    /// Deletes methods, recovery codes, PINs of both purposes and trusted
    /// devices — the admin reset and the ruling I-Q14 option.
    @Override
    public void resetAll(String principalId) {
        for (MfaMethod m : MfaMethod.values()) {
            repo.deleteMethod(principalId, m);
        }
        repo.deleteRecoveryCodes(principalId);
        repo.deletePins(principalId);
        repo.revokeAllTrustedDevices(principalId);
    }

    /// §6.9: the same wipe, then the audit row naming the administrator.
    @Override
    public void resetAllByAdmin(String principalId, String adminId, String adminName) {
        resetAll(principalId);
        if (audit == null) {
            LOG.atWarn().setMessage("2FA_RESET_BY_ADMIN not audited: no audit repository wired")
                    .addKeyValue("principal", principalId)
                    .addKeyValue("admin_id", adminId)
                    .log();
            return;
        }
        try {
            audit.insertBatch(List.of(new AuditLog(EntityType.AUDIT_LOG.generate(), "PRINCIPAL", principalId, "2FA_RESET_BY_ADMIN",
                    null, adminId, adminName, null, null, clock.instant())));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("2FA_RESET_BY_ADMIN audit insert failed")
                    .addKeyValue("principal", principalId)
                    .addKeyValue("admin_id", adminId)
                    .setCause(e)
                    .log();
        }
    }

    @Override
    public boolean configured() {
        return true;
    }

    // ── TOTP ───────────────────────────────────────────────────────────────

    /// A confirmed TOTP → [AlreadyEnrolled]; a pending row is replaced with
    /// a new secret.
    public TotpEnrollment beginTotpEnrollment(String principalId, String account) {
        Encryption enc = encryption.orElseThrow(EncryptionUnavailable::new);
        Optional<MfaMethodRow> existing = repo.findMethod(principalId, MfaMethod.TOTP);
        if (existing.isPresent() && existing.get().confirmed()) {
            throw new AlreadyEnrolled(MfaMethod.TOTP);
        }
        existing.ifPresent(_ -> repo.deleteMethod(principalId, MfaMethod.TOTP));
        String secret = Totp.generateSecret();
        String issuer = issuerName.get();
        String uri = Totp.uri(issuer, account, secret);
        repo.insertPending(principalId, MfaMethod.TOTP, enc.encrypt(secret));
        return new TotpEnrollment(secret, uri, QrPng.dataUri(uri));
    }

    /// The first code confirms the row and seeds the replay guard.
    public boolean confirmTotpEnrollment(String principalId, String code) {
        MfaMethodRow row = repo.findMethod(principalId, MfaMethod.TOTP).orElseThrow(() -> new NoPendingEnrollment(MfaMethod.TOTP));
        if (row.secretEncrypted() == null) {
            throw new NoPendingEnrollment(MfaMethod.TOTP);
        }
        if (row.confirmed()) {
            throw new AlreadyEnrolled(MfaMethod.TOTP);
        }
        OptionalLong step = Totp.validate(secret(row), code, clock.instant());
        if (step.isEmpty()) {
            return false;
        }
        return repo.confirm(principalId, MfaMethod.TOTP, Totp.timeForStep(step.getAsLong()));
    }

    /// Not enrolled, unconfirmed, wrong, or a step at or before the last
    /// accepted one → false; a match advances the guard (§6.5, the
    /// enrolment code cannot be replayed at login).
    public boolean verifyTotp(String principalId, String code) {
        Optional<MfaMethodRow> found = repo.findMethod(principalId, MfaMethod.TOTP);
        if (found.isEmpty() || !found.get().confirmed() || found.get().secretEncrypted() == null) {
            return false;
        }
        MfaMethodRow row = found.get();
        OptionalLong step = Totp.validate(secret(row), code, clock.instant());
        if (step.isEmpty()) {
            return false;
        }
        if (row.lastUsedAt() != null && step.getAsLong() <= Totp.stepOf(row.lastUsedAt())) {
            return false; // replay
        }
        // The guarded update is the second half of the guard: two
        // concurrent presentations of one code cannot both advance it.
        return repo.advanceLastUsed(principalId, MfaMethod.TOTP, Totp.timeForStep(step.getAsLong()));
    }

    private String secret(MfaMethodRow row) {
        Encryption enc = encryption.orElseThrow(EncryptionUnavailable::new);
        return switch (enc.decrypt(row.secretEncrypted())) {
            case Decryption.Plaintext p -> p.value();
            case Decryption.External _ -> throw UseCaseException.internal("MFA_SECRET", "TOTP secret is an external reference", null);
            case Decryption.Failed f -> throw UseCaseException.internal("MFA_SECRET", "TOTP secret cannot be decrypted: " + f.reason(), null);
        };
    }

    // ── e-mail PIN ─────────────────────────────────────────────────────────

    public void beginEmailEnrollment(String principalId, String email) {
        Optional<MfaMethodRow> existing = repo.findMethod(principalId, MfaMethod.EMAIL_PIN);
        if (existing.isPresent() && existing.get().confirmed()) {
            throw new AlreadyEnrolled(MfaMethod.EMAIL_PIN);
        }
        if (existing.isEmpty()) {
            repo.insertPending(principalId, MfaMethod.EMAIL_PIN, null);
        }
        sendPin(principalId, email, EmailPin.ENROLL);
    }

    public boolean confirmEmailEnrollment(String principalId, String code) {
        if (!verifyPin(principalId, EmailPin.ENROLL, code)) {
            return false;
        }
        if (!repo.confirm(principalId, MfaMethod.EMAIL_PIN, null)) {
            throw new NoPendingEnrollment(MfaMethod.EMAIL_PIN);
        }
        return true;
    }

    public void sendLoginEmailPin(String principalId, String email) {
        sendPin(principalId, email, EmailPin.LOGIN);
    }

    public boolean verifyLoginEmailPin(String principalId, String code) {
        return verifyPin(principalId, EmailPin.LOGIN, code);
    }

    /// Issues (replacing any outstanding PIN of the purpose) and sends; a
    /// send failure propagates — the user needs the PIN.
    void sendPin(String principalId, String email, String purpose) {
        String pin = RecoveryCodes.randomDigits(config.pinDigits());
        Instant now = clock.instant();
        repo.issuePin(principalId, purpose, RecoveryCodes.sha256Hex(pin), now.plus(config.pinTtl()));
        mail.send(email, "Your verification code", renderEmailPin(pin, config.pinTtl()));
    }

    /// The latest PIN of the purpose: none → false; expired or out of
    /// attempts → deleted, false; match → deleted, true; else attempts+1
    /// (deleted when the new count reaches the budget), false.
    boolean verifyPin(String principalId, String purpose, String code) {
        Optional<EmailPin> latest = repo.latestPin(principalId, purpose);
        if (latest.isEmpty()) {
            return false;
        }
        EmailPin pin = latest.get();
        if (pin.isExpired(clock.instant()) || pin.attempts() >= config.pinMaxAttempts()) {
            repo.deletePin(pin.id());
            return false;
        }
        String provided = code == null ? "" : code.trim();
        if (RecoveryCodes.constantTimeEquals(RecoveryCodes.sha256Hex(provided), pin.pinHash())) {
            repo.deletePin(pin.id());
            return true;
        }
        int attempts = repo.countPinAttempt(pin.id());
        if (attempts >= config.pinMaxAttempts()) {
            repo.deletePin(pin.id());
        }
        return false;
    }

    static String renderEmailPin(String pin, Duration ttl) {
        return "<p>Your verification code is:</p>"
                + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:3px\">" + pin + "</p>"
                + "<p>This code expires in " + ttl.toMinutes() + " minutes.</p>"
                + "<p>If you did not try to sign in, you can ignore this email.</p>";
    }

    // ── recovery codes ─────────────────────────────────────────────────────

    /// A fresh set replaces the whole old one; the plaintext codes are shown
    /// once and never stored.
    public List<String> generateRecoveryCodes(String principalId) {
        List<String> codes = RecoveryCodes.generate(config.recoveryCodeCount());
        repo.replaceRecoveryCodes(principalId, codes.stream().map(RecoveryCodes::hash).toList());
        return codes;
    }

    public boolean verifyRecoveryCode(String principalId, String code) {
        String normalised = RecoveryCodes.normalize(code);
        if (normalised.isEmpty()) {
            return false;
        }
        return repo.consumeRecoveryCode(principalId, RecoveryCodes.sha256Hex(normalised));
    }

    public int remainingRecoveryCodes(String principalId) {
        return repo.remainingRecoveryCodes(principalId);
    }

    /// The first set exists only once TOTP is confirmed and none remain
    /// (§6.5 `ensureRecoveryCodes`); empty when nothing was generated.
    public List<String> ensureRecoveryCodes(String principalId) {
        Optional<MfaMethodRow> totp = repo.findMethod(principalId, MfaMethod.TOTP);
        if (totp.isEmpty() || !totp.get().confirmed()) {
            return List.of();
        }
        if (repo.remainingRecoveryCodes(principalId) > 0) {
            return List.of();
        }
        return generateRecoveryCodes(principalId);
    }

    // ── methods ────────────────────────────────────────────────────────────

    public List<MfaMethodRow> methods(String principalId) {
        return repo.methods(principalId);
    }

    /// True when a row existed (ruling: 404 when nothing was deleted).
    public boolean removeMethod(String principalId, MfaMethod method) {
        return repo.deleteMethod(principalId, method);
    }

    // ── trusted devices ────────────────────────────────────────────────────

    /// The raw cookie value (32 random bytes, base64url); only its hash is stored.
    public String issueTrustedDevice(String principalId, String label, Duration ttl) {
        Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? config.trustedDeviceTtl() : ttl;
        String token = SecureTokens.urlSafe(32);
        String stored = label == null || label.isBlank() ? null : label.trim().length() > 250 ? label.trim().substring(0, 250) : label.trim();
        repo.insertTrustedDevice(principalId, RecoveryCodes.sha256Hex(token), stored, clock.instant().plus(effective));
        return token;
    }

    public boolean verifyTrustedDevice(String principalId, String rawToken) {
        if (rawToken == null || rawToken.isEmpty()) {
            return false;
        }
        return repo.touchTrustedDevice(principalId, RecoveryCodes.sha256Hex(rawToken));
    }

    public List<TrustedDevice> listTrustedDevices(String principalId) {
        return repo.trustedDevices(principalId);
    }

    public int countTrustedDevices(String principalId) {
        return repo.countTrustedDevices(principalId);
    }

    public boolean revokeTrustedDevice(String principalId, String id) {
        return repo.revokeTrustedDevice(principalId, id);
    }

    public int revokeAllTrustedDevices(String principalId) {
        return repo.revokeAllTrustedDevices(principalId);
    }

    // ── the purger's sweeps (ruling I-Q17) ─────────────────────────────────

    public int purgeExpired(Instant before) {
        int pins = repo.deleteExpiredPins(before);
        int devices = repo.deleteExpiredTrustedDevices(before);
        if (pins + devices > 0) {
            LOG.atInfo().setMessage("mfa purge completed")
                    .addKeyValue("expired_pins", pins)
                    .addKeyValue("expired_trusted_devices", devices)
                    .log();
        }
        return pins + devices;
    }
}
