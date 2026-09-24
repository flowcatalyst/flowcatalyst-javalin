package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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

    /// The message [#invalid] throws, exposed so [Manifest]'s collecting
    /// parser can reuse it via [#tryParse] without catching this class's
    /// exception (`CONVENTIONS.md` §8).
    public static final String INVALID_MESSAGE = "hostname must be a lower-cased DNS name of at least two labels, "
            + "with no trailing dot, wildcard, port or IP literal";

    /// Malformed input: the code and message [#parse] raises as a
    /// `UseCaseException` (`CONVENTIONS.md` §8) — carried here so a caller
    /// outside an operation's validate/authorize phases can switch on
    /// [#check] instead of catching that exception for control flow.
    public record Invalid(String code, String message) {
    }

    /// Validates `raw` without throwing. The `Err` case carries exactly the
    /// code and message [#parse] raises as a `UseCaseException`.
    public static Result<Hostname, Invalid> check(String raw) {
        if (raw == null || raw.isEmpty()) return invalid();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.length() > MAX_LENGTH) return invalid();
        String[] labels = lower.split("\\.", -1);
        if (labels.length < 2) return invalid();
        for (String label : labels) {
            if (!DnsLabel.isValid(label)) return invalid();
        }
        if (isAllDigits(labels[labels.length - 1])) return invalid();
        return Result.ok(new Hostname(lower));
    }

    /// Non-throwing companion of [#parse]: empty on any malformed input,
    /// never throws.
    public static Optional<Hostname> tryParse(String raw) {
        return switch (check(raw)) {
            case Result.Ok<Hostname, Invalid> ok -> Optional.of(ok.value());
            case Result.Err<Hostname, Invalid> ignored -> Optional.empty();
        };
    }

    /// @throws UseCaseException validation `HOSTNAME_INVALID`
    public static Hostname parse(String raw) {
        return check(raw).orElseThrow(e -> UseCaseException.validation(e.code(), e.message()));
    }

    /// The candidate zone apexes this hostname could be covered by (spec
    /// `function-zones-and-aliases.md` §1): this hostname itself, then each
    /// successively shorter suffix, stopping at two labels — a claim needs at
    /// least two labels, so a one-label suffix is never a legal zone apex and
    /// is never offered as a candidate. `qa-myapp.acme.com` ⇒
    /// `["qa-myapp.acme.com", "acme.com"]`. Most specific first, so
    /// [FunctionDomainRepository#covering] can pick the first candidate it
    /// finds a claim for and know it is the longest (there is at most one
    /// match, by the no-nesting rule enforced at claim time).
    public List<String> zoneCandidates() {
        String[] labels = value.split("\\.", -1);
        List<String> out = new ArrayList<>(Math.max(0, labels.length - 1));
        for (int start = 0; start <= labels.length - 2; start++) {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < labels.length; i++) {
                if (i > start) sb.append('.');
                sb.append(labels[i]);
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static Result<Hostname, Invalid> invalid() {
        return Result.err(new Invalid("HOSTNAME_INVALID", INVALID_MESSAGE));
    }
}
