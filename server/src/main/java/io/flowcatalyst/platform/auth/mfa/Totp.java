package io.flowcatalyst.platform.auth.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/// RFC 6238 TOTP as the authenticator apps implement it and as
/// `docs/spec/auth-identity.md` §6.5 fixes it: HMAC-SHA1, 6 digits, 30 s
/// period, ±1 step of skew, a 20-byte secret. Pure functions over a base32
/// secret; the replay guard lives in the service, keyed by the step this
/// class reports.
public final class Totp {

    public static final int PERIOD_SECONDS = 30;
    public static final int SKEW_STEPS = 1;
    public static final int DIGITS = 6;
    public static final int SECRET_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /// A fresh 160-bit secret, base32 without padding.
    public static String generateSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        RANDOM.nextBytes(raw);
        return Base32.encode(raw);
    }

    public static long stepOf(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), PERIOD_SECONDS);
    }

    /// The representative instant of a step (`step × 30 s`), what the
    /// replay guard stores.
    public static Instant timeForStep(long step) {
        return Instant.ofEpochSecond(step * PERIOD_SECONDS);
    }

    /// The 6-digit code for one step.
    public static String code(String base32Secret, long step) {
        byte[] key = Base32.decode(base32Secret);
        byte[] msg = ByteBuffer.allocate(8).putLong(step).array();
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format("%0" + DIGITS + "d", otp);
    }

    /// The step whose code matches, searching `now − 1 .. now + 1` steps,
    /// each compare constant-time; empty when none does.
    public static OptionalLong validate(String base32Secret, String code, Instant now) {
        if (code == null) {
            return OptionalLong.empty();
        }
        String trimmed = code.trim();
        long base = stepOf(now);
        byte[] provided = trimmed.getBytes(StandardCharsets.US_ASCII);
        for (long s = base - SKEW_STEPS; s <= base + SKEW_STEPS; s++) {
            byte[] candidate = code(base32Secret, s).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(candidate, provided)) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    /// The `otpauth://totp/` URI authenticator apps scan, parameters in the
    /// order Go's `url.Values.Encode` emits (sorted by key).
    public static String uri(String issuer, String account, String base32Secret) {
        return "otpauth://totp/" + pathEncode(issuer) + ":" + pathEncode(account)
                + "?algorithm=SHA1&digits=" + DIGITS
                + "&issuer=" + queryEncode(issuer)
                + "&period=" + PERIOD_SECONDS
                + "&secret=" + base32Secret;
    }

    private static String pathEncode(String s) {
        // Go's url.PathEscape keeps ':' '@' etc.; a space is %20.
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%3A", ":").replace("%40", "@");
    }

    private static String queryEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
