package io.flowcatalyst.platform.portalauth;

import io.flowcatalyst.platform.shared.SecureTokens;
import java.time.Instant;
import java.util.Objects;

/// One parked `/portal/authorize` handshake (spec `auth-identity.md` §3.2,
/// §11.2): the OAuth chain the portal app started, stashed until the
/// person picks a login method and either succeeds with a password or
/// starts an SSO round-trip. `Find` (here [PortalLoginFlowRepository#findLive])
/// reads a live row **without** consuming it — a wrong password or a
/// domain check must not burn the flow — while the actual sign-in
/// (`Consume`) is single-use, exactly like the OIDC bridge's own login
/// state.
///
/// @param id                   the lookup key AND a CSRF-relevant bearer-adjacent
///                             value carried in a URL — 32 random bytes,
///                             base64url-no-pad (spec §13: entropy is
///                             load-bearing), **not** a TSID: a TSID's
///                             13-character body embeds far less entropy
///                             and a sortable timestamp, exactly the
///                             opposite of what an unguessable lookup key
///                             needs (see the class doc for the "add a
///                             `plf` `EntityType`?" question this answers)
/// @param oauthClientId        `oauth_clients.client_id` (the OAuth2 string, not the internal id)
/// @param portalClientId       the OAuth client's `portal_client_id` at stash time
/// @param redirectUri          validated against the client's registered URIs before insert
/// @param scope                `null` when the query param was empty
/// @param state                required at `/portal/authorize`
/// @param nonce                `null` when the query param was empty
/// @param codeChallenge        `null` when the query param was empty
/// @param codeChallengeMethod  defaults to `S256` whenever a challenge is present
/// @param createdAt            creation time
/// @param expiresAt            `createdAt + 15 min`
public record PortalLoginFlow(
        String id,
        String oauthClientId,
        String portalClientId,
        String redirectUri,
        String scope,
        String state,
        String nonce,
        String codeChallenge,
        String codeChallengeMethod,
        Instant createdAt,
        Instant expiresAt) {

    /// Spec §13: 15 minutes.
    public static final long TTL_SECONDS = 900;


    public PortalLoginFlow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(oauthClientId, "oauthClientId");
        Objects.requireNonNull(portalClientId, "portalClientId");
        Objects.requireNonNull(redirectUri, "redirectUri");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /// A fresh flow with the default TTL (spec §5.1).
    public static PortalLoginFlow start(String oauthClientId, String portalClientId, String redirectUri, String scope,
                                        String state, String nonce, String codeChallenge, String codeChallengeMethod,
                                        Instant now) {
        return new PortalLoginFlow(generateId(), oauthClientId, portalClientId, redirectUri, scope, state, nonce,
                codeChallenge, codeChallengeMethod, now, now.plusSeconds(TTL_SECONDS));
    }

    /// 32 random bytes, unpadded base64url — 43 characters.
    private static String generateId() {
        return SecureTokens.urlSafe(32);
    }

    public boolean isLive(Instant now) {
        return now.isBefore(expiresAt);
    }
}
