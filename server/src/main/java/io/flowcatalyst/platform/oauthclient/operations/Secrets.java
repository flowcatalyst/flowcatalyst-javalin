package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/// Client-secret generation and encryption, shared by [CreateOAuthClient] and
/// [RotateOAuthClientSecret] (spec `auth-core.md` §3.6, §6.3).
final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /// 32 random bytes, base64url with no padding — shown once by the handler.
    static String generatePlaintext() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /// The at-rest ciphertext ref for `plaintext` (`"encrypted:" + …`).
    ///
    /// @throws UseCaseException internal `SECRET` when no app key is configured
    static String encryptedRef(Optional<Encryption> encryption, String plaintext) {
        if (encryption.isEmpty()) {
            throw UseCaseException.internal("SECRET",
                    "FLOWCATALYST_APP_KEY not configured; cannot encrypt client secret", null);
        }
        return encryption.get().encryptSecretRef(plaintext);
    }
}
