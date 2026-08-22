package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeArchived;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// `CURRENT` → `ARCHIVED` ([EventType#archive]) and emits [EventTypeArchived].
/// Reached from the BFF only; that entry point holds the coarse gate.
public final class ArchiveEventType {

    private ArchiveEventType() {
    }

    public static Operation<ArchiveCommand, EventTypeArchived> of(EventTypeRepository repo) {
        return Operation.<ArchiveCommand, EventTypeArchived>named("ArchiveEventType")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "id is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    EventType et = Access.loadScoped(repo, cmd.id()).archive();
                    return Plan.save(et, repo, EventTypeArchived.of(ec, et));
                });
    }
}
