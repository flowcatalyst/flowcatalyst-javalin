package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// The developer client-secret's two side channels (spec §2): minting +
/// encrypting a fresh secret, and the process-local one-shot stash through
/// which the plaintext reaches the HTTP response exactly once. The operation
/// returns only an event, and events never carry secrets, so the handler
/// pops the plaintext here by principal id right after the commit — the
/// same one-time-disclosure shape service-account rotation uses.
///
/// One instance per composition root; `toString` never prints a secret.
public final class DeveloperSecrets {

    /// How long an un-popped plaintext may sit in memory.
    static final Duration STASH_TTL = Duration.ofMinutes(2);

    private static final int SECRET_BYTES = 32;

    private final Encryption encryption; // null = app key not configured
    private final SecureRandom random = new SecureRandom();
    /// Principal id → issued plaintext; entries are evicted on pop, on TTL, and on the next stash.
    private final Map<String, Stashed> stash = new ConcurrentHashMap<>();

    private DeveloperSecrets(Encryption encryption) {
        this.encryption = encryption;
    }

    /// Secrets encrypted under the platform app key.
    public static DeveloperSecrets withEncryption(Encryption encryption) {
        return new DeveloperSecrets(Objects.requireNonNull(encryption, "encryption"));
    }

    /// No app key: issuing a secret fails with 500 `SECRET`.
    public static DeveloperSecrets unconfigured() {
        return new DeveloperSecrets(null);
    }

    /// A freshly issued secret: the plaintext (returned once) and its encrypted at-rest form.
    public record Issued(String plaintext, String encryptedRef) {
        @Override
        public String toString() {
            return "Issued[***]";
        }
    }

    /// Mints 32 random bytes → base64url (no padding) and encrypts them; the
    /// plaintext is parked under `principalId` for [#pop].
    ///
    /// @throws UseCaseException internal `SECRET` when no app key is configured
    Issued issueFor(String principalId) {
        if (encryption == null) {
            throw UseCaseException.internal("SECRET", "FLOWCATALYST_APP_KEY not configured; cannot encrypt developer client secret", null);
        }
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var issued = new Issued(plaintext, encryption.encrypt(plaintext));
        Instant now = Instant.now();
        stash.values().removeIf(s -> s.isExpired(now));
        stash.put(principalId, new Stashed(plaintext, now));
        return issued;
    }

    /// Removes and returns the plaintext issued for `principalId`, if it was
    /// issued in this process within the TTL and not yet disclosed.
    public Optional<String> pop(String principalId) {
        Stashed s = stash.remove(principalId);
        return s == null || s.isExpired(Instant.now()) ? Optional.empty() : Optional.of(s.plaintext());
    }

    private record Stashed(String plaintext, Instant storedAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(storedAt.plus(STASH_TTL));
        }

        @Override
        public String toString() {
            return "Stashed[***]";
        }
    }

    @Override
    public String toString() {
        return "DeveloperSecrets[configured=" + (encryption != null) + "]";
    }
}
