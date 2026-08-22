package io.flowcatalyst.sdk.usecase;

import java.util.Objects;
import java.util.Optional;

/// The exception that carries a [UseCaseError] out of a use case. Unchecked,
/// so the `Validate` / `Authorize` / `Execute` lambdas stay plain; the
/// transport layer catches it once (e.g. a Javalin `exception(...)` handler)
/// and writes `{"error": code, "message": …, "details": …}` with
/// [UseCaseError#httpStatus()].
///
/// Use the static factories where Go code would `return usecase.Xxx(...)`:
///
/// ```java
/// if (existing != null) throw UseCaseException.conflict("CODE_EXISTS", "Event type with code '" + code + "' already exists");
/// ```
public final class UseCaseException extends RuntimeException {

    private final transient UseCaseError error;

    public UseCaseException(UseCaseError error) {
        super(describe(error), causeOf(error));
        this.error = error;
    }

    public UseCaseError error() {
        return error;
    }

    public String code() {
        return error.code();
    }

    public int httpStatus() {
        return error.httpStatus();
    }

    /// Finds the nearest [UseCaseError] in a cause chain, if any.
    public static Optional<UseCaseError> find(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof UseCaseException uce) return Optional.of(uce.error);
            if (cur.getCause() == cur) break;
        }
        return Optional.empty();
    }

    public static UseCaseException validation(String code, String message) {
        return new UseCaseException(UseCaseError.validation(code, message));
    }

    public static UseCaseException businessRule(String code, String message) {
        return new UseCaseException(UseCaseError.businessRule(code, message));
    }

    public static UseCaseException authorization(String code, String message) {
        return new UseCaseException(UseCaseError.authorization(code, message));
    }

    public static UseCaseException notFound(String code, String message) {
        return new UseCaseException(UseCaseError.notFound(code, message));
    }

    public static UseCaseException conflict(String code, String message) {
        return new UseCaseException(UseCaseError.conflict(code, message));
    }

    public static UseCaseException internal(String code, String message, Throwable cause) {
        return new UseCaseException(UseCaseError.internal(code, message, cause));
    }

    /// The canonical not-found shape for a named resource:
    /// code `<Resource>_NOT_FOUND`, message `<Resource> not found: <id>` —
    /// e.g. `EventType_NOT_FOUND` / `EventType not found: evt_…`. One helper
    /// so every aggregate's 404 reads the same on the wire.
    public static UseCaseException resourceNotFound(String resource, String id) {
        return notFound(resource + "_NOT_FOUND", resource + " not found: " + id);
    }

    /// Throws a validation error when `value` is `null` or blank — the
    /// one-line form of the most common `Validate` phase check.
    public static void requireNonBlank(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw validation(code, message);
        }
    }

    /// `kind: code: message[: cause]` — the same rendering as the Go `Error()`.
    private static String describe(UseCaseError error) {
        Objects.requireNonNull(error, "error");
        String base = error.kind() + ": " + error.code() + ": " + error.message();
        Throwable cause = causeOf(error);
        return cause == null ? base : base + ": " + cause;
    }

    private static Throwable causeOf(UseCaseError error) {
        return error instanceof UseCaseError.Internal internal ? internal.cause() : null;
    }
}
