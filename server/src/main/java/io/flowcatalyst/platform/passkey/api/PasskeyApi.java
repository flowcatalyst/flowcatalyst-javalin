package io.flowcatalyst.platform.passkey.api;

import io.flowcatalyst.platform.auth.login.AuthAlarms;
import io.flowcatalyst.platform.auth.login.BackoffCheck;
import io.flowcatalyst.platform.auth.login.ClientIp;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.notify.Notifications;
import io.flowcatalyst.platform.passkey.CeremonyRepository;
import io.flowcatalyst.platform.passkey.Passkey;
import io.flowcatalyst.platform.passkey.PasskeyRepository;
import io.flowcatalyst.platform.passkey.PasskeyService;
import io.flowcatalyst.platform.passkey.operations.AuthenticatePasskey;
import io.flowcatalyst.platform.passkey.operations.RegisterPasskey;
import io.flowcatalyst.platform.passkey.operations.RevokePasskey;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The six `/auth/webauthn/*` routes (`docs/spec/auth-identity.md` §7.2–§7.4
/// with rulings I-Q13, I-Q24, I-Q25; Go `webauthn/api`). Registration and
/// the credential list are session-gated; authentication is public, answers
/// an unknown or passkey-less account with a decoy challenge, shares the
/// password path's backoff budget, records attempts only for real
/// principals after a real ceremony, and refuses a signature counter that
/// went backwards. A passkey login never consults 2FA and returns no
/// permissions (the SPA loads `/auth/me`).
public final class PasskeyApi {

    private static final Logger LOG = LoggerFactory.getLogger(PasskeyApi.class);

    public record State(PasskeyService service, PasskeyRepository credentials, CeremonyRepository ceremonies,
                        PrincipalRepository principals, UnitOfWork uow, TokenIssuer issuer, SessionCookie cookie,
                        Notifications notices, LoginAttemptRepository attempts, BackoffCheck backoff, Clock clock) {
        public State {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(ceremonies, "ceremonies");
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(cookie, "cookie");
            Objects.requireNonNull(notices, "notices");
            Objects.requireNonNull(clock, "clock");
            if ((attempts == null) != (backoff == null)) throw new IllegalArgumentException("attempts and backoff go together");
        }
    }

    private PasskeyApi() {
    }

    public static void register(Routes routes, State s) {
        routes.post("/auth/webauthn/register/begin", Auth.scoped(ctx -> registerBegin(ctx, s)));
        routes.post("/auth/webauthn/register/complete", Auth.scoped(ctx -> registerComplete(ctx, s)));
        routes.post("/auth/webauthn/authenticate/begin", ctx -> authenticateBegin(ctx, s));
        routes.post("/auth/webauthn/authenticate/complete", ctx -> authenticateComplete(ctx, s));
        routes.get("/auth/webauthn/credentials", Auth.scoped(ctx -> listCredentials(ctx, s)));
        routes.delete("/auth/webauthn/credentials/{id}", Auth.scoped(ctx -> deleteCredential(ctx, s)));
    }

    // ── register ───────────────────────────────────────────────────────────

    record RegisterBeginRequest(String displayName) {
    }

    record StateAndOptions(String stateId, JsonNode options) {
    }

    static void registerBegin(Exchange ctx, State s) {
        AuthContext ac = requireSession();
        Principal p = s.principals().findById(ac.principalId())
                .orElseThrow(() -> UseCaseException.resourceNotFound("Principal", ac.principalId()));
        String displayName = p.name();
        RegisterBeginRequest req = ctx.body().isBlank() ? new RegisterBeginRequest(null) : decode(ctx, RegisterBeginRequest.class);
        if (req.displayName() != null && !req.displayName().isEmpty()) {
            displayName = req.displayName();
        }
        PasskeyService.Started started;
        try {
            started = s.service().beginRegistration(p.id(), p.email() == null ? "" : p.email(), displayName);
        } catch (PasskeyService.CeremonyException e) {
            throw UseCaseException.internal("WEBAUTHN", "begin registration failed", e);
        }
        String stateId = CeremonyRepository.newStateId();
        try {
            s.ceremonies().storeRegistration(stateId, new CeremonyRepository.Registration(p.id(), started.session(), displayName));
        } catch (RuntimeException e) {
            throw UseCaseException.internal("REPO", "store ceremony failed", e);
        }
        ctx.json(new StateAndOptions(stateId, started.options()));
    }

    record RegisterCompleteRequest(String stateId, String name, JsonNode credential) {
    }

    static void registerComplete(Exchange ctx, State s) {
        AuthContext ac = requireSession();
        RegisterCompleteRequest req = decode(ctx, RegisterCompleteRequest.class);
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            HttpError.write(ctx, 400, "NAME_REQUIRED", "a passkey name is required", Map.of());
            return;
        }
        Optional<CeremonyRepository.Registration> consumed;
        try {
            consumed = s.ceremonies().consumeRegistration(req.stateId());
        } catch (RuntimeException e) {
            LOG.warn("registration ceremony consume failed", e);
            consumed = Optional.empty();
        }
        if (consumed.isEmpty()) {
            HttpError.write(ctx, 400, "STATE_NOT_FOUND", "registration ceremony state not found or expired", Map.of());
            return;
        }
        if (!consumed.get().principalId().equals(ac.principalId())) {
            HttpError.write(ctx, 403, "FORBIDDEN", "registration ceremony belongs to a different principal", Map.of());
            return;
        }
        CeremonyRepository.Registration ceremony = consumed.get();
        Principal p = s.principals().findById(ceremony.principalId())
                .orElseThrow(() -> UseCaseException.resourceNotFound("Principal", ceremony.principalId()));
        PasskeyService.Registered registered;
        try {
            registered = s.service().finishRegistration(ceremony.session(), credentialJson(req.credential()));
        } catch (PasskeyService.InvalidCredential e) {
            HttpError.write(ctx, 400, e.code, e.getMessage(), Map.of());
            return;
        } catch (PasskeyService.CeremonyException e) {
            throw UseCaseException.internal("WEBAUTHN", "finish registration failed", e);
        }
        var event = RegisterPasskey.of(s.credentials()).run(s.uow(),
                new RegisterPasskey.RegisterCommand(req.stateId(), name, registered), ExecutionContext.of(ac.principalId()));
        s.notices().newPasskey(p.email());
        ctx.json(Map.of("credentialId", event.credentialId()));
    }

    // ── authenticate ───────────────────────────────────────────────────────

    record AuthenticateBeginRequest(String email) {
    }

    static void authenticateBegin(Exchange ctx, State s) {
        AuthenticateBeginRequest req = decode(ctx, AuthenticateBeginRequest.class);
        String email = req.email() == null ? "" : req.email().trim();
        if (email.isEmpty()) {
            HttpError.write(ctx, 400, "EMAIL_REQUIRED", "email is required", Map.of());
            return;
        }
        String ip = ClientIp.of(ctx);
        if (s.backoff() != null) {
            BackoffCheck.Decision d;
            try {
                d = s.backoff().check(email, ip, s.clock().instant());
            } catch (RuntimeException e) {
                // Fail closed (ruling C-Q23): a store error never switches the lock off.
                LOG.error("passkey backoff check failed; refusing", e);
                AuthAlarms.backoffStoreError();
                HttpError.write(ctx, 503, "BACKOFF_UNAVAILABLE", "login is temporarily unavailable; try again shortly", Map.of());
                return;
            }
            if (!d.allowed()) {
                // Ruling I-Q24: the platform envelope, not huma's.
                ctx.header("Retry-After", Long.toString(d.retryAfterSecs()));
                HttpError.write(ctx, 429, "TOO_MANY_REQUESTS", "Too many failed attempts — try again later", Map.of());
                return;
            }
        }
        Optional<Principal> found = s.principals().findByEmail(email.toLowerCase(Locale.ROOT));
        if (found.isEmpty() || !found.get().active()) {
            ctx.json(new StateAndOptions(CeremonyRepository.newStateId(), s.service().decoyChallenge()));
            return;
        }
        Principal p = found.get();
        List<Passkey> creds;
        try {
            creds = s.credentials().findByPrincipal(p.id());
        } catch (RuntimeException e) {
            LOG.warn("passkey list failed principal={}", p.id(), e);
            creds = List.of();
        }
        if (creds.isEmpty()) {
            ctx.json(new StateAndOptions(CeremonyRepository.newStateId(), s.service().decoyChallenge()));
            return;
        }
        PasskeyService.Started started;
        try {
            started = s.service().beginAssertion(p.id());
        } catch (PasskeyService.CeremonyException e) {
            throw UseCaseException.internal("WEBAUTHN", "begin login failed", e);
        }
        String stateId = CeremonyRepository.newStateId();
        try {
            s.ceremonies().storeAuthentication(stateId, new CeremonyRepository.Authentication(p.id(), started.session()));
        } catch (RuntimeException e) {
            throw UseCaseException.internal("REPO", "store ceremony failed", e);
        }
        ctx.json(new StateAndOptions(stateId, started.options()));
    }

    record AuthenticateCompleteRequest(String stateId, JsonNode credential) {
    }

    record AuthenticatedResponse(String principalId, String email, String name, List<String> roles) {
    }

    static void authenticateComplete(Exchange ctx, State s) {
        AuthenticateCompleteRequest req = decode(ctx, AuthenticateCompleteRequest.class);
        Optional<CeremonyRepository.Authentication> consumed;
        try {
            consumed = s.ceremonies().consumeAuthentication(req.stateId());
        } catch (RuntimeException e) {
            LOG.warn("authentication ceremony consume failed", e);
            consumed = Optional.empty();
        }
        if (consumed.isEmpty() || consumed.get().principalId() == null) {
            invalidCredentials(ctx);
            return;
        }
        Optional<Principal> found = s.principals().findById(consumed.get().principalId());
        if (found.isEmpty() || !found.get().active()) {
            invalidCredentials(ctx);
            return;
        }
        Principal p = found.get();
        if (s.credentials().findByPrincipal(p.id()).isEmpty()) {
            invalidCredentials(ctx);
            return;
        }
        String ip = ClientIp.of(ctx);
        String userAgent = ctx.header("User-Agent");
        PasskeyService.Asserted asserted;
        try {
            asserted = s.service().finishAssertion(consumed.get().session(), credentialJson(req.credential()));
        } catch (PasskeyService.InvalidCredential e) {
            LOG.info("passkey assertion rejected principal={}: {}", p.id(), e.getMessage());
            recordAttempt(s, p, AttemptOutcome.FAILURE, "Invalid passkey", ip, userAgent);
            invalidCredentials(ctx);
            return;
        } catch (PasskeyService.CeremonyException e) {
            throw UseCaseException.internal("WEBAUTHN", "finish login failed", e);
        }
        if (!asserted.counterValid()) {
            // Ruling I-Q13: a counter that went backwards is a cloned authenticator.
            LOG.warn("passkey signature counter went backwards principal={} credential={}", p.id(), asserted.credential().id());
            recordAttempt(s, p, AttemptOutcome.FAILURE, "Invalid passkey", ip, userAgent);
            invalidCredentials(ctx);
            return;
        }
        AuthenticatePasskey.of(s.credentials()).run(s.uow(), new AuthenticatePasskey.AuthenticateCommand(req.stateId(),
                asserted.credential().id(), asserted.signCount(), asserted.userVerified(), asserted.backedUp()),
                ExecutionContext.of(p.id()));
        String token;
        try {
            token = s.issuer().sessionToken(p.id(), p.email());
        } catch (RuntimeException e) {
            throw UseCaseException.internal("MINT_FAILED", "failed to mint session token", e);
        }
        s.cookie().set(ctx, token);
        recordAttempt(s, p, AttemptOutcome.SUCCESS, null, ip, userAgent);
        ctx.json(new AuthenticatedResponse(p.id(), p.email() == null || p.email().isEmpty() ? null : p.email(), p.name(), p.roleNames()));
    }

    // ── credentials ────────────────────────────────────────────────────────

    record CredentialSummary(String id, String name, Instant createdAt, Instant lastUsedAt) {
    }

    static void listCredentials(Exchange ctx, State s) {
        AuthContext ac = requireSession();
        List<CredentialSummary> out = s.credentials().findByPrincipal(ac.principalId()).stream()
                .map(p -> new CredentialSummary(p.id(), p.name(), p.createdAt(), p.lastUsedAt())).toList();
        ctx.json(out);
    }

    static void deleteCredential(Exchange ctx, State s) {
        AuthContext ac = requireSession();
        String id = ctx.pathParam("id");
        boolean owned = s.credentials().findByPrincipal(ac.principalId()).stream().anyMatch(p -> p.id().equals(id));
        if (!owned) {
            // Never 403: an id that is not the caller's reads as absent.
            throw UseCaseException.resourceNotFound("Credential", id);
        }
        RevokePasskey.of(s.credentials()).run(s.uow(), new RevokePasskey.RevokeCommand(id), ExecutionContext.of(ac.principalId()));
        ctx.status(204);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static AuthContext requireSession() {
        Optional<AuthContext> ac = Auth.currentOptional();
        if (ac.isEmpty() || ac.get().principalId() == null || ac.get().principalId().isBlank()) {
            throw UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
        }
        return ac.get();
    }

    private static void invalidCredentials(Exchange ctx) {
        HttpError.write(ctx, 403, "INVALID_CREDENTIALS", "Invalid credentials.", Map.of());
    }

    private static void recordAttempt(State s, Principal p, AttemptOutcome outcome, String reason, String ip, String userAgent) {
        if (s.attempts() == null || p.email() == null || p.email().isBlank()) {
            return;
        }
        try {
            s.attempts().recordAttempt(LoginAttempt.attempt(AttemptType.USER_LOGIN, outcome, reason,
                    p.email().trim().toLowerCase(Locale.ROOT), p.id(), ip == null || ip.isBlank() ? null : ip,
                    userAgent == null || userAgent.isBlank() ? null : userAgent));
        } catch (RuntimeException e) {
            LOG.warn("recording passkey attempt failed principal={}", p.id(), e);
        }
    }

    private static String credentialJson(JsonNode credential) throws PasskeyService.InvalidCredential {
        if (credential == null || credential.isNull() || !credential.isObject()) {
            throw new PasskeyService.InvalidCredential("INVALID_CREDENTIAL", "credential is required");
        }
        return Json.write(credential);
    }

    private static <T> T decode(Exchange ctx, Class<T> type) {
        try {
            return Json.MAPPER.readValue(ctx.body(), type);
        } catch (RuntimeException e) {
            throw UseCaseException.validation("INVALID_JSON", "malformed request body");
        }
    }
}
