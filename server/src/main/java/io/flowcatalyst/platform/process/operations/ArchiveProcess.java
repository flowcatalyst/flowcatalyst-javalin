package io.flowcatalyst.platform.process.operations;

import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessArchived;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// `CURRENT` → `ARCHIVED` ([Process#archive]) and emits [ProcessArchived].
public final class ArchiveProcess {

    private ArchiveProcess() {
    }

    public static Operation<ArchiveCommand, ProcessArchived> of(ProcessRepository repo) {
        return Operation.<ArchiveCommand, ProcessArchived>named("ArchiveProcess")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Processes are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Process p = Access.byId(repo, cmd.id()).archive();
                    return Plan.save(p, repo, ProcessArchived.of(ec, p));
                });
    }
}
