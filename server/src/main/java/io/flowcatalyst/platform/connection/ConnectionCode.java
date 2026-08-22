package io.flowcatalyst.platform.connection;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// A connection code, normalised (trimmed, lower-cased) and validated
/// against `^[a-z][a-z0-9-]*$` (spec §4). This is the one place the format
/// rule lives: the create command's validate phase and [Connection#create]
/// both go through [#parse], so every entry point rejects a bad code with
/// the same error and message.
///
/// @param value the normalised code as it is stored
public record ConnectionCode(String value) {

    public static final String FORMAT_MESSAGE =
            "Code must start with lowercase letter, contain only lowercase alphanumeric and hyphens";

    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    public ConnectionCode {
        Objects.requireNonNull(value, "value");
    }

    /// Normalises and validates a raw code.
    ///
    /// @throws UseCaseException validation `CODE_REQUIRED` when blank,
    ///                          `INVALID_CODE_FORMAT` when the normalised code does not match the pattern
    public static ConnectionCode parse(String raw) {
        String code = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        UseCaseException.requireNonBlank(code, "CODE_REQUIRED", "Connection code is required");
        if (!PATTERN.matcher(code).matches()) {
            throw UseCaseException.validation("INVALID_CODE_FORMAT", FORMAT_MESSAGE);
        }
        return new ConnectionCode(code);
    }
}
