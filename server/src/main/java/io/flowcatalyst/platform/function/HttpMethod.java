package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Optional;

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

    /// Wire reader — case-insensitive (`function-invocation.md` §3: an
    /// unknown method is `ENDPOINT_INVALID`).
    ///
    /// Non-throwing companion of [#parseStrict]: empty on any unrecognised
    /// value, never throws — [Manifest]'s collecting parser uses this
    /// instead of catching [#parseStrict]'s exception (`CONVENTIONS.md` §8).
    public static Optional<HttpMethod> tryParseStrict(String raw) {
        try {
            return Optional.of(parse(raw == null ? null : raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /// @throws UseCaseException validation `ENDPOINT_INVALID`
    public static HttpMethod parseStrict(String raw) {
        return tryParseStrict(raw)
                .orElseThrow(() -> UseCaseException.validation("ENDPOINT_INVALID", "unknown HTTP method: " + raw));
    }
}
