package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.regex.Pattern;

/// The `code` format rule (spec §4.1, Go `validate.CodePattern`): trimmed and
/// lower-cased, then must start with a lowercase letter and contain only
/// lowercase alphanumerics and hyphens. One parser so create and any future
/// update-with-code entry point reject a malformed code with the same
/// message. Normalisation (trim + lower-case) happens here, not the caller —
/// `"SACreate-Happy"` and `" sacreate-happy "` both parse to `sacreate-happy`.
///
/// @param value the normalised code
public record ServiceAccountCode(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    /// @throws UseCaseException validation `CODE_REQUIRED` when blank,
    ///                          `INVALID_CODE_FORMAT` when the normalised code does not match the pattern
    public static ServiceAccountCode parse(String raw) {
        String normalised = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            throw UseCaseException.validation("CODE_REQUIRED", "code is required");
        }
        if (!PATTERN.matcher(normalised).matches()) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT",
                    "code must start with a lowercase letter and contain only lowercase alphanumeric and hyphens");
        }
        return new ServiceAccountCode(normalised);
    }
}
