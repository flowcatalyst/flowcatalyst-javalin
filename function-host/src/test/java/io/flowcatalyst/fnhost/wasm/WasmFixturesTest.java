package io.flowcatalyst.fnhost.wasm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/function-wasm-runtime.md` §5: every committed `.wasm` fixture's
/// sha256 matches the record `make wasm-fixtures` wrote beside it
/// (`SHA256SUMS`), and no module is committed without a record — so a fixture
/// cannot change without its source being rebuilt on purpose.
class WasmFixturesTest {

    @Test
    void everyCommittedWasmFixtureMatchesItsRecordedSha256() throws Exception {
        Path fixtureDir = Path.of(WasmFixturesTest.class.getResource("/wasm/SHA256SUMS").toURI()).getParent();
        Map<String, String> recorded = new LinkedHashMap<>();
        for (String line : Files.readAllLines(fixtureDir.resolve("SHA256SUMS"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+", 2);
            recorded.put(parts[1].replaceFirst("^\\*", ""), parts[0]);
        }
        try (Stream<Path> files = Files.list(fixtureDir)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".wasm")).toList())
                    .as("every committed module has a recorded digest, and every record a module")
                    .containsExactlyInAnyOrderElementsOf(recorded.keySet());
        }
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, String> fixture : recorded.entrySet()) {
            byte[] bytes = Files.readAllBytes(fixtureDir.resolve(fixture.getKey()));
            assertThat(HexFormat.of().formatHex(sha256.digest(bytes)))
                    .as("%s changed without `make wasm-fixtures`", fixture.getKey())
                    .isEqualTo(fixture.getValue());
        }
    }
}
