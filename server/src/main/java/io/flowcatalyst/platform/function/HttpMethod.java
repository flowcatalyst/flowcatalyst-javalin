package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;

/// An HTTP method a route may accept (spec `function-registry.md` §5, §4.4).
/// Written upper-case on the wire and in a stored manifest — the spelling of
/// §4.1.
public enum HttpMethod {
    GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS;

    /// Stored reader — exact constant name, no case-folding.
    ///
    /// @throws IllegalArgumentException `raw` is not one of the seven constants
    public static HttpMethod parse(String raw) {
        return switch (raw) {
            case "GET" -> GET;
            case "HEAD" -> HEAD;
            case "POST" -> POST;
            case "PUT" -> PUT;
            case "PATCH" -> PATCH;
            case "DELETE" -> DELETE;
            case "OPTIONS" -> OPTIONS;
            case null, default -> throw new IllegalArgumentException("unrecognised HTTP method: " + raw);
        };
    }

    /// Wire reader — case-insensitive (spec §4.3 `ROUTE_INVALID`: "an
    /// unknown method").
    ///
    /// @throws UseCaseException validation `ROUTE_INVALID`
    public static HttpMethod parseStrict(String raw) {
        try {
            return parse(raw == null ? null : raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw UseCaseException.validation("ROUTE_INVALID", "unknown HTTP method: " + raw);
        }
    }
}
