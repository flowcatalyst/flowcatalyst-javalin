package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn validate` (docs/spec/function-manifest-authoring.md M2.3) posts the manifest verbatim to
/// `POST /api/functions/{address}/manifest/check` and prints the platform's own answer — no
/// runtime-specific branch in [ValidateCommand] itself, so a `runtime: wasm` manifest goes through
/// exactly the same path a `jvm` one does. `docs/spec/function-wasm-platform-ui.md` §2: pin that a
/// `wasm` manifest validates, and that a `wasm` manifest whose `wasmMemoryMb` is over the client's
/// ceiling is reported (the platform's own `LIMIT_OVER_CEILING`, not something this CLI computes).
class ValidateCommandTest {

    @TempDir
    Path dir;

    private Map<String, String> envFor(FakePlatform platform) {
        var env = new HashMap<String, String>();
        env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
        env.put("FLOWCATALYST_CLIENT_ID", "id");
        env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
        env.put("XDG_DATA_HOME", dir.resolve("state").toString());
        return env;
    }

    private Path wasmManifest(int wasmMemoryMb) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, """
                {"runtime":"wasm","entrypoint":"handle","pool":"default","warm":false,
                 "limits":{"wasmMemoryMb":%d},"endpoints":[{"path":"/healthz","auth":"none"}]}
                """.formatted(wasmMemoryMb));
        return manifest;
    }

    /// A valid `runtime: wasm` manifest: the platform answers `valid: true` with an httpOnly-false,
    /// no-op plan, and `fn validate` prints "no changes" and exits 0 — proving the wasm manifest
    /// reached the platform and came back accepted, not refused by this CLI before ever being sent.
    /// Mutant: have [ValidateCommand] refuse any manifest whose `runtime` is not `jvm` before
    /// posting — this test would see no POST at all and fail on `checkCalls`/exit code.
    @Test
    void wasmManifestValidates() throws Exception {
        try (var platform = FakePlatform.start()) {
            var checkCalls = new java.util.concurrent.atomic.AtomicInteger();
            var receivedRuntime = new java.util.concurrent.atomic.AtomicReference<String>();
            platform.on("POST", "/api/functions/app.svc.fn/manifest/check", ex -> {
                checkCalls.incrementAndGet();
                String body = FakePlatform.bodyOf(ex);
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(body);
                receivedRuntime.set(node.path("manifest").path("runtime").asString());
                FakePlatform.writeJson(ex, 200, Map.of(
                        "valid", true,
                        "errors", java.util.List.of(),
                        "plan", Map.of(
                                "httpOnly", false,
                                "pool", Map.of("action", "unchanged"),
                                "subscriptions", java.util.List.of(),
                                "schedules", java.util.List.of(),
                                "publicRoutes", Map.of("action", "unchanged"),
                                "conflicts", java.util.List.of(),
                                "settingsMissing", java.util.List.of())));
            });

            var r = FnCliTestSupport.run(envFor(platform), "fn", "validate", "app.svc.fn",
                    "--manifest", wasmManifest(64).toString());

            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(checkCalls.get()).as("the manifest must actually reach the platform").isEqualTo(1);
            assertThat(receivedRuntime.get()).isEqualTo("wasm");
            assertThat(r.out()).contains("no changes");
        }
    }

    /// A `wasm` manifest whose `limits.wasmMemoryMb` exceeds the client's ceiling: the platform
    /// answers `valid: false` with the `LIMIT_OVER_CEILING` problem, and `fn validate` prints the
    /// code, the pointer and the message on one line, and exits 1. This pins that `ValidateCommand`
    /// surfaces the platform's own rejection verbatim rather than silently accepting an over-cap
    /// wasm manifest. Mutant: print only the code (drop the pointer/message) — the `.contains(...)`
    /// assertions on those substrings fail; mutant: exit 0 regardless of `valid` — the exit
    /// assertion fails.
    @Test
    void wasmManifestOverWasmMemoryCeilingIsReported() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("POST", "/api/functions/app.svc.fn/manifest/check", ex ->
                    FakePlatform.writeJson(ex, 200, Map.of(
                            "valid", false,
                            "errors", java.util.List.of(Map.of(
                                    "code", "LIMIT_OVER_CEILING",
                                    "message", "wasmMemoryMb is 4096, which exceeds the ceiling of 256",
                                    "details", Map.of("pointer", "/limits/wasmMemoryMb"))))));

            var r = FnCliTestSupport.run(envFor(platform), "fn", "validate", "app.svc.fn",
                    "--manifest", wasmManifest(4096).toString());

            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.out()).contains("LIMIT_OVER_CEILING");
            assertThat(r.out()).contains("/limits/wasmMemoryMb");
            assertThat(r.out()).contains("exceeds the ceiling of 256");
        }
    }
}
