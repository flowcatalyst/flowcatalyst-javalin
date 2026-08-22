package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces name and description on an existing event type and emits
/// [EventTypeUpdated]. The code is immutable.
public final class UpdateEventType {

    private UpdateEventType() {
    }

    public static Operation<UpdateCommand, EventTypeUpdated> of(EventTypeRepository repo) {
        return Operation.<UpdateCommand, EventTypeUpdated>named("UpdateEventType")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "Event type id is required");
                    }
                    if (cmd.name() == null || cmd.name().isBlank()) {
                        throw UseCaseException.validation("NAME_REQUIRED", "Event type name is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    EventType et = Access.loadScoped(repo, cmd.id())
                            .withName(cmd.name())
                            .withDescription(cmd.description());
                    return Plan.save(et, repo, EventTypeUpdated.of(ec, et));
                });
    }
}
