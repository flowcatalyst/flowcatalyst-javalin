package io.flowcatalyst.platform.shared.auth;

/// The principal kind carried on the access token's `type` claim.
public enum PrincipalType {
    USER, SERVICE;

    /// Parses the wire string; `null` for blank or unrecognised values.
    public static PrincipalType parse(String wire) {
        if (wire == null) return null;
        return switch (wire.trim()) {
            case "USER" -> USER;
            case "SERVICE" -> SERVICE;
            default -> null;
        };
    }
}
