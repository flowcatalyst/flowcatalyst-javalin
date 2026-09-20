package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn publish` — spec §4 E4: "stores the copy, not the build path;
/// re-publishing the same jar is the platform's `VERSION_DIGEST_EXISTS`,
/// exit 1, message printed; two-part address ⇒ exit 2".
class PublishCommandTest {

    @TempDir
    Path dir;

    private Map<String, String> envFor(FakePlatform platform, Path dataDir) {
        var env = new HashMap<String, String>();
        env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
        env.put("FLOWCATALYST_CLIENT_ID", "id");
        env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
        env.put("XDG_DATA_HOME", dataDir.toString());
        return env;
    }

    private Path jar(String content) throws Exception {
        Path jar = dir.resolve("fn-" + content.hashCode() + ".jar");
        Files.writeString(jar, content);
        return jar;
    }

    private Path manifest() throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, """
                {"runtime":"jvm","entrypoint":"x.Fn","pool":"default","warm":false,"endpoints":[]}
                """);
        return manifest;
    }

    /// The published `artifactRef` must be the CLI's own copy under
    /// `fn-artifacts/`, never the jar's original (about-to-be-overwritten)
    /// build path. Mutant: pass the build path straight through.
    @Test
    void localPublishStoresACopyNotTheBuildPath() throws Exception {
        try (var platform = FakePlatform.start()) {
            var receivedRef = new java.util.concurrent.atomic.AtomicReference<String>();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeError(ex, 404, "Function_NOT_FOUND", "not found"));
            platform.on("POST", "/api/functions", ex -> FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1")));
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex -> {
                String body = FakePlatform.bodyOf(ex);
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(body);
                receivedRef.set(node.path("artifactRef").asString());
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnv_1", "version", 1, "state", "PUBLISHED", "digest", node.path("digest").asString()));
            });

            Path dataDir = dir.resolve("state");
            Path jarPath = jar("hello");
            var r = FnCliTestSupport.run(envFor(platform, dataDir), "fn", "publish", jarPath.toString(),
                    "app.svc.fn", "--manifest", manifest().toString());
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(receivedRef.get()).startsWith("file://").contains("fn-artifacts");
            assertThat(receivedRef.get()).as("must not be the original build-path jar")
                    .doesNotContain(jarPath.getFileName().toString());
        }
    }

    /// Re-publishing the identical digest surfaces the platform's
    /// `VERSION_DIGEST_EXISTS` verbatim — exit 1, one line, never swallowed.
    /// Mutant: swallow the error / retry silently.
    @Test
    void rePublishingSameDigestSurfacesVersionDigestExists() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex ->
                    FakePlatform.writeError(ex, 409, "VERSION_DIGEST_EXISTS",
                            "digest is already published as version 1 for this function", Map.of("version", 1)));

            var r = FnCliTestSupport.run(envFor(platform, dir.resolve("state")), "fn", "publish",
                    jar("same").toString(), "app.svc.fn", "--manifest", manifest().toString());
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("VERSION_DIGEST_EXISTS").contains("version 1");
            assertThat(r.err()).doesNotContain("Exception").doesNotContain("\tat ");
        }
    }

    @Test
    void twoPartAddressIsAUsageError() throws Exception {
        var r = FnCliTestSupport.run(Map.of(), "fn", "publish", jar("x").toString(), "app.fn",
                "--manifest", manifest().toString());
        assertThat(r.exit()).isEqualTo(2);
    }

    /// `--no-create` on an unknown address: exit 1 with the platform's 404
    /// message, and no function is ever created.
    @Test
    void noCreateFailsWithThePlatforms404Message() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger createCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn", ex ->
                    FakePlatform.writeError(ex, 404, "Function_NOT_FOUND", "function not found: app.svc.fn"));
            platform.on("POST", "/api/functions", ex -> {
                createCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnc_1"));
            });

            var r = FnCliTestSupport.run(envFor(platform, dir.resolve("state")), "fn", "publish",
                    jar("x").toString(), "app.svc.fn", "--manifest", manifest().toString(), "--no-create");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("function not found: app.svc.fn");
            assertThat(createCalls.get()).as("must never create when --no-create is given").isZero();
        }
    }

    @Test
    void invalidManifestJsonIsAUsageErrorNamingTheFile() throws Exception {
        Path badManifest = dir.resolve("bad.json");
        Files.writeString(badManifest, "{not json");
        var r = FnCliTestSupport.run(Map.of(), "fn", "publish", jar("x").toString(), "app.svc.fn",
                "--manifest", badManifest.toString());
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains(badManifest.toString());
    }

    /// Publishing twice with the SAME jar content reuses the stored copy
    /// (same target file) rather than writing a second one.
    @Test
    void republishingTheIdenticalJarReusesTheStoredCopy() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            AtomicInteger n = new AtomicInteger();
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex -> {
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(FakePlatform.bodyOf(ex));
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnv_" + n.incrementAndGet(), "version", n.get(),
                        "state", "PUBLISHED", "digest", node.path("digest").asString()));
            });
            Path dataDir = dir.resolve("state");
            Path jarPath = jar("identical-content");
            var r1 = FnCliTestSupport.run(envFor(platform, dataDir), "fn", "publish", jarPath.toString(),
                    "app.svc.fn", "--manifest", manifest().toString());
            assertThat(r1.exit()).as(r1.err()).isZero();
            Path artifactsDir = dataDir.resolve("flowcatalyst").resolve("fn-artifacts");
            assertThat(Files.list(artifactsDir).count()).isEqualTo(1);

            var r2 = FnCliTestSupport.run(envFor(platform, dataDir), "fn", "publish", jarPath.toString(),
                    "app.svc.fn", "--manifest", manifest().toString());
            assertThat(r2.exit()).as(r2.err()).isZero();
            assertThat(Files.list(artifactsDir).count()).as("same digest must reuse the same stored file").isEqualTo(1);
        }
    }
}
