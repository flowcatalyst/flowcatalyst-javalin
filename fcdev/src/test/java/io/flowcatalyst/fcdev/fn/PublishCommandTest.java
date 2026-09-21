package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn publish` — spec `function-artifact-upload.md` §5: "no --artifact-ref
/// uploads and publishes the returned ref; re-publishing the same jar is the
/// platform's `VERSION_DIGEST_EXISTS`, exit 1, message printed; two-part
/// address ⇒ exit 2".
class PublishCommandTest {

    /// sha256 of the literal jar content `"hello"` — every test that uploads
    /// that exact jar asserts against the platform route this digest names.
    private static final String DIGEST_HELLO = "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

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

    /// No `--artifact-ref`: the jar is uploaded (`PUT .../artifacts/{digest}`,
    /// the EXACT bytes) and the version is published with the ref the
    /// UPLOAD returned — never one this CLI built itself. The fake's upload
    /// response uses a deliberately distinctive ref so echoing a locally
    /// built `platform://…` string would fail this. Mutant: build the ref
    /// locally instead of using the response.
    @Test
    void noArtifactRefUploadsTheJarAndPublishesTheUploadsOwnReturnedRef() throws Exception {
        try (var platform = FakePlatform.start()) {
            var uploadedBytes = new java.util.concurrent.atomic.AtomicReference<byte[]>();
            var uploadPath = new java.util.concurrent.atomic.AtomicReference<String>();
            var putCalls = new AtomicInteger();
            var receivedRef = new java.util.concurrent.atomic.AtomicReference<String>();
            String distinctiveRef = "platform://fnc_1/deadbeef-not-locally-buildable";

            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/" + DIGEST_HELLO, ex -> {
                putCalls.incrementAndGet();
                uploadPath.set(ex.getRequestURI().getPath());
                uploadedBytes.set(ex.getRequestBody().readAllBytes());
                FakePlatform.writeJson(ex, 200,
                        Map.of("artifactRef", distinctiveRef, "digest", DIGEST_HELLO, "bytes", 5));
            });
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
            assertThat(putCalls.get()).as("exactly one upload PUT").isEqualTo(1);
            assertThat(uploadedBytes.get()).as("the uploaded bytes must be the jar's own bytes")
                    .isEqualTo(Files.readAllBytes(jarPath));
            assertThat(uploadPath.get()).isEqualTo("/api/functions/app.svc.fn/artifacts/" + DIGEST_HELLO);
            assertThat(receivedRef.get()).as("mutant: build the ref locally instead of using the upload's response")
                    .isEqualTo(distinctiveRef);
        }
    }

    /// `--bundle` rides along with an UPLOADED artifact too: the bundle signs the
    /// jar's bytes, not where they are kept, and a platform with signatures
    /// required refuses a publish without one. Mutant: read the bundle only
    /// on the `--artifact-ref` branch.
    @Test
    void anUploadedPublishCarriesTheSignatureBundle() throws Exception {
        try (var platform = FakePlatform.start()) {
            var receivedBundle = new java.util.concurrent.atomic.AtomicReference<String>();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/" + DIGEST_HELLO, ex -> {
                ex.getRequestBody().readAllBytes();
                FakePlatform.writeJson(ex, 200, Map.of("artifactRef", "platform://fnc_1/x", "digest", DIGEST_HELLO, "bytes", 5));
            });
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex -> {
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(FakePlatform.bodyOf(ex));
                receivedBundle.set(node.path("signatureBundle").asString(null));
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnv_1", "version", 1, "state", "PUBLISHED", "digest", DIGEST_HELLO));
            });
            Path bundle = dir.resolve("fn.sigstore.json");
            Files.writeString(bundle, "{\"the\":\"bundle\"}");

            var r = FnCliTestSupport.run(envFor(platform, dir.resolve("state")), "fn", "publish", jar("hello").toString(),
                    "app.svc.fn", "--manifest", manifest().toString(), "--bundle", bundle.toString());
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(receivedBundle.get()).isEqualTo("{\"the\":\"bundle\"}");
        }
    }

    /// `--artifact-ref oci://…` publishes by reference — no upload PUT at all.
    /// Mutant: upload even when `--artifact-ref` is given.
    @Test
    void artifactRefSkipsTheUploadEntirely() throws Exception {
        try (var platform = FakePlatform.start()) {
            var putCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/" + DIGEST_HELLO, ex -> {
                putCalls.incrementAndGet();
                FakePlatform.writeNoBody(ex, 200);
            });
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex -> {
                String body = FakePlatform.bodyOf(ex);
                var node = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(body);
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnv_1", "version", 1, "state", "PUBLISHED",
                        "digest", node.path("digest").asString()));
            });

            var r = FnCliTestSupport.run(envFor(platform, dir.resolve("state")), "fn", "publish",
                    jar("hello").toString(), "app.svc.fn", "--manifest", manifest().toString(),
                    "--artifact-ref", "oci://ghcr.io/acme/fn");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(putCalls.get()).as("mutant: upload even when --artifact-ref is given").isZero();
        }
    }

    /// An upload rejected with 422 is a non-zero exit, the platform's code on
    /// stderr, and NO publish call at all. Mutant: publish after a failed
    /// upload.
    @Test
    void aFailedUploadNeverPublishes() throws Exception {
        try (var platform = FakePlatform.start()) {
            var publishCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/" + DIGEST_HELLO, ex ->
                    FakePlatform.writeError(ex, 422, "DIGEST_MISMATCH", "bytes do not hash to the digest"));
            platform.on("POST", "/api/functions/app.svc.fn/versions", ex -> {
                publishCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 201, Map.of("id", "fnv_1", "version", 1, "state", "PUBLISHED", "digest", DIGEST_HELLO));
            });

            var r = FnCliTestSupport.run(envFor(platform, dir.resolve("state")), "fn", "publish",
                    jar("hello").toString(), "app.svc.fn", "--manifest", manifest().toString());
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err()).contains("DIGEST_MISMATCH");
            assertThat(publishCalls.get()).as("mutant: publish after a failed upload").isZero();
        }
    }

    /// Re-publishing the identical digest surfaces the platform's
    /// `VERSION_DIGEST_EXISTS` verbatim — exit 1, one line, never swallowed.
    /// Mutant: swallow the error / retry silently.
    @Test
    void rePublishingSameDigestSurfacesVersionDigestExists() throws Exception {
        String digestSame = "sha256:0967115f2813a3541eaef77de9d9d5773f1c0c04314b0bbfe4ff3b3b1c55b5d5";
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/" + digestSame, ex ->
                    FakePlatform.writeJson(ex, 200, Map.of("artifactRef", "platform://fnc_1/" + digestSame.substring(7),
                            "digest", digestSame, "bytes", 4)));
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

    /// Publishing twice with the SAME jar content uploads twice — the
    /// platform's upload route is itself idempotent for a digest already
    /// stored (spec §3: "a 200 with the same body"); the CLI has no local
    /// cache of its own left to dedupe with.
    @Test
    void republishingTheIdenticalJarUploadsAgainEachTime() throws Exception {
        try (var platform = FakePlatform.start()) {
            String digest = "sha256:" + Publisher.sha256(jar("identical-content"))
                    .substring("sha256:".length()); // computed once for the assertion below
            AtomicInteger putCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn", ex -> FakePlatform.writeJson(ex, 200, Map.of("id", "fnc_1")));
            platform.on("PUT", "/api/functions/app.svc.fn/artifacts/" + digest, ex -> {
                putCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("artifactRef", "platform://fnc_1/" + digest.substring(7),
                        "digest", digest, "bytes", 17));
            });
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

            var r2 = FnCliTestSupport.run(envFor(platform, dataDir), "fn", "publish", jarPath.toString(),
                    "app.svc.fn", "--manifest", manifest().toString());
            assertThat(r2.exit()).as(r2.err()).isZero();
            assertThat(putCalls.get()).isEqualTo(2);
        }
    }
}
