package io.flowcatalyst.function;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `docs/spec/function-host-core.md` §1, L12: every row of the shared
/// address table in `function-address-table.csv`. `function-host` reads the
/// same file (by relative path, since a test-jar is out of scope for this
/// slice) and runs it through `io.flowcatalyst.platform.function.FunctionAddress`
/// too, so the two parsers cannot quietly drift apart.
class FunctionAddressTest {

    @Test
    void threePartsAccepted() {
        FunctionAddress address = FunctionAddress.parse("billing.invoices.create");
        assertThat(address.application()).isEqualTo("billing");
        assertThat(address.service()).isEqualTo("invoices");
        assertThat(address.name()).isEqualTo("create");
        assertThat(address.render()).isEqualTo("billing.invoices.create");
        assertThat(address.toString()).isEqualTo("billing.invoices.create");
    }

    @Test
    void nullRejected() {
        assertThatThrownBy(() -> FunctionAddress.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofDoesNotReparse() {
        // Same rule as parse(), applied per-component via the canonical constructor.
        assertThat(new FunctionAddress("billing", "invoices", "create"))
                .isEqualTo(FunctionAddress.parse("billing.invoices.create"));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> accepted={1}")
    @MethodSource("sharedTable")
    void sharedTableRow(String raw, boolean accepted) {
        if (accepted) {
            assertThat(FunctionAddress.parse(raw).render()).isEqualTo(raw);
        } else {
            assertThatThrownBy(() -> FunctionAddress.parse(raw)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Stream<Arguments> sharedTable() {
        List<Arguments> rows = new ArrayList<>();
        try (InputStream in = FunctionAddressTest.class.getResourceAsStream("/function-address-table.csv")) {
            assertThat(in).as("function-address-table.csv must be on the test classpath").isNotNull();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                boolean sawHeader = false;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    if (!sawHeader) {
                        sawHeader = true;
                        continue;
                    }
                    int open = line.indexOf('"');
                    int close = line.lastIndexOf('"');
                    String raw = line.substring(open + 1, close);
                    boolean accepted = Boolean.parseBoolean(line.substring(close + 1).replace(",", "").trim());
                    rows.add(Arguments.of(raw, accepted));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(rows).as("function-address-table.csv must contain rows").isNotEmpty();
        return rows.stream();
    }
}
