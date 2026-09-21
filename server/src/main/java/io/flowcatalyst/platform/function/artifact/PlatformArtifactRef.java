package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.util.Objects;
import java.util.Optional;

/// The `platform://<functionId>/<hex>` artifact reference (spec
/// `function-artifact-upload.md` §1): names *what* was uploaded, scoped by
/// function — never a global content-addressed namespace (a global one
/// would let a tenant publish a digest another tenant uploaded, and turn
/// the upload route into an existence oracle). `PublishVersion`'s ref-match
/// check and `FunctionControlApi`'s download route both parse through this
/// one type rather than re-deriving the shape.
///
/// @param functionId the function the artifact was uploaded under
/// @param hex         the 64-char lower-case sha256 hex
public record PlatformArtifactRef(String functionId, String hex) {

    private static final String PREFIX = "platform://";

    public PlatformArtifactRef {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(hex, "hex");
    }

    /// `Optional.empty()` for anything not shaped like `platform://x/y` —
    /// including an empty function id or hex, or extra path segments.
    /// `functionId`/`hex` are NOT format-validated here (that is the
    /// store's job, spec §2 — every `ArtifactBlobStore` call re-checks); this
    /// parser only recognises the *shape*.
    public static Optional<PlatformArtifactRef> parse(String ref) {
        if (ref == null || !ref.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = ref.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return Optional.empty();
        }
        String functionId = rest.substring(0, slash);
        String hex = rest.substring(slash + 1);
        if (hex.indexOf('/') >= 0) {
            return Optional.empty();
        }
        return Optional.of(new PlatformArtifactRef(functionId, hex));
    }

    public String render() {
        return PREFIX + functionId + "/" + hex;
    }

    public Digest digest() {
        return new Digest("sha256:" + hex);
    }
}
