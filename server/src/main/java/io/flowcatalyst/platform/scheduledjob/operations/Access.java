package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The resource-level authorization helpers (spec §9). [#loadScoped] is
/// load-or-404 + per-resource scope check — the opening of every by-id
/// write operation's execute phase, which is why those operations declare
/// `Authorize.publicAccess()`. [#checkApplicationAccess] is the sync
/// operation's application half.
final class Access {

    private Access() {
    }

    /// The job `id`, if it exists and the current principal may act on it.
    ///
    /// @throws UseCaseException not-found `ScheduledJob_NOT_FOUND`,
    ///                          authorization `SCOPE_FORBIDDEN` | `UNAUTHENTICATED`
    static ScheduledJob loadScoped(ScheduledJobRepository repo, String id) {
        ScheduledJob j = repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("ScheduledJob", id));
        Checks.checkScopeAccess(Auth.current(), j.clientId());
        return j;
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
