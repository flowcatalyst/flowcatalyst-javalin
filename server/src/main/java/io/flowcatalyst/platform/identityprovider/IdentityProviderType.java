package io.flowcatalyst.platform.identityprovider;

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

    /// The pre-X-06 lenient reader, kept ONLY for the create command's
    /// `type` field: exactly `OIDC` → `OIDC`, anything else (including
    /// `null`) → `INTERNAL` (spec §1). [CreateIdentityProvider] is the one
    /// caller.
    public static IdentityProviderType parseWire(String s) {
        return "OIDC".equals(s) ? OIDC : INTERNAL;
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedIdentityProviderTypeException extends RuntimeException {
        public UnrecognisedIdentityProviderTypeException(String raw) {
            super("unrecognised identity provider type: " + raw);
        }
    }
}
