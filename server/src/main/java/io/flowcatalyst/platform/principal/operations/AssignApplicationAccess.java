package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.ApplicationAccessAssigned;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces a principal's explicit application-access set (and, when given,
/// its all-applications flag) and emits [ApplicationAccessAssigned] (spec
/// §6). Both users and service accounts carry per-application scope —
/// service accounts are the main case for confining a principal to specific
/// applications. The per-resource rule (`Access.requireUserAdmin`) runs
/// post-load; the bounding for non-anchor admins is the handler's (spec §5.3).
public final class AssignApplicationAccess {

    private AssignApplicationAccess() {
    }

    public static Operation<AssignApplicationAccessCommand, ApplicationAccessAssigned> of(PrincipalRepository repo,
                                                                                          ApplicationRepository applications) {
        return Operation.<AssignApplicationAccessCommand, ApplicationAccessAssigned>named("AssignApplicationAccess")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.userId(), "USER_ID_REQUIRED", "User ID is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource rule runs post-load: Access.requireUserAdmin
                .execute((cmd, ec) -> {
                    Principal p = Access.loadUser(repo, cmd.userId());
                    Access.requireUserAdmin(p);
                    for (String appId : cmd.applicationIds()) {
                        Application app = applications.findById(appId)
                                .orElseThrow(() -> UseCaseException.validation("APPLICATION_NOT_FOUND", "Application not found: " + appId));
                        if (!app.active()) {
                            throw UseCaseException.businessRule("APPLICATION_INACTIVE", "Application is not active: " + appId);
                        }
                    }
                    var change = p.assignApplicationAccess(cmd.applicationIds(), cmd.allApplications());
                    return Plan.save(change.principal(), repo.withApplicationAccess(), ApplicationAccessAssigned.of(ec, change));
                });
    }
}
