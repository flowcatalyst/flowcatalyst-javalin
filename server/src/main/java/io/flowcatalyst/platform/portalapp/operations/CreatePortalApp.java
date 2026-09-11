package io.flowcatalyst.platform.portalapp.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalapp.operations.PortalAppEvents.PortalAppCreated;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a portal app as a single aggregate (spec `portal-apps.md` §3.3) —
/// used internally / by tests; the HTTP API's `POST /api/portal-apps` always
/// runs [CreatePortalAppWithOAuthClient] (§3.4) instead, one transaction with
/// the provisioned OAuth client. `Authorize: Public` (spec §3: "all
/// operations are authorize-public at the operation layer") — the controller
/// gates.
public final class CreatePortalApp {

    private CreatePortalApp() {
    }

    public static Operation<CreatePortalAppCommand, PortalAppCreated> of(PortalAppRepository repo, ClientRepository clients) {
        return Operation.<CreatePortalAppCommand, PortalAppCreated>named("CreatePortalApp")
                .validate(cmd -> PortalAppCreation.validateFields(cmd.clientId(), cmd.code(), cmd.name()))
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    PortalApp app = PortalAppCreation.create(repo, clients, cmd.clientId(), cmd.code(), cmd.name(), cmd.description());
                    return Plan.save(app, repo, PortalAppCreated.of(ec, app));
                });
    }
}
