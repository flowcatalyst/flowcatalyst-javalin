package io.flowcatalyst.platform.identityprovider.api;

import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.encryption.SecretRef;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;
import java.util.Optional;

/// How an incoming `oidcClientSecretRef` becomes the stored form (spec §5):
/// with a key configured, plaintext is sealed to `encrypted:…`; without one,
/// plaintext is refused (`ENCRYPTION_NOT_CONFIGURED`) and only values that
/// are already safe at rest (`encrypted:`, external refs, `literal:`) pass.
/// The conversion runs in the handler, before the command is built, so the
/// audit row never carries a plaintext secret. Models the "encryption may
/// be absent" case (`Encryption.fromKeys` → `Optional`) as a sealed pair
/// instead of an `Optional` component; the composition root builds it from
/// `Env.appKey()` / `Env.appKeyPrevious()`, never from the process
/// environment directly (fcdev loads its environment from a map).
public sealed interface ClientSecretEncryption permits ClientSecretEncryption.Enabled, ClientSecretEncryption.Disabled {

    /// Storable form of `incoming`: `null` stays `null` (absent); blank stays
    /// blank (create: no secret, update: clear); everything else per spec §5.
    ///
    /// @throws UseCaseException validation `INVALID_SECRET_REF`
    ///         (an `encrypted:` claim whose payload is not base64) or `ENCRYPTION_NOT_CONFIGURED`
    String atRest(String incoming);

    /// A key configured → [Enabled]; none → [Disabled] (a malformed key never
    /// gets this far: `Encryption.fromKeys` is fatal on one, `encryption.md` §1).
    static ClientSecretEncryption of(Optional<Encryption> encryption) {
        return encryption.<ClientSecretEncryption>map(Enabled::new).orElse(Disabled.INSTANCE);
    }

    /// A key is configured: plaintext is sealed, at-rest values pass through.
    record Enabled(Encryption encryption) implements ClientSecretEncryption {
        public Enabled {
            Objects.requireNonNull(encryption, "encryption");
        }

        @Override
        public String atRest(String incoming) {
            if (incoming == null) return null;
            try {
                return encryption.encryptSecretRef(incoming);
            } catch (Encryption.UnsupportedSchemeException e) {
                throw HttpError.badRequest("UNSUPPORTED_SECRET_SCHEME", "oidcClientSecretRef: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw HttpError.badRequest("INVALID_SECRET_REF", "oidcClientSecretRef: " + e.getMessage());
            }
        }
    }

    /// No key: a plaintext secret cannot be stored — it would sit in the
    /// column verbatim and then fail to decrypt at login.
    enum Disabled implements ClientSecretEncryption {
        INSTANCE;

        @Override
        public String atRest(String incoming) {
            if (incoming == null) return null;
            SecretRef ref;
            try {
                ref = SecretRef.parse(incoming);
            } catch (IllegalArgumentException e) {
                throw HttpError.badRequest("INVALID_SECRET_REF", "oidcClientSecretRef: " + e.getMessage());
            }
            return switch (ref) {
                case SecretRef.AtRest r -> r.stored();
                case SecretRef.Plain _ -> throw HttpError.badRequest("ENCRYPTION_NOT_CONFIGURED",
                        "cannot store OIDC client secret: " + Encryption.ENV_APP_KEY + " is not configured");
            };
        }
    }
}
