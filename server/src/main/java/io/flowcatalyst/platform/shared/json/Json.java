package io.flowcatalyst.platform.shared.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

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
///   - Records, `Optional` and `java.time` need no modules: Jackson 3
///     folded both `Jdk8Module` and the jsr310 datatype into databind
///     (`tools.jackson.databind.ext.javatime`);
///     enums go on the wire as their `name()` unless they declare `@JsonValue`.
///
/// The Javalin adapter is `io.flowcatalyst.http.javalin.JavalinJsonMapper`.
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
                .addModule(micro) // overrides databind's built-in java.time handling for the three types above
                // Go `omitempty` on pointers/interfaces: nulls AND empty Optionals are dropped;
                // empty strings / collections are still written (NON_ABSENT, not NON_EMPTY).
                .changeDefaultPropertyInclusion(v ->
                        JsonInclude.Value.construct(JsonInclude.Include.NON_ABSENT, JsonInclude.Include.USE_DEFAULTS))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                // Jackson 3 flipped this default to true (Jackson 2: false) — a
                // missing/null field on a primitive would now hard-fail instead
                // of defaulting to zero/false, which every record here relies on.
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    /// Serialises with [#MAPPER]; a failure here is a programming error, not a
    /// request error, so it surfaces unchecked.
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
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
    public static <T> T read(String json, Class<T> type) throws JacksonException {
        return MAPPER.readValue(json, type);
    }

    /// The 400 a malformed request body maps to, shared by every listener
    /// adapter so the envelope is identical (`docs/spec/http-seam.md` §1).
    public static io.flowcatalyst.sdk.usecase.UseCaseException invalidJson(tools.jackson.core.JacksonException e) {
        var msg = e.getOriginalMessage();
        return io.flowcatalyst.sdk.usecase.UseCaseException.validation("INVALID_JSON",
                msg == null || msg.isBlank() ? "malformed request body" : msg);
    }
}
