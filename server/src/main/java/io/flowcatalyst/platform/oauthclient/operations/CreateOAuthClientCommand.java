package io.flowcatalyst.platform.oauthclient.operations;

import java.util.List;

/// `CreateOAuthClient`'s command (spec §6.3). `clientId` is internal-only —
/// the HTTP request has no `clientId` field; it is always backend-generated
/// there. A blank/`null` `clientId` here means "generate one". `portalAppId`
/// (spec §4.5): non-blank links this client to one portal app; the
/// controller resolves `portalClientId` from it before building the command.
public record CreateOAuthClientCommand(
        String clientId,
        String clientName,
        String clientType,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        List<String> grantTypes,
        List<String> defaultScopes,
        List<String> allowedOrigins,
        List<String> applicationIds,
        String principalId,
        Boolean pkceRequired,
        String portalClientId,
        String portalAppId,
        Boolean apiAccess) {
}
