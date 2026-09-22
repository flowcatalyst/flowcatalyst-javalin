package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn config get|set` — `set` is read-modify-write of the full map (spec §2):
/// setting one key must not drop an already-set OTHER key. `set` also
/// creates the function on a 404 for its address, the way `fn publish` does
/// (`docs/spec/function-backlog-2026-09-22.md` Unit F) — `get` never does.
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

    private Path manifest() throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, """
                {"runtime":"jvm","entrypoint":"x.Fn","pool":"default","warm":false,"endpoints":[]}
                """);
        return manifest;
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
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
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

    /// `set` on an unknown address, with `--manifest`, creates the function
    /// (runtime from the manifest, address split into app/service/name) and
    /// then sets the value against the newly created (so far empty) config.
    /// Mutant: skip the create and go straight to the read-modify-write —
    /// pinned because that would 404 on the `/config` GET below, which this
    /// test registers to answer only once the function is known to exist.
    @Test
    void setOnUnknownAddressWithManifestCreatesTheFunctionAndSetsTheValue() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> createBody = new AtomicReference<>();
            AtomicInteger createCalls = new AtomicInteger();
            // No GET /api/functions/app.svc.fn handler ⇒ the fake's default 404.
            platform.on("POST", "/api/functions", ex -> {
                createCalls.incrementAndGet();
                createBody.set(FakePlatform.bodyOf(ex));
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });
            platform.on("GET", "/api/functions/app.svc.fn/config", ex -> FakePlatform.writeJson(ex, 200,
                    Map.of("values", Map.of(), "declared", java.util.List.of(), "missing", java.util.List.of())));
            AtomicReference<String> putBody = new AtomicReference<>();
            platform.on("PUT", "/api/functions/app.svc.fn/config", ex -> {
                putBody.set(FakePlatform.bodyOf(ex));
                FakePlatform.writeJson(ex, 200, Map.of("values", Map.of("NEW", "value"),
                        "declared", java.util.List.of(), "missing", java.util.List.of()));
            });

            var r = FnCliTestSupport.run(env(platform), "fn", "config", "set", "app.svc.fn", "NEW=value",
                    "--manifest", manifest().toString());
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(createCalls.get()).as("must create exactly once").isEqualTo(1);
            var created = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(createBody.get());
            assertThat(created.path("applicationCode").asString()).isEqualTo("app");
            assertThat(created.path("serviceName").asString()).isEqualTo("svc");
            assertThat(created.path("name").asString()).isEqualTo("fn");
            assertThat(created.path("runtime").asString()).isEqualTo("jvm");
            var sent = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(putBody.get());
            assertThat(sent.path("values").path("NEW").asString()).isEqualTo("value");
        }
    }

    /// `--no-create` on an unknown address: exit 1, and neither the create
    /// POST nor the config PUT is ever sent. Mutant: ignore `--no-create` and
    /// create anyway.
    @Test
    void setWithNoCreateOnUnknownAddressExitsOneAndPostsNothing() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger createCalls = new AtomicInteger();
            AtomicInteger putCalls = new AtomicInteger();
            platform.on("POST", "/api/functions", ex -> {
                createCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });
            platform.on("PUT", "/api/functions/app.svc.fn/config", ex -> {
                putCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("values", Map.of(), "declared", java.util.List.of(), "missing", java.util.List.of()));
            });

            var r = FnCliTestSupport.run(env(platform), "fn", "config", "set", "app.svc.fn", "NEW=value",
                    "--manifest", manifest().toString(), "--no-create");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(createCalls.get()).as("must never create when --no-create is given").isZero();
            assertThat(putCalls.get()).as("must never set when creation was refused").isZero();
        }
    }

    /// 404 with no `--manifest` and no default `manifest.json` in the working
    /// directory: exit 1 with the exact message, nothing posted or put.
    /// Mutant: attempt to create anyway (NPE on the missing manifest, or a
    /// create with a null runtime) — pinned by asserting zero create calls
    /// AND the precise wording.
    @Test
    void setWithNoManifestOnUnknownAddressExitsOneWithMessage() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger createCalls = new AtomicInteger();
            AtomicInteger putCalls = new AtomicInteger();
            platform.on("POST", "/api/functions", ex -> {
                createCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });
            platform.on("PUT", "/api/functions/app.svc.fn/config", ex -> {
                putCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("values", Map.of(), "declared", java.util.List.of(), "missing", java.util.List.of()));
            });

            var r = FnCliTestSupport.run(env(platform), "fn", "config", "set", "app.svc.fn", "NEW=value");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("function app.svc.fn does not exist and no manifest.json was found to "
                    + "create it from — pass --manifest, or run fn publish first");
            assertThat(createCalls.get()).isZero();
            assertThat(putCalls.get()).isZero();
        }
    }

    /// `config get` on an unknown address does NOT create the function — a
    /// read must not create. Mutant: create on `get` too — pinned on the
    /// existence-check GET itself never being called (not just on the create
    /// POST), so this fails even if a "create on get" mutant has no manifest
    /// to create from and never reaches the POST.
    @Test
    void getOnUnknownAddressExitsOneAndCreatesNothing() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger existenceCheckCalls = new AtomicInteger();
            AtomicInteger createCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> {
                existenceCheckCalls.incrementAndGet();
                FakePlatform.writeError(ex, 404, "Function_NOT_FOUND", "function not found: app.svc.fn");
            });
            // No GET /api/functions/app.svc.fn/config handler ⇒ the fake's default 404.
            platform.on("POST", "/api/functions", ex -> {
                createCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });

            var r = FnCliTestSupport.run(env(platform), "fn", "config", "get", "app.svc.fn");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(existenceCheckCalls.get()).as("a read must never even check for existence").isZero();
            assertThat(createCalls.get()).as("a read must never create").isZero();
        }
    }
}
