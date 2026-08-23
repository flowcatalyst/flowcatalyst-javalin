package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;

/// A mapping's email domain, normalised (spec §1, §4). This is the one
/// place the format rule lives: the create command's validate phase and
/// [EmailDomainMapping#create] both go through [#parse], so every entry
/// point rejects a malformed domain with the same error and stores the same
/// trimmed, lower-cased form (uniqueness is on that form).
///
/// @param value the normalised domain
public record EmailDomain(String value) {

    public static final String FORMAT_MESSAGE = "Email domain must be a valid DNS name (e.g. example.com)";

    public EmailDomain {
        Objects.requireNonNull(value, "value");
    }

    /// Trims, lower-cases and validates a raw domain: it must contain a `.`
    /// and none of ` `, `/`, `@`.
    ///
    /// @throws UseCaseException validation `EMAIL_DOMAIN_REQUIRED` when blank,
    ///                          `INVALID_EMAIL_DOMAIN` when not DNS-like
    public static EmailDomain parse(String raw) {
        String normalised = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        UseCaseException.requireNonBlank(normalised, "EMAIL_DOMAIN_REQUIRED", "Email domain is required");
        if (!normalised.contains(".") || normalised.contains(" ") || normalised.contains("/") || normalised.contains("@")) {
            throw UseCaseException.validation("INVALID_EMAIL_DOMAIN", FORMAT_MESSAGE);
        }
        return new EmailDomain(normalised);
    }
}
