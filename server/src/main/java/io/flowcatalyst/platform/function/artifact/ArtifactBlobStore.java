package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.io.InputStream;
import java.nio.file.Path;

/// The platform's write side for an uploaded function artifact (spec
/// `function-artifact-upload.md` §2) — distinct from [ArtifactStore]
/// (`function-artifacts.md` §2), which only ever *reads* an artifact a
/// developer points at by reference. Two implementations, chosen at the
/// composition root by `FC_FN_ARTIFACT_STORE` ([ArtifactBlobStores#configure]):
/// [FileArtifactBlobStore] (`file://`) and [S3ArtifactBlobStore] (`s3://`).
public interface ArtifactBlobStore {

    /// The same 256 MiB cap [ArtifactStoreSupport] enforces on the fetch
    /// side (spec §3: "the same constant the fetch side uses") — one
    /// constant, never two copies that could drift apart.
    long MAX_BYTES = ArtifactStoreSupport.defaultMaxBytes();

    /// Stores `file` (already hashed to `digest` by the caller) under
    /// `(functionId, digest)`. Idempotent: an existing blob is left as is.
    void put(String functionId, Digest digest, Path file) throws ArtifactException;

    boolean exists(String functionId, Digest digest) throws ArtifactException;

    /// The blob's bytes; the caller closes. `NotFound` when absent.
    InputStream open(String functionId, Digest digest) throws ArtifactException;

    long size(String functionId, Digest digest) throws ArtifactException;

    /// Every blob of a function. Best-effort callers only (spec §5:
    /// "a failure is a WARN, never a failed [function] delete").
    void deleteAll(String functionId) throws ArtifactException;
}
