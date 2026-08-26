package io.flowcatalyst.sdk.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

/** Shared Jackson configuration for the SDK. */
public final class Json {

    private Json() {}

    public static ObjectMapper newMapper() {
        // JsonMapper.builder() rather than the deprecated mutator chain:
        // setSerializationInclusion mutates a mapper that callers may already
        // be using, which is why Jackson deprecated it.
        return tools.jackson.databind.json.JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Jackson 3 flipped this default to true (Jackson 2: false) — a
                // missing/null field on a primitive would now hard-fail instead
                // of defaulting to zero/false.
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // changeDefaultPropertyInclusion, not serializationInclusion: the
                // latter is deprecated on the builder too.
                .changeDefaultPropertyInclusion(v ->
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS))
                .build();
    }
}
