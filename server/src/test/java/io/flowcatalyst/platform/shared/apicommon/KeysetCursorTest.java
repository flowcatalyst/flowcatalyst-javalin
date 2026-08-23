package io.flowcatalyst.platform.shared.apicommon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The one keyset-cursor encoding shared by the cursor lists: the token
/// layout, the lenient timestamp read and the pinned malformed table.
class KeysetCursorTest {

    private static final Instant T = Instant.parse("2026-08-22T10:11:12.123456Z");

    @Test
    void cursorRoundTripsThroughItsOpaqueToken() {
        var cursor = new KeysetCursor(T, "aud_0ABC123XYZ456");
        String token = cursor.encode();
        assertThat(token).doesNotContain("=", "+", "/");
        assertThat(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8))
                .isEqualTo("2026-08-22T10:11:12.123456Z|aud_0ABC123XYZ456");
        assertThat(KeysetCursor.parse(token)).contains(cursor);
    }

    @ParameterizedTest(name = "{0} reads as ({1}, {2})")
    @CsvSource(delimiter = ';', value = {
            "2026-08-22T10:11:12.123456789Z|lat_1; 2026-08-22T10:11:12.123456789Z; lat_1",      // nanos
            "2026-08-22T10:11:12Z|lat_1;           2026-08-22T10:11:12Z;           lat_1",      // no fraction
            "2026-08-22T10:11:12.5Z|lat_1|with|bars; 2026-08-22T10:11:12.5Z;      lat_1|with|bars", // only the first bar splits
    })
    void acceptsAnyFractionalPrecisionAnEarlierWriterMayHaveUsed(String raw, String at, String id) {
        assertThat(KeysetCursor.parse(token(raw))).contains(new KeysetCursor(Instant.parse(at), id));
    }

    @ParameterizedTest(name = "token {0}")
    @ValueSource(strings = {
            "",                                   // nothing
            "not base64 !!",                      // bad alphabet
            "bm8tYmFy",                           // "no-bar"
            "bm90LWEtdGltZXxsYXRfMQ",             // "not-a-time|lat_1"
            "MjAyNi0wOC0yMiAxMDoxMToxMlp8bGF0XzE", // "2026-08-22 10:11:12Z|lat_1" (space, not T)
            "MjAyNi0wOC0yMlQxMDoxMToxMlp8",        // "2026-08-22T10:11:12Z|" (empty id)
    })
    void malformedTokenIsReportedAsEmpty(String token) {
        assertThat(KeysetCursor.parse(token)).isEmpty();
    }

    @Test
    void aPositionNeedsATimestampAndANonEmptyId() {
        assertThatThrownBy(() -> new KeysetCursor(null, "x")).isInstanceOf(NullPointerException.class).hasMessage("at");
        assertThatThrownBy(() -> new KeysetCursor(T, null)).isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> new KeysetCursor(T, "")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String token(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
