package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The scope a client auth config grants users of its e-mail domain: `ANCHOR`
/// (platform staff), `PARTNER` or `CLIENT` (spec §1). The constant name is
/// the stored and wire string. Stored reads are strict (spec §2, X-06): a
/// row outside this closed set must fail loudly, never default silently —
/// [#parse] throws [UnrecognisedConfigTypeException], which
/// [ClientAuthConfigRepository] wraps into [CorruptClientAuthConfigException]
/// carrying the row id. [#parseStrict] is the same closed set for wire input
/// (spec §4.2), turning the same rejection into the validation error the
/// command's validate phase throws.
public enum ConfigType {
    ANCHOR, PARTNER, CLIENT;

    public static final String INVALID_MESSAGE = "configType must be ANCHOR, PARTNER, or CLIENT";

    /// The one strict reader for both the stored column and the wire value.
    ///
    /// @throws UnrecognisedConfigTypeException `s` is `null` or outside the closed set
    public static ConfigType parse(String s) {
        return switch (s) {
            case "ANCHOR" -> ANCHOR;
            case "PARTNER" -> PARTNER;
            case "CLIENT" -> CLIENT;
            case null, default -> throw new UnrecognisedConfigTypeException(s);
        };
    }

    /// The wire parser (spec §4.2): [#parse], with an unrecognised value
    /// reported as the `INVALID_CONFIG_TYPE` validation error instead of the
    /// bare internal exception.
    ///
    /// @throws UseCaseException validation `INVALID_CONFIG_TYPE`
    public static ConfigType parseStrict(String s) {
        try {
            return parse(s);
        } catch (UnrecognisedConfigTypeException e) {
            throw UseCaseException.validation("INVALID_CONFIG_TYPE", INVALID_MESSAGE);
        }
    }

    /// Thrown by [#parse] for a value outside the recognised set — X-06:
    /// never a silent default.
    public static final class UnrecognisedConfigTypeException extends RuntimeException {
        public UnrecognisedConfigTypeException(String raw) {
            super("unrecognised config type: " + raw);
        }
    }
}
