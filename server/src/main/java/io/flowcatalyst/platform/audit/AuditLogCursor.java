package io.flowcatalyst.platform.audit;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;

/// A keyset position in the newest-first audit order — `(performedAt, id)` —
/// and its one wire encoding (spec §4): base64url without padding of
/// `<performedAt RFC 3339 UTC>|<id>`. The next page is every row strictly
/// before this position in `(performed_at DESC, id DESC)`.
///
/// The encoding is opaque to clients; [#parse] is the single reader and
/// reports every malformation as the one validation error `CURSOR`.
public record AuditLogCursor(Instant performedAt, String id) {

    public AuditLogCursor {
        Objects.requireNonNull(performedAt, "performedAt");
        Objects.requireNonNull(id, "id");
    }

    /// The opaque token for this position.
    public String encode() {
        String raw = performedAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /// Decodes a token produced by [#encode] (or by an earlier writer using
    /// the same layout with any number of fractional digits).
    ///
    /// @throws UseCaseException validation `CURSOR` `invalid cursor` for bad
    ///                          base64, a missing `|`, or an unparseable timestamp
    public static AuditLogCursor parse(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar < 0) throw invalid();
            return new AuditLogCursor(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1));
        } catch (IllegalArgumentException | DateTimeParseException _) {
            throw invalid();
        }
    }

    private static UseCaseException invalid() {
        return UseCaseException.validation("CURSOR", "invalid cursor");
    }
}
