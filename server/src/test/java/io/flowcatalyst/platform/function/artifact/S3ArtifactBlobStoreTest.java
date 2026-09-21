package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/// `S3ArtifactBlobStore` (U11, spec `function-artifact-upload.md` §2) driven
/// through the real AWS SDK `S3Client`, path-style, against an in-process
/// fake S3 endpoint ([FakeS3]) — put/exists/open/size/deleteAll, and the key
/// layout with and without a configured prefix.
class S3ArtifactBlobStoreTest {

    private FakeS3 fake;

    @BeforeEach
    void start() throws Exception {
        fake = FakeS3.start();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private S3Client client() {
        return S3Client.builder()
                .endpointOverride(fake.endpoint())
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider.create())
                .requestChecksumCalculation(software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
                .build();
    }

    private static Digest digestOf(byte[] bytes) throws Exception {
        return new Digest("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void putExistsOpenSizeRoundTripWithNoPrefix() throws Exception {
        var store = new S3ArtifactBlobStore(client(), "my-bucket", "");
        byte[] bytes = "hello-s3".getBytes(StandardCharsets.UTF_8);
        Digest digest = digestOf(bytes);
        Path file = Files.writeString(Files.createTempFile("s3-src-", ".bin"), "hello-s3", StandardCharsets.UTF_8);

        assertThat(store.exists("fn1", digest)).isFalse();
        store.put("fn1", digest, file);
        assertThat(store.exists("fn1", digest)).isTrue();
        assertThat(store.size("fn1", digest)).isEqualTo(bytes.length);
        try (var in = store.open("fn1", digest)) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void keyLayoutHasNoPrefixWhenNoneConfigured() throws Exception {
        var store = new S3ArtifactBlobStore(client(), "my-bucket", "");
        byte[] bytes = "no-prefix".getBytes(StandardCharsets.UTF_8);
        Digest digest = digestOf(bytes);
        Path file = Files.writeString(Files.createTempFile("s3-src-", ".bin"), "no-prefix", StandardCharsets.UTF_8);
        store.put("fn1", digest, file);

        String hex = digest.value().substring("sha256:".length());
        // The fake's map is keyed by the raw HTTP request path; assert the OBJECT
        // was written under /{bucket}/{functionId}/{hex} — no prefix segment.
        assertThat(fakeObjectExists("/my-bucket/fn1/" + hex)).isTrue();
    }

    @Test
    void keyLayoutIncludesTheConfiguredPrefix() throws Exception {
        var store = new S3ArtifactBlobStore(client(), "my-bucket", "fn-artifacts");
        byte[] bytes = "with-prefix".getBytes(StandardCharsets.UTF_8);
        Digest digest = digestOf(bytes);
        Path file = Files.writeString(Files.createTempFile("s3-src-", ".bin"), "with-prefix", StandardCharsets.UTF_8);
        store.put("fn1", digest, file);

        String hex = digest.value().substring("sha256:".length());
        assertThat(fakeObjectExists("/my-bucket/fn-artifacts/fn1/" + hex)).isTrue();
        assertThat(store.exists("fn1", digest)).isTrue();
    }

    @Test
    void putIsIdempotentAnExistingObjectIsNeverOverwritten() throws Exception {
        var store = new S3ArtifactBlobStore(client(), "my-bucket", "");
        byte[] bytes = "first".getBytes(StandardCharsets.UTF_8);
        Digest digest = digestOf(bytes);
        Path first = Files.writeString(Files.createTempFile("s3-src-", ".bin"), "first", StandardCharsets.UTF_8);
        Path second = Files.writeString(Files.createTempFile("s3-src-", ".bin"), "second-never-uploaded", StandardCharsets.UTF_8);

        store.put("fn1", digest, first);
        store.put("fn1", digest, second);

        try (var in = store.open("fn1", digest)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .as("mutant: put() overwrites an existing object")
                    .isEqualTo("first");
        }
    }

    @Test
    void openOfAMissingObjectIsNotFound() {
        var store = new S3ArtifactBlobStore(client(), "my-bucket", "");
        var digest = new Digest("sha256:" + "0".repeat(64));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.open("fn1", digest))
                .isInstanceOf(ArtifactException.class)
                .extracting(e -> ((ArtifactException) e).reason())
                .isInstanceOf(ArtifactException.NotFound.class);
    }

    @Test
    void deleteAllRemovesEveryObjectUnderTheFunctionsPrefixWithAndWithoutAConfiguredPrefix() throws Exception {
        for (String prefix : new String[]{"", "fn-artifacts"}) {
            var store = new S3ArtifactBlobStore(client(), "my-bucket", prefix);
            byte[] a = "a".getBytes(StandardCharsets.UTF_8);
            byte[] b = "b".getBytes(StandardCharsets.UTF_8);
            Digest digestA = digestOf(a);
            Digest digestB = digestOf(("b-" + prefix).getBytes(StandardCharsets.UTF_8));
            Path fileA = Files.writeString(Files.createTempFile("s3-src-", ".bin"), "a", StandardCharsets.UTF_8);
            Path fileB = Files.writeString(Files.createTempFile("s3-src-", ".bin"), "b-" + prefix, StandardCharsets.UTF_8);
            store.put("fnDel", digestA, fileA);
            store.put("fnDel", digestB, fileB);
            store.put("fnOther", digestA, fileA);

            store.deleteAll("fnDel");

            assertThat(store.exists("fnDel", digestA)).as("prefix='" + prefix + "'").isFalse();
            assertThat(store.exists("fnDel", digestB)).as("prefix='" + prefix + "'").isFalse();
            assertThat(store.exists("fnOther", digestA)).as("a different function's object must survive, prefix='" + prefix + "'").isTrue();
        }
    }

    /// Reaches into the fake's own object map via a raw HEAD, independent of
    /// `S3ArtifactBlobStore#exists` — a key-layout assertion must not go
    /// through the very method whose key-building it is trying to pin.
    private boolean fakeObjectExists(String path) throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + fake.port() + path))
                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
        return resp.statusCode() == 200;
    }
}
