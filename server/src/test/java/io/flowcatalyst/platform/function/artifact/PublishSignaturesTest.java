package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.ClientPolicy;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.function.operations.PublishCommand;
import io.flowcatalyst.platform.function.operations.PublishVersion;
import io.flowcatalyst.platform.function.operations.TriggerSync;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `PublishVersion` under `Signatures.Required`/`Signatures.Off` (spec
/// `function-api.md` §5.1 step 5, §8 P8). Lives in package `artifact` because
/// [TestSigstore] is package-private — it mints the real bundles this pins
/// against, the same fixture builder `SignatureVerifierTest` uses.
@SuppressWarnings("deprecation")
class PublishSignaturesTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(DS);
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = "usr_sig_" + RUN;
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final AuthContext ANCHOR =
            new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io", List.of("*"), List.of(), List.of(), true, List.of());

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final TriggerSync NONE = TriggerSync.none();

    private static final Instant NOT_BEFORE = Instant.parse("2024-03-19T17:26:26Z");
    private static final Instant NOT_AFTER = Instant.parse("2024-03-19T17:36:26Z");
    private static final Instant INTEGRATED_TIME = NOT_BEFORE.plusSeconds(60);
    private static final long ENTRY_LOG_INDEX = 12345L;

    private static final String MINIMAL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice"
            }
            """;

    private static tools.jackson.databind.JsonNode manifestJson() {
        return Json.MAPPER.readTree(MINIMAL_JVM);
    }

    private static Function createFunction(String tag, FunctionOwner owner) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("sig" + RUN), new DnsLabel("svc"), new DnsLabel(tag));
        Function f = Function.create("app_" + RUN, address, owner, Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static void putPolicy(FunctionOwner owner, List<ClientPolicy.SignerRule> signers) {
        Instant now = Instant.now();
        ClientPolicy policy = new ClientPolicy(owner, signers, null, null, null, null, now, now);
        uow.inTransaction(tx -> {
            policies.persist(policy, tx.dbTx());
            return null;
        });
    }

    private static PublishVersion.Result publish(FunctionAddress address, String artifactSuffix, byte[] artifactDigestBytes,
            String bundle, Signatures signatures) {
        Digest digest = digestOf(artifactDigestBytes);
        var cmd = new PublishCommand(address, "oci://artifact/" + artifactSuffix, digest.value(), bundle, manifestJson());
        return Auth.runAs(ANCHOR, () -> PublishVersion.of(functions, versions, policies, DEFAULTS, signatures, NONE, java.util.Optional.empty()).run(uow, cmd, EC));
    }

    private static void assertUseCaseError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    // ── Fixtures: one real Sigstore ecosystem + trust root, shared by the "Required" tests ──

    private static TestSigstore.Ecosystem ecosystem() throws GeneralSecurityException {
        return TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
    }

    private static TrustRoot trustRootFor(TestSigstore.Ecosystem eco) throws GeneralSecurityException {
        return eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);
    }

    private static byte[] digestBytes(String artifact) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(artifact.getBytes(StandardCharsets.UTF_8));
    }

    private static Digest digestOf(byte[] digestBytes) {
        return new Digest("sha256:" + HexFormat.of().formatHex(digestBytes));
    }

    /// [TestSigstore.LeafSpec#valid]'s own SAN/issuer, exactly what
    /// `SignatureVerifier` reads back as the signer identity.
    private static final SignerIdentity EXPECTED_SIGNER =
            new SignerIdentity("https://example.test/issuer", "https://example.test/workflow.yml");

    // ── P8 clause 1: no bundle ⇒ SIGNATURE_REQUIRED ─────────────────────────

    @Test
    void requiredWithNoBundleIsSignatureRequired() throws Exception {
        var eco = ecosystem();
        Signatures required = new Signatures.Required(new SignatureVerifier(trustRootFor(eco)));
        Function f = createFunction("nobundle", new FunctionOwner.Platform());

        assertUseCaseError(() -> publish(f.address(), "a", digestBytes("artifact-a"), null, required),
                UseCaseError.Validation.class, "SIGNATURE_REQUIRED");
    }

    // ── P8 clause 2: bundle for ANOTHER digest ⇒ SIGNATURE_REJECTED, details.reason=DIGEST_MISMATCH ──

    @Test
    void requiredWithABundleForAnotherDigestIsSignatureRejectedWithDigestMismatchReason() throws Exception {
        var eco = ecosystem();
        Signatures required = new Signatures.Required(new SignatureVerifier(trustRootFor(eco)));
        Function f = createFunction("wrongdigest", new FunctionOwner.Platform());

        byte[] bundleDigest = digestBytes("the bundle's real artifact");
        String bundle = TestSigstore.validBundleJson(eco, bundleDigest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        byte[] publishedDigest = digestBytes("a completely different artifact");

        assertThatThrownBy(() -> publish(f.address(), "b", publishedDigest, bundle, required))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("SIGNATURE_REJECTED");
                    assertThat(err.details()).as("mutant: skip verify — details.reason must name DIGEST_MISMATCH")
                            .containsEntry("reason", Verification.Reason.DIGEST_MISMATCH.name());
                });
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    // ── P8 clause 3: valid bundle, NO policy row ⇒ SIGNER_NOT_PERMITTED ─────

    @Test
    void requiredWithAValidBundleAndNoPolicyRowIsSignerNotPermitted() throws Exception {
        var eco = ecosystem();
        Signatures required = new Signatures.Required(new SignatureVerifier(trustRootFor(eco)));
        Function f = createFunction("nopolicy", new FunctionOwner.Platform());

        byte[] digestBytes = digestBytes("artifact-c");
        String bundle = TestSigstore.validBundleJson(eco, digestBytes, INTEGRATED_TIME, ENTRY_LOG_INDEX);

        assertUseCaseError(() -> publish(f.address(), "c", digestBytes, bundle, required),
                UseCaseError.Authorization.class, "SIGNER_NOT_PERMITTED");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    // ── P8 clause 4: policy row with a DIFFERENT subject ⇒ SIGNER_NOT_PERMITTED ──

    @Test
    void requiredWithAPolicyNamingADifferentSubjectIsSignerNotPermitted() throws Exception {
        var eco = ecosystem();
        Signatures required = new Signatures.Required(new SignatureVerifier(trustRootFor(eco)));
        FunctionOwner owner = FunctionOwner.ofClientId("clw1_" + RUN);
        Function f = createFunction("wrongsubject", owner);
        putPolicy(owner, List.of(new ClientPolicy.SignerRule(
                EXPECTED_SIGNER.issuer(), "https://example.test/not-the-real-workflow.yml", Set.of(Runtime.JVM))));

        byte[] digestBytes = digestBytes("artifact-d");
        String bundle = TestSigstore.validBundleJson(eco, digestBytes, INTEGRATED_TIME, ENTRY_LOG_INDEX);

        assertUseCaseError(() -> publish(f.address(), "d", digestBytes, bundle, required),
                UseCaseError.Authorization.class, "SIGNER_NOT_PERMITTED");
    }

    // ── P8 clause 5: policy permits only WASM for a JVM function ⇒ SIGNER_NOT_PERMITTED ──

    @Test
    void requiredWithAPolicyPermittingOnlyWasmIsSignerNotPermittedForAJvmFunction() throws Exception {
        var eco = ecosystem();
        Signatures required = new Signatures.Required(new SignatureVerifier(trustRootFor(eco)));
        FunctionOwner owner = FunctionOwner.ofClientId("clw2_" + RUN);
        Function f = createFunction("wasmonly", owner); // JVM function
        putPolicy(owner, List.of(new ClientPolicy.SignerRule(
                EXPECTED_SIGNER.issuer(), EXPECTED_SIGNER.subject(), Set.of(Runtime.WASM))));

        byte[] digestBytes = digestBytes("artifact-e");
        String bundle = TestSigstore.validBundleJson(eco, digestBytes, INTEGRATED_TIME, ENTRY_LOG_INDEX);

        assertUseCaseError(() -> publish(f.address(), "e", digestBytes, bundle, required),
                UseCaseError.Authorization.class, "SIGNER_NOT_PERMITTED");
    }

    // ── P8 clause 6: exact match ⇒ succeeds; stored signer equals the certificate's identity ──

    @Test
    void requiredWithAnExactPolicyMatchSucceedsAndStoresTheCertificatesSignerIdentity() throws Exception {
        var eco = ecosystem();
        Signatures required = new Signatures.Required(new SignatureVerifier(trustRootFor(eco)));
        FunctionOwner owner = FunctionOwner.ofClientId("clex_" + RUN);
        Function f = createFunction("exact", owner);
        putPolicy(owner, List.of(new ClientPolicy.SignerRule(
                EXPECTED_SIGNER.issuer(), EXPECTED_SIGNER.subject(), Set.of(Runtime.JVM))));

        byte[] digestBytes = digestBytes("artifact-f");
        String bundle = TestSigstore.validBundleJson(eco, digestBytes, INTEGRATED_TIME, ENTRY_LOG_INDEX);

        var result = publish(f.address(), "f", digestBytes, bundle, required);

        assertThat(result.version().signer()).isEqualTo(EXPECTED_SIGNER);
        assertThat(result.event().signerIssuer()).isEqualTo(EXPECTED_SIGNER.issuer());
        assertThat(result.event().signerSubject()).isEqualTo(EXPECTED_SIGNER.subject());
    }

    // ── P8 with Signatures.Off: bundle-less publish succeeds, signer null ──

    @Test
    void offAllowsABundlelessPublishWithANullSigner() throws Exception {
        Function f = createFunction("off", new FunctionOwner.Platform());

        var result = publish(f.address(), "g", digestBytes("artifact-g"), null, new Signatures.Off());

        assertThat(result.version().signer()).as("mutant: verify even when off").isNull();
        assertThat(result.version().signatureBundle()).isNull();
        assertThat(result.event().signerIssuer()).isNull();
        assertThat(result.event().signerSubject()).isNull();
    }

    /// `Off` still STORES a bundle if one is sent, unverified (spec §5.1 step
    /// 5's "the bundle is stored if sent") — distinct from the null-bundle case above.
    @Test
    void offStoresASentBundleUnverifiedWithANullSigner() throws Exception {
        var eco = ecosystem();
        Function f = createFunction("offwithbundle", new FunctionOwner.Platform());
        byte[] digestBytes = digestBytes("artifact-h");
        // A bundle that would NEVER verify (garbage) — Off must never even try.
        String garbageBundle = "not a real sigstore bundle";

        var result = publish(f.address(), "h", digestBytes, garbageBundle, new Signatures.Off());

        assertThat(result.version().signer()).isNull();
        assertThat(result.version().signatureBundle()).isEqualTo(garbageBundle);
    }
}
