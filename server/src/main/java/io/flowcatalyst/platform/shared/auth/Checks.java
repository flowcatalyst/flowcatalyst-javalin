package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The authorization checks every handler uses (Go `auth` package, the
/// `Require*` / `Can*` half). Conventions:
///
///   - `require(ac, Permission)` for a single permission (`VIEW` on GET,
///     the verb on a verb), `requireAny(ac, Permission...)` for the "any
///     write" / sync groupings, `requireAnchor(ac)` for anchor-only
///     resources (clients, identity providers), `requireAdmin(ac)` (Go
///     `IsAdmin`) for anchor OR the super-admin wildcard.
///   - Every helper takes the (possibly `null`) [AuthContext] and **throws**
///     `UseCaseException.authorization(CODE, message)` on failure — the HTTP
///     layer renders it as the 403 envelope — so handlers need no branching.
///     Codes: `UNAUTHENTICATED`, `ANCHOR_REQUIRED`, `ADMIN_REQUIRED`,
///     `SCOPE_FORBIDDEN`, `PERMISSION_REQUIRED`, `FORBIDDEN`.
///   - Anchors pass every permission check; otherwise the required code is
///     matched against held permissions with `*` segment wildcards
///     ([Permission#matches]).
///
/// Which permissions gate which endpoint is each aggregate's business (its
/// spec and its `api/` class) — nothing per-resource lives here.
public final class Checks {

    private Checks() {
    }

    // ── Core ───────────────────────────────────────────────────────────────

    private static UseCaseException unauthenticated() {
        return UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
    }

    /// Fails unless the principal holds `permission` (anchors always do).
    ///
    /// @throws UseCaseException `UNAUTHENTICATED` | `PERMISSION_REQUIRED`
    ///                          `permission required: <code>`
    public static void require(AuthContext a, Permission permission) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor() || a.hasPermission(permission)) return;
        throw UseCaseException.authorization("PERMISSION_REQUIRED", "permission required: " + permission.code());
    }

    /// Fails unless the principal holds at least one of `permissions`
    /// (anchors always do).
    ///
    /// @throws UseCaseException `UNAUTHENTICATED` | `PERMISSION_REQUIRED`
    ///                          `one of: <code>, <code>…`
    public static void requireAny(AuthContext a, Permission... permissions) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor() || Arrays.stream(permissions).anyMatch(a::hasPermission)) return;
        throw UseCaseException.authorization("PERMISSION_REQUIRED", "one of: "
                + Arrays.stream(permissions).map(Permission::code).collect(Collectors.joining(", ")));
    }

    /// Fails unless the principal is anchor-scoped.
    public static void requireAnchor(AuthContext a) {
        if (a == null) throw unauthenticated();
        if (!a.isAnchor()) throw UseCaseException.authorization("ANCHOR_REQUIRED", "anchor scope required");
    }

    /// Go `IsAdmin`: anchor-scoped or holding the super-admin wildcard.
    public static void requireAdmin(AuthContext a) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor() || a.hasPermission(SUPER_ADMIN)) return;
        throw UseCaseException.authorization("ADMIN_REQUIRED", "admin permission required");
    }

    /// Authorizes a user-management action on a principal owned by
    /// `targetClientId`: anchors pass for any target; a non-anchor must be able
    /// to access the target's client AND hold a user-write permission; a `null`
    /// target (platform user) is anchor-only.
    public static void requireUserAdmin(AuthContext a, String targetClientId) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor()) return;
        if (targetClientId == null) {
            throw UseCaseException.authorization("ANCHOR_REQUIRED", "anchor scope required for platform users");
        }
        if (!a.canAccessClient(targetClientId)) {
            throw UseCaseException.authorization("SCOPE_FORBIDDEN", "no access to this user's client");
        }
        requireAny(a, USER_CREATE, USER_UPDATE, USER_DELETE);
    }

    // ── Scope ──────────────────────────────────────────────────────────────

    /// Whether the caller may access a resource owned by `clientId` (`null` =
    /// platform-level → anchor / super-admin only). The boolean form of
    /// [#checkScopeAccess], for filtering list results.
    public static boolean canAccessScope(AuthContext a, String clientId) {
        if (a == null) return false;
        if (clientId != null) return a.canAccessClient(clientId);
        return a.isAnchor() || a.isSuperAdmin();
    }

    /// Go `FilterClientScoped`: keeps platform-scoped items (`null` client id)
    /// and client-scoped items the caller can access.
    public static <T> List<T> filterClientScoped(AuthContext a, List<T> items, Function<T, String> clientId) {
        return items.stream()
                .filter(item -> {
                    var cid = clientId.apply(item);
                    return cid == null || (a != null && a.canAccessClient(cid));
                })
                .toList();
    }

    /// Per-resource scope on a by-id operation, on top of the coarse
    /// [#require] check: a client-scoped resource requires access to that
    /// client; a platform-level resource (`null`) requires anchor or super-admin.
    public static void checkScopeAccess(AuthContext a, String clientId) {
        if (a == null) throw unauthenticated();
        if (canAccessScope(a, clientId)) return;
        if (clientId != null) {
            throw UseCaseException.authorization("SCOPE_FORBIDDEN", "no access to this resource's client");
        }
        throw UseCaseException.authorization("SCOPE_FORBIDDEN", "anchor scope required for this resource");
    }

    /// Application-scoped authorization for sync operations: the principal
    /// must be able to act for the application the sync is scoped to (the
    /// coarse sync permission and the code → id resolution are the
    /// handler's). `null` principal → `UNAUTHENTICATED`; no access →
    /// `FORBIDDEN` "Not authorised for application '<code>'".
    public static void checkApplicationAccess(AuthContext a, String applicationId, String applicationCode) {
        if (a == null) throw unauthenticated();
        if (!a.canAccessApplication(applicationId)) {
            throw UseCaseException.authorization("FORBIDDEN", "Not authorised for application '" + applicationCode + "'");
        }
    }

    // ── Portal users (CLIENT-delegable) ────────────────────────────────────

    /// Listing a client's portal identities: anchors pass; otherwise access to
    /// the client AND view-or-manage.
    public static void requirePortalUserView(AuthContext a, String clientId) {
        requireClientAccess(a, clientId);
        requireAny(a, PORTAL_USER_VIEW, PORTAL_USER_MANAGE);
    }

    /// Ensure/invite, suspend, delete a client's portal identities: anchors
    /// pass; otherwise access to the client AND manage.
    public static void requirePortalUserManage(AuthContext a, String clientId) {
        requireClientAccess(a, clientId);
        require(a, PORTAL_USER_MANAGE);
    }

    private static void requireClientAccess(AuthContext a, String clientId) {
        if (a == null) throw unauthenticated();
        if (a.canAccessClient(clientId)) return;
        throw UseCaseException.authorization("SCOPE_FORBIDDEN", "no access to this client");
    }
}
