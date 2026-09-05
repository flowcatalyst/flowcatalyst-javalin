package io.flowcatalyst.platform.auth.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/// PKCE verification at the token endpoint (`docs/spec/auth-core.md` §6.2a
/// "authorization_code", Go `verifyPKCE`): the verifier's length and
/// alphabet per RFC 7636 §4.1, `S256` (the default when the stored method
/// is absent) or `plain`, and a constant-time compare so neither method
/// leaks match-prefix timing.
public final class Pkce {

    private Pkce() {
    }

    /// The RFC 6749 error a failed verification maps to; `null` when it passed.
    public static OAuthError verify(String challenge, String method, String verifier) {
        if (verifier == null || verifier.isEmpty()) {
            return OAuthError.invalidGrant("Missing code_verifier");
        }
        if (verifier.length() < 43 || verifier.length() > 128) {
            return OAuthError.invalidGrant("code_verifier must be 43-128 characters");
        }
        for (int i = 0; i < verifier.length(); i++) {
            if (!isUnreserved(verifier.charAt(i))) {
                return OAuthError.invalidGrant("code_verifier contains invalid characters");
            }
        }
        String m = method == null || method.isEmpty() ? "S256" : method;
        String computed = "S256".equals(m) ? s256(verifier) : verifier;
        if (!MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8), challenge.getBytes(StandardCharsets.UTF_8))) {
            return OAuthError.invalidGrant("Invalid code_verifier");
        }
        return null;
    }

    /// `base64url(sha256(verifier))`, unpadded — what a client sends as its challenge.
    public static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
