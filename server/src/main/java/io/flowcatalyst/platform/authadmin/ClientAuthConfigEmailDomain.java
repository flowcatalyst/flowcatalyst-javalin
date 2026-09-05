package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;

/// A client auth config's e-mail domain format rule (spec §4.2): trimmed,
/// lower-cased, must contain a `.`. Unlike [AnchorDomainValue], a blank
/// value folds into the same `INVALID_EMAIL_DOMAIN` code rather than a
/// distinct "required" code, and there is no character-blocklist beyond the
/// dot requirement. [ClientAuthConfig#create] takes the parsed type.
///
/// @param value the normalised domain
public record ClientAuthConfigEmailDomain(String value) {

    public static final String FORMAT_MESSAGE = "emailDomain must be a valid DNS name";

    public ClientAuthConfigEmailDomain {
        Objects.requireNonNull(value, "value");
    }

    /// Trims, lower-cases and validates a raw domain.
    ///
    /// @throws UseCaseException validation `INVALID_EMAIL_DOMAIN` when blank or missing a dot
    public static ClientAuthConfigEmailDomain parse(String raw) {
        String normalised = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty() || !normalised.contains(".")) {
            throw UseCaseException.validation("INVALID_EMAIL_DOMAIN", FORMAT_MESSAGE);
        }
        return new ClientAuthConfigEmailDomain(normalised);
    }
}
