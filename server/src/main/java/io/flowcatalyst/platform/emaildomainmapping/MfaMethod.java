package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.List;

/// A second-factor mechanism a domain may permit: `TOTP` (RFC 6238
/// authenticator app) or `EMAIL_PIN` (one-time numeric PIN by email). The
/// constant name is the stored and wire string. There is no lenient reader:
/// the set is closed and every stored value was written from a parsed one
/// (spec §1, open question 8).
public enum MfaMethod {
    TOTP, EMAIL_PIN;

    public static final String INVALID_MESSAGE = "allowed2faMethods entries must be TOTP or EMAIL_PIN";

    /// The one parser for a method string (spec §4).
    ///
    /// @throws UseCaseException validation `INVALID_2FA_METHOD`
    public static MfaMethod parse(String s) {
        return switch (s == null ? "" : s) {
            case "TOTP" -> TOTP;
            case "EMAIL_PIN" -> EMAIL_PIN;
            default -> throw UseCaseException.validation("INVALID_2FA_METHOD", INVALID_MESSAGE);
        };
    }

    /// Parses every entry; `null` → empty list.
    ///
    /// @throws UseCaseException validation `INVALID_2FA_METHOD` on the first bad entry
    public static List<MfaMethod> parseAll(List<String> raw) {
        return raw == null ? List.of() : raw.stream().map(MfaMethod::parse).toList();
    }
}
