package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeCode;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates an event type (unique by code), optionally minting spec version
/// `1.0` from the supplied schema, and emits [EventTypeCreated].
public final class CreateEventType {

    private CreateEventType() {
    }

    public static Operation<CreateCommand, EventTypeCreated> of(EventTypeRepository repo) {
        return Operation.<CreateCommand, EventTypeCreated>named("CreateEventType")
                .validate(cmd -> {
                    if (cmd.code() == null || cmd.code().isBlank()) {
                        throw UseCaseException.validation("CODE_REQUIRED", "Event type code is required");
                    }
                    if (cmd.name() == null || cmd.name().isBlank()) {
                        throw UseCaseException.validation("NAME_REQUIRED", "Event type name is required");
                    }
                    EventTypeCode.parse(cmd.code());
                })
                // Every event type is platform-wide, so creating one is a
                // platform-level act: anchor (or super-admin) reach, the scope
                // check on no client. A clientId sent on the wire used to pass
                // this check for the caller's own client and then be dropped,
                // creating a platform-wide type (spec §5, 2026-10-11 F2).
                .authorize(_ -> Checks.checkScopeAccess(Auth.current(), null))
                .execute((cmd, ec) -> {
                    if (repo.findByCode(cmd.code()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Event type with code '" + cmd.code() + "' already exists");
                    }
                    EventType et = EventType.create(cmd.code(), cmd.name())
                            .withDescription(cmd.description())
                            .withClientScoped(cmd.clientScoped())
                            .withCreatedBy(ec.principalId());
                    if (cmd.schema() != null && !cmd.schema().isNull()) {
                        et = et.addSchemaVersion("1.0", cmd.schema());
                    }
                    return Plan.save(et, repo, EventTypeCreated.of(ec, et));
                });
    }
}
