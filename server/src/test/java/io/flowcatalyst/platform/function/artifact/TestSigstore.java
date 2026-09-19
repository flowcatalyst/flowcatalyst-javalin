package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/// A self-contained miniature Sigstore ecosystem for tests, JDK only (spec
/// `function-artifacts.md` §5): a P-256 root CA and leaf built with
/// `$JAVA_HOME/bin/keytool` via `ProcessBuilder` — this JDK's `keytool` has
/// no `java.util.spi.ToolProvider` implementation (checked empirically: only
/// a handful of tools, `jar` among them, register one), which is exactly the
/// fallback the spec names — a P-256 log key built with plain JCA, and a
/// builder that produces a correct v0.3 bundle over a single-entry tree — a
/// tree of one leaf needs no audit-path hashes at all (RFC 6962: the root of
/// a one-leaf tree *is* the leaf hash), which keeps every bundle here
/// self-consistent without hand-rolling a multi-leaf Merkle tree.
final class TestSigstore {

    static final String ISSUER_OID = "1.3.6.1.4.1.57264.1.8";
    static final String ISSUER_OID_DEPRECATED = "1.3.6.1.4.1.57264.1.1";
    static final String LOG_ORIGIN = "test.rekor.local - 1";

    private TestSigstore() {
    }

    /// What to bake into the leaf certificate; `null` on an optional field
    /// omits that extension entirely (used by the broken-variant tests).
    record LeafSpec(
            Instant notBefore,
            Instant notAfter,
            boolean codeSigningEku,
            String sanType,
            String sanValue,
            String issuerOid,
            String issuerValue,
            String keyAlgorithm,
            String rawIssuerExtensionHex
    ) {
        static LeafSpec valid(Instant notBefore, Instant notAfter) {
            return new LeafSpec(notBefore, notAfter, true, "uri",
                    "https://example.test/workflow.yml", ISSUER_OID, "https://example.test/issuer", "EC", null);
        }

        LeafSpec withValidity(Instant from, Instant until) {
            return new LeafSpec(from, until, codeSigningEku, sanType, sanValue, issuerOid, issuerValue, keyAlgorithm, rawIssuerExtensionHex);
        }

        LeafSpec withoutEku() {
            return new LeafSpec(notBefore, notAfter, false, sanType, sanValue, issuerOid, issuerValue, keyAlgorithm, rawIssuerExtensionHex);
        }

        LeafSpec withSan(String type, String value) {
            return new LeafSpec(notBefore, notAfter, codeSigningEku, type, value, issuerOid, issuerValue, keyAlgorithm, rawIssuerExtensionHex);
        }

        LeafSpec withoutSan() {
            return withSan(null, null);
        }

        LeafSpec withIssuer(String oid, String value) {
            return new LeafSpec(notBefore, notAfter, codeSigningEku, sanType, sanValue, oid, value, keyAlgorithm, rawIssuerExtensionHex);
        }

        LeafSpec withoutIssuer() {
            return withIssuer(null, null);
        }

        LeafSpec withKeyAlgorithm(String algorithm) {
            return new LeafSpec(notBefore, notAfter, codeSigningEku, sanType, sanValue, issuerOid, issuerValue, algorithm, rawIssuerExtensionHex);
        }

        /// Overrides the issuer extension's DER content with `hex` verbatim
        /// — bypassing the normal TLV encoding entirely, to build a leaf
        /// whose custom extension is not even a well-formed TLV (spec §5:
        /// this is the fixture behind [SignatureVerifierTest]'s C8 test for
        /// the DER reader specifically, which no field-level JSON check
        /// guards — only the outer catch in `verify` does).
        LeafSpec withRawIssuerExtensionHex(String hex) {
            return new LeafSpec(notBefore, notAfter, codeSigningEku, sanType, sanValue, issuerOid, issuerValue, keyAlgorithm, hex);
        }
    }

    record Ecosystem(X509Certificate rootCert, X509Certificate leafCert, PrivateKey leafPrivateKey, KeyPair logKey) {

        TrustRoot trustRootFor(Instant caFrom, Instant caUntil, Instant tlogFrom, Instant tlogUntil) throws GeneralSecurityException {
            byte[] logSpki = logKey.getPublic().getEncoded();
            var ca = new TrustRoot.CertificateAuthority(List.of(rootCert.getEncoded()), caFrom, caUntil);
            var tlog = new TrustRoot.TransparencyLog(sha256(logSpki), logSpki, tlogFrom, tlogUntil);
            return new TrustRoot(List.of(ca), List.of(tlog));
        }
    }

    /// Builds a root CA and a leaf certificate it signs, with the extensions
    /// `spec` describes. `keyAlgorithm` other than `"EC"` produces an RSA
    /// leaf (for the "non-EC key ⇒ UNSUPPORTED_BUNDLE" case, spec §3.2 step 7).
    static Ecosystem build(LeafSpec spec) {
        try {
            Path dir = Files.createTempDirectory("sigstore-test");
            Path keystore = dir.resolve("ks.p12");
            String storepass = "changeit";

            keytool("-genkeypair", "-alias", "root", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-sigalg", "SHA384withECDSA", "-keystore", keystore.toString(), "-storetype", "PKCS12",
                    "-storepass", storepass, "-dname", "CN=test-root", "-validity", "3650",
                    "-ext", "bc:critical=ca:true");

            String leafKeyAlg = spec.keyAlgorithm() == null ? "EC" : spec.keyAlgorithm();
            List<String> leafGenArgs = new ArrayList<>(List.of(
                    "-genkeypair", "-alias", "leaf", "-keyalg", leafKeyAlg,
                    "-keystore", keystore.toString(), "-storetype", "PKCS12",
                    "-storepass", storepass, "-dname", "CN=test-leaf", "-validity", "3650"));
            if ("EC".equals(leafKeyAlg)) {
                leafGenArgs.addAll(List.of("-groupname", "secp256r1"));
            } else {
                leafGenArgs.addAll(List.of("-keysize", "2048"));
            }
            keytool(leafGenArgs.toArray(new String[0]));

            Path csr = dir.resolve("leaf.csr");
            keytool("-certreq", "-alias", "leaf", "-keystore", keystore.toString(), "-storetype", "PKCS12",
                    "-storepass", storepass, "-file", csr.toString());

            Path leafCrt = dir.resolve("leaf.crt");
            List<String> gencertArgs = new ArrayList<>(List.of(
                    "-gencert", "-alias", "root", "-keystore", keystore.toString(), "-storetype", "PKCS12",
                    "-storepass", storepass, "-infile", csr.toString(), "-outfile", leafCrt.toString(),
                    "-sigalg", "SHA384withECDSA",
                    "-startdate", formatStartDate(spec.notBefore()),
                    "-validity", String.valueOf(validityDays(spec.notBefore(), spec.notAfter()))));
            if (spec.codeSigningEku()) {
                gencertArgs.addAll(List.of("-ext", "eku=codeSigning"));
            }
            gencertArgs.addAll(List.of("-ext", "ku:critical=digitalSignature"));
            if (spec.sanValue() != null) {
                gencertArgs.addAll(List.of("-ext", "san=" + spec.sanType() + ":" + spec.sanValue()));
            }
            if (spec.rawIssuerExtensionHex() != null) {
                gencertArgs.addAll(List.of("-ext", spec.issuerOid() + "=" + spec.rawIssuerExtensionHex()));
            } else if (spec.issuerOid() != null) {
                gencertArgs.addAll(List.of("-ext", spec.issuerOid() + "=" + issuerExtensionHex(spec)));
            }
            keytool(gencertArgs.toArray(new String[0]));

            keytool("-importcert", "-alias", "leaf", "-file", leafCrt.toString(),
                    "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", storepass, "-noprompt");

            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(keystore)) {
                ks.load(in, storepass.toCharArray());
            }
            X509Certificate rootCert = (X509Certificate) ks.getCertificate("root");
            X509Certificate leafCert = (X509Certificate) ks.getCertificate("leaf");
            PrivateKey leafPrivateKey = (PrivateKey) ks.getKey("leaf", storepass.toCharArray());

            KeyPair logKey = freshEcKeyPair();

            return new Ecosystem(rootCert, leafCert, leafPrivateKey, logKey);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("failed to build the test Sigstore ecosystem", e);
        }
    }

    /// The issuer extension's DER content: a UTF8String TLV for [#ISSUER_OID],
    /// raw ASCII bytes (no TLV) for [#ISSUER_OID_DEPRECATED] — mirrors what
    /// [SignatureVerifier] reads back (spec §3.2 step 8).
    private static String issuerExtensionHex(LeafSpec spec) {
        byte[] ascii = spec.issuerValue().getBytes(StandardCharsets.UTF_8);
        if (ISSUER_OID.equals(spec.issuerOid())) {
            byte[] tlv = new byte[ascii.length + 2];
            tlv[0] = 0x0C;
            tlv[1] = (byte) ascii.length;
            System.arraycopy(ascii, 0, tlv, 2, ascii.length);
            return HexFormat.of().formatHex(tlv);
        }
        return HexFormat.of().formatHex(ascii);
    }

    private static String formatStartDate(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneOffset.UTC).format(instant);
    }

    private static long validityDays(Instant from, Instant until) {
        long seconds = until.getEpochSecond() - from.getEpochSecond();
        // keytool -validity is whole days from -startdate; round up so `until` is always covered
        return Math.max(1, (seconds + 86_399) / 86_400);
    }

    /// `keytool` has no [java.util.spi.ToolProvider] implementation on this
    /// JDK (checked empirically — only `jar`/`javac`/`javap`/… do), so this
    /// runs `$JAVA_HOME/bin/keytool` as the spec's fallback allows (§5).
    /// `java.home`, not the `JAVA_HOME` environment variable, names the
    /// running JVM reliably regardless of how the surefire fork inherited
    /// its environment.
    private static void keytool(String... args) {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path keytoolBin = javaHome.resolve("bin").resolve("keytool");
        List<String> command = new ArrayList<>();
        command.add(keytoolBin.toString());
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException("keytool " + String.join(" ", args) + " failed (" + code + "): " + output);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to run keytool", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running keytool", e);
        }
    }

    // ---- bundle construction: a single-leaf tree, so the audit path is empty and root == leaf hash ----

    static String validBundleJson(Ecosystem eco, byte[] artifactDigest, Instant integratedTime, long entryLogIndex) throws GeneralSecurityException {
        return bundleJson(eco.leafCert(), eco.leafPrivateKey(), eco.logKey(), artifactDigest, artifactDigest, integratedTime, entryLogIndex);
    }

    static String bundleJson(X509Certificate leafCert, PrivateKey leafPrivateKey, KeyPair logKey,
                              byte[] artifactDigest, Instant integratedTime, long entryLogIndex) throws GeneralSecurityException {
        return bundleJson(leafCert, leafPrivateKey, logKey, artifactDigest, artifactDigest, integratedTime, entryLogIndex);
    }

    /// `declaredDigest` is what `messageDigest.digest` claims; `signOverDigest`
    /// is what the leaf key actually signs. Equal for every ordinary bundle —
    /// deliberately different only to build the C5-step-7 broken variant (a
    /// cryptographically wrong signature that is still internally consistent
    /// with everything else).
    static String bundleJson(X509Certificate leafCert, PrivateKey leafPrivateKey, KeyPair logKey,
                              byte[] declaredDigest, byte[] signOverDigest, Instant integratedTime, long entryLogIndex) throws GeneralSecurityException {
        return bundleJson(leafCert, leafPrivateKey, logKey, declaredDigest, signOverDigest,
                HexFormat.of().formatHex(declaredDigest), integratedTime, entryLogIndex);
    }

    /// As above, but `rekorHashValueHex` — the tlog entry's own
    /// `spec.data.hash.value` — can be made to lie about which digest the
    /// entry is for, while everything derived from `canonicalizedBody` (the
    /// signed entry timestamp, the leaf hash, the checkpoint) stays
    /// internally self-consistent with that lie. This isolates the C5-step-3
    /// / C7 broken variant precisely: only the hash-value check (spec §3.2
    /// step 3) can catch it — `signature.content` and `publicKey.content`
    /// still match the bundle exactly.
    static String bundleJson(X509Certificate leafCert, PrivateKey leafPrivateKey, KeyPair logKey,
                              byte[] declaredDigest, byte[] signOverDigest, String rekorHashValueHex,
                              Instant integratedTime, long entryLogIndex) throws GeneralSecurityException {
        return buildBundle(leafCert, leafPrivateKey, logKey, declaredDigest, signOverDigest, rekorHashValueHex,
                null, null, integratedTime, entryLogIndex, 1, 0, null, null, null);
    }

    /// C5-step-3 broken variant, isolating the SIGNATURE binding alone: the
    /// entry's `spec.signature.content` is `overrideSignature`, a value the
    /// bundle's own `messageSignature.signature` (still the real one) does
    /// not match — `hash.value` and `publicKey.content` stay exactly right.
    static String bundleJsonWithEntrySignatureOverride(Ecosystem eco, byte[] artifactDigest, byte[] overrideSignature,
                                                         Instant integratedTime, long entryLogIndex) throws GeneralSecurityException {
        return buildBundle(eco.leafCert(), eco.leafPrivateKey(), eco.logKey(), artifactDigest, artifactDigest,
                HexFormat.of().formatHex(artifactDigest), overrideSignature, null, integratedTime, entryLogIndex,
                1, 0, null, null, null);
    }

    /// C5-step-3 broken variant, isolating the PUBLIC-KEY binding alone: the
    /// entry's `spec.signature.publicKey.content` is `overrideCertDer` (some
    /// other certificate), which the bundle's own leaf certificate (still the
    /// real one) does not match — `hash.value` and `signature.content` stay
    /// exactly right.
    static String bundleJsonWithEntryPublicKeyOverride(Ecosystem eco, byte[] artifactDigest, byte[] overrideCertDer,
                                                         Instant integratedTime, long entryLogIndex) throws GeneralSecurityException {
        return buildBundle(eco.leafCert(), eco.leafPrivateKey(), eco.logKey(), artifactDigest, artifactDigest,
                HexFormat.of().formatHex(artifactDigest), null, overrideCertDer, integratedTime, entryLogIndex,
                1, 0, null, null, null);
    }

    /// A genuine RFC 6962 multi-leaf tree of `treeSize` leaves (`leafIndex`'s
    /// leaf is the real entry; the rest are opaque 32-byte stand-ins — their
    /// content never matters, only their position) — a correct,
    /// self-consistent, VERIFIABLE bundle whose audit path is non-trivial
    /// (spec §3.2 step 5's happy path with `treeSize > 1`).
    static String validBundleJsonWithTree(Ecosystem eco, byte[] artifactDigest, Instant integratedTime,
                                           long entryLogIndex, int treeSize, int leafIndex) throws GeneralSecurityException {
        return buildBundle(eco.leafCert(), eco.leafPrivateKey(), eco.logKey(), artifactDigest, artifactDigest,
                HexFormat.of().formatHex(artifactDigest), null, null, integratedTime, entryLogIndex,
                treeSize, leafIndex, null, null, null);
    }

    /// C5-step-5 broken variant, isolating the CHECKPOINT↔PROOF binding
    /// alone: the inclusion proof is internally self-consistent (its own
    /// `rootHash` really is what the audit path — empty, one-leaf tree —
    /// produces), but the checkpoint that is supposed to vouch for that same
    /// root/size is built from `rootOverride`/`sizeOverride` (`null` = use
    /// the proof's real value) and signed by `checkpointSigningKey` (`null` =
    /// the pinned log key, for the "right key, wrong content" variants; a
    /// different key for the "right content, wrong key" variant).
    static String bundleJsonWithCheckpointOverride(Ecosystem eco, byte[] artifactDigest, Instant integratedTime,
                                                     long entryLogIndex, KeyPair checkpointSigningKey,
                                                     byte[] rootOverride, Long sizeOverride) throws GeneralSecurityException {
        return buildBundle(eco.leafCert(), eco.leafPrivateKey(), eco.logKey(), artifactDigest, artifactDigest,
                HexFormat.of().formatHex(artifactDigest), null, null, integratedTime, entryLogIndex,
                1, 0, checkpointSigningKey, rootOverride, sizeOverride);
    }

    /// A fresh, unpinned P-256 log key — used to build a checkpoint validly
    /// signed by a key the [TrustRoot] never pinned.
    static KeyPair freshEcKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /// The one bundle builder every `bundleJson*`/`validBundleJson*` method
    /// above delegates to — every broken variant is a deliberate divergence
    /// from an otherwise wholly self-consistent bundle, never a shortcut that
    /// skips building a piece for real.
    private static String buildBundle(X509Certificate leafCert, PrivateKey leafPrivateKey, KeyPair logKey,
                                       byte[] declaredDigest, byte[] signOverDigest, String rekorHashValueHex,
                                       byte[] entrySignatureContentOverride, byte[] entryPublicKeyCertDerOverride,
                                       Instant integratedTime, long entryLogIndex,
                                       int treeSize, int leafIndex,
                                       KeyPair checkpointSigningKey, byte[] checkpointRootHashOverride,
                                       Long checkpointTreeSizeOverride) throws GeneralSecurityException {
        byte[] leafDer = certDer(leafCert);
        // NONEwithECDSA cannot init with a non-EC key; for the "non-EC key ⇒ UNSUPPORTED_BUNDLE"
        // test the exact bytes never matter — SignatureVerifier rejects on key type first.
        String signAlgorithm = "EC".equals(leafPrivateKey.getAlgorithm()) ? "NONEwithECDSA" : "SHA256withRSA";
        byte[] signature = sign(signAlgorithm, leafPrivateKey, signOverDigest);

        ObjectNode bodyRoot = Json.MAPPER.createObjectNode();
        bodyRoot.put("apiVersion", "0.0.1");
        bodyRoot.put("kind", "hashedrekord");
        ObjectNode spec = bodyRoot.putObject("spec");
        ObjectNode data = spec.putObject("data");
        ObjectNode hash = data.putObject("hash");
        hash.put("algorithm", "sha256");
        hash.put("value", rekorHashValueHex);
        ObjectNode sigNode = spec.putObject("signature");
        byte[] entrySignatureBytes = entrySignatureContentOverride != null ? entrySignatureContentOverride : signature;
        sigNode.put("content", b64(entrySignatureBytes));
        byte[] entryPublicKeyDer = entryPublicKeyCertDerOverride != null ? entryPublicKeyCertDerOverride : leafDer;
        sigNode.putObject("publicKey").put("content", b64(pem(entryPublicKeyDer)));
        byte[] canonicalizedBody = Json.MAPPER.writeValueAsBytes(bodyRoot);
        String canonicalizedBodyB64 = b64(canonicalizedBody);

        byte[] logSpki = logKey.getPublic().getEncoded();
        byte[] logIdBytes = sha256(logSpki);
        byte[] realLeafHash = sha256(concat(new byte[]{0x00}, canonicalizedBody));

        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < treeSize; i++) {
            leaves.add(i == leafIndex ? realLeafHash : randomBytes(32));
        }
        byte[] rootHash = merkleRoot(leaves, 0, treeSize);
        List<byte[]> proofHashes = treeSize == 1 ? List.of() : auditPath(leaves, leafIndex, 0, treeSize);
        long proofTreeSize = treeSize;
        long proofLogIndex = leafIndex;

        KeyPair signerForCheckpoint = checkpointSigningKey != null ? checkpointSigningKey : logKey;
        byte[] checkpointRoot = checkpointRootHashOverride != null ? checkpointRootHashOverride : rootHash;
        long checkpointSize = checkpointTreeSizeOverride != null ? checkpointTreeSizeOverride : proofTreeSize;
        String checkpointBody = LOG_ORIGIN + "\n" + checkpointSize + "\n" + b64(checkpointRoot) + "\n";
        byte[] checkpointSig = sign("SHA256withECDSA", signerForCheckpoint.getPrivate(), checkpointBody.getBytes(StandardCharsets.UTF_8));
        byte[] sigLine = concat(new byte[]{0, 0, 0, 0}, checkpointSig);
        String checkpointEnvelope = checkpointBody + "\n— test.rekor.local " + b64(sigLine) + "\n";

        String canonicalSet = "{\"body\":\"" + canonicalizedBodyB64 + "\",\"integratedTime\":" + integratedTime.getEpochSecond()
                + ",\"logID\":\"" + HexFormat.of().formatHex(logIdBytes) + "\",\"logIndex\":" + entryLogIndex + "}";
        byte[] setSig = sign("SHA256withECDSA", logKey.getPrivate(), canonicalSet.getBytes(StandardCharsets.UTF_8));

        ObjectNode bundle = Json.MAPPER.createObjectNode();
        bundle.put("mediaType", "application/vnd.dev.sigstore.bundle.v0.3+json");
        ObjectNode vm = bundle.putObject("verificationMaterial");
        vm.putObject("certificate").put("rawBytes", b64(leafDer));
        ArrayNode tlogEntries = vm.putArray("tlogEntries");
        ObjectNode entryNode = Json.MAPPER.createObjectNode();
        tlogEntries.add(entryNode);
        entryNode.put("logIndex", String.valueOf(entryLogIndex));
        entryNode.putObject("logId").put("keyId", b64(logIdBytes));
        ObjectNode kv = entryNode.putObject("kindVersion");
        kv.put("kind", "hashedrekord");
        kv.put("version", "0.0.1");
        entryNode.put("integratedTime", String.valueOf(integratedTime.getEpochSecond()));
        entryNode.putObject("inclusionPromise").put("signedEntryTimestamp", b64(setSig));
        ObjectNode proof = entryNode.putObject("inclusionProof");
        proof.put("logIndex", String.valueOf(proofLogIndex));
        proof.put("rootHash", b64(rootHash));
        proof.put("treeSize", String.valueOf(proofTreeSize));
        ArrayNode hashesNode = proof.putArray("hashes");
        for (byte[] h : proofHashes) {
            hashesNode.add(b64(h));
        }
        proof.putObject("checkpoint").put("envelope", checkpointEnvelope);
        entryNode.put("canonicalizedBody", canonicalizedBodyB64);
        ObjectNode msgSig = bundle.putObject("messageSignature");
        ObjectNode md = msgSig.putObject("messageDigest");
        md.put("algorithm", "SHA2_256");
        md.put("digest", b64(declaredDigest));
        msgSig.put("signature", b64(signature));

        return Json.MAPPER.writeValueAsString(bundle);
    }

    // ---- RFC 6962 Merkle helpers — mirror SignatureVerifier's client-side
    // audit-path algorithm exactly (verified by hand against RFC 6962 §2.1.1's
    // recursive MTH/PATH definitions) so a test tree really round-trips ----

    private static byte[] merkleRoot(List<byte[]> leaves, int lo, int hi) {
        if (hi - lo == 1) {
            return leaves.get(lo);
        }
        int k = Integer.highestOneBit(hi - lo - 1);
        byte[] left = merkleRoot(leaves, lo, lo + k);
        byte[] right = merkleRoot(leaves, lo + k, hi);
        return hashChildren(left, right);
    }

    private static List<byte[]> auditPath(List<byte[]> leaves, int m, int lo, int hi) {
        if (hi - lo == 1) {
            return new ArrayList<>();
        }
        int k = Integer.highestOneBit(hi - lo - 1);
        List<byte[]> path = new ArrayList<>();
        if (m - lo < k) {
            path.addAll(auditPath(leaves, m, lo, lo + k));
            path.add(merkleRoot(leaves, lo + k, hi));
        } else {
            path.addAll(auditPath(leaves, m, lo + k, hi));
            path.add(merkleRoot(leaves, lo, lo + k));
        }
        return path;
    }

    private static byte[] hashChildren(byte[] left, byte[] right) {
        return sha256(concat(new byte[]{0x01}, concat(left, right)));
    }

    private static byte[] certDer(X509Certificate cert) {
        try {
            return cert.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pem(byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        String text = "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sign(String algorithm, PrivateKey key, byte[] message) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(key);
        signature.update(message);
        return signature.sign();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
