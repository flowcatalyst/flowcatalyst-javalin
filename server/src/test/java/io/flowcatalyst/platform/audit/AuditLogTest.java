package io.flowcatalyst.platform.audit;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The pure rules of the aggregate (spec §1, §4): the record's invariants and
/// the cursor's one encoding, without a database.
class AuditLogTest {

    private static final Instant T = Instant.parse("2026-08-22T10:11:12.123456Z");

    private static AuditLog entry(String operationJson) throws Exception {
        return new AuditLog("aud_1", "Eventtype", "evt_1", "CreateCommand",
                operationJson == null ? null : Json.MAPPER.readTree(operationJson),
                "prn_1", "Ada", null, null, T);
    }

    // ── Record ─────────────────────────────────────────────────────────────

    @Test
    void requiredComponentsAreNonNull() {
        assertThatThrownBy(() -> new AuditLog(null, "E", "e", "Op", null, null, null, null, null, T))
                .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> new AuditLog("aud_1", "E", "e", "Op", null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("performedAt");
    }

    @Test
    void aJsonNullOperationDocumentIsAbsent() throws Exception {
        assertThat(entry("null").operationJson()).isNull();
        assertThat(entry(null).operationJson()).isNull();
        assertThat(entry("{\"k\":\"v\"}").operationJson().get("k").asText()).isEqualTo("v");
    }

    @Test
    void cursorIsTheEntryPosition() throws Exception {
        assertThat(entry(null).cursor()).isEqualTo(new AuditLogCursor(T, "aud_1"));
    }

    // ── Cursor encoding ────────────────────────────────────────────────────

    @Test
    void cursorRoundTripsThroughItsOpaqueToken() {
        var cursor = new AuditLogCursor(T, "aud_0ABC123XYZ456");
        String token = cursor.encode();
        assertThat(token).doesNotContain("=", "+", "/");
        assertThat(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8))
                .isEqualTo("2026-08-22T10:11:12.123456Z|aud_0ABC123XYZ456");
        assertThat(AuditLogCursor.parse(token)).isEqualTo(cursor);
    }

    @Test
    void cursorAcceptsAnyFractionalPrecisionAnEarlierWriterMayHaveUsed() {
        assertThat(AuditLogCursor.parse(token("2026-08-22T10:11:12.123456789Z|aud_1")))
                .isEqualTo(new AuditLogCursor(Instant.parse("2026-08-22T10:11:12.123456789Z"), "aud_1"));
        assertThat(AuditLogCursor.parse(token("2026-08-22T10:11:12Z|aud_1")))
                .isEqualTo(new AuditLogCursor(Instant.parse("2026-08-22T10:11:12Z"), "aud_1"));
        assertThat(AuditLogCursor.parse(token("2026-08-22T10:11:12.5Z|aud_1|with|bars")))
                .as("only the first bar splits").isEqualTo(new AuditLogCursor(Instant.parse("2026-08-22T10:11:12.5Z"), "aud_1|with|bars"));
    }

    @ParameterizedTest(name = "token {0}")
    @ValueSource(strings = {
            "",                                   // nothing
            "not base64 !!",                      // bad alphabet
            "bm8tYmFy",                           // "no-bar"
            "bm90LWEtdGltZXxhdWRfMQ",             // "not-a-time|aud_1"
            "MjAyNi0wOC0yMiAxMDoxMToxMlp8YXVkXzE", // "2026-08-22 10:11:12Z|aud_1" (space, not T)
            "MjAyNi0wOC0yMlQxMDoxMToxMlp8",        // "2026-08-22T10:11:12Z|" (empty id)
    })
    void malformedCursorIsOneValidationError(String token) {
        assertThatThrownBy(() -> AuditLogCursor.parse(token))
                .isInstanceOf(UseCaseException.class)
                .extracting(e -> ((UseCaseException) e).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("CURSOR");
                    assertThat(err.message()).isEqualTo("invalid cursor");
                });
    }

    private static String token(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
