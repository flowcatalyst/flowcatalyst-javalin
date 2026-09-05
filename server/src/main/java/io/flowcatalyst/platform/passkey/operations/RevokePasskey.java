package io.flowcatalyst.platform.passkey.operations;

import io.flowcatalyst.platform.passkey.Passkey;
import io.flowcatalyst.platform.passkey.PasskeyRepository;
import io.flowcatalyst.platform.passkey.operations.PasskeyEvents.PasskeyRevoked;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Deletes a credential (ownership is the route's business, §7.2).
public final class RevokePasskey {

    public record RevokeCommand(String id) {
    }

    private RevokePasskey() {
    }

    public static Operation<RevokeCommand, PasskeyRevoked> of(PasskeyRepository repo) {
        return Operation.<RevokeCommand, PasskeyRevoked>named("RevokePasskey")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Passkey p = repo.findById(cmd.id())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("WebauthnCredential", cmd.id()));
                    return Plan.delete(p, repo, PasskeyRevoked.of(ec, p));
                });
    }
}
