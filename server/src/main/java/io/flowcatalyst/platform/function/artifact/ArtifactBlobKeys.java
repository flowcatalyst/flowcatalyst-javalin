package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.util.regex.Pattern;

/// Validates `functionId`/`hex` **inside the store** (spec
/// `function-artifact-upload.md` §2: "`functionId` and `hex` are validated
/// ... in the store, not only by callers: they become path segments and
/// object keys"). A [Digest]'s canonical constructor only null-checks its
/// `value` — [Digest#parse] is what enforces the `sha256:`+64-hex shape, and
/// nothing stops a caller from handing a store a [Digest] built directly —
/// so [FileArtifactBlobStore] and [S3ArtifactBlobStore] both re-check here
/// rather than trust an already-parsed type.
final class ArtifactBlobKeys {

    private static final Pattern FUNCTION_ID = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final String SHA256_PREFIX = "sha256:";

    private ArtifactBlobKeys() {
    }

    /// A validated `(functionId, hex)` pair, ready to become a path segment
    /// pair or an object-key suffix.
    record Key(String functionId, String hex) {
    }

    /// @throws ArtifactException `BadRef` when `functionId` is not
    ///                            `^[A-Za-z0-9_]+$`
    static String validateFunctionId(String functionId) throws ArtifactException {
        if (functionId == null || !FUNCTION_ID.matcher(functionId).matches()) {
            throw new ArtifactException(new ArtifactException.BadRef("functionId must match ^[A-Za-z0-9_]+$"));
        }
        return functionId;
    }

    /// @throws ArtifactException `BadRef` when `functionId` or `digest` is malformed
    static Key of(String functionId, Digest digest) throws ArtifactException {
        validateFunctionId(functionId);
        if (digest == null || !digest.value().startsWith(SHA256_PREFIX)) {
            throw new ArtifactException(new ArtifactException.BadRef("digest must be a sha256: digest"));
        }
        String hex = digest.value().substring(SHA256_PREFIX.length());
        if (!HEX.matcher(hex).matches()) {
            throw new ArtifactException(
                    new ArtifactException.BadRef("digest must be sha256: followed by 64 lower-case hex characters"));
        }
        return new Key(functionId, hex);
    }
}
