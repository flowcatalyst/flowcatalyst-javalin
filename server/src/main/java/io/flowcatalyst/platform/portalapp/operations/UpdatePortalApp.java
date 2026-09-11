package io.flowcatalyst.platform.portalapp.operations;

import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalapp.operations.PortalAppEvents.PortalAppUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Applies the supplied fields (spec `portal-apps.md` §3.5, [PortalApp#update])
/// and emits [PortalAppUpdated]. `Authorize: Public` (spec §3) — the
/// controller gates manage-only.
public final class UpdatePortalApp {

    private UpdatePortalApp() {
    }

    public static Operation<UpdatePortalAppCommand, PortalAppUpdated> of(PortalAppRepository repo) {
        return Operation.<UpdatePortalAppCommand, PortalAppUpdated>named("UpdatePortalApp")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null && cmd.name().isBlank()) {
                        throw UseCaseException.validation("NAME_REQUIRED", "name cannot be empty");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    PortalApp app = Access.byId(repo, cmd.id(), cmd.clientId());
                    PortalApp updated = app.update(cmd.name(), cmd.description(), cmd.active());
                    return Plan.save(updated, repo, PortalAppUpdated.of(ec, updated));
                });
    }
}
