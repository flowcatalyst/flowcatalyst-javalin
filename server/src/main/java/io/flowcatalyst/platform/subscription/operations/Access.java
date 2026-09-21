package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.shared.auth.Auth;
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
}
