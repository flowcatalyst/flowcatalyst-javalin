package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes an event type with its spec versions and emits [EventTypeDeleted].
public final class DeleteEventType {

    private DeleteEventType() {
    }

    public static Operation<DeleteCommand, EventTypeDeleted> of(EventTypeRepository repo) {
        return Operation.<DeleteCommand, EventTypeDeleted>named("DeleteEventType")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "Event type id is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    EventType et = Access.loadScoped(repo, cmd.id());
                    return Plan.delete(et, repo, EventTypeDeleted.of(ec, et));
                });
    }
}
