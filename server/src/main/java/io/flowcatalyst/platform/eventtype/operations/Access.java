package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 + per-resource scope check — the opening of every by-id
/// write operation's execute phase (spec §5: the resource-level check runs
/// right after the load, which is why those operations declare
/// `Authorize.publicAccess()`).
final class Access {

    private Access() {
    }

    /// The event type `id`, if it exists and the current principal may act on it.
    ///
    /// @throws UseCaseException not-found `EventType_NOT_FOUND`,
    ///                                                       authorization `SCOPE_FORBIDDEN` | `UNAUTHENTICATED`
    static EventType loadScoped(EventTypeRepository repo, String id) {
        EventType et = repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("EventType", id));
        Checks.checkScopeAccess(Auth.current(), et.clientId());
        return et;
    }
}
