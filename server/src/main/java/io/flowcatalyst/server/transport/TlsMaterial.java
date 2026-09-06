package io.flowcatalyst.server.transport;

import io.flowcatalyst.server.Env;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/// TLS material for the server's TLS listener (`docs/spec/http-transport.md`
/// §2): exactly one of a PKCS#12 keystore (`FC_TLS_KEYSTORE_PATH` +
/// `FC_TLS_KEYSTORE_PASSWORD`) or a PEM certificate+key pair
/// (`FC_TLS_CERT_PATH` + `FC_TLS_KEY_PATH`) may be configured — both forms
/// converge on an in-memory [KeyStore] handed to `SslContextFactory.Server`
/// ([Listeners]). [#resolve] throws a startup error (never a listener that
/// silently stays HTTP/1.1) for a malformed pair, a partial pair, or both
/// forms set at once.
public sealed interface TlsMaterial permits TlsMaterial.Keystore, TlsMaterial.Pem {

    KeyStore keyStore();

    char[] keyPassword();

    /// The `FC_TLS_KEYSTORE_PATH` / `FC_TLS_KEYSTORE_PASSWORD` form: a
    /// PKCS#12 file read as-is.
    record Keystore(String path, KeyStore keyStore, char[] keyPassword) implements TlsMaterial {
    }

    /// The `FC_TLS_CERT_PATH` / `FC_TLS_KEY_PATH` form: a leaf-first PEM
    /// certificate chain and an unencrypted PKCS#8 private key, assembled
    /// into an in-memory PKCS#12 [KeyStore] that never touches disk.
    record Pem(String certPath, String keyPath, KeyStore keyStore, char[] keyPassword) implements TlsMaterial {
    }

    /// Every [Pem] gets its own copy of this array as its [#keyPassword] (see
    /// [#loadPem]) — the key material never leaves this process and the
    /// KeyStore itself is never persisted, so the password only has to
    /// satisfy the KeyStore API, not protect anything.
    String PEM_KEY_ALIAS = "fc";

    /// `Optional.empty()` when neither form is configured — the TLS listener
    /// (and HTTP/3, which needs it) stays off. Throws `IllegalStateException`
    /// for every other invalid combination: a half-set pair, both forms set,
    /// a file that will not parse, or `FC_HTTP3_ENABLED=true` with no
    /// material at all.
    static Optional<TlsMaterial> resolve(Env env) {
        boolean keystorePathSet = !env.tlsKeystorePath().isBlank();
        boolean keystorePasswordSet = !env.tlsKeystorePassword().isBlank();
        boolean certSet = !env.tlsCertPath().isBlank();
        boolean keySet = !env.tlsKeyPath().isBlank();

        if (keystorePathSet != keystorePasswordSet) {
            throw new IllegalStateException(
                    "FC_TLS_KEYSTORE_PATH and FC_TLS_KEYSTORE_PASSWORD must both be set, or neither");
        }
        if (certSet != keySet) {
            throw new IllegalStateException("FC_TLS_CERT_PATH and FC_TLS_KEY_PATH must both be set, or neither");
        }
        boolean keystoreForm = keystorePathSet && keystorePasswordSet;
        boolean pemForm = certSet && keySet;
        if (keystoreForm && pemForm) {
            throw new IllegalStateException(
                    "set exactly one of FC_TLS_KEYSTORE_PATH or FC_TLS_CERT_PATH/FC_TLS_KEY_PATH, not both");
        }
        if (keystoreForm) {
            return Optional.of(loadKeystore(env.tlsKeystorePath(), env.tlsKeystorePassword()));
        }
        if (pemForm) {
            return Optional.of(loadPem(env.tlsCertPath(), env.tlsKeyPath()));
        }
        if (env.http3Enabled()) {
            throw new IllegalStateException("HTTP/3 needs a certificate "
                    + "(FC_HTTP3_ENABLED=true but no FC_TLS_KEYSTORE_PATH/FC_TLS_CERT_PATH is configured)");
        }
        return Optional.empty();
    }

    private static Keystore loadKeystore(String path, String password) {
        try {
            var ks = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(Path.of(path))) {
                ks.load(in, password.toCharArray());
            }
            return new Keystore(path, ks, password.toCharArray());
        } catch (Exception e) {
            throw new IllegalStateException("FC_TLS_KEYSTORE_PATH " + path + ": " + e.getMessage(), e);
        }
    }

    private static Pem loadPem(String certPath, String keyPath) {
        List<? extends Certificate> chain;
        try (InputStream in = Files.newInputStream(Path.of(certPath))) {
            chain = List.copyOf(CertificateFactory.getInstance("X.509").generateCertificates(in));
            if (chain.isEmpty()) {
                throw new IllegalStateException("FC_TLS_CERT_PATH " + certPath + ": not a PEM certificate");
            }
        } catch (IOException e) {
            throw new IllegalStateException("FC_TLS_CERT_PATH " + certPath + ": " + e.getMessage(), e);
        } catch (CertificateException e) {
            throw new IllegalStateException("FC_TLS_CERT_PATH " + certPath + ": not a PEM certificate", e);
        }

        var key = loadPrivateKey(keyPath);
        var keyPassword = PEM_KEY_ALIAS.toCharArray();
        try {
            var ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(PEM_KEY_ALIAS, key, keyPassword, chain.toArray(new Certificate[0]));
            return new Pem(certPath, keyPath, ks, keyPassword);
        } catch (Exception e) {
            throw new IllegalStateException("FC_TLS_CERT_PATH " + certPath + ": " + e.getMessage(), e);
        }
    }

    /// Base64-decodes the PEM body and tries RSA, then EC, then Ed25519
    /// (`docs/spec/http-transport.md` §2) — a PKCS8 key carries no algorithm
    /// hint in the file itself, so [KeyFactory] has to be asked in order
    /// until one accepts the encoding.
    private static PrivateKey loadPrivateKey(String keyPath) {
        String pem;
        try {
            pem = Files.readString(Path.of(keyPath), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("FC_TLS_KEY_PATH " + keyPath + ": " + e.getMessage(), e);
        }
        var base64 = pem.lines()
                .filter(line -> !line.startsWith("-----"))
                .reduce("", String::concat);
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("FC_TLS_KEY_PATH " + keyPath + ": not a PKCS8 private key", e);
        }
        if (der.length == 0) {
            throw new IllegalStateException("FC_TLS_KEY_PATH " + keyPath + ": not a PKCS8 private key");
        }
        var spec = new PKCS8EncodedKeySpec(der);
        for (var algorithm : List.of("RSA", "EC", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (InvalidKeySpecException | NoSuchAlgorithmException ignored) {
                // try the next algorithm
            }
        }
        throw new IllegalStateException("FC_TLS_KEY_PATH " + keyPath + ": not a PKCS8 private key");
    }
}
