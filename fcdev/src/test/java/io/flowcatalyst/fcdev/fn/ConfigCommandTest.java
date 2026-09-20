package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn config get|set` — `set` is read-modify-write of the full map (spec §2):
/// setting one key must not drop an already-set OTHER key.
class ConfigCommandTest {

    @TempDir
    Path dir;

    private Map<String, String> env(FakePlatform platform) {
        var env = new HashMap<String, String>();
        env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
        env.put("FLOWCATALYST_CLIENT_ID", "id");
        env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
        env.put("XDG_DATA_HOME", dir.resolve("state").toString());
        return env;
    }

    @Test
    void getPrintsTheValues() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/config", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "values", Map.of("A", "1"), "declared", java.util.List.of("A"), "missing", java.util.List.of())));
            var r = FnCliTestSupport.run(env(platform), "fn", "config", "get", "app.svc.fn");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).contains("A=1");
        }
    }

    /// Mutant: `set` sends only the new key(s), dropping everything else —
    /// pinned by asserting the PUT body still carries the EXISTING key.
    @Test
    void setIsReadModifyWriteAndKeepsExistingKeys() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/config", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "values", Map.of("EXISTING", "old"), "declared", java.util.List.of(), "missing", java.util.List.of())));
            AtomicReference<String> putBody = new AtomicReference<>();
            platform.on("PUT", "/api/functions/app.svc.fn/config", ex -> {
                putBody.set(FakePlatform.bodyOf(ex));
                FakePlatform.writeJson(ex, 200, Map.of("values", Map.of("EXISTING", "old", "NEW", "value"),
                        "declared", java.util.List.of(), "missing", java.util.List.of()));
            });

            var r = FnCliTestSupport.run(env(platform), "fn", "config", "set", "app.svc.fn", "NEW=value");
            assertThat(r.exit()).as(r.err()).isZero();
            var sent = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(putBody.get());
            assertThat(sent.path("values").path("EXISTING").asString()).isEqualTo("old");
            assertThat(sent.path("values").path("NEW").asString()).isEqualTo("value");
        }
    }

    @Test
    void malformedAssignmentIsAUsageError() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "config", "set", "app.svc.fn", "NOEQUALSIGN");
        assertThat(r.exit()).isEqualTo(2);
    }
}
