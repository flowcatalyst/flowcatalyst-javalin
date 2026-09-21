package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FileArtifactBlobStore` (spec `function-artifact-upload.md` §2): layout,
/// idempotent `put`, and — U9 — that `functionId`/hex validation lives in
/// the STORE, not only in a caller that already parsed a [Digest]. A
/// [Digest]'s canonical constructor only null-checks `value` (no format
/// check — that is [Digest#parse]'s job), so a test can hand the store a
/// digest a real caller never could.
class FileArtifactBlobStoreTest {

    @TempDir
    Path dir;

    private FileArtifactBlobStore store(Path base) {
        return new FileArtifactBlobStore(base);
    }

    private static Digest digest(String hex) {
        return new Digest("sha256:" + hex);
    }

    private static final String HEX_A = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);

    @Test
    void putThenOpenThenSizeRoundTrip() throws Exception {
        var store = store(dir);
        Path file = Files.writeString(dir.resolve("src.bin"), "hello world", StandardCharsets.UTF_8);
        store.put("fn1", digest(HEX_A), file);

        assertThat(store.exists("fn1", digest(HEX_A))).isTrue();
        assertThat(store.size("fn1", digest(HEX_A))).isEqualTo(Files.size(file));
        try (var in = store.open("fn1", digest(HEX_A))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
        }

        // layout: <dir>/<functionId>/<hex>
        assertThat(dir.resolve("fn1").resolve(HEX_A)).isRegularFile();
    }

    @Test
    void putIsIdempotentAnExistingBlobIsLeftAsIs() throws Exception {
        var store = store(dir);
        Path first = Files.writeString(dir.resolve("first.bin"), "first", StandardCharsets.UTF_8);
        Path second = Files.writeString(dir.resolve("second.bin"), "second-but-never-written", StandardCharsets.UTF_8);
        store.put("fn1", digest(HEX_A), first);
        store.put("fn1", digest(HEX_A), second);

        try (var in = store.open("fn1", digest(HEX_A))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .as("mutant: put() overwrites an existing blob")
                    .isEqualTo("first");
        }
    }

    @Test
    void openOfAnAbsentBlobIsNotFound() throws Exception {
        var store = store(dir);
        assertThatThrownBy(() -> store.open("fn1", digest(HEX_A)))
                .isInstanceOf(ArtifactException.class)
                .extracting(e -> ((ArtifactException) e).reason())
                .isInstanceOf(ArtifactException.NotFound.class);
        assertThat(store.exists("fn1", digest(HEX_A))).isFalse();
    }

    @Test
    void deleteAllRemovesEveryBlobOfTheFunctionAndLeavesOthersAlone() throws Exception {
        var store = store(dir);
        Path a = Files.writeString(dir.resolve("a.bin"), "a", StandardCharsets.UTF_8);
        Path b = Files.writeString(dir.resolve("b.bin"), "b", StandardCharsets.UTF_8);
        store.put("fn1", digest(HEX_A), a);
        store.put("fn1", digest(HEX_B), b);
        store.put("fn2", digest(HEX_A), a);

        store.deleteAll("fn1");

        assertThat(store.exists("fn1", digest(HEX_A))).isFalse();
        assertThat(store.exists("fn1", digest(HEX_B))).isFalse();
        assertThat(store.exists("fn2", digest(HEX_A))).as("a different function's blob must survive").isTrue();
    }

    @Test
    void deleteAllOfAFunctionWithNoBlobsIsANoOp() {
        var store = store(dir);
        assertThatCode(() -> store.deleteAll("neverUploaded")).doesNotThrowAnyException();
    }

    // ── U9: the store validates functionId/hex itself ────────────────────

    @Test
    void putRejectsAPathTraversalFunctionId() throws Exception {
        var store = store(dir);
        Path file = Files.writeString(dir.resolve("src.bin"), "x", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.put("../x", digest(HEX_A), file))
                .as("mutant: validate in the route only")
                .isInstanceOf(ArtifactException.class)
                .extracting(e -> ((ArtifactException) e).reason())
                .isInstanceOf(ArtifactException.BadRef.class);
    }

    @Test
    void existsRejectsAShortHexNeverReachingTheFilesystem() {
        var store = store(dir);
        String shortHex = "a".repeat(63);
        assertThatThrownBy(() -> store.exists("fn1", new Digest("sha256:" + shortHex)))
                .as("mutant: validate in the route only")
                .isInstanceOf(ArtifactException.class)
                .extracting(e -> ((ArtifactException) e).reason())
                .isInstanceOf(ArtifactException.BadRef.class);
    }
}
