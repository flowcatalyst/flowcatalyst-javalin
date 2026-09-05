package io.flowcatalyst.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.flowcatalyst.sdk.generated.ApiClient;
import io.flowcatalyst.sdk.generated.model.AuditLogResponse;
import io.flowcatalyst.sdk.generated.model.CreatedResponse;
import io.flowcatalyst.sdk.http.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/// The generated models carry only `com.fasterxml.jackson.annotation.*`,
/// which Jackson 3 databind honours; the SDK ships no Jackson 2 (owner
/// ruling 2026-09-06). Pinned here through the SDK's own mapper: a plain
/// model, a model with an `OffsetDateTime` (RFC 3339 without the jsr310
/// module, which folded into Jackson 3), and the two static helpers the
/// models' `toUrlQueryString` needs from the hand-written `ApiClient`.
class GeneratedModelsOnJackson3Test {

    private final ObjectMapper mapper = Json.newMapper();

    @Test
    void aGeneratedModelRoundTripsThroughTheJackson3Mapper() {
        CreatedResponse in = mapper.readValue("{\"id\":\"evt_123\",\"$schema\":\"http://x/CreatedResponse.json\"}", CreatedResponse.class);
        assertEquals("evt_123", in.getId());
        String out = mapper.writeValueAsString(in);
        assertEquals(in, mapper.readValue(out, CreatedResponse.class));
    }

    @Test
    void anOffsetDateTimeMemberIsRfc3339WithoutTheJsr310Module() {
        String json = "{\"id\":\"aud_1\",\"performedAt\":\"2026-09-06T08:15:30.123456Z\"}";
        AuditLogResponse row = mapper.readValue(json, AuditLogResponse.class);
        assertEquals(OffsetDateTime.of(2026, 9, 6, 8, 15, 30, 123_456_000, ZoneOffset.UTC), row.getPerformedAt());
        assertTrue(mapper.writeValueAsString(row).contains("\"performedAt\":\"2026-09-06T08:15:30.123456Z\""), mapper.writeValueAsString(row));
    }

    @Test
    void theModelsQueryStringHelpersKeepTheGeneratorsSemantics() {
        assertEquals("a%20b%26c", ApiClient.urlEncode("a b&c"));
        assertEquals("", ApiClient.valueToString(null));
        assertEquals("2026-09-06T08:00:00Z", ApiClient.valueToString(OffsetDateTime.of(2026, 9, 6, 8, 0, 0, 0, ZoneOffset.UTC)));
        assertEquals("42", ApiClient.valueToString(42));
    }
}
