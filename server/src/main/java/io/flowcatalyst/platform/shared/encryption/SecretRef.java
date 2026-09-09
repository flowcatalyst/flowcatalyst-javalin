package io.flowcatalyst.platform.shared.encryption;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// What a stored (or incoming) secret-reference string *claims* to be — the
/// grammar of `client_secret_ref`, `oidc_client_secret_ref`,
/// `secret_encrypted` and friends (`docs/spec/encryption.md` §3). Parsing is
/// by prefix, after stripping surrounding whitespace; it never reads the
/// environment and never touches a key.
///
/// A bare string makes no claim and parses as [Plain]: an incoming one is
/// plaintext to encrypt, a stored one may be a legacy envelope written
/// without the `encrypted:` prefix — [Encryption#decrypt(String)] tries that
/// reading, [Encryption#encryptSecretRef(String)] never does (a user-supplied
/// secret that happens to look like base64 must not be stored raw).
public sealed interface SecretRef permits SecretRef.AtRest, SecretRef.Plain {

    /// The at-rest prefix of an inline ciphertext.
    String ENCRYPTED_PREFIX = "encrypted:";
    /// The at-rest prefix of a keyed-hash MAC — a verify-only secret
    /// (`docs/spec/encryption.md` §3): never decryptable, only comparable.
    String HASHED_PREFIX = "hashed:v1:";
    /// The SPA's "encrypt this on save" directive; stripped, never stored.
    String ENCRYPT_DIRECTIVE = "encrypt:";
    /// The dev bypass: the rest of the string *is* the plaintext.
    String LITERAL_PREFIX = "literal:";
    /// The closed list of secret-manager schemes stored verbatim and resolved at read time.
    List<String> EXTERNAL_SCHEMES = List.of("aws-sm", "aws-ps", "gcp-sm", "vault", "env");

    /// Parse a stored or incoming value. `null` is a caller bug (map omitted /
    /// `NULL` fields before calling); an `encrypted:` or `hashed:v1:` payload
    /// that is not base64 of the right length is rejected with
    /// [IllegalArgumentException] — it is not a secret ref of any kind.
    static SecretRef parse(String value) {
        var v = Objects.requireNonNull(value, "value").strip();
        if (v.isEmpty()) return None.INSTANCE;
        if (v.startsWith(ENCRYPTED_PREFIX)) {
            var payload = v.substring(ENCRYPTED_PREFIX.length());
            return new Encrypted(Base64Strict.decode(payload)
                    .orElseThrow(() -> new IllegalArgumentException("encrypted: payload is not base64")));
        }
        if (v.startsWith(HASHED_PREFIX)) {
            var payload = v.substring(HASHED_PREFIX.length());
            var mac = Base64Strict.decode(payload)
                    .orElseThrow(() -> new IllegalArgumentException("hashed:v1: payload is not base64"));
            if (mac.length != Encryption.HMAC_BYTES) {
                throw new IllegalArgumentException("hashed:v1: payload must be " + Encryption.HMAC_BYTES + " bytes, got " + mac.length);
            }
            return new Hashed(mac);
        }
        var sep = v.indexOf("://");
        if (sep > 0) {
            var scheme = v.substring(0, sep);
            if (EXTERNAL_SCHEMES.contains(scheme)) return new External(scheme, v.substring(sep + 3));
        }
        if (v.startsWith(LITERAL_PREFIX)) return new Literal(v.substring(LITERAL_PREFIX.length()));
        if (v.startsWith(ENCRYPT_DIRECTIVE)) return new Plain(v.substring(ENCRYPT_DIRECTIVE.length()));
        return new Plain(v);
    }

    /// The scheme of a `<scheme>://…` value whose scheme is **not** in
    /// [#EXTERNAL_SCHEMES] (`docs/spec/encryption.md` §3, owner ruling
    /// 2026-09-08: the list is closed and an unknown scheme is *rejected*,
    /// not encrypted). Empty when there is nothing to reject.
    ///
    /// This is a **write-side** check only — [#parse] still reads such a value
    /// as [Plain], so a row already stored under an unknown scheme keeps
    /// decrypting exactly as before. The explicit `encrypt:` directive is the
    /// override ("this is plaintext, seal it"), the escape hatch for a genuine
    /// secret shaped like `foo://bar`.
    static Optional<String> unsupportedScheme(String value) {
        if (value == null) return Optional.empty();
        var v = value.strip();
        var sep = v.indexOf("://");
        if (sep <= 0) return Optional.empty();
        var scheme = v.substring(0, sep);
        // The at-rest claims (`encrypted:`, `hashed:v1:`, `literal:`) and the
        // `encrypt:` directive each carry a ':', so none of them can form a
        // scheme token: they are excluded by isSchemeToken, not by a
        // separate prefix guard that would only drift out of step with it.
        if (!isSchemeToken(scheme) || EXTERNAL_SCHEMES.contains(scheme)) return Optional.empty();
        return Optional.of(scheme);
    }

    /// An RFC 3986 scheme token: `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`.
    /// Anything else before a `://` is not a scheme, it is a secret that
    /// happens to contain the separator — and must still be encrypted.
    private static boolean isSchemeToken(String s) {
        if (s.isEmpty()) return false;
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            var alpha = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            var ok = alpha || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return false;
        }
        return true;
    }

    /// The shapes that are safe to persist as they are. [Plain] is not one:
    /// it must go through [Encryption#encryptSecretRef(String)] (or be rejected
    /// when no key is configured).
    sealed interface AtRest extends SecretRef permits None, Encrypted, Hashed, External, Literal {
        /// The canonical stored string.
        String stored();
    }

    /// Blank: no secret (a public OIDC client), or "clear the secret" on update.
    enum None implements AtRest {
        INSTANCE;

        @Override
        public String stored() {
            return "";
        }
    }

    /// `encrypted:<base64>` — an inline envelope ([Encryption] §2 layouts), decoded.
    record Encrypted(byte[] envelope) implements AtRest {
        public Encrypted {
            envelope = Objects.requireNonNull(envelope, "envelope").clone();
        }

        @Override
        public byte[] envelope() {
            return envelope.clone();
        }

        @Override
        public String stored() {
            return ENCRYPTED_PREFIX + Base64Strict.encode(envelope);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Encrypted other && Arrays.equals(envelope, other.envelope);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(envelope);
        }

        @Override
        public String toString() {
            return "Encrypted[" + envelope.length + " bytes]";
        }
    }

    /// `hashed:v1:<base64>` — a keyed-hash MAC ([Encryption] `HmacSHA256` under
    /// the app key), decoded. One-way: never decryptable, only verified against
    /// a caller-supplied plaintext ([Encryption#verifySecret(String, String)]).
    /// The at-rest form of a verify-only secret (OAuth client secrets,
    /// self-service developer client secrets) — never a secret the platform
    /// itself must later use or send (`docs/spec/encryption.md` §3).
    record Hashed(byte[] mac) implements AtRest {
        public Hashed {
            mac = Objects.requireNonNull(mac, "mac").clone();
        }

        @Override
        public byte[] mac() {
            return mac.clone();
        }

        @Override
        public String stored() {
            return HASHED_PREFIX + Base64Strict.encode(mac);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Hashed other && Arrays.equals(mac, other.mac);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(mac);
        }

        @Override
        public String toString() {
            return "Hashed[***]";
        }
    }

    /// `<scheme>://<ref>` for a scheme in [#EXTERNAL_SCHEMES]: the secret lives
    /// in a secret manager and is resolved at read time, never encrypted inline.
    record External(String scheme, String ref) implements AtRest {
        public External {
            Objects.requireNonNull(scheme, "scheme");
            Objects.requireNonNull(ref, "ref");
            if (!EXTERNAL_SCHEMES.contains(scheme)) throw new IllegalArgumentException("unknown external scheme: " + scheme);
        }

        @Override
        public String stored() {
            return scheme + "://" + ref;
        }
    }

    /// `literal:<value>` — the value is the plaintext, stored as-is (dev bypass).
    record Literal(String value) implements AtRest {
        public Literal {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String stored() {
            return LITERAL_PREFIX + value;
        }

        @Override
        public String toString() {
            return "Literal[***]";
        }
    }

    /// No prefix claim (or the `encrypt:` directive, already stripped): plaintext
    /// to encrypt before storing — or, read back from a column, possibly a bare
    /// legacy envelope.
    record Plain(String value) implements SecretRef {
        public Plain {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Plain[***]";
        }
    }
}
