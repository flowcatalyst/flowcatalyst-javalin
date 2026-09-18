package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-registry.md` §4.6's default table, and the `<= 0` rejection.
class FunctionLimitsTest {

    @Test
    void defaultsMatchTheSpecTable() {
        FunctionLimits defaults = FunctionLimits.defaults();
        assertThat(defaults.maxDurationMs()).isEqualTo(30_000);
        assertThat(defaults.maxConcurrency()).isEqualTo(32);
        assertThat(defaults.wasmMemoryMb()).isEqualTo(64);
        assertThat(defaults.dbPoolSize()).isEqualTo(4);
        assertThat(defaults.maxWarmPerHost()).isEqualTo(200);
    }

    @ParameterizedTest(name = "[{index}] component {0}")
    @CsvSource({
            "maxDurationMs",
            "maxConcurrency",
            "wasmMemoryMb",
            "dbPoolSize",
            "maxWarmPerHost",
    })
    void everyComponentRejectsNonPositive(String component) {
        assertThatThrownBy(() -> build(component, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(component);
        assertThatThrownBy(() -> build(component, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(component);
    }

    private static FunctionLimits build(String zeroedComponent, int value) {
        int maxDurationMs = "maxDurationMs".equals(zeroedComponent) ? value : 30_000;
        int maxConcurrency = "maxConcurrency".equals(zeroedComponent) ? value : 32;
        int wasmMemoryMb = "wasmMemoryMb".equals(zeroedComponent) ? value : 64;
        int dbPoolSize = "dbPoolSize".equals(zeroedComponent) ? value : 4;
        int maxWarmPerHost = "maxWarmPerHost".equals(zeroedComponent) ? value : 200;
        return new FunctionLimits(maxDurationMs, maxConcurrency, wasmMemoryMb, dbPoolSize, maxWarmPerHost);
    }
}
