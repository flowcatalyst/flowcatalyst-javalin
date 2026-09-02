package io.flowcatalyst.platform.dispatchjob.operations;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The status-flip precondition shared by [CancelDispatchJob] and
/// [CompleteDispatchJob]'s `execute` phase (dispatch-seam spec §8, mirroring
/// Go's `statusFlip`). The load-or-404 + scope check both operations open
/// with is [Access#loadOwn] — shared with the read routes since PR-3 (ledger,
/// ruled 2026-09-01), not private to this class any more.
final class StatusFlip {

    private StatusFlip() {
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
