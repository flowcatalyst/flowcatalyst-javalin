package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The per-application access rules (spec §3): platform config has no
/// permission codes — a principal is an anchor, or holds a role with a
/// grant on the application. One definition serves the handler gates (list,
/// get, delete) and [SetProperty]'s authorize phase, so the rule is never
/// written twice. It is the aggregate's counterpart of
/// `Checks.checkApplicationAccess`: resource-level (the application is the
/// resource), DB-backed, and raised as a [UseCaseException] so the HTTP
/// layer renders it like every other authorization failure.
public final class Access {

    private Access() {
    }

    /// Anchor, or a role with a `canRead` grant on `applicationCode`.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED` | `FORBIDDEN`
    ///                          `No read access to platform config for <app>`
    public static void requireRead(ConfigAccessRepository grants, AuthContext ac, String applicationCode) {
        if (ac == null) throw unauthenticated();
        if (ac.isAnchor() || grants.canRead(applicationCode, ac.roles())) return;
        throw UseCaseException.authorization("FORBIDDEN", "No read access to platform config for " + applicationCode);
    }

    /// Anchor, or a role with a `canWrite` grant on `applicationCode`.
    ///
    /// @throws UseCaseException authorization `UNAUTHENTICATED` | `FORBIDDEN`
    ///                          `No write access to platform config for <app>`
    public static void requireWrite(ConfigAccessRepository grants, AuthContext ac, String applicationCode) {
        if (ac == null) throw unauthenticated();
        if (ac.isAnchor() || grants.canWrite(applicationCode, ac.roles())) return;
        throw UseCaseException.authorization("FORBIDDEN", "No write access to platform config for " + applicationCode);
    }

    private static UseCaseException unauthenticated() {
        return UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
    }
}
