package io.flowcatalyst.platform.connection;

/// The connection lifecycle state: `ACTIVE` ⇄ `PAUSED` (spec §2). The
/// constant name is the stored and wire string.
public enum ConnectionStatus {
    ACTIVE, PAUSED;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [ConnectionRepository]'s row mapper, which wraps
    /// [UnrecognisedConnectionStatusException] in
    /// [CorruptConnectionException] carrying the row id.
    ///
    /// This is the STORED reader only. The update command's `status` field
    /// (open question 4) is a separate, deliberately lenient wire rule —
    /// see [#parseCommandStatus] — untouched by X-06, which is about
    /// database rows, not request bodies.
    ///
    /// @throws UnrecognisedConnectionStatusException `s` is `null` or not
    ///                                               `ACTIVE` / `PAUSED`
    public static ConnectionStatus parse(String s) {
        return switch (s) {
            case "ACTIVE" -> ACTIVE;
            case "PAUSED" -> PAUSED;
            case null, default -> throw new UnrecognisedConnectionStatusException(s);
        };
    }

    /// The pre-X-06 lenient reader, kept ONLY for the update command's
    /// `status` field (spec §1, open question 4 — wire-side, out of scope
    /// for X-06): exactly `PAUSED` → `PAUSED`, anything else (including an
    /// unrecognised string) → `ACTIVE`. [UpdateConnection] is the one caller.
    public static ConnectionStatus parseCommandStatus(String s) {
        return switch (s == null ? "" : s) {
            case "PAUSED" -> PAUSED;
            default -> ACTIVE;
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedConnectionStatusException extends RuntimeException {
        public UnrecognisedConnectionStatusException(String raw) {
            super("unrecognised connection status: " + raw);
        }
    }
}
