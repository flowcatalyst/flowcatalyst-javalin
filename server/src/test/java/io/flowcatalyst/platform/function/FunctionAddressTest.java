package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-registry.md` §3.2: every row of the Accepted/Rejected table.
class FunctionAddressTest {

    @Test
    void threePartsAccepted() {
        FunctionAddress address = FunctionAddress.parse("billing.invoices.create");
        assertThat(address.application().value()).isEqualTo("billing");
        assertThat(address.service().value()).isEqualTo("invoices");
        assertThat(address.name().value()).isEqualTo("create");
        assertThat(address.render()).isEqualTo("billing.invoices.create");
        assertThat(address.toString()).isEqualTo("billing.invoices.create");
    }

    @ParameterizedTest(name = "[{index}] wrong count: {0}")
    @CsvSource({"billing.create", "a.b.c.d", "billing"})
    void wrongPartCountRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest(name = "[{index}] empty part: ''{0}''")
    @CsvSource({"'billing..create'", "'.b.c'", "'a.b.'", "''"})
    void emptyPartRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest(name = "[{index}] segment rule accepted: {0}")
    @CsvSource({"b-1.inv-2.c-3"})
    void segmentRuleAccepted(String raw) {
        assertThat(FunctionAddress.parse(raw).render()).isEqualTo(raw);
    }

    @ParameterizedTest(name = "[{index}] segment rule rejected: {0}")
    @CsvSource({"Billing.invoices.create", "billing.in_voices.create", "a.-b.c"})
    void segmentRuleRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest(name = "[{index}] wildcards rejected: {0}")
    @CsvSource({"billing.invoices.*", "*.b.c"})
    void wildcardsRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest(name = "[{index}] whitespace rejected: ''{0}''")
    @CsvSource({"' a.b.c'", "'a.b.c '"})
    void whitespaceRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest
    @NullSource
    void absentRejected(String raw) {
        assertRejected(raw);
    }

    @Test
    void ofBuildsFromParsedLabels() {
        DnsLabel app = DnsLabel.parse("f", "billing");
        DnsLabel svc = DnsLabel.parse("f", "invoices");
        DnsLabel name = DnsLabel.parse("f", "create");
        assertThat(FunctionAddress.of(app, svc, name)).isEqualTo(FunctionAddress.parse("billing.invoices.create"));
    }

    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> FunctionAddress.parse(raw))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("ADDRESS_INVALID");
                });
    }
}
