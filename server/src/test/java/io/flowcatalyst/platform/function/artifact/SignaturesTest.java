package io.flowcatalyst.platform.function.artifact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `Signatures.resolve` (spec `function-api.md` §5.1 step 5, §8 P9):
/// `FC_FN_SIGNATURES=off` is refused outright without `FLOWCATALYST_DEV_MODE=true`.
class SignaturesTest {

    /// A trust-root supplier that would fail the test if it were ever
    /// called — [Signatures.Off] must never build one.
    private static final java.util.function.Supplier<TrustRoot> POISON = () -> {
        throw new AssertionError("Signatures.Off must never build a TrustRoot");
    };

    @Test
    void offWithoutDevModeFailsStartupNamingBothVariables() {
        assertThatThrownBy(() -> Signatures.resolve(SignaturesMode.OFF, false, POISON))
                .as("mutant: accept FC_FN_SIGNATURES=off outside dev mode")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_SIGNATURES")
                .hasMessageContaining("FLOWCATALYST_DEV_MODE");
    }

    @Test
    void offWithDevModeIsAccepted() {
        Signatures signatures = Signatures.resolve(SignaturesMode.OFF, true, POISON);
        assertThat(signatures).isInstanceOf(Signatures.Off.class);
    }

    @Test
    void requiredBuildsAVerifierFromTheGivenTrustRootRegardlessOfDevMode() {
        TrustRoot trustRoot = new TrustRoot(java.util.List.of(), java.util.List.of());
        Signatures off = Signatures.resolve(SignaturesMode.REQUIRED, false, () -> trustRoot);
        assertThat(off).isInstanceOf(Signatures.Required.class);
        assertThat(((Signatures.Required) off).verifier()).isNotNull();
    }

    @Test
    void signaturesModeParseOnlyRecognisesOffExactly() {
        assertThat(SignaturesMode.parse(null)).isEqualTo(SignaturesMode.REQUIRED);
        assertThat(SignaturesMode.parse("")).isEqualTo(SignaturesMode.REQUIRED);
        assertThat(SignaturesMode.parse("required")).isEqualTo(SignaturesMode.REQUIRED);
        assertThat(SignaturesMode.parse("ofF")).as("case-insensitive").isEqualTo(SignaturesMode.OFF);
        assertThat(SignaturesMode.parse(" off ")).as("trimmed").isEqualTo(SignaturesMode.OFF);
        assertThat(SignaturesMode.parse("offf")).as("a typo must never silently become OFF")
                .isEqualTo(SignaturesMode.REQUIRED);
    }
}
