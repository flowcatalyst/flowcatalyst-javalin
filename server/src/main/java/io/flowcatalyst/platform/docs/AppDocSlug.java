package io.flowcatalyst.platform.docs;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;
import java.util.regex.Pattern;

/// A page's URL-safe id within its application (spec §5): kebab-case —
/// lowercase letters, digits and hyphens, starting with a letter or digit.
/// The one parser for the format; the sync's validate phase and the entity
/// factory both go through it. Trimmed before matching; blank is rejected
/// like any other malformation.
public record AppDocSlug(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    public AppDocSlug {
        Objects.requireNonNull(value, "value");
    }

    /// @throws UseCaseException validation `SLUG_INVALID`
    public static AppDocSlug parse(String raw) {
        String slug = raw == null ? "" : raw.trim();
        if (!PATTERN.matcher(slug).matches()) {
            throw UseCaseException.validation("SLUG_INVALID",
                    "doc slug " + slug + " must be kebab-case (lowercase letters, digits, hyphens)");
        }
        return new AppDocSlug(slug);
    }
}
