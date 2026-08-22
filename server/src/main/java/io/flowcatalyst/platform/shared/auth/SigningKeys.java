package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.EnvReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// The JWT signing key material, resolved once at boot — the Java reading of
/// `internal/server/signing_key.go` (`LoadSigningKeyOrEphemeral`,
/// `NormalizePEM`, `EnsureSigningKeyFile`) together with the PEM parsing and
/// `kid` derivation from `internal/platform/auth/authservice/authservice.go`
/// (`parseRSAPrivateKey`, `parseRSAPublicKey`, `publicPEMFromPrivatePEM`,
/// `generateKeyID`). Only `java.security` is used: PKCS#1 ⇄ PKCS#8 is a
/// twenty-line ASN.1 walk, not a dependency.
///
/// Resolution order of the **private key** ([#load(Env, Map)]):
///
///   1. `FC_JWT_SIGNING_KEY_PATH` — read from disk if set (an unreadable file
///      logs a warning and falls through);
///   2. inline PEM from the environment — `FLOWCATALYST_JWT_PRIVATE_KEY` (the
///      name the deploy IaC uses; it wins) then `FC_JWT_SIGNING_KEY_PEM`;
///      both go through [#normalizePem(String)] to repair SSM/ECS mangling;
///   3. otherwise an **ephemeral** RSA-2048 key is generated with a loud
///      warning — fine for dev and first-boot smoke tests, but tokens die on
///      restart and replicas reject each other's tokens. Production must
///      supply (1) or (2).
///
/// A configured key that does not parse is fatal ([IllegalArgumentException]),
/// as in Go's `authservice.New`: silently downgrading would turn a
/// misconfiguration into forgeable tokens.
///
/// The **previous public key** (`FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY`,
/// validation-only, for zero-downtime rotation) arrives pre-normalized on
/// [Env#jwtPreviousPublicKey()] — already `""` when unset or not a real PEM,
/// so a junk value never stops boot; a value that *looks* like a PEM but does
/// not parse is fatal, again as in Go.
///
/// **`kid`**: Go computes it in `authservice.go`, not `signing_key.go` —
/// `base64url-nopad( sha256( publicKeyPEM )[0:16] )` over the *PEM text* of
/// the public key. For the current key that PEM is the PKIX `PUBLIC KEY`
/// block Go derives from the private key (`x509.MarshalPKIXPublicKey` +
/// `pem.EncodeToMemory`: 64-column base64, trailing newline) —
/// [#publicKeyPem(RSAPublicKey)] reproduces that byte-for-byte; for the
/// previous key it is the normalized env string as supplied. [#kid()] and
/// [PublicKeyEntry#kid()] apply exactly that, so JWKS key ids match the Go
/// platform's for the same key material.
///
/// @param privateKey    the RS256 signing key
/// @param privateKeyPem the PEM the key was loaded from (or generated as) — what Go's
///                      `LoadSigningKeyOrEphemeral` returns; downstream issuers (MFA tokens) take it
/// @param current       the matching public key with its Go-format PEM and kid
/// @param previous      the validation-only previous public key, when configured
/// @param ephemeral     true when no key was configured and one was minted at boot
public record SigningKeys(
        RSAPrivateKey privateKey,
        String privateKeyPem,
        PublicKeyEntry current,
        Optional<PublicKeyEntry> previous,
        boolean ephemeral
) {

    private static final Logger LOG = LoggerFactory.getLogger(SigningKeys.class);

    /// The environment variables carrying an inline private-key PEM, in priority order.
    private static final List<String> INLINE_PEM_VARS = List.of("FLOWCATALYST_JWT_PRIVATE_KEY", "FC_JWT_SIGNING_KEY_PEM");

    public SigningKeys {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(privateKeyPem, "privateKeyPem");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previous, "previous");
    }

    /// A verification key with the PEM text its `kid` is derived from.
    public record PublicKeyEntry(RSAPublicKey publicKey, String pem) {
        public PublicKeyEntry {
            Objects.requireNonNull(publicKey, "publicKey");
            Objects.requireNonNull(pem, "pem");
        }

        /// `generateKeyID(pem)`.
        public String kid() {
            return keyId(pem);
        }
    }

    public RSAPublicKey publicKey() {
        return current.publicKey();
    }

    /// The current key's JWKS `kid`.
    public String kid() {
        return current.kid();
    }

    // ── loading ──────────────────────────────────────────────────────────

    /// Resolve against the process environment.
    public static SigningKeys load(Env env) {
        return load(env, System.getenv());
    }

    /// `LoadSigningKeyOrEphemeral(cfg.JWTSigningKeyPath)` + previous-key wiring,
    /// reading the inline PEM variables from `environment`.
    public static SigningKeys load(Env env, Map<String, String> environment) {
        var reader = new EnvReader(environment);
        var path = env.jwtSigningKeyPath();
        if (!path.isEmpty()) {
            try {
                var pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
                return fromPem(pem, env.jwtPreviousPublicKey());
            } catch (IOException e) {
                LOG.warn("FC_JWT_SIGNING_KEY_PATH unreadable, falling back: {}", e.toString());
            }
        }
        // FLOWCATALYST_JWT_PRIVATE_KEY first: it's the deployed signing key, so reading it
        // keeps RS256 tokens validating against the same keypair (and stops the silent
        // ephemeral-key fallback that mints tokens no other replica can verify).
        for (var var : INLINE_PEM_VARS) {
            var pem = reader.get(var);
            if (!pem.isEmpty()) {
                return fromPem(normalizePem(pem), env.jwtPreviousPublicKey());
            }
        }
        LOG.warn("no JWT signing key configured — generating ephemeral RSA key (tokens won't survive restart)");
        return generateEphemeral(env.jwtPreviousPublicKey());
    }

    /// Build from PEM text: the private key (PKCS#1 `RSA PRIVATE KEY` or
    /// PKCS#8 `PRIVATE KEY`) and an optional previous public key (PKIX
    /// `PUBLIC KEY` or PKCS#1 `RSA PUBLIC KEY`; `null`/blank = none).
    public static SigningKeys fromPem(String privateKeyPem, String previousPublicKeyPem) {
        return build(privateKeyPem, previousPublicKeyPem, false);
    }

    /// A freshly minted RSA-2048 key, marked ephemeral.
    public static SigningKeys generateEphemeral() {
        return generateEphemeral("");
    }

    static SigningKeys generateEphemeral(String previousPublicKeyPem) {
        return build(generateRsaPem(), previousPublicKeyPem, true);
    }

    private static SigningKeys build(String privateKeyPem, String previousPublicKeyPem, boolean ephemeral) {
        var priv = parsePrivateKey(privateKeyPem);
        var pub = publicKeyOf(priv);
        var current = new PublicKeyEntry(pub, publicKeyPem(pub));
        var previous = Optional.ofNullable(previousPublicKeyPem)
                .filter(s -> !s.isBlank())
                .map(pem -> {
                    try {
                        return new PublicKeyEntry(parsePublicKey(pem), pem);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("load previous RSA key: invalid previous RSA public key: " + e.getMessage(), e);
                    }
                });
        return new SigningKeys(priv, privateKeyPem, current, previous, ephemeral);
    }

    // ── PEM normalization ────────────────────────────────────────────────

    /// `NormalizePEM`: repair the common ways a PEM gets mangled when carried in
    /// an environment variable (AWS SSM / Secrets Manager → ECS task def):
    ///
    ///   - literal `\n` (and `\r\n`) escape sequences instead of real newlines;
    ///   - surrounding double quotes;
    ///   - the whole PEM base64-encoded.
    ///
    /// A value that is already a clean PEM passes through unchanged (trimmed).
    public static String normalizePem(String s) {
        if (s == null) return "";
        s = s.strip();
        s = trimChar(s, '"');
        if (s.contains("\\n")) {
            s = s.replace("\\r\\n", "\n").replace("\\n", "\n");
        }
        if (!s.contains("-----BEGIN")) {
            try {
                var decoded = new String(Base64.getDecoder().decode(s.strip()), StandardCharsets.UTF_8);
                if (decoded.contains("-----BEGIN")) return decoded;
            } catch (IllegalArgumentException _) {
                // not base64 — fall through with the value as-is
            }
        }
        return s;
    }

    /// Go's `strings.Trim(s, cutset)` for a single character.
    private static String trimChar(String s, char c) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == c) start++;
        while (end > start && s.charAt(end - 1) == c) end--;
        return s.substring(start, end);
    }

    // ── PEM / DER parsing ────────────────────────────────────────────────

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([^\\r\\n-]*)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    /// `pem.Decode`: the first PEM block's DER bytes, regardless of label;
    /// absent → [IllegalArgumentException] "no PEM block found".
    static byte[] pemBlock(String pem) {
        var m = PEM_BLOCK.matcher(pem);
        if (!m.find()) throw new IllegalArgumentException("no PEM block found");
        var body = m.group(2);
        // Tolerate RFC 1421 headers ("Proc-Type: ...") before the blank line, as Go does.
        var blank = body.indexOf("\n\n");
        if (blank >= 0 && body.substring(0, blank).contains(":")) body = body.substring(blank + 2);
        try {
            return Base64.getMimeDecoder().decode(body.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("no PEM block found: " + e.getMessage(), e);
        }
    }

    /// `parseRSAPrivateKey`: PKCS#1 first, then PKCS#8; a non-RSA PKCS#8 key is
    /// rejected.
    public static RSAPrivateKey parsePrivateKey(String pem) {
        var der = pemBlock(pem);
        KeyFactory kf;
        try {
            kf = KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        // PKCS#1 RSAPrivateKey: SEQUENCE { INTEGER version, INTEGER n, INTEGER e, ... }
        // → wrap into PKCS#8 PrivateKeyInfo so the JDK can read it.
        if (looksLikePkcs1Private(der)) {
            try {
                return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Der.pkcs1ToPkcs8(der)));
            } catch (InvalidKeySpecException | ClassCastException | IllegalArgumentException _) {
                // fall through to PKCS#8
            }
        }
        try {
            var key = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (key instanceof RSAPrivateKey rsa) return rsa;
            throw new IllegalArgumentException("private key is not RSA");
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("parse pkcs8: " + e.getMessage(), e);
        }
    }

    /// `parseRSAPublicKey`: PKIX (`SubjectPublicKeyInfo`) first, then PKCS#1
    /// `RSAPublicKey`.
    public static RSAPublicKey parsePublicKey(String pem) {
        var der = pemBlock(pem);
        KeyFactory kf;
        try {
            kf = KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try {
            var key = kf.generatePublic(new X509EncodedKeySpec(der));
            if (key instanceof RSAPublicKey rsa) return rsa;
            throw new IllegalArgumentException("public key is not RSA");
        } catch (InvalidKeySpecException _) {
            // not PKIX — try PKCS#1
        }
        try {
            var seq = Der.read(der, 0);
            if (seq.tag() != Der.SEQUENCE) throw new IllegalArgumentException("unparseable RSA public key");
            var n = Der.read(seq.content(), 0);
            var e = Der.read(seq.content(), n.end());
            if (n.tag() != Der.INTEGER || e.tag() != Der.INTEGER) throw new IllegalArgumentException("unparseable RSA public key");
            return (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(new BigInteger(n.content()), new BigInteger(e.content())));
        } catch (InvalidKeySpecException | ClassCastException | IllegalArgumentException | IndexOutOfBoundsException _) {
            throw new IllegalArgumentException("unparseable RSA public key");
        }
    }

    private static boolean looksLikePkcs1Private(byte[] der) {
        try {
            var seq = Der.read(der, 0);
            if (seq.tag() != Der.SEQUENCE) return false;
            var version = Der.read(seq.content(), 0);
            var second = Der.read(seq.content(), version.end());
            // PKCS#8 has a SEQUENCE (AlgorithmIdentifier) second; PKCS#1 has INTEGER n.
            return version.tag() == Der.INTEGER && second.tag() == Der.INTEGER;
        } catch (IllegalArgumentException | IndexOutOfBoundsException _) {
            return false;
        }
    }

    /// The public half of an RSA private key (`&priv.PublicKey` in Go).
    public static RSAPublicKey publicKeyOf(RSAPrivateKey priv) {
        if (!(priv instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalArgumentException("derive RSA public key from private key: key carries no public exponent");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── PEM / DER encoding ───────────────────────────────────────────────

    /// `publicPEMFromPrivatePEM`'s output format: `x509.MarshalPKIXPublicKey`
    /// wrapped by `pem.EncodeToMemory` — `-----BEGIN PUBLIC KEY-----`, 64-column
    /// base64 lines, `-----END PUBLIC KEY-----`, trailing newline. The `kid` is
    /// a hash of this text, so the format matters.
    public static String publicKeyPem(RSAPublicKey pub) {
        return encodePem("PUBLIC KEY", pub.getEncoded());
    }

    /// `generateRSAPEM`: a fresh 2048-bit RSA private key as PKCS#1
    /// `RSA PRIVATE KEY` PEM.
    public static String generateRsaPem() {
        try {
            var gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            var pkcs8 = gen.generateKeyPair().getPrivate().getEncoded();
            return encodePem("RSA PRIVATE KEY", Der.pkcs8ToPkcs1(pkcs8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("rsa generate: " + e.getMessage(), e);
        }
    }

    /// `generateKeyID`: `base64url-nopad(sha256(pem)[0:16])`.
    public static String keyId(String publicKeyPem) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(publicKeyPem.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// `pem.EncodeToMemory`: 64-column base64, every line newline-terminated.
    static String encodePem(String label, byte[] der) {
        var b64 = Base64.getEncoder().encodeToString(der);
        var sb = new StringBuilder(b64.length() + b64.length() / 64 + 64);
        sb.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    // ── fcdev key file ───────────────────────────────────────────────────

    /// `EnsureSigningKeyFile`: guarantee a PEM RSA private key exists at `path`
    /// — when absent or empty, generate one and write it `0600` (parent
    /// directories `0700`). Used by fcdev so tokens survive restarts without
    /// engineers managing a keyring. Returns the path to set
    /// `FC_JWT_SIGNING_KEY_PATH` to.
    public static Path ensureSigningKeyFile(Path path) throws IOException {
        if (path == null || path.toString().isEmpty()) {
            throw new IllegalArgumentException("signing key path is empty");
        }
        if (Files.isRegularFile(path) && Files.size(path) > 0) {
            return path;
        }
        var dir = path.toAbsolutePath().getParent();
        if (dir != null) {
            if (posix(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)));
            } else {
                Files.createDirectories(dir);
            }
        }
        Files.writeString(path, generateRsaPem(), StandardCharsets.UTF_8);
        if (posix(path)) {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        LOG.info("generated persistent JWT signing key at {}", path);
        return path;
    }

    private static boolean posix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    // ── minimal DER ──────────────────────────────────────────────────────

    /// Just enough ASN.1 DER to move between PKCS#1 and PKCS#8 and to read an
    /// `RSAPublicKey` — tag/length/content, no classes, no indefinite lengths.
    static final class Der {
        static final int INTEGER = 0x02;
        static final int OCTET_STRING = 0x04;
        static final int NULL = 0x05;
        static final int OID = 0x06;
        static final int SEQUENCE = 0x30;

        /// `AlgorithmIdentifier { rsaEncryption (1.2.840.113549.1.1.1), NULL }`.
        private static final byte[] RSA_ALG_ID = {
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};

        private Der() {
        }

        /// One TLV: `tag`, the decoded `content`, and the offset just past it.
        record Tlv(int tag, byte[] content, int end) {
        }

        static Tlv read(byte[] in, int off) {
            if (off + 2 > in.length) throw new IllegalArgumentException("truncated DER");
            int tag = in[off++] & 0xFF;
            int len = in[off++] & 0xFF;
            if (len >= 0x80) {
                int bytes = len & 0x7F;
                if (bytes == 0 || bytes > 4 || off + bytes > in.length) throw new IllegalArgumentException("bad DER length");
                len = 0;
                for (int i = 0; i < bytes; i++) len = (len << 8) | (in[off++] & 0xFF);
                if (len < 0) throw new IllegalArgumentException("bad DER length");
            }
            if (off + len > in.length) throw new IllegalArgumentException("truncated DER");
            return new Tlv(tag, Arrays.copyOfRange(in, off, off + len), off + len);
        }

        static byte[] tlv(int tag, byte[] content) {
            var len = content.length;
            byte[] header;
            if (len < 0x80) {
                header = new byte[]{(byte) tag, (byte) len};
            } else {
                int n = len <= 0xFF ? 1 : len <= 0xFFFF ? 2 : len <= 0xFFFFFF ? 3 : 4;
                header = new byte[2 + n];
                header[0] = (byte) tag;
                header[1] = (byte) (0x80 | n);
                for (int i = 0; i < n; i++) header[2 + i] = (byte) (len >>> (8 * (n - 1 - i)));
            }
            var out = Arrays.copyOf(header, header.length + len);
            System.arraycopy(content, 0, out, header.length, len);
            return out;
        }

        /// `PrivateKeyInfo { version 0, rsaEncryption, OCTET STRING pkcs1 }`.
        static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
            var version = tlv(INTEGER, new byte[]{0});
            var octets = tlv(OCTET_STRING, pkcs1);
            var body = new byte[version.length + RSA_ALG_ID.length + octets.length];
            System.arraycopy(version, 0, body, 0, version.length);
            System.arraycopy(RSA_ALG_ID, 0, body, version.length, RSA_ALG_ID.length);
            System.arraycopy(octets, 0, body, version.length + RSA_ALG_ID.length, octets.length);
            return tlv(SEQUENCE, body);
        }

        /// The `privateKey OCTET STRING` payload of a PKCS#8 `PrivateKeyInfo`.
        static byte[] pkcs8ToPkcs1(byte[] pkcs8) {
            var outer = read(pkcs8, 0);
            if (outer.tag() != SEQUENCE) throw new IllegalArgumentException("not PKCS#8");
            var version = read(outer.content(), 0);
            var alg = read(outer.content(), version.end());
            var key = read(outer.content(), alg.end());
            if (key.tag() != OCTET_STRING) throw new IllegalArgumentException("not PKCS#8");
            return key.content();
        }
    }
}
