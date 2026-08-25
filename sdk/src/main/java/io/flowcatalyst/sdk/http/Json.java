package io.flowcatalyst.sdk.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared Jackson configuration for the SDK. */
public final class Json {

    private Json() {}

    public static ObjectMapper newMapper() {
        // JsonMapper.builder() rather than the deprecated mutator chain:
        // setSerializationInclusion mutates a mapper that callers may already
        // be using, which is why Jackson deprecated it.
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // defaultPropertyInclusion, not serializationInclusion: the
                // latter is deprecated on the builder too.
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS))
                .build();
    }
}
