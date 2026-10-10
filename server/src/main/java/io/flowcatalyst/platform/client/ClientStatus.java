package io.flowcatalyst.platform.client;

/// The tenant lifecycle state (spec §1–2). The constant name is the stored
/// and wire string. `INACTIVE` is set by "deactivate" ([Client#deactivate],
/// bug 15 of 2026-10-10: it used to hard-delete the row).
public enum ClientStatus {
    ACTIVE, INACTIVE, SUSPENDED;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [ClientRepository]'s row mapper, which wraps
    /// [UnrecognisedClientStatusException] in [CorruptClientException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedClientStatusException `s` is `null` or not one
    ///                                           of the three states
    public static ClientStatus parse(String s) {
        return switch (s) {
            case "ACTIVE" -> ACTIVE;
            case "INACTIVE" -> INACTIVE;
            case "SUSPENDED" -> SUSPENDED;
            case null, default -> throw new UnrecognisedClientStatusException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedClientStatusException extends RuntimeException {
        public UnrecognisedClientStatusException(String raw) {
            super("unrecognised client status: " + raw);
        }
    }
}
