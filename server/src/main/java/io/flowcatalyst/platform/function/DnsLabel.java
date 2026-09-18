package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;
import java.util.regex.Pattern;

/// A DNS label (spec `function-registry.md` §3.1): 1-63 characters of
/// lower-case letters, digits and `-`, not starting or ending with `-`. No
/// normalisation — the input is not trimmed and not lower-cased; anything
/// that is not already a label is rejected outright. An address is an
/// identity used in router targets, permissions and metrics, and two
/// spellings of one identity is exactly how `Billing.Invoices.Create` and
/// `billing.invoices.create` become two grants.
///
/// @param value the label, unchanged from the input
public record DnsLabel(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    public DnsLabel {
        Objects.requireNonNull(value, "value");
    }

    /// Whether `raw` is already a valid label, without throwing — used by
    /// [FunctionAddress] and [FunctionAddressPattern] to check a segment
    /// before committing to build one.
    static boolean isValid(String raw) {
        return raw != null && PATTERN.matcher(raw).matches();
    }

    /// @throws UseCaseException validation `LABEL_INVALID` when `raw` is
    ///                          `null` or is not 1-63 characters of `a-z`,
    ///                          `0-9` and `-`, not starting or ending with `-`
    public static DnsLabel parse(String field, String raw) {
        if (!isValid(raw)) {
            throw UseCaseException.validation("LABEL_INVALID",
                    field + " must be a DNS label: 1-63 characters of a-z, 0-9 and '-', not starting or ending with '-'");
        }
        return new DnsLabel(raw);
    }
}
