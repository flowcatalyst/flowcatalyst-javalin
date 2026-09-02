package io.flowcatalyst.platform.dispatchjob.operations;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The shared opening of [CancelDispatchJob] and [CompleteDispatchJob]'s
/// `execute` phase (dispatch-seam spec §8, mirroring Go's `statusFlip`):
/// load-or-404, then a per-resource scope check that answers the SAME 404 a
/// truly-missing id gets — not the 403 `SCOPE_FORBIDDEN` the read routes use.
/// This unit's ruling: an operator *write* on a resource id must not let a
/// caller distinguish "exists but not mine" from "does not exist" by status
/// code, so [#loadOwn] never throws [UseCaseException#authorization].
final class StatusFlip {

    private StatusFlip() {
    }

    /// @throws UseCaseException not-found `DispatchJob_NOT_FOUND` — for a
    ///                          missing id AND for one outside the caller's
    ///                          scope, byte-identical either way
    static DispatchJob loadOwn(DispatchJobRepository repo, String id) {
        DispatchJob j = repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("DispatchJob", id));
        if (!Checks.canAccessScope(Auth.current(), j.clientId())) {
            throw UseCaseException.resourceNotFound("DispatchJob", id);
        }
        return j;
    }

    /// Only a `FAILED` job may be overridden — 409 `NOT_FAILED` otherwise
    /// (spec §8): deliberately narrower than [DispatchJobStatus#isTerminal],
    /// without this guard an operator could "cancel" an already-COMPLETED job.
    ///
    /// @throws UseCaseException conflict `NOT_FAILED`
    static DispatchJob requireFailed(DispatchJob j) {
        if (j.status() != DispatchJobStatus.FAILED) {
            throw UseCaseException.conflict("NOT_FAILED",
                    "dispatch job is not FAILED (current status: " + j.status()
                            + "); only a FAILED job can be overridden");
        }
        return j;
    }
}
