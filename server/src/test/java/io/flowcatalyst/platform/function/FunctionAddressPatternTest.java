package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-registry.md` §3.3: the `matches` table (§8 M3: whole-segment,
/// never a string prefix) and the parse Accepted/Rejected rules.
class FunctionAddressPatternTest {

    // ── matches — §3.3's table ───────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}: pattern={1} address={2} -> {3}")
    @CsvSource({
            "exact,                    billing.invoices.create, billing.invoices.create,  true",
            "exact,                    billing.invoices.create, billing.invoices.created, false",
            "service,                  billing.invoices.*,      billing.invoices.create,  true",
            "'service whole segment',  billing.invoices.*,      billing.invoices-v2.create, false",
            "'service other app',      billing.invoices.*,      billing2.invoices.create, false",
            "application,              billing.*,               billing.invoices.create,  true",
            "application,              billing.*,               billing.payments.refund,  true",
            "'application whole segment', billing.*,            billing-eu.invoices.create, false",
    })
    void matchesTable(String rule, String pattern, String address, boolean expected) {
        assertThat(FunctionAddressPattern.parse(pattern).matches(FunctionAddress.parse(address)))
                .as(rule).isEqualTo(expected);
    }

    @Test
    void nullAddressNeverMatches() {
        assertThat(FunctionAddressPattern.parse("billing.*").matches(null)).isFalse();
        assertThat(FunctionAddressPattern.parse("billing.invoices.create").matches(null)).isFalse();
        assertThat(FunctionAddressPattern.parse("billing.invoices.*").matches(null)).isFalse();
    }

    @Test
    void matchIsWholeSegmentNotPrefix() {
        // billing.invoices.* must not match billing.invoices-v2.create via a
        // startsWith/LIKE-style prefix comparison (§8 M3).
        FunctionAddressPattern pattern = FunctionAddressPattern.parse("billing.invoices.*");
        assertThat(pattern.matches(FunctionAddress.parse("billing.invoices-v2.create"))).isFalse();
    }

    // ── parse — shape rules ───────────────────────────────────────────────────

    @Test
    void exactParsesToExact() {
        assertThat(FunctionAddressPattern.parse("billing.invoices.create"))
                .isEqualTo(new FunctionAddressPattern.Exact(FunctionAddress.parse("billing.invoices.create")));
        assertThat(FunctionAddressPattern.parse("billing.invoices.create").render())
                .isEqualTo("billing.invoices.create");
    }

    @Test
    void servicePatternParses() {
        var pattern = FunctionAddressPattern.parse("billing.invoices.*");
        assertThat(pattern).isInstanceOf(FunctionAddressPattern.Service.class);
        assertThat(pattern.render()).isEqualTo("billing.invoices.*");
    }

    @Test
    void applicationPatternParses() {
        var pattern = FunctionAddressPattern.parse("billing.*");
        assertThat(pattern).isInstanceOf(FunctionAddressPattern.Application.class);
        assertThat(pattern.render()).isEqualTo("billing.*");
    }

    @ParameterizedTest(name = "[{index}] rejected: {0}")
    @CsvSource({
            "'*'",                 // bare *
            "a.*.c",                // wildcard not last
            "'*.b.c'",              // wildcard not last
            "billing.inv*",         // partial-segment wildcard
            "a.b.c.*",              // four segments
    })
    void rejectedPatterns(String raw) {
        assertThatThrownBy(() -> FunctionAddressPattern.parse(raw))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("ADDRESS_PATTERN_INVALID");
                });
    }

    @Test
    void nullRejected() {
        assertThatThrownBy(() -> FunctionAddressPattern.parse(null))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error().code())
                .isEqualTo("ADDRESS_PATTERN_INVALID");
    }
}
