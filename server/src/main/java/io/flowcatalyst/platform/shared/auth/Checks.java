package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static io.flowcatalyst.platform.shared.auth.Permissions.*;

/// The permission-check helpers every handler uses (Go `auth` package, the
/// `Require*` / `Can*` half). Conventions:
///
///   - `canRead<Resource>(ac)` for GET, `canCreate/Update/Delete` for the
///     verbs, `canWrite<Resource>` for any of create/update/delete,
///     `requireAnchor(ac)` for anchor-only endpoints, `requireAdmin(ac)`
///     (Go `IsAdmin`) for anchor OR the super-admin wildcard.
///   - Every helper takes the (possibly `null`) [AuthContext] and **throws**
///     `UseCaseException.authorization(CODE, message)` on failure — the HTTP
///     layer renders it as the 403 envelope — so handlers need no branching.
///     Codes: `UNAUTHENTICATED`, `ANCHOR_REQUIRED`, `ADMIN_REQUIRED`,
///     `SCOPE_FORBIDDEN`, `PERMISSION_REQUIRED`.
///   - Anchors pass every permission check; the typed checks match held
///     permissions with `*` segment wildcards ([Permissions#matches]).
///
/// All 73 Go `Can*` helpers are ported below, grouped as in Go.
public final class Checks {

    private Checks() {
    }

    // ── Core ───────────────────────────────────────────────────────────────

    private static UseCaseException unauthenticated() {
        return UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
    }

    /// Fails unless the principal is anchor-scoped.
    public static void requireAnchor(AuthContext a) {
        if (a == null) throw unauthenticated();
        if (!a.isAnchor()) throw UseCaseException.authorization("ANCHOR_REQUIRED", "anchor scope required");
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
        canWritePrincipals(a);
    }

    /// Go `IsAdmin`: anchor-scoped or holding the super-admin wildcard.
    public static void requireAdmin(AuthContext a) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor() || a.hasPermission(SUPER_ADMIN)) return;
        throw UseCaseException.authorization("ADMIN_REQUIRED", "admin permission required");
    }

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

    /// Per-resource scope on a by-id operation, on top of the coarse `can*`
    /// check: a client-scoped resource requires access to that client; a
    /// platform-level resource (`null`) requires anchor or super-admin.
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

    private static void requirePermission(AuthContext a, String perm) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor() || a.hasPermission(perm)) return;
        throw UseCaseException.authorization("PERMISSION_REQUIRED", "permission required: " + perm);
    }

    /// Go `CanWritePermission`: the generic check for an ad-hoc full
    /// 4-segment permission string.
    public static void canWritePermission(AuthContext a, String perm) {
        requirePermission(a, perm);
    }

    private static void requireAny(AuthContext a, String... perms) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor() || Arrays.stream(perms).anyMatch(a::hasPermission)) return;
        throw UseCaseException.authorization("PERMISSION_REQUIRED", "one of: " + String.join(", ", perms));
    }

    // ── EventType ──────────────────────────────────────────────────────────
    public static void canReadEventTypes(AuthContext a) { requirePermission(a, EVENT_TYPE_VIEW); }
    public static void canCreateEventTypes(AuthContext a) { requirePermission(a, EVENT_TYPE_CREATE); }
    public static void canUpdateEventTypes(AuthContext a) { requirePermission(a, EVENT_TYPE_UPDATE); }
    public static void canDeleteEventTypes(AuthContext a) { requirePermission(a, EVENT_TYPE_DELETE); }

    /// Guards `POST /api/applications/{appCode}/event-types/sync`.
    public static void canSyncEventTypes(AuthContext a) {
        requireAny(a, EVENT_TYPE_SYNC, EVENT_TYPE_MANAGE,
                APP_SVC_EVENT_TYPE_CREATE, APP_SVC_EVENT_TYPE_UPDATE, APP_SVC_EVENT_TYPE_DELETE);
    }

    public static void canWriteEventTypes(AuthContext a) {
        requireAny(a, EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);
    }

    // ── Connection ─────────────────────────────────────────────────────────
    public static void canReadConnections(AuthContext a) { requirePermission(a, CONNECTION_VIEW); }
    public static void canCreateConnections(AuthContext a) { requirePermission(a, CONNECTION_CREATE); }
    public static void canUpdateConnections(AuthContext a) { requirePermission(a, CONNECTION_UPDATE); }
    public static void canDeleteConnections(AuthContext a) { requirePermission(a, CONNECTION_DELETE); }

    public static void canWriteConnections(AuthContext a) {
        requireAny(a, CONNECTION_CREATE, CONNECTION_UPDATE, CONNECTION_DELETE);
    }

    // ── Subscription ───────────────────────────────────────────────────────
    public static void canReadSubscriptions(AuthContext a) { requirePermission(a, SUBSCRIPTION_VIEW); }
    public static void canCreateSubscriptions(AuthContext a) { requirePermission(a, SUBSCRIPTION_CREATE); }
    public static void canUpdateSubscriptions(AuthContext a) { requirePermission(a, SUBSCRIPTION_UPDATE); }
    public static void canDeleteSubscriptions(AuthContext a) { requirePermission(a, SUBSCRIPTION_DELETE); }

    public static void canWriteSubscriptions(AuthContext a) {
        requireAny(a, SUBSCRIPTION_CREATE, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE);
    }

    // ── Dispatch pool ──────────────────────────────────────────────────────
    public static void canReadDispatchPools(AuthContext a) { requirePermission(a, DISPATCH_POOL_VIEW); }
    public static void canCreateDispatchPools(AuthContext a) { requirePermission(a, DISPATCH_POOL_CREATE); }
    public static void canUpdateDispatchPools(AuthContext a) { requirePermission(a, DISPATCH_POOL_UPDATE); }
    public static void canDeleteDispatchPools(AuthContext a) { requirePermission(a, DISPATCH_POOL_DELETE); }

    public static void canWriteDispatchPools(AuthContext a) {
        requireAny(a, DISPATCH_POOL_CREATE, DISPATCH_POOL_UPDATE, DISPATCH_POOL_DELETE);
    }

    // ── Process ────────────────────────────────────────────────────────────
    public static void canReadProcesses(AuthContext a) { requirePermission(a, PROCESS_VIEW); }
    public static void canCreateProcesses(AuthContext a) { requirePermission(a, PROCESS_CREATE); }
    public static void canUpdateProcesses(AuthContext a) { requirePermission(a, PROCESS_UPDATE); }
    public static void canDeleteProcesses(AuthContext a) { requirePermission(a, PROCESS_DELETE); }

    public static void canWriteProcesses(AuthContext a) {
        requireAny(a, PROCESS_CREATE, PROCESS_UPDATE, PROCESS_DELETE);
    }

    // ── Application ────────────────────────────────────────────────────────
    public static void canReadApplications(AuthContext a) { requirePermission(a, APPLICATION_VIEW); }
    public static void canCreateApplications(AuthContext a) { requirePermission(a, APPLICATION_CREATE); }
    public static void canUpdateApplications(AuthContext a) { requirePermission(a, APPLICATION_UPDATE); }
    public static void canDeleteApplications(AuthContext a) { requirePermission(a, APPLICATION_DELETE); }

    public static void canWriteApplications(AuthContext a) {
        requireAny(a, APPLICATION_CREATE, APPLICATION_UPDATE, APPLICATION_DELETE);
    }

    // ── Role ───────────────────────────────────────────────────────────────
    public static void canReadRoles(AuthContext a) { requirePermission(a, ROLE_VIEW); }
    public static void canCreateRoles(AuthContext a) { requirePermission(a, ROLE_CREATE); }
    public static void canUpdateRoles(AuthContext a) { requirePermission(a, ROLE_UPDATE); }
    public static void canDeleteRoles(AuthContext a) { requirePermission(a, ROLE_DELETE); }

    public static void canWriteRoles(AuthContext a) {
        requireAny(a, ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE);
    }

    /// Guards `POST /api/applications/{appCode}/roles/sync`.
    public static void canSyncRoles(AuthContext a) {
        requireAny(a, ROLE_MANAGE, ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE,
                APP_SVC_ROLE_CREATE, APP_SVC_ROLE_UPDATE, APP_SVC_ROLE_DELETE);
    }

    /// Guards `POST /api/applications/{appCode}/subscriptions/sync`.
    public static void canSyncSubscriptions(AuthContext a) {
        requireAny(a, SUBSCRIPTION_SYNC, SUBSCRIPTION_MANAGE,
                APP_SVC_SUBSCRIPTION_CREATE, APP_SVC_SUBSCRIPTION_UPDATE, APP_SVC_SUBSCRIPTION_DELETE);
    }

    /// Guards `POST /api/applications/{appCode}/principals/sync`.
    public static void canSyncPrincipals(AuthContext a) {
        requireAny(a, USER_MANAGE, USER_CREATE, USER_UPDATE, USER_DELETE, USER_ASSIGN_ROLES);
    }

    /// Guards `POST /api/applications/{appCode}/scheduled-jobs/sync`.
    public static void canSyncScheduledJobs(AuthContext a) {
        requireAny(a, APP_SVC_SCHEDULED_JOB_SYNC, SCHEDULED_JOB_SYNC, SCHEDULED_JOB_MANAGE);
    }

    /// Guards `POST /api/applications/{appCode}/processes/sync`.
    public static void canSyncProcesses(AuthContext a) {
        requireAny(a, PROCESS_SYNC, APP_SVC_PROCESS_SYNC);
    }

    /// Guards `POST /api/applications/{appCode}/dispatch-pools/sync` (admin-tier only).
    public static void canSyncDispatchPools(AuthContext a) {
        requireAny(a, DISPATCH_POOL_SYNC, DISPATCH_POOL_MANAGE);
    }

    /// Guards `POST /api/applications/{appCode}/openapi/sync`.
    public static void canSyncApplicationOpenAPI(AuthContext a) {
        requireAny(a, APP_OPENAPI_SYNC, APP_OPENAPI_MANAGE);
    }

    /// Guards the self-service developer client_credentials endpoints.
    public static void canManageOwnDeveloperCredential(AuthContext a) {
        requirePermission(a, DEVELOPER_API_CREDENTIAL_MANAGE);
    }

    // ── Service account ────────────────────────────────────────────────────
    public static void canReadServiceAccounts(AuthContext a) { requirePermission(a, SERVICE_ACCOUNT_VIEW); }
    public static void canCreateServiceAccounts(AuthContext a) { requirePermission(a, SERVICE_ACCOUNT_CREATE); }
    public static void canUpdateServiceAccounts(AuthContext a) { requirePermission(a, SERVICE_ACCOUNT_UPDATE); }
    public static void canDeleteServiceAccounts(AuthContext a) { requirePermission(a, SERVICE_ACCOUNT_DELETE); }

    public static void canWriteServiceAccounts(AuthContext a) {
        requireAny(a, SERVICE_ACCOUNT_CREATE, SERVICE_ACCOUNT_UPDATE, SERVICE_ACCOUNT_DELETE);
    }

    // ── Client (tenant) — anchor-only by convention ────────────────────────
    public static void canReadClients(AuthContext a) { requireAnchor(a); }
    public static void canCreateClients(AuthContext a) { requireAnchor(a); }
    public static void canUpdateClients(AuthContext a) { requireAnchor(a); }
    public static void canDeleteClients(AuthContext a) { requireAnchor(a); }
    public static void canWriteClients(AuthContext a) { requireAnchor(a); }

    // ── Principal (user) ───────────────────────────────────────────────────
    public static void canReadPrincipals(AuthContext a) { requirePermission(a, USER_VIEW); }
    public static void canCreatePrincipals(AuthContext a) { requirePermission(a, USER_CREATE); }
    public static void canUpdatePrincipals(AuthContext a) { requirePermission(a, USER_UPDATE); }
    public static void canDeletePrincipals(AuthContext a) { requirePermission(a, USER_DELETE); }

    public static void canWritePrincipals(AuthContext a) {
        requireAny(a, USER_CREATE, USER_UPDATE, USER_DELETE);
    }

    // ── Portal users (CLIENT-delegable) ────────────────────────────────────

    /// Listing a client's portal identities: anchors pass; otherwise access to
    /// the client AND view-or-manage.
    public static void canReadPortalUsers(AuthContext a, String clientId) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor()) return;
        if (!a.canAccessClient(clientId)) {
            throw UseCaseException.authorization("SCOPE_FORBIDDEN", "no access to this client");
        }
        requireAny(a, PORTAL_USER_VIEW, PORTAL_USER_MANAGE);
    }

    /// Ensure/invite, suspend, delete a client's portal identities.
    public static void canManagePortalUsers(AuthContext a, String clientId) {
        if (a == null) throw unauthenticated();
        if (a.isAnchor()) return;
        if (!a.canAccessClient(clientId)) {
            throw UseCaseException.authorization("SCOPE_FORBIDDEN", "no access to this client");
        }
        requirePermission(a, PORTAL_USER_MANAGE);
    }

    // ── Platform documentation ─────────────────────────────────────────────
    public static void canReadPlatformDocs(AuthContext a) { requirePermission(a, DOCS_VIEW); }

    /// Guards `POST /api/applications/{appCode}/docs/sync`.
    public static void canSyncAppDocs(AuthContext a) { requireAny(a, APP_SVC_DOCS_SYNC); }

    // ── Identity providers — anchor-only ───────────────────────────────────
    public static void canReadIdentityProviders(AuthContext a) { requireAnchor(a); }
    public static void canWriteIdentityProviders(AuthContext a) { requireAnchor(a); }

    // ── Scheduled job ──────────────────────────────────────────────────────
    public static void canReadScheduledJobs(AuthContext a) { requirePermission(a, SCHEDULED_JOB_VIEW); }
    public static void canCreateScheduledJobs(AuthContext a) { requirePermission(a, SCHEDULED_JOB_CREATE); }
    public static void canUpdateScheduledJobs(AuthContext a) { requirePermission(a, SCHEDULED_JOB_UPDATE); }
    public static void canDeleteScheduledJobs(AuthContext a) { requirePermission(a, SCHEDULED_JOB_DELETE); }

    public static void canWriteScheduledJobs(AuthContext a) {
        requireAny(a, SCHEDULED_JOB_CREATE, SCHEDULED_JOB_UPDATE, SCHEDULED_JOB_DELETE);
    }

    public static void canFireScheduledJobs(AuthContext a) { requirePermission(a, SCHEDULED_JOB_FIRE); }
}
