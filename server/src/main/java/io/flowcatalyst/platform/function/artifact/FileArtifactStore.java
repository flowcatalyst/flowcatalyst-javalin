package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/// Fetches an artifact from `file:///abs/path/fn.jar` (spec
/// `function-artifacts.md` §2.1). Relative paths, a host component, and a
/// path that is not a regular readable file are rejected before the shared
/// cache/hash machinery in [ArtifactStoreSupport] ever runs — the source may
/// change under us, so it is always copied into the cache like any other
/// store rather than read in place.
public final class FileArtifactStore extends ArtifactStoreSupport {

    public FileArtifactStore(Path cacheDir) {
        this(cacheDir, defaultMaxBytes());
    }

    public FileArtifactStore(Path cacheDir, long maxBytes) {
        super(cacheDir, maxBytes);
    }

    @Override
    protected SourceStream open(String ref, Digest expected, String versionId) throws ArtifactException {
        // versionId (ArtifactStoreSupport's three-arg fetch widening, spec §5) is
        // PlatformArtifactStore's own concern — a file:// ref already names the exact
        // path, so this store never needs it.
        Path source = resolve(ref);
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new ArtifactException(new ArtifactException.NotFound());
        }
        try {
            long size = Files.size(source);
            return new SourceStream(Files.newInputStream(source), OptionalLong.of(size));
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private static Path resolve(String ref) throws ArtifactException {
        URI uri;
        try {
            uri = new URI(ref);
        } catch (URISyntaxException e) {
            throw new ArtifactException(new ArtifactException.BadRef("malformed URI: " + e.getMessage()));
        }
        if (!"file".equals(uri.getScheme())) {
            throw new ArtifactException(new ArtifactException.BadRef("not a file:// reference"));
        }
        if (uri.getHost() != null && !uri.getHost().isEmpty()) {
            throw new ArtifactException(new ArtifactException.BadRef("file:// references must not carry a host"));
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) {
            throw new ArtifactException(new ArtifactException.BadRef("file:// references must be absolute"));
        }
        return Path.of(path);
    }
}
