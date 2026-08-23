package io.flowcatalyst.platform.scheduledjob;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// A scheduled-job code, normalised (trimmed, lower-cased) and validated
/// against the strict hyphen-only resource-code rule (spec §5). The create
/// command and [ScheduledJob#create] both take this, so the format rule
/// lives here once.
///
/// @param value the normalised code
public record ScheduledJobCode(String value) {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]*$");

    public static final String FORMAT_MESSAGE =
            "code must start with a lowercase letter and contain only lowercase alphanumeric and hyphens";

    public ScheduledJobCode {
        Objects.requireNonNull(value, "value");
    }

    /// Normalises and validates a raw code.
    ///
    /// @throws UseCaseException validation `CODE_REQUIRED` when blank,
    ///                          `INVALID_CODE_FORMAT` when it does not match `^[a-z][a-z0-9-]*$`
    public static ScheduledJobCode parse(String raw) {
        String code = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        UseCaseException.requireNonBlank(code, "CODE_REQUIRED", "code is required");
        if (!CODE.matcher(code).matches()) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", FORMAT_MESSAGE);
        }
        return new ScheduledJobCode(code);
    }

    /// A sync-entry code taken as given — not normalised, not pattern-checked
    /// (spec §5, open question 3): the SDK contract predates the format rule.
    ///
    /// @throws UseCaseException validation `CODE_REQUIRED` when blank
    public static ScheduledJobCode verbatim(String raw) {
        UseCaseException.requireNonBlank(raw, "CODE_REQUIRED", "code is required");
        return new ScheduledJobCode(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
