package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// Load-or-404 plus the per-resource authorization rules of spec §5.2 — the
/// opening of every by-id operation's execute phase, which is why those
/// operations declare `Authorize.publicAccess()`. The coarse permission
/// stays in the handler; these rules decide whether *this* principal may
/// act on *this* target.
///
/// Two not-found spellings exist because the wire pins them (spec §6, open
/// question 7): the user-management operations say `Principal_NOT_FOUND`,
/// the access operations say `User_NOT_FOUND`.
public final class Access {

    private Access() {
    }

    /// `Principal_NOT_FOUND` — update, delete, activate, deactivate, reset password.
    static Principal loadPrincipal(PrincipalRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("Principal", id));
    }

    /// `User_NOT_FOUND` — roles, grants, application access, association, developer credential.
    static Principal loadUser(PrincipalRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("User", id));
    }

    /// A non-anchor administrator may act only on `CLIENT`-scope principals;
    /// anchors are unrestricted. Client access alone is not enough — a
    /// PARTNER's home client may be reachable, yet partners and anchors are
    /// outside a client-admin's remit. Public: the handlers that gate before
    /// loading (send-password-reset, reset-2fa, reset-password) use it too.
    ///
    /// @throws UseCaseException authorization `FORBIDDEN`
    public static void blockNonClientTarget(AuthContext ac, Principal p) {
        if (ac != null && !ac.isAnchor() && p.scope() != UserScope.CLIENT) {
            throw UseCaseException.authorization("FORBIDDEN", "Client administrators can only manage client-scope users");
        }
    }

    /// The by-id user-management rule: [#blockNonClientTarget] plus the
    /// out-of-scope-is-not-found rule below. Used by update / activate /
    /// deactivate / delete.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED` | `FORBIDDEN`; not-found `Principal_NOT_FOUND`
    public static void requireManageable(Principal p) {
        AuthContext ac = Auth.current();
        blockNonClientTarget(ac, p);
        requireInScope(ac, p, "Principal");
    }

    /// The user-administration rule used by role and application-access
    /// assignment and the developer-credential admin branch:
    /// [#blockNonClientTarget], then the out-of-scope-is-not-found rule, then
    /// the coarse user-write permission — at **every** tier, anchors included
    /// (permissions come from roles; the anchor tier is reach, never
    /// authority — `docs/spec/permissions-from-roles.md`,
    /// `docs/spec/security-fixes-2026-09-24.md` S1.1). Order matters (ledger PR-3/PR-4, ruled 2026-09-01): a
    /// target outside the caller's client scope answers the SAME not-found a
    /// genuinely missing id would — never 403 — so an unauthorized caller
    /// cannot use the response to learn whether an id is real. A 403 is only
    /// ever returned for a target the caller CAN reach: the wrong kind of
    /// administrator ([#blockNonClientTarget]) or lacking the permission
    /// outright.
    ///
    /// `notFoundResource` MUST be the same wire spelling the *caller's own
    /// route* already answers with for a genuinely missing id — the whole
    /// point of PR-4 is that the two responses are indistinguishable, and a
    /// route whose handler pre-loads with `principal(s, id)` (`Principal`)
    /// before ever reaching this check must not suddenly say `User` here.
    /// [#requireUserAdmin(Principal)] is the `User` convenience for the
    /// routes that load no other way.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED` | `FORBIDDEN` | `PERMISSION_REQUIRED`; not-found `<notFoundResource>_NOT_FOUND`
    public static void requireUserAdmin(Principal p, String notFoundResource) {
        AuthContext ac = Auth.current();
        blockNonClientTarget(ac, p);
        requireInScope(ac, p, notFoundResource);
        Checks.requireAny(ac, Permission.USER_CREATE, Permission.USER_UPDATE, Permission.USER_DELETE);
    }

    /// The gate for changing a user's roles (owner ruling 2026-09-25, backlog
    /// §"Overnight review" item 14): the same scope rules as
    /// [#requireUserAdmin(Principal, String)], but the permission is
    /// `USER_ASSIGN_ROLES` itself. Holding user-create or user-delete no longer
    /// lets a caller change roles. [io.flowcatalyst.platform.role.RoleCeiling]
    /// then bounds which roles.
    public static void requireRoleAssigner(Principal p, String notFoundResource) {
        AuthContext ac = Auth.current();
        blockNonClientTarget(ac, p);
        requireInScope(ac, p, notFoundResource);
        Checks.require(ac, Permission.USER_ASSIGN_ROLES);
    }

    /// [#requireUserAdmin(Principal, String)] with the `User_NOT_FOUND`
    /// spelling — correct for the routes that load only inside the operation
    /// ([#loadUser]): the developer-credential admin branch and any future
    /// caller with no handler-level pre-load.
    public static void requireUserAdmin(Principal p) {
        requireUserAdmin(p, "User");
    }

    /// The out-of-scope-is-not-found rule (PR-4): a target the caller cannot
    /// reach answers the same not-found error a missing id would, never the
    /// `SCOPE_FORBIDDEN` 403 [Checks#checkScopeAccess] would raise elsewhere
    /// in the platform — a 403 here would be an existence oracle over the
    /// principal table. `resource` picks the wire spelling (`Principal` vs
    /// `User`) the caller's route already uses for a missing id.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED`; not-found `<resource>_NOT_FOUND`
    private static void requireInScope(AuthContext ac, Principal p, String resource) {
        if (ac == null) throw UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
        if (!Checks.canAccessScope(ac, p.clientId())) {
            throw UseCaseException.resourceNotFound(resource, p.id());
        }
    }

    /// The read-side scope gate for `/{id}` and its sub-routes (roles,
    /// application-access, available-applications, version): the same
    /// out-of-scope-is-not-found rule [#requireUserAdmin] enforces on writes,
    /// for a GET. The caller checks the self-read exemption first — this is
    /// for "anyone else's" only. `Principal_NOT_FOUND` (spec §6, open
    /// question 7 pins this spelling for the whole by-id family, reads included).
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED`; not-found `Principal_NOT_FOUND`
    public static void requireReadable(Principal p) {
        requireInScope(Auth.current(), p, "Principal");
    }

    /// Whether `ac` reaches `p` as its administrator — [#blockNonClientTarget]
    /// and the in-scope rule of [#requireUserAdmin] as a boolean, without the
    /// permission (a caller that already passed its route's coarse gate). The
    /// principal sync's rule for which existing principals it may touch
    /// (security-fixes S1.3).
    static boolean administers(AuthContext ac, Principal p) {
        if (ac == null) return false;
        if (!ac.isAnchor() && p.scope() != UserScope.CLIENT) return false;
        return Checks.canAccessScope(ac, p.clientId());
    }

    /// The application ids a client is entitled to (enabled client-configs) —
    /// the bound a non-anchor administrator is held to; empty for a clientless target.
    public static Set<String> clientApplicationIds(ClientConfigRepository configs, String clientId) {
        if (clientId == null || clientId.isEmpty()) return Set.of();
        return configs.findByClient(clientId).stream()
                .filter(ClientConfig::enabled).map(ClientConfig::applicationId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /// The first role in `roleNames` a non-anchor may not assign (spec §5.3),
    /// as the error the mutation would refuse with; empty when every role
    /// passes. Every role must exist, be application-scoped, and belong to an
    /// application in `allowed`.
    public static Optional<UseCaseError> assignableRolesProblem(RoleRepository roles, List<String> roleNames, Set<String> allowed) {
        for (String name : roleNames) {
            Optional<Role> role = roles.findByName(name);
            if (role.isEmpty()) {
                return Optional.of(new UseCaseError.Validation("UNKNOWN_ROLE", "role not found: " + name, Map.of()));
            }
            if (role.get().applicationId() == null) {
                return Optional.of(new UseCaseError.Authorization("PLATFORM_ROLE_FORBIDDEN",
                        "client administrators cannot assign platform roles", Map.of()));
            }
            if (!allowed.contains(role.get().applicationId())) {
                return Optional.of(new UseCaseError.Authorization("ROLE_APP_FORBIDDEN",
                        "role belongs to an application the client cannot access", Map.of()));
            }
        }
        return Optional.empty();
    }

    /// A principal manages its own developer credential unconditionally;
    /// anyone else's needs [#requireUserAdmin].
    static void requireSelfOrUserAdmin(Principal p) {
        AuthContext ac = Auth.current();
        if (ac != null && ac.principalId().equals(p.id())) return;
        requireUserAdmin(p);
    }
}
