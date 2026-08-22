package io.flowcatalyst.platform.process.operations;

import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id write operation's execute
/// phase. Processes are global (spec §5): there is no per-resource scope to
/// check after the load, which is why those operations declare
/// `Authorize.publicAccess()` and rely on the handler's coarse gate.
final class Access {

    private Access() {
    }

    /// The process with TSID `id`.
    ///
    /// @throws UseCaseException not-found `Process_NOT_FOUND`
    static Process byId(ProcessRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("Process", id));
    }
}
