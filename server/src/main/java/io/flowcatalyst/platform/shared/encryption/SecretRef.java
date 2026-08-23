package io.flowcatalyst.platform.shared.encryption;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

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
    /// The SPA's "encrypt this on save" directive; stripped, never stored.
    String ENCRYPT_DIRECTIVE = "encrypt:";
    /// The dev bypass: the rest of the string *is* the plaintext.
    String LITERAL_PREFIX = "literal:";
    /// The closed list of secret-manager schemes stored verbatim and resolved at read time.
    List<String> EXTERNAL_SCHEMES = List.of("aws-sm", "aws-ps", "gcp-sm", "vault", "env");

    /// Parse a stored or incoming value. `null` is a caller bug (map omitted /
    /// `NULL` fields before calling); an `encrypted:` payload that is not
    /// base64 is rejected with [IllegalArgumentException] — it is not a secret
    /// ref of any kind.
    static SecretRef parse(String value) {
        var v = Objects.requireNonNull(value, "value").strip();
        if (v.isEmpty()) return None.INSTANCE;
        if (v.startsWith(ENCRYPTED_PREFIX)) {
            var payload = v.substring(ENCRYPTED_PREFIX.length());
            return new Encrypted(Base64Strict.decode(payload)
                    .orElseThrow(() -> new IllegalArgumentException("encrypted: payload is not base64")));
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

    /// The shapes that are safe to persist as they are. [Plain] is not one:
    /// it must go through [Encryption#encryptSecretRef(String)] (or be rejected
    /// when no key is configured).
    sealed interface AtRest extends SecretRef permits None, Encrypted, External, Literal {
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
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(envelope);
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
