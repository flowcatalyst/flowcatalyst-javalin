package io.flowcatalyst.platform.shared.encryption;

import io.flowcatalyst.server.EnvReader;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static io.flowcatalyst.platform.shared.encryption.SecretRef.ENCRYPTED_PREFIX;
import static io.flowcatalyst.platform.shared.encryption.SecretRef.HASHED_PREFIX;

/// Field-level AES-256-GCM encryption with one-step key rotation
/// (`docs/spec/encryption.md`). Envelopes are base64 of
/// `0x01 || nonce(12) || ciphertext || tag(16)` (v1); legacy v0 envelopes
/// without the version byte and the `encrypted:` prefix are read
/// transparently. [#encrypt(String)] always uses the current key and writes
/// v1; [#decrypt(String)] tries current then previous. Keys come from
/// `FLOWCATALYST_APP_KEY` / `FLOWCATALYST_APP_KEY_PREVIOUS` ([#fromEnv]).
///
/// JDK `javax.crypto` only; thread-safe (a `Cipher` per call).
public final class Encryption {

    public static final String ENV_APP_KEY = "FLOWCATALYST_APP_KEY";
    public static final String ENV_APP_KEY_PREVIOUS = "FLOWCATALYST_APP_KEY_PREVIOUS";

    /// AES-256.
    public static final int KEY_BYTES = 32;
    /// GCM nonce length in the envelope.
    public static final int NONCE_BYTES = 12;
    /// GCM tag length in the envelope.
    public static final int TAG_BYTES = 16;
    /// Version byte of the current envelope layout.
    static final byte VERSION_1 = 0x01;
    /// Smallest v0 / v1 envelope: nonce, tag, and nothing encrypted.
    static final int MIN_V0 = NONCE_BYTES + TAG_BYTES;
    static final int MIN_V1 = 1 + MIN_V0;
    /// `HmacSHA256` output length — the payload length a `hashed:v1:` ref's MAC must decode to.
    static final int HMAC_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    /// Which keys decrypt: the current one alone, or current plus the previous
    /// one during a rotation. Only the current key ever encrypts.
    public sealed interface KeyRotation permits KeyRotation.Single, KeyRotation.Rotating {

        /// The key new envelopes are sealed with.
        SecretKey current();

        /// Current, then previous (when rotating) — the order [#decrypt] tries.
        default List<SecretKey> decryptionKeys() {
            return switch (this) {
                case Single(var current) -> List.of(current);
                case Rotating(var current, var previous) -> List.of(current, previous);
            };
        }

        /// `FLOWCATALYST_APP_KEY_PREVIOUS` unset.
        record Single(SecretKey current) implements KeyRotation {
            public Single {
                Objects.requireNonNull(current, "current");
            }

            /// `SecretKeySpec#toString` prints a hash derived from the key bytes; keep it out of logs.
            @Override
            public String toString() {
                return "Single[***]";
            }
        }

        /// `FLOWCATALYST_APP_KEY_PREVIOUS` set: `previous` still decrypts, never encrypts.
        record Rotating(SecretKey current, SecretKey previous) implements KeyRotation {
            public Rotating {
                Objects.requireNonNull(current, "current");
                Objects.requireNonNull(previous, "previous");
            }

            @Override
            public String toString() {
                return "Rotating[***]";
            }
        }

        /// From base64 key material; a blank `previousBase64` means [Single].
        static KeyRotation of(String currentBase64, String previousBase64) {
            var current = parseKey(currentBase64);
            if (previousBase64 == null || previousBase64.isBlank()) return new Single(current);
            try {
                return new Rotating(current, parseKey(previousBase64));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("previous key: " + e.getMessage(), e);
            }
        }
    }

    private final KeyRotation keys;

    private Encryption(KeyRotation keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    // ── construction ─────────────────────────────────────────────────────

    public static Encryption of(KeyRotation keys) {
        return new Encryption(keys);
    }

    /// A single key, no rotation.
    public static Encryption withKey(String base64Key) {
        return of(new KeyRotation.Single(parseKey(base64Key)));
    }

    /// `FLOWCATALYST_APP_KEY` (current) and `FLOWCATALYST_APP_KEY_PREVIOUS`
    /// (optional). Empty when the current key is unset or blank — encryption
    /// is *disabled*: callers must refuse to store plaintext secrets and fail
    /// closed on reads. A configured key that does not parse is fatal
    /// ([IllegalArgumentException]): a key that cannot decrypt is worse than
    /// no key. Both values are stripped of surrounding whitespace.
    public static Optional<Encryption> fromEnv(EnvReader env) {
        return fromKeys(env.get(ENV_APP_KEY), env.get(ENV_APP_KEY_PREVIOUS));
    }

    /// [#fromEnv] over already-read values — what the composition root calls
    /// with `Env.appKey()` / `Env.appKeyPrevious()`, so an environment loaded
    /// from a map (fcdev, `.env`) configures encryption exactly like the
    /// process environment does. `null` reads as unset; both values are
    /// stripped; blank current key → empty (disabled); malformed → fatal.
    public static Optional<Encryption> fromKeys(String currentBase64, String previousBase64) {
        var current = currentBase64 == null ? "" : currentBase64.strip();
        if (current.isEmpty()) return Optional.empty();
        var previous = previousBase64 == null ? "" : previousBase64.strip();
        return Optional.of(of(KeyRotation.of(current, previous)));
    }

    /// A fresh 32-byte key as the padded standard-base64 string the
    /// environment variables and the fcdev `app-key` file carry.
    public static String generateKey() {
        var raw = new byte[KEY_BYTES];
        RANDOM.nextBytes(raw);
        return Base64Strict.encode(raw);
    }

    /// Decode a base64 key; anything but exactly 32 bytes is rejected.
    public static SecretKey parseKey(String base64Key) {
        var raw = Base64Strict.decode(Objects.requireNonNull(base64Key, "base64Key").strip())
                .orElseThrow(() -> new IllegalArgumentException("invalid base64 key"));
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException("key must be " + KEY_BYTES + " bytes, got " + raw.length);
        }
        var key = new SecretKeySpec(raw, "AES"); // copies
        Arrays.fill(raw, (byte) 0);
        return key;
    }

    KeyRotation keys() {
        return keys;
    }

    // ── encrypt ──────────────────────────────────────────────────────────

    /// A bare v1 envelope of `plaintext` under the current key with a fresh
    /// nonce; no prefix. Encrypts whatever it is given — feeding an envelope
    /// back in wraps it twice. For values arriving as secret refs use
    /// [#encryptSecretRef(String)].
    public String encrypt(String plaintext) {
        var nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return Base64Strict.encode(seal(keys.current(), nonce, plaintext));
    }

    /// The at-rest form of an incoming secret-ref field (`docs/spec/encryption.md`
    /// §5): plaintext (with or without the `encrypt:` directive) becomes
    /// `encrypted:<v1 envelope>`; blank, `encrypted:`, external and `literal:`
    /// values pass through, so the operation is idempotent. An `encrypted:`
    /// value whose payload is not base64 is rejected ([IllegalArgumentException])
    /// rather than stored.
    ///
    /// A `<scheme>://…` value whose scheme is not one of
    /// [SecretRef#EXTERNAL_SCHEMES] is **rejected** rather than encrypted
    /// (owner ruling 2026-09-08): the list is closed, and silently sealing a
    /// mistyped `aws-smm://…` would store a secret-manager *reference* as
    /// though it were the secret. `encrypt:` overrides, for the rare secret
    /// that genuinely looks like a URL.
    public String encryptSecretRef(String incoming) {
        var unsupported = SecretRef.unsupportedScheme(incoming);
        if (unsupported.isPresent()) {
            throw new IllegalArgumentException("unsupported secret-manager scheme \"" + unsupported.get()
                    + "://\"; supported: " + String.join(", ", SecretRef.EXTERNAL_SCHEMES)
                    + " (prefix the value with \"" + SecretRef.ENCRYPT_DIRECTIVE
                    + "\" to store it as an encrypted plaintext secret instead)");
        }
        return switch (SecretRef.parse(incoming)) {
            case SecretRef.None _ -> incoming;
            case SecretRef.AtRest r -> r.stored();
            case SecretRef.Plain(var plaintext) -> ENCRYPTED_PREFIX + encrypt(plaintext);
        };
    }

    /// `version || nonce || AES-GCM(key, nonce, plaintext)` — the v1 layout.
    static byte[] seal(SecretKey key, byte[] nonce, String plaintext) {
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            var ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var out = new byte[1 + nonce.length + ct.length];
            out[0] = VERSION_1;
            System.arraycopy(nonce, 0, out, 1, nonce.length);
            System.arraycopy(ct, 0, out, 1 + nonce.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable: " + e.getMessage(), e);
        }
    }

    // ── decrypt ──────────────────────────────────────────────────────────

    /// Read a stored secret ref (`docs/spec/encryption.md` §4): `encrypted:` and
    /// bare envelopes (v1 or legacy v0) are opened with the current key, then
    /// the previous one; external refs come back as [Decryption.External];
    /// `literal:` is its own plaintext; everything else is a [Decryption.Failed]
    /// with a reason. Never throws for bad data.
    public Decryption decrypt(String stored) {
        SecretRef ref;
        try {
            ref = SecretRef.parse(stored);
        } catch (IllegalArgumentException _) {
            return new Decryption.Failed(Decryption.Reason.MALFORMED);
        }
        return switch (ref) {
            case SecretRef.None _ -> new Decryption.Failed(Decryption.Reason.EMPTY);
            case SecretRef.Encrypted(var envelope) -> open(envelope);
            case SecretRef.Hashed _ -> new Decryption.Failed(Decryption.Reason.HASHED);
            case SecretRef.External ext -> new Decryption.External(ext);
            case SecretRef.Literal(var value) -> new Decryption.Plaintext(value);
            case SecretRef.Plain(var value) -> bareEnvelope(value)
                    .<Decryption>map(this::open)
                    .orElseGet(() -> new Decryption.Failed(Decryption.Reason.NOT_ENCRYPTED));
        };
    }

    // ── keyed hashing (verify-only secrets) ─────────────────────────────────

    /// Outcome of [#verifySecret(String, String)]: whether the provided
    /// plaintext matched the stored ref, and — when it did — whether the
    /// caller should rewrite the row to the hashed form
    /// (`docs/spec/encryption.md` §3 transparent migration): `true` for any
    /// match not already a `hashed:v1:` ref sealed under the *current* key
    /// (a legacy decrypt-and-compare shape, or a hash under a previous key).
    public sealed interface SecretVerification {
        record Matched(boolean rehash) implements SecretVerification {
        }

        record NoMatch() implements SecretVerification {
        }
    }

    /// Verifies `providedPlaintext` against a stored secret ref of either
    /// shape (`docs/spec/encryption.md` §3): a `hashed:v1:` ref is verified
    /// by keyed MAC (current key, then previous — same order as [#decrypt]),
    /// no decryption involved; any other shape keeps today's decrypt-and-compare.
    /// Constant-time either way ([MessageDigest#isEqual]). Never throws for
    /// bad data — a malformed or unmatched ref is [SecretVerification.NoMatch].
    public SecretVerification verifySecret(String stored, String providedPlaintext) {
        Objects.requireNonNull(providedPlaintext, "providedPlaintext");
        SecretRef ref;
        try {
            ref = SecretRef.parse(stored);
        } catch (IllegalArgumentException _) {
            return new SecretVerification.NoMatch();
        }
        if (ref instanceof SecretRef.Hashed(var mac)) {
            return verifyHashed(mac, providedPlaintext);
        }
        return switch (decrypt(stored)) {
            case Decryption.Plaintext(var pt) -> MessageDigest.isEqual(
                    pt.getBytes(StandardCharsets.UTF_8), providedPlaintext.getBytes(StandardCharsets.UTF_8))
                    ? new SecretVerification.Matched(true) // any successful legacy-shape match migrates
                    : new SecretVerification.NoMatch();
            case Decryption.External _, Decryption.Failed _ -> new SecretVerification.NoMatch();
        };
    }

    /// Tries every key, current first (the order [#decrypt] uses); a match
    /// under anything but the current key needs rehashing.
    private SecretVerification verifyHashed(byte[] mac, String providedPlaintext) {
        List<SecretKey> ordered = keys.decryptionKeys(); // current first, then previous when rotating
        for (int i = 0; i < ordered.size(); i++) {
            if (MessageDigest.isEqual(hmac(ordered.get(i), providedPlaintext), mac)) {
                return new SecretVerification.Matched(i > 0);
            }
        }
        return new SecretVerification.NoMatch();
    }

    /// The at-rest form of a verify-only secret: `hashed:v1:` + the base64
    /// `HmacSHA256` MAC of `plaintext` under the *current* key. Unlike
    /// [#encryptSecretRef(String)] this is not idempotent over secret-ref
    /// shapes — callers mint a verify-only secret's plaintext once and hash
    /// it directly, they never re-hash an already-stored ref.
    public String hashSecretRef(String plaintext) {
        return HASHED_PREFIX + Base64Strict.encode(hmac(keys.current(), plaintext));
    }

    /// `HmacSHA256(key, plaintext)` — the MAC inside a `hashed:v1:` ref.
    private static byte[] hmac(SecretKey key, String plaintext) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getEncoded(), HMAC_ALGORITHM));
            return mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC_ALGORITHM + " unavailable: " + e.getMessage(), e);
        }
    }

    /// The legacy reading of a bare value: strict base64 of at least the smallest
    /// envelope. Anything shorter is plaintext, not a malformed envelope.
    private static Optional<byte[]> bareEnvelope(String value) {
        return Base64Strict.decode(value).filter(bytes -> bytes.length >= MIN_V0);
    }

    /// Open an envelope with every key, v1 layout first when the version byte
    /// says so, falling back to the v0 reading (a v0 nonce may start with 0x01;
    /// GCM authentication makes the retry safe).
    private Decryption open(byte[] envelope) {
        if (envelope.length < MIN_V0) return new Decryption.Failed(Decryption.Reason.MALFORMED);
        if (envelope[0] == VERSION_1 && envelope.length >= MIN_V1) {
            var pt = openWithAnyKey(envelope, 1);
            if (pt.isPresent()) return new Decryption.Plaintext(pt.get());
        }
        return openWithAnyKey(envelope, 0)
                .<Decryption>map(Decryption.Plaintext::new)
                .orElseGet(() -> new Decryption.Failed(Decryption.Reason.NO_MATCHING_KEY));
    }

    /// `envelope[offset..offset+12)` is the nonce, the rest ciphertext+tag.
    private Optional<String> openWithAnyKey(byte[] envelope, int offset) {
        for (var key : keys.decryptionKeys()) {
            var pt = openWith(key, envelope, offset);
            if (pt.isPresent()) return pt;
        }
        return Optional.empty();
    }

    private static Optional<String> openWith(SecretKey key, byte[] envelope, int offset) {
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            var nonce = Arrays.copyOfRange(envelope, offset, offset + NONCE_BYTES);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            var pt = cipher.doFinal(envelope, offset + NONCE_BYTES, envelope.length - offset - NONCE_BYTES);
            return Optional.of(new String(pt, StandardCharsets.UTF_8));
        } catch (AEADBadTagException _) {
            return Optional.empty();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable: " + e.getMessage(), e);
        }
    }

    // ── rotation ─────────────────────────────────────────────────────────

    /// `true` when `stored` is an inline envelope (prefixed or bare) that is
    /// not a v1 envelope the **current** key opens — i.e. the rotation job
    /// should rewrite it. Blank, external, literal and non-envelope values
    /// are `false`: nothing inline to migrate. An `encrypted:` value whose
    /// payload is too short to be an envelope is `true` (it claims to be one
    /// and cannot be read); a bare base64 string that short is plaintext, `false`.
    public boolean needsReEncryption(String stored) {
        return inlineEnvelope(stored)
                .map(inline -> !sealedByCurrentKeyV1(inline.envelope()))
                .orElse(false);
    }

    /// Decrypt with any key and re-seal with the current one, keeping the
    /// input's shape (`encrypted:`-prefixed or bare). Empty when there is no
    /// decryptable inline envelope to migrate — the job leaves that row alone.
    public Optional<String> reEncrypt(String stored) {
        return inlineEnvelope(stored).flatMap(inline -> switch (open(inline.envelope())) {
            case Decryption.Plaintext(var pt) -> {
                var blob = encrypt(pt);
                yield Optional.of(inline.prefixed() ? ENCRYPTED_PREFIX + blob : blob);
            }
            case Decryption.External _, Decryption.Failed _ -> Optional.empty();
        });
    }

    private boolean sealedByCurrentKeyV1(byte[] envelope) {
        return envelope.length >= MIN_V1 && envelope[0] == VERSION_1
                && openWith(keys.current(), envelope, 1).isPresent();
    }

    /// An inline envelope and the column convention it was stored under.
    private record Inline(byte[] envelope, boolean prefixed) {
    }

    /// The envelope bytes of an `encrypted:` or bare-base64 value; empty for
    /// every other shape, including an `encrypted:` value that does not parse.
    private static Optional<Inline> inlineEnvelope(String stored) {
        SecretRef ref;
        try {
            ref = SecretRef.parse(stored);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        return switch (ref) {
            case SecretRef.Encrypted(var envelope) -> Optional.of(new Inline(envelope, true));
            case SecretRef.Plain(var value) -> bareEnvelope(value).map(b -> new Inline(b, false));
            // Hashed refs migrate off a previous key lazily, at verify time
            // (#verifySecret) — never via this batch job, which only knows
            // how to re-seal an AES-GCM envelope.
            case SecretRef.None _, SecretRef.External _, SecretRef.Literal _, SecretRef.Hashed _ -> Optional.empty();
        };
    }
}
