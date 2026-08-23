package io.flowcatalyst.platform.shared.encryption;

import io.flowcatalyst.server.EnvReader;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static io.flowcatalyst.platform.shared.encryption.SecretRef.ENCRYPTED_PREFIX;

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

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
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
        }

        /// `FLOWCATALYST_APP_KEY_PREVIOUS` set: `previous` still decrypts, never encrypts.
        record Rotating(SecretKey current, SecretKey previous) implements KeyRotation {
            public Rotating {
                Objects.requireNonNull(current, "current");
                Objects.requireNonNull(previous, "previous");
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
        var current = env.get(ENV_APP_KEY).strip();
        if (current.isEmpty()) return Optional.empty();
        return Optional.of(of(KeyRotation.of(current, env.get(ENV_APP_KEY_PREVIOUS).strip())));
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
        return new SecretKeySpec(raw, "AES");
    }

    public KeyRotation keys() {
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
    public String encryptSecretRef(String incoming) {
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
            case SecretRef.External ext -> new Decryption.External(ext);
            case SecretRef.Literal(var value) -> new Decryption.Plaintext(value);
            case SecretRef.Plain(var value) -> Base64Strict.decode(value)
                    .filter(bytes -> bytes.length >= MIN_V0)
                    .<Decryption>map(this::open)
                    .orElseGet(() -> new Decryption.Failed(Decryption.Reason.NOT_ENCRYPTED));
        };
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
            var pt = open(key, envelope, offset);
            if (pt.isPresent()) return pt;
        }
        return Optional.empty();
    }

    private static Optional<String> open(SecretKey key, byte[] envelope, int offset) {
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
    /// are `false`: nothing inline to migrate.
    public boolean needsReEncryption(String stored) {
        var envelope = inlineEnvelope(stored);
        if (envelope.isEmpty()) return false;
        var bytes = envelope.get();
        if (bytes.length < MIN_V1 || bytes[0] != VERSION_1) return true;
        return open(keys.current(), bytes, 1).isEmpty();
    }

    /// Decrypt with any key and re-seal with the current one, keeping the
    /// input's shape (`encrypted:`-prefixed or bare). Empty when there is no
    /// decryptable inline envelope to migrate — the job leaves that row alone.
    public Optional<String> reEncrypt(String stored) {
        if (inlineEnvelope(stored).isEmpty()) return Optional.empty();
        return switch (decrypt(stored)) {
            case Decryption.Plaintext(var pt) -> {
                var blob = encrypt(pt);
                yield Optional.of(stored.strip().startsWith(ENCRYPTED_PREFIX) ? ENCRYPTED_PREFIX + blob : blob);
            }
            case Decryption.External _, Decryption.Failed _ -> Optional.empty();
        };
    }

    /// The envelope bytes of an `encrypted:` or bare-base64 value; empty for
    /// every other shape.
    private static Optional<byte[]> inlineEnvelope(String stored) {
        SecretRef ref;
        try {
            ref = SecretRef.parse(stored);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        return switch (ref) {
            case SecretRef.Encrypted(var envelope) -> Optional.of(envelope);
            case SecretRef.Plain(var value) -> Base64Strict.decode(value).filter(b -> b.length >= MIN_V0);
            case SecretRef.None _, SecretRef.External _, SecretRef.Literal _ -> Optional.empty();
        };
    }
}
