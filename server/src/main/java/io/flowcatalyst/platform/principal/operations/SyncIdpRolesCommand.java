package io.flowcatalyst.platform.principal.operations;

import java.util.List;

/// The input DTO for [SyncIdpRoles] (audit `operation` = `SyncIdpRolesCommand`):
/// the internal role names the IdP claim resolved to, after the
/// email-domain mapping's allowed-roles filter. Empty = the user lost every
/// group upstream, so every `IDP_SYNC` assignment goes.
public record SyncIdpRolesCommand(String userId, List<String> platformRoles) {
    public SyncIdpRolesCommand {
        platformRoles = platformRoles == null ? List.of() : List.copyOf(platformRoles);
    }
}
