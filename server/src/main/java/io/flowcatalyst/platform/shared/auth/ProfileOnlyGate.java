package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The profile-only gate (`docs/spec/portal-apps.md` §6): a principal
/// holding no platform role may reach only its own profile. Installed as a
/// `before` filter immediately after the [Authenticator] and before
/// request-schema validation, so it sees exactly the [AuthContext] the
/// authenticator bound (or none, for an unauthenticated request).
///
/// **Role-less** ⇔ [PrincipalType#USER] and both roles and permissions are
/// empty. `SERVICE` principals authorize through their applications claim,
/// never roles, so they are never role-less by this definition; an unknown
/// (`null`) type and an unauthenticated request (no context at all) pass
/// through untouched — every handler still runs its own checks.
///
/// **Allowlist**: any path starting `/auth/` or `/portal/`, and `GET
/// /api/me` exactly — every other method on `/api/me` (there are none today,
/// but the rule is method-specific, not path-specific) and every other path
/// are gated. A gated request never reaches its handler: this throws before
/// [Auth#scoped] wraps the route, in the platform envelope
/// (`{"error":"NO_PLATFORM_ROLE", ...}`), rendered by the same
/// `UseCaseException` → [HttpError] mapping every other 403 uses.
///
/// Rationale (spec §6): per-handler checks were insufficient — several
/// endpoints had no permission check at all, and an anchor-tier
/// short-circuit let a zero-role ANCHOR user through almost everything. This
/// filter closes both gaps for human users in one place.
public final class ProfileOnlyGate implements Handler {

    /// Stateless; one instance is enough for every request.
    public static final ProfileOnlyGate INSTANCE = new ProfileOnlyGate();

    public ProfileOnlyGate() {
    }

    @Override
    public void handle(Exchange ctx) {
        AuthContext ac = Auth.from(ctx);
        if (ac == null) {
            return; // unauthenticated: handlers apply their own checks
        }
        if (ac.principalType() != PrincipalType.USER) {
            return; // SERVICE, or an unknown/absent type — never gated here
        }
        if (!ac.roles().isEmpty() || !ac.permissions().isEmpty()) {
            return; // holds at least one role or permission — not role-less
        }
        if (allowlisted(ctx)) {
            return;
        }
        throw UseCaseException.authorization("NO_PLATFORM_ROLE",
                "Your account has no platform access. Only your profile is available.");
    }

    private static boolean allowlisted(Exchange ctx) {
        String path = ctx.path();
        if (path.startsWith("/auth/") || path.startsWith("/portal/")) {
            return true;
        }
        return "GET".equals(ctx.method()) && "/api/me".equals(path);
    }
}
