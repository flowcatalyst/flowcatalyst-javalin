package io.flowcatalyst.platform.portalidentity.operations;

import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalidentity.PortalAppGrantSource;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityAppGranted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Grants a portal identity access to one of the client's portal apps (spec
/// `portal-apps.md` §3.2). `Authorize: Public` — the admin API's
/// `Checks.requirePortalUserManage` gates before calling in.
///
/// Idempotent at the aggregate level ([PortalIdentity#grant] no-ops if the
/// grant is already held) but the event is emitted and the identity
/// persisted on every call regardless — never skipped because nothing
/// visibly changed (spec: "both always persist and emit").
public final class GrantPortalIdentityApp {

    private GrantPortalIdentityApp() {
    }

    public static Operation<GrantPortalIdentityAppCommand, PortalIdentityAppGranted> of(
            PortalIdentityRepository identities, PortalAppRepository apps) {
        return Operation.<GrantPortalIdentityAppCommand, PortalIdentityAppGranted>named("GrantPortalIdentityApp")
                .validate(cmd -> {
                    if (isBlank(cmd.clientId()) || isBlank(cmd.identityId()) || isBlank(cmd.portalAppId())) {
                        throw UseCaseException.validation("TARGET_REQUIRED", "clientId, identityId and portalAppId are required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    PortalIdentity identity = identities.findById(cmd.identityId())
                            .filter(pi -> pi.clientId().equals(cmd.clientId()))
                            .orElseThrow(() -> UseCaseException.resourceNotFound("PortalIdentity", cmd.identityId()));
                    PortalApp app = apps.findById(cmd.portalAppId())
                            .filter(a -> a.clientId().equals(cmd.clientId()))
                            .orElseThrow(() -> UseCaseException.resourceNotFound("PortalApp", cmd.portalAppId()));

                    PortalIdentity granted = identity.grant(app.id(), PortalAppGrantSource.ADMIN);
                    return Plan.save(granted, identities, PortalIdentityAppGranted.of(ec, granted, app, PortalAppGrantSource.ADMIN.name()));
                });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
