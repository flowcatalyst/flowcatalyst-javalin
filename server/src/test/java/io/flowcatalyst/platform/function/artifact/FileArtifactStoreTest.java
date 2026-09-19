package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Exercises [ArtifactStoreSupport]'s cache/hash machinery (spec
/// `function-artifacts.md` §2) through [FileArtifactStore], the simplest
/// [ArtifactStore] — no network involved.
class FileArtifactStoreTest {

    @Test
    void fetchesAndCachesByDigest(@TempDir Path cacheDir, @TempDir Path sourceDir) throws Exception {
        Path source = sourceDir.resolve("fn.jar");
        Files.writeString(source, "hello artifact");
        Digest digest = digestOf("hello artifact");
        var store = new FileArtifactStore(cacheDir);

        ArtifactStore.Fetched fetched = store.fetch("file://" + source, digest);

        assertThat(Files.readString(fetched.file())).isEqualTo("hello artifact");
        assertThat(fetched.bytes()).isEqualTo(14);
        assertThat(fetched.file()).isEqualTo(cacheDir.resolve("sha256").resolve(digest.value().substring(7)));
    }

    /// C1: fetched bytes are hashed; a wrong-digest source leaves **no
    /// file** under the digest's name and no temp file anywhere in the
    /// cache directory.
    @Test
    void wrongDigestSourceLeavesNoFileBehind(@TempDir Path cacheDir, @TempDir Path sourceDir) throws Exception {
        Path source = sourceDir.resolve("fn.jar");
        Files.writeString(source, "actual bytes");
        Digest claimedDigest = digestOf("bytes nobody wrote"); // does not match the source's real content
        var store = new FileArtifactStore(cacheDir);

        assertThatThrownBy(() -> store.fetch("file://" + source, claimedDigest))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason()).isInstanceOf(ArtifactException.DigestMismatch.class));

        assertThat(cacheDir.resolve("sha256").resolve(claimedDigest.value().substring(7))).doesNotExist();
        try (Stream<Path> entries = Files.walk(cacheDir)) {
            List<Path> files = entries.filter(Files::isRegularFile).toList();
            assertThat(files).as("no temp file (or any file) should remain in the cache directory").isEmpty();
        }
    }

    /// C2: a poisoned cache entry is detected — by re-hashing, not by
    /// trusting the name on disk — and replaced with the real bytes.
    @Test
    void poisonedCacheEntryIsReplacedWithRealBytes(@TempDir Path cacheDir, @TempDir Path sourceDir) throws Exception {
        Path source = sourceDir.resolve("fn.jar");
        Files.writeString(source, "the real content");
        Digest digest = digestOf("the real content");

        Path sha256Dir = Files.createDirectories(cacheDir.resolve("sha256"));
        Path cachedFile = sha256Dir.resolve(digest.value().substring(7));
        Files.writeString(cachedFile, "poisoned garbage that happens to share this digest's filename");

        var store = new FileArtifactStore(cacheDir);
        ArtifactStore.Fetched fetched = store.fetch("file://" + source, digest);

        // the load-bearing assertion: the returned file's CONTENT is the real bytes,
        // not the poisoned bytes a "trust the cache" implementation would have returned.
        assertThat(Files.readString(fetched.file())).isEqualTo("the real content");
    }

    @Test
    void relativePathIsBadRef(@TempDir Path cacheDir) {
        var store = new FileArtifactStore(cacheDir);
        assertThatThrownBy(() -> store.fetch("file:relative/path.jar", digestOf("x")))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason()).isInstanceOf(ArtifactException.BadRef.class));
    }

    @Test
    void hostComponentIsBadRef(@TempDir Path cacheDir) {
        var store = new FileArtifactStore(cacheDir);
        assertThatThrownBy(() -> store.fetch("file://somehost/abs/path.jar", digestOf("x")))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason()).isInstanceOf(ArtifactException.BadRef.class));
    }

    @Test
    void missingFileIsNotFound(@TempDir Path cacheDir, @TempDir Path sourceDir) {
        var store = new FileArtifactStore(cacheDir);
        Path missing = sourceDir.resolve("nope.jar");
        assertThatThrownBy(() -> store.fetch("file://" + missing, digestOf("x")))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason()).isInstanceOf(ArtifactException.NotFound.class));
    }

    static Digest digestOf(String content) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        return new Digest("sha256:" + HexFormat.of().formatHex(hash));
    }
}
