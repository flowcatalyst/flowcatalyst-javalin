package io.flowcatalyst.sdk.usecase;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// The canonical use-case error: a closed set of kinds, each carrying a
/// stable machine-readable `code`, a human `message` and optional structured
/// `details`. The kind drives the HTTP status; the code is what goes on the
/// wire as `{"error": CODE, "message": …, "details": …}`.
///
/// Thrown wrapped in a [UseCaseException]; handlers and transports switch on
/// the error exhaustively:
///
/// ```java
/// int status = switch (error) {
///     case Validation _               -> 400;
///     case Authorization _            -> 403;
///     case NotFound _                 -> 404;
///     case BusinessRule _, Conflict _ -> 409;
///     case Internal _                 -> 500;
/// };
/// ```
public sealed interface UseCaseError {

    String code();

    String message();

    /// Structured details (e.g. field → constraint). Never `null`; may be empty.
    Map<String, Object> details();

    /// Same error with `details` attached.
    UseCaseError withDetails(Map<String, Object> details);

    /// The wire/log name of the kind: `validation`, `business_rule`,
    /// `authorization`, `not_found`, `conflict`, `internal`.
    default String kind() {
        return switch (this) {
            case Validation _ -> "validation";
            case BusinessRule _ -> "business_rule";
            case Authorization _ -> "authorization";
            case NotFound _ -> "not_found";
            case Conflict _ -> "conflict";
            case Internal _ -> "internal";
        };
    }

    /// HTTP status for the kind: 400 / 403 / 404 / 409 / 409 / 500.
    default int httpStatus() {
        return switch (this) {
            case Validation _ -> 400;
            case Authorization _ -> 403;
            case NotFound _ -> 404;
            case BusinessRule _, Conflict _ -> 409;
            case Internal _ -> 500;
        };
    }

    /// Field-level / shape checks on the command.
    record Validation(String code, String message, Map<String, Object> details) implements UseCaseError {
        public Validation {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = copy(details);
        }

        public Validation(String code, String message) {
            this(code, message, Map.of());
        }

        @Override
        public Validation withDetails(Map<String, Object> details) {
            return new Validation(code, message, details);
        }
    }

    /// A domain invariant was violated.
    record BusinessRule(String code, String message, Map<String, Object> details) implements UseCaseError {
        public BusinessRule {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = copy(details);
        }

        public BusinessRule(String code, String message) {
            this(code, message, Map.of());
        }

        @Override
        public BusinessRule withDetails(Map<String, Object> details) {
            return new BusinessRule(code, message, details);
        }
    }

    /// The principal may not act on this resource.
    record Authorization(String code, String message, Map<String, Object> details) implements UseCaseError {
        public Authorization {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = copy(details);
        }

        public Authorization(String code, String message) {
            this(code, message, Map.of());
        }

        @Override
        public Authorization withDetails(Map<String, Object> details) {
            return new Authorization(code, message, details);
        }
    }

    /// The addressed resource does not exist.
    record NotFound(String code, String message, Map<String, Object> details) implements UseCaseError {
        public NotFound {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = copy(details);
        }

        public NotFound(String code, String message) {
            this(code, message, Map.of());
        }

        @Override
        public NotFound withDetails(Map<String, Object> details) {
            return new NotFound(code, message, details);
        }
    }

    /// Uniqueness / state conflict.
    record Conflict(String code, String message, Map<String, Object> details) implements UseCaseError {
        public Conflict {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = copy(details);
        }

        public Conflict(String code, String message) {
            this(code, message, Map.of());
        }

        @Override
        public Conflict withDetails(Map<String, Object> details) {
            return new Conflict(code, message, details);
        }
    }

    /// Infrastructure failure, wrapping the lower-level cause (may be `null`).
    record Internal(String code, String message, Map<String, Object> details, Throwable cause) implements UseCaseError {
        public Internal {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = copy(details);
        }

        public Internal(String code, String message, Throwable cause) {
            this(code, message, Map.of(), cause);
        }

        @Override
        public Internal withDetails(Map<String, Object> details) {
            return new Internal(code, message, details, cause);
        }
    }

    static Validation validation(String code, String message) {
        return new Validation(code, message);
    }

    static BusinessRule businessRule(String code, String message) {
        return new BusinessRule(code, message);
    }

    static Authorization authorization(String code, String message) {
        return new Authorization(code, message);
    }

    static NotFound notFound(String code, String message) {
        return new NotFound(code, message);
    }

    static Conflict conflict(String code, String message) {
        return new Conflict(code, message);
    }

    static Internal internal(String code, String message, Throwable cause) {
        return new Internal(code, message, cause);
    }

    /// Defensive, order-preserving, null-value-tolerant copy (details may
    /// legitimately carry `null` values).
    private static Map<String, Object> copy(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
