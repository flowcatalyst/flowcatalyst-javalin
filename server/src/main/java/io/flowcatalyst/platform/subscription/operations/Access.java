package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.SigningReach;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The resource-level authorization helpers (spec §5). [#loadScoped] is
/// load-or-404 + per-resource scope check — the opening of every by-id
/// write operation's execute phase, which is why those operations declare
/// `Authorize.publicAccess()`. [#checkSyncAccess] is the sync operation's
/// authorize phase.
final class Access {

    private Access() {
    }

    /// The subscription `id`, if it exists and the current principal may act on it.
    ///
    /// @throws UseCaseException not-found `Subscription_NOT_FOUND`,
    ///                          authorization `SCOPE_FORBIDDEN` | `UNAUTHENTICATED`
    static Subscription loadScoped(SubscriptionRepository repo, String id) {
        Subscription s = repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("Subscription", id));
        Checks.checkScopeAccess(Auth.current(), s.clientId());
        return s;
    }

    /// The current principal must be able to act for the application a
    /// subscription sync is scoped to, and — when `clientId` is given — for
    /// that client too. A client-less sync needs ONLY application access
    /// (`code-first-connections.md` §3, hand-off "Subscription sync", ruled
    /// 2026-09-21: mirrors
    /// [io.flowcatalyst.platform.connection.operations.Access#checkSyncAccess]
    /// — ownership, not reach, fences a client-less subscription sync in,
    /// since it can only ever touch rows of ITS OWN application that a prior
    /// sync authored).
    ///
    /// @throws UseCaseException authorization `FORBIDDEN` | `UNAUTHENTICATED`
    static void checkSyncAccess(String applicationId, String applicationCode, String clientId) {
        var ac = Auth.current();
        Checks.checkApplicationAccess(ac, applicationId, applicationCode);
        if (clientId != null && !ac.canAccessClient(clientId)) {
            throw UseCaseException.authorization("FORBIDDEN", "No access to client: " + clientId);
        }
    }

    /// The account a subscription's deliveries will be signed with — named
    /// directly, or through its connection — must be one the caller may use
    /// (`docs/spec/security-fixes-2026-09-24.md` S3.1; [SigningReach]). The
    /// caller chooses the endpoint, so naming an account is choosing where
    /// its bearer token and signatures go.
    ///
    /// `ownerApplicationCode` is the subscription's own `applicationCode`
    /// (set only by an application's sync, never by the admin routes): that
    /// application's accounts are usable on its own subscription.
    ///
    /// `serviceAccountId`/`connectionId` are `null` when absent. A reference
    /// the caller is setting (`mustExist`) must name an existing row; one
    /// carried over unchanged may be dangling (it signs nothing) and is then
    /// not refused.
    ///
    /// @throws UseCaseException not-found `ServiceAccount_NOT_FOUND` | `Connection_NOT_FOUND`,
    ///                          authorization `SERVICE_ACCOUNT_OUT_OF_REACH` | `CONNECTION_OUT_OF_REACH`
    static void requireUsableSigners(SigningReach reach, ConnectionRepository connections, String ownerApplicationCode,
                                     String serviceAccountId, boolean serviceAccountMustExist,
                                     String connectionId, boolean connectionMustExist) {
        AuthContext ac = Auth.current();
        if (ac == null) {
            throw UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
        }
        String ownerApplicationId = ownerApplicationCode == null ? null
                : reach.applicationId(ownerApplicationCode).orElse(null);
        if (serviceAccountId != null) {
            requireUsable(reach, ac, serviceAccountId, serviceAccountMustExist, ownerApplicationId);
        }
        if (connectionId != null) {
            Connection connection = connections.findById(connectionId).orElse(null);
            if (connection == null) {
                if (connectionMustExist) {
                    throw UseCaseException.resourceNotFound("Connection", connectionId);
                }
                return;
            }
            if (!Checks.canAccessScope(ac, connection.clientId())) {
                throw UseCaseException.authorization("CONNECTION_OUT_OF_REACH",
                        "connection " + connection.code() + " belongs to a scope the caller cannot access");
            }
            String connectionAccountId = connection.serviceAccountId();
            if (connectionAccountId != null && !connectionAccountId.isBlank()) {
                requireUsable(reach, ac, connectionAccountId, false, ownerApplicationId);
            }
        }
    }

    private static void requireUsable(SigningReach reach, AuthContext ac, String serviceAccountId, boolean mustExist,
                                      String ownerApplicationId) {
        ServiceAccount account = reach.account(serviceAccountId).orElse(null);
        if (account == null) {
            if (mustExist) {
                throw UseCaseException.resourceNotFound("ServiceAccount", serviceAccountId);
            }
            return;
        }
        reach.mayUse(ac, account, ownerApplicationId)
                .orElseThrow(refusal -> UseCaseException.authorization("SERVICE_ACCOUNT_OUT_OF_REACH", refusal.message()));
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
