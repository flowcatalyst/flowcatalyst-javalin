package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeSchemaAdded;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Appends a `FINALISING` schema version ([EventType#addSchemaVersion]) and
/// emits [EventTypeSchemaAdded].
public final class AddSchema {

    private AddSchema() {
    }

    public static Operation<AddSchemaCommand, EventTypeSchemaAdded> of(EventTypeRepository repo) {
        return Operation.<AddSchemaCommand, EventTypeSchemaAdded>named("AddSchema")
                .validate(cmd -> {
                    if (cmd.eventTypeId() == null || cmd.eventTypeId().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "eventTypeId is required");
                    }
                    if (cmd.version() == null || cmd.version().isBlank()) {
                        throw UseCaseException.validation("VERSION_REQUIRED", "version is required");
                    }
                    if (cmd.schema() == null || cmd.schema().isNull()) {
                        throw UseCaseException.validation("SCHEMA_REQUIRED", "schema payload is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    EventType et = Access.loadScoped(repo, cmd.eventTypeId()).addSchemaVersion(cmd.version(), cmd.schema());
                    return Plan.save(et, repo, EventTypeSchemaAdded.of(ec, et, cmd.version()));
                });
    }
}
