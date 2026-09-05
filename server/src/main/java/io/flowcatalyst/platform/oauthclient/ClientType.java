package io.flowcatalyst.platform.oauthclient;

/// `PUBLIC` (no secret — SPA/mobile) or `CONFIDENTIAL` (server-side, secret
/// required). The constant name is the stored/wire string.
///
/// [#parse] is used on BOTH boundaries (spec `auth-core.md` §0.5, ruling
/// Q21/X-06): an unrecognised value is REJECTED rather than silently
/// defaulting to `PUBLIC` — coercing an unreadable value to the LESS trusted
/// type would be a security regression, not just a display bug. The wire
/// boundary ([io.flowcatalyst.platform.oauthclient.operations.CreateOAuthClient])
/// catches [UnrecognisedClientTypeException] and re-throws it as validation
/// `INVALID_CLIENT_TYPE`; the stored boundary ([OAuthClientRepository])
/// catches it and wraps it in [CorruptOAuthClientException] — the same
/// `WebhookAuthType` / `CorruptServiceAccountException` pattern.
public enum ClientType {
    PUBLIC, CONFIDENTIAL;

    /// @throws UnrecognisedClientTypeException `s` is `null`, blank, or not
    ///                                         one of the constants above
    public static ClientType parse(String s) {
        return switch (s) {
            case "PUBLIC" -> PUBLIC;
            case "CONFIDENTIAL" -> CONFIDENTIAL;
            case null, default -> throw new UnrecognisedClientTypeException(s);
        };
    }

    /// Thrown by [#parse] for a value outside the recognised set — X-06:
    /// never a silent default.
    public static final class UnrecognisedClientTypeException extends RuntimeException {
        public UnrecognisedClientTypeException(String raw) {
            super("unrecognised OAuth client type: " + raw);
        }
    }
}
