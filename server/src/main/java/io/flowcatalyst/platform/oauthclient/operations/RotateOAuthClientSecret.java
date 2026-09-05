package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientSecretRotated;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/// Mints a fresh secret for a `CONFIDENTIAL` client, keeps the outgoing one
/// acceptable for the grace window (A-22, `docs/improvements.md`), and emits
/// [OAuthClientSecretRotated]. Platform-level config (`Authorize.publicAccess()`);
/// the controller gates anchor-only (spec §6.3).
public final class RotateOAuthClientSecret {

    /// How long the outgoing secret keeps working after a rotation unless
    /// the caller says otherwise — long enough to roll a fleet across a
    /// normal deployment window without being an open-ended second credential.
    public static final Duration DEFAULT_GRACE = Duration.ofHours(24);

    private RotateOAuthClientSecret() {
    }

    /// @param disclose receives the new plaintext secret, once, before commit
    public static Operation<RotateSecretCommand, OAuthClientSecretRotated> of(
            OAuthClientRepository repo, Optional<Encryption> encryption, Consumer<String> disclose) {
        Objects.requireNonNull(disclose, "disclose");
        return Operation.<RotateSecretCommand, OAuthClientSecretRotated>named("RotateOAuthClientSecret")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "id is required");
                    }
                    if (cmd.graceSeconds() != null && cmd.graceSeconds() < 0) {
                        throw UseCaseException.validation("GRACE_INVALID", "graceSeconds must not be negative");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    OAuthClient c = Access.byId(repo, cmd.id());
                    if (c.clientType() != ClientType.CONFIDENTIAL) {
                        throw UseCaseException.conflict("NOT_CONFIDENTIAL", "Only CONFIDENTIAL clients have rotatable secrets");
                    }
                    String plaintext = Secrets.generatePlaintext();
                    String ref = Secrets.encryptedRef(encryption, plaintext);
                    Duration grace = cmd.graceSeconds() != null ? Duration.ofSeconds(cmd.graceSeconds()) : DEFAULT_GRACE;
                    OAuthClient.RotateResult result = c.rotateSecret(ref, grace, Instant.now());
                    disclose.accept(plaintext);
                    return Plan.save(result.client(), repo,
                            OAuthClientSecretRotated.of(ec, result.client(), result.previousSecretExpiresAt()));
                });
    }
}
