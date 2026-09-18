package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionStatusTest {

    @ParameterizedTest
    @CsvSource({"ACTIVE,ACTIVE", "DISABLED,DISABLED"})
    void parseIsExact(String raw, FunctionStatus expected) {
        assertThat(FunctionStatus.parse(raw)).isEqualTo(expected);
    }

    @Test
    void parseRejectsUnrecognised() {
        assertThatThrownBy(() -> FunctionStatus.parse("active")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FunctionStatus.parse("RETIRED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FunctionStatus.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
