package io.flowcatalyst.platform.oauthclient.operations;

import java.util.List;

/// `UpdateOAuthClient`'s command (spec §6.3, §4.5): every field but `id` is
/// optional — `null` = untouched, matching [io.flowcatalyst.platform.oauthclient.OAuthClient.Changes].
/// `portalClientId` / `portalAppId`: `null` = untouched, blank = clear,
/// non-blank = set. The controller ([io.flowcatalyst.platform.oauthclient.api.OAuthClientApi])
/// resolves `portalClientId` from a non-blank `portalAppId` before building
/// this command (spec §4.5) — by the time it lands here the two are already
/// consistent.
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
        String portalAppId,
        Boolean apiAccess) {
}
