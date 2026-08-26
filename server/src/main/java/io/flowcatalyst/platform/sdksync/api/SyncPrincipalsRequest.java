package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.principal.operations.SyncPrincipalInput;
import io.flowcatalyst.platform.principal.operations.SyncPrincipalsCommand;

import java.util.List;

/// `SyncPrincipalsRequest` (lockfile):
/// `{principals[]: {email, name, roles[], active, passwordHash}}`.
public record SyncPrincipalsRequest(List<Input> principals) {

    /// @param active absent ⇒ `true`. **The one wire default applied on this
    ///               surface** (spec §3): the command field is a primitive
    ///               `boolean`, so the aggregate has no "absent" to see and
    ///               this mapping is the only place the default can live.
    ///               Defaults are otherwise the domain's (CONVENTIONS
    ///               "defaults are domain, not transport") — flagged in the
    ///               spec in case the owner prefers a `Boolean` command field.
    public record Input(String email, String name, List<String> roles, Boolean active, String passwordHash) {
        SyncPrincipalInput toInput() {
            return new SyncPrincipalInput(email, name, roles, active == null || active, passwordHash);
        }
    }

    SyncPrincipalsCommand toCommand(String applicationCode, boolean removeUnlisted) {
        return new SyncPrincipalsCommand(applicationCode,
                principals == null ? List.of() : principals.stream().map(Input::toInput).toList(), removeUnlisted);
    }
}
