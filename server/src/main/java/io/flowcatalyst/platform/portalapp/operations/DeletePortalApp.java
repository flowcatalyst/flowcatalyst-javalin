package io.flowcatalyst.platform.portalapp.operations;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientDeleted;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalapp.operations.PortalAppEvents.PortalAppDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Deletes a portal app AND every OAuth client linked to it, in ONE
/// transaction (spec `portal-apps.md` §3.6, Part A J5). Deliberately deletes
/// rather than unlinks the OAuth clients — an unlinked portal client would
/// fall back to a legacy client-wide portal, widening access (spec §3.6,
/// §2.4). `Authorize: Public` (spec §3) — the controller gates manage-only.
public final class DeletePortalApp {

    private DeletePortalApp() {
    }

    /// @param portalAppId          the deleted app's id
    /// @param deletedOAuthClientIds the deleted OAuth clients' `client_id` strings, in name order
    public record Result(String portalAppId, List<String> deletedOAuthClientIds) {
        public Result {
            Objects.requireNonNull(portalAppId, "portalAppId");
            deletedOAuthClientIds = deletedOAuthClientIds == null ? List.of() : List.copyOf(deletedOAuthClientIds);
        }
    }

    public static TxOperation<DeletePortalAppCommand, Result> of(PortalAppRepository apps, OAuthClientRepository oauthClients) {
        return TxOperation.<DeletePortalAppCommand, Result>named("DeletePortalApp")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    PortalApp app = Access.byId(apps, cmd.id(), cmd.clientId());

                    List<String> deletedClientIds = new ArrayList<>();
                    for (OAuthClient oc : oauthClients.findByPortalAppId(app.id())) {
                        scoped.commitDelete(oc, oauthClients, OAuthClientDeleted.of(ec, oc), cmd);
                        deletedClientIds.add(oc.clientId());
                    }

                    // The app's own grants cascade via the FK (spec §10) — no explicit cleanup.
                    scoped.commitDelete(app, apps, PortalAppDeleted.of(ec, app), cmd);

                    return new Result(app.id(), deletedClientIds);
                });
    }
}
