package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.ClientPolicy;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.function.artifact.ArtifactBlobStore;
import io.flowcatalyst.platform.function.artifact.ArtifactException;
import io.flowcatalyst.platform.function.artifact.ArtifactHttpException;
import io.flowcatalyst.platform.function.artifact.PlatformArtifactRef;
import io.flowcatalyst.platform.function.artifact.SignatureVerifier;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.artifact.Verification;
import io.flowcatalyst.platform.function.operations.FunctionEvents.VersionPublished;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/// Publishes a new version of an existing function (spec `function-api.md`
/// §5.1). A [TxOperation] — not the single-aggregate [Operation] every other
/// operation in this package uses — because [FunctionVersionRepository#nextVersion]
/// must run under the function's row lock in the SAME transaction the new
/// version is written in (spec §8 P11): two concurrent publishes of
/// different digests must serialise to versions `n` and `n+1`, never race to
/// the same number.
///
/// `Authorize: Public` — load-or-404 + reach is [Access#byAddress], run in
/// `execute`, same as every other by-address operation in this package.
///
/// Spec §5.1's eight steps, in order, ALL inside [#execute] (only the
/// address-shaped/digest-shaped/artifactRef-shaped checks that need no
/// database read live in `validate`, mirroring `CreateFunction`'s split):
///
/// 1. load + reach + `FUNCTION_DISABLED`
/// 2. `artifactRef`/`digest` shape (`validate`, re-checked here for the
///    parsed [Digest] `execute` needs)
/// 3. the owner's policy → ceilings (absent row ⇒ the platform defaults)
/// 4. `Manifest.parseStrict`
/// 5. signature: [Signatures.Off] stores the bundle unverified with a `null`
///    signer; [Signatures.Required] demands a bundle, `SIGNATURE_REJECTED`
///    with `details.reason` on a failed verify, `SIGNER_NOT_PERMITTED`
///    (naming issuer/subject verbatim) when the owner's policy — ABSENT
///    policy included — does not permit that signer for this runtime
/// 6. same digest already published ⇒ `VERSION_DIGEST_EXISTS` naming the
///    existing version, checked BEFORE the insert so it is a 409 here, not a
///    unique-violation 500 from `fn_versions_function_id_digest_key`
/// 7. `nextVersion` under the row lock → `FunctionVersion.publish` → commit
/// 8. `TriggerSync.onPublish`, inside the SAME open transaction — a throw
///    here rolls back steps 6/7 too (spec §8 P7): no version row, no event,
///    and the NEXT publish still gets the version this one would have taken
public final class PublishVersion {

    private static final Pattern ARTIFACT_REF = Pattern.compile("^(oci|file|s3|platform)://.+");

    private PublishVersion() {
    }

    /// @param version the freshly published, committed version
    /// @param event   the `version:published` event that was written with it
    public record Result(FunctionVersion version, VersionPublished event) {
        public Result {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(event, "event");
        }
    }

    public static TxOperation<PublishCommand, Result> of(FunctionRepository functions, FunctionVersionRepository versions,
            ClientPolicyRepository policies, FunctionLimits defaults, Signatures signatures, TriggerSync triggerSync,
            Optional<ArtifactBlobStore> artifactStore) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(signatures, "signatures");
        Objects.requireNonNull(triggerSync, "triggerSync");
        Objects.requireNonNull(artifactStore, "artifactStore");
        return TxOperation.<PublishCommand, Result>named("PublishVersion")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.artifactRef(), "ARTIFACT_REF_REQUIRED", "artifactRef is required");
                    validateArtifactRef(cmd.artifactRef());
                    Digest.parse(cmd.digest());
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    AuthContext ac = Auth.current();
                    // Step 1: load + reach + FUNCTION_DISABLED.
                    Function f = Access.byAddress(functions, cmd.address(), ac);
                    if (f.status() == FunctionStatus.DISABLED) {
                        throw UseCaseException.conflict("FUNCTION_DISABLED", "function is disabled");
                    }

                    // Step 2 (again — validate already ran, but execute needs the parsed value).
                    validateArtifactRef(cmd.artifactRef());
                    Digest digest = Digest.parse(cmd.digest());

                    // Step 2b (spec `function-artifact-upload.md` §1): a platform:// ref must
                    // name THIS function and the command's own digest, and must already exist
                    // in the store — checked before anything else touches the database, so a
                    // bogus or missing upload never reaches the version row.
                    checkPlatformRef(artifactStore, cmd.artifactRef(), f.id(), digest);

                    // Step 3: the owner's policy → ceilings. Absent row ⇒ the platform defaults.
                    Optional<ClientPolicy> policy = policies.findByOwner(f.owner());
                    ClientCeilings ceilings = policy.map(p -> p.ceilings(defaults))
                            .orElseGet(() -> ClientCeilings.of(defaults));

                    // Step 4.
                    Manifest manifest = Manifest.parseStrict(cmd.manifest(), f.runtime(), defaults, ceilings);

                    // Step 5: signature.
                    SignerIdentity signer = resolveSigner(signatures, cmd.signatureBundle(), digest, policy, f.runtime());

                    // Step 6: duplicate digest, checked before the insert (spec §5.1 step 6).
                    Optional<FunctionVersion> duplicate = versions.findByFunctionAndDigest(f.id(), digest);
                    if (duplicate.isPresent()) {
                        throw new UseCaseException(UseCaseError.conflict("VERSION_DIGEST_EXISTS",
                                        "digest is already published as version " + duplicate.get().version()
                                                + " for this function")
                                // `details.version` lets a caller (fcdev `fn deploy`) recover the
                                // existing version number without parsing the message's prose.
                                .withDetails(Map.of("version", duplicate.get().version())));
                    }

                    // Step 7: nextVersion under the row lock, in this same transaction (spec §8 P11).
                    int nextVersion = versions.nextVersion(f.id(), scoped.dbTx());
                    FunctionVersion v = FunctionVersion.publish(f.id(), nextVersion, cmd.artifactRef(), digest,
                            cmd.signatureBundle(), null, signer, manifest, ec.principalId(), ec.initiatedAt());
                    VersionPublished event = VersionPublished.of(ec, f, v);
                    scoped.commit(v, versions, event, cmd);

                    // Step 8: the trigger seam, same open transaction — a throw here rolls everything back.
                    triggerSync.onPublish(scoped, f, v);

                    return new Result(v, event);
                });
    }

    /// Spec §5.1 step 5. `Off` stores whatever bundle was sent (or none),
    /// signer always `null`. `Required` demands a bundle, verifies it, then
    /// checks the OWNER's policy permits that signer for `runtime` — an
    /// ABSENT policy row permits nothing (never "permit all"), which is why
    /// this takes the raw `Optional<ClientPolicy>` rather than a
    /// pre-resolved policy.
    private static SignerIdentity resolveSigner(Signatures signatures, String bundle, Digest digest,
            Optional<ClientPolicy> policy, Runtime runtime) {
        return switch (signatures) {
            case Signatures.Off ignored -> null;
            case Signatures.Required(SignatureVerifier verifier) -> verifyAndAuthorize(verifier, bundle, digest, policy, runtime);
        };
    }

    private static SignerIdentity verifyAndAuthorize(SignatureVerifier verifier, String bundle, Digest digest,
            Optional<ClientPolicy> policy, Runtime runtime) {
        if (bundle == null || bundle.isBlank()) {
            throw UseCaseException.validation("SIGNATURE_REQUIRED", "a signature bundle is required to publish");
        }
        Verification verification = verifier.verify(bundle, digest);
        return switch (verification) {
            case Verification.Rejected rejected -> throw new UseCaseException(
                    UseCaseError.validation("SIGNATURE_REJECTED", "signature rejected: " + rejected.detail())
                            .withDetails(Map.of("reason", rejected.reason().name())));
            case Verification.Verified verified -> {
                SignerIdentity signer = verified.signer();
                // ABSENT policy row permits nothing — this is `Optional.map`, never `orElseGet`
                // to some permit-all default (spec §8 P8's "missing policy = permit-all" mutant).
                boolean permitted = policy.map(p -> p.permits(signer, runtime)).orElse(false);
                if (!permitted) {
                    throw UseCaseException.authorization("SIGNER_NOT_PERMITTED",
                            "signer not permitted to publish: issuer='" + signer.issuer() + "' subject='"
                                    + signer.subject() + "'");
                }
                yield signer;
            }
        };
    }

    private static void validateArtifactRef(String artifactRef) {
        if (artifactRef != null && !ARTIFACT_REF.matcher(artifactRef).matches()) {
            throw UseCaseException.validation("ARTIFACT_REF_INVALID",
                    "artifactRef must start with oci://, file://, s3://, or platform://");
        }
    }

    /// Spec `function-artifact-upload.md` §1: a no-op for every scheme but
    /// `platform://`. A `platform://` ref must (a) carry `functionId`'s own
    /// id and `digest`'s own hex — anything else is `ARTIFACT_REF_MISMATCH`
    /// — and (b) already exist in the store — otherwise `ARTIFACT_NOT_UPLOADED`.
    /// No store configured is `ARTIFACT_STORE_NOT_CONFIGURED` (503),
    /// checked first: without a store neither check below can even run.
    private static void checkPlatformRef(Optional<ArtifactBlobStore> artifactStore, String artifactRef,
            String functionId, Digest digest) {
        if (artifactRef == null || !artifactRef.startsWith("platform://")) {
            return;
        }
        ArtifactBlobStore store = artifactStore.orElseThrow(ArtifactHttpException::storeNotConfigured);
        String expectedHex = digest.value().substring("sha256:".length());
        PlatformArtifactRef ref = PlatformArtifactRef.parse(artifactRef)
                .filter(r -> r.functionId().equals(functionId) && r.hex().equals(expectedHex))
                .orElseThrow(ArtifactHttpException::refMismatch);
        boolean exists;
        try {
            exists = store.exists(ref.functionId(), digest);
        } catch (ArtifactException e) {
            throw UseCaseException.internal("ARTIFACT_STORE_ERROR", "checking the uploaded artifact failed", e);
        }
        if (!exists) {
            throw ArtifactHttpException.notUploaded();
        }
    }
}
