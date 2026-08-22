package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id write operation's execute phase.
/// Applications are platform-level (spec §5): there is no per-resource scope
/// to check after the load, which is why those operations declare
/// `Authorize.publicAccess()` and rely on the handler's coarse gate.
final class Access {

    private Access() {
    }

    /// The application with TSID `id`.
    ///
    /// @throws UseCaseException not-found `Application_NOT_FOUND`
    static Application byId(ApplicationRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("Application", id));
    }
}
