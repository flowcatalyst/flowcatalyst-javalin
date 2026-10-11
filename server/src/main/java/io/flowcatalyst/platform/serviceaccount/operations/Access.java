package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Load-or-404 and the one per-resource rule of this unit.
///
/// Service accounts have no client dimension (spec §4.2–§4.6: client-scoped
/// users manage no service account — every write needs anchor reach at the
/// handler), so the by-id load is `byId`, not `loadScoped`. The per-resource
/// rule is the **application** confinement (2026-10-11 F4, Go `0ae0988`): a
/// caller without all-applications access (an application's own service
/// account) manages only service accounts bound to an application it can
/// access — never a platform-wide one or another application's. Every write
/// operation's `authorize` phase calls [#requireWrite] (create:
/// [#requireApplicationReach] on the requested application); the token mint,
/// which is not an operation, calls [#requireWrite] from its handler.
public final class Access {

    private Access() {
    }

    /// @throws UseCaseException not-found `ServiceAccount_NOT_FOUND`
    static ServiceAccount byId(ServiceAccountRepository repo, String id) {
        return repo.findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("ServiceAccount", id));
    }

    /// The application confinement on the service account `applicationId`
    /// belongs to (`null` = platform-wide). A caller with all-applications
    /// access is not confined.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED` |
    ///                          `APPLICATION_ACCESS_REQUIRED`
    public static void requireApplicationReach(AuthContext ac, String applicationId) {
        if (ac == null) throw UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
        if (!ac.isApplicationScoped()) return;
        if (applicationId != null && ac.canAccessApplication(applicationId)) return;
        throw UseCaseException.authorization("APPLICATION_ACCESS_REQUIRED",
                "not authorised for this service account's application");
    }

    /// The application confinement on an existing service account. Loads it
    /// only for a confined caller (an unknown id is then 404 here); anyone else
    /// is not confined and the operation's own load answers the 404.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED` |
    ///                          `APPLICATION_ACCESS_REQUIRED`, not-found `ServiceAccount_NOT_FOUND`
    public static void requireWrite(ServiceAccountRepository repo, AuthContext ac, String id) {
        if (ac == null) throw UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
        if (!ac.isApplicationScoped()) return;
        requireApplicationReach(ac, byId(repo, id).applicationId());
    }
}
