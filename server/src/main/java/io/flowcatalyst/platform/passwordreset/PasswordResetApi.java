package io.flowcatalyst.platform.passwordreset;

import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.login.ClientIp;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.mfa.DomainPolicy;
import io.flowcatalyst.platform.auth.mfa.Mfa;
import io.flowcatalyst.platform.auth.mfa.MfaToken;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.notify.Notifications;
import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.PasswordPolicy;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.PrincipalType;
import io.flowcatalyst.platform.principal.operations.ResetPassword;
import io.flowcatalyst.platform.principal.operations.ResetPasswordCommand;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;
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
    /// @param issuer nullable together with `cookie` (as `LoginApi.State.attempts`/`backoff`); `null` = the
    ///               confirm route never signs anyone in (today's behaviour, and what every existing test
    ///               constructs) — app-managed-invitations §4
    /// @param cookie the session cookie the login route uses; must never drift from it — see `Platform`
    /// @param attempts config-permissions.md §B: the trail an invite-confirm session mint is recorded to,
    ///                 the same store the password login writes through — never null (recording is
    ///                 best-effort at the call site, not by being absent here)
    public record State(ResetLinks links, ResetTokenRepository tokens, PrincipalRepository principals, UnitOfWork uow,
                        Mfa mfa, MfaToken mfaTokens, DomainPolicy.Evaluator policy, GrantStore grants,
                        Notifications notices, PortalPasswords portal, ApprovalQueue approvals,
                        boolean requireStrongFactorForReset, Clock clock, TokenIssuer issuer, SessionCookie cookie,
                        LoginAttemptRepository attempts) {
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
            Objects.requireNonNull(attempts, "attempts");
            if ((issuer == null) != (cookie == null)) throw new IllegalArgumentException("issuer and cookie go together");
        }
    }

    private PasswordResetApi() {
    }

    public static void register(Routes routes, State s) {
        routes.post("/auth/password-reset/request", ctx -> request(ctx, s));
        routes.post("/auth/password-setup/request", ctx -> passwordSetupRequest(ctx, s));
        routes.get("/auth/password-reset/validate", ctx -> validate(ctx, s));
        // Group.LOGIN (admission.md §11.7 part B follow-up): password-reset completion —
        // verifies a TOTP factor (Mfa#verifyTotp) when the token requires one, then
        // hashes and persists the new password (ResetPassword / PasswordHash.hash on the
        // portal branch).
        routes.in(Group.LOGIN).post("/auth/password-reset/confirm", ctx -> confirm(ctx, s));
    }

    // ── request ────────────────────────────────────────────────────────────

    static void request(Exchange ctx, State s) {
        JsonNode body = body(ctx);
        if (body == null) {
            HttpError.write(ctx, 400, "INVALID_BODY", "malformed request body", Map.of());
            return;
        }
        String email = body.path("email").asString("").trim().toLowerCase(Locale.ROOT);
        String redirectUri = resetReturnUrl(body.path("redirectUri").asString(null));
        try {
            tryIssueToken(s, email, redirectUri);
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("password reset request suppressed error")
                    .addKeyValue("domain", domainOf(email))
                    .setCause(e)
                    .log();
        }
        ctx.status(200).json(Map.of("message", "If an account exists, a reset email has been sent."));
    }

    /// §8.2: nothing for a blank, unknown or ineligible address; a user
    /// without a strong factor is queued for approval only when the
    /// platform requires one; else one fresh token, mailed best-effort.
    static void tryIssueToken(State s, String email) {
        tryIssueToken(s, email, null);
    }

    /// `redirectUri` (owner, 2026-09-22, Go `359df6b`): the OAuth authorize
    /// round-trip the user was in the middle of when they clicked "Forgot
    /// password" — stored on the token, returned by confirm, followed by the
    /// SPA — so a user signing in to another application through this IdP
    /// resumes that sign-in after the reset. Already filtered by
    /// [#resetReturnUrl]; `null` keeps the reset exactly as before.
    static void tryIssueToken(State s, String email, String redirectUri) {
        if (email.isEmpty()) {
            return;
        }
        Optional<Principal> found = s.principals().findByEmail(email);
        if (found.isEmpty()) {
            LOG.atWarn().setMessage("password reset requested for an unknown address")
                    .addKeyValue("domain", domainOf(email))
                    .log();
            return;
        }
        // Silent to the caller, never silent in the logs: "no reset email
        // arrived" must be told apart from a delivery failure (Go `89f1a08`).
        // The reason is a class, never the address.
        Principal p;
        switch (resetEligibility(found.get())) {
            case Result.Ok<Principal, ResetIneligible>(Principal eligible) -> p = eligible;
            case Result.Err<Principal, ResetIneligible>(ResetIneligible why) -> {
                LOG.atInfo().setMessage("password reset requested for an ineligible account; no email sent")
                        .addKeyValue("principal", why.principalId())
                        .addKeyValue("reason", why.reason())
                        .log();
                return;
            }
        }
        boolean strong = s.mfa().confirmed(p.id()).contains(MfaMethod.TOTP);
        if (!strong && s.requireStrongFactorForReset()) {
            LOG.atInfo().setMessage("password reset queued for approval; no email sent")
                    .addKeyValue("principal", p.id())
                    .addKeyValue("reason", "no strong factor and the platform requires one")
                    .log();
            s.approvals().queue(p);
            return;
        }
        String raw = s.links().mintSelfServiceReset(p.id(), strong, redirectUri);
        try {
            s.links().sendResetLink(p.email(), s.links().resetLink(raw), s.links().platformTheme());
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("password reset mail not sent")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log(); // the token still exists
        }
    }

    /// Why a self-service reset cannot be issued — the eligibility rule's own
    /// clauses, each carrying what an operator needs to act on it. `reason`
    /// is abstract so every case states its own.
    sealed interface ResetIneligible {
        String principalId();

        String reason();

        record NotAUser(String principalId, PrincipalType type) implements ResetIneligible {
            public String reason() {
                return "not a USER principal (" + type + ")";
            }
        }

        record Federated(String principalId) implements ResetIneligible {
            public String reason() {
                return "OIDC-federated (signs in through an external identity provider; has no platform password)";
            }
        }

        record NoEmail(String principalId) implements ResetIneligible {
            public String reason() {
                return "no email address on the account";
            }
        }
    }

    /// `p` itself when a self-service reset may be issued, else why not, in
    /// the rule's order. `findByEmail` returns only USER principals with that
    /// address, so today only [ResetIneligible.Federated] is reachable; the
    /// other two stay so the rule does not silently depend on how the lookup
    /// is written.
    static Result<Principal, ResetIneligible> resetEligibility(Principal p) {
        if (!p.isUser()) {
            return Result.err(new ResetIneligible.NotAUser(p.id(), p.type()));
        }
        if (p.isFederated()) {
            return Result.err(new ResetIneligible.Federated(p.id()));
        }
        if (p.email() == null || p.email().isBlank()) {
            return Result.err(new ResetIneligible.NoEmail(p.id()));
        }
        return Result.ok(p);
    }

    // ── password-setup/request (app-managed-invitations §3) ─────────────────

    /// The login-detected pattern's other half of `request`: same
    /// never-reveal-existence shape, a different fixed message, and it is
    /// eligible only for a passwordless INTERNAL user awaiting setup.
    static void passwordSetupRequest(Exchange ctx, State s) {
        JsonNode body = body(ctx);
        if (body == null) {
            HttpError.write(ctx, 400, "INVALID_BODY", "malformed request body", Map.of());
            return;
        }
        String email = body.path("email").asString("").trim().toLowerCase(Locale.ROOT);
        String redirectUri = body.path("redirectUri").asString(null);
        try {
            tryIssuePasswordSetupInvite(s, email, redirectUri);
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("password setup request suppressed error")
                    .addKeyValue("domain", domainOf(email))
                    .setCause(e)
                    .log();
        }
        ctx.status(200).json(Map.of("message", "If your account needs a password, we've emailed you a link to create it."));
    }

    /// §3: nothing for a blank, unknown, already-set-up or non-internal
    /// address; otherwise the ordinary 72-hour INVITE token, carrying a
    /// redirect only when it is a safe same-site relative path.
    static void tryIssuePasswordSetupInvite(State s, String email, String redirectUri) {
        if (email.isEmpty()) {
            return;
        }
        Optional<Principal> found = s.principals().findByEmail(email);
        if (found.isEmpty() || !found.get().awaitingPasswordSetup()) {
            return;
        }
        if (!s.policy().evaluate(email).internal()) {
            return;
        }
        s.links().sendInviteRedirect(found.get(), safeRelativeRedirect(redirectUri));
    }

    /// §3 step 4: kept only when it starts with exactly one `/`, not `//`,
    /// not `/\`; anything else (absolute URL, scheme, bare host, empty,
    /// `null`) is dropped silently — the same rule `OidcBridgeApi#landing`
    /// applies to `returnUrl`, restated here rather than shared across
    /// packages for one two-line predicate.
    /// The stricter rule for a self-service reset's post-reset redirect: only
    /// the same-origin OAuth authorize round-trip. A reset is requested by
    /// anyone who knows an e-mail address, so its redirect must not be a
    /// general "send me anywhere on this origin"; `/oauth/authorize` validates
    /// its own `client_id`/`redirect_uri`, which is the one reason this is
    /// safe to honour. `null` for anything else — dropped silently.
    static String resetReturnUrl(String uri) {
        String safe = safeRelativeRedirect(uri == null ? null : uri.trim());
        if (safe == null || !safe.startsWith("/oauth/authorize?") || safe.length() > 4096) {
            return null;
        }
        return safe;
    }

    static String safeRelativeRedirect(String uri) {
        if (uri != null && uri.startsWith("/") && !uri.startsWith("//") && !uri.startsWith("/\\")) {
            return uri;
        }
        return null;
    }

    // ── validate ───────────────────────────────────────────────────────────

    static void validate(Exchange ctx, State s) {
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

    static void confirm(Exchange ctx, State s) {
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
                LOG.atError().setMessage("factor verification failed")
                        .addKeyValue("principal", token.principalId())
                        .setCause(e)
                        .log();
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
        // A refusal (password policy, principal gone) propagates to
        // HttpError.install, which writes the same envelope this handler
        // used to write by hand; the token survives for another attempt.
        ResetPassword.of(s.principals()).run(s.uow(), new ResetPasswordCommand(token.principalId(), password, true),
                ExecutionContext.of(SYSTEM_ACTOR));
        try {
            s.tokens().deleteByPrincipal(token.principalId());
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("reset token cleanup failed")
                    .addKeyValue("principal", token.principalId())
                    .setCause(e)
                    .log();
        }
        LOG.atInfo().setMessage("password reset completed")
                .addKeyValue("principal", token.principalId())
                .log();
        Map<String, Object> out = postResetTwoFactor(s, token);
        // app-managed-invitations §4: the principal branch only — the portal
        // branch (confirmPortal) returns before this point and never mints.
        if (shouldAttemptSessionMint(token.purpose(), (String) out.get("status"), s.issuer() != null)) {
            // config-permissions.md §B: an invite sign-in is recorded as a
            // login, written only when a session was actually minted.
            switch (maybeEstablishSession(ctx, s, token, out)) {
                case Result.Ok<Principal, NoSession>(Principal signedIn) -> recordInviteSignIn(s, ctx, signedIn);
                case Result.Err<Principal, NoSession> _ -> { }
            }
        }
        ctx.status(200).json(out);
    }

    /// §4: pure — `wired` is whether `State.issuer`/`cookie` are set. RESET
    /// keeps today's UX (never signs in here); `enrollment_required` mints
    /// its own session when enrolment completes, not this one.
    static boolean shouldAttemptSessionMint(ResetToken.Purpose purpose, String status, boolean wired) {
        return wired && purpose == ResetToken.Purpose.INVITE && "ok".equals(status);
    }

    /// Why [#maybeEstablishSession] minted no session — each case names the
    /// principal, so the caller has the context without re-reading anything.
    sealed interface NoSession {
        String principalId();

        record PrincipalGone(String principalId) implements NoSession {
        }

        record DomainRequiresTwoFactor(String principalId) implements NoSession {
        }

        /// The failure itself is logged where it is caught (infrastructure);
        /// this carries only that it happened.
        record MintFailed(String principalId) implements NoSession {
        }
    }

    /// §4: re-read the principal (absent → nothing); a domain requiring 2FA
    /// never mints here (would bypass the challenge); a mint failure is
    /// logged and leaves the user to sign in normally — the password write
    /// already succeeded either way.
    private static Result<Principal, NoSession> maybeEstablishSession(Exchange ctx, State s, ResetToken token, Map<String, Object> out) {
        // Best-effort end to end: the password write already succeeded, so a
        // failing principal re-read, policy lookup or mint must not turn the
        // 200 into a 500 — the user simply signs in normally.
        try {
            Optional<Principal> found = s.principals().findById(token.principalId());
            if (found.isEmpty()) {
                return Result.err(new NoSession.PrincipalGone(token.principalId()));
            }
            Principal p = found.get();
            if (s.policy().evaluate(p.email()).requires2fa()) {
                return Result.err(new NoSession.DomainRequiresTwoFactor(p.id()));
            }
            String sessionToken = s.issuer().sessionToken(p.id(), p.email());
            s.cookie().set(ctx, sessionToken);
            out.put("sessionEstablished", true);
            return Result.ok(p);
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("session mint after password setup failed")
                    .addKeyValue("principal", token.principalId())
                    .setCause(e)
                    .log();
            return Result.err(new NoSession.MintFailed(token.principalId()));
        }
    }

    /// config-permissions.md §B: one `iam_login_attempts` row — `USER_LOGIN`,
    /// `SUCCESS`, identifier the principal's normalised email, IP and user
    /// agent as the password login records them (`LoginApi#record` /
    /// `ClientIp`). Best-effort: a failed write is logged and never changes
    /// the confirm response — the session was already minted either way.
    private static void recordInviteSignIn(State s, Exchange ctx, Principal signedIn) {
        try {
            String ip = ClientIp.of(ctx);
            s.attempts().recordAttempt(LoginAttempt.attempt(AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, null,
                    EmailAddress.normalise(signedIn.email()), signedIn.id(), ip == null || ip.isBlank() ? null : ip,
                    ctx.header("User-Agent")));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("recording invite sign-in attempt failed")
                    .addKeyValue("principal", signedIn.id())
                    .setCause(e)
                    .log();
        }
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
            LOG.atWarn().setMessage("refresh token revocation failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
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
    static void confirmPortal(Exchange ctx, State s, ResetToken token, String password) {
        Optional<PortalPasswords.Identity> found;
        try {
            found = s.portal().find(token.principalId());
        } catch (RuntimeException e) {
            LOG.atError().setMessage("portal identity lookup failed")
                    .addKeyValue("id", token.principalId())
                    .setCause(e)
                    .log();
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
            LOG.atError().setMessage("portal password update failed")
                    .addKeyValue("id", identity.id())
                    .setCause(e)
                    .log();
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

    private static JsonNode body(Exchange ctx) {
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
