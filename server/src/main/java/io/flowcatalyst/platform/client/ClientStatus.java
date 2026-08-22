package io.flowcatalyst.platform.client;

/// The tenant lifecycle state (spec §1–2). The constant name is the stored
/// and wire string. `INACTIVE` exists in the catalogue but no transition in
/// this aggregate sets it: "deactivate" on the API is a hard delete of the
/// row, not a move to `INACTIVE` (spec §3; §9, open questions 1–2).
public enum ClientStatus {
    ACTIVE, INACTIVE, SUSPENDED;

    /// Lenient reader for stored values: unknown → `ACTIVE` (spec §1).
    public static ClientStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "INACTIVE" -> INACTIVE;
            case "SUSPENDED" -> SUSPENDED;
            default -> ACTIVE;
        };
    }
}
