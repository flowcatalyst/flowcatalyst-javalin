package io.flowcatalyst.platform.loginattempt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/// A keyset position in the newest-first attempt order — `(attemptedAt, id)`
/// — and its one wire encoding (spec §4): base64url without padding of
/// `<attemptedAt RFC 3339 UTC>|<id>`. The next page is every row strictly
/// before this position in `(attempted_at DESC, id DESC)`.
///
/// The encoding is opaque to clients; [#parse] is the single reader and
/// answers *no cursor* for every malformation — the list route then serves
/// the first page (spec §3, open question 4).
public record LoginAttemptCursor(Instant attemptedAt, String id) {

    public LoginAttemptCursor {
        Objects.requireNonNull(attemptedAt, "attemptedAt");
        Objects.requireNonNull(id, "id");
    }

    /// The opaque token for this position.
    public String encode() {
        String raw = attemptedAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /// Decodes a token produced by [#encode] (or by an earlier writer using
    /// the same layout with any number of fractional digits); empty for bad
    /// base64, a missing `|`, or an unparseable timestamp.
    public static Optional<LoginAttemptCursor> parse(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar < 0) return Optional.empty();
            return Optional.of(new LoginAttemptCursor(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException _) {
            return Optional.empty();
        }
    }
}
