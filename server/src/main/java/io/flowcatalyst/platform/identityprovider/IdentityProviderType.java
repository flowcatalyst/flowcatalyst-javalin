package io.flowcatalyst.platform.identityprovider;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The kind of identity provider: the platform's own password
/// authentication (`INTERNAL`) or an external OpenID Connect provider
/// (`OIDC`). The constant name is the stored and wire string.
public enum IdentityProviderType {
    INTERNAL, OIDC;

    /// Strict reader for STORED values (spec §1, X-06): never a silent
    /// default. See [IdentityProviderRepository]'s row mapper, which wraps
    /// [UnrecognisedIdentityProviderTypeException] in
    /// [CorruptIdentityProviderException] carrying the row id.
    ///
    /// The create command's `type` field ([#parseWire]) stays the pre-X-06
    /// lenient rule (spec §1: an unknown wire `type` is not an error) —
    /// wire input, out of scope for X-06.
    ///
    /// @throws UnrecognisedIdentityProviderTypeException `s` is `null` or
    ///                                                   not `INTERNAL` / `OIDC`
    public static IdentityProviderType parse(String s) {
        return switch (s) {
            case "INTERNAL" -> INTERNAL;
            case "OIDC" -> OIDC;
            case null, default -> throw new UnrecognisedIdentityProviderTypeException(s);
        };
    }

    /// The create command's `type` field: exactly `INTERNAL` or `OIDC`,
    /// anything else (including `null`) is a validation error — owner
    /// ruling 2026-09-06 #19 (X-06 at the wire, Go's `ParseType`).
    /// [CreateIdentityProvider] is the one caller.
    ///
    /// @throws UseCaseException validation `INVALID_TYPE`
    public static IdentityProviderType parseWire(String s) {
        return switch (s) {
            case "INTERNAL" -> INTERNAL;
            case "OIDC" -> OIDC;
            case null, default -> throw UseCaseException.validation("INVALID_TYPE", "type must be INTERNAL or OIDC");
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedIdentityProviderTypeException extends RuntimeException {
        public UnrecognisedIdentityProviderTypeException(String raw) {
            super("unrecognised identity provider type: " + raw);
        }
    }
}
