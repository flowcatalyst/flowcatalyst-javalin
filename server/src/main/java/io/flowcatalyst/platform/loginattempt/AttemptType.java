package io.flowcatalyst.platform.loginattempt;

/// The kind of login an attempt records (spec §1). The constant name is the
/// stored and wire string. `DEVELOPER_TOKEN` is a human minting their own
/// developer credential and is kept apart from `SERVICE_ACCOUNT_TOKEN` so the
/// trail never blurs the two.
public enum AttemptType {
    USER_LOGIN, SERVICE_ACCOUNT_TOKEN, DEVELOPER_TOKEN;

    /// Lenient reader for stored values: unknown → `USER_LOGIN` (spec §1).
    public static AttemptType parse(String s) {
        return switch (s == null ? "" : s) {
            case "SERVICE_ACCOUNT_TOKEN" -> SERVICE_ACCOUNT_TOKEN;
            case "DEVELOPER_TOKEN" -> DEVELOPER_TOKEN;
            default -> USER_LOGIN;
        };
    }
}
