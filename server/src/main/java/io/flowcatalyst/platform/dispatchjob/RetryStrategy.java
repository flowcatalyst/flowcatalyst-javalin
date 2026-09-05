package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Backoff between delivery attempts (spec §1.1). The **stored and wire
/// strings are lowercase** (`immediate` / `fixed` / `exponential`) — a Java
/// constant cannot be, so this enum carries [#wire] explicitly; that is the
/// one documented deviation from "constant name = stored string".
public enum RetryStrategy {
    IMMEDIATE("immediate"),
    FIXED("fixed"),
    EXPONENTIAL("exponential");

    private final String wire;

    RetryStrategy(String wire) {
        this.wire = wire;
    }

    /// The stored / wire string.
    public String wire() {
        return wire;
    }

    /// Strict reader for stored values (spec §1.1, X-06): legacy aliases
    /// `IMMEDIATE` and `FIXED_DELAY` are accepted deliberately — real values
    /// the column has held, not wire input — and `null` is the column's own
    /// documented "unset" state (`retry_strategy` is nullable specifically so
    /// this defaults; migration `V7`'s check constraint permits it
    /// explicitly: "nullable (defaults to exponential when unset)") — but any
    /// other value is rejected; never a silent default for a value we
    /// actually don't recognise. Only used for the stored column — nothing
    /// parses this enum from the wire. See [DispatchJobRepository]'s row
    /// mapper, which wraps [UnrecognisedRetryStrategyException] in
    /// [CorruptDispatchJobException] carrying the row id.
    ///
    /// @throws UnrecognisedRetryStrategyException `s` is non-`null` and not
    ///                                            one of the recognised/legacy values above
    public static RetryStrategy parse(String s) {
        if (s == null) return EXPONENTIAL;
        return switch (s) {
            case "immediate", "IMMEDIATE" -> IMMEDIATE;
            case "fixed", "FIXED_DELAY" -> FIXED;
            case "exponential" -> EXPONENTIAL;
            default -> throw new UnrecognisedRetryStrategyException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedRetryStrategyException extends RuntimeException {
        public UnrecognisedRetryStrategyException(String raw) {
            super("unrecognised retry strategy: " + raw);
        }
    }

    /// Strict reader for the ingest wire boundary (sdk-ingest spec §4.2,
    /// X-06): an absent/blank `retryStrategy` is the documented
    /// "unspecified" case and defaults to `EXPONENTIAL`, but a non-blank,
    /// unrecognised value is a caller error — never silently coerced.
    ///
    /// @throws UseCaseException validation `INVALID_RETRY_STRATEGY`
    public static RetryStrategy parseStrict(String s) {
        if (s == null || s.isBlank()) return EXPONENTIAL;
        return switch (s) {
            case "immediate", "IMMEDIATE" -> IMMEDIATE;
            case "fixed", "FIXED_DELAY" -> FIXED;
            case "exponential" -> EXPONENTIAL;
            default -> throw UseCaseException.validation("INVALID_RETRY_STRATEGY",
                    "unknown retry strategy \"" + s + "\"; must be immediate, fixed, or exponential");
        };
    }
}
