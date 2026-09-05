package io.flowcatalyst.platform.oauthclient.operations;

import java.util.List;

/// `UpdateOAuthClient`'s command (spec §6.3): every field but `id` is
/// optional — `null` = untouched, matching [io.flowcatalyst.platform.oauthclient.OAuthClient.Changes].
/// `portalClientId`: `null` = untouched, blank = clear, non-blank = set.
public record UpdateOAuthClientCommand(
        String id,
        String clientName,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        List<String> grantTypes,
        List<String> defaultScopes,
        List<String> allowedOrigins,
        List<String> applicationIds,
        Boolean pkceRequired,
        String portalClientId,
        Boolean apiAccess) {
}
