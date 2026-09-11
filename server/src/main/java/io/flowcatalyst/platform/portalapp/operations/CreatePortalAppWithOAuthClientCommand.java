package io.flowcatalyst.platform.portalapp.operations;

import java.util.List;

/// `CreatePortalAppWithOAuthClient`'s command (spec `portal-apps.md` §3.4) —
/// [CreatePortalAppCommand]'s fields plus the provisioned OAuth client's
/// shape. `clientType`: blank/`null` ⇒ `CONFIDENTIAL` (unless `PUBLIC`
/// requested); any other value is `INVALID_CLIENT_TYPE`.
public record CreatePortalAppWithOAuthClientCommand(
        String clientId, String code, String name, String description,
        List<String> redirectUris, String clientType) {
}
