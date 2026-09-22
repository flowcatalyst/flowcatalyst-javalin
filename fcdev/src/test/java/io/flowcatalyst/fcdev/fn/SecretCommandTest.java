package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn secret set|list|delete` — spec §4 E6: "never takes the value as an
/// argument and never echoes it". [FnCliTestSupport] captures BOTH picocli
/// writers (stdout AND stderr), so a value that leaked into either would be
/// caught. `set` also creates the function on a 404 for its address, the way
/// `fn publish` does (`docs/spec/function-backlog-2026-09-22.md` Unit F) —
/// `list` never does.
class SecretCommandTest {

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

    /// Drives `fn secret set` with stdin piped from `pipedValue`, capturing
    /// both writers.
    private FnCliTestSupport.Run runSetWithStdin(Map<String, String> env, String pipedValue, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        CommandLine cl = FnCliTestSupport.commandLine(env, out, err);
        var setCli = cl.getSubcommands().get("fn").getSubcommands().get("secret").getSubcommands().get("set");
        var set = (SecretCommand.Set) setCli.getCommand();
        set.console = null; // force the "piped" branch, same as a real non-TTY invocation
        set.stdin = new ByteArrayInputStream(pipedValue.getBytes(StandardCharsets.UTF_8));
        int exit = cl.execute(args);
        return new FnCliTestSupport.Run(exit, out.toString(), err.toString());
    }

    @Test
    void setReadsTheValueFromStdinAndNeverEchoesIt() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> received = new AtomicReference<>();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/secrets/API_KEY", ex -> {
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(FakePlatform.bodyOf(ex));
                received.set(node.path("value").asString());
                FakePlatform.writeNoBody(ex, 204);
            });

            var r = runSetWithStdin(env(platform), "s3cr3t-value\n", "fn", "secret", "set", "app.svc.fn", "API_KEY");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(received.get()).as("the platform must still receive the value").isEqualTo("s3cr3t-value");
            assertThat(r.out()).as("value must never appear on stdout").doesNotContain("s3cr3t-value");
            assertThat(r.err()).as("value must never appear on stderr").doesNotContain("s3cr3t-value");
        }
    }

    @Test
    void trailingNewlineFromStdinIsStripped() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> received = new AtomicReference<>();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/secrets/K", ex -> {
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(FakePlatform.bodyOf(ex));
                received.set(node.path("value").asString());
                FakePlatform.writeNoBody(ex, 204);
            });
            var r = runSetWithStdin(env(platform), "value-with-newline\n", "fn", "secret", "set", "app.svc.fn", "K");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(received.get()).isEqualTo("value-with-newline");
        }
    }

    @Test
    void fromFileReadsTheValueAndNeverEchoesIt() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicReference<String> received = new AtomicReference<>();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/secrets/K", ex -> {
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(FakePlatform.bodyOf(ex));
                received.set(node.path("value").asString());
                FakePlatform.writeNoBody(ex, 204);
            });
            Path file = dir.resolve("secret.txt");
            Files.writeString(file, "file-secret-value\n");
            var r = FnCliTestSupport.run(env(platform), "fn", "secret", "set", "app.svc.fn", "K", "--from-file", file.toString());
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(received.get()).isEqualTo("file-secret-value");
            assertThat(r.out()).doesNotContain("file-secret-value");
            assertThat(r.err()).doesNotContain("file-secret-value");
        }
    }

    @Test
    void listNeverPrintsAValueOnlyKeysAndMetadata() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn/secrets", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "keys", java.util.List.of(Map.of("key", "API_KEY", "updatedAt", "2026-01-01T00:00:00.000000Z", "updatedBy", "u_1")),
                    "declared", java.util.List.of("API_KEY"), "missing", java.util.List.of())));
            var r = FnCliTestSupport.run(env(platform), "fn", "secret", "list", "app.svc.fn");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).contains("API_KEY").doesNotContain("value");
        }
    }

    @Test
    void deleteCallsTheDeleteRoute() throws Exception {
        try (var platform = FakePlatform.start()) {
            var called = new java.util.concurrent.atomic.AtomicBoolean();
            platform.on("DELETE", "/api/functions/app.svc.fn/secrets/K", ex -> {
                called.set(true);
                FakePlatform.writeNoBody(ex, 204);
            });
            var r = FnCliTestSupport.run(env(platform), "fn", "secret", "delete", "app.svc.fn", "K");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(called.get()).isTrue();
        }
    }

    @Test
    void emptyValueIsAUsageError() {
        var r = runSetWithStdin(Map.of(), "", "fn", "secret", "set", "app.svc.fn", "K");
        assertThat(r.exit()).isEqualTo(2);
    }

    /// `secret set` has no GET of its own before the PUT — the existence
    /// check added for this unit IS what creates the function here. Unknown
    /// address + `--manifest` ⇒ create (runtime from the manifest, address
    /// split into app/service/name), then the secret is still set. Mutant:
    /// skip the create and PUT straight away — pinned on the recorded create
    /// call count and body.
    @Test
    void setOnUnknownAddressWithManifestCreatesTheFunctionAndSetsTheValue() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger createCalls = new AtomicInteger();
            AtomicReference<String> createBody = new AtomicReference<>();
            platform.on("POST", "/api/functions", ex -> {
                createCalls.incrementAndGet();
                createBody.set(FakePlatform.bodyOf(ex));
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });
            AtomicReference<String> received = new AtomicReference<>();
            platform.on("PUT", "/api/functions/app.svc.fn/secrets/API_KEY", ex -> {
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(FakePlatform.bodyOf(ex));
                received.set(node.path("value").asString());
                FakePlatform.writeNoBody(ex, 204);
            });

            var r = runSetWithStdin(env(platform), "s3cr3t-value\n", "fn", "secret", "set", "app.svc.fn", "API_KEY",
                    "--manifest", manifest().toString());
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(createCalls.get()).as("must create exactly once").isEqualTo(1);
            var created = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(createBody.get());
            assertThat(created.path("applicationCode").asString()).isEqualTo("app");
            assertThat(created.path("serviceName").asString()).isEqualTo("svc");
            assertThat(created.path("name").asString()).isEqualTo("fn");
            assertThat(created.path("runtime").asString()).isEqualTo("jvm");
            assertThat(received.get()).isEqualTo("s3cr3t-value");
        }
    }

    /// `--no-create` on an unknown address: exit 1, and neither the create
    /// POST nor the secret PUT is ever sent. Mutant: ignore `--no-create` and
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
            platform.on("PUT", "/api/functions/app.svc.fn/secrets/API_KEY", ex -> {
                putCalls.incrementAndGet();
                FakePlatform.writeNoBody(ex, 204);
            });

            var r = runSetWithStdin(env(platform), "s3cr3t-value\n", "fn", "secret", "set", "app.svc.fn", "API_KEY",
                    "--manifest", manifest().toString(), "--no-create");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(createCalls.get()).as("must never create when --no-create is given").isZero();
            assertThat(putCalls.get()).as("must never set when creation was refused").isZero();
        }
    }

    /// 404 with no `--manifest` and no default `manifest.json` in the working
    /// directory: exit 1 with the exact message, nothing posted or put.
    @Test
    void setWithNoManifestOnUnknownAddressExitsOneWithMessage() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger createCalls = new AtomicInteger();
            AtomicInteger putCalls = new AtomicInteger();
            platform.on("POST", "/api/functions", ex -> {
                createCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });
            platform.on("PUT", "/api/functions/app.svc.fn/secrets/API_KEY", ex -> {
                putCalls.incrementAndGet();
                FakePlatform.writeNoBody(ex, 204);
            });

            var r = runSetWithStdin(env(platform), "s3cr3t-value\n", "fn", "secret", "set", "app.svc.fn", "API_KEY");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("function app.svc.fn does not exist and no manifest.json was found to "
                    + "create it from — pass --manifest, or run fn publish first");
            assertThat(createCalls.get()).isZero();
            assertThat(putCalls.get()).isZero();
        }
    }

    /// `secret list` on an unknown address does NOT create the function — a
    /// read must not create. Mutant: create on `list` too — pinned on the
    /// existence-check GET itself never being called (not just on the create
    /// POST), so this fails even if a "create on list" mutant has no
    /// manifest to create from and never reaches the POST.
    @Test
    void listOnUnknownAddressExitsOneAndCreatesNothing() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger existenceCheckCalls = new AtomicInteger();
            AtomicInteger createCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> {
                existenceCheckCalls.incrementAndGet();
                FakePlatform.writeError(ex, 404, "Function_NOT_FOUND", "function not found: app.svc.fn");
            });
            // No GET /api/functions/app.svc.fn/secrets handler ⇒ the fake's default 404.
            platform.on("POST", "/api/functions", ex -> {
                createCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });

            var r = FnCliTestSupport.run(env(platform), "fn", "secret", "list", "app.svc.fn");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(existenceCheckCalls.get()).as("a read must never even check for existence").isZero();
            assertThat(createCalls.get()).as("a read must never create").isZero();
        }
    }
}
