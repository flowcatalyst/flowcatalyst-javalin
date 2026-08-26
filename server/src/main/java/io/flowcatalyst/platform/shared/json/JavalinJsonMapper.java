package io.flowcatalyst.platform.shared.json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.javalin.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

/// Javalin [JsonMapper] over [Json#MAPPER]. Install with
/// `Javalin.create(cfg -> cfg.jsonMapper(new JavalinJsonMapper()))`.
///
/// Two deliberate behaviours:
///
///   - `ctx.json(...)` bodies end with `\n`, like every Go response (see
///     [Json#writeLine]).
///   - A malformed request body (`ctx.bodyAsClass(...)`) throws
///     `UseCaseException.validation("INVALID_JSON", <parser message>)`, which the
///     `HttpError` installer renders as the 400 `INVALID_JSON` envelope — the
///     same code Go handlers emit via `httperror.BadRequest("INVALID_JSON",
///     err.Error())`. The message text is Jackson's, not Go's `encoding/json`'s.
public final class JavalinJsonMapper implements JsonMapper {

    private final ObjectMapper mapper;

    public JavalinJsonMapper() {
        this(Json.MAPPER);
    }

    public JavalinJsonMapper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String toJsonString(Object obj, Type type) {
        try {
            return mapper.writerFor(mapper.constructType(type)).writeValueAsString(obj) + "\n";
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON serialisation failed for " + type.getTypeName(), e);
        }
    }

    @Override
    public InputStream toJsonStream(Object obj, Type type) {
        return new ByteArrayInputStream(toJsonString(obj, type).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeToOutputStream(Stream<?> stream, OutputStream outputStream) {
        try (stream) {
            var gen = mapper.createGenerator(outputStream);
            gen.writeStartArray();
            var it = stream.iterator();
            while (it.hasNext()) {
                mapper.writeValue(gen, it.next());
            }
            gen.writeEndArray();
            gen.writeRaw('\n');
            gen.flush();
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON stream serialisation failed", e);
        }
    }

    @Override
    public <T> T fromJsonString(String json, Type type) {
        try {
            return mapper.readValue(json, mapper.constructType(type));
        } catch (JacksonException e) {
            throw invalidJson(e);
        }
    }

    @Override
    public <T> T fromJsonStream(InputStream json, Type type) {
        try {
            return mapper.readValue(json, mapper.constructType(type));
        } catch (JacksonException e) {
            throw invalidJson(e);
        }
    }

    private static UseCaseException invalidJson(JacksonException e) {
        var msg = e.getOriginalMessage();
        return UseCaseException.validation("INVALID_JSON", msg == null || msg.isBlank() ? "malformed request body" : msg);
    }
}
