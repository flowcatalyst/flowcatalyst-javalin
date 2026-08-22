package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationServiceAccountProvisioned;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Attaches an existing service account to an application — the row stores
/// the service account's *principal* id (spec §1.1) — and emits
/// [ApplicationServiceAccountProvisioned].
public final class AttachServiceAccount {

    private AttachServiceAccount() {
    }

    public static Operation<AttachServiceAccountCommand, ApplicationServiceAccountProvisioned> of(ApplicationRepository repo) {
        return Operation.<AttachServiceAccountCommand, ApplicationServiceAccountProvisioned>named("AttachServiceAccount")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationId(), "APPLICATION_ID_REQUIRED", "Application ID is required");
                    UseCaseException.requireNonBlank(cmd.serviceAccountId(), "SERVICE_ACCOUNT_ID_REQUIRED", "Service account ID is required");
                })
                .authorize(Operation.Authorize.publicAccess()) // platform-level; load-or-404 is in Access.byId
                .execute((cmd, ec) -> {
                    Application a = Access.byId(repo, cmd.applicationId());
                    if (a.hasServiceAccount()) {
                        // Checked before the principal lookup so the 409 business rule wins over a 404 (spec §6).
                        throw UseCaseException.businessRule("APPLICATION_HAS_SERVICE_ACCOUNT",
                                "Application already has a service account provisioned");
                    }
                    String principalId = repo.servicePrincipalIdFor(cmd.serviceAccountId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("ServiceAccountPrincipal", cmd.serviceAccountId()));
                    Application attached = a.attachServiceAccount(principalId);
                    return Plan.save(attached, repo,
                            ApplicationServiceAccountProvisioned.of(ec, attached, cmd.serviceAccountId(), cmd.serviceAccountCode()));
                });
    }
}
