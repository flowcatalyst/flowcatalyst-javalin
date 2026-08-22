package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.ConfigAccess;
import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.platformconfig.operations.PlatformConfigEvents.AccessRevoked;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Removes a platform-config access grant and emits [AccessRevoked].
public final class RevokeAccess {

    private RevokeAccess() {
    }

    public static Operation<RevokeAccessCommand, AccessRevoked> of(ConfigAccessRepository grants) {
        return Operation.<RevokeAccessCommand, AccessRevoked>named("RevokeAccess")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Anchor-only at the handler; a grant has no per-instance authorization dimension (spec §6).
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    ConfigAccess a = grants.findById(cmd.id())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("PlatformConfigAccess", cmd.id()));
                    return Plan.delete(a, grants, AccessRevoked.of(ec, a));
                });
    }
}
