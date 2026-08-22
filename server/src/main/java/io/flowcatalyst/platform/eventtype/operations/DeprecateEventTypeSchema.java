package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeSchemaDeprecated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// `CURRENT` → `DEPRECATED` for one spec version ([EventType#deprecateSchema])
/// and emits [EventTypeSchemaDeprecated]. Reached from the BFF only; that
/// entry point holds the coarse gate.
public final class DeprecateEventTypeSchema {

    private DeprecateEventTypeSchema() {
    }

    public static Operation<DeprecateSchemaCommand, EventTypeSchemaDeprecated> of(EventTypeRepository repo) {
        return Operation.<DeprecateSchemaCommand, EventTypeSchemaDeprecated>named("DeprecateEventTypeSchema")
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
                    EventType et = Access.loadScoped(repo, cmd.eventTypeId()).deprecateSchema(cmd.version());
                    return Plan.save(et, repo, EventTypeSchemaDeprecated.of(ec, et, cmd.version()));
                });
    }
}
