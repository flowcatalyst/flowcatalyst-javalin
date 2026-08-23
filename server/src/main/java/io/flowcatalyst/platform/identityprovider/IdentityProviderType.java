package io.flowcatalyst.platform.identityprovider;

/// The kind of identity provider: the platform's own password
/// authentication (`INTERNAL`) or an external OpenID Connect provider
/// (`OIDC`). The constant name is the stored and wire string.
public enum IdentityProviderType {
    INTERNAL, OIDC;

    /// Lenient reader for stored and wire values: anything but `OIDC`
    /// (including `null`) is `INTERNAL` (spec §1).
    public static IdentityProviderType parse(String s) {
        return "OIDC".equals(s) ? OIDC : INTERNAL;
    }
}
