package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.EventTypeSource;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeCreated;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeDeleted;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeUpdated;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypesSynced;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Bulk-upserts an application's event-type catalogue in one transaction
/// (spec §7): existing codes get name + description replaced, new codes are
/// created `API`-sourced, and with `removeUnlisted` the `API`-sourced rows
/// not in the batch are deleted (`UI`/`CODE` rows are never touched). One
/// per-row event per row touched plus one [EventTypesSynced] rollup.
///
/// Authorization is deliberately [Operation.Authorize#publicAccess()]: the
/// operation is reached from the app-scoped SDK sync and the anchor-only BFF
/// catalogue sync, and each entry point keeps its own gate.
public final class SyncEventTypes {

    private SyncEventTypes() {
    }

    public static Operation<SyncEventTypesCommand, EventTypesSynced> of(EventTypeRepository repo) {
        return Operation.<SyncEventTypesCommand, EventTypesSynced>named("SyncEventTypes")
                .validate(cmd -> {
                    if (cmd.applicationCode() == null || cmd.applicationCode().isBlank()) {
                        throw UseCaseException.validation("APPLICATION_CODE_REQUIRED", "Application code is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Map<String, EventType> existingByCode = repo.findByApplication(cmd.applicationCode()).stream()
                            .collect(Collectors.toMap(EventType::code, Function.identity(), (a, _) -> a, LinkedHashMap::new));
                    Set<String> incomingCodes = cmd.eventTypes().stream()
                            .map(SyncEventTypeInput::code).collect(Collectors.toSet());

                    var saves = new ArrayList<SyncSave<EventType>>(cmd.eventTypes().size());
                    var deletes = new ArrayList<SyncDelete<EventType>>();
                    int created = 0;
                    int updated = 0;
                    for (SyncEventTypeInput in : cmd.eventTypes()) {
                        EventType existing = existingByCode.get(in.code());
                        if (existing != null) {
                            EventType et = existing.withName(in.name()).withDescription(in.description());
                            saves.add(new SyncSave<>(et, EventTypeUpdated.of(ec, et)));
                            updated++;
                        } else {
                            EventType et = fromSync(in);
                            saves.add(new SyncSave<>(et, EventTypeCreated.of(ec, et)));
                            created++;
                        }
                    }
                    if (cmd.removeUnlisted()) {
                        existingByCode.values().stream()
                                .filter(et -> et.source() == EventTypeSource.API && !incomingCodes.contains(et.code()))
                                .forEach(et -> deletes.add(new SyncDelete<>(et, EventTypeDeleted.of(ec, et))));
                    }

                    List<String> syncedCodes = cmd.eventTypes().stream().map(SyncEventTypeInput::code).toList();
                    var rollup = EventTypesSynced.of(ec, cmd.applicationCode(), created, updated, deletes.size(), syncedCodes);
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }

    /// A new `API`-sourced event type for a batch row. A malformed code names
    /// the offending row: the batch aborts on the first one, and a bare format
    /// message gives no clue which of N codes failed.
    private static EventType fromSync(SyncEventTypeInput in) {
        try {
            return EventType.create(in.code(), in.name())
                    .withDescription(in.description())
                    .withSource(EventTypeSource.API);
        } catch (UseCaseException e) {
            throw UseCaseException.validation("INVALID_CODE",
                    e.error().message() + " (offending code: \"" + in.code() + "\")");
        }
    }
}
