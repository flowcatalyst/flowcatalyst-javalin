package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.Objects;

/// A function version aggregate (spec `function-registry.md` §6.2): an
/// immutable published artifact — its content never changes once published —
/// plus its lifecycle state.
///
/// @param id                 `fnv_…` TSID
/// @param functionId         the owning function
/// @param version            1-based, unique per function
/// @param artifactRef        where the artifact lives
/// @param digest             the artifact's content digest — the immutable identity a copy is compared against (§8 M9)
/// @param signatureBundle    nullable — `null` when signatures are off (fcdev, design §8)
/// @param signatureBundleRef nullable
/// @param signer             nullable as a whole — the keyless OIDC signer identity
/// @param manifest           the parsed, frozen manifest (spec §4.6): every applicable limit is already resolved
/// @param state              `PUBLISHED` \| `READY` \| `RETIRED`
/// @param publishedBy        the publishing principal
/// @param publishedAt        creation time
public record FunctionVersion(
        String id,
        String functionId,
        int version,
        String artifactRef,
        Digest digest,
        String signatureBundle,
        String signatureBundleRef,
        SignerIdentity signer,
        Manifest manifest,
        VersionState state,
        String publishedBy,
        Instant publishedAt) implements HasId {

    public FunctionVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(functionId, "functionId");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be > 0");
        }
        Objects.requireNonNull(artifactRef, "artifactRef");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(publishedBy, "publishedBy");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    /// A version's lifecycle (spec §6.2). The stored form is the constant
    /// name plus the two timestamp columns `ready_at` / `retired_at`; a
    /// [Retired] version that was once [Ready] keeps its `ready_at` in the
    /// row — this record does not model that fact, because nothing reads it.
    public sealed interface VersionState {
        record Published() implements VersionState {
        }

        record Ready(Instant at) implements VersionState {
            public Ready {
                Objects.requireNonNull(at, "at");
            }
        }

        record Retired(Instant at) implements VersionState {
            public Retired {
                Objects.requireNonNull(at, "at");
            }
        }
    }

    /// A fresh `Published` version (spec §6.2).
    public static FunctionVersion publish(String functionId, int version, String artifactRef, Digest digest,
            String signatureBundle, String signatureBundleRef, SignerIdentity signer, Manifest manifest,
            String publishedBy, Instant now) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(artifactRef, "artifactRef");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(publishedBy, "publishedBy");
        Objects.requireNonNull(now, "now");
        return new FunctionVersion(EntityType.FUNCTION_VERSION.generate(), functionId, version, artifactRef, digest,
                signatureBundle, signatureBundleRef, signer, manifest, new VersionState.Published(), publishedBy, now);
    }

    /// `Published` ⇒ `Ready(now)`; `Ready` ⇒ unchanged, returning **this same
    /// instance** so the first `ready_at` is kept; `Retired` ⇒ unchanged.
    /// Never throws (spec §6.2): this is driven by host heartbeats, and a
    /// host still reporting a retired version for one more reconcile is
    /// routine, not an error (§8 M12).
    public FunctionVersion markReady(Instant now) {
        Objects.requireNonNull(now, "now");
        if (state instanceof VersionState.Published) {
            return withState(new VersionState.Ready(now));
        }
        return this;
    }

    /// `Published`/`Ready` ⇒ `Retired(now)`.
    ///
    /// @throws UseCaseException conflict `VERSION_ALREADY_RETIRED`
    public FunctionVersion retire(Instant now) {
        Objects.requireNonNull(now, "now");
        if (state instanceof VersionState.Retired) {
            throw UseCaseException.conflict("VERSION_ALREADY_RETIRED", "version is already retired");
        }
        return withState(new VersionState.Retired(now));
    }

    /// Not retired — a retired version is never loaded onto a fresh host.
    public boolean loadable() {
        return !(state instanceof VersionState.Retired);
    }

    private FunctionVersion withState(VersionState newState) {
        return new FunctionVersion(id, functionId, version, artifactRef, digest, signatureBundle, signatureBundleRef,
                signer, manifest, newState, publishedBy, publishedAt);
    }

    /// Masks the signature bundle to its length, never its contents
    /// (`CONVENTIONS.md` §8: carriers of key material mask `toString`).
    @Override
    public String toString() {
        return "FunctionVersion[id=" + id + ", functionId=" + functionId + ", version=" + version
                + ", artifactRef=" + artifactRef + ", digest=" + digest
                + ", signatureBundle=" + (signatureBundle == null ? "null" : signatureBundle.length() + " chars")
                + ", signatureBundleRef=" + signatureBundleRef + ", signer=" + signer + ", manifest=" + manifest
                + ", state=" + state + ", publishedBy=" + publishedBy + ", publishedAt=" + publishedAt + "]";
    }
}
