package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeTest {

    @ParameterizedTest
    @CsvSource({"JVM,JVM", "WASM,WASM"})
    void parseIsExact(String raw, Runtime expected) {
        assertThat(Runtime.parse(raw)).isEqualTo(expected);
    }

    @Test
    void parseRejectsLowerCase() {
        assertThatThrownBy(() -> Runtime.parse("jvm")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"jvm,JVM", "Wasm,WASM", "WASM,WASM"})
    void parseStrictIsCaseInsensitive(String raw, Runtime expected) {
        assertThat(Runtime.parseStrict(raw)).isEqualTo(expected);
    }

    @Test
    void parseStrictRejectsUnknown() {
        assertThatThrownBy(() -> Runtime.parseStrict("dotnet"))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("RUNTIME_INVALID");
                });
        assertThatThrownBy(() -> Runtime.parseStrict(null))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error().code())
                .isEqualTo("RUNTIME_INVALID");
    }

    @Test
    void wireValueIsLowerCase() {
        assertThat(Runtime.JVM.wireValue()).isEqualTo("jvm");
        assertThat(Runtime.WASM.wireValue()).isEqualTo("wasm");
    }
}
