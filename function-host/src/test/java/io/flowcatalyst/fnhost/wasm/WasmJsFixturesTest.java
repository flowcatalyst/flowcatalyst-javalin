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

/// `docs/spec/function-js-guest.md` §2: the committed `function-hello-js`
/// module's sha256 matches the record `make wasm-fixtures` wrote beside it,
/// in its own `wasm/js/SHA256SUMS` — separate from [WasmFixturesTest]'s
/// `wasm/SHA256SUMS` so the two fixture sets never collide.
class WasmJsFixturesTest {

    @Test
    void theCommittedJsFixtureMatchesItsRecordedSha256() throws Exception {
        Path fixtureDir = Path.of(WasmJsFixturesTest.class.getResource("/wasm/js/SHA256SUMS").toURI()).getParent();
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
