package io.flowcatalyst.platform.auth.mfa.api;

import io.flowcatalyst.platform.audit.AuditLog;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.auth.login.AuthAlarms;
import io.flowcatalyst.platform.auth.login.BackoffCheck;
import io.flowcatalyst.platform.auth.login.ClientIp;
import io.flowcatalyst.platform.auth.login.LoginApi;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.mfa.DomainPolicy;
import io.flowcatalyst.platform.auth.mfa.Mfa;
import io.flowcatalyst.platform.auth.mfa.MfaToken;
import io.flowcatalyst.platform.auth.mfa.TrustedDevice;
import io.flowcatalyst.platform.auth.mfa.TrustedDeviceCookie;
import io.flowcatalyst.platform.auth.mfa.TwoFactorNotifier;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The `/auth/2fa/*` HTTP surface over the [Mfa] core
/// (`docs/spec/auth-identity.md` §6.3, §6.4, §6.6, §6.7 and the §0.5
/// rulings; Go `login/twofactor.go` + `twofactor_selfservice.go`): the six
/// token-gated routes (a pending or enrol [MfaToken] stands in for a
/// session) and the eight session-gated self-service routes. Every rule
/// beyond routing lives in [Mfa] / [DomainPolicy] — this class only decodes,
/// checks the gate, calls through, and writes the response.
///
/// Rulings applied here: **I-Q12** — [#verify] enforces the domain's
/// allowed-method list via [DomainPolicy#permits] (recovery codes are never
/// restricted); **I-Q11** — a device is only remembered when
/// [DomainPolicy#rememberEnabled()]; **I-Q21** — a trusted-device list item
/// carries no `principalId`; **defect 10** — the two DELETE routes answer
/// **404** (the platform's `HttpError.notFound` envelope), not 200, when
/// nothing was deleted.
public final class TwoFactorApi {

    private static final Logger LOG = LoggerFactory.getLogger(TwoFactorApi.class);

    private TwoFactorApi() {
    }

    /// @param login  the session surface's own state, shared rather than duplicated:
    ///               its `principals`, `attempts`, `backoff` and `clock` back this class too
    /// @param policy the domain's 2FA policy (§6.1)
    public record State(LoginApi.State login, Mfa mfa, DomainPolicy.Evaluator policy, MfaToken tokens,
                        TrustedDeviceCookie deviceCookie, AuditLogRepository auditLog, TwoFactorNotifier notifier) {
        public State {
            Objects.requireNonNull(login, "login");
            Objects.requireNonNull(mfa, "mfa");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(tokens, "tokens");
            Objects.requireNonNull(deviceCookie, "deviceCookie");
            Objects.requireNonNull(auditLog, "auditLog");
            Objects.requireNonNull(notifier, "notifier");
        }
    }

    public static void register(Routes routes, State s) {
        // ── public: gated by a pending / enrol MfaToken, not a session ──────
        routes.post("/auth/2fa/verify", ctx -> verify(ctx, s));
        routes.post("/auth/2fa/challenge/email", ctx -> challengeEmail(ctx, s));
        routes.post("/auth/2fa/enroll/totp/begin", ctx -> enrollTotpBegin(ctx, s));
        routes.post("/auth/2fa/enroll/totp/confirm", ctx -> enrollTotpConfirm(ctx, s));
        routes.post("/auth/2fa/enroll/email/begin", ctx -> enrollEmailBegin(ctx, s));
        routes.post("/auth/2fa/enroll/email/confirm", ctx -> enrollEmailConfirm(ctx, s));

        // ── self-service: session-gated (Profile screen) ────────────────────
        routes.get("/auth/2fa/status", Auth.scoped(ctx -> status(ctx, s)));
        routes.post("/auth/2fa/methods/totp/begin", Auth.scoped(ctx -> selfTotpBegin(ctx, s)));
        routes.post("/auth/2fa/methods/totp/confirm", Auth.scoped(ctx -> selfTotpConfirm(ctx, s)));
        routes.post("/auth/2fa/methods/email/begin", Auth.scoped(ctx -> selfEmailBegin(ctx, s)));
        routes.post("/auth/2fa/methods/email/confirm", Auth.scoped(ctx -> selfEmailConfirm(ctx, s)));
        routes.delete("/auth/2fa/methods/{method}", Auth.scoped(ctx -> removeMethod(ctx, s)));
        routes.post("/auth/2fa/recovery-codes/regenerate", Auth.scoped(ctx -> regenerateRecoveryCodes(ctx, s)));
        routes.get("/auth/2fa/trusted-devices", Auth.scoped(ctx -> listTrustedDevices(ctx, s)));
        routes.delete("/auth/2fa/trusted-devices/{id}", Auth.scoped(ctx -> revokeTrustedDevice(ctx, s)));
    }

    // ── verify ───────────────────────────────────────────────────────────

    record VerifyRequest(String mfaToken, String method, String code, boolean rememberDevice) {
    }

    private static void verify(Exchange ctx, State s) {
        var req = decode(ctx, VerifyRequest.class);
        Optional<Principal> op = principalFromToken(ctx, s, req.mfaToken(), MfaToken.Purpose.PENDING);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        String ip = ClientIp.of(ctx);
        String email = p.email() == null ? "" : p.email().toLowerCase(Locale.ROOT);

        if (s.login().backoff() != null) {
            BackoffCheck.Decision d;
            try {
                d = s.login().backoff().check(email, ip, s.login().clock().instant());
            } catch (RuntimeException e) {
                LOG.error("2FA verify backoff check failed; refusing", e);
                AuthAlarms.backoffStoreError();
                HttpError.writeLoginSurface(ctx, 503, "BACKOFF_UNAVAILABLE", "login is temporarily unavailable; try again shortly");
                return;
            }
            if (!d.allowed()) {
                ctx.header("Retry-After", Long.toString(d.retryAfterSecs()));
                HttpError.writeLoginSurface(ctx, 429, "TOO_MANY_REQUESTS", "too many failed login attempts; try again later");
                return;
            }
        }

        String method = req.method() == null ? "" : req.method().trim().toUpperCase(Locale.ROOT);
        if (!method.equals("TOTP") && !method.equals("EMAIL_PIN") && !method.equals("RECOVERY_CODE")) {
            HttpError.writeLoginSurface(ctx, 400, "INVALID_METHOD", "unknown 2FA method");
            return;
        }
        // Ruling I-Q12: verify enforces the domain's allowed-method list —
        // recovery codes are never restricted by it.
        if (!method.equals("RECOVERY_CODE")) {
            MfaMethod m = MfaMethod.valueOf(method);
            if (!s.policy().evaluate(p.email()).permits(m)) {
                HttpError.writeLoginSurface(ctx, 403, "METHOD_NOT_ALLOWED", methodNotAllowedMessage(m));
                return;
            }
        }

        boolean ok;
        try {
            ok = switch (method) {
                case "TOTP" -> s.mfa().verifyTotp(p.id(), req.code());
                case "EMAIL_PIN" -> s.mfa().verifyLoginEmailPin(p.id(), req.code());
                default -> s.mfa().verifyRecoveryCode(p.id(), req.code());
            };
        } catch (RuntimeException e) {
            LOG.atError().setMessage("2FA verify failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "VERIFY_FAILED", "could not verify code");
            return;
        }
        if (!ok) {
            recordAttempt(s, AttemptOutcome.FAILURE, email, p.id(), ip, "Invalid 2FA code");
            unauthorized(ctx, "Invalid or expired code");
            return;
        }

        if (method.equals("RECOVERY_CODE")) {
            s.notifier().recoveryCodeUsed(p.email());
        }
        if (req.rememberDevice()) {
            rememberDevice(ctx, s, p);
        }
        LoginApi.completeLogin(ctx, s.login(), p, ip, null);
    }

    private static String methodNotAllowedMessage(MfaMethod m) {
        return m == MfaMethod.TOTP ? "authenticator app is not permitted for this domain"
                : "email codes are not permitted for this domain";
    }

    /// Only when the domain allows it (ruling I-Q11): mint, set the cookie,
    /// notify. A store failure is logged and the login still completes —
    /// remembering the device is a convenience, not a login precondition.
    private static void rememberDevice(Exchange ctx, State s, Principal p) {
        DomainPolicy dp = s.policy().evaluate(p.email());
        if (!dp.rememberEnabled()) {
            return;
        }
        String label = userAgentLabel(ctx);
        Duration ttl = Duration.ofDays(dp.rememberDays());
        String raw;
        try {
            raw = s.mfa().issueTrustedDevice(p.id(), label, ttl);
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("issue trusted device failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            return;
        }
        s.deviceCookie().set(ctx, raw, ttl);
        s.notifier().newTrustedDevice(p.email(), label);
    }

    private static String userAgentLabel(Exchange ctx) {
        String ua = ctx.header("User-Agent");
        if (ua == null) {
            return null;
        }
        ua = ua.trim();
        if (ua.isEmpty()) {
            return null;
        }
        return ua.length() > 250 ? ua.substring(0, 250) : ua;
    }

    // ── challenge / email ────────────────────────────────────────────────

    record ChallengeEmailRequest(String mfaToken) {
    }

    private static void challengeEmail(Exchange ctx, State s) {
        var req = decode(ctx, ChallengeEmailRequest.class);
        Optional<Principal> op = principalFromToken(ctx, s, req.mfaToken(), MfaToken.Purpose.PENDING);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        String email = p.email();
        if (email == null || email.isBlank()) {
            HttpError.writeLoginSurface(ctx, 400, "NO_EMAIL", "account has no email");
            return;
        }
        try {
            s.mfa().sendLoginEmailPin(p.id(), email);
        } catch (RuntimeException e) {
            LOG.atError().setMessage("send login email pin failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 502, "EMAIL_SEND_FAILED", "could not send code");
            return;
        }
        ctx.json(Map.of("message", "A verification code has been sent to your email."));
    }

    // ── enrolment (token-gated) ──────────────────────────────────────────

    record EnrollBeginRequest(String enrollToken) {
    }

    record EnrollConfirmRequest(String enrollToken, String code) {
    }

    record TotpEnrollResponse(String secret, String uri, String qr) {
    }

    private static void enrollTotpBegin(Exchange ctx, State s) {
        var req = decode(ctx, EnrollBeginRequest.class);
        Optional<Principal> op = principalFromToken(ctx, s, req.enrollToken(), MfaToken.Purpose.ENROLL);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        if (!s.policy().evaluate(p.email()).permits(MfaMethod.TOTP)) {
            HttpError.writeLoginSurface(ctx, 403, "METHOD_NOT_ALLOWED", methodNotAllowedMessage(MfaMethod.TOTP));
            return;
        }
        Mfa.TotpEnrollment enr;
        try {
            enr = s.mfa().beginTotpEnrollment(p.id(), p.email());
        } catch (RuntimeException e) {
            writeEnrollErr(ctx, e);
            return;
        }
        ctx.json(new TotpEnrollResponse(enr.secret(), enr.uri(), enr.qr().orElse(null)));
    }

    private static void enrollTotpConfirm(Exchange ctx, State s) {
        var req = decode(ctx, EnrollConfirmRequest.class);
        Optional<Principal> op = principalFromToken(ctx, s, req.enrollToken(), MfaToken.Purpose.ENROLL);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        boolean ok;
        try {
            ok = s.mfa().confirmTotpEnrollment(p.id(), req.code());
        } catch (RuntimeException e) {
            writeEnrollErr(ctx, e);
            return;
        }
        if (!ok) {
            HttpError.writeLoginSurface(ctx, 400, "INVALID_CODE", "that code didn't match — try again");
            return;
        }
        s.notifier().twoFactorEnrolled(p.email(), MfaMethod.TOTP);
        audit(s, p, "2FA_TOTP_ENROLLED");
        LoginApi.completeLogin(ctx, s.login(), p, ClientIp.of(ctx), ensureRecoveryCodes(s, p));
    }

    private static void enrollEmailBegin(Exchange ctx, State s) {
        var req = decode(ctx, EnrollBeginRequest.class);
        Optional<Principal> op = principalFromToken(ctx, s, req.enrollToken(), MfaToken.Purpose.ENROLL);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        if (!s.policy().evaluate(p.email()).permits(MfaMethod.EMAIL_PIN)) {
            HttpError.writeLoginSurface(ctx, 403, "METHOD_NOT_ALLOWED", methodNotAllowedMessage(MfaMethod.EMAIL_PIN));
            return;
        }
        String email = p.email();
        if (email == null || email.isBlank()) {
            HttpError.writeLoginSurface(ctx, 400, "NO_EMAIL", "account has no email");
            return;
        }
        try {
            s.mfa().beginEmailEnrollment(p.id(), email);
        } catch (RuntimeException e) {
            writeEnrollErr(ctx, e);
            return;
        }
        ctx.json(Map.of("message", "A verification code has been sent to your email."));
    }

    private static void enrollEmailConfirm(Exchange ctx, State s) {
        var req = decode(ctx, EnrollConfirmRequest.class);
        Optional<Principal> op = principalFromToken(ctx, s, req.enrollToken(), MfaToken.Purpose.ENROLL);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        boolean ok;
        try {
            ok = s.mfa().confirmEmailEnrollment(p.id(), req.code());
        } catch (RuntimeException e) {
            writeEnrollErr(ctx, e);
            return;
        }
        if (!ok) {
            HttpError.writeLoginSurface(ctx, 400, "INVALID_CODE", "that code didn't match — try again");
            return;
        }
        s.notifier().twoFactorEnrolled(p.email(), MfaMethod.EMAIL_PIN);
        audit(s, p, "2FA_EMAIL_ENROLLED");
        // §6.6: recovery codes are omitted here unless TOTP is also confirmed —
        // ensureRecoveryCodes (the Mfa core) enforces that on its own.
        LoginApi.completeLogin(ctx, s.login(), p, ClientIp.of(ctx), ensureRecoveryCodes(s, p));
    }

    /// Go's sentinel-error mapping (`writeEnrollErr`), so both the
    /// token-gated and self-service enrolment routes answer the same way.
    private static void writeEnrollErr(Exchange ctx, RuntimeException e) {
        switch (e) {
            case Mfa.AlreadyEnrolled ignored ->
                    HttpError.writeLoginSurface(ctx, 409, "ALREADY_ENROLLED", "that method is already set up");
            case Mfa.EncryptionUnavailable ignored ->
                    HttpError.writeLoginSurface(ctx, 503, "TOTP_UNAVAILABLE", "authenticator-app 2FA is not available");
            case Mfa.NoPendingEnrollment ignored ->
                    HttpError.writeLoginSurface(ctx, 400, "NO_PENDING_ENROLLMENT", "start enrollment first");
            default -> {
                LOG.error("2FA enrolment error", e);
                HttpError.writeLoginSurface(ctx, 500, "ENROLL_FAILED", "could not complete enrollment");
            }
        }
    }

    /// The first recovery-code set (§6.6), generated only once TOTP is
    /// confirmed and none remain — [Mfa#ensureRecoveryCodes] enforces that.
    /// Notifies on generation; a generation failure is logged and answers
    /// as "none generated" rather than failing the enrolment that already
    /// succeeded.
    private static List<String> ensureRecoveryCodes(State s, Principal p) {
        List<String> codes;
        try {
            codes = s.mfa().ensureRecoveryCodes(p.id());
        } catch (RuntimeException e) {
            LOG.atError().setMessage("recovery code generation failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            return List.of();
        }
        if (!codes.isEmpty()) {
            s.notifier().recoveryCodesRegenerated(p.email());
        }
        return codes;
    }

    // ── self-service ─────────────────────────────────────────────────────

    record StatusResponse(List<String> methods, boolean required, List<String> allowedMethods, int recoveryCodesLeft,
                          boolean rememberDeviceEnabled, int trustedDeviceCount) {
    }

    private static void status(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        List<MfaMethod> confirmed;
        try {
            confirmed = s.mfa().confirmed(p.id());
        } catch (RuntimeException e) {
            LOG.atError().setMessage("2FA status load failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "STATUS_FAILED", "could not load 2FA status");
            return;
        }
        DomainPolicy dp = s.policy().evaluate(p.email());
        int left = safely(() -> s.mfa().remainingRecoveryCodes(p.id()), "remaining recovery codes", p.id());
        int devices = safely(() -> s.mfa().countTrustedDevices(p.id()), "trusted device count", p.id());
        ctx.json(new StatusResponse(confirmed.stream().map(MfaMethod::name).toList(), dp.requires2fa(),
                dp.permittedMethods().stream().map(MfaMethod::name).toList(), left, dp.rememberEnabled(), devices));
    }

    private static int safely(java.util.function.IntSupplier sup, String what, String principalId) {
        try {
            return sup.getAsInt();
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("operation failed")
                    .addKeyValue("operation", what)
                    .addKeyValue("principal", principalId)
                    .setCause(e)
                    .log();
            return 0;
        }
    }

    record CodeRequest(String code) {
    }

    private static void selfTotpBegin(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        if (!s.policy().evaluate(p.email()).permits(MfaMethod.TOTP)) {
            HttpError.writeLoginSurface(ctx, 403, "METHOD_NOT_ALLOWED", methodNotAllowedMessage(MfaMethod.TOTP));
            return;
        }
        Mfa.TotpEnrollment enr;
        try {
            enr = s.mfa().beginTotpEnrollment(p.id(), p.email());
        } catch (RuntimeException e) {
            writeEnrollErr(ctx, e);
            return;
        }
        ctx.json(new TotpEnrollResponse(enr.secret(), enr.uri(), enr.qr().orElse(null)));
    }

    private static void selfTotpConfirm(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        var req = decode(ctx, CodeRequest.class);
        boolean ok;
        try {
            ok = s.mfa().confirmTotpEnrollment(p.id(), req.code());
        } catch (RuntimeException e) {
            writeEnrollErr(ctx, e);
            return;
        }
        if (!ok) {
            HttpError.writeLoginSurface(ctx, 400, "INVALID_CODE", "that code didn't match — try again");
            return;
        }
        s.notifier().twoFactorEnrolled(p.email(), MfaMethod.TOTP);
        audit(s, p, "2FA_TOTP_ENROLLED");
        ctx.json(Map.of("recoveryCodes", ensureRecoveryCodes(s, p)));
    }

    private static void selfEmailBegin(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        if (!s.policy().evaluate(p.email()).permits(MfaMethod.EMAIL_PIN)) {
            HttpError.writeLoginSurface(ctx, 403, "METHOD_NOT_ALLOWED", methodNotAllowedMessage(MfaMethod.EMAIL_PIN));
            return;
        }
        String email = p.email();
        if (email == null || email.isBlank()) {
            HttpError.writeLoginSurface(ctx, 400, "NO_EMAIL", "account has no email");
            return;
        }
        try {
            s.mfa().beginEmailEnrollment(p.id(), email);
        } catch (RuntimeException e) {
            writeEnrollErr(ctx, e);
            return;
        }
        ctx.json(Map.of("message", "A verification code has been sent to your email."));
    }

    private static void selfEmailConfirm(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        var req = decode(ctx, CodeRequest.class);
        boolean ok;
        try {
            ok = s.mfa().confirmEmailEnrollment(p.id(), req.code());
        } catch (RuntimeException e) {
            writeEnrollErr(ctx, e);
            return;
        }
        if (!ok) {
            HttpError.writeLoginSurface(ctx, 400, "INVALID_CODE", "that code didn't match — try again");
            return;
        }
        s.notifier().twoFactorEnrolled(p.email(), MfaMethod.EMAIL_PIN);
        audit(s, p, "2FA_EMAIL_ENROLLED");
        ctx.json(Map.of("recoveryCodes", ensureRecoveryCodes(s, p)));
    }

    private static void removeMethod(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        MfaMethod method;
        try {
            String raw = ctx.pathParam("method");
            method = MfaMethod.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            HttpError.writeLoginSurface(ctx, 400, "INVALID_METHOD", "unknown 2FA method");
            return;
        }
        List<MfaMethod> confirmed;
        try {
            confirmed = s.mfa().confirmed(p.id());
        } catch (RuntimeException e) {
            LOG.atError().setMessage("2FA remove-method load failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "REMOVE_FAILED", "could not load methods");
            return;
        }
        // Policy guard: a 2FA-required user can't drop their last confirmed
        // factor — mirrors Go's lastConfirmedFactor, checked BEFORE the
        // delete so a required domain never observes a momentary zero-factor
        // state (mutant 4: dropping this check must fail its own test).
        if (s.policy().evaluate(p.email()).requires2fa() && lastConfirmedFactor(confirmed, method)) {
            HttpError.writeLoginSurface(ctx, 409, "LAST_FACTOR",
                    "your organisation requires 2FA — add another method before removing this one");
            return;
        }
        boolean removed;
        try {
            removed = s.mfa().removeMethod(p.id(), method);
        } catch (RuntimeException e) {
            LOG.atError().setMessage("2FA remove-method failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "REMOVE_FAILED", "could not remove method");
            return;
        }
        if (!removed) {
            // Defect 10: 404, not Go's unconditional 200, when nothing was deleted.
            throw HttpError.notFound("TwoFactorMethod", method.name());
        }
        s.notifier().twoFactorMethodRemoved(p.email(), method);
        audit(s, p, "2FA_METHOD_REMOVED");
        ctx.json(Map.of("message", "Two-factor method removed."));
    }

    /// Exactly one confirmed factor, and it is the one being removed. A
    /// user with none has nothing to protect — that request falls through
    /// to the 404 of ruling defect 10.
    private static boolean lastConfirmedFactor(List<MfaMethod> confirmed, MfaMethod method) {
        return confirmed.size() == 1 && confirmed.getFirst() == method;
    }

    private static void regenerateRecoveryCodes(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        List<MfaMethod> confirmed;
        try {
            confirmed = s.mfa().confirmed(p.id());
        } catch (RuntimeException e) {
            LOG.atError().setMessage("2FA recovery-code regen: load methods failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "REGEN_FAILED", "could not load methods");
            return;
        }
        if (!confirmed.contains(MfaMethod.TOTP)) {
            HttpError.writeLoginSurface(ctx, 400, "NO_TOTP", "recovery codes apply to authenticator-app 2FA");
            return;
        }
        List<String> codes;
        try {
            codes = s.mfa().generateRecoveryCodes(p.id());
        } catch (RuntimeException e) {
            LOG.atError().setMessage("2FA recovery-code regen failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "REGEN_FAILED", "could not generate recovery codes");
            return;
        }
        s.notifier().recoveryCodesRegenerated(p.email());
        audit(s, p, "2FA_RECOVERY_REGENERATED");
        ctx.json(Map.of("recoveryCodes", codes));
    }

    // ── trusted devices ──────────────────────────────────────────────────

    /// Ruling I-Q21: the platform time shape, no `principalId`.
    record TrustedDeviceView(String id, String label, Instant expiresAt, Instant createdAt, Instant lastUsedAt) {
        static TrustedDeviceView of(TrustedDevice d) {
            return new TrustedDeviceView(d.id(), d.label(), d.expiresAt(), d.createdAt(), d.lastUsedAt());
        }
    }

    private static void listTrustedDevices(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        List<TrustedDevice> devices;
        try {
            devices = s.mfa().listTrustedDevices(p.id());
        } catch (RuntimeException e) {
            LOG.atError().setMessage("2FA trusted-device list failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "LIST_FAILED", "could not list devices");
            return;
        }
        ctx.json(Map.of("devices", devices.stream().map(TrustedDeviceView::of).toList()));
    }

    private static void revokeTrustedDevice(Exchange ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        String id = ctx.pathParam("id");
        boolean revoked;
        try {
            revoked = s.mfa().revokeTrustedDevice(p.id(), id);
        } catch (RuntimeException e) {
            LOG.atError().setMessage("2FA trusted-device revoke failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "REVOKE_FAILED", "could not revoke device");
            return;
        }
        if (!revoked) {
            // Defect 10: 404, not Go's unconditional 200, when nothing matched.
            throw HttpError.notFound("TrustedDevice", id);
        }
        ctx.json(Map.of("message", "Device removed."));
    }

    // ── shared helpers ───────────────────────────────────────────────────

    /// Parses `token` for `want`, loads the active principal; any failure
    /// writes the 401 (§6.3) and returns empty.
    private static Optional<Principal> principalFromToken(Exchange ctx, State s, String token, MfaToken.Purpose want) {
        Optional<MfaToken.Claims> claims = s.tokens().parse(token, want);
        if (claims.isEmpty()) {
            unauthorized(ctx, "Invalid or expired session");
            return Optional.empty();
        }
        Optional<Principal> p = s.login().principals().findById(claims.get().subject());
        if (p.isEmpty() || !p.get().active()) {
            unauthorized(ctx, "Invalid or expired session");
            return Optional.empty();
        }
        return p;
    }

    /// Loads the session's active principal; any failure writes 401 and
    /// returns empty — like `LoginApi#me`.
    private static Optional<Principal> principalFromSession(Exchange ctx, State s) {
        Optional<AuthContext> ac = Auth.currentOptional();
        if (ac.isEmpty() || ac.get().principalId().isBlank()) {
            unauthorized(ctx, "Not authenticated");
            return Optional.empty();
        }
        Optional<Principal> p = s.login().principals().findById(ac.get().principalId());
        if (p.isEmpty() || !p.get().active()) {
            unauthorized(ctx, "Not authenticated");
            return Optional.empty();
        }
        return p;
    }

    private static void unauthorized(Exchange ctx, String message) {
        ctx.header("WWW-Authenticate", "Cookie realm=\"" + SessionCookie.NAME + "\"");
        HttpError.writeLoginSurface(ctx, 401, "UNAUTHENTICATED", message);
    }

    private static void recordAttempt(State s, AttemptOutcome outcome, String identifier, String principalId, String ip, String reason) {
        if (s.login().attempts() == null) {
            return;
        }
        try {
            s.login().attempts().recordAttempt(LoginAttempt.attempt(AttemptType.USER_LOGIN, outcome, reason, identifier,
                    principalId, ip == null || ip.isBlank() ? null : ip, null));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("recording 2FA attempt failed")
                    .addKeyValue("identifier", identifier)
                    .addKeyValue("outcome", outcome)
                    .setCause(e)
                    .log();
        }
    }

    private static void audit(State s, Principal p, String operation) {
        try {
            s.auditLog().insertBatch(List.of(new AuditLog(EntityType.AUDIT_LOG.generate(), "PRINCIPAL", p.id(), operation,
                    null, p.id(), p.name(), null, null, s.login().clock().instant())));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("2FA audit insert failed")
                    .addKeyValue("op", operation)
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
        }
    }

    private static <T> T decode(Exchange ctx, Class<T> type) {
        try {
            return Json.MAPPER.readValue(ctx.body(), type);
        } catch (JacksonException e) {
            throw HttpError.loginSurfaceInvalidJson(e.getOriginalMessage() == null ? "malformed request body" : e.getOriginalMessage());
        }
    }
}
