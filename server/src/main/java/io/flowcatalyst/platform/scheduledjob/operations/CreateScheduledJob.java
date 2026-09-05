package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobCode;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a scheduled job (unique by code within its client scope) and
/// emits [ScheduledJobCreated].
public final class CreateScheduledJob {

    private CreateScheduledJob() {
    }

    public static Operation<CreateCommand, ScheduledJobCreated> of(ScheduledJobRepository repo) {
        return Operation.<CreateCommand, ScheduledJobCreated>named("CreateScheduledJob")
                .validate(cmd -> {
                    ScheduledJobCode.parse(cmd.code());
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name is required");
                    Crons.parseAll(cmd.crons());
                })
                // The target client is a command field, so the per-resource check
                // can run before execute: a client-bound create needs access to that
                // client; a platform-scoped (null clientId) create needs anchor.
                .authorize(cmd -> {
                    if (cmd.clientId() == null && Auth.current() != null && !Auth.current().isAnchor()) {
                        // Go's own wording and code for this one refusal (ops.go): the parity
                        // harness (S1-C) found Java answering the generic SCOPE_FORBIDDEN here.
                        throw UseCaseException.authorization("FORBIDDEN", "Only anchor users can create platform-scoped jobs");
                    }
                    Checks.checkScopeAccess(Auth.current(), cmd.clientId());
                })
                .execute((cmd, ec) -> {
                    ScheduledJobCode code = ScheduledJobCode.parse(cmd.code());
                    if (repo.findByCode(code.value(), cmd.clientId()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Scheduled job with code '" + code + "' already exists");
                    }
                    ScheduledJob j = ScheduledJob.create(code, definitionOf(cmd))
                            .withClientId(cmd.clientId())
                            .withApplicationId(cmd.applicationId())
                            .withCreatedBy(ec.principalId());
                    return Plan.save(j, repo, ScheduledJobCreated.of(ec, j));
                });
    }

    private static ScheduledJob.Definition definitionOf(CreateCommand cmd) {
        return new ScheduledJob.Definition(cmd.name(), cmd.description(), Crons.parseAll(cmd.crons()), cmd.timezone(),
                cmd.payload(), cmd.concurrent(), cmd.tracksCompletion(), cmd.timeoutSeconds(),
                cmd.deliveryMaxAttempts(), cmd.targetUrl());
    }
}
