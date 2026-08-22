package io.flowcatalyst.platform.cors.operations;

import io.flowcatalyst.platform.cors.CorsOrigin;
import io.flowcatalyst.platform.cors.CorsOriginRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of the by-id write operation's execute phase.
/// CORS origins are anchor-only, platform-owned resources with no
/// per-resource scope (spec §5): there is nothing to check after the load,
/// which is why those operations declare `Authorize.publicAccess()` and rely
/// on the handler's `requireAnchor`.
final class Access {

    private Access() {
    }

    /// The allowlist entry with TSID `id`.
    ///
    /// @throws UseCaseException not-found `CorsOrigin_NOT_FOUND`
    static CorsOrigin byId(CorsOriginRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("CorsOrigin", id));
    }
}
