package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.List;

/// A second-factor mechanism a domain may permit: `TOTP` (RFC 6238
/// authenticator app) or `EMAIL_PIN` (one-time numeric PIN by email). The
/// constant name is the stored and wire string. Two readers (spec §1, §4):
/// [#parseStrict] / [#parseAllStrict] for the wire, which reject anything
/// outside the closed set with `INVALID_2FA_METHOD`; [#readStored] for the
/// junction rows, which drops an unknown value so a stored row always reads
/// (open question 8 — no such row can be produced through this API).
public enum MfaMethod {
    TOTP, EMAIL_PIN;

    public static final String INVALID_MESSAGE = "allowed2faMethods entries must be TOTP or EMAIL_PIN";

    /// The one wire parser for a method string (spec §4).
    ///
    /// @throws UseCaseException validation `INVALID_2FA_METHOD`
    public static MfaMethod parseStrict(String s) {
        return switch (s == null ? "" : s) {
            case "TOTP" -> TOTP;
            case "EMAIL_PIN" -> EMAIL_PIN;
            default -> throw UseCaseException.validation("INVALID_2FA_METHOD", INVALID_MESSAGE);
        };
    }

    /// Parses every wire entry; `null` → empty list.
    ///
    /// @throws UseCaseException validation `INVALID_2FA_METHOD` on the first bad entry
    public static List<MfaMethod> parseAllStrict(List<String> raw) {
        return raw == null ? List.of() : raw.stream().map(MfaMethod::parseStrict).toList();
    }

    /// Lenient reader for stored values: a value outside the closed set is
    /// dropped, never an error; `null` → empty list.
    public static List<MfaMethod> readStored(List<String> stored) {
        return stored == null ? List.of() : stored.stream()
                .map(s -> switch (s == null ? "" : s) {
                    case "TOTP" -> TOTP;
                    case "EMAIL_PIN" -> EMAIL_PIN;
                    default -> null;
                })
                .filter(m -> m != null)
                .toList();
    }
}
