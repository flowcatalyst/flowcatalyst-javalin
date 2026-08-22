package io.flowcatalyst.platform.dispatchpool;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// A dispatch-pool code, validated. This is the one place the format rule
/// lives (spec §1, §4): a lowercase letter followed by lowercase
/// alphanumerics, hyphens or underscores. The admin create command
/// normalises (trim + lowercase) through [#normalised] before parsing; the
/// SDK sync parses the raw value, so `My-Pool` is accepted by the UI and
/// rejected by sync (spec open question 3).
///
/// @param value the validated code, exactly as it will be stored
public record DispatchPoolCode(String value) {

    public static final String FORMAT_MESSAGE =
            "code must start with a lowercase letter and contain only lowercase alphanumeric, hyphens, underscores";

    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*$");

    public DispatchPoolCode {
        Objects.requireNonNull(value, "value");
    }

    /// Validates `code` as given — no trimming, no case folding.
    ///
    /// @throws UseCaseException validation `INVALID_CODE_FORMAT`
    public static DispatchPoolCode parse(String code) {
        if (code == null || !PATTERN.matcher(code).matches()) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", FORMAT_MESSAGE);
        }
        return new DispatchPoolCode(code);
    }

    /// Trims and lowercases, then validates — the admin create rule.
    ///
    /// @throws UseCaseException validation `INVALID_CODE_FORMAT`
    public static DispatchPoolCode normalised(String raw) {
        return parse(raw == null ? null : raw.trim().toLowerCase(Locale.ROOT));
    }
}
