package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn deploy` = publish + promote (spec §2). Deploying the SAME jar twice
/// must promote the EXISTING version the platform's `VERSION_DIGEST_EXISTS`
/// names — via `details.version`, never parsed prose — not fail.
class DeployCommandTest {

    /// sha256 hex of the literal jar bytes `"same-bytes"` / `"x"` used below —
    /// `fn deploy` with no `--artifact-ref` now uploads before publishing, so
    /// every test needs a PUT handler at the exact digest path.
    private static final String SAME_BYTES_DIGEST = "7ad5509fac1a1be4a59ae87959468840d2808bab1b3cd5676733194aa82ef338";
    private static final String X_DIGEST = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881";

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
    void deployingTheSameJarTwiceSecondCallPromotesExistingVersionNoError() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/sha256:" + SAME_BYTES_DIGEST, ex ->
                    FakePlatform.writeJson(ex, 200, Map.of("artifactRef", "platform://fnc_1/" + SAME_BYTES_DIGEST,
                            "digest", "sha256:" + SAME_BYTES_DIGEST, "bytes", 10)));
            AtomicInteger publishCalls = new AtomicInteger();
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex -> {
                if (publishCalls.incrementAndGet() == 1) {
                    var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(FakePlatform.bodyOf(ex));
                    FakePlatform.writeJson(ex, 201, Map.of("id", "fnv_1", "version", 1, "state", "PUBLISHED",
                            "digest", node.path("digest").asString()));
                } else {
                    FakePlatform.writeError(ex, 409, "VERSION_DIGEST_EXISTS",
                            "digest is already published as version 1 for this function", Map.of("version", 1));
                }
            });
            AtomicInteger promoteCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn/status", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "address", "app.svc.fn", "status", "ACTIVE",
                    "versions", java.util.List.of(Map.of("version", 1, "state", "READY")),
                    "hosts", java.util.List.of(), "wiring", java.util.List.of())));
            platform.on("PUT", "/api/functions/app.svc.fn/aliases/live", ex -> {
                promoteCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("alias", "live", "version", 1, "versionId", "fnv_1"));
            });

            Path jar = dir.resolve("fn.jar");
            Files.writeString(jar, "same-bytes");

            var r1 = FnCliTestSupport.run(env(platform), "fn", "deploy", jar.toString(), "app.svc.fn",
                    "--manifest", manifest().toString());
            assertThat(r1.exit()).as(r1.err()).isZero();

            var r2 = FnCliTestSupport.run(env(platform), "fn", "deploy", jar.toString(), "app.svc.fn",
                    "--manifest", manifest().toString());
            assertThat(r2.exit()).as("re-deploying the identical jar must NOT fail: " + r2.err()).isZero();
            assertThat(r2.err()).isEmpty();

            assertThat(promoteCalls.get()).as("both deploys promote version 1").isEqualTo(2);
        }
    }

    /// The realistic shape of "deploy twice": the SECOND deploy's recovered
    /// version is not just already-published but already LIVE (the first
    /// deploy's own promote already did that) — the platform answers the
    /// PUT with `ALIAS_UNCHANGED`, which `fn deploy` must treat as success,
    /// never as an error (found by the end-to-end test against a real
    /// platform, `FnCliEndToEndTest`; this fake-server test pins it fast).
    /// Mutant: let `ALIAS_UNCHANGED` propagate like any other promote error.
    @Test
    void reDeployingAnAlreadyLiveVersionIsSuccessNotAliasUnchanged() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/sha256:" + SAME_BYTES_DIGEST, ex ->
                    FakePlatform.writeJson(ex, 200, Map.of("artifactRef", "platform://fnc_1/" + SAME_BYTES_DIGEST,
                            "digest", "sha256:" + SAME_BYTES_DIGEST, "bytes", 10)));
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex ->
                    FakePlatform.writeError(ex, 409, "VERSION_DIGEST_EXISTS",
                            "digest is already published as version 1 for this function", Map.of("version", 1)));
            platform.on("GET", "/api/functions/app.svc.fn/status", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "address", "app.svc.fn", "status", "ACTIVE",
                    "versions", java.util.List.of(Map.of("version", 1, "state", "READY")),
                    "hosts", java.util.List.of(), "wiring", java.util.List.of())));
            platform.on("PUT", "/api/functions/app.svc.fn/aliases/live", ex ->
                    FakePlatform.writeError(ex, 409, "ALIAS_UNCHANGED", "alias already points at this version"));

            Path jar = dir.resolve("fn.jar");
            Files.writeString(jar, "same-bytes");
            var r = FnCliTestSupport.run(env(platform), "fn", "deploy", jar.toString(), "app.svc.fn",
                    "--manifest", manifest().toString());
            assertThat(r.exit()).as("re-deploying an already-live version must succeed: " + r.err()).isZero();
            assertThat(r.err()).isEmpty();
        }
    }

    /// A publish failure for any OTHER reason is not swallowed the way
    /// `VERSION_DIGEST_EXISTS` is — `details.version` absent (or a different
    /// code entirely) must still surface as an ordinary error.
    @Test
    void aDifferentPublishFailureIsNotTreatedAsADigestMatch() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/sha256:" + X_DIGEST, ex ->
                    FakePlatform.writeJson(ex, 200, Map.of("artifactRef", "platform://fnc_1/" + X_DIGEST,
                            "digest", "sha256:" + X_DIGEST, "bytes", 1)));
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex ->
                    FakePlatform.writeError(ex, 409, "FUNCTION_DISABLED", "function is disabled"));

            Path jar = dir.resolve("fn.jar");
            Files.writeString(jar, "x");
            var r = FnCliTestSupport.run(env(platform), "fn", "deploy", jar.toString(), "app.svc.fn",
                    "--manifest", manifest().toString());
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("FUNCTION_DISABLED");
        }
    }
}
