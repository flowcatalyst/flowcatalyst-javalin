package io.flowcatalyst.platform.subscription;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// A subscription code as the **admin create** stores it: trimmed,
/// lower-cased and matching `^[a-z][a-z0-9-]*$` (spec §4). This is the one
/// place the format rule lives. Sync does not go through it — it stores the
/// SDK's code verbatim after a non-blank check only (spec §7, open question 3).
///
/// @param value the normalised code as it is stored
public record SubscriptionCode(String value) {

    public static final String FORMAT_MESSAGE =
            "code must start with a lowercase letter and contain only lowercase alphanumeric and hyphens";

    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    public SubscriptionCode {
        Objects.requireNonNull(value, "value");
    }

    /// Normalises and validates a raw code.
    ///
    /// @throws UseCaseException validation `CODE_REQUIRED` when blank,
    ///                          `INVALID_CODE_FORMAT` when the normalised code does not match the pattern
    public static SubscriptionCode parse(String raw) {
        String code = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        UseCaseException.requireNonBlank(code, "CODE_REQUIRED", "code is required");
        if (!PATTERN.matcher(code).matches()) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", FORMAT_MESSAGE);
        }
        return new SubscriptionCode(code);
    }
}
