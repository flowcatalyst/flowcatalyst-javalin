package io.flowcatalyst.platform.principal;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// A user's email, normalised (lower-cased, trimmed) and split (spec §4).
/// This is the one place the create-time format rule lives: `CreateUser`
/// and `CreatePortalUser` both go through [#parse], so every entry point
/// rejects a malformed address with the same `INVALID_EMAIL` error.
///
/// The admin handlers (`/users`, `/check-email-domain`) apply a looser
/// "has a domain" check with their own pinned message — [#domainOf].
///
/// @param value     normalised address
/// @param localPart text before the `@`
/// @param domain    text after the `@`
public record EmailAddress(String value, String localPart, String domain) {

    private static final Pattern PATTERN = Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    public EmailAddress {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(localPart, "localPart");
        Objects.requireNonNull(domain, "domain");
    }

    /// Normalises and validates a raw address.
    ///
    /// @throws UseCaseException validation `EMAIL_REQUIRED` when blank, `INVALID_EMAIL` when malformed
    public static EmailAddress parse(String raw) {
        String email = normalise(raw);
        if (email.isEmpty()) {
            throw UseCaseException.validation("EMAIL_REQUIRED", "email is required");
        }
        if (!PATTERN.matcher(email).matches()) {
            throw UseCaseException.validation("INVALID_EMAIL", "email must be a valid address");
        }
        int at = email.indexOf('@');
        return new EmailAddress(email, email.substring(0, at), email.substring(at + 1));
    }

    /// Lower-cased + trimmed; `null` → `""`.
    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /// The domain of a normalised address, or `null` when there is no `@`
    /// or nothing after it — the handlers' loose check and the
    /// `email_domain` column derivation share this rule.
    public static String domainOf(String normalised) {
        if (normalised == null) return null;
        int at = normalised.indexOf('@');
        if (at < 0 || at == normalised.length() - 1) return null;
        return normalised.substring(at + 1);
    }
}
