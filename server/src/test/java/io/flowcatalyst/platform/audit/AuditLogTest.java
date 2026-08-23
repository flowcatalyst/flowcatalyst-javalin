package io.flowcatalyst.platform.audit;

import io.flowcatalyst.platform.shared.apicommon.KeysetCursor;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The pure rules of the aggregate (spec §1, §4): the record's invariants and
/// its keyset position, without a database. The cursor's encoding is the
/// shared `apicommon.KeysetCursor`'s — see `KeysetCursorTest`.
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
        assertThat(entry(null).cursor()).isEqualTo(new KeysetCursor(T, "aud_1"));
    }
}
