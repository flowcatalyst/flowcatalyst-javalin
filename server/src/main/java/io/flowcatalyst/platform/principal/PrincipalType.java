package io.flowcatalyst.platform.principal;

/// The principal kind: a human `USER` (identified by email) or the `SERVICE`
/// identity of a service account. The constant name is the stored and wire
/// string (spec §1). Only used for the stored column — nothing parses this
/// enum from the wire (the JWT `type` claim is a distinct, unrelated
/// [io.flowcatalyst.platform.shared.auth.PrincipalType]).
public enum PrincipalType {
    USER, SERVICE;

    /// Strict reader for stored values (X-06, ruled 2026-09-01: never a
    /// silent default). See [PrincipalRepository]'s row mapper, which wraps
    /// [UnrecognisedPrincipalTypeException] in [CorruptPrincipalException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedPrincipalTypeException `s` is `null` or not `USER`/`SERVICE`
    public static PrincipalType parse(String s) {
        return switch (s) {
            case "USER" -> USER;
            case "SERVICE" -> SERVICE;
            case null, default -> throw new UnrecognisedPrincipalTypeException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedPrincipalTypeException extends RuntimeException {
        public UnrecognisedPrincipalTypeException(String raw) {
            super("unrecognised principal type: " + raw);
        }
    }
}
