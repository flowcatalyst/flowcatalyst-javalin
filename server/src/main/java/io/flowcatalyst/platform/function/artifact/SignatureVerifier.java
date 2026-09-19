package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Verifies a Sigstore bundle against an artifact digest, JDK-only (spec
/// `function-artifacts.md` §3, owner ruling 2026-09-19: no `sigstore-java`).
/// Establishes **who signed these bytes and when**; whether that signer may
/// publish is `ClientPolicy.permits(signer, runtime)`, the caller's next
/// line. There is no configuration that skips a check — every bundle runs
/// every step in §3.2, fail closed. What this deliberately does not check
/// (no SCT, Rekor v1 only, no online lookups) is recorded in spec §4.
public final class SignatureVerifier {

    private static final String MEDIA_TYPE = "application/vnd.dev.sigstore.bundle.v0.3+json";
    private static final String CODE_SIGNING_EKU = "1.3.6.1.5.5.7.3.3";
    private static final String OID_ISSUER = "1.3.6.1.4.1.57264.1.8";
    private static final String OID_ISSUER_DEPRECATED = "1.3.6.1.4.1.57264.1.1";

    private final TrustRoot trustRoot;

    public SignatureVerifier(TrustRoot trustRoot) {
        this.trustRoot = Objects.requireNonNull(trustRoot, "trustRoot");
    }

    /// Never throws, for any `bundleJson` — including `null`, empty,
    /// non-JSON, or truncated input (spec §3, C8): every malformation this
    /// hand-written reader anticipates is an explicit [Verification.Rejected]
    /// below, and the outer catch is a documented last-resort net for a
    /// shape it did not.
    public Verification verify(String bundleJson, Digest digest) {
        Objects.requireNonNull(digest, "digest");
        try {
            return doVerify(bundleJson, digest);
        } catch (RuntimeException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "unreadable bundle: " + e);
        }
    }

    private Verification doVerify(String bundleJson, Digest digest) {
        if (bundleJson == null) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "bundle is null");
        }
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(bundleJson);
        } catch (JacksonException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "bundle is not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "bundle is not a JSON object");
        }
        if (root.has("dsseEnvelope")) {
            return rejected(Verification.Reason.UNSUPPORTED_BUNDLE, "DSSE envelope bundles are not supported");
        }

        JsonNode mediaTypeNode = root.path("mediaType");
        if (!mediaTypeNode.isString()) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "missing mediaType");
        }
        if (!MEDIA_TYPE.equals(mediaTypeNode.asString())) {
            return rejected(Verification.Reason.UNSUPPORTED_BUNDLE, "unsupported mediaType: " + mediaTypeNode.asString());
        }

        JsonNode verificationMaterial = root.path("verificationMaterial");
        if (!verificationMaterial.isObject()) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "missing verificationMaterial");
        }
        if (verificationMaterial.has("x509CertificateChain") || verificationMaterial.has("publicKey")) {
            return rejected(Verification.Reason.UNSUPPORTED_BUNDLE, "only a single leaf certificate is supported, not a chain or a public-key hint");
        }
        JsonNode certificateNode = verificationMaterial.path("certificate");
        if (!certificateNode.isObject()) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "missing verificationMaterial.certificate");
        }
        JsonNode rawBytesNode = certificateNode.path("rawBytes");
        if (!rawBytesNode.isString()) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "missing certificate.rawBytes");
        }
        byte[] leafDer;
        try {
            leafDer = Base64.getDecoder().decode(rawBytesNode.asString());
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "certificate.rawBytes is not valid base64");
        }
        X509Certificate leaf;
        try {
            leaf = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(leafDer));
        } catch (CertificateException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "certificate.rawBytes is not a valid X.509 certificate");
        }

        JsonNode tlogEntries = verificationMaterial.path("tlogEntries");
        if (!tlogEntries.isArray() || tlogEntries.size() != 1) {
            int found = tlogEntries.isArray() ? tlogEntries.size() : 0;
            return rejected(Verification.Reason.UNSUPPORTED_BUNDLE, "expected exactly one tlogEntries entry, found " + found);
        }
        JsonNode entry = tlogEntries.get(0);
        JsonNode kindVersion = entry.path("kindVersion");
        if (!"hashedrekord".equals(kindVersion.path("kind").asString(null))
                || !"0.0.1".equals(kindVersion.path("version").asString(null))) {
            return rejected(Verification.Reason.UNSUPPORTED_BUNDLE, "unsupported tlog entry kind/version");
        }

        JsonNode inclusionPromiseNode = entry.path("inclusionPromise");
        JsonNode inclusionProofNode = entry.path("inclusionProof");
        if (!inclusionPromiseNode.isObject() || !inclusionProofNode.isObject()) {
            return rejected(Verification.Reason.TLOG_MISSING, "tlog entry is missing inclusionPromise or inclusionProof");
        }
        JsonNode checkpointNode = inclusionProofNode.path("checkpoint");
        if (!checkpointNode.isObject() || !checkpointNode.path("envelope").isString()) {
            return rejected(Verification.Reason.TLOG_MISSING, "inclusionProof is missing its checkpoint");
        }

        JsonNode messageSignature = root.path("messageSignature");
        if (!messageSignature.isObject()) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "missing messageSignature");
        }
        JsonNode messageDigestNode = messageSignature.path("messageDigest");
        if (!"SHA2_256".equals(messageDigestNode.path("algorithm").asString(null))) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "messageDigest.algorithm must be SHA2_256");
        }
        JsonNode digestValueNode = messageDigestNode.path("digest");
        if (!digestValueNode.isString()) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "missing messageDigest.digest");
        }
        byte[] bundleDigestBytes;
        try {
            bundleDigestBytes = Base64.getDecoder().decode(digestValueNode.asString());
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "messageDigest.digest is not valid base64");
        }
        if (bundleDigestBytes.length != 32) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "messageDigest.digest must be 32 bytes");
        }
        JsonNode signatureNode = messageSignature.path("signature");
        if (!signatureNode.isString()) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "missing messageSignature.signature");
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(signatureNode.asString());
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "messageSignature.signature is not valid base64");
        }

        // ---- step 1: digest ----
        byte[] expectedDigestBytes = HexFormat.of().parseHex(hexOf(digest));
        if (!MessageDigest.isEqual(expectedDigestBytes, bundleDigestBytes)) {
            return rejected(Verification.Reason.DIGEST_MISMATCH, "bundle messageDigest does not match the expected artifact digest");
        }

        Instant integratedTime;
        long entryLogIndex;
        try {
            integratedTime = Instant.ofEpochSecond(Long.parseLong(entry.path("integratedTime").asString("")));
            entryLogIndex = Long.parseLong(entry.path("logIndex").asString(""));
        } catch (NumberFormatException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "integratedTime/logIndex are not numeric");
        }
        byte[] logIdBytes;
        try {
            logIdBytes = Base64.getDecoder().decode(entry.path("logId").path("keyId").asString(""));
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "logId.keyId is not valid base64");
        }

        // ---- step 2: log entry belongs to a pinned log ----
        TrustRoot.TransparencyLog tlog = trustRoot.tlogs().stream()
                .filter(t -> Arrays.equals(t.keyId(), logIdBytes) && t.containsAt(integratedTime))
                .findFirst()
                .orElse(null);
        if (tlog == null) {
            return rejected(Verification.Reason.TLOG_UNKNOWN_LOG, "no pinned transparency-log key covers this entry's logId/integratedTime");
        }
        PublicKey tlogPublicKey;
        try {
            tlogPublicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(tlog.publicKeyDer()));
        } catch (GeneralSecurityException e) {
            return rejected(Verification.Reason.TLOG_UNKNOWN_LOG, "pinned transparency-log key is not a usable EC key");
        }

        String canonicalizedBodyB64 = entry.path("canonicalizedBody").asString(null);
        if (canonicalizedBodyB64 == null) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "missing canonicalizedBody");
        }
        byte[] canonicalizedBody;
        try {
            canonicalizedBody = Base64.getDecoder().decode(canonicalizedBodyB64);
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "canonicalizedBody is not valid base64");
        }

        // ---- step 3: the entry is about this signature ----
        JsonNode body;
        try {
            body = Json.MAPPER.readTree(new String(canonicalizedBody, StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            return rejected(Verification.Reason.TLOG_ENTRY_MISMATCH, "canonicalizedBody is not valid JSON");
        }
        JsonNode hashNode = body.path("spec").path("data").path("hash");
        JsonNode bodySignature = body.path("spec").path("signature");
        String bodyHashValue = hashNode.path("value").asString(null);
        String bodySignatureContentB64 = bodySignature.path("content").asString(null);
        String bodyPublicKeyContentB64 = bodySignature.path("publicKey").path("content").asString(null);
        if (!"sha256".equals(hashNode.path("algorithm").asString(null))
                || bodyHashValue == null || bodySignatureContentB64 == null || bodyPublicKeyContentB64 == null) {
            return rejected(Verification.Reason.TLOG_ENTRY_MISMATCH, "canonicalizedBody is missing the expected hashedrekord fields");
        }
        if (!hexOf(digest).equals(bodyHashValue)) {
            return rejected(Verification.Reason.TLOG_ENTRY_MISMATCH, "canonicalizedBody hash.value does not match the artifact digest");
        }
        byte[] bodySignatureBytes;
        try {
            bodySignatureBytes = Base64.getDecoder().decode(bodySignatureContentB64);
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.TLOG_ENTRY_MISMATCH, "canonicalizedBody signature.content is not valid base64");
        }
        if (!Arrays.equals(bodySignatureBytes, signatureBytes)) {
            return rejected(Verification.Reason.TLOG_ENTRY_MISMATCH, "canonicalizedBody signature does not match the bundle's signature");
        }
        byte[] bodyLeafDer;
        try {
            bodyLeafDer = pemToDer(new String(Base64.getDecoder().decode(bodyPublicKeyContentB64), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.TLOG_ENTRY_MISMATCH, "canonicalizedBody publicKey.content is not a PEM certificate");
        }
        if (!Arrays.equals(bodyLeafDer, leafDer)) {
            return rejected(Verification.Reason.TLOG_ENTRY_MISMATCH, "canonicalizedBody publicKey does not match the bundle's certificate");
        }

        // ---- step 4: the signed entry timestamp authenticates integratedTime ----
        String setB64 = inclusionPromiseNode.path("signedEntryTimestamp").asString(null);
        if (setB64 == null) {
            return rejected(Verification.Reason.TLOG_MISSING, "inclusionPromise is missing signedEntryTimestamp");
        }
        String canonicalSet = "{\"body\":\"" + canonicalizedBodyB64 + "\",\"integratedTime\":" + integratedTime.getEpochSecond()
                + ",\"logID\":\"" + HexFormat.of().formatHex(logIdBytes) + "\",\"logIndex\":" + entryLogIndex + "}";
        byte[] setBytes;
        try {
            setBytes = Base64.getDecoder().decode(setB64);
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.MALFORMED_BUNDLE, "signedEntryTimestamp is not valid base64");
        }
        if (!ecdsaVerifies(tlogPublicKey, canonicalSet.getBytes(StandardCharsets.UTF_8), setBytes)) {
            return rejected(Verification.Reason.TLOG_PROMISE_INVALID, "signed entry timestamp does not verify under the pinned log key");
        }

        // ---- step 5: inclusion (audit path + checkpoint) ----
        Verification.Rejected inclusionFailure = checkInclusion(inclusionProofNode, checkpointNode, canonicalizedBody, tlogPublicKey);
        if (inclusionFailure != null) {
            return inclusionFailure;
        }

        // ---- step 6: certificate chain, validity window, code-signing EKU ----
        Verification.Rejected certificateFailure = checkCertificate(leaf, integratedTime);
        if (certificateFailure != null) {
            return certificateFailure;
        }

        // ---- step 7: signature over the digest ----
        if (!(leaf.getPublicKey() instanceof ECPublicKey ecPublicKey) || !isP256(ecPublicKey)) {
            return rejected(Verification.Reason.UNSUPPORTED_BUNDLE, "leaf certificate key is not P-256 EC");
        }
        if (!noneWithEcdsaVerifies(ecPublicKey, expectedDigestBytes, signatureBytes)) {
            return rejected(Verification.Reason.BAD_SIGNATURE, "signature does not verify under the leaf certificate's key");
        }

        // ---- step 8: identity ----
        SignerIdentity identity = identityOf(leaf);
        if (identity == null) {
            return rejected(Verification.Reason.IDENTITY_MISSING, "issuer extension or a single URI/rfc822 SAN is missing");
        }

        return new Verification.Verified(identity, integratedTime);
    }

    // ---- step 5: RFC 6962 audit path + signed-note checkpoint ----

    private static Verification.Rejected checkInclusion(JsonNode inclusionProofNode, JsonNode checkpointNode,
                                                          byte[] canonicalizedBody, PublicKey tlogPublicKey) {
        long proofLogIndex;
        long treeSize;
        byte[] rootHash;
        List<byte[]> hashes = new ArrayList<>();
        try {
            proofLogIndex = Long.parseLong(inclusionProofNode.path("logIndex").asString(""));
            treeSize = Long.parseLong(inclusionProofNode.path("treeSize").asString(""));
            rootHash = Base64.getDecoder().decode(inclusionProofNode.path("rootHash").asString(""));
            for (JsonNode hash : inclusionProofNode.path("hashes")) {
                hashes.add(Base64.getDecoder().decode(hash.asString()));
            }
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.TLOG_INCLUSION_INVALID, "inclusionProof fields are malformed");
        }

        byte[] leafHash = sha256(concat(new byte[]{0x00}, canonicalizedBody));
        byte[] computedRoot;
        try {
            computedRoot = rootFromInclusionProof(proofLogIndex, treeSize, hashes, leafHash);
        } catch (IndexOutOfBoundsException e) {
            return rejected(Verification.Reason.TLOG_INCLUSION_INVALID, "audit path is too short for the proof");
        }
        if (!Arrays.equals(computedRoot, rootHash)) {
            return rejected(Verification.Reason.TLOG_INCLUSION_INVALID, "audit path does not produce the checkpoint's root hash");
        }

        String envelope = checkpointNode.path("envelope").asString("");
        SignedNote note;
        try {
            note = parseSignedNote(envelope);
        } catch (IllegalArgumentException e) {
            return rejected(Verification.Reason.TLOG_CHECKPOINT_INVALID, "checkpoint envelope is malformed: " + e.getMessage());
        }
        if (note.size() != treeSize || !Arrays.equals(note.rootHash(), rootHash)) {
            return rejected(Verification.Reason.TLOG_CHECKPOINT_INVALID, "checkpoint size/root do not match the inclusion proof");
        }
        boolean anySignatureValid = note.signatures().stream()
                .anyMatch(signature -> ecdsaVerifies(tlogPublicKey, note.body().getBytes(StandardCharsets.UTF_8), signature.signature()));
        if (!anySignatureValid) {
            return rejected(Verification.Reason.TLOG_CHECKPOINT_INVALID, "no checkpoint signature verifies under the pinned log key");
        }
        return null;
    }

    /// The RFC 6962 client-side audit-path algorithm: folds sibling hashes
    /// from `leafHash` up to the root, consuming one proof entry whenever
    /// the current node is a right child or has a right sibling still to
    /// merge, and simply promoting otherwise.
    private static byte[] rootFromInclusionProof(long leafIndex, long treeSize, List<byte[]> proof, byte[] leafHash) {
        byte[] hash = leafHash;
        long node = leafIndex;
        long lastNode = treeSize - 1;
        int i = 0;
        while (lastNode > 0) {
            if (node % 2 == 1) {
                hash = hashChildren(proof.get(i), hash);
                i++;
            } else if (node < lastNode) {
                hash = hashChildren(hash, proof.get(i));
                i++;
            }
            node /= 2;
            lastNode /= 2;
        }
        return hash;
    }

    private static byte[] hashChildren(byte[] left, byte[] right) {
        return sha256(concat(new byte[]{0x01}, concat(left, right)));
    }

    /// A signed note (the checkpoint envelope): `body` is everything up to
    /// and including the blank line's preceding newline; each `— name
    /// base64(4-byte key hint ‖ signature)` line after it is one signature.
    private record SignedNote(String body, long size, byte[] rootHash, List<NoteSignature> signatures) {
    }

    private record NoteSignature(String name, byte[] signature) {
    }

    private static SignedNote parseSignedNote(String envelope) {
        int blank = envelope.indexOf("\n\n");
        if (blank < 0) {
            throw new IllegalArgumentException("no blank line separating the checkpoint body from its signatures");
        }
        String body = envelope.substring(0, blank + 1);
        String[] bodyLines = body.split("\n");
        if (bodyLines.length < 3) {
            throw new IllegalArgumentException("checkpoint body has fewer than three lines");
        }
        long size;
        byte[] rootHash;
        try {
            size = Long.parseLong(bodyLines[1].trim());
            rootHash = Base64.getDecoder().decode(bodyLines[2].trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("checkpoint size/root line is malformed", e);
        }
        List<NoteSignature> signatures = new ArrayList<>();
        for (String line : envelope.substring(blank + 2).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("— ")) {
                throw new IllegalArgumentException("malformed checkpoint signature line");
            }
            String rest = line.substring(2);
            int space = rest.indexOf(' ');
            if (space < 0) {
                throw new IllegalArgumentException("malformed checkpoint signature line");
            }
            String name = rest.substring(0, space);
            byte[] raw = Base64.getDecoder().decode(rest.substring(space + 1));
            if (raw.length <= 4) {
                throw new IllegalArgumentException("checkpoint signature is too short for its 4-byte key hint");
            }
            signatures.add(new NoteSignature(name, Arrays.copyOfRange(raw, 4, raw.length)));
        }
        if (signatures.isEmpty()) {
            throw new IllegalArgumentException("checkpoint has no signature lines");
        }
        return new SignedNote(body, size, rootHash, signatures);
    }

    // ---- step 6: certificate chain, validity window, code-signing EKU ----

    /// Picks every pinned certificate authority whose window contains
    /// `integratedTime` and tries each; `UNTRUSTED_CERTIFICATE` when none
    /// covers the time at all, `CERTIFICATE_NOT_VALID_AT_SIGNING` when a
    /// covering CA's chain is trusted but the **leaf's own** notBefore/notAfter
    /// excludes `integratedTime` (spec §3.2 step 6: "the chain is fine but
    /// the time is outside the leaf's validity").
    private Verification.Rejected checkCertificate(X509Certificate leaf, Instant integratedTime) {
        List<TrustRoot.CertificateAuthority> candidates = trustRoot.cas().stream()
                .filter(ca -> ca.containsAt(integratedTime))
                .toList();
        if (candidates.isEmpty()) {
            return rejected(Verification.Reason.UNTRUSTED_CERTIFICATE, "no pinned certificate authority is active at integratedTime");
        }
        boolean trusted = false;
        boolean leafTimeFailure = false;
        for (TrustRoot.CertificateAuthority ca : candidates) {
            ChainOutcome outcome = validateChain(leaf, ca, integratedTime);
            if (outcome == ChainOutcome.TRUSTED) {
                trusted = true;
                break;
            }
            if (outcome == ChainOutcome.LEAF_NOT_VALID_AT_TIME) {
                leafTimeFailure = true;
            }
        }
        if (!trusted) {
            return leafTimeFailure
                    ? rejected(Verification.Reason.CERTIFICATE_NOT_VALID_AT_SIGNING, "leaf certificate's own validity window does not contain integratedTime")
                    : rejected(Verification.Reason.UNTRUSTED_CERTIFICATE, "leaf certificate does not chain to a pinned certificate authority");
        }
        List<String> eku;
        try {
            eku = leaf.getExtendedKeyUsage();
        } catch (CertificateParsingException e) {
            eku = null;
        }
        if (eku == null || !eku.contains(CODE_SIGNING_EKU)) {
            return rejected(Verification.Reason.NOT_A_CODE_SIGNING_CERTIFICATE, "leaf certificate's extended key usage does not include code signing");
        }
        return null;
    }

    private enum ChainOutcome {
        TRUSTED, LEAF_NOT_VALID_AT_TIME, UNTRUSTED
    }

    private static ChainOutcome validateChain(X509Certificate leaf, TrustRoot.CertificateAuthority ca, Instant integratedTime) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (byte[] der : ca.certChainDer()) {
                chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
            X509Certificate root = chain.get(chain.size() - 1);
            List<Certificate> path = new ArrayList<>();
            path.add(leaf);
            path.addAll(chain.subList(0, chain.size() - 1));
            CertPath certPath = factory.generateCertPath(path);

            PKIXParameters params = new PKIXParameters(Set.of(new TrustAnchor(root, null)));
            params.setRevocationEnabled(false);
            params.setDate(Date.from(integratedTime));

            CertPathValidator.getInstance("PKIX").validate(certPath, params);
            return ChainOutcome.TRUSTED;
        } catch (CertPathValidatorException e) {
            boolean leafExpiryReason = e.getIndex() == 0
                    && (e.getReason() == CertPathValidatorException.BasicReason.EXPIRED
                    || e.getReason() == CertPathValidatorException.BasicReason.NOT_YET_VALID);
            return leafExpiryReason ? ChainOutcome.LEAF_NOT_VALID_AT_TIME : ChainOutcome.UNTRUSTED;
        } catch (GeneralSecurityException e) {
            return ChainOutcome.UNTRUSTED;
        }
    }

    // ---- helpers ----

    private static Verification.Rejected rejected(Verification.Reason reason, String detail) {
        return new Verification.Rejected(reason, detail);
    }

    private static String hexOf(Digest digest) {
        String value = digest.value();
        return value.substring(value.indexOf(':') + 1);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean ecdsaVerifies(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static boolean noneWithEcdsaVerifies(PublicKey key, byte[] digestBytes, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("NONEwithECDSA");
            verifier.initVerify(key);
            verifier.update(digestBytes);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static boolean isP256(ECPublicKey key) {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec p256 = params.getParameterSpec(ECParameterSpec.class);
            return key.getParams().getCurve().equals(p256.getCurve());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static byte[] pemToDer(String pem) {
        String base64 = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    // ---- step 8: identity ----

    private static SignerIdentity identityOf(X509Certificate leaf) {
        String issuer = extensionUtf8String(leaf, OID_ISSUER);
        if (issuer == null) {
            issuer = extensionRawString(leaf, OID_ISSUER_DEPRECATED);
        }
        String subject = singleUriOrEmailSan(leaf);
        if (issuer == null || subject == null) {
            return null;
        }
        return new SignerIdentity(issuer, subject);
    }

    private static String singleUriOrEmailSan(X509Certificate leaf) {
        Collection<List<?>> sans;
        try {
            sans = leaf.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return null;
        }
        if (sans == null || sans.size() != 1) {
            return null;
        }
        List<?> entry = sans.iterator().next();
        if (entry.size() != 2 || !(entry.get(0) instanceof Integer type) || !(entry.get(1) instanceof String value)) {
            return null;
        }
        return (type == 6 || type == 1) ? value : null;
    }

    /// The Fulcio issuer extension `1.3.6.1.4.1.57264.1.8`: a DER UTF8String
    /// wrapped in the OCTET STRING [X509Certificate#getExtensionValue] returns.
    private static String extensionUtf8String(X509Certificate cert, String oid) {
        byte[] extensionValue = cert.getExtensionValue(oid);
        if (extensionValue == null) {
            return null;
        }
        Tlv outer = readTlv(extensionValue, 0);
        Tlv inner = readTlv(outer.value(), 0);
        if (inner.tag() != 0x0C) {
            return null;
        }
        return new String(inner.value(), StandardCharsets.UTF_8);
    }

    /// The deprecated issuer extension `1.3.6.1.4.1.57264.1.1`: raw ASCII
    /// bytes directly inside the OCTET STRING, not a further-typed TLV.
    private static String extensionRawString(X509Certificate cert, String oid) {
        byte[] extensionValue = cert.getExtensionValue(oid);
        if (extensionValue == null) {
            return null;
        }
        Tlv outer = readTlv(extensionValue, 0);
        return new String(outer.value(), StandardCharsets.UTF_8);
    }

    /// A minimal DER tag-length-value reader for the two Fulcio extensions
    /// above (spec §3.2 step 8: "reading a DER UTF8String by hand is fine").
    private record Tlv(int tag, byte[] value) {
    }

    private static Tlv readTlv(byte[] der, int offset) {
        int tag = der[offset] & 0xFF;
        int lengthByte = der[offset + 1] & 0xFF;
        int length;
        int headerLength;
        if ((lengthByte & 0x80) == 0) {
            length = lengthByte;
            headerLength = 2;
        } else {
            int numBytes = lengthByte & 0x7F;
            length = 0;
            for (int i = 0; i < numBytes; i++) {
                length = (length << 8) | (der[offset + 2 + i] & 0xFF);
            }
            headerLength = 2 + numBytes;
        }
        return new Tlv(tag, Arrays.copyOfRange(der, offset + headerLength, offset + headerLength + length));
    }
}
