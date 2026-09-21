package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;

/// `FC_FN_ARTIFACT_STORE=file:///abs/dir` (spec `function-artifact-upload.md`
/// §2): layout `<dir>/<functionId>/<hex>`; a temp file in the SAME directory
/// is moved into place with `ATOMIC_MOVE` — a reader never sees a partial
/// blob, the same discipline [ArtifactStoreSupport] uses on the fetch side.
public final class FileArtifactBlobStore implements ArtifactBlobStore {

    private final Path dir;

    public FileArtifactBlobStore(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot create artifact directory " + dir, e);
        }
    }

    @Override
    public void put(String functionId, Digest digest, Path file) throws ArtifactException {
        Path target = pathOf(functionId, digest);
        // Idempotent (spec §2): an existing blob is left exactly as is.
        if (Files.isRegularFile(target)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
            try {
                Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    @Override
    public boolean exists(String functionId, Digest digest) throws ArtifactException {
        return Files.isRegularFile(pathOf(functionId, digest));
    }

    @Override
    public InputStream open(String functionId, Digest digest) throws ArtifactException {
        Path p = pathOf(functionId, digest);
        if (!Files.isRegularFile(p)) {
            throw new ArtifactException(new ArtifactException.NotFound());
        }
        try {
            return Files.newInputStream(p);
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    @Override
    public long size(String functionId, Digest digest) throws ArtifactException {
        Path p = pathOf(functionId, digest);
        try {
            return Files.size(p);
        } catch (NoSuchFileException e) {
            throw new ArtifactException(new ArtifactException.NotFound());
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    @Override
    public void deleteAll(String functionId) throws ArtifactException {
        Path functionDir = dir.resolve(ArtifactBlobKeys.validateFunctionId(functionId));
        if (!Files.isDirectory(functionDir)) {
            return;
        }
        try (var files = Files.walk(functionDir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private Path pathOf(String functionId, Digest digest) throws ArtifactException {
        ArtifactBlobKeys.Key k = ArtifactBlobKeys.of(functionId, digest);
        return dir.resolve(k.functionId()).resolve(k.hex());
    }
}
