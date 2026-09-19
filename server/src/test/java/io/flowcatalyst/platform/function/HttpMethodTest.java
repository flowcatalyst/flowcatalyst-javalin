package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMethodTest {

    @ParameterizedTest
    @CsvSource({"GET,GET", "HEAD,HEAD", "POST,POST", "PUT,PUT", "PATCH,PATCH", "DELETE,DELETE", "OPTIONS,OPTIONS"})
    void parseIsExact(String raw, HttpMethod expected) {
        assertThat(HttpMethod.parse(raw)).isEqualTo(expected);
    }

    @Test
    void parseRejectsLowerCase() {
        assertThatThrownBy(() -> HttpMethod.parse("get")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsUnrecognised() {
        assertThatThrownBy(() -> HttpMethod.parse("TRACE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpMethod.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"get,GET", "Post,POST", "DELETE,DELETE"})
    void parseStrictIsCaseInsensitive(String raw, HttpMethod expected) {
        assertThat(HttpMethod.parseStrict(raw)).isEqualTo(expected);
    }

    @Test
    void parseStrictRejectsUnknownMethod() {
        assertThatThrownBy(() -> HttpMethod.parseStrict("TRACE"))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("ENDPOINT_INVALID");
                });
    }
}
