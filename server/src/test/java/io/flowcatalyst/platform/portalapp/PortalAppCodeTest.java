package io.flowcatalyst.platform.portalapp;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The `code` format rule (spec `portal-apps.md` §2.1, §9.2) as a pinned
/// table: normalisation always runs first (trim + lower-case), then the
/// pattern is checked against the normalised value — `"UPPER"` is accepted
/// and stored as `"upper"` (spec §9.2), never rejected for its original case.
class PortalAppCodeTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "'  Customer-Portal ', customer-portal",
            "a, a",
            "supplier_portal2, supplier_portal2",
            "9lives, 9lives",
            "UPPER, upper",
    })
    void acceptedCodesNormaliseBeforeValidating(String raw, String expected) {
        assertThat(PortalAppCode.parse(raw).value()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] rejects ''{0}''")
    @CsvSource(value = {
            "''",             // empty
            "'   '",          // blank
            "-lead",          // must start with a letter or digit, not '-'
            "'has space'",
            "dot.ted",        // '.' is not accepted
    })
    void rejectedCodesThrowCodeInvalid(String raw) {
        assertThatThrownBy(() -> PortalAppCode.parse(raw))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("CODE_INVALID");
                });
    }

    /// [PortalAppCode#normalize] trims + lower-cases only — no pattern
    /// check, for a lookup key that must still find a row on mistyped case.
    @ParameterizedTest
    @CsvSource({
            "'  Customer-Portal ', customer-portal",
            "'has space', 'has space'",
    })
    void normalizeTrimsAndLowerCasesWithoutValidating(String raw, String expected) {
        assertThat(PortalAppCode.normalize(raw)).isEqualTo(expected);
    }
}
