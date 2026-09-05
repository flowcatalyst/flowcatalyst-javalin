package io.flowcatalyst.platform.shared;

/// A row read from storage whose value in some column is not one the reading
/// type recognises (ledger `X-06`, 2026-09-01: "lenient enum reads mask bad
/// rows. Fail loudly."). A strict `parse(String)` on a stored enum throws
/// its own small unchecked exception on an unrecognised value; the
/// repository's row mapper wraps that into one of these, naming the entity
/// and the offending row's id so an operator can find it, and lets it
/// propagate — a list read that hits one corrupt row fails the whole list,
/// never a partial one. [io.flowcatalyst.platform.shared.httperror.HttpError#install]
/// maps every subtype to 500 `CORRUPT_ROW`.
///
/// `CorruptDispatchJobException` is the original, model instance of this
/// shape; every subtype constructs its message the same way.
public class CorruptRowException extends RuntimeException {

    private final String entity;
    private final String rowId;

    public CorruptRowException(String entity, String rowId, Throwable cause) {
        this(entity + " " + rowId + " has a corrupt row: " + cause.getMessage(), entity, rowId, cause);
    }

    /// For a subtype that keeps its own established message wording
    /// (`CorruptDispatchJobException`'s "has a corrupt status") while still
    /// exposing [#entity] / [#rowId] for the shared HTTP mapping.
    protected CorruptRowException(String message, String entity, String rowId, Throwable cause) {
        super(message, cause);
        this.entity = entity;
        this.rowId = rowId;
    }

    /// The entity name, e.g. `"login attempt"` — for logging/grouping.
    public String entity() {
        return entity;
    }

    /// The offending row's id.
    public String rowId() {
        return rowId;
    }
}
