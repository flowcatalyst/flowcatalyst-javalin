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
                // The target client is a command field, so the per-resource check
                // can run before execute: a client-bound create needs access to that
                // client; a platform-wide (null clientId) create needs anchor.
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    if (repo.findByCode(cmd.code()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Event type with code '" + cmd.code() + "' already exists");
                    }
                    EventType et = EventType.create(cmd.code(), cmd.name())
                            .withDescription(cmd.description())
                            .withClientId(cmd.clientId())
                            .withCreatedBy(ec.principalId());
                    if (cmd.schema() != null && !cmd.schema().isNull()) {
                        et = et.addSchemaVersion("1.0", cmd.schema());
                    }
                    return Plan.save(et, repo, EventTypeCreated.of(ec, et));
                });
    }
}
