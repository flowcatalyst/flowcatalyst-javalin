package io.flowcatalyst.platform.portalidentity.operations;

import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentityStatus;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityStatusSet;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Activates or deactivates a portal identity (spec `auth-identity.md`
/// §5.7, §11.8). `Authorize: Public` — the admin API's controller gates
/// (`Checks.requirePortalUserManage`) before calling in.
///
/// The cross-client check runs **after** the load, exactly like every
/// other by-id write here: a `clientId` on the command that disagrees with
/// the loaded row's own `clientId` renders the *same* `PortalIdentity_NOT_FOUND`
/// as a genuinely missing id — never a distinct "forbidden" — so this
/// endpoint cannot be used to probe whether an id exists under a client the
/// caller cannot see (spec §5.7: "cross-client hidden").
public final class SetPortalIdentityStatus {

    private SetPortalIdentityStatus() {
    }

    public static Operation<SetStatusCommand, PortalIdentityStatusSet> of(PortalIdentityRepository repo) {
        return Operation.<SetStatusCommand, PortalIdentityStatusSet>named("SetPortalIdentityStatus")
                .validate(cmd -> {
                    boolean hasId = cmd.id() != null && !cmd.id().isBlank();
                    boolean hasClientAndEmail = cmd.clientId() != null && !cmd.clientId().isBlank()
                            && cmd.email() != null && !cmd.email().isBlank();
                    if (!hasId && !hasClientAndEmail) {
                        throw UseCaseException.validation("TARGET_REQUIRED", "id, or clientId and email, is required");
                    }
                    PortalIdentityStatus.parse(cmd.status()); // STATUS_INVALID on a bad value
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    PortalIdentity existing = load(repo, cmd);
                    if (cmd.clientId() != null && !cmd.clientId().isBlank() && !cmd.clientId().equals(existing.clientId())) {
                        throw UseCaseException.resourceNotFound("PortalIdentity", targetLabel(cmd));
                    }
                    PortalIdentityStatus status = PortalIdentityStatus.parse(cmd.status());
                    PortalIdentity updated = status == PortalIdentityStatus.ACTIVE ? existing.activate() : existing.deactivate();
                    return Plan.save(updated, repo, PortalIdentityStatusSet.of(ec, updated));
                });
    }

    private static PortalIdentity load(PortalIdentityRepository repo, SetStatusCommand cmd) {
        if (cmd.id() != null && !cmd.id().isBlank()) {
            return repo.findById(cmd.id())
                    .orElseThrow(() -> UseCaseException.resourceNotFound("PortalIdentity", cmd.id()));
        }
        return repo.findByClientAndEmail(cmd.clientId(), cmd.email())
                .orElseThrow(() -> UseCaseException.resourceNotFound("PortalIdentity", targetLabel(cmd)));
    }

    private static String targetLabel(SetStatusCommand cmd) {
        return cmd.id() != null && !cmd.id().isBlank() ? cmd.id() : cmd.email();
    }
}
