package io.flowcatalyst.platform.serviceaccount;

/// The outbound-webhook authentication scheme (spec §2.1). The constant name
/// is the stored/wire string.
///
/// [#parse] is used on BOTH boundaries, because the spec's owner ruling
/// (X-06, `serviceaccount-fixes.md` drift `6cbe708`) makes them agree: blank
/// means `NONE` (no credentials given is not an error) but any other
/// unrecognised value is REJECTED rather than silently coerced to `NONE` — a
/// misspelled `BEARER_TOKEN` must never silently ship an unauthenticated
/// webhook. The wire boundary ([io.flowcatalyst.platform.serviceaccount.api.ServiceAccountApi])
/// catches [UnrecognisedAuthTypeException] and re-throws it as validation
/// `INVALID_AUTH_TYPE`; the stored boundary ([ServiceAccountRepository])
/// catches it and wraps it in [CorruptServiceAccountException] — exactly the
/// `platform.dispatchjob.DispatchJobStatus` / `CorruptDispatchJobException`
/// pattern.
public enum WebhookAuthType {
    NONE, BEARER_TOKEN, BASIC_AUTH, API_KEY, HMAC_SIGNATURE;

    /// @throws UnrecognisedAuthTypeException `s` is non-blank and not one of
    ///                                       the constants above
    public static WebhookAuthType parse(String s) {
        if (s == null || s.isBlank()) {
            return NONE;
        }
        return switch (s) {
            case "NONE" -> NONE;
            case "BEARER_TOKEN" -> BEARER_TOKEN;
            case "BASIC_AUTH" -> BASIC_AUTH;
            case "API_KEY" -> API_KEY;
            case "HMAC_SIGNATURE" -> HMAC_SIGNATURE;
            default -> throw new UnrecognisedAuthTypeException(s);
        };
    }

    /// Thrown by [#parse] for a non-blank value outside the recognised set.
    public static final class UnrecognisedAuthTypeException extends RuntimeException {
        public UnrecognisedAuthTypeException(String raw) {
            super("unrecognised webhook auth type: " + raw);
        }
    }
}
