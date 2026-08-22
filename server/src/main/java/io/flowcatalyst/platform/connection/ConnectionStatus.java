package io.flowcatalyst.platform.connection;

/// The connection lifecycle state: `ACTIVE` ⇄ `PAUSED` (spec §2). The
/// constant name is the stored and wire string.
public enum ConnectionStatus {
    ACTIVE, PAUSED;

    /// Lenient reader for stored values and the update command's `status`
    /// field: exactly `PAUSED` → `PAUSED`, anything else → `ACTIVE`
    /// (spec §1, open question 4).
    public static ConnectionStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "PAUSED" -> PAUSED;
            default -> ACTIVE;
        };
    }
}
