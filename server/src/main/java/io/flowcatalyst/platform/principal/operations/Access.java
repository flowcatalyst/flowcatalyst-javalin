package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;

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

    /// The by-id user-management rule: [#blockNonClientTarget] plus
    /// [Checks#checkScopeAccess] against the target's home client (a clientless
    /// target is anchor-only). Used by update / activate / deactivate / delete.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED` | `FORBIDDEN` | `SCOPE_FORBIDDEN`
    public static void requireManageable(Principal p) {
        AuthContext ac = Auth.current();
        blockNonClientTarget(ac, p);
        Checks.checkScopeAccess(ac, p.clientId());
    }

    /// The user-administration rule: [Checks#requireUserAdmin] against the
    /// target's home client (anchors pass; a non-anchor must reach the client
    /// and hold a write permission; clientless → `ANCHOR_REQUIRED`) plus
    /// [#blockNonClientTarget]. Used by role and application-access assignment.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED` | `ANCHOR_REQUIRED` | `SCOPE_FORBIDDEN` | `PERMISSION_REQUIRED` | `FORBIDDEN`
    public static void requireUserAdmin(Principal p) {
        AuthContext ac = Auth.current();
        Checks.requireUserAdmin(ac, p.clientId());
        blockNonClientTarget(ac, p);
    }

    /// A principal manages its own developer credential unconditionally;
    /// anyone else's needs [#requireUserAdmin].
    static void requireSelfOrUserAdmin(Principal p) {
        AuthContext ac = Auth.current();
        if (ac != null && ac.principalId().equals(p.id())) return;
        requireUserAdmin(p);
    }
}
