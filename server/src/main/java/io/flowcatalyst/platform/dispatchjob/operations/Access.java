package io.flowcatalyst.platform.dispatchjob.operations;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 + per-resource scope for every id-addressed dispatch-job
/// route, read and write alike (ledger PR-3, ruled 2026-09-01: after the
/// coarse permission gate, an out-of-scope target answers 404
/// byte-identical to not-found — real-but-forbidden and nonexistent must be
/// indistinguishable, or the id space becomes an existence oracle).
///
/// One definition serves `GET /{id}`, `/{id}/raw`, `/{id}/attempts` (called
/// directly from `DispatchJobApi`, per `HttpError.notFound`'s own spelling of
/// [UseCaseException#resourceNotFound]) and [CancelDispatchJob] /
/// [CompleteDispatchJob]'s execute phase — so the read side and the write
/// side can never drift the way Go's did (PR-3's own history).
public final class Access {

    private Access() {
    }

    /// @throws UseCaseException not-found `DispatchJob_NOT_FOUND` — for a
    ///                          missing id AND for one outside the caller's
    ///                          scope, byte-identical either way (PR-3)
    public static DispatchJob loadOwn(DispatchJobRepository repo, String id) {
        DispatchJob j = repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("DispatchJob", id));
        if (!Checks.canAccessScope(Auth.current(), j.clientId())) {
            throw UseCaseException.resourceNotFound("DispatchJob", id);
        }
        return j;
    }
}
