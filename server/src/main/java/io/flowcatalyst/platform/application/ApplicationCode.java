package io.flowcatalyst.platform.application;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// A normalised application code (spec §1.1, §4). This is the one place the
/// format rule lives: the create command's validate phase and
/// [Application#create] both go through [#parse], so every entry point
/// rejects a bad code with the same `CODE_REQUIRED` / `INVALID_CODE_FORMAT`
/// error and the same message.
///
/// Normalisation (trim + lower-case) happens *before* the pattern check, so
/// `"  Logistics_Portal "` is accepted as `logistics_portal`.
///
/// @param value the normalised code
public record ApplicationCode(String value) {

    public static final String FORMAT_MESSAGE =
            "code must start with a lowercase letter and contain only lowercase alphanumerics, hyphens, and underscores";

    /// Underscores are allowed on purpose: real codes use them (`logistics_portal`).
    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*$");

    public ApplicationCode {
        Objects.requireNonNull(value, "value");
    }

    /// Trims, lower-cases and validates a raw code.
    ///
    /// @throws UseCaseException validation `CODE_REQUIRED` when blank,
    ///                          `INVALID_CODE_FORMAT` when it does not match the pattern
    public static ApplicationCode parse(String raw) {
        String code = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        UseCaseException.requireNonBlank(code, "CODE_REQUIRED", "code is required");
        if (!PATTERN.matcher(code).matches()) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", FORMAT_MESSAGE);
        }
        return new ApplicationCode(code);
    }
}
