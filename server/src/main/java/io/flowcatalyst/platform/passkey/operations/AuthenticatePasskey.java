package io.flowcatalyst.platform.passkey.operations;

import io.flowcatalyst.platform.passkey.Passkey;
import io.flowcatalyst.platform.passkey.PasskeyRepository;
import io.flowcatalyst.platform.passkey.operations.PasskeyEvents.PasskeyAuthenticated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;

/// A successful assertion moves the credential's counter and stamps its
/// use (ruling I-Q13 — Go never persisted either), and emits
/// `passkey:authenticated`.
public final class AuthenticatePasskey {

    public record AuthenticateCommand(String stateId, String credentialId, long signCount, boolean userVerified, boolean backedUp) {
    }

    private AuthenticatePasskey() {
    }

    public static Operation<AuthenticateCommand, PasskeyAuthenticated> of(PasskeyRepository repo) {
        return Operation.<AuthenticateCommand, PasskeyAuthenticated>named("AuthenticatePasskey")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.stateId(), "STATE_ID_REQUIRED", "stateId is required");
                    UseCaseException.requireNonBlank(cmd.credentialId(), "CREDENTIAL_ID_REQUIRED", "persisted credentialId is required");
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Passkey p = repo.findById(cmd.credentialId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("WebauthnCredential", cmd.credentialId()));
                    Passkey used = p.authenticated(cmd.signCount(), cmd.userVerified(), cmd.backedUp(), Instant.now());
                    return Plan.save(used, repo, PasskeyAuthenticated.of(ec, used));
                });
    }
}
