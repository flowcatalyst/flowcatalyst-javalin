package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id write operation's execute phase.
/// OAuth clients are platform-level config with no per-resource scope to
/// check after the load (spec §6.3: the coarse anchor gate lives in the
/// handler), so this names its helper `byId`, not `loadScoped`, per
/// CONVENTIONS §2 — which is *why* every by-id operation here declares
/// `Authorize.publicAccess()`.
final class Access {

    private Access() {
    }

    /// @throws UseCaseException not-found `OAuthClient_NOT_FOUND`
    static OAuthClient byId(OAuthClientRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("OAuthClient", id));
    }
}
