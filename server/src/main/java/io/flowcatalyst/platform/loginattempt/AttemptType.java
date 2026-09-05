package io.flowcatalyst.platform.loginattempt;

/// The kind of login an attempt records (spec §1). The constant name is the
/// stored and wire string. `DEVELOPER_TOKEN` is a human minting their own
/// developer credential and is kept apart from `SERVICE_ACCOUNT_TOKEN` so the
/// trail never blurs the two.
public enum AttemptType {
    USER_LOGIN, SERVICE_ACCOUNT_TOKEN, DEVELOPER_TOKEN;

    /// Strict reader for stored values (X-06): never a silent default — see
    /// [LoginAttemptRepository]'s row mapper, which wraps
    /// [UnrecognisedAttemptTypeException] in [CorruptLoginAttemptException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedAttemptTypeException `s` is `null` or not one of
    ///                                          the three recognised values
    public static AttemptType parse(String s) {
        return switch (s) {
            case "USER_LOGIN" -> USER_LOGIN;
            case "SERVICE_ACCOUNT_TOKEN" -> SERVICE_ACCOUNT_TOKEN;
            case "DEVELOPER_TOKEN" -> DEVELOPER_TOKEN;
            case null, default -> throw new UnrecognisedAttemptTypeException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedAttemptTypeException extends RuntimeException {
        public UnrecognisedAttemptTypeException(String raw) {
            super("unrecognised login attempt type: " + raw);
        }
    }
}
