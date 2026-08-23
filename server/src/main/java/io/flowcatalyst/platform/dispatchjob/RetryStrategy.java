package io.flowcatalyst.platform.dispatchjob;

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

    /// Lenient reader for stored values: legacy aliases `IMMEDIATE` and
    /// `FIXED_DELAY`; unknown (and `null`) → `EXPONENTIAL`.
    public static RetryStrategy parse(String s) {
        return switch (s == null ? "" : s) {
            case "immediate", "IMMEDIATE" -> IMMEDIATE;
            case "fixed", "FIXED_DELAY" -> FIXED;
            default -> EXPONENTIAL;
        };
    }
}
