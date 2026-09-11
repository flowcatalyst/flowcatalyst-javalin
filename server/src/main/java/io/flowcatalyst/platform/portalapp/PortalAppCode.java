package io.flowcatalyst.platform.portalapp;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.regex.Pattern;

/// The `code` format rule (spec `portal-apps.md` §2.1, §3.3): trimmed then
/// lower-cased, then `^[a-z0-9][a-z0-9_-]{0,99}$`. One parser so `CreateApp`
/// and every lookup-by-code reject/normalise the same way — `"  Customer-Portal "`
/// parses to `customer-portal` (spec §9.2).
///
/// @param value the normalised code
public record PortalAppCode(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,99}$");

    /// Trim + lower-case only, no validation — for a lookup key that must
    /// still find a row even if a caller mistyped case (spec §2.1).
    public static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    /// @throws UseCaseException validation `CODE_INVALID` when the normalised
    ///                          code does not match `^[a-z0-9][a-z0-9_-]{0,99}$`
    public static PortalAppCode parse(String raw) {
        String normalised = normalize(raw);
        if (!PATTERN.matcher(normalised).matches()) {
            throw UseCaseException.validation("CODE_INVALID",
                    "code must be 1-100 lower-case letters, digits, '-' or '_', starting with a letter or digit");
        }
        return new PortalAppCode(normalised);
    }
}
