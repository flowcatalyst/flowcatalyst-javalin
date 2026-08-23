package io.flowcatalyst.platform.shared.apicommon;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/// A keyset position `(at, id)` in a newest-first `(<timestamp> DESC, id DESC)`
/// order and its one wire encoding: base64url without padding of
/// `<at RFC 3339 UTC>|<id>`. The next page is every row strictly before this
/// position. One record for every cursor list (audit log, login attempts…):
/// the token layout is a platform-wide contract, the *meaning* of a malformed
/// token (400 `CURSOR` or "first page") is each route's policy — so [#parse]
/// only reports a malformation, as empty, and the call site decides.
///
/// The encoder writes `ISO_INSTANT` (0/3/6/9 fractional digits); the stored
/// timestamps have microsecond precision, so a round trip is exact. The
/// decoder accepts any fractional precision an earlier writer may have used.
public record KeysetCursor(Instant at, String id) {

    public KeysetCursor {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(id, "id");
        if (id.isEmpty()) throw new IllegalArgumentException("empty id"); // no row has one — never a position
    }

    /// The opaque token for this position.
    public String encode() {
        String raw = at + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /// Decodes a token produced by [#encode] (or by an earlier writer using the
    /// same layout with any number of fractional digits): empty for bad base64,
    /// a missing `|`, an unparseable timestamp or an empty id after the `|`.
    /// Only the first `|` splits — an id may not contain one, but the parser
    /// does not rely on that.
    public static Optional<KeysetCursor> parse(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar < 0) return Optional.empty();
            return Optional.of(new KeysetCursor(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException _) {
            return Optional.empty();
        }
    }
}
