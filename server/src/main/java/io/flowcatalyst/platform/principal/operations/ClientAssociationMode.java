package io.flowcatalyst.platform.principal.operations;

import java.util.Locale;

/// What setting a specific client on an existing principal means (spec §2):
/// `CHANGE_CLIENT` replaces the home client (stays `CLIENT`); `TO_PARTNER`
/// promotes to `PARTNER`, keeping the old home client as a grant. The
/// anchor wildcard `*` ignores the mode. The constant name is the wire string.
public enum ClientAssociationMode {
    CHANGE_CLIENT, TO_PARTNER;

    /// Lenient wire reader (case-insensitive, trimmed): unknown → `null`, which
    /// the operation reports as `MODE_REQUIRED` when a specific client is named.
    public static ClientAssociationMode parse(String s) {
        if (s == null) return null;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "CHANGE_CLIENT" -> CHANGE_CLIENT;
            case "TO_PARTNER" -> TO_PARTNER;
            default -> null;
        };
    }
}
