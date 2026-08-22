package io.flowcatalyst.platform.subscription;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;
import java.util.regex.Pattern;

/// A delivery endpoint as the **admin create/update** accept it: an
/// `http(s)://…` URL (spec §4). The one place the rule lives. Sync requires
/// only a non-blank target and stores it verbatim (spec §7, open question 3).
///
/// @param value the URL exactly as given (no normalisation)
public record EndpointUrl(String value) {

    public static final String FORMAT_MESSAGE = "endpoint must be a http(s) URL";

    private static final Pattern PATTERN = Pattern.compile("^https?://.+");

    public EndpointUrl {
        Objects.requireNonNull(value, "value");
    }

    /// Validates a raw endpoint.
    ///
    /// @throws UseCaseException validation `INVALID_ENDPOINT` when absent or not an http(s) URL
    public static EndpointUrl parse(String raw) {
        if (raw == null || !PATTERN.matcher(raw).matches()) {
            throw UseCaseException.validation("INVALID_ENDPOINT", FORMAT_MESSAGE);
        }
        return new EndpointUrl(raw);
    }
}
