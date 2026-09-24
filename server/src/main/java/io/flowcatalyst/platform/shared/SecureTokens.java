package io.flowcatalyst.platform.shared;

import java.security.SecureRandom;
import java.util.Base64;

/// The one way the platform makes an opaque random token (ceremony ids,
/// OIDC state and nonce, reset and invite tokens, trusted-device tokens,
/// client and developer secrets, webhook signing secrets): `bytes` bytes from
/// [SecureRandom], URL-safe base64 without padding. One place, so no call site
/// can drift to `java.util.Random` or the padded, `+`/`/`-bearing encoder.
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {
    }

    /// `bytes` random bytes as URL-safe, unpadded base64 (`ceil(bytes * 4 / 3)` characters).
    public static String urlSafe(int bytes) {
        if (bytes < 16) {
            throw new IllegalArgumentException("a token needs at least 16 random bytes, got " + bytes);
        }
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return URL_SAFE.encodeToString(b);
    }
}
