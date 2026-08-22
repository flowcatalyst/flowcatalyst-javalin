package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
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

    /// The connection `id`, if it exists and the current principal may act on it.
    ///
    /// @throws UseCaseException not-found `Connection_NOT_FOUND`,
    ///                          authorization `SCOPE_FORBIDDEN` | `UNAUTHENTICATED`
    static Connection loadScoped(ConnectionRepository repo, String id) {
        Connection c = repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("Connection", id));
        Checks.checkScopeAccess(Auth.current(), c.clientId());
        return c;
    }
}
