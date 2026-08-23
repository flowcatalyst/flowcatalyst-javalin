package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id write operation's execute
/// phase. Identity providers are anchor-only resources with no per-resource
/// scope (spec §4): there is nothing to check after the load, which is why
/// those operations declare `Authorize.publicAccess()` and rely on the
/// handler's `requireAnchor`.
final class Access {

    private Access() {
    }

    /// The provider with TSID `id`.
    ///
    /// @throws UseCaseException not-found `IdentityProvider_NOT_FOUND`
    static IdentityProvider byId(IdentityProviderRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("IdentityProvider", id));
    }
}
