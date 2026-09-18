package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;

/// A route's public hostname (spec `function-registry.md` §5.1): lower-cased
/// (DNS is case-insensitive, and `fn_domains`/`fn_routes` check `hostname =
/// lower(hostname)`), at most 253 characters, at least two [DnsLabel]s, no
/// trailing dot, no wildcard, no port, and not an IP literal (an all-numeric
/// last label).
///
/// @param value the lower-cased hostname
public record Hostname(String value) {

    private static final int MAX_LENGTH = 253;

    public Hostname {
        Objects.requireNonNull(value, "value");
    }

    /// @throws UseCaseException validation `HOSTNAME_INVALID`
    public static Hostname parse(String raw) {
        if (raw == null || raw.isEmpty()) throw invalid();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.length() > MAX_LENGTH) throw invalid();
        String[] labels = lower.split("\\.", -1);
        if (labels.length < 2) throw invalid();
        for (String label : labels) {
            if (!DnsLabel.isValid(label)) throw invalid();
        }
        if (isAllDigits(labels[labels.length - 1])) throw invalid();
        return new Hostname(lower);
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static UseCaseException invalid() {
        return UseCaseException.validation("HOSTNAME_INVALID",
                "hostname must be a lower-cased DNS name of at least two labels, with no trailing dot, wildcard, port or IP literal");
    }
}
