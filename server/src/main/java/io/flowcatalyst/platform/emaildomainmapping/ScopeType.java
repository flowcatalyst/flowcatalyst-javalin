package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The scope at which a mapping operates: `ANCHOR` (platform staff),
/// `PARTNER` (a partner organisation bound to a primary client) or `CLIENT`
/// (a tenant's own users). The constant name is the stored and wire string.
public enum ScopeType {
    ANCHOR, PARTNER, CLIENT;

    /// Lenient reader for stored values: unknown (and `null`) → `ANCHOR`
    /// (spec §1, open question 8).
    public static ScopeType parse(String s) {
        return switch (s == null ? "" : s) {
            case "PARTNER" -> PARTNER;
            case "CLIENT" -> CLIENT;
            default -> ANCHOR;
        };
    }

    /// Strict reader for wire values (spec §4).
    ///
    /// @throws UseCaseException validation `INVALID_SCOPE_TYPE`
    public static ScopeType parseStrict(String s) {
        return switch (s == null ? "" : s) {
            case "ANCHOR" -> ANCHOR;
            case "PARTNER" -> PARTNER;
            case "CLIENT" -> CLIENT;
            default -> throw UseCaseException.validation("INVALID_SCOPE_TYPE",
                    "scopeType must be ANCHOR, PARTNER, or CLIENT");
        };
    }

    /// Whether a mapping of this scope must name a primary client (spec §4).
    public boolean requiresPrimaryClient() {
        return switch (this) {
            case PARTNER, CLIENT -> true;
            case ANCHOR -> false;
        };
    }
}
