package io.flowcatalyst.platform.passwordreset;

import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.mfa.DomainPolicy;
import io.flowcatalyst.platform.auth.mfa.Mfa;
import io.flowcatalyst.platform.auth.mfa.MfaToken;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.notify.Notifications;
import io.flowcatalyst.platform.principal.PasswordPolicy;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.ResetPassword;
import io.flowcatalyst.platform.principal.operations.ResetPasswordCommand;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// `/auth/password-reset/{request,validate,confirm}` (`docs/spec/auth-identity.md`
/// §8.2–§8.5 with the §0.5 rulings; Go `passwordreset/api`). `request`
/// always answers the same 200 and never reveals whether an account
/// exists; `validate` reports without consuming; `confirm` is the ordered
/// table — a token that requires a factor takes a TOTP code with a
/// five-attempt budget, the password goes through the principal's own
/// `ResetPassword` operation as the `system` actor (Q22), and the
/// post-reset step clears factors when asked, always revokes trusted
/// devices and refresh tokens, notifies, and demands enrolment when the
/// domain requires a factor the user does not have.
public final class PasswordResetApi {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetApi.class);
    static final String SYSTEM_ACTOR = "system";

    /// @param requireStrongFactorForReset ruling I-Q19: false — a user without TOTP gets a token, not an approval
    public record State(ResetLinks links, ResetTokenRepository tokens, PrincipalRepository principals, UnitOfWork uow,
                        Mfa mfa, MfaToken mfaTokens, DomainPolicy.Evaluator policy, GrantStore grants,
                        Notifications notices, PortalPasswords portal, ApprovalQueue approvals,
                        boolean requireStrongFactorForReset, Clock clock) {
        public State {
            Objects.requireNonNull(links, "links");
            Objects.requireNonNull(tokens, "tokens");
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(mfa, "mfa");
            Objects.requireNonNull(mfaTokens, "mfaTokens");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(grants, "grants");
            Objects.requireNonNull(notices, "notices");
            Objects.requireNonNull(portal, "portal");
            Objects.requireNonNull(approvals, "approvals");
            Objects.requireNonNull(clock, "clock");
        }
    }

    private PasswordResetApi() {
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.post("/auth/password-reset/request", ctx -> request(ctx, s));
        routes.get("/auth/password-reset/validate", ctx -> validate(ctx, s));
        routes.post("/auth/password-reset/confirm", ctx -> confirm(ctx, s));
    }

    // ── request ────────────────────────────────────────────────────────────

    static void request(Context ctx, State s) {
        JsonNode body = body(ctx);
        if (body == null) {
            HttpError.write(ctx, 400, "INVALID_BODY", "malformed request body", Map.of());
            return;
        }
        String email = body.path("email").asString("").trim().toLowerCase(Locale.ROOT);
        try {
            tryIssueToken(s, email);
        } catch (RuntimeException e) {
            LOG.warn("password reset request suppressed error domain={}", domainOf(email), e);
        }
        ctx.status(200).json(Map.of("message", "If an account exists, a reset email has been sent."));
    }

    /// §8.2: nothing for a blank, unknown or ineligible address; a user
    /// without a strong factor is queued for approval only when the
    /// platform requires one; else one fresh token, mailed best-effort.
    static void tryIssueToken(State s, String email) {
        if (email.isEmpty()) {
            return;
        }
        Optional<Principal> found = s.principals().findByEmail(email);
        if (found.isEmpty()) {
            LOG.warn("password reset requested for an unknown address domain={}", domainOf(email));
            return;
        }
        Principal p = found.get();
        if (!p.isUser() || p.isFederated() || p.email() == null || p.email().isBlank()) {
            return;
        }
        boolean strong = s.mfa().confirmed(p.id()).contains(MfaMethod.TOTP);
        if (!strong && s.requireStrongFactorForReset()) {
            s.approvals().queue(p);
            return;
        }
        String raw = s.links().mintSelfServiceReset(p.id(), strong);
        try {
            s.links().sendResetLink(p.email(), s.links().resetLink(raw), s.links().platformTheme());
        } catch (RuntimeException e) {
            LOG.warn("password reset mail not sent principal={}", p.id(), e); // the token still exists
        }
    }

    // ── validate ───────────────────────────────────────────────────────────

    static void validate(Context ctx, State s) {
        String raw = ctx.queryParam("token");
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<ResetToken> t;
        try {
            t = s.links().find(raw);
        } catch (RuntimeException e) {
            LOG.warn("reset token lookup failed", e);
            t = Optional.empty();
        }
        if (t.isEmpty()) {
            out.put("valid", false);
            out.put("reason", "not_found");
            out.put("requiresFactor", false);
        } else if (t.get().isExpired(s.clock().instant())) {
            out.put("valid", false);
            out.put("reason", "expired");
            out.put("requiresFactor", false);
        } else {
            out.put("valid", true);
            out.put("reason", null);
            out.put("requiresFactor", t.get().requiresFactor());
            if (t.get().portalSubject()) {
                out.put("portal", true);
            }
        }
        ctx.status(200).contentType("application/json").result(Json.writeLine(out));
    }

    // ── confirm ────────────────────────────────────────────────────────────

    static void confirm(Context ctx, State s) {
        JsonNode body = body(ctx);
        if (body == null) {
            HttpError.write(ctx, 400, "INVALID_BODY", "malformed request body", Map.of());
            return;
        }
        String raw = body.path("token").asString("");
        String password = body.path("password").asString("");
        String factorCode = body.path("factorCode").asString("");
        Optional<ResetToken> found;
        try {
            found = s.links().find(raw);
        } catch (RuntimeException e) {
            LOG.error("reset token lookup failed", e);
            HttpError.write(ctx, 500, "REPO", "token lookup failed", Map.of());
            return;
        }
        if (found.isEmpty()) {
            HttpError.write(ctx, 400, "INVALID_TOKEN", "Invalid or expired reset token.", Map.of());
            return;
        }
        ResetToken token = found.get();
        if (token.isExpired(s.clock().instant())) {
            s.tokens().deleteByPrincipal(token.principalId());
            HttpError.write(ctx, 400, "EXPIRED_TOKEN", "Reset token has expired.", Map.of());
            return;
        }
        if (token.portalSubject()) {
            confirmPortal(ctx, s, token, password);
            return;
        }
        if (token.requiresFactor()) {
            if (token.factorAttempts() >= ResetToken.MAX_FACTOR_ATTEMPTS) {
                s.tokens().deleteByPrincipal(token.principalId());
                HttpError.write(ctx, 400, "INVALID_TOKEN", "Invalid or expired reset token.", Map.of());
                return;
            }
            boolean ok;
            try {
                ok = s.mfa().verifyTotp(token.principalId(), factorCode);
            } catch (RuntimeException e) {
                LOG.error("factor verification failed principal={}", token.principalId(), e);
                HttpError.write(ctx, 500, "MFA", "factor verification failed", Map.of());
                return;
            }
            if (!ok) {
                int attempts = s.tokens().countFactorAttempt(token.id());
                if (attempts < 0 || attempts >= ResetToken.MAX_FACTOR_ATTEMPTS) {
                    s.tokens().deleteByPrincipal(token.principalId());
                    HttpError.write(ctx, 400, "INVALID_TOKEN", "Invalid or expired reset token.", Map.of());
                } else {
                    HttpError.write(ctx, 400, "INVALID_FACTOR", "Invalid authenticator code.", Map.of());
                }
                return;
            }
        }
        try {
            ResetPassword.of(s.principals()).run(s.uow(), new ResetPasswordCommand(token.principalId(), password, true),
                    ExecutionContext.of(SYSTEM_ACTOR));
        } catch (UseCaseException e) {
            HttpError.write(ctx, e.error());
            return;
        }
        try {
            s.tokens().deleteByPrincipal(token.principalId());
        } catch (RuntimeException e) {
            LOG.warn("reset token cleanup failed principal={}", token.principalId(), e);
        }
        LOG.info("password reset completed principal={}", token.principalId());
        ctx.status(200).json(postResetTwoFactor(s, token));
    }

    /// §8.4 `postResetTwoFactor`: clear the factors when the token says so,
    /// always revoke trusted devices and refresh tokens, notify, then
    /// demand enrolment when the domain requires a factor the user lacks.
    static Map<String, Object> postResetTwoFactor(State s, ResetToken token) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<Principal> found = s.principals().findById(token.principalId());
        if (found.isEmpty()) {
            out.put("status", "ok");
            out.put("message", "Password reset successfully.");
            return out;
        }
        Principal p = found.get();
        if (token.reset2fa()) {
            s.mfa().resetAll(p.id());
            s.notices().twoFactorReset(p.email());
        }
        s.mfa().revokeAllTrustedDevices(p.id());
        try {
            s.grants().revokeAllForPrincipal(p.id());
        } catch (RuntimeException e) {
            LOG.warn("refresh token revocation failed principal={}", p.id(), e);
        }
        s.notices().passwordChanged(p.email());
        DomainPolicy dp = s.policy().evaluate(p.email());
        if (dp.requires2fa() && s.mfa().confirmed(p.id()).isEmpty()) {
            out.put("status", "enrollment_required");
            out.put("message", "Password set. Set up two-factor authentication to finish.");
            out.put("enrollToken", s.mfaTokens().mint(p.id(), MfaToken.Purpose.ENROLL));
            out.put("allowedMethods", dp.permittedMethods().stream().map(MfaMethod::name).toList());
        } else {
            out.put("status", "ok");
            out.put("message", "Password reset successfully.");
        }
        if (token.redirectUri() != null && !token.redirectUri().isEmpty()) {
            out.put("redirectUri", token.redirectUri());
        }
        return out;
    }

    /// §8.5: the portal identity must exist and be active; the policy
    /// violation is a 400 with its own code; no 2FA gate, no revocation.
    static void confirmPortal(Context ctx, State s, ResetToken token, String password) {
        Optional<PortalPasswords.Identity> found;
        try {
            found = s.portal().find(token.principalId());
        } catch (RuntimeException e) {
            LOG.error("portal identity lookup failed id={}", token.principalId(), e);
            HttpError.write(ctx, 500, "REPO", "identity lookup failed", Map.of());
            return;
        }
        if (found.isEmpty() || !found.get().active()) {
            HttpError.write(ctx, 400, "INVALID_TOKEN", "Invalid or expired reset token.", Map.of());
            return;
        }
        PortalPasswords.Identity identity = found.get();
        if (PasswordPolicy.check(password, identity.email(), identity.name() == null ? "" : identity.name()) instanceof PasswordPolicy.Rejected r) {
            HttpError.write(ctx, 400, r.code(), r.message(), Map.of());
            return;
        }
        String hash;
        try {
            hash = PasswordHash.hash(password);
        } catch (RuntimeException e) {
            LOG.error("password hash failed", e);
            HttpError.write(ctx, 500, "HASH", "could not hash the password", Map.of());
            return;
        }
        boolean updated;
        try {
            updated = s.portal().setPasswordHash(identity.id(), hash);
        } catch (RuntimeException e) {
            LOG.error("portal password update failed id={}", identity.id(), e);
            updated = false;
        }
        if (!updated) {
            HttpError.write(ctx, 500, "REPO", "could not store the password", Map.of());
            return;
        }
        s.tokens().deleteByPrincipal(identity.id());
        s.notices().portalPasswordChanged(identity.email());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("message", "Password set successfully.");
        if (token.redirectUri() != null && !token.redirectUri().isEmpty()) {
            out.put("redirectUri", token.redirectUri());
        }
        out.put("portal", true);
        ctx.status(200).json(out);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static JsonNode body(Context ctx) {
        try {
            JsonNode n = Json.MAPPER.readTree(ctx.body());
            return n != null && n.isObject() ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1);
    }

}
