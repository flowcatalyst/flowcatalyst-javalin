package io.flowcatalyst.platform.shared.auth;

import com.password4j.Argon2Function;
import com.password4j.BcryptFunction;
import com.password4j.types.Argon2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The single source of truth for FlowCatalyst's password hashing. The
/// behavioural contract is `docs/spec/password-hash.md`.
///
/// Hashes are stored in the PHC string format
/// (https://github.com/P-H-C/phc-string-format) using argon2id:
///
/// ```
/// $argon2id$v=19$m=65536,t=3,p=4$<salt-b64>$<hash-b64>
/// ```
///
/// The `m` / `t` / `p` parameters are:
///
/// ```
/// memory      64 MiB (65536 KiB)
/// iterations  3
/// parallelism 4
/// key length  32 bytes
/// salt length 16 bytes
/// ```
///
/// [#verify] parses the stored PHC envelope so future param changes are
/// backwards-compatible: each row carries the params it was hashed with.
///
/// Callers are principal/user passwords (create, reset, seed, login verify) —
/// they go through [#hash] + [#verify], there is no other path. OAuth client
/// secrets do NOT use this; they're reversibly encrypted via shared/encryption
/// (decrypt + compare).
///
/// The argon2 / bcrypt primitives come from password4j; the PHC envelope is
/// encoded and decoded here so the bytes on disk are exactly what existing
/// rows (and the Go binary, for rollback) write and read.
public final class PasswordHash {

    /// The Argon2id parameter set. The defaults are the configuration
    /// existing hashes were minted with.
    public record Params(int memory, int iterations, int parallelism, int keyLength, int saltLength) {
        public Params {
            if (memory <= 0 || iterations <= 0 || parallelism <= 0 || keyLength <= 0 || saltLength <= 0) {
                throw new IllegalArgumentException("argon2 params must be positive");
            }
        }
    }

    /// What new hashes use. Existing rows may carry different params in
    /// their PHC envelope; [#verify] reads those rather than these.
    public static final Params DEFAULT_PARAMS = new Params(64 * 1024, 3, 4, 32, 16);

    /// Outcome of [#verify].
    public enum Verification {
        /// The plaintext re-hashes to the stored value.
        OK,
        /// A correctly-formatted hash whose plaintext was wrong.
        MISMATCH,
        /// A stored hash that doesn't parse as a PHC argon2 (or bcrypt) envelope.
        INVALID_HASH
    }

    /// The argon2 family members a stored envelope may name. argon2id is the
    /// native scheme; argon2i is what an upstream Laravel app produced and
    /// existing users carry those hashes (see [#needsRehash]).
    private enum Variant {
        ARGON2ID("argon2id", Argon2.ID),
        ARGON2I("argon2i", Argon2.I);

        /// The PHC identifier between the first two `$`.
        final String label;
        final Argon2 function;

        Variant(String label, Argon2 function) {
            this.label = label;
            this.function = function;
        }

        static Optional<Variant> fromLabel(String label) {
            for (Variant v : values()) {
                if (v.label.equals(label)) {
                    return Optional.of(v);
                }
            }
            return Optional.empty();
        }
    }

    /// `argon2.Version` — the only version accepted (0x13).
    private static final int ARGON2_VERSION = 19;

    private static final Pattern VERSION = Pattern.compile("v=(\\d+)");
    private static final Pattern PARAMS = Pattern.compile("m=(\\d+),t=(\\d+),p=(\\d+)");
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODE = Base64.getDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHash() {
    }

    /// A PHC-encoded argon2id hash of `plaintext` using a fresh random salt
    /// and the default params.
    public static String hash(String plaintext) {
        return hashWithParams(plaintext, DEFAULT_PARAMS);
    }

    /// The parameter-explicit form. Use for tests that need reproducibility
    /// or for forcing weaker params on a slow machine.
    public static String hashWithParams(String plaintext, Params p) {
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] salt = new byte[p.saltLength()];
        RANDOM.nextBytes(salt);
        byte[] key = deriveKey(Variant.ARGON2ID, plaintext, p, salt);
        return encode(p, salt, key);
    }

    /// Performs one Argon2id verification against a fixed internal hash and
    /// discards the result. Login handlers call it on the
    /// principal-not-found / no-password-set path so a rejected login spends
    /// roughly the same CPU whether or not the account exists. Without it, the
    /// tens-of-milliseconds Argon2id cost incurred only when the account
    /// exists is a timing oracle that lets an attacker enumerate registered
    /// emails. The plaintext is passed through so the work is genuinely
    /// input-dependent and can't be optimised away. The dummy hash is computed
    /// once, lazily, using [#DEFAULT_PARAMS] so its cost matches a live verify.
    public static void equalizeTiming(String plaintext) {
        verify(plaintext, DummyHash.VALUE);
    }

    /// Lazy holder: the sentinel value is irrelevant — the comparison result
    /// is discarded; only the Argon2id work matters.
    private static final class DummyHash {
        static final String VALUE = hash("fc-login-timing-equalization-sentinel");
    }

    /// [Verification#OK] iff `plaintext` re-hashes to the same value as the
    /// stored PHC string; [Verification#INVALID_HASH] for malformed input;
    /// [Verification#MISMATCH] for a hash that parsed but didn't match.
    ///
    /// Both argon2id (the native scheme) and argon2i are accepted — the
    /// algorithm is read from the PHC envelope. bcrypt (`$2a$`/`$2b$`/`$2y$`,
    /// the Laravel default) is accepted too. Migrate a legacy row to argon2id
    /// on next login by pairing [#verify] with [#needsRehash].
    public static Verification verify(String plaintext, String encoded) {
        Objects.requireNonNull(plaintext, "plaintext");
        if (encoded == null) {
            return Verification.INVALID_HASH;
        }
        if (isBcrypt(encoded)) {
            return verifyBcrypt(plaintext, encoded);
        }
        Optional<Decoded> decoded = decode(encoded);
        if (decoded.isEmpty()) {
            return Verification.INVALID_HASH;
        }
        Decoded d = decoded.get();
        byte[] got = deriveKey(d.variant(), plaintext, d.params(), d.salt());
        return MessageDigest.isEqual(got, d.hash()) ? Verification.OK : Verification.MISMATCH;
    }

    /// `verify(plaintext, encoded) == OK`.
    public static boolean matches(String plaintext, String encoded) {
        return verify(plaintext, encoded) == Verification.OK;
    }

    /// Whether a stored hash should be re-encoded to the native scheme after
    /// a successful verify. True for any non-native variant (e.g. Laravel's
    /// argon2i or bcrypt), for argon2id rows whose params differ from
    /// [#DEFAULT_PARAMS], and for anything that doesn't parse — so a login
    /// transparently upgrades a legacy hash without disrupting the user.
    public static boolean needsRehash(String encoded) {
        Optional<Decoded> decoded = decode(encoded);
        if (decoded.isEmpty() || decoded.get().variant() != Variant.ARGON2ID) {
            return true;
        }
        Params p = decoded.get().params();
        return p.memory() != DEFAULT_PARAMS.memory()
                || p.iterations() != DEFAULT_PARAMS.iterations()
                || p.parallelism() != DEFAULT_PARAMS.parallelism()
                || p.keyLength() != DEFAULT_PARAMS.keyLength();
    }

    /// Whether the encoded hash is a bcrypt string (the Laravel default
    /// password algorithm).
    private static boolean isBcrypt(String s) {
        return s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$");
    }

    /// bcrypt only considers the first 72 bytes; truncate to match PHP's
    /// `password_verify` so long passwords compare identically. password4j
    /// signals a malformed bcrypt string with an `IllegalArgumentException`
    /// (its `BadParametersException` is one).
    private static Verification verifyBcrypt(String plaintext, String encoded) {
        byte[] pw = plaintext.getBytes(StandardCharsets.UTF_8);
        if (pw.length > 72) {
            pw = Arrays.copyOf(pw, 72);
        }
        try {
            return BcryptFunction.getInstanceFromHash(encoded)
                    .check(pw, encoded.getBytes(StandardCharsets.UTF_8))
                    ? Verification.OK : Verification.MISMATCH;
        } catch (IllegalArgumentException _) {
            return Verification.INVALID_HASH;
        }
    }

    private static byte[] deriveKey(Variant variant, String plaintext, Params p, byte[] salt) {
        Argon2Function fn = Argon2Function.getInstance(
                p.memory(), p.iterations(), p.parallelism(), p.keyLength(), variant.function, ARGON2_VERSION);
        return fn.hash(plaintext.getBytes(StandardCharsets.UTF_8), salt).getBytes();
    }

    private static String encode(Params p, byte[] salt, byte[] hash) {
        return "$" + Variant.ARGON2ID.label + "$v=" + ARGON2_VERSION
                + "$m=" + p.memory() + ",t=" + p.iterations() + ",p=" + p.parallelism()
                + "$" + B64.encodeToString(salt)
                + "$" + B64.encodeToString(hash);
    }

    /// A parsed PHC argon2 envelope. Internal and consumed immediately by the
    /// caller that decoded it, so the arrays are not copied.
    private record Decoded(Variant variant, Params params, byte[] salt, byte[] hash) {
    }

    /// Parses `$argon2id$v=19$m=…,t=…,p=…$<salt>$<hash>` (or `$argon2i$…`);
    /// empty for anything else.
    private static Optional<Decoded> decode(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String[] parts = s.split("\\$", -1);
        // parts: ["", "argon2id", "v=19", "m=...,t=...,p=...", "<salt>", "<hash>"]
        if (parts.length != 6 || !parts[0].isEmpty()) {
            return Optional.empty();
        }
        Optional<Variant> variant = Variant.fromLabel(parts[1]);
        if (variant.isEmpty()) {
            return Optional.empty();
        }
        Matcher v = VERSION.matcher(parts[2]);
        if (!v.matches() || parseDigits(v.group(1)) != ARGON2_VERSION) {
            return Optional.empty();
        }
        Matcher m = PARAMS.matcher(parts[3]);
        if (!m.matches()) {
            return Optional.empty();
        }
        int memory = parseDigits(m.group(1));
        int iterations = parseDigits(m.group(2));
        int parallelism = parseDigits(m.group(3));
        byte[] salt;
        byte[] hash;
        try {
            salt = B64_DECODE.decode(parts[4]);
            hash = B64_DECODE.decode(parts[5]);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        if (memory <= 0 || iterations <= 0 || parallelism <= 0 || parallelism > 255
                || salt.length == 0 || hash.length == 0) {
            return Optional.empty();
        }
        return Optional.of(new Decoded(variant.get(),
                new Params(memory, iterations, parallelism, hash.length, salt.length), salt, hash));
    }

    /// A digits-only string (the regex has already guaranteed the shape) as an
    /// `int`, or `-1` when it overflows — which the range checks above then
    /// reject like any other invalid value.
    private static int parseDigits(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException _) {
            return -1;
        }
    }
}
