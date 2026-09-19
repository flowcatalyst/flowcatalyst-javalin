package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// Pins [SignatureVerifier] against the golden fixture (spec
/// `function-artifacts.md` §5) and, through [TestSigstore], every load-bearing
/// check in §3.2 — C1–C9 as the spec's table names them.
class SignatureVerifierTest {

    private static final Instant NOT_BEFORE = Instant.parse("2024-03-19T17:26:26Z");
    private static final Instant NOT_AFTER = Instant.parse("2024-03-19T17:36:26Z");
    private static final Instant INTEGRATED_TIME = NOT_BEFORE.plusSeconds(60);
    private static final long ENTRY_LOG_INDEX = 12345L;

    // ---- golden fixture: the proof our reading of the format is the real one ----

    @Test
    void goldenFixtureVerifiesWithTheRealIssuerAndSubject() throws Exception {
        String bundleJson = resourceText("/function/sigstore/happy-path-v0.3.sigstore.json");
        byte[] artifact = resourceBytes("/function/sigstore/artifact.txt");
        Digest digest = digestOf(digestBytes(artifact));

        var verifier = new SignatureVerifier(TrustRoot.sigstorePublicGood());
        Verification result = verifier.verify(bundleJson, digest);

        assertThat(result).isInstanceOf(Verification.Verified.class);
        Verification.Verified verified = (Verification.Verified) result;
        assertThat(verified.signer()).isEqualTo(new SignerIdentity(
                "https://token.actions.githubusercontent.com",
                "https://github.com/sigstore-conformance/extremely-dangerous-public-oidc-beacon/"
                        + ".github/workflows/extremely-dangerous-oidc-beacon.yml@refs/heads/main"));
        assertThat(verified.signedAt()).isEqualTo(Instant.ofEpochSecond(1710869186L));
    }

    // ---- TestSigstore round trip; also C6 (first half): integratedTime, not now(), governs validity ----

    @Test
    void validBundleVerifiesEvenThoughTheLeafExpiredLongAgo() throws Exception {
        assertThat(Instant.now()).isAfter(NOT_AFTER.plusSeconds(3600 * 24 * 365));

        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertThat(result).isInstanceOf(Verification.Verified.class);
    }

    // ---- C5: one broken variant per §3.2 step, each an exact Reason ----

    @Test
    void step1_wrongDigestIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("the real artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digestBytes("a different artifact")));

        assertRejected(result, Verification.Reason.DIGEST_MISMATCH);
    }

    @Test
    void step2_logFromAnUnpinnedKeyIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        var otherEco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        // the trust root only pins otherEco's log key, not eco's
        TrustRoot trustRoot = otherEco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_UNKNOWN_LOG);
    }

    /// Also C7: "a genuine log entry for a different digest, spliced into an
    /// otherwise valid bundle" is the exact scenario step 3 exists to catch.
    @Test
    void step3_logEntrySplicedFromADifferentDigestIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digestA = digestBytes("artifact A");
        byte[] digestB = digestBytes("artifact B, a completely different one");
        String bundleAJson = TestSigstore.validBundleJson(eco, digestA, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        String bundleBJson = TestSigstore.validBundleJson(eco, digestB, INTEGRATED_TIME, ENTRY_LOG_INDEX + 1);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode rootA = (ObjectNode) Json.MAPPER.readTree(bundleAJson);
        JsonNode rootB = Json.MAPPER.readTree(bundleBJson);
        JsonNode entryB = rootB.path("verificationMaterial").path("tlogEntries").get(0);
        ArrayNode tlogEntriesA = (ArrayNode) rootA.path("verificationMaterial").path("tlogEntries");
        tlogEntriesA.set(0, entryB);
        String splicedJson = Json.MAPPER.writeValueAsString(rootA);

        // messageSignature/messageDigest still claim digestA — only the tlog entry now describes digestB
        Verification result = new SignatureVerifier(trustRoot).verify(splicedJson, digestOf(digestA));

        assertRejected(result, Verification.Reason.TLOG_ENTRY_MISMATCH);
    }

    /// Isolates the step-3 hash-value check precisely: `signature.content`
    /// and `publicKey.content` inside the tlog entry still match the bundle
    /// exactly — only `hash.value` lies about which digest this entry is
    /// for. The spec's mutant for this row is literally "compare only the
    /// signature" (C7); a verifier that did only that would let this through.
    @Test
    void step3_hashValueAloneMismatchIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.bundleJson(eco.leafCert(), eco.leafPrivateKey(), eco.logKey(),
                digest, digest, "00".repeat(32), INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_ENTRY_MISMATCH);
    }

    @Test
    void step4_corruptedSignedEntryTimestampIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode entry = (ObjectNode) root.path("verificationMaterial").path("tlogEntries").get(0);
        ObjectNode inclusionPromise = (ObjectNode) entry.path("inclusionPromise");
        inclusionPromise.put("signedEntryTimestamp", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_PROMISE_INVALID);
    }

    @Test
    void step5_corruptedInclusionProofRootHashIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode entry = (ObjectNode) root.path("verificationMaterial").path("tlogEntries").get(0);
        ObjectNode proof = (ObjectNode) entry.path("inclusionProof");
        proof.put("rootHash", Base64.getEncoder().encodeToString(new byte[32])); // all-zero, not the real leaf hash
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_INCLUSION_INVALID);
    }

    @Test
    void step6_certificateNotChainingToAPinnedCaIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        var otherEco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        // the trust root pins eco's log key (so step 2 passes) but otherEco's CA, which never signed eco's leaf
        TrustRoot mismatchedCa = new TrustRoot(
                otherEco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null).cas(),
                eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null).tlogs());

        Verification result = new SignatureVerifier(mismatchedCa).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.UNTRUSTED_CERTIFICATE);
    }

    @Test
    void step7_signatureOverTheWrongBytesIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] declaredDigest = digestBytes("hello artifact");
        byte[] actuallySigned = digestBytes("a value the leaf key never signed the declared digest for");
        String bundleJson = TestSigstore.bundleJson(eco.leafCert(), eco.leafPrivateKey(), eco.logKey(),
                declaredDigest, actuallySigned, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(declaredDigest));

        assertRejected(result, Verification.Reason.BAD_SIGNATURE);
    }

    @Test
    void step8_missingSanIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER).withoutSan());
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.IDENTITY_MISSING);
    }

    @Test
    void nonEcLeafKeyIsUnsupported() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER).withKeyAlgorithm("RSA"));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.UNSUPPORTED_BUNDLE);
    }

    // ---- step 3, isolated: signature.content and publicKey.content bound separately from hash.value ----

    /// The signature binding alone: `spec.signature.content` is a value the
    /// bundle's own `messageSignature.signature` does not match; `hash.value`
    /// and `publicKey.content` still describe this exact bundle.
    @Test
    void step3_signatureContentAloneMismatchIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        byte[] wrongSignature = TestSigstore.randomBytes(64);
        String bundleJson = TestSigstore.bundleJsonWithEntrySignatureOverride(eco, digest, wrongSignature, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_ENTRY_MISMATCH);
    }

    /// The public-key binding alone: `spec.signature.publicKey.content` is a
    /// different certificate's PEM; `hash.value` and `signature.content`
    /// still describe this exact bundle.
    @Test
    void step3_publicKeyAloneMismatchIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        byte[] otherCertDer = eco.rootCert().getEncoded();
        String bundleJson = TestSigstore.bundleJsonWithEntryPublicKeyOverride(eco, digest, otherCertDer, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_ENTRY_MISMATCH);
    }

    // ---- step 4 (the SET): it must cover EVERY field it claims to authenticate ----

    /// `integratedTime` is part of the canonical SET payload — altering it
    /// after the SET was computed breaks the SET, which is exactly what
    /// authenticates it (spec §3.2 step 4).
    @Test
    void setInvalidWhenIntegratedTimeAlteredAfterSigning() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode entry = (ObjectNode) root.path("verificationMaterial").path("tlogEntries").get(0);
        entry.put("integratedTime", String.valueOf(INTEGRATED_TIME.getEpochSecond() + 1));
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_PROMISE_INVALID);
    }

    /// `logIndex` (the entry's, not the proof's) is also part of the SET
    /// payload — altering it after the SET was computed likewise breaks it.
    @Test
    void setInvalidWhenEntryLogIndexAlteredAfterSigning() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode entry = (ObjectNode) root.path("verificationMaterial").path("tlogEntries").get(0);
        entry.put("logIndex", String.valueOf(ENTRY_LOG_INDEX + 1));
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_PROMISE_INVALID);
    }

    // ---- step 5: the checkpoint must be about the SAME tree as the inclusion proof, not just self-consistent ----

    /// A one-leaf tree's audit path is trivially self-consistent (root ==
    /// leaf hash, no hashes needed) — the proof itself is never at fault
    /// here. Only the checkpoint's claim about that root is examined: it is
    /// validly signed by the pinned log key, but for a root the proof never
    /// produced.
    @Test
    void checkpointValidlySignedButForADifferentRootIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        byte[] wrongRoot = TestSigstore.randomBytes(32);
        String bundleJson = TestSigstore.bundleJsonWithCheckpointOverride(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX,
                null, wrongRoot, null);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_CHECKPOINT_INVALID);
    }

    /// Same shape, but the checkpoint's SIZE is what disagrees with the
    /// proof — the root matches.
    @Test
    void checkpointValidlySignedButForADifferentSizeIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.bundleJsonWithCheckpointOverride(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX,
                null, null, 2L);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_CHECKPOINT_INVALID);
    }

    /// The other half: the checkpoint's content (size and root) is exactly
    /// right, but it is signed by a key the [TrustRoot] never pinned.
    @Test
    void checkpointCorrectContentButSignedByAnUnpinnedKeyIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        var unpinnedKey = TestSigstore.freshEcKeyPair();
        String bundleJson = TestSigstore.bundleJsonWithCheckpointOverride(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX,
                unpinnedKey, null, null);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_CHECKPOINT_INVALID);
    }

    /// A genuine multi-leaf tree (index matters, the audit path is
    /// non-trivial) verifies — the baseline the two broken variants below
    /// are compared against.
    @Test
    void multiLeafInclusionProofVerifiesAsABaseline() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJsonWithTree(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX, 6, 2);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertThat(result).isInstanceOf(Verification.Verified.class);
    }

    @Test
    void multiLeafInclusionProofWithAWrongLogIndexIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJsonWithTree(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX, 6, 2);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode entry = (ObjectNode) root.path("verificationMaterial").path("tlogEntries").get(0);
        ObjectNode proof = (ObjectNode) entry.path("inclusionProof");
        proof.put("logIndex", "3"); // the hashes were built for index 2, not 3
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_INCLUSION_INVALID);
    }

    @Test
    void multiLeafInclusionProofWithARemovedHashIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJsonWithTree(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX, 6, 2);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode entry = (ObjectNode) root.path("verificationMaterial").path("tlogEntries").get(0);
        ObjectNode proof = (ObjectNode) entry.path("inclusionProof");
        ArrayNode hashes = (ArrayNode) proof.path("hashes");
        assertThat(hashes.size()).as("a 6-leaf tree's audit path at index 2 is non-trivial").isGreaterThan(1);
        hashes.remove(hashes.size() - 1);
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_INCLUSION_INVALID);
    }

    // ---- step 6, the remaining clauses: EKU, and each window separately ----

    @Test
    void step6_noCodeSigningEkuIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER).withoutEku());
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.NOT_A_CODE_SIGNING_CERTIFICATE);
    }

    /// The chain would otherwise validate fine — only the CA's OWN pinned
    /// window excludes `integratedTime`.
    @Test
    void step6_caWindowNotCoveringIntegratedTimeIsUntrustedCertificate() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(INTEGRATED_TIME.plusSeconds(60), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.UNTRUSTED_CERTIFICATE);
    }

    /// Same log key as ever (unlike [#step2_logFromAnUnpinnedKeyIsRejected])
    /// — only its OWN pinned window excludes `integratedTime`.
    @Test
    void step6_logKeyWindowNotCoveringIntegratedTimeIsUnknownLog() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, INTEGRATED_TIME.plusSeconds(60), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_UNKNOWN_LOG);
    }

    // ---- step 8, the remaining clauses ----

    @Test
    void step8_twoSansIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER)
                .withSan("uri", "https://one.example/workflow.yml,uri:https://two.example/workflow.yml"));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.IDENTITY_MISSING);
    }

    @Test
    void step8_issuerExtensionAbsentIsRejected() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER).withoutIssuer());
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.IDENTITY_MISSING);
    }

    /// Only the deprecated `…57264.1.1` extension is present (no
    /// `…57264.1.8`) — the fallback alone is what is read.
    @Test
    void step8_deprecatedIssuerExtensionAloneIsRead() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER)
                .withIssuer(TestSigstore.ISSUER_OID_DEPRECATED, "https://deprecated.example/issuer"));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertThat(result).isInstanceOf(Verification.Verified.class);
        assertThat(((Verification.Verified) result).signer().issuer()).isEqualTo("https://deprecated.example/issuer");
    }

    // ---- §3.1: bundle shapes this verifier does not read ----

    @Test
    void oldMediaTypeIsUnsupported() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        root.put("mediaType", "application/vnd.dev.sigstore.bundle+json;version=0.1");
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.UNSUPPORTED_BUNDLE);
    }

    @Test
    void dsseEnvelopeBundleIsUnsupported() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        root.putObject("dsseEnvelope").put("payload", "eyJ9");
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.UNSUPPORTED_BUNDLE);
    }

    @Test
    void certificateChainInsteadOfSingleLeafIsUnsupported() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode vm = (ObjectNode) root.path("verificationMaterial");
        vm.putArray("x509CertificateChain");
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.UNSUPPORTED_BUNDLE);
    }

    @Test
    void twoTlogEntriesIsUnsupported() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ArrayNode tlogEntries = (ArrayNode) root.path("verificationMaterial").path("tlogEntries");
        tlogEntries.add(tlogEntries.get(0).deepCopy());
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.UNSUPPORTED_BUNDLE);
    }

    @Test
    void tlogEntryKindIntotoIsUnsupported() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode entry = (ObjectNode) root.path("verificationMaterial").path("tlogEntries").get(0);
        ((ObjectNode) entry.path("kindVersion")).put("kind", "intoto");
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.UNSUPPORTED_BUNDLE);
    }

    @Test
    void missingInclusionPromiseIsTlogMissing() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        ObjectNode root = (ObjectNode) Json.MAPPER.readTree(bundleJson);
        ObjectNode entry = (ObjectNode) root.path("verificationMaterial").path("tlogEntries").get(0);
        entry.remove("inclusionPromise");
        String corrupted = Json.MAPPER.writeValueAsString(root);

        Verification result = new SignatureVerifier(trustRoot).verify(corrupted, digestOf(digest));

        assertRejected(result, Verification.Reason.TLOG_MISSING);
    }

    // ---- C6 (second half): integratedTime outside the leaf's own window ----

    @Test
    void integratedTimeOutsideLeafValidityIsCertificateNotValidAtSigning() throws Exception {
        // keytool's -validity is whole days only (no sub-day precision, unlike real Fulcio's
        // 10-minute-lived certs) — request an exact one-day window so the real certificate's
        // notAfter is known precisely, then pick integratedTime well past it.
        Instant oneDayLater = NOT_BEFORE.plusSeconds(86_400);
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, oneDayLater));
        byte[] digest = digestBytes("hello artifact");
        Instant outsideLeafWindow = NOT_BEFORE.plusSeconds(3 * 86_400);
        String bundleJson = TestSigstore.validBundleJson(eco, digest, outsideLeafWindow, ENTRY_LOG_INDEX);
        // a CA window wide enough that step 6's CA-selection alone would not reject this
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertRejected(result, Verification.Reason.CERTIFICATE_NOT_VALID_AT_SIGNING);
    }

    // ---- C8: verify() never throws ----

    static Stream<String> fuzzInputs() {
        return Stream.of(null, "", "[]", "{\"mediaType\":", "{".repeat(10 * 1024 * 1024));
    }

    @ParameterizedTest
    @MethodSource("fuzzInputs")
    void neverThrowsOnFuzzInput(String input) throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER));
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);
        Digest digest = digestOf(digestBytes("whatever"));

        assertThatCode(() -> new SignatureVerifier(trustRoot).verify(input, digest)).doesNotThrowAnyException();
        assertThat(new SignatureVerifier(trustRoot).verify(input, digest)).isInstanceOf(Verification.Rejected.class);
    }

    /// The other half of C8: none of the fuzz strings above ever reach the
    /// hand-written DER reader (spec §3.2 step 8), so they alone would not
    /// catch a removed outer catch. This bundle's leaf certificate carries a
    /// custom issuer extension whose value is a single, truncated byte (tag
    /// only, no length) — a well-formed X.509 certificate (the extension's
    /// *content* is opaque to X.509 parsing) whose extension content is not
    /// itself a well-formed DER TLV. No field-level JSON check guards this;
    /// only `verify`'s outer catch does.
    @Test
    void neverThrowsOnATruncatedDerExtension() throws Exception {
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER).withRawIssuerExtensionHex("0c"));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);
        Digest expected = digestOf(digest);

        assertThatCode(() -> new SignatureVerifier(trustRoot).verify(bundleJson, expected)).doesNotThrowAnyException();
        assertRejected(new SignatureVerifier(trustRoot).verify(bundleJson, expected), Verification.Reason.MALFORMED_BUNDLE);
    }

    // ---- C9: identity is reported verbatim ----

    @Test
    void identityIsReportedVerbatimIncludingATrailingSlash() throws Exception {
        String subjectWithTrailingSlash = "https://example.test/workflow.yml/";
        var eco = TestSigstore.build(TestSigstore.LeafSpec.valid(NOT_BEFORE, NOT_AFTER).withSan("uri", subjectWithTrailingSlash));
        byte[] digest = digestBytes("hello artifact");
        String bundleJson = TestSigstore.validBundleJson(eco, digest, INTEGRATED_TIME, ENTRY_LOG_INDEX);
        TrustRoot trustRoot = eco.trustRootFor(NOT_BEFORE.minusSeconds(3600), null, NOT_BEFORE.minusSeconds(3600), null);

        Verification result = new SignatureVerifier(trustRoot).verify(bundleJson, digestOf(digest));

        assertThat(result).isInstanceOf(Verification.Verified.class);
        assertThat(((Verification.Verified) result).signer().subject()).isEqualTo(subjectWithTrailingSlash);
    }

    // ---- helpers ----

    private static void assertRejected(Verification result, Verification.Reason expected) {
        assertThat(result).isInstanceOf(Verification.Rejected.class);
        assertThat(((Verification.Rejected) result).reason()).isEqualTo(expected);
    }

    private static byte[] digestBytes(String artifact) throws Exception {
        return digestBytes(artifact.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digestBytes(byte[] artifact) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(artifact);
    }

    private static Digest digestOf(byte[] digestBytes) {
        return new Digest("sha256:" + HexFormat.of().formatHex(digestBytes));
    }

    private static String resourceText(String path) throws Exception {
        return new String(resourceBytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] resourceBytes(String path) throws Exception {
        try (InputStream in = SignatureVerifierTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return in.readAllBytes();
        }
    }
}
