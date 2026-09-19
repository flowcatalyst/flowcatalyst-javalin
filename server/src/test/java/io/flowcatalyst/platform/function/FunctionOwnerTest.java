package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The owner type's own rules (spec `function-registry.md` §6.1, ruling R2):
/// `null` ⇔ [FunctionOwner.Platform], a blank client id is rejected rather
/// than coerced.
class FunctionOwnerTest {

    @Test
    void ofClientIdNullIsPlatform() {
        assertThat(FunctionOwner.ofClientId(null)).isEqualTo(new FunctionOwner.Platform());
    }

    @Test
    void ofClientIdNonBlankIsClient() {
        assertThat(FunctionOwner.ofClientId("clt_1")).isEqualTo(new FunctionOwner.Client("clt_1"));
    }

    @Test
    void ofClientIdBlankThrowsRatherThanCoercingToPlatform() {
        assertThatThrownBy(() -> FunctionOwner.ofClientId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FunctionOwner.ofClientId("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clientConstructorRejectsBlank() {
        assertThatThrownBy(() -> new FunctionOwner.Client("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FunctionOwner.Client(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void clientIdOrNullIsTheInverseOfOfClientId() {
        assertThat(new FunctionOwner.Platform().clientIdOrNull()).isNull();
        assertThat(new FunctionOwner.Client("clt_1").clientIdOrNull()).isEqualTo("clt_1");
        assertThat(FunctionOwner.ofClientId(new FunctionOwner.Client("clt_2").clientIdOrNull()))
                .isEqualTo(new FunctionOwner.Client("clt_2"));
    }

    @Test
    void twoPlatformInstancesAreEqual() {
        assertThat(new FunctionOwner.Platform()).isEqualTo(new FunctionOwner.Platform());
    }
}
