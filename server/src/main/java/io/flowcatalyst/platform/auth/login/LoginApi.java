package io.flowcatalyst.platform.auth.login;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The session surface (`docs/spec/auth-core.md` §6.1 with the §0.5
/// rulings; Go `login/endpoint.go`):
///
/// | Method | Path | Auth |
/// |---|---|---|
/// | POST | `/auth/check-domain` | none |
/// | GET | `/auth/check-domain` | none — legacy shape, no fabricated `authorizationUrl` (ruling C-Q29) |
/// | POST | `/auth/login` | none |
/// | POST | `/auth/logout` | none (a stale cookie is fine) |
/// | GET | `/auth/me` | session or bearer — `status: "ok"` (ruling C-Q24) |
/// | GET | `/auth/login-history` | session |
///
/// `/auth/refresh`, `/auth/change-password` and its e-mail code land with
/// the grant store and the MFA unit respectively.
///
/// Login (§7.1) in order: decode → lower-case the email → constant-shape 401
/// on a blank field, nothing recorded → backoff, **failing closed** on a
/// store error (503, ruling C-Q23) → SSO enforcement (403, a FAILURE row)
/// → principal lookup with timing equalised for the not-found cases →
/// password verify → best-effort rehash → the MFA gate → mint the cookie
/// (500 `MINT_FAILED` in the platform envelope, ruling A-18) → a SUCCESS
/// row → the login response.
///
/// Not carried over: Go's best-effort lower-casing of a legacy mixed-case
/// stored e-mail at login. The Java aggregate normalises e-mails on write,
/// and `findByEmail` compares case-insensitively, so a mixed-case row from
/// older Go data still authenticates; it is simply left as stored.
public final class LoginApi {

    private static final Logger LOG = LoggerFactory.getLogger(LoginApi.class);

    /// Go `SessionHistory`: the last 20 attempts by identifier.
    static final int HISTORY_LIMIT = 20;

    private LoginApi() {
    }

    /// @param attempts  the login-attempt store; `null` disables backoff and recording (Go: repo unwired)
    /// @param backoff   the brute-force gate over `attempts`; `null` when `attempts` is
    /// @param resolver  the store-backed claims resolver (permissions for the response)
    /// @param mfa       the second-factor gate; [MfaChallenge#none()] until the MFA unit lands
    /// @param writes    the pool the best-effort rehash writes through
    public record State(PrincipalRepository principals, EmailDomainMappingRepository mappings,
                        IdentityProviderRepository identityProviders, LoginAttemptRepository attempts,
                        BackoffCheck backoff, TokenIssuer issuer, ClaimsResolver resolver, MfaChallenge mfa,
                        SessionCookie cookie, DataSource writes, Clock clock) {
        public State {
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(mappings, "mappings");
            Objects.requireNonNull(identityProviders, "identityProviders");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(resolver, "resolver");
            Objects.requireNonNull(mfa, "mfa");
            Objects.requireNonNull(cookie, "cookie");
            Objects.requireNonNull(writes, "writes");
            Objects.requireNonNull(clock, "clock");
            if ((attempts == null) != (backoff == null)) throw new IllegalArgumentException("attempts and backoff go together");
        }
    }

    public static void register(Routes routes, State s) {
        routes.post("/auth/check-domain", ctx -> checkDomain(ctx, s));
        routes.get("/auth/check-domain", ctx -> checkDomainLegacy(ctx, s));
        // Group.LOGIN (admission.md §11.7 part B follow-up): verifies the password —
        // PasswordHash.verify on the real path, PasswordHash.equalizeTiming on the
        // not-found path — the Argon2 cost this group's budget exists to protect.
        routes.in(Group.LOGIN).post("/auth/login", ctx -> login(ctx, s));
        routes.post("/auth/logout", ctx -> logout(ctx, s));
        routes.get("/auth/me", Auth.scoped(ctx -> me(ctx, s)));
        routes.get("/auth/login-history", Auth.scoped(ctx -> loginHistory(ctx, s)));
    }

    // ── /auth/check-domain ─────────────────────────────────────────────────

    record CheckDomainRequest(String email) {
    }

    /// `{authMethod: "internal"|"external", loginUrl?, idpIssuer?}` — a
    /// malformed or unknown domain answers `internal` so nothing leaks.
    record CheckDomainResponse(String authMethod, String loginUrl, String idpIssuer) {
        static final CheckDomainResponse INTERNAL = new CheckDomainResponse("internal", null, null);
    }

    private static void checkDomain(Exchange ctx, State s) {
        var req = decode(ctx, CheckDomainRequest.class);
        String email = req.email() == null ? "" : req.email().trim();
        if (email.isEmpty()) {
            throw HttpError.badRequest("EMAIL_REQUIRED", "email is required");
        }
        Optional<String> domain = domainOf(email);
        if (domain.isEmpty()) {
            ctx.json(CheckDomainResponse.INTERNAL);
            return;
        }
        Optional<IdentityProvider> idp = mappedProvider(s, domain.get());
        if (idp.isEmpty() || idp.get().type() != IdentityProviderType.OIDC) {
            ctx.json(CheckDomainResponse.INTERNAL);
            return;
        }
        // The domain, not the e-mail, so the local part never rides the redirect chain.
        ctx.json(new CheckDomainResponse("external", "/auth/oidc/login?domain=" + encodeUri(domain.get()),
                idp.get().oidcIssuerUrl()));
    }

    /// The legacy query shape: `providerId` for any mapped IdP type; never
    /// a fabricated `authorizationUrl` (rulings I-Q9 / C-Q29 — the field is
    /// omitted until a real value exists).
    record CheckDomainLegacyResponse(String domain, String authMethod, String providerId, String authorizationUrl) {
    }

    private static void checkDomainLegacy(Exchange ctx, State s) {
        String email = ctx.queryParam("email");
        Optional<String> domain = domainOf(email == null ? "" : email.trim());
        String authMethod = "INTERNAL";
        String providerId = null;
        if (domain.isPresent()) {
            Optional<IdentityProvider> idp = mappedProvider(s, domain.get());
            if (idp.isPresent()) {
                providerId = idp.get().id();
                if (idp.get().type() == IdentityProviderType.OIDC) {
                    authMethod = "OIDC";
                }
            }
        }
        ctx.json(new CheckDomainLegacyResponse(domain.orElse(""), authMethod, providerId, null));
    }

    // ── /auth/login ────────────────────────────────────────────────────────

    /// `rememberMe` is parsed and unused, as in Go.
    record LoginRequest(String email, String password, Boolean rememberMe) {
    }

    private static void login(Exchange ctx, State s) {
        var req = decode(ctx, LoginRequest.class);
        String email = req.email() == null ? "" : req.email().trim().toLowerCase(Locale.ROOT);
        String password = req.password() == null ? "" : req.password();
        if (email.isEmpty() || password.isEmpty()) {
            unauthorized(ctx, "Invalid credentials"); // constant shape — which field is missing is not revealed
            return;
        }
        String ip = ClientIp.of(ctx);
        Instant now = s.clock().instant();

        if (s.backoff() != null) {
            BackoffCheck.Decision d;
            try {
                d = s.backoff().check(email, ip, now);
            } catch (RuntimeException e) {
                // Fail closed (ruling C-Q23): a backoff-store error during a
                // brute force must not switch the lock off.
                LOG.error("login backoff check failed; refusing login", e);
                AuthAlarms.backoffStoreError();
                backoffUnavailable(ctx);
                return;
            }
            if (!d.allowed()) {
                tooManyRequests(ctx, d.retryAfterSecs()); // nothing recorded: the timeline freezes (spec §4)
                return;
            }
        }

        // SSO enforcement: a domain mapped to an OIDC provider signs in
        // through it; the method is public knowledge via check-domain.
        Optional<String> domain = domainOf(email);
        if (domain.isPresent()) {
            Optional<IdentityProvider> idp = mappedProvider(s, domain.get());
            if (idp.isPresent() && idp.get().type() == IdentityProviderType.OIDC) {
                record(s, AttemptOutcome.FAILURE, email, null, ip, "SSO required");
                HttpError.write(ctx, 403, "SSO_REQUIRED",
                        "This email domain signs in through its identity provider; password login is disabled", Map.of());
                return;
            }
        }

        Optional<Principal> found = s.principals().findByEmail(email);
        Principal p = found.orElse(null);
        String storedHash = p == null || p.userIdentity() == null ? null : p.userIdentity().passwordHash();
        if (p == null || !p.active() || storedHash == null) {
            // Spend the same Argon2id cost as a real verify so the response
            // time does not reveal whether this e-mail is registered.
            PasswordHash.equalizeTiming(password);
            record(s, AttemptOutcome.FAILURE, email, null, ip, "Invalid credentials");
            unauthorized(ctx, "Invalid credentials");
            return;
        }
        if (PasswordHash.verify(password, storedHash) != PasswordHash.Verification.OK) {
            record(s, AttemptOutcome.FAILURE, email, null, ip, "Invalid credentials");
            unauthorized(ctx, "Invalid credentials");
            return;
        }
        if (PasswordHash.needsRehash(storedHash)) {
            rehash(s, p, password);
        }

        Optional<MfaChallenge.Challenge> challenge;
        try {
            challenge = s.mfa().evaluate(p, ctx);
        } catch (RuntimeException e) {
            // Fail closed: an evaluation error denies rather than bypassing 2FA.
            LOG.atError().setMessage("MFA evaluation failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.writeLoginSurface(ctx, 500, "MFA_EVAL_FAILED", "could not evaluate second factor");
            return;
        }
        if (challenge.isPresent()) {
            ctx.status(200).json(challenge.get().body());
            return;
        }
        completeLogin(ctx, s, p, ip);
    }

    /// Mint the cookie, record the success, answer the login response.
    /// Public so the 2FA and passkey flows complete a login the same way.
    public static void completeLogin(Exchange ctx, State s, Principal p, String ip) {
        completeLogin(ctx, s, p, ip, null);
    }

    /// [#completeLogin(Context, State, Principal, String)], with a first
    /// recovery-code set to show once (§6.6 enrol-and-complete) — `null` or
    /// empty when this login minted none.
    public static void completeLogin(Exchange ctx, State s, Principal p, String ip, List<String> recoveryCodes) {
        String token;
        try {
            token = s.issuer().sessionToken(p.id(), p.email());
        } catch (RuntimeException e) {
            // The credentials verified; a mint failure is a server-side
            // fault, reported as such with the cause kept off the wire.
            LOG.atError().setMessage("session token mint failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            HttpError.write(ctx, 500, "MINT_FAILED", "failed to mint session token", Map.of());
            return;
        }
        s.cookie().set(ctx, token);
        record(s, AttemptOutcome.SUCCESS, p.email() == null ? "" : p.email().toLowerCase(Locale.ROOT), p.id(), ip, null);
        ctx.status(200).json(loginResponse(s, p, recoveryCodes));
    }

    // ── /auth/logout ───────────────────────────────────────────────────────

    private static void logout(Exchange ctx, State s) {
        s.cookie().clear(ctx);
        ctx.status(204);
    }

    // ── /auth/me ───────────────────────────────────────────────────────────

    private static void me(Exchange ctx, State s) {
        Optional<AuthContext> ac = Auth.currentOptional();
        if (ac.isEmpty() || ac.get().principalId().isBlank()) {
            unauthorized(ctx, "Not authenticated");
            return;
        }
        // Re-load so name / active / roles are fresh, not what a token was stamped with.
        Optional<Principal> p = s.principals().findById(ac.get().principalId());
        if (p.isEmpty() || !p.get().active()) {
            unauthorized(ctx, "Not authenticated");
            return;
        }
        ctx.json(loginResponse(s, p.get()));
    }

    // ── /auth/login-history ────────────────────────────────────────────────

    record HistoryEntry(String attemptType, String outcome, String failureReason, String ipAddress, String userAgent,
                        Instant attemptedAt) {
        static HistoryEntry of(LoginAttempt a) {
            return new HistoryEntry(a.attemptType().name(), a.outcome().name(), a.failureReason(), a.ipAddress(),
                    a.userAgent(), a.attemptedAt());
        }
    }

    private static void loginHistory(Exchange ctx, State s) {
        Optional<AuthContext> ac = Auth.currentOptional();
        if (ac.isEmpty() || ac.get().principalId().isBlank()) {
            unauthorized(ctx, "Not authenticated");
            return;
        }
        List<HistoryEntry> out = List.of();
        if (s.attempts() != null) {
            Optional<Principal> p = s.principals().findById(ac.get().principalId());
            String email = p.map(Principal::email).orElse(null);
            if (email != null && !email.isBlank()) {
                out = s.attempts().findRecentByIdentifier(email.toLowerCase(Locale.ROOT), HISTORY_LIMIT)
                        .stream().map(HistoryEntry::of).toList();
            }
        }
        ctx.json(Map.of("attempts", out));
    }

    // ── the login response (§6.1a) ─────────────────────────────────────────

    /// `clientId` is always present (`null` when none), as Go's pointer field
    /// serialises; the mapper's default would omit it.
    record LoginResponse(String status, String principalId, String name, String email, List<String> roles,
                         List<String> permissions,
                         @JsonInclude(JsonInclude.Include.ALWAYS) String clientId,
                         List<String> recoveryCodes, boolean ssoManaged) {
    }

    static LoginResponse loginResponse(State s, Principal p) {
        return loginResponse(s, p, null);
    }

    /// [#loginResponse(State, Principal)] with the first recovery-code set to
    /// show once (§6.6); omitted (not an empty array) when `null` or empty —
    /// the field carries no `@JsonInclude` of its own, so an empty list would
    /// otherwise still be written under the mapper's `NON_ABSENT` default.
    static LoginResponse loginResponse(State s, Principal p, List<String> recoveryCodes) {
        List<String> permissions;
        try {
            permissions = permissionList(s.resolver().resolveSession(p.id()).map(AuthContext::permissions).orElse(List.of()));
        } catch (RuntimeException e) {
            // Signed in, but the authority could not be loaded: an empty
            // list, not a 500 — the user can refresh.
            LOG.atWarn().setMessage("claims resolution failed after login")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
            permissions = List.of();
        }
        List<String> codes = recoveryCodes == null || recoveryCodes.isEmpty() ? null : recoveryCodes;
        return new LoginResponse("ok", p.id(), p.name(), p.email() == null ? "" : p.email(), p.roleNames(),
                permissions, p.clientId(), codes, ssoManaged(s, p));
    }

    /// The flattened permissions plus the literal `"*"` when the set holds
    /// the platform wildcard — what the SPA's route guards key on.
    static List<String> permissionList(List<String> permissions) {
        if (!permissions.contains("platform:*:*:*")) {
            return permissions;
        }
        var out = new ArrayList<>(permissions);
        out.add("*");
        return List.copyOf(out);
    }

    /// An external identity, or an e-mail domain mapped to an OIDC provider.
    /// Public: change-password closes on the same rule (auth-identity §4.10, §6.8).
    public static boolean ssoManaged(State s, Principal p) {
        if (p.externalIdentity() != null) {
            return true;
        }
        if (p.email() == null) {
            return false;
        }
        return domainOf(p.email())
                .flatMap(d -> mappedProvider(s, d))
                .map(idp -> idp.type() == IdentityProviderType.OIDC)
                .orElse(false);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static <T> T decode(Exchange ctx, Class<T> type) {
        try {
            return Json.MAPPER.readValue(ctx.body(), type);
        } catch (JacksonException e) {
            throw HttpError.invalidJson(e.getOriginalMessage() == null ? "malformed request body" : e.getOriginalMessage());
        }
    }

    /// The lower-cased domain of `email`; empty when there is no `@` or nothing after it.
    static Optional<String> domainOf(String email) {
        if (email == null) return Optional.empty();
        int at = email.indexOf('@');
        if (at < 0 || at == email.length() - 1) return Optional.empty();
        return Optional.of(email.substring(at + 1).toLowerCase(Locale.ROOT));
    }

    private static Optional<IdentityProvider> mappedProvider(State s, String domain) {
        try {
            return s.mappings().findByEmailDomain(domain)
                    .flatMap(m -> s.identityProviders().findById(m.identityProviderId()));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("domain lookup failed")
                    .addKeyValue("domain", domain)
                    .setCause(e)
                    .log();
            return Optional.empty();
        }
    }

    /// Go's `encodeURI`: keeps `@-_.~`, percent-encodes the rest.
    static String encodeUri(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%40", "@").replace("%7E", "~");
    }

    private static void record(State s, AttemptOutcome outcome, String identifier, String principalId, String ip, String reason) {
        if (s.attempts() == null) return;
        try {
            s.attempts().recordAttempt(LoginAttempt.attempt(AttemptType.USER_LOGIN, outcome, reason, identifier, principalId,
                    ip == null || ip.isBlank() ? null : ip, null));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("recording login attempt failed")
                    .addKeyValue("identifier", identifier)
                    .addKeyValue("outcome", outcome)
                    .setCause(e)
                    .log();
        }
    }

    /// Transparent migration to the native scheme now the plaintext is in
    /// hand. Best-effort: a persist failure must not block a valid login.
    private static void rehash(State s, Principal p, String password) {
        try (Connection conn = s.writes().getConnection()) {
            conn.setAutoCommit(false);
            try {
                s.principals().persist(p.withPasswordHash(PasswordHash.hash(password)), DbTx.wrapForBootstrap(conn));
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
        } catch (RuntimeException | SQLException e) {
            LOG.atWarn().setMessage("password rehash persist failed; login continues")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
        }
    }

    private static void unauthorized(Exchange ctx, String message) {
        ctx.header("WWW-Authenticate", "Cookie realm=\"" + SessionCookie.NAME + "\"");
        HttpError.writeLoginSurface(ctx, 401, "UNAUTHENTICATED", message);
    }

    private static void tooManyRequests(Exchange ctx, long retryAfterSecs) {
        ctx.header("Retry-After", Long.toString(retryAfterSecs));
        HttpError.writeLoginSurface(ctx, 429, "TOO_MANY_REQUESTS", "too many failed login attempts; try again later");
    }

    private static void backoffUnavailable(Exchange ctx) {
        HttpError.writeLoginSurface(ctx, 503, "BACKOFF_UNAVAILABLE", "login is temporarily unavailable; try again shortly");
    }
}
