package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthModeTest {

    @ParameterizedTest
    @CsvSource({"BEARER,BEARER", "NONE,NONE"})
    void parseIsExact(String raw, AuthMode expected) {
        assertThat(AuthMode.parse(raw)).isEqualTo(expected);
    }

    @Test
    void parseRejectsLowerCase() {
        assertThatThrownBy(() -> AuthMode.parse("bearer")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"bearer,BEARER", "None,NONE"})
    void parseStrictIsCaseInsensitive(String raw, AuthMode expected) {
        assertThat(AuthMode.parseStrict(raw)).isEqualTo(expected);
    }

    @Test
    void parseStrictRejectsUnknownOrAbsent() {
        assertThatThrownBy(() -> AuthMode.parseStrict("basic"))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("ROUTE_INVALID");
                });
        assertThatThrownBy(() -> AuthMode.parseStrict(null))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error().code())
                .isEqualTo("ROUTE_INVALID");
    }
}
