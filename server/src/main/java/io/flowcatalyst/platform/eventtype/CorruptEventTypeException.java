package io.flowcatalyst.platform.eventtype;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `msg_event_types` or `msg_event_type_spec_versions` whose
/// `status`, `source` or `schema_type` column holds a value the relevant
/// enum's `parse` does not recognise (X-06: never a silent default). Carries
/// the offending row's id — `entity()` says which table (`"event type"` or
/// `"event type spec version"`).
public final class CorruptEventTypeException extends CorruptRowException {

    public CorruptEventTypeException(String entity, String rowId, Throwable cause) {
        super(entity, rowId, cause);
    }
}
