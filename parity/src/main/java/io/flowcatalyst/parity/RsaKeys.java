package io.flowcatalyst.parity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/// The RSA-2048 PKCS#8 signing key generated once per harness run
/// (parity-harness spec §2): the same PEM file path is handed to both sides
/// as `FC_JWT_SIGNING_KEY_PATH`, so they mint tokens under the same `kid` and
/// JWKS.
public final class RsaKeys {

    private RsaKeys() {
    }

    /// Generates a fresh RSA-2048 key pair and writes its PKCS#8 private key
    /// as a standard, 64-column PEM to `path`.
    public static void generatePkcs8Pem(Path path) {
        generatePkcs8Pem(path, null);
    }

    /// As [#generatePkcs8Pem(Path)], and — when `publicPath` is non-null —
    /// also writes the matching X.509 SubjectPublicKeyInfo public key PEM
    /// alongside it (L1 lane, `docs/java-parity-plan.md` §3: Rust's
    /// `FC_JWT_PUBLIC_KEY_PATH` needs its own file pre-L0, before
    /// `FC_JWT_SIGNING_KEY_PATH` derives the public key itself).
    public static void generatePkcs8Pem(Path path, Path publicPath) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(path, pem);

            if (publicPath != null) {
                String publicBase64 = Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(pair.getPublic().getEncoded());
                String publicPem = "-----BEGIN PUBLIC KEY-----\n" + publicBase64 + "\n-----END PUBLIC KEY-----\n";
                Files.writeString(publicPath, publicPem);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("write " + path, e);
        }
    }
}
