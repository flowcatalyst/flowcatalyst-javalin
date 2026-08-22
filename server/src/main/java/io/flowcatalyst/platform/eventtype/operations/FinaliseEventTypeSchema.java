package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeSchemaFinalised;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// `FINALISING` → `CURRENT` for one spec version ([EventType#finaliseSchema],
/// which also auto-deprecates the same-major `CURRENT` sibling) and emits
/// [EventTypeSchemaFinalised] carrying the deprecated sibling, if any.
/// Reached from the BFF only; that entry point holds the coarse gate.
public final class FinaliseEventTypeSchema {

    private FinaliseEventTypeSchema() {
    }

    public static Operation<FinaliseSchemaCommand, EventTypeSchemaFinalised> of(EventTypeRepository repo) {
        return Operation.<FinaliseSchemaCommand, EventTypeSchemaFinalised>named("FinaliseEventTypeSchema")
                .validate(cmd -> {
                    if (cmd.eventTypeId() == null || cmd.eventTypeId().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "eventTypeId is required");
                    }
                    if (cmd.version() == null || cmd.version().isBlank()) {
                        throw UseCaseException.validation("VERSION_REQUIRED", "version is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    var finalised = Access.loadScoped(repo, cmd.eventTypeId()).finaliseSchema(cmd.version());
                    EventType et = finalised.eventType();
                    return Plan.save(et, repo,
                            EventTypeSchemaFinalised.of(ec, et, cmd.version(), finalised.deprecatedVersion()));
                });
    }
}
