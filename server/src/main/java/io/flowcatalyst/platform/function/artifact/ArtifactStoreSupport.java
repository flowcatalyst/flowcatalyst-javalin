package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalLong;

/// Shared caching/hashing behaviour for every [ArtifactStore] (spec
/// `function-artifacts.md` §2): a second fetch of a cached digest re-hashes
/// the cached file rather than trusting it; a fetch streams through a
/// running digest into a temp file in the cache directory and `ATOMIC_MOVE`s
/// it into place only once the digest matches — nothing with the wrong bytes
/// ever exists under a digest's name, even briefly. [FileArtifactStore] and
/// [OciArtifactStore] differ only in [#open]. `public` (constructor and
/// [#open] `protected`) so `function-host`'s `PlatformArtifactStore` (spec
/// `function-artifact-upload.md` §5) can extend it from another module and
/// reuse this SAME cache/hash/cap machinery — the widening is additive only,
/// no behaviour here changed.
public abstract class ArtifactStoreSupport implements ArtifactStore {

    private static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;

    private final Path cacheDir;
    private final long maxBytes;

    protected ArtifactStoreSupport(Path cacheDir, long maxBytes) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        try {
            Files.createDirectories(cacheDir.resolve("sha256"));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot create cache directory " + cacheDir, e);
        }
    }

    protected static long defaultMaxBytes() {
        return DEFAULT_MAX_BYTES;
    }

    /// Opens the source named by `ref`, addressed by `expected` where the
    /// scheme needs the digest to locate the bytes (`oci://`'s blob path).
    /// `versionId` is the three-arg [ArtifactStore#fetch]'s own addition
    /// (spec `function-artifact-upload.md` §5) — `null` on every two-arg
    /// call; [FileArtifactStore] and [OciArtifactStore] both ignore it,
    /// `PlatformArtifactStore` requires it (the download route is keyed by
    /// version id, not by ref).
    protected abstract SourceStream open(String ref, Digest expected, String versionId) throws ArtifactException;

    @Override
    public final Fetched fetch(String artifactRef, Digest expected) throws ArtifactException {
        return fetch(artifactRef, expected, null);
    }

    /// [ArtifactStore#fetch]'s three-arg widening (spec §5) — same cache
    /// check, same [#downloadAndCache] as the two-arg form, `versionId`
    /// threaded through to [#open] for a store that needs it.
    public final Fetched fetch(String artifactRef, Digest expected, String versionId) throws ArtifactException {
        Objects.requireNonNull(artifactRef, "artifactRef");
        Objects.requireNonNull(expected, "expected");
        Path cached = cachePath(expected);
        if (Files.isRegularFile(cached)) {
            Digest actual = hashOf(cached);
            if (actual.equals(expected)) {
                return new Fetched(cached, sizeOf(cached));
            }
            // a poisoned cache entry is detected here, by re-hashing, and replaced — never trusted on sight
            deleteQuietly(cached);
        }
        return downloadAndCache(artifactRef, expected, versionId, cached);
    }

    private Fetched downloadAndCache(String ref, Digest expected, String versionId, Path target) throws ArtifactException {
        try (SourceStream source = open(ref, expected, versionId)) {
            if (source.contentLength().isPresent() && source.contentLength().getAsLong() > maxBytes) {
                throw new ArtifactException(new ArtifactException.TooLarge(maxBytes));
            }
            Path temp = Files.createTempFile(cacheDir, "artifact-", ".tmp");
            boolean moved = false;
            try {
                long count = writeVerified(source.body(), temp, expected);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
                return new Fetched(target, count);
            } finally {
                if (!moved) {
                    deleteQuietly(temp);
                }
            }
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    /// Streams `in` through a running SHA-256 into `temp`, aborting as soon
    /// as the byte count passes `maxBytes` — checked on every chunk, not
    /// only against a declared `Content-Length`, so a source that would
    /// stream for ever is still cut off. Returns the byte count when the
    /// digest matches `expected`, having written nothing the caller moves
    /// into place otherwise: the caller only calls `Files.move` after this
    /// method returns normally.
    private long writeVerified(InputStream in, Path temp, Digest expected) throws ArtifactException, IOException {
        MessageDigest sha256 = sha256();
        long count = 0;
        try (var digestIn = new DigestInputStream(in, sha256);
             OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = digestIn.read(buffer)) != -1) {
                count += n;
                if (count > maxBytes) {
                    throw new ArtifactException(new ArtifactException.TooLarge(maxBytes));
                }
                out.write(buffer, 0, n);
            }
        }
        Digest actual = new Digest("sha256:" + HexFormat.of().formatHex(sha256.digest()));
        if (!actual.equals(expected)) {
            throw new ArtifactException(new ArtifactException.DigestMismatch(expected, actual));
        }
        return count;
    }

    private Digest hashOf(Path file) throws ArtifactException {
        try {
            MessageDigest sha256 = sha256();
            try (var in = Files.newInputStream(file); var digestIn = new DigestInputStream(in, sha256)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (digestIn.read(buffer) != -1) {
                    // read only for the digest side effect
                }
            }
            return new Digest("sha256:" + HexFormat.of().formatHex(sha256.digest()));
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private long sizeOf(Path file) throws ArtifactException {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private Path cachePath(Digest digest) {
        return cacheDir.resolve("sha256").resolve(digest.value().substring("sha256:".length()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
            // best-effort cleanup; a leftover temp file is harmless and never served
        }
    }

    /// A fetch source: the byte stream and, when the transport declared one,
    /// its length — checked against `maxBytes` before a single byte is read.
    public record SourceStream(InputStream body, OptionalLong contentLength) implements AutoCloseable {
        public SourceStream {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(contentLength, "contentLength");
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
