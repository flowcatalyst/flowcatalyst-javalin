package io.flowcatalyst.platform.shared.auth;

/// The principal's tenancy tier (Go `auth.Scope`; the `tier` JWT claim).
public enum Scope {
    ANCHOR, PARTNER, CLIENT;

    /// Parses the wire string; `null` for blank or unrecognised values (Go
    /// keeps the raw string, which then simply never equals `ANCHOR`).
    public static Scope parse(String wire) {
        if (wire == null) return null;
        return switch (wire.trim()) {
            case "ANCHOR" -> ANCHOR;
            case "PARTNER" -> PARTNER;
            case "CLIENT" -> CLIENT;
            default -> null;
        };
    }
}
