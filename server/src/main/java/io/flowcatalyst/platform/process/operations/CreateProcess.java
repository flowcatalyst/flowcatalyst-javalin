package io.flowcatalyst.platform.process.operations;

import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.platform.process.ProcessCode;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.operations.ProcessEvents.ProcessCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a `UI`-sourced process (unique by code) and emits [ProcessCreated].
public final class CreateProcess {

    private CreateProcess() {
    }

    public static Operation<CreateCommand, ProcessCreated> of(ProcessRepository repo) {
        return Operation.<CreateCommand, ProcessCreated>named("CreateProcess")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.code(), "CODE_REQUIRED", "Process code is required");
                    ProcessCode.parse(cmd.code());
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "Process name is required");
                })
                // Processes are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    if (repo.findByCode(cmd.code()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Process with code '" + cmd.code() + "' already exists");
                    }
                    Process p = Process.create(cmd.code(), cmd.name().trim())
                            .withDescription(cmd.description())
                            .withBody(cmd.body())
                            .withDiagramType(cmd.diagramType())
                            .withTags(cmd.tags())
                            .withCreatedBy(ec.principalId());
                    return Plan.save(p, repo, ProcessCreated.of(ec, p));
                });
    }
}
