package io.flowcatalyst.platform.process.operations;

import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes an archived process and emits [ProcessDeleted]; a current one
/// is refused (`PROCESS_NOT_ARCHIVED`, [Process#requireDeletable], bug 23).
public final class DeleteProcess {

    private DeleteProcess() {
    }

    public static Operation<DeleteCommand, ProcessDeleted> of(ProcessRepository repo) {
        return Operation.<DeleteCommand, ProcessDeleted>named("DeleteProcess")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Processes are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Process p = Access.byId(repo, cmd.id()).requireDeletable();
                    return Plan.delete(p, repo, ProcessDeleted.of(ec, p));
                });
    }
}
