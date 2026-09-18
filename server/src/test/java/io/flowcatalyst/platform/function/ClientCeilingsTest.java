package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-registry.md` §4.6: a client with no policy row gets the
/// platform defaults as its ceilings.
class ClientCeilingsTest {

    @Test
    void ofUsesThePlatformDefaultsAsCeilings() {
        FunctionLimits defaults = FunctionLimits.defaults();
        ClientCeilings ceilings = ClientCeilings.of(defaults);
        assertThat(ceilings.maxDurationMs()).isEqualTo(defaults.maxDurationMs());
        assertThat(ceilings.maxConcurrency()).isEqualTo(defaults.maxConcurrency());
        assertThat(ceilings.wasmMemoryMb()).isEqualTo(defaults.wasmMemoryMb());
        assertThat(ceilings.dbPoolSize()).isEqualTo(defaults.dbPoolSize());
    }

    @Test
    void everyComponentRejectsNonPositive() {
        assertThatThrownBy(() -> new ClientCeilings(0, 32, 64, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientCeilings(30_000, -1, 64, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientCeilings(30_000, 32, 0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientCeilings(30_000, 32, 64, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
