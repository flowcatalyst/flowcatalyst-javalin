package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.server.Env;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class SigningKeysTest {

    private static KeyPair pair;
    private static String pkcs8Pem;   // "PRIVATE KEY"
    private static String pkcs1Pem;   // "RSA PRIVATE KEY"
    private static String pkixPubPem; // "PUBLIC KEY"

    @BeforeAll
    static void keys() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        pair = gen.generateKeyPair();
        pkcs8Pem = SigningKeys.encodePem("PRIVATE KEY", pair.getPrivate().getEncoded());
        pkcs1Pem = SigningKeys.encodePem("RSA PRIVATE KEY", SigningKeys.Der.pkcs8ToPkcs1(pair.getPrivate().getEncoded()));
        pkixPubPem = SigningKeys.publicKeyPem((RSAPublicKey) pair.getPublic());
    }

    private static Env envWith(Map<String, String> m) {
        return Env.load(m);
    }

    @Test
    void loadsInlinePkcs8PemFromFlowcatalystVar() {
        var env = Map.of("FLOWCATALYST_JWT_PRIVATE_KEY", pkcs8Pem);
        var keys = SigningKeys.load(envWith(env), env);

        assertThat(keys.ephemeral()).isFalse();
        assertThat(keys.privateKey().getModulus()).isEqualTo(((RSAPrivateCrtKey) pair.getPrivate()).getModulus());
        assertThat(keys.publicKey()).isEqualTo(pair.getPublic());
        assertThat(keys.rotation()).isInstanceOf(SigningKeys.KeyRotation.Single.class);
        assertThat(keys.rotation().verificationKeys()).containsExactly(keys.current());
        assertThat(keys.privateKeyPem()).isEqualTo(pkcs8Pem.strip());
        assertThat(keys.current().pem()).isEqualTo(pkixPubPem);
    }

    @Test
    void loadsPkcs1Pem() {
        var keys = SigningKeys.fromPem(pkcs1Pem, null);
        assertThat(keys.publicKey()).isEqualTo(pair.getPublic());
        assertThat(keys.privateKey().getModulus()).isEqualTo(((RSAPrivateCrtKey) pair.getPrivate()).getModulus());
    }

    @Test
    void flowcatalystVarTakesPrecedenceOverFcAlias() throws Exception {
        var other = KeyPairGenerator.getInstance("RSA");
        other.initialize(2048);
        var otherPem = SigningKeys.encodePem("PRIVATE KEY", other.generateKeyPair().getPrivate().getEncoded());
        var env = Map.of("FLOWCATALYST_JWT_PRIVATE_KEY", pkcs8Pem, "FC_JWT_SIGNING_KEY_PEM", otherPem);
        assertThat(SigningKeys.load(envWith(env), env).publicKey()).isEqualTo(pair.getPublic());

        var aliasOnly = Map.of("FC_JWT_SIGNING_KEY_PEM", otherPem);
        assertThat(SigningKeys.load(envWith(aliasOnly), aliasOnly).publicKey()).isNotEqualTo(pair.getPublic());
    }

    @Test
    void keyPathWinsOverInlinePemAndUnreadablePathFallsThrough(@TempDir Path dir) throws Exception {
        var file = dir.resolve("jwt.pem");
        Files.writeString(file, pkcs1Pem);
        var env = Map.of("FC_JWT_SIGNING_KEY_PATH", file.toString(), "FLOWCATALYST_JWT_PRIVATE_KEY", "garbage");
        assertThat(SigningKeys.load(envWith(env), env).publicKey()).isEqualTo(pair.getPublic());

        var missing = Map.of("FC_JWT_SIGNING_KEY_PATH", dir.resolve("missing.pem").toString(), "FLOWCATALYST_JWT_PRIVATE_KEY", pkcs8Pem);
        assertThat(SigningKeys.load(envWith(missing), missing).publicKey()).isEqualTo(pair.getPublic());
    }

    @Test
    void mangledPemIsRepaired() {
        var clean = pkcs8Pem.strip();
        var escaped = clean.replace("\n", "\\n");
        assertThatThrownBy(() -> SigningKeys.parsePrivateKey(escaped)).as("precondition: escaped PEM does not parse").isInstanceOf(IllegalArgumentException.class);

        assertThat(SigningKeys.normalizePem(clean)).isEqualTo(clean);
        assertThat(SigningKeys.normalizePem(escaped)).isEqualTo(clean);
        assertThat(SigningKeys.normalizePem(clean.replace("\n", "\\r\\n"))).isEqualTo(clean);
        assertThat(SigningKeys.normalizePem("\"" + clean + "\"")).isEqualTo(clean);
        assertThat(SigningKeys.normalizePem("  \"" + escaped + "\"\n")).isEqualTo(clean);
        var b64 = Base64.getEncoder().encodeToString(clean.getBytes(StandardCharsets.UTF_8));
        assertThat(SigningKeys.normalizePem(b64)).isEqualTo(clean);
        assertThat(SigningKeys.normalizePem("not a pem at all")).isEqualTo("not a pem at all");
        assertThat(SigningKeys.normalizePem(null)).isEmpty();

        var env = Map.of("FLOWCATALYST_JWT_PRIVATE_KEY", "\"" + escaped + "\"");
        assertThat(SigningKeys.load(envWith(env), env).publicKey()).isEqualTo(pair.getPublic());
    }

    @Test
    void ephemeralFallbackWhenNothingConfigured() throws Exception {
        var keys = SigningKeys.load(envWith(Map.of()), Map.of());
        assertThat(keys.ephemeral()).isTrue();
        assertThat(keys.privateKey().getModulus().bitLength()).isEqualTo(2048);
        assertThat(keys.privateKeyPem()).startsWith("-----BEGIN RSA PRIVATE KEY-----\n").endsWith("-----END RSA PRIVATE KEY-----\n");
        assertThat(keys.rotation()).isInstanceOf(SigningKeys.KeyRotation.Single.class);
        assertThat(keys.kid()).hasSize(22);

        // a second load mints a different key — that's the point of the warning
        assertThat(SigningKeys.load(envWith(Map.of()), Map.of()).publicKey()).isNotEqualTo(keys.publicKey());

        // the pair actually works for RS256
        var sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keys.privateKey());
        sig.update("hello".getBytes(StandardCharsets.UTF_8));
        var signed = sig.sign();
        sig.initVerify(keys.publicKey());
        sig.update("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(sig.verify(signed)).isTrue();
    }

    @Test
    void configuredButUnparseableKeyIsFatal() {
        var env = Map.of("FLOWCATALYST_JWT_PRIVATE_KEY", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----");
        assertThatThrownBy(() -> SigningKeys.load(envWith(env), env)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningKeys.parsePrivateKey("no pem here")).hasMessageContaining("no PEM block found");
    }

    @Test
    void previousPublicKeyForRotation() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var previous = gen.generateKeyPair();
        var prevPem = SigningKeys.publicKeyPem((RSAPublicKey) previous.getPublic());
        var mangledPrev = "\"" + prevPem.strip().replace("\n", "\\n") + "\"";

        var env = Map.of("FLOWCATALYST_JWT_PRIVATE_KEY", pkcs8Pem, "FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY", mangledPrev);
        var keys = SigningKeys.load(envWith(env), env);
        var rotating = assertThat(keys.rotation()).asInstanceOf(type(SigningKeys.KeyRotation.Rotating.class)).actual();
        assertThat(rotating.current()).isEqualTo(keys.current());
        assertThat(rotating.previous().publicKey()).isEqualTo(previous.getPublic());
        // kid of the previous key hashes the normalized env text as supplied (no trailing newline)
        assertThat(rotating.previous().kid()).isEqualTo(SigningKeys.keyId(prevPem.strip()));
        assertThat(rotating.previous().kid()).isNotEqualTo(keys.kid());
        // verifiers and JWKS see current first, then previous
        assertThat(keys.rotation().verificationKeys()).containsExactly(keys.current(), rotating.previous());

        // junk is dropped silently by Env
        var junk = Map.of("FLOWCATALYST_JWT_PRIVATE_KEY", pkcs8Pem, "FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY", "definitely-not-pem");
        assertThat(SigningKeys.load(envWith(junk), junk).rotation()).isInstanceOf(SigningKeys.KeyRotation.Single.class);

        // something that looks like a PEM but isn't one is fatal (Go: "load previous RSA key")
        var bad = Map.of("FLOWCATALYST_JWT_PRIVATE_KEY", pkcs8Pem, "FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----");
        assertThatThrownBy(() -> SigningKeys.load(envWith(bad), bad)).hasMessageContaining("previous RSA key");
    }

    @Test
    void parsesPkcs1PublicKey() {
        // PKCS#1 RSAPublicKey = SEQUENCE { INTEGER n, INTEGER e }
        var pub = (RSAPublicKey) pair.getPublic();
        var n = SigningKeys.Der.tlv(SigningKeys.Der.INTEGER, pub.getModulus().toByteArray());
        var e = SigningKeys.Der.tlv(SigningKeys.Der.INTEGER, pub.getPublicExponent().toByteArray());
        var seq = SigningKeys.Der.tlv(SigningKeys.Der.SEQUENCE, concat(n, e));
        var pem = SigningKeys.encodePem("RSA PUBLIC KEY", seq);
        assertThat(SigningKeys.parsePublicKey(pem)).isEqualTo(pub);
        assertThat(SigningKeys.parsePublicKey(pkixPubPem)).isEqualTo(pub);
        assertThatThrownBy(() -> SigningKeys.parsePublicKey("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"))
                .hasMessageContaining("unparseable RSA public key");
    }

    @Test
    void kidIsBase64UrlOfFirst16Sha256BytesOfThePublicPem() throws Exception {
        var keys = SigningKeys.fromPem(pkcs8Pem, null);
        var digest = MessageDigest.getInstance("SHA-256").digest(pkixPubPem.getBytes(StandardCharsets.UTF_8));
        var expected = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
        assertThat(keys.kid()).isEqualTo(expected).hasSize(22).doesNotContain("=", "+", "/");
        // the PEM the kid is hashed over has Go's pem.EncodeToMemory shape
        assertThat(pkixPubPem).startsWith("-----BEGIN PUBLIC KEY-----\n").endsWith("-----END PUBLIC KEY-----\n");
        assertThat(pkixPubPem.lines().filter(l -> !l.startsWith("-----")).mapToInt(String::length).max().orElse(0)).isEqualTo(64);
    }

    @Test
    void pemBlockToleratesSurroundingNoiseAndHeaders() {
        var der = SigningKeys.pemBlock("garbage before\n" + pkcs8Pem + "garbage after");
        assertThat(der).isEqualTo(pair.getPrivate().getEncoded());
        var withHeaders = pkcs8Pem.replace("-----BEGIN PRIVATE KEY-----\n", "-----BEGIN PRIVATE KEY-----\nProc-Type: 4,PLAIN\n\n");
        assertThat(SigningKeys.pemBlock(withHeaders)).isEqualTo(pair.getPrivate().getEncoded());
    }

    @Test
    void ensureSigningKeyFileWritesOnceWith0600(@TempDir Path dir) throws Exception {
        var path = dir.resolve("nested").resolve("jwt.pem");
        var out = SigningKeys.ensureSigningKeyFile(path);
        assertThat(out).isEqualTo(path);
        assertThat(Files.readString(path)).startsWith("-----BEGIN RSA PRIVATE KEY-----");
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(path)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
        var first = Files.readString(path);
        assertThat(SigningKeys.ensureSigningKeyFile(path)).isEqualTo(path);
        assertThat(Files.readString(path)).as("existing non-empty file is kept").isEqualTo(first);

        // the generated file round-trips through the loader
        var env = Map.of("FC_JWT_SIGNING_KEY_PATH", path.toString());
        var keys = SigningKeys.load(envWith(env), env);
        assertThat(keys.ephemeral()).isFalse();
        assertThat(keys.privateKeyPem()).isEqualTo(first);

        assertThatThrownBy(() -> SigningKeys.ensureSigningKeyFile(Path.of(""))).hasMessageContaining("signing key path is empty");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
