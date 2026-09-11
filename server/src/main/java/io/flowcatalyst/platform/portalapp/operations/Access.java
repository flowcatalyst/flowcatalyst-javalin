package io.flowcatalyst.platform.portalapp.operations;

import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 for a by-id write, cross-client hidden (spec `portal-apps.md`
/// §3.2, §3.5, §3.6, mirroring `portalidentity.operations.DeletePortalIdentity`'s
/// rule): a client-scoped resource — unlike `oauthclient`'s platform-level
/// `Access.byId` — so a `clientId` on the command that disagrees with the
/// row's is not-found, never a distinct error, so a caller cannot use this
/// to probe whether an id exists under another tenant.
final class Access {

    private Access() {
    }

    /// @throws UseCaseException not-found `PortalApp_NOT_FOUND` when the row
    ///                          is absent, or `clientId` is given and differs
    static PortalApp byId(PortalAppRepository repo, String id, String clientId) {
        PortalApp app = repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("PortalApp", id));
        if (clientId != null && !clientId.isBlank() && !clientId.equals(app.clientId())) {
            throw UseCaseException.resourceNotFound("PortalApp", id);
        }
        return app;
    }
}
