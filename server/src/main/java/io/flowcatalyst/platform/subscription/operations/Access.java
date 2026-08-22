package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The resource-level authorization helpers (spec §5). [#loadScoped] is
/// load-or-404 + per-resource scope check — the opening of every by-id
/// write operation's execute phase, which is why those operations declare
/// `Authorize.publicAccess()`. [#checkApplicationAccess] is the sync
/// operation's authorize phase.
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

    /// The current principal must be able to act for the application a sync
    /// is scoped to (the coarse sync permission and the code → id resolution
    /// are the handler's).
    ///
    /// @throws UseCaseException authorization `FORBIDDEN` | `UNAUTHENTICATED`
    static void checkApplicationAccess(String applicationId, String applicationCode) {
        Checks.checkApplicationAccess(Auth.current(), applicationId, applicationCode);
    }
}
