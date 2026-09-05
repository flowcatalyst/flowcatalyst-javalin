package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;

/// An anchor domain's format rule (spec §4.1): trimmed, lower-cased, must
/// contain a `.` and none of ` `, `/`, `@`. Both [AnchorDomain#create] and
/// [AnchorDomain#changeDomain] go through [#parse] so every entry point
/// rejects a malformed domain with the same message. Blank input is rejected
/// here as `INVALID_DOMAIN` — create's distinct `DOMAIN_REQUIRED` code (spec
/// §4.1) is a pre-check the create operation makes before calling this,
/// because only create needs the more specific code.
///
/// @param value the normalised domain
public record AnchorDomainValue(String value) {

    public static final String FORMAT_MESSAGE = "Anchor domain must be a valid DNS name (e.g. example.com)";

    public AnchorDomainValue {
        Objects.requireNonNull(value, "value");
    }

    /// Trims, lower-cases and validates a raw domain.
    ///
    /// @throws UseCaseException validation `INVALID_DOMAIN` when blank or malformed
    public static AnchorDomainValue parse(String raw) {
        String normalised = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty() || !normalised.contains(".")
                || normalised.contains(" ") || normalised.contains("/") || normalised.contains("@")) {
            throw UseCaseException.validation("INVALID_DOMAIN", FORMAT_MESSAGE);
        }
        return new AnchorDomainValue(normalised);
    }
}
