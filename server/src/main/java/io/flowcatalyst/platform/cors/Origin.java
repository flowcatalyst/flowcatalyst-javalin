package io.flowcatalyst.platform.cors;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;
import java.util.regex.Pattern;

/// A browser origin as the allowlist accepts it (spec §4). This is the one
/// place the format rule lives: the add command's validate phase and
/// [CorsOrigin#create] both go through [#parse], so every entry point
/// rejects a malformed origin with the same error and stores the same
/// trimmed form (uniqueness is on that form).
///
/// @param value the trimmed origin
public record Origin(String value) {

    public static final String FORMAT_MESSAGE =
            "Origin must be a valid URL (e.g. https://example.com or http://localhost:3000)";

    /// `http(s)://host[:port]`, the rules pinned in spec §4 (and by
    /// `CorsOriginTest`): scheme `http` / `https` lower-case only; host of
    /// ASCII letters (either case, preserved), digits, `.`, `-` and `*` (the
    /// `*` admits wildcard hosts anywhere, even `https://*`; spec open
    /// question 1), not starting or ending with `.` / `-`; optional `:` +
    /// ASCII digits with no range check; nothing after — no path (not even
    /// `/`), query, fragment or userinfo.
    private static final Pattern ORIGIN = Pattern.compile("^https?://[a-zA-Z0-9*]([a-zA-Z0-9*.-]*[a-zA-Z0-9*])?(:\\d+)?$");

    public Origin {
        Objects.requireNonNull(value, "value");
    }

    /// Trims and validates a raw origin.
    ///
    /// @throws UseCaseException validation `ORIGIN_REQUIRED` when blank,
    ///                          `INVALID_ORIGIN_FORMAT` when the trimmed form is not `scheme://host[:port]`
    public static Origin parse(String raw) {
        UseCaseException.requireNonBlank(raw, "ORIGIN_REQUIRED", "Origin is required");
        String trimmed = raw.trim();
        if (!ORIGIN.matcher(trimmed).matches()) {
            throw UseCaseException.validation("INVALID_ORIGIN_FORMAT", FORMAT_MESSAGE);
        }
        return new Origin(trimmed);
    }
}
