package io.flowcatalyst.platform.auth.mfa.api;

import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.login.LoginApi;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.mfa.Mfa;
import io.flowcatalyst.platform.auth.mfa.TrustedDeviceCookie;
import io.flowcatalyst.platform.auth.mfa.TwoFactorNotifier;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.principal.PasswordPolicy;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// `/auth/change-password` and its e-mail-code helper
/// (`docs/spec/auth-identity.md` §6.8; Go `login/change_password.go`):
/// session-gated, lets an internal user change their own password, gated by
/// whichever second factor they have confirmed. Federated / passwordless
/// accounts have no password to change here.
///
/// Post-change hygiene mirrors a password reset: every trusted device is
/// revoked, the browser's own cookie is cleared, and every refresh token is
/// revoked (best-effort — the password is already changed by that point, so
/// a hygiene failure is logged, not surfaced).
public final class ChangePasswordApi {

    private static final Logger LOG = LoggerFactory.getLogger(ChangePasswordApi.class);

    private ChangePasswordApi() {
    }

    public record State(LoginApi.State login, Mfa mfa, TrustedDeviceCookie deviceCookie, GrantStore grantStore,
                        TwoFactorNotifier notifier) {
        public State {
            Objects.requireNonNull(login, "login");
            Objects.requireNonNull(mfa, "mfa");
            Objects.requireNonNull(deviceCookie, "deviceCookie");
            Objects.requireNonNull(grantStore, "grantStore");
            Objects.requireNonNull(notifier, "notifier");
        }
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.post("/auth/change-password", Auth.scoped(ctx -> changePassword(ctx, s)));
        routes.post("/auth/change-password/send-email-code", Auth.scoped(ctx -> sendEmailCode(ctx, s)));
    }

    // ── /auth/change-password ────────────────────────────────────────────

    record ChangePasswordRequest(String currentPassword, String newPassword, String code) {
    }

    /// The bespoke `{"code":"MFA_REQUIRED","message":...,"methods":[...]}`
    /// body (§6.8) — not the platform's `{error,message,details}` envelope,
    /// because the caller needs `methods` at the top level to prompt for a
    /// specific factor.
    record MfaRequiredBody(String code, String message, List<String> methods) {
    }

    private static void changePassword(Context ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        var req = decode(ctx, ChangePasswordRequest.class);

        // Go's ssoManaged (an external identity, or a domain routed to an
        // OIDC provider — a password-era user whose domain moved to SSO
        // changes credentials at the provider now) plus an identity
        // provisioned as OIDC, which never had a password here.
        if (LoginApi.ssoManaged(s.login(), p) || p.isFederated()) {
            HttpError.write(ctx, 400, "SSO_MANAGED",
                    "Your password is managed by your identity provider and cannot be changed here.", Map.of());
            return;
        }
        if (p.userIdentity() == null || !p.userIdentity().hasPassword()) {
            HttpError.write(ctx, 400, "NO_PASSWORD", "This account signs in without a password.", Map.of());
            return;
        }
        String currentPassword = req.currentPassword() == null ? "" : req.currentPassword();
        if (!PasswordHash.matches(currentPassword, p.userIdentity().passwordHash())) {
            HttpError.write(ctx, 401, "INVALID_CURRENT_PASSWORD", "Your current password is incorrect.", Map.of());
            return;
        }
        String newPassword = req.newPassword() == null ? "" : req.newPassword();
        var verdict = PasswordPolicy.check(newPassword, p.email(), p.name());
        if (verdict instanceof PasswordPolicy.Rejected r) {
            HttpError.write(ctx, 400, r.code(), r.message(), Map.of());
            return;
        }

        List<MfaMethod> confirmed;
        try {
            confirmed = s.mfa().confirmed(p.id());
        } catch (RuntimeException e) {
            LOG.error("change-password: MFA status load failed principal={}", p.id(), e);
            HttpError.write(ctx, 500, "MFA_STATUS_FAILED", "could not check two-factor status", Map.of());
            return;
        }
        if (!confirmed.isEmpty()) {
            String code = req.code();
            if (code == null || code.isEmpty()) {
                ctx.status(400).json(new MfaRequiredBody("MFA_REQUIRED",
                        "Enter a code from your second factor to change your password.",
                        confirmed.stream().map(MfaMethod::name).toList()));
                return;
            }
            if (!verifyAnySecondFactor(s, p, confirmed, code)) {
                HttpError.write(ctx, 400, "INVALID_CODE", "That code didn't match — try again.", Map.of());
                return;
            }
        }

        String newHash = PasswordHash.hash(newPassword);
        if (!persistNewPassword(s, p, newHash)) {
            HttpError.write(ctx, 500, "UPDATE_FAILED", "could not save the new password", Map.of());
            return;
        }

        // Post-change hygiene (§6.7): best-effort, the password is already changed.
        try {
            s.mfa().revokeAllTrustedDevices(p.id());
        } catch (RuntimeException e) {
            LOG.warn("revoke trusted devices after password change failed principal={}", p.id(), e);
        }
        s.deviceCookie().clear(ctx);
        try {
            s.grantStore().revokeAllForPrincipal(p.id());
        } catch (RuntimeException e) {
            LOG.warn("revoke refresh tokens after password change failed principal={}", p.id(), e);
        }
        s.notifier().passwordChanged(p.email());
        ctx.json(Map.of("message", "Your password has been changed."));
    }

    /// Any confirmed factor accepts its own code (TOTP, e-mail PIN); a
    /// recovery code also backs a confirmed TOTP. Verification errors are
    /// swallowed as "not this factor" (Go: `verifyAnySecondFactor`), the
    /// same posture as trying the next confirmed factor.
    private static boolean verifyAnySecondFactor(State s, Principal p, List<MfaMethod> confirmed, String code) {
        for (MfaMethod m : confirmed) {
            boolean ok = switch (m) {
                case TOTP -> safeVerify(() -> s.mfa().verifyTotp(p.id(), code));
                case EMAIL_PIN -> safeVerify(() -> s.mfa().verifyLoginEmailPin(p.id(), code));
            };
            if (ok) {
                return true;
            }
        }
        if (confirmed.contains(MfaMethod.TOTP)) {
            return safeVerify(() -> s.mfa().verifyRecoveryCode(p.id(), code));
        }
        return false;
    }

    private static boolean safeVerify(java.util.function.BooleanSupplier sup) {
        try {
            return sup.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /// Mirrors `LoginApi#rehash`'s write path, but a failure here fails the
    /// request — this is the primary write, not a best-effort side effect.
    private static boolean persistNewPassword(State s, Principal p, String newHash) {
        try (Connection conn = s.login().writes().getConnection()) {
            conn.setAutoCommit(false);
            try {
                s.login().principals().persist(p.withPasswordHash(newHash), DbTx.wrapForBootstrap(conn));
                conn.commit();
            } catch (RuntimeException | SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
            return true;
        } catch (RuntimeException | SQLException e) {
            LOG.error("password change persist failed principal={}", p.id(), e);
            return false;
        }
    }

    // ── /auth/change-password/send-email-code ────────────────────────────

    private static void sendEmailCode(Context ctx, State s) {
        Optional<Principal> op = principalFromSession(ctx, s);
        if (op.isEmpty()) {
            return;
        }
        Principal p = op.get();
        List<MfaMethod> confirmed;
        try {
            confirmed = s.mfa().confirmed(p.id());
        } catch (RuntimeException e) {
            LOG.error("change-password send-email-code: MFA status load failed principal={}", p.id(), e);
            HttpError.write(ctx, 500, "MFA_STATUS_FAILED", "could not check two-factor status", Map.of());
            return;
        }
        if (confirmed.isEmpty()) {
            HttpError.write(ctx, 400, "NO_MFA", "two-factor is not enabled", Map.of());
            return;
        }
        if (!confirmed.contains(MfaMethod.EMAIL_PIN)) {
            HttpError.write(ctx, 400, "NO_EMAIL_2FA", "email codes are not enabled for your account", Map.of());
            return;
        }
        String email = p.email();
        if (email == null || email.isBlank()) {
            HttpError.write(ctx, 400, "NO_EMAIL", "account has no email", Map.of());
            return;
        }
        try {
            s.mfa().sendLoginEmailPin(p.id(), email);
        } catch (RuntimeException e) {
            LOG.error("change-password send-email-code failed principal={}", p.id(), e);
            HttpError.write(ctx, 500, "SEND_FAILED", "could not send the code", Map.of());
            return;
        }
        ctx.json(Map.of("message", "A code has been sent to your email."));
    }

    // ── shared helpers ───────────────────────────────────────────────────

    private static Optional<Principal> principalFromSession(Context ctx, State s) {
        Optional<AuthContext> ac = Auth.currentOptional();
        if (ac.isEmpty() || ac.get().principalId().isBlank()) {
            unauthorized(ctx);
            return Optional.empty();
        }
        Optional<Principal> p = s.login().principals().findById(ac.get().principalId());
        if (p.isEmpty() || !p.get().active()) {
            unauthorized(ctx);
            return Optional.empty();
        }
        return p;
    }

    private static void unauthorized(Context ctx) {
        ctx.header("WWW-Authenticate", "Cookie realm=\"" + SessionCookie.NAME + "\"");
        HttpError.write(ctx, 401, "UNAUTHENTICATED", "Not authenticated", Map.of());
    }

    private static <T> T decode(Context ctx, Class<T> type) {
        try {
            return Json.MAPPER.readValue(ctx.body(), type);
        } catch (JacksonException e) {
            throw HttpError.invalidJson(e.getOriginalMessage() == null ? "malformed request body" : e.getOriginalMessage());
        }
    }
}
