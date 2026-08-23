package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 + per-resource scope check (spec §9) — the opening of every
/// by-id write operation's execute phase, which is why those operations
/// declare `Authorize.publicAccess()`.
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
}
