package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/// Routes a fetch to the store for its reference's scheme (spec
/// `function-artifacts.md` §2): `file://` and `oci://` are wired in this
/// package; `s3://` has no S3 SDK module on the classpath yet — the design
/// calls it a fallback — so it stays `UnsupportedScheme` until package D
/// needs it, which is the entire point of routing through this type rather
/// than the host picking a store itself.
public final class ArtifactStores implements ArtifactStore {

    private final FileArtifactStore file;
    private final OciArtifactStore oci;

    public ArtifactStores(FileArtifactStore file, OciArtifactStore oci) {
        this.file = Objects.requireNonNull(file, "file");
        this.oci = Objects.requireNonNull(oci, "oci");
    }

    @Override
    public Fetched fetch(String artifactRef, Digest expected) throws ArtifactException {
        return forRef(artifactRef).fetch(artifactRef, expected);
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
            default -> throw new ArtifactException(new ArtifactException.UnsupportedScheme(scheme));
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
