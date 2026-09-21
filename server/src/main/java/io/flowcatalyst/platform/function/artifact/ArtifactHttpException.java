package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.util.Objects;

/// A function-artifact error whose HTTP status falls outside
/// [io.flowcatalyst.sdk.usecase.UseCaseError]'s fixed 400/401/403/404/409/500
/// set (spec `function-artifact-upload.md` §3 upload, §4 download, and
/// `PublishVersion`'s own `platform://` checks): 413 the artifact exceeds
/// the cap, 422 a syntactically valid request whose bytes or reference
/// cannot be processed, 503 no store is configured. Thrown from a handler
/// OR an operation alike —
/// [io.flowcatalyst.platform.shared.httperror.HttpError#install] renders it
/// in the platform's ordinary `{error, message}` envelope, the
/// `CorruptRowException`/`LoginSurfaceException` model of a domain
/// exception the transport layer knows how to render: this type is owned by
/// the artifact package, never `HttpError` itself, so `operations/` code
/// (`PublishVersion`) can throw it without importing the handler-layer
/// `HttpError` (`CONVENTIONS.md` §8's "`HttpError` constructors are
/// handler-layer only").
public final class ArtifactHttpException extends RuntimeException {

    private final int status;
    private final String code;

    public ArtifactHttpException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = Objects.requireNonNull(code, "code");
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /// Spec §2/§3/§4: no [ArtifactBlobStore] is configured — the upload
    /// route, a `platform://` publish, and the download route all answer
    /// this the same way.
    public static ArtifactHttpException storeNotConfigured() {
        return new ArtifactHttpException(503, "ARTIFACT_STORE_NOT_CONFIGURED",
                "no function-artifact store is configured (FC_FN_ARTIFACT_STORE is unset)");
    }

    /// Spec §3: a declared `Content-Length` over the cap, or the running
    /// count passing it mid-stream.
    public static ArtifactHttpException tooLarge(long limitBytes) {
        return new ArtifactHttpException(413, "ARTIFACT_TOO_LARGE",
                "artifact exceeds the " + limitBytes + "-byte limit");
    }

    /// Spec §3: the uploaded body was zero bytes.
    public static ArtifactHttpException empty() {
        return new ArtifactHttpException(422, "ARTIFACT_EMPTY", "the uploaded artifact is empty");
    }

    /// Spec §3: the bytes received do not hash to the `{digest}` path segment.
    public static ArtifactHttpException digestMismatch(Digest expected, Digest actual) {
        return new ArtifactHttpException(422, "DIGEST_MISMATCH",
                "digest mismatch: expected " + expected.value() + " but got " + actual.value());
    }

    /// Spec §1: a `platform://` artifactRef names another function, or a
    /// hex that does not match the publish command's own digest.
    public static ArtifactHttpException refMismatch() {
        return new ArtifactHttpException(422, "ARTIFACT_REF_MISMATCH",
                "platform:// artifactRef must name this function and the published digest");
    }

    /// Spec §1: a `platform://` ref that names the right function and
    /// digest, but nothing was ever uploaded for it.
    public static ArtifactHttpException notUploaded() {
        return new ArtifactHttpException(422, "ARTIFACT_NOT_UPLOADED",
                "no artifact has been uploaded for this function at this digest yet");
    }
}
