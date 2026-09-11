package io.flowcatalyst.platform.portalidentity.operations;

import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityAppRevoked;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Revokes a portal identity's access to one portal app (spec
/// `portal-apps.md` §3.2). `Authorize: Public` — the admin API's
/// `Checks.requirePortalUserManage` gates before calling in.
///
/// Idempotent at the aggregate level ([PortalIdentity#revoke] no-ops if the
/// grant is not held) but the event is emitted and the identity persisted on
/// every call regardless (spec: "both always persist and emit").
public final class RevokePortalIdentityApp {

    private RevokePortalIdentityApp() {
    }

    public static Operation<RevokePortalIdentityAppCommand, PortalIdentityAppRevoked> of(
            PortalIdentityRepository identities, PortalAppRepository apps) {
        return Operation.<RevokePortalIdentityAppCommand, PortalIdentityAppRevoked>named("RevokePortalIdentityApp")
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

                    PortalIdentity revoked = identity.revoke(app.id());
                    return Plan.save(revoked, identities, PortalIdentityAppRevoked.of(ec, revoked, app));
                });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
