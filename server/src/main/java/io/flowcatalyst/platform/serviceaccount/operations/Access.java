package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 — the opening of every by-id write operation's execute phase.
/// Every write operation in this unit is admin-managed with no per-resource
/// scope check (spec §4.2–§4.6: "the coarse permission is enforced at the
/// controller; this admin-managed operation has no per-client resource
/// check"), so this names its helper `byId`, not `loadScoped`, per
/// CONVENTIONS §2 — which is *why* every by-id operation here declares
/// `Authorize.publicAccess()`.
final class Access {

    private Access() {
    }

    /// @throws UseCaseException not-found `ServiceAccount_NOT_FOUND`
    static ServiceAccount byId(ServiceAccountRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("ServiceAccount", id));
    }
}
