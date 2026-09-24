package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.shared.SecureTokens;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Optional;

/// Client-secret generation and keyed hashing, shared by [CreateOAuthClient]
/// and [RotateOAuthClientSecret] (spec `auth-core.md` §3.6, §6.3;
/// `docs/spec/encryption.md` §3: an OAuth client secret is verify-only —
/// the platform only ever compares it, never sends or signs with it — so
/// it is stored `hashed:v1:…`, not reversibly encrypted).
///
/// Public: `application.operations.ProvisionServiceAccount` (spec
/// `application.md` §10) mints the service-account client's secret the same
/// way, from outside this package. This is the other visibility widening
/// this unit made outside its listed files — see the port brief's report.
public final class Secrets {


    private Secrets() {
    }

    /// 32 random bytes, base64url with no padding — shown once by the handler.
    public static String generatePlaintext() {
        return SecureTokens.urlSafe(32);
    }

    /// The at-rest keyed-hash ref for `plaintext` (`"hashed:v1:" + …`) —
    /// never decryptable, only verified against a caller-supplied secret.
    ///
    /// @throws UseCaseException internal `SECRET` when no app key is configured
    public static String hashedRef(Optional<Encryption> encryption, String plaintext) {
        if (encryption.isEmpty()) {
            throw UseCaseException.internal("SECRET",
                    "FLOWCATALYST_APP_KEY not configured; cannot hash client secret", null);
        }
        return encryption.get().hashSecretRef(plaintext);
    }
}
