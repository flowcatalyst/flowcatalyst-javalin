package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import org.slf4j.MDC;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The platform's authentication middleware (Go `middleware.Authenticator`),
/// a Javalin [Handler] for `cfg.routes.before(...)`.
///
/// Decision tree, per request:
///
/// 1. **Token extraction.** `Authorization` present →
///    `Bearer <token>` (scheme case-insensitive) yields the token, *from
///    header*; any other scheme yields **no token** and the cookie is NOT
///    consulted (the request declared its intent). No `Authorization` →
///    the configured session cookie ([Config#sessionCookieName]), *from
///    cookie*, if present.
/// 2. **Token present** → verify with [JwtVerifier] (RS256 current + previous
///    keys, or HS256; `iss`, `aud`, `exp`, `nbf`, `sub`):
///    - *from cookie*: the claims carry identity only; the mutable authority
///      (scope, roles, clients, applications, permissions) is re-resolved via
///      [ClaimsResolver#resolveSession] on every request. A verification
///      failure, or an unknown / deactivated principal, **degrades to
///      unauthenticated** — the browser replays a stale cookie on every call,
///      including the public login routes.
///    - *from header*: `token_use == "identity"` is rejected (an interactive
///      login token carries no authority); a missing marker (legacy) is
///      accepted. Permissions come from the `scope` claim, or — when the
///      token carries roles but no scope — from
///      [ClaimsResolver#flattenPermissions]. Any verification failure is a
///      **401** with the `invalid_token` body + `WWW-Authenticate` header and
///      the remaining handlers are skipped (unless `ignoreInvalidTokens`,
///      which strips the token and proceeds unauthenticated).
/// 3. **No token** and `allowTestHeaders` and `X-FC-Test-Principal` set →
///    a dev-only context from `X-FC-Test-{Principal,Scope,Clients,Permissions,
///    Roles,Email,Applications,All-Applications}`. Scope defaults to `CLIENT`;
///    all-applications defaults to `true`, flips to `false` when an
///    `X-FC-Test-Applications` list is given, and `X-FC-Test-All-Applications`
///    overrides explicitly.
/// 4. Otherwise the request proceeds **unauthenticated** — the per-handler
///    [Checks] reject it with `UNAUTHENTICATED` (403).
///
/// On success the context is bound with [Auth#bind] and `principal_id` is put
/// on the MDC; [Auth#scoped] then exposes it as [Auth#CURRENT] to route code.
public final class Authenticator implements Handler {

    public static final String TEST_PRINCIPAL = "X-FC-Test-Principal";
    public static final String TEST_SCOPE = "X-FC-Test-Scope";
    public static final String TEST_CLIENTS = "X-FC-Test-Clients";
    public static final String TEST_PERMISSIONS = "X-FC-Test-Permissions";
    public static final String TEST_ROLES = "X-FC-Test-Roles";
    public static final String TEST_EMAIL = "X-FC-Test-Email";
    public static final String TEST_APPLICATIONS = "X-FC-Test-Applications";
    public static final String TEST_ALL_APPLICATIONS = "X-FC-Test-All-Applications";
    /// `docs/spec/portal-apps.md` §6, Part A J6: the profile-only gate needs a
    /// principal type from dev/test contexts too; absent ⇒ `null` ⇒ exempt.
    public static final String TEST_PRINCIPAL_TYPE = "X-FC-Test-Principal-Type";

    /// Go's `errIdentityTokenNotAPICredential`, verbatim.
    public static final String IDENTITY_TOKEN_REJECTED =
            "this access token was issued for interactive login and cannot authorize API requests; "
                    + "obtain an API token via the client_credentials grant";

    /// `allowTestHeaders` enables the `X-FC-Test-Principal` dev bypass (never
    /// in production); `ignoreInvalidTokens` flips the bad-bearer 401 into
    /// "strip and proceed unauthenticated"; `sessionCookieName` is the ONE
    /// name [#extractToken] reads the session cookie under.
    ///
    /// Cookie security is its own setting (`docs/spec/cookie-hardening.md`
    /// §3) — no longer implied here from `allowTestHeaders`. The composition
    /// root derives `sessionCookieName` from the SAME `cookiesSecure`
    /// decision that builds every `SessionCookie` it mints (`Platform`
    /// cannot hand this record a `SessionCookie` itself: that type lives in
    /// a feature package this one must not depend on, so the name crosses
    /// as a plain `String`), so the mint side and the enforcement side can
    /// never drift. In secure mode the plain `fc_session` a subdomain could
    /// plant is never accepted.
    /// The session cookie's names — declared here, beside the reader, because `shared.auth` must
    /// not depend on the login package; `SessionCookie` uses these, so there is one spelling.
    public static final String SECURE_SESSION_COOKIE = "__Host-fc_session";
    public static final String INSECURE_SESSION_COOKIE = "fc_session";

    public record Config(boolean allowTestHeaders, boolean ignoreInvalidTokens, String sessionCookieName) {
        public Config {
            Objects.requireNonNull(sessionCookieName, "sessionCookieName");
        }

        /// The deployed default: test headers off, the secure cookie name.
        /// `Platform` does not use this constant directly — it derives the
        /// name from its own `cookiesSecure` so the two stay pinned together
        /// even if a future environment ever decoupled them; this is the
        /// shape a test wants when it means "exactly what a real deployment
        /// enforces".
        public static final Config PRODUCTION = new Config(false, false, SECURE_SESSION_COOKIE);

        /// Test/dev convenience: `sessionCookieName` is the plain
        /// `fc_session` name every test's own `SessionCookie(false, ...)`
        /// mints, independent of `allowTestHeaders` — a test asserting
        /// cookie-name enforcement itself uses [#of(boolean, String)].
        public static Config of(boolean allowTestHeaders) {
            return new Config(allowTestHeaders, false, INSECURE_SESSION_COOKIE);
        }

        public static Config of(boolean allowTestHeaders, String sessionCookieName) {
            return new Config(allowTestHeaders, false, sessionCookieName);
        }
    }

    /// A token and where it came from.
    record Extracted(String token, boolean fromCookie) {
    }

    /// What a presented token amounts to. `Anonymous` is the cookie path's
    /// "valid token, no principal behind it"; `Rejected` carries the text the
    /// 401 body shows when the caller is not allowed to degrade.
    private sealed interface Outcome permits Authenticated, Anonymous, Rejected {
    }

    private record Authenticated(AuthContext context) implements Outcome {
    }

    private record Anonymous() implements Outcome {
    }

    private record Rejected(String reason) implements Outcome {
    }

    private final JwtVerifier verifier;
    private final ClaimsResolver resolver;
    private final Config config;

    public Authenticator(JwtVerifier verifier, ClaimsResolver resolver, Config config) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void handle(Exchange ctx) {
        var extracted = extractToken(ctx);
        if (extracted.isPresent()) {
            var token = extracted.get();
            switch (introspect(token)) {
                case Authenticated(var ac) -> attach(ctx, ac);
                case Anonymous() -> { }
                case Rejected(var reason) -> {
                    // A stale cookie is a graceful logout, not a 401: the browser replays it on every
                    // call, including the public login routes. A bad bearer is the client's explicit claim.
                    if (!token.fromCookie() && !config.ignoreInvalidTokens()) {
                        HttpError.writeInvalidToken(ctx, reason);
                        ctx.skipRemainingHandlers();
                    }
                }
            }
            return;
        }
        var testPrincipal = ctx.header(TEST_PRINCIPAL);
        if (config.allowTestHeaders() && testPrincipal != null && !testPrincipal.isEmpty()) {
            attach(ctx, buildTestAuthContext(ctx));
        }
    }

    private static void attach(Exchange ctx, AuthContext ac) {
        Auth.bind(ctx, ac);
        MDC.put(CorrelationId.MDC_PRINCIPAL_KEY, ac.principalId());
    }

    /// The bearer (scheme case-insensitive, value trimmed) or the configured
    /// session cookie ([Config#sessionCookieName]). A non-Bearer
    /// `Authorization` header yields nothing and blocks the cookie fallback.
    /// An empty token counts as none. Instance-scoped (not `static`): which
    /// cookie name counts is per-`Config`, not fixed platform-wide — in
    /// secure mode a plain `fc_session` is a DIFFERENT cookie, not this
    /// one's stale value, and must not be read at all.
    private Optional<Extracted> extractToken(Exchange ctx) {
        var h = ctx.header("Authorization");
        if (h != null && !h.isEmpty()) {
            var prefix = "Bearer ";
            if (h.length() > prefix.length() && h.regionMatches(true, 0, prefix, 0, prefix.length())) {
                var token = h.substring(prefix.length()).trim();
                return token.isEmpty() ? Optional.empty() : Optional.of(new Extracted(token, false));
            }
            return Optional.empty();
        }
        var cookie = ctx.cookie(config.sessionCookieName());
        if (cookie != null) {
            var token = cookie.trim();
            return token.isEmpty() ? Optional.empty() : Optional.of(new Extracted(token, true));
        }
        return Optional.empty();
    }

    /// Verify, then project the claims onto an [AuthContext] according to
    /// where the token came from.
    private Outcome introspect(Extracted token) {
        return switch (verifier.verify(token.token())) {
            case JwtVerifier.Rejected(var reason) -> new Rejected(reason);
            case JwtVerifier.Verified(var claims) -> token.fromCookie() ? session(claims) : bearer(claims);
        };
    }

    private Outcome session(TokenClaims claims) {
        // Session-cookie authentication always carries PrincipalType.USER
        // (`docs/spec/portal-apps.md` §6, Part A J6) — stamped here, not left
        // to whichever ClaimsResolver produced the context, so the invariant
        // holds for every implementation (including test stubs). The
        // credential is stamped the same way: this is the only place a
        // context becomes SESSION_COOKIE.
        return resolver.resolveSession(claims.subject())
                .map(ac -> ac.withPrincipalType(PrincipalType.USER).withCredential(AuthContext.Credential.SESSION_COOKIE))
                .<Outcome>map(Authenticated::new)
                .orElseGet(Anonymous::new);
    }

    private Outcome bearer(TokenClaims claims) {
        if (TokenClaims.TOKEN_USE_IDENTITY.equals(claims.tokenUse())) {
            return new Rejected(IDENTITY_TOKEN_REJECTED);
        }
        var perms = claims.permissions();
        if (perms.isEmpty() && !claims.roles().isEmpty()) {
            var derived = resolver.flattenPermissions(claims.roles());
            if (derived != null && !derived.isEmpty()) perms = derived;
        }
        // The boundary where a token becomes an AuthContext, and the only
        // place the "{id}:{label}" claim form is understood — see [ScopeClaim].
        // Everything inward reasons in bare ids.
        var clients = ScopeClaim.parse(claims.clients());
        var applications = ScopeClaim.parse(claims.applications());
        return new Authenticated(new AuthContext(
                claims.subject(),
                PrincipalType.parse(claims.principalType()),
                Scope.parse(claims.tier()),
                claims.email(),
                claims.name(),
                // Go keeps the anchor wildcard verbatim in AuthContext.Clients and
                // /api/me echoes it (parity S2); visibility is by tier, so it is inert.
                clients.wildcard() ? List.of(ScopeClaim.WILDCARD) : clients.ids(),
                claims.roles(),
                applications.ids(),
                // The wildcard is the claim's own way of saying "every
                // application"; `all_applications` is the older boolean. Either
                // grants it, so a token carrying only one of them still works.
                claims.allApplications() || applications.wildcard(),
                perms,
                claims.tokenUse(),
                AuthContext.Credential.BEARER_TOKEN));
    }

    /// The dev-only context from the `X-FC-Test-*` headers — only reachable
    /// when `allowTestHeaders`.
    static AuthContext buildTestAuthContext(Exchange ctx) {
        var scopeHeader = header(ctx, TEST_SCOPE);
        var scope = scopeHeader.isEmpty() ? Scope.CLIENT : Scope.parse(scopeHeader);
        var apps = splitCsv(header(ctx, TEST_APPLICATIONS));
        var allAppsHeader = header(ctx, TEST_ALL_APPLICATIONS);
        var allApps = allAppsHeader.isEmpty() ? apps.isEmpty() : allAppsHeader.equals("true");
        // Only X-FC-Test-Principal-Type carries a type here (absent ⇒ null ⇒
        // exempt from the profile-only gate) — dev/test contexts are not
        // bearer tokens, so there is no `type` claim to fall back to.
        var principalType = PrincipalType.parse(header(ctx, TEST_PRINCIPAL_TYPE));
        return new AuthContext(
                header(ctx, TEST_PRINCIPAL),
                principalType,
                scope,
                header(ctx, TEST_EMAIL),
                null,
                splitCsv(header(ctx, TEST_CLIENTS)),
                splitCsv(header(ctx, TEST_ROLES)),
                apps,
                allApps,
                splitCsv(header(ctx, TEST_PERMISSIONS)),
                null,
                AuthContext.Credential.TEST_HEADERS);
    }

    private static String header(Exchange ctx, String name) {
        var v = ctx.header(name);
        return v == null ? "" : v;
    }

    /// Comma-separated, empties dropped, remaining parts trimmed.
    static List<String> splitCsv(String s) {
        if (s == null || s.isEmpty()) return List.of();
        return Arrays.stream(s.split(",", -1))
                .filter(part -> !part.isEmpty())
                .map(String::trim)
                .toList();
    }
}
