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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn secret set|list|delete` — spec §4 E6: "never takes the value as an
/// argument and never echoes it". [FnCliTestSupport] captures BOTH picocli
/// writers (stdout AND stderr), so a value that leaked into either would be
/// caught.
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
}
