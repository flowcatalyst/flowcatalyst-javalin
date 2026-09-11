package io.flowcatalyst.platform.shared.auth;

import java.util.List;
import java.util.Objects;

/// The authenticated principal for one request (Go `auth.AuthContext`).
/// Attached by [Authenticator]; handlers and use cases read it via
/// [Auth#current()] / [Auth#from].
///
/// The boolean queries fail closed on the *absence* of a context: callers
/// that may hold `null` (Go's nil receiver) go through the static helpers in
/// [Checks], which treat `null` as unauthenticated.
///
/// @param principalId     the acting principal's id (JWT `sub`)
/// @param principalType   `USER` / `SERVICE` from the `type` claim; `null` when the
///                        transport did not carry it (session cookie, test headers).
///                        Not part of Go's `AuthContext`; carried here because the
///                        claims have it and handlers should not re-parse tokens.
/// @param scope           tenancy tier; `null` when absent / unrecognised
/// @param email           user email; `null` / blank for service principals
/// @param name            display name from the `name` claim; `null` when not carried
/// @param clients         tenant ids this principal can access, verbatim from the
///                        claim (authservice emits `["*"]` for anchors and
///                        `id:identifier` pairs otherwise — the Go middleware
///                        passes them through unchanged, so does this one)
/// @param roles           assigned role codes
/// @param applications    explicit application ids; ignored when [#allApplications]
/// @param allApplications access to every application, present and future
/// @param permissions     flattened permission codes (4-segment, `*` wildcards allowed)
/// @param tokenUse        the `token_use` claim (`api` / `identity` / `null`)
public record AuthContext(
        String principalId,
        PrincipalType principalType,
        Scope scope,
        String email,
        String name,
        List<String> clients,
        List<String> roles,
        List<String> applications,
        boolean allApplications,
        List<String> permissions,
        String tokenUse) {

    public AuthContext {
        Objects.requireNonNull(principalId, "principalId");
        clients = clients == null ? List.of() : List.copyOf(clients);
        roles = roles == null ? List.of() : List.copyOf(roles);
        applications = applications == null ? List.of() : List.copyOf(applications);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    /// Identity + authority only — for contexts that did not come off a token
    /// (session resolution, test headers): no principal type, name or token use.
    public AuthContext(String principalId, Scope scope, String email, List<String> clients, List<String> roles,
                       List<String> applications, boolean allApplications, List<String> permissions) {
        this(principalId, null, scope, email, null, clients, roles, applications, allApplications, permissions, null);
    }

    /// Anchor scope.
    public boolean isAnchor() {
        return scope == Scope.ANCHOR;
    }

    /// Holds the super-admin wildcard `platform:*:*:*`.
    public boolean isSuperAdmin() {
        return hasPermission(Permission.SUPER_ADMIN);
    }

    /// Access to a specific tenant: anchors always; otherwise the id must be in [#clients].
    public boolean canAccessClient(String clientId) {
        return isAnchor() || clients.contains(clientId);
    }

    /// Whose view a tenant-scoped list read is: an anchor sees
    /// [Visibility.Everything]; anyone else platform-scoped rows plus its own
    /// [#clients] ([Visibility.Tenants]) — enforced in SQL by the repositories.
    public Visibility visibility() {
        return isAnchor() ? Visibility.Everything.INSTANCE : new Visibility.Tenants(clients);
    }

    /// Restricted to an explicit application list (no all-applications access).
    public boolean isApplicationScoped() {
        return !allApplications;
    }

    /// Access to a specific application: all-applications, or the id is in [#applications].
    public boolean canAccessApplication(String applicationId) {
        return allApplications || applications.contains(applicationId);
    }

    /// A copy with [#principalType] overridden — every other field carried
    /// through unchanged. Used by the [Authenticator] to stamp `USER` on a
    /// session-cookie resolution regardless of which [ClaimsResolver] built
    /// it (`docs/spec/portal-apps.md` §6, Part A J6).
    public AuthContext withPrincipalType(PrincipalType type) {
        return new AuthContext(principalId, type, scope, email, name, clients, roles, applications, allApplications, permissions, tokenUse);
    }

    /// Whether a held permission satisfies `permission` (wildcard-aware, see [Permission#matches]).
    public boolean hasPermission(Permission permission) {
        return permission.grantedBy(permissions);
    }

    /// [#hasPermission(Permission)] for a raw code — the token-scope path, where
    /// the required side is a string the caller did not mint.
    public boolean hasPermission(String code) {
        return Permission.grants(permissions, code);
    }
}
