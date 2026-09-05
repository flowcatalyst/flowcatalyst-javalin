package io.flowcatalyst.platform.principal;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// A principal's tenancy tier (spec §1): `ANCHOR` reaches every client,
/// `PARTNER` the clients it holds access grants for, `CLIENT` its home
/// client only. The constant name is the stored and wire string.
///
/// Two readers (CONVENTIONS §2): the stored column now reads strictly
/// (X-06, ruled 2026-09-01 — this superseded the previous "unknown is the
/// most restrictive tier" default, which masked a corrupt row instead of
/// failing loudly) while the wire rejects unknown values with `INVALID_SCOPE`.
public enum UserScope {
    ANCHOR, PARTNER, CLIENT;

    public static final String INVALID_SCOPE_MESSAGE = "scope must be ANCHOR, PARTNER, or CLIENT";

    /// Strict reader for STORED values (X-06): never a silent default. See
    /// [PrincipalRepository]'s row mapper, which wraps
    /// [UnrecognisedUserScopeException] in [CorruptPrincipalException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedUserScopeException `s` is `null` or not `ANCHOR`/`PARTNER`/`CLIENT`
    public static UserScope parse(String s) {
        return switch (s) {
            case "ANCHOR" -> ANCHOR;
            case "PARTNER" -> PARTNER;
            case "CLIENT" -> CLIENT;
            case null, default -> throw new UnrecognisedUserScopeException(s);
        };
    }

    /// Wire reader: the exact constant name, or validation `INVALID_SCOPE`.
    public static UserScope parseStrict(String s) {
        return switch (s == null ? "" : s) {
            case "ANCHOR" -> ANCHOR;
            case "PARTNER" -> PARTNER;
            case "CLIENT" -> CLIENT;
            default -> throw UseCaseException.validation("INVALID_SCOPE", INVALID_SCOPE_MESSAGE);
        };
    }

    public boolean isAnchor() {
        return this == ANCHOR;
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedUserScopeException extends RuntimeException {
        public UnrecognisedUserScopeException(String raw) {
            super("unrecognised user scope: " + raw);
        }
    }
}
