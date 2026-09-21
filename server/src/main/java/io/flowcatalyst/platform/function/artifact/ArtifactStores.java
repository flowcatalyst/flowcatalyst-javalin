package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;

/// Routes a fetch to the store for its reference's scheme (spec
/// `function-artifacts.md` §2): `file://` and `oci://` are wired in this
/// package; `s3://` has no S3 SDK module on the classpath yet — the design
/// calls it a fallback — so it stays `UnsupportedScheme` until package D
/// needs it, which is the entire point of routing through this type rather
/// than the host picking a store itself. `extra` (spec
/// `function-artifact-upload.md` §5) is a small, additive widening: a caller
/// outside this package (`function-host`'s `PlatformArtifactStore`, scheme
/// `platform`) is registered without this class ever depending on it.
public final class ArtifactStores implements ArtifactStore {

    private final FileArtifactStore file;
    private final OciArtifactStore oci;
    private final Map<String, ArtifactStore> extra;

    public ArtifactStores(FileArtifactStore file, OciArtifactStore oci) {
        this(file, oci, Map.of());
    }

    /// @param extra additional scheme → store entries, checked after `file`/`oci`
    ///              — never a scheme either of those already owns (spec §5:
    ///              `PlatformArtifactStore` registers `"platform"`)
    public ArtifactStores(FileArtifactStore file, OciArtifactStore oci, Map<String, ArtifactStore> extra) {
        this.file = Objects.requireNonNull(file, "file");
        this.oci = Objects.requireNonNull(oci, "oci");
        this.extra = Map.copyOf(Objects.requireNonNull(extra, "extra"));
    }

    @Override
    public Fetched fetch(String artifactRef, Digest expected) throws ArtifactException {
        return forRef(artifactRef).fetch(artifactRef, expected);
    }

    @Override
    public Fetched fetch(String artifactRef, Digest expected, String versionId) throws ArtifactException {
        return forRef(artifactRef).fetch(artifactRef, expected, versionId);
    }

    /// The store that owns `ref`'s scheme.
    ///
    /// @throws ArtifactException `UnsupportedScheme` for `s3://` or anything
    ///                           unrecognised, `BadRef` for no scheme at all
    public ArtifactStore forRef(String ref) throws ArtifactException {
        String scheme = schemeOf(ref);
        return switch (scheme) {
            case "file" -> file;
            case "oci" -> oci;
            default -> {
                ArtifactStore fromExtra = extra.get(scheme);
                if (fromExtra == null) {
                    throw new ArtifactException(new ArtifactException.UnsupportedScheme(scheme));
                }
                yield fromExtra;
            }
        };
    }

    private static String schemeOf(String ref) throws ArtifactException {
        try {
            String scheme = new URI(ref).getScheme();
            if (scheme == null) {
                throw new ArtifactException(new ArtifactException.BadRef("artifact reference has no scheme"));
            }
            return scheme;
        } catch (URISyntaxException e) {
            throw new ArtifactException(new ArtifactException.BadRef("malformed URI: " + e.getMessage()));
        }
    }
}
