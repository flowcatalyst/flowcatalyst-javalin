package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;

/// A route's authentication requirement (spec `function-registry.md` §4.1,
/// §4.4, §4.5): `BEARER` or `NONE`. Absent on the wire resolves to `BEARER`
/// — a route is never public by omission (§4.5, §8 M6). Written lower-case
/// wherever the manifest's own JSON shape appears.
public enum AuthMode {
    BEARER, NONE;

    /// Stored reader — exact constant name.
    ///
    /// @throws IllegalArgumentException `raw` is not `BEARER` or `NONE`
    public static AuthMode parse(String raw) {
        return switch (raw) {
            case "BEARER" -> BEARER;
            case "NONE" -> NONE;
            case null, default -> throw new IllegalArgumentException("unrecognised auth mode: " + raw);
        };
    }

    /// Wire reader — case-insensitive (spec §4.3 `ROUTE_INVALID`: "auth not
    /// bearer/none"). Does **not** apply the absent-means-`BEARER` default —
    /// that is [Manifest]'s job (spec §4.5); this throws on a genuinely
    /// missing or unrecognised value.
    ///
    /// @throws UseCaseException validation `ROUTE_INVALID`
    public static AuthMode parseStrict(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "bearer" -> BEARER;
            case "none" -> NONE;
            default -> throw UseCaseException.validation("ROUTE_INVALID", "auth must be bearer or none");
        };
    }

    /// The lower-case spelling used in the manifest's own JSON shape (spec §4.4).
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
