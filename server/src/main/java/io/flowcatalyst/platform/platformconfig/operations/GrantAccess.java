package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.ConfigAccess;
import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.platformconfig.operations.PlatformConfigEvents.AccessGranted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates — or, for an existing `(application, role)` pair, re-grants in
/// place — a platform-config access grant and emits [AccessGranted].
public final class GrantAccess {

    private GrantAccess() {
    }

    public static Operation<GrantAccessCommand, AccessGranted> of(ConfigAccessRepository grants) {
        return Operation.<GrantAccessCommand, AccessGranted>named("GrantAccess")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_REQUIRED", "applicationCode is required");
                    UseCaseException.requireNonBlank(cmd.roleCode(), "ROLE_REQUIRED", "roleCode is required");
                })
                // Anchor-only at the handler; a grant has no per-instance authorization dimension (spec §6).
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    ConfigAccess a = grants.findByRole(cmd.applicationCode(), cmd.roleCode())
                            .orElseGet(() -> ConfigAccess.create(cmd.applicationCode(), cmd.roleCode()))
                            .grant(cmd.canWrite());
                    return Plan.save(a, grants, AccessGranted.of(ec, a));
                });
    }
}
