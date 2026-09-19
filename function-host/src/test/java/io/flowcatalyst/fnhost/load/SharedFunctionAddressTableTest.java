package io.flowcatalyst.fnhost.load;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// L12 (`docs/spec/function-host-core.md` §1, §3): the API jar's own
/// `io.flowcatalyst.function.FunctionAddress` and the server's
/// `io.flowcatalyst.platform.function.FunctionAddress` — two deliberate,
/// separate parsers — agree on every row of the one shared table. A
/// test-jar is out of scope for this slice, so this reads
/// `function-api`'s test resource by relative path rather than as a
/// dependency; a missing file fails loudly rather than silently skipping
/// every row.
class SharedFunctionAddressTableTest {

    private static final Path TABLE_PATH = Path.of("..", "function-api", "src", "test", "resources",
            "function-address-table.csv");

    @ParameterizedTest(name = "[{index}] \"{0}\" -> accepted={1}")
    @MethodSource("sharedTable")
    void apiAddressParserAgreesWithTheTable(String raw, boolean accepted) {
        if (accepted) {
            assertThat(io.flowcatalyst.function.FunctionAddress.parse(raw).render()).isEqualTo(raw);
        } else {
            assertThatThrownBy(() -> io.flowcatalyst.function.FunctionAddress.parse(raw))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> accepted={1}")
    @MethodSource("sharedTable")
    void platformAddressParserAgreesWithTheTable(String raw, boolean accepted) {
        if (accepted) {
            assertThat(io.flowcatalyst.platform.function.FunctionAddress.parse(raw).render()).isEqualTo(raw);
        } else {
            assertThatThrownBy(() -> io.flowcatalyst.platform.function.FunctionAddress.parse(raw))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    static Stream<Arguments> sharedTable() throws IOException {
        assertThat(Files.exists(TABLE_PATH))
                .as(() -> "shared address table not found at " + TABLE_PATH.toAbsolutePath()
                        + " — function-api's test resources must be built first")
                .isTrue();

        List<Arguments> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(TABLE_PATH, StandardCharsets.UTF_8)) {
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
        assertThat(rows).as("shared address table must contain rows").isNotEmpty();
        return rows.stream();
    }
}
