package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id write operation's execute
/// phase. Clients are anchor-only resources with no per-resource scope
/// (spec §5): there is nothing to check after the load, which is why those
/// operations declare `Authorize.publicAccess()` and rely on the handler's
/// `requireAnchor`.
final class Access {

    private Access() {
    }

    /// The client with TSID `id`.
    ///
    /// @throws UseCaseException not-found `Client_NOT_FOUND`
    static Client byId(ClientRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("Client", id));
    }
}
