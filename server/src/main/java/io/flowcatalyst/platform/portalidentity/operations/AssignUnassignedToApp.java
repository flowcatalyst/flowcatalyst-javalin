package io.flowcatalyst.platform.portalidentity.operations;

import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalidentity.PortalAppGrantSource;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityAppGranted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The gap-closer for identities holding no portal app at all — e.g.
/// created before portal apps existed, so once their OAuth client is linked
/// to an app the gate refuses them (spec `portal-apps.md` §3.2a). Grants one
/// app to every one of a client's portal identities that currently holds no
/// grant, ONE transaction (template: `portalapp.operations.DeletePortalApp`).
/// `Authorize: Public` (spec §3) — the controller gates manage-only.
public final class AssignUnassignedToApp {

    private AssignUnassignedToApp() {
    }

    /// @param appId       the app every identity was granted
    /// @param appCode     the app's normalised code
    /// @param identityIds the identities granted, in the load order (`created_at, id`)
    public record Result(String appId, String appCode, List<String> identityIds) {
        public Result {
            Objects.requireNonNull(appId, "appId");
            Objects.requireNonNull(appCode, "appCode");
            identityIds = identityIds == null ? List.of() : List.copyOf(identityIds);
        }
    }

    public static TxOperation<AssignUnassignedToAppCommand, Result> of(
            PortalIdentityRepository identities, PortalAppRepository apps) {
        return TxOperation.<AssignUnassignedToAppCommand, Result>named("AssignUnassignedToApp")
                .validate(cmd -> {
                    if (isBlank(cmd.clientId()) || isBlank(cmd.portalAppId())) {
                        throw UseCaseException.validation("TARGET_REQUIRED", "clientId and portalAppId are required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    PortalApp app = apps.findById(cmd.portalAppId())
                            .filter(a -> a.clientId().equals(cmd.clientId()))
                            .orElseThrow(() -> UseCaseException.resourceNotFound("PortalApp", cmd.portalAppId()));
                    if (!app.active()) {
                        throw UseCaseException.validation("PORTAL_APP_INACTIVE",
                                "portal app '" + app.code() + "' is inactive");
                    }

                    List<String> grantedIds = new ArrayList<>();
                    for (PortalIdentity identity : identities.findUnassigned(cmd.clientId())) {
                        PortalIdentity granted = identity.grant(app.id(), PortalAppGrantSource.ADMIN);
                        scoped.commit(granted, identities, PortalIdentityAppGranted.of(ec, granted, app, PortalAppGrantSource.ADMIN.name()), cmd);
                        grantedIds.add(granted.id());
                    }

                    return new Result(app.id(), app.code(), grantedIds);
                });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
