package io.flowcatalyst.platform.client;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// A client's URL-safe slug, normalised (spec §1, §4). This is the one place
/// the format rule lives: the create command's validate phase and
/// [Client#create] both go through [#parse], so every entry point rejects a
/// malformed identifier with the same error and stores the same
/// trimmed, lower-cased form (uniqueness is on that form).
///
/// **`platform` is reserved (ruling R5, `docs/go-mirror/2026-09-12-dispatch-rulings.md`).**
/// Client-less dispatch jobs publish to `FC-{env}-platform-DEFAULT.fifo`; a
/// client whose identifier is literally `platform` would collide with that
/// lane. The identifier is immutable after create (ruling O1), so refusing
/// it here, once, closes the collision permanently.
///
/// @param value the normalised identifier
public record ClientIdentifier(String value) {

    public static final String FORMAT_MESSAGE =
            "identifier must be lowercase alphanumeric with optional hyphens (URL-safe)";

    /// Lower-case alphanumerics with interior hyphens, or a single character.
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*[a-z0-9]$|^[a-z0-9]$");

    /// The tenant segment reserved for client-less dispatch jobs (ruling R5).
    private static final String RESERVED_PLATFORM = "platform";

    public ClientIdentifier {
        Objects.requireNonNull(value, "value");
    }

    /// Trims, lower-cases and validates a raw identifier.
    ///
    /// @throws UseCaseException validation `IDENTIFIER_REQUIRED` when blank,
    ///                          `INVALID_IDENTIFIER` when the normalised form is not a slug,
    ///                          `RESERVED_IDENTIFIER` when the normalised form is `platform` (ruling R5)
    public static ClientIdentifier parse(String raw) {
        String normalised = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        UseCaseException.requireNonBlank(normalised, "IDENTIFIER_REQUIRED", "identifier is required");
        if (!SLUG.matcher(normalised).matches()) {
            throw UseCaseException.validation("INVALID_IDENTIFIER", FORMAT_MESSAGE);
        }
        if (normalised.equals(RESERVED_PLATFORM)) {
            throw UseCaseException.validation("RESERVED_IDENTIFIER",
                    "identifier '" + RESERVED_PLATFORM + "' is reserved for platform-wide dispatch");
        }
        return new ClientIdentifier(normalised);
    }
}
