package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.grant.RefreshRotation;
import io.flowcatalyst.platform.auth.ratelimit.Governor;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.auth.token.ClaimLabels;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/// What the OAuth / OIDC provider endpoints share (`docs/spec/auth-core.md`
/// §6.2; Go `oauthapi.State`). Optional collaborators are nullable, with
/// Go's semantics: no attempts store ⇒ nothing recorded; no rate-limit
/// store ⇒ no distributed throttle; no governor ⇒ no local throttle; no
/// encryption ⇒ confidential-client authentication fails closed.
///
/// @param oauthClients    the client store (`findByClientId` is the hot path)
/// @param principals      the principal store
/// @param serviceAccounts to stamp `last_used_at` on a client_credentials success; nullable
/// @param grants          codes, refresh tokens, pending-auth stashes
/// @param rotation        the refresh rotation core over `grants`
/// @param issuer          the RS256 mint
/// @param tokens          verifies presented access tokens (introspect, userinfo, authorize's Bearer fallback)
/// @param resolver        ceiling and role narrowing
/// @param labels          the `clients`/`applications` claim labels
/// @param encryption      decrypts stored secret refs; empty ⇒ every secret fails
/// @param attempts        SERVICE_ACCOUNT_TOKEN / DEVELOPER_TOKEN rows; nullable
/// @param rateLimit       the distributed store; nullable
/// @param policies        the per-bucket policies
/// @param clientGovernor  the per-process per-client_id limiter on `/oauth/token`; nullable
/// @param signingKeys     for JWKS
/// @param baseUrl         the external issuer the discovery document advertises from (`FC_JWT_ISSUER`)
/// @param portalSubjects  the portal plane's identities for `ptu_` codes; nullable ⇒ portal codes refused
/// @param portalApps      resolves "the app for OAuth client X" for the redemption gate
///                        (`docs/spec/portal-apps.md` §2.4, §5.3)
public record OAuthState(
        OAuthClientRepository oauthClients,
        PrincipalRepository principals,
        ServiceAccountRepository serviceAccounts,
        GrantStore grants,
        RefreshRotation rotation,
        TokenIssuer issuer,
        AccessTokenReader tokens,
        DbClaimsResolver resolver,
        ClaimLabels labels,
        Optional<Encryption> encryption,
        LoginAttemptRepository attempts,
        RateLimit.Store rateLimit,
        RateLimit.Policies policies,
        Governor clientGovernor,
        SigningKeys signingKeys,
        String baseUrl,
        Clock clock,
        PortalSubjects portalSubjects,
        PortalAppRepository portalApps) {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthState.class);

    public OAuthState {
        Objects.requireNonNull(oauthClients, "oauthClients");
        Objects.requireNonNull(principals, "principals");
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(encryption, "encryption");
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(signingKeys, "signingKeys");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(portalApps, "portalApps");
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /// Whether `providedPlaintext` matches the secret stored as `ref`
    /// ([Encryption#verifySecret]) — `false` with no encryption service
    /// (fails closed, same as today's decrypt path) or on any verification
    /// error. What [io.flowcatalyst.platform.oauthclient.OAuthClient#acceptsSecret]
    /// is supplied as its matcher.
    public boolean matchesSecret(String ref, String providedPlaintext) {
        return verifySecret(ref, providedPlaintext) instanceof Encryption.SecretVerification.Matched;
    }

    /// [#matchesSecret], but with the full outcome — whether it matched, and
    /// if so whether the stored ref should be rewritten to the hashed form
    /// (`docs/spec/encryption.md` §3 transparent migration). `NoMatch` with
    /// no encryption service configured.
    public Encryption.SecretVerification verifySecret(String ref, String providedPlaintext) {
        return encryption.map(enc -> {
            try {
                return enc.verifySecret(ref, providedPlaintext);
            } catch (RuntimeException e) {
                return (Encryption.SecretVerification) new Encryption.SecretVerification.NoMatch();
            }
        }).orElseGet(Encryption.SecretVerification.NoMatch::new);
    }

    /// Best-effort attempt row; a logging miss never fails the auth flow.
    public void recordAttempt(AttemptType type, AttemptOutcome outcome, String identifier, String principalId, String reason) {
        if (attempts == null) {
            return;
        }
        try {
            attempts.recordAttempt(LoginAttempt.attempt(type, outcome, reason, identifier, principalId, null, null));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("recording attempt failed")
                    .addKeyValue("type", type)
                    .addKeyValue("identifier", identifier)
                    .setCause(e)
                    .log();
        }
    }
}
