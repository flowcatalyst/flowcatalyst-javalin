package io.flowcatalyst.platform.shared.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/// The platform's ONE configured [ObjectMapper] — every wire body, every
/// stored JSON column and every log payload goes through [#MAPPER] so the
/// shape is the same everywhere. Mirrors the Go conventions:
///
///   - `null` fields are omitted (`NON_NULL` ≈ Go `omitempty` for nil);
///     empty strings/lists are still written, exactly as Go writes them unless
///     a field opts in with `@JsonInclude(NON_EMPTY)`.
///   - Unknown request fields are ignored (Go `httpcompat.RelaxRequestBodies`).
///   - [Instant] / [OffsetDateTime] / [ZonedDateTime] are written as RFC 3339
///     with exactly six fractional digits and `Z` for UTC (`jsontime.Layout`),
///     and read from any RFC 3339 precision.
///   - Records and `Optional` are supported (jsr310 + jdk8 modules); enums go
///     on the wire as their `name()` unless they declare `@JsonValue`.
///
/// The Javalin adapter is [JavalinJsonMapper].
public final class Json {

    public static final ObjectMapper MAPPER = build();

    private Json() {
    }

    private static ObjectMapper build() {
        var micro = new SimpleModule("flowcatalyst-jsontime")
                .addSerializer(Instant.class, new MicroInstantSerializer<>(Instant.class))
                .addSerializer(OffsetDateTime.class, new MicroInstantSerializer<>(OffsetDateTime.class))
                .addSerializer(ZonedDateTime.class, new MicroInstantSerializer<>(ZonedDateTime.class))
                .addDeserializer(Instant.class, MicroInstantDeserializer.forInstant())
                .addDeserializer(OffsetDateTime.class, MicroInstantDeserializer.forOffsetDateTime())
                .addDeserializer(ZonedDateTime.class, MicroInstantDeserializer.forZonedDateTime());
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(new Jdk8Module())
                .addModule(micro) // registered last so it wins over JavaTimeModule for the three types above
                // Go `omitempty` on pointers/interfaces: nulls AND empty Optionals are dropped;
                // empty strings / collections are still written (NON_ABSENT, not NON_EMPTY).
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_ABSENT, JsonInclude.Include.USE_DEFAULTS))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .build();
    }

    /// Serialises with [#MAPPER]; a failure here is a programming error, not a
    /// request error, so it surfaces unchecked.
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialisation failed for " + value.getClass().getName(), e);
        }
    }

    /// Serialises as Go's `json.Encoder.Encode` does — the document followed
    /// by a single `\n`. Both `httperror.Write` and huma's JSON format stream
    /// through an encoder, so every Go response body ends with a newline; the
    /// HTTP layer uses this so the bytes line up.
    public static String writeLine(Object value) {
        return write(value) + "\n";
    }

    /// Deserialises with [#MAPPER]; the caller decides how a malformed input is
    /// reported (the HTTP layer maps it to the `INVALID_JSON` envelope).
    public static <T> T read(String json, Class<T> type) throws JsonProcessingException {
        return MAPPER.readValue(json, type);
    }
}
