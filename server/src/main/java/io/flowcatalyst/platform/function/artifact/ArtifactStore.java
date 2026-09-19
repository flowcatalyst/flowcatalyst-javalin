package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.nio.file.Path;

/// Fetches a function version's artifact bytes by content digest (spec
/// `function-artifacts.md` §1, §2). A version's [Digest] is the sha256 of
/// the artifact file's bytes — the jar or wasm module — which is why a
/// store can fetch by it directly.
public interface ArtifactStore {

    /// Fetches the artifact `artifactRef` names, verifying it hashes to
    /// `expected`. A cached copy is re-hashed, never trusted, and replaced
    /// when it no longer matches.
    ///
    /// @throws ArtifactException the artifact could not be fetched, or its
    ///                           bytes do not hash to `expected`
    Fetched fetch(String artifactRef, Digest expected) throws ArtifactException;

    /// @param file  a regular file under the store's cache directory, named
    ///              `<cache>/sha256/<hex>`, whose bytes hash to the digest requested
    /// @param bytes the file's size
    record Fetched(Path file, long bytes) {
    }
}
