package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.SchemaType;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents.EventTypeSchemaAdded;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Appends a `FINALISING` schema version ([EventType#addSchemaVersion]) and
/// emits [EventTypeSchemaAdded]. The schema is stored as the user wrote it
/// (bug 3): a JSON Schema sent as a JSON string holding the document is stored
/// as the document, XSD and PROTO as text ([SchemaType#normaliseContent]); the
/// MIME type and schema type are honoured.
public final class AddSchema {

    /// `msg_event_type_spec_versions.mime_type`'s width.
    static final int MAX_MIME_TYPE_LENGTH = 100;

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
                    if (cmd.mimeType() != null && cmd.mimeType().strip().length() > MAX_MIME_TYPE_LENGTH) {
                        throw UseCaseException.validation("INVALID_MIME_TYPE", "mimeType is longer than 100 characters");
                    }
                    SchemaType.parseWire(cmd.schemaType()).normaliseContent(cmd.schema());
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    SchemaType type = SchemaType.parseWire(cmd.schemaType());
                    String mimeType = cmd.mimeType() == null || cmd.mimeType().isBlank()
                            ? type.defaultMimeType() : cmd.mimeType().strip();
                    EventType et = Access.loadScoped(repo, cmd.eventTypeId())
                            .addSchemaVersion(cmd.version(), type.normaliseContent(cmd.schema()), type, mimeType);
                    return Plan.save(et, repo, EventTypeSchemaAdded.of(ec, et, cmd.version()));
                });
    }
}
