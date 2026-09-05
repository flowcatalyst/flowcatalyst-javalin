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
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(path, pem);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("write " + path, e);
        }
    }
}
