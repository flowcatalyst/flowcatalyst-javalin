package io.flowcatalyst.platform.principal.operations;

import java.util.List;

/// The input DTO for [SyncPrincipals] (audit `operation` =
/// `SyncPrincipalsCommand`). `applicationCode` is event provenance only —
/// `null` for the platform-level `POST /api/principals/sync`; it scopes and
/// prefixes nothing (spec §7).
public record SyncPrincipalsCommand(String applicationCode, List<SyncPrincipalInput> principals, boolean removeUnlisted) {
    public SyncPrincipalsCommand {
        principals = principals == null ? List.of() : List.copyOf(principals);
    }
}
