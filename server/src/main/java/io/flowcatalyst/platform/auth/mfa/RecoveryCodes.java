package io.flowcatalyst.platform.auth.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/// Recovery codes and the hashing every MFA credential shares
/// (`docs/spec/auth-identity.md` §6.5): `XXXXX-XXXXX` over a 30-symbol
/// alphabet (Crockford base32 without `I L O U 0 1`), normalised by trim +
/// upper-case + stripping `-` and spaces, stored as SHA-256 hex of the
/// normalised form. `"  a7k2m 9pqrt "` and `"A7K2M-9PQRT"` are the same code.
public final class RecoveryCodes {

    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
    public static final int GROUP = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate(int count) {
        var out = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            out.add(one());
        }
        return out;
    }

    static String one() {
        var b = new StringBuilder(GROUP * 2 + 1);
        for (int g = 0; g < 2; g++) {
            if (g > 0) {
                b.append('-');
            }
            for (int i = 0; i < GROUP; i++) {
                // nextInt(bound) is uniform (rejection-sampled) — no modulo bias.
                b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
        }
        return b.toString();
    }

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT).replace("-", "").replace(" ", "");
    }

    public static String hash(String raw) {
        return sha256Hex(normalize(raw));
    }

    /// Lower-case hex SHA-256 of the UTF-8 text.
    public static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /// `n` decimal digits drawn uniformly, zero-padded — the e-mail PIN.
    public static String randomDigits(int n) {
        var b = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            b.append((char) ('0' + RANDOM.nextInt(10)));
        }
        return b.toString();
    }
}
