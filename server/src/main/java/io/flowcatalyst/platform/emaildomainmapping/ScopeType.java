package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The scope at which a mapping operates: `ANCHOR` (platform staff),
/// `PARTNER` (a partner organisation bound to a primary client) or `CLIENT`
/// (a tenant's own users). The constant name is the stored and wire string.
public enum ScopeType {
    ANCHOR, PARTNER, CLIENT;

    /// Strict reader for stored values (spec §1, open question 8; X-06):
    /// unknown (and `null`) used to default to `ANCHOR`, the MOST privileged
    /// scope — a corrupted or truncated column would silently grant
    /// platform-staff scope to a mapping that was never meant to have it.
    /// There is no default here: [EmailDomainMappingRepository]'s row mapper
    /// wraps [UnrecognisedScopeTypeException] in
    /// [CorruptEmailDomainMappingException] carrying the row id.
    ///
    /// @throws UnrecognisedScopeTypeException `s` is `null` or not one of
    ///                                        `ANCHOR` / `PARTNER` / `CLIENT`
    public static ScopeType parse(String s) {
        return switch (s) {
            case "ANCHOR" -> ANCHOR;
            case "PARTNER" -> PARTNER;
            case "CLIENT" -> CLIENT;
            case null, default -> throw new UnrecognisedScopeTypeException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default (a privilege-escalation bug when the
    /// default is the most-privileged scope).
    public static final class UnrecognisedScopeTypeException extends RuntimeException {
        public UnrecognisedScopeTypeException(String raw) {
            super("unrecognised scope type: " + raw);
        }
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
