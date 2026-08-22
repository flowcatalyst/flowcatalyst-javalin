package io.flowcatalyst.platform.process.operations;

import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces the supplied fields of an existing process (`null` = untouched)
/// and emits [ProcessUpdated]. The code is immutable.
public final class UpdateProcess {

    private UpdateProcess() {
    }

    public static Operation<UpdateCommand, ProcessUpdated> of(ProcessRepository repo) {
        return Operation.<UpdateCommand, ProcessUpdated>named("UpdateProcess")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                })
                // Processes are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Process p = Access.byId(repo, cmd.id()).update(new Process.Changes(
                            cmd.name() == null ? null : cmd.name().trim(),
                            cmd.description(), cmd.body(), cmd.diagramType(), cmd.tags()));
                    return Plan.save(p, repo, ProcessUpdated.of(ec, p));
                });
    }
}
