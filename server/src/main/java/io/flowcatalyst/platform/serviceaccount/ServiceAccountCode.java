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

    /// The reserved namespace of an application's own service account
    /// (`app:<applicationCode>`, written by provisioning and `fcdev init`) —
    /// owner ruling 2026-09-06 #16: the value object admits it, the API's
    /// create path ([#parseUserChosen]) refuses it.
    public static final String APPLICATION_PREFIX = "app:";

    private static final Pattern PATTERN = Pattern.compile("^(app:)?[a-z][a-z0-9-]*$");

    /// [#parse], then refuses the reserved `app:` namespace — the rule for a
    /// code chosen by a caller of `POST /api/service-accounts`.
    ///
    /// @throws UseCaseException validation `RESERVED_CODE` for an `app:`-prefixed code
    public static ServiceAccountCode parseUserChosen(String raw) {
        ServiceAccountCode code = parse(raw);
        if (code.value().startsWith(APPLICATION_PREFIX)) {
            throw UseCaseException.validation("RESERVED_CODE",
                    "codes starting with 'app:' are reserved for application service accounts");
        }
        return code;
    }

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
        if (normalised.equals(APPLICATION_PREFIX)) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", "an application service-account code needs the application code after 'app:'");
        }
        return new ServiceAccountCode(normalised);
    }
}
