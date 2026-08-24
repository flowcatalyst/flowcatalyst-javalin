package io.flowcatalyst.platform.principal;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// A principal's tenancy tier (spec §1): `ANCHOR` reaches every client,
/// `PARTNER` the clients it holds access grants for, `CLIENT` its home
/// client only. The constant name is the stored and wire string.
///
/// Two readers (CONVENTIONS §2): the stored column reads leniently — a
/// `null` or unknown value is the most restrictive tier — while the wire
/// rejects unknown values with `INVALID_SCOPE`.
public enum UserScope {
    ANCHOR, PARTNER, CLIENT;

    public static final String INVALID_SCOPE_MESSAGE = "scope must be ANCHOR, PARTNER, or CLIENT";

    /// Lenient stored reader: `null` / unknown → `CLIENT`.
    public static UserScope parse(String s) {
        return switch (s == null ? "" : s) {
            case "ANCHOR" -> ANCHOR;
            case "PARTNER" -> PARTNER;
            default -> CLIENT;
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
}
