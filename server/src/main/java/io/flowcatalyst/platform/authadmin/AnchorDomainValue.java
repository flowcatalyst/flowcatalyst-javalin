package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;

/// An anchor domain's format rule (spec §4.1): trimmed, lower-cased, must
/// contain a `.` and none of ` `, `/`, `@`. Both [AnchorDomain#create] and
/// [AnchorDomain#changeDomain] go through this format check so every entry
/// point rejects a malformed domain with the same code — but NOT the same
/// message: Go's `anchor_domain.go` gives create the longer, example-bearing
/// text and update the shorter one (parity S1 alignment, 2026-09-05); [#parse]
/// is update's wording (the default, and the one every entry point but
/// create used before this file existed), [#parseForCreate] is create's.
/// Blank input is rejected here as `INVALID_DOMAIN` — create's distinct
/// `DOMAIN_REQUIRED` code (spec §4.1) is a pre-check the create operation
/// makes before calling this, because only create needs the more specific
/// code.
///
/// @param value the normalised domain
public record AnchorDomainValue(String value) {

    public static final String FORMAT_MESSAGE = "domain must be a valid DNS name";
    public static final String FORMAT_MESSAGE_WITH_EXAMPLE = "domain must be a valid DNS name (e.g. example.com)";

    public AnchorDomainValue {
        Objects.requireNonNull(value, "value");
    }

    /// Trims, lower-cases and validates a raw domain — update's wording.
    ///
    /// @throws UseCaseException validation `INVALID_DOMAIN` when blank or malformed
    public static AnchorDomainValue parse(String raw) {
        return parse(raw, FORMAT_MESSAGE);
    }

    /// As [#parse], with create's example-bearing wording.
    ///
    /// @throws UseCaseException validation `INVALID_DOMAIN` when blank or malformed
    public static AnchorDomainValue parseForCreate(String raw) {
        return parse(raw, FORMAT_MESSAGE_WITH_EXAMPLE);
    }

    private static AnchorDomainValue parse(String raw, String message) {
        String normalised = (raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty() || !normalised.contains(".")
                || normalised.contains(" ") || normalised.contains("/") || normalised.contains("@")) {
            throw UseCaseException.validation("INVALID_DOMAIN", message);
        }
        return new AnchorDomainValue(normalised);
    }
}
