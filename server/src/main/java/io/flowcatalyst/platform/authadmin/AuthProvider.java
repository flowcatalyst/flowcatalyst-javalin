package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// How a client auth config's e-mail domain authenticates: `INTERNAL`
/// (password login) or `OIDC` (spec §1). The constant name is the stored
/// and wire string. Stored reads are strict (spec §2, X-06): see
/// [ConfigType] for the shared rationale — [#parse] throws
/// [UnrecognisedAuthProviderException], wrapped by
/// [ClientAuthConfigRepository] into [CorruptClientAuthConfigException];
/// [#parseStrict] is the wire parser (spec §4.2).
public enum AuthProvider {
    INTERNAL, OIDC;

    public static final String INVALID_MESSAGE = "authProvider must be INTERNAL or OIDC";

    /// The one strict reader for both the stored column and the wire value.
    ///
    /// @throws UnrecognisedAuthProviderException `s` is `null` or outside the closed set
    public static AuthProvider parse(String s) {
        return switch (s) {
            case "INTERNAL" -> INTERNAL;
            case "OIDC" -> OIDC;
            case null, default -> throw new UnrecognisedAuthProviderException(s);
        };
    }

    /// The wire parser (spec §4.2): [#parse], with an unrecognised value
    /// reported as the `INVALID_AUTH_PROVIDER` validation error instead of
    /// the bare internal exception.
    ///
    /// @throws UseCaseException validation `INVALID_AUTH_PROVIDER`
    public static AuthProvider parseStrict(String s) {
        try {
            return parse(s);
        } catch (UnrecognisedAuthProviderException e) {
            throw UseCaseException.validation("INVALID_AUTH_PROVIDER", INVALID_MESSAGE);
        }
    }

    /// Thrown by [#parse] for a value outside the recognised set — X-06:
    /// never a silent default.
    public static final class UnrecognisedAuthProviderException extends RuntimeException {
        public UnrecognisedAuthProviderException(String raw) {
            super("unrecognised auth provider: " + raw);
        }
    }
}
