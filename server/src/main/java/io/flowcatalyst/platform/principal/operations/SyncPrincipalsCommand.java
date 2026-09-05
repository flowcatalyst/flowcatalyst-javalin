package io.flowcatalyst.platform.principal.operations;

import java.util.List;

/// The input DTO for [SyncPrincipals] (audit `operation` =
/// `SyncPrincipalsCommand`). `applicationCode` is `null` for the
/// platform-level `POST /api/principals/sync`; otherwise it is event
/// provenance AND (spec X-02(c), ruled 2026-09-01) the `removeUnlisted`
/// sweep's application narrowing — it is never used to prefix a role name.
public record SyncPrincipalsCommand(String applicationCode, List<SyncPrincipalInput> principals, boolean removeUnlisted) {
    public SyncPrincipalsCommand {
        principals = principals == null ? List.of() : List.copyOf(principals);
    }
}
