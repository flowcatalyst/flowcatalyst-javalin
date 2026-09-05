package io.flowcatalyst.platform.passkey.operations;

import io.flowcatalyst.platform.passkey.Passkey;
import io.flowcatalyst.platform.passkey.PasskeyRepository;
import io.flowcatalyst.platform.passkey.PasskeyService;
import io.flowcatalyst.platform.passkey.operations.PasskeyEvents.PasskeyRegistered;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;

/// Stores a credential the ceremony already verified (§7.2 register/complete).
public final class RegisterPasskey {

    /// @param registered the verified ceremony result; never in the audit row
    public record RegisterCommand(String stateId, String name, @com.fasterxml.jackson.annotation.JsonIgnore PasskeyService.Registered registered) {
    }

    private RegisterPasskey() {
    }

    public static Operation<RegisterCommand, PasskeyRegistered> of(PasskeyRepository repo) {
        return Operation.<RegisterCommand, PasskeyRegistered>named("RegisterPasskey")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.stateId(), "STATE_ID_REQUIRED", "stateId is required");
                    if (cmd.registered() == null) {
                        throw UseCaseException.validation("CREDENTIAL_REQUIRED", "a verified credential is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    var r = cmd.registered();
                    Passkey p = Passkey.register(ec.principalId(), r.credentialId(), r.publicKeyCose(), r.signCount(), r.transports(),
                            r.aaguid(), r.userVerified(), r.backupEligible(), r.backedUp(), cmd.name(), Instant.now());
                    return Plan.save(p, repo, PasskeyRegistered.of(ec, p));
                });
    }
}
