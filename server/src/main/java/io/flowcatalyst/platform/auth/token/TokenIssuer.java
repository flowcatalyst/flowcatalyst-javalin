package io.flowcatalyst.platform.auth.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalType;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.auth.TokenClaims;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/// Mints every JWT the platform signs — access tokens, identity access
/// tokens, OIDC ID tokens and the `fc_session` cookie — **RS256 under the
/// platform signing key** (`docs/spec/auth-core.md` §3.1–§3.3, §4; Go
/// `authservice.generateTokenWithExpiry` / `generateIDToken`,
/// `sessiontoken.Mint`).
///
/// Java signs RS256 only. Go falls back to HS256 when no RSA key is
/// configured; [SigningKeys] always yields one (an ephemeral key with a
/// warning when nothing is configured), so the fallback has no reason to
/// exist here. The verifier keeps `HmacKey` for tokens a Go development
/// instance may have minted. Ruling C-Q19 (HS256 secret ≥ 32 bytes) is
/// therefore a Go-only change.
///
/// The issuer is deliberately dumb about authority: the caller computes the
/// [Authority] a token carries (confinement, scope narrowing, role
/// filtering live in [ClaimShapes], [ScopeNarrowing] and the claims
/// resolver), and this class only lays it out on the wire. That keeps the
/// one place that decides *what a token says* out of the one place that
/// *signs* it.
public final class TokenIssuer {

    /// `AccessTokenExpirySecs`: one hour (§10 #1).
    public static final long ACCESS_TTL_SECONDS = 3600;
    /// `IDTokenExpirySecs`: five minutes (§3.2).
    public static final long ID_TOKEN_TTL_SECONDS = 300;
    /// The session cookie's default life — 24 h. Ruling C-Q16 made this
    /// compile-time; superseded 2026-09-11 (`docs/spec/deployed-dispatch.md`
    /// §4) — the deployed environment sets `OIDC_SESSION_TTL` and Java must
    /// honour it, so this constant is now only [Config#of]'s default.
    public static final long SESSION_TTL_SECONDS = 24 * 3600;

    /// @param issuer           `FC_JWT_ISSUER`
    /// @param audience         the platform audience — equals the issuer in every deployment (§4)
    /// @param sessionTtlSeconds the `fc_session` cookie's lifetime — the session JWT's `exp`
    ///                          and (via [io.flowcatalyst.platform.auth.login.SessionCookie])
    ///                          the cookie's `Max-Age` must stay equal
    public record Config(String issuer, String audience, long accessTtlSeconds, long idTokenTtlSeconds,
                          long sessionTtlSeconds) {
        public Config {
            Objects.requireNonNull(issuer, "issuer");
            if (issuer.isBlank()) throw new IllegalArgumentException("issuer must not be blank");
            audience = audience == null || audience.isBlank() ? issuer : audience;
            if (accessTtlSeconds <= 0 || idTokenTtlSeconds <= 0 || sessionTtlSeconds <= 0) {
                throw new IllegalArgumentException("TTLs must be positive");
            }
        }

        public static Config of(String issuer) {
            return new Config(issuer, issuer, ACCESS_TTL_SECONDS, ID_TOKEN_TTL_SECONDS, SESSION_TTL_SECONDS);
        }
    }

    /// The authority an API access token carries, as the caller decided it.
    ///
    /// @param clients          `clients` claim entries ([ClaimShapes#clients])
    /// @param roles            `roles` — canonical names
    /// @param applications     `applications` claim entries ([ClaimShapes#applications])
    /// @param allApplications  `all_applications` (deprecated alongside the `"*"` sentinel, still emitted)
    /// @param scope            the permissions the `scope` claim advertises; empty ⇒ claim omitted
    public record Authority(List<String> clients, List<String> roles, List<String> applications,
                            boolean allApplications, List<String> scope) {
        public Authority {
            clients = List.copyOf(clients);
            roles = List.copyOf(roles);
            applications = List.copyOf(applications);
            scope = List.copyOf(scope);
        }

        /// The full, unconfined authority of `p`, labels resolved through `labels`.
        public static Authority full(Principal p, List<String> ceiling, ClaimLabels labels) {
            var clientIds = switch (p.scope()) {
                case ANCHOR -> List.<String>of();
                case PARTNER -> p.assignedClients();
                case CLIENT -> p.clientId() == null ? List.<String>of() : List.of(p.clientId());
            };
            return new Authority(
                    ClaimShapes.clients(p, labels.clientIdentifiers(clientIds)),
                    ClaimShapes.roleNames(p),
                    ClaimShapes.applications(p, p.allApplications() ? java.util.Map.of() : labels.applicationCodes(p.accessibleApplicationIds())),
                    p.allApplications(),
                    ceiling == null ? List.of() : ceiling);
        }
    }

    /// What an OIDC ID token says (§3.2).
    ///
    /// @param clientId        the relying party — `aud` and `azp`
    /// @param nonce           echoed when the authorize request carried one
    /// @param authTime        when the user actually signed in (ruling C-Q1; rides the
    ///                        code and the refresh family); `null` ⇒ now
    /// @param roles           the roles the RP may see — full or confined, canonical names
    /// @param applications    the `applications` entries the RP may see
    /// @param allApplications forced off on a confined view
    /// @param clients         the `clients` entries
    /// @param portalClientId  the portal identity's tenant client id (portal logins only,
    ///                        `docs/spec/portal-apps.md` §5.4); `null` for every non-portal id_token
    /// @param portalAppCode   the app-linked OAuth client's app code (normalised); `null` on a
    ///                        legacy client-wide portal login or a non-portal id_token
    /// @param portalAppId     the app-linked OAuth client's app id (`pta_…`); `null` likewise
    public record IdTokenInput(String clientId, String nonce, Instant authTime, List<String> roles,
                               List<String> applications, boolean allApplications, List<String> clients,
                               String portalClientId, String portalAppCode, String portalAppId) {
        public IdTokenInput {
            Objects.requireNonNull(clientId, "clientId");
            roles = List.copyOf(roles);
            applications = List.copyOf(applications);
            clients = List.copyOf(clients);
        }

        /// Non-portal id_tokens never carry portal claims (spec `portal-apps.md` §5.4).
        public IdTokenInput(String clientId, String nonce, Instant authTime, List<String> roles,
                            List<String> applications, boolean allApplications, List<String> clients) {
            this(clientId, nonce, authTime, roles, applications, allApplications, clients, null, null, null);
        }
    }

    private static final String PORTAL_SUBJECT_PREFIX = EntityType.PORTAL_USER.prefix() + "_";

    private final SigningKeys keys;
    private final Config config;
    private final Clock clock;

    public TokenIssuer(SigningKeys keys, Config config) {
        this(keys, config, Clock.systemUTC());
    }

    public TokenIssuer(SigningKeys keys, Config config, Clock clock) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Config config() {
        return config;
    }

    /// An authority-bearing API access token (`token_use = "api"`), §3.1.
    ///
    /// @param azp the OAuth client the token is minted through, stamped as
    ///            `azp` so introspection and userinfo can name it (rulings
    ///            C-Q26, `8ec7f9a`); `null` when minted outside a client
    public String accessToken(Principal p, Authority authority, String azp) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(authority, "authority");
        Instant now = clock.instant();
        var b = baseAccessClaims(p, now, config.accessTtlSeconds())
                .claim("clients", authority.clients())
                .claim("roles", authority.roles())
                .claim("applications", authority.applications())
                .claim("all_applications", authority.allApplications())
                .claim("token_use", TokenClaims.TOKEN_USE_API);
        if (!authority.scope().isEmpty()) {
            b.claim("scope", String.join(" ", authority.scope()));
        }
        if (azp != null && !azp.isBlank()) {
            b.claim("azp", azp);
        }
        return sign(b.build(), true);
    }

    /// The access token an interactive login receives (`token_use =
    /// "identity"`): identity fields intact, **no** authority — empty
    /// `clients`/`roles`/`applications`, `all_applications = false`, no
    /// `scope`. The API middleware refuses it as a credential; its only uses
    /// are `/oauth/userinfo` and proving authentication to the RP.
    public String identityAccessToken(Principal p, String azp) {
        Objects.requireNonNull(p, "principal");
        Instant now = clock.instant();
        var b = baseAccessClaims(p, now, config.accessTtlSeconds())
                .claim("clients", List.of())
                .claim("roles", List.of())
                .claim("applications", List.of())
                .claim("all_applications", false)
                .claim("token_use", TokenClaims.TOKEN_USE_IDENTITY);
        if (azp != null && !azp.isBlank()) {
            b.claim("azp", azp);
        }
        return sign(b.build(), true);
    }

    /// An OIDC ID token addressed to `in.clientId()` (§3.2): no `nbf`/`jti`;
    /// `auth_time` the real sign-in time; `updated_at` the principal's own;
    /// `email_verified` true whenever an email exists (ruling C-Q2);
    /// `azp = aud`; `acr`/`amr` never.
    public String idToken(Principal p, IdTokenInput in) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(in, "input");
        Instant now = clock.instant();
        Instant authTime = in.authTime() == null ? now : in.authTime();
        Instant updatedAt = p.updatedAt() == null ? now : p.updatedAt();
        var b = new JWTClaimsSet.Builder()
                .issuer(config.issuer())
                .subject(p.id())
                .expirationTime(Date.from(now.plusSeconds(config.idTokenTtlSeconds())))
                .issueTime(Date.from(now))
                .audience(in.clientId())
                .claim("auth_time", authTime.getEpochSecond())
                .claim("name", p.name())
                .claim("updated_at", updatedAt.getEpochSecond())
                .claim("azp", in.clientId())
                .claim("type", p.type().name())
                .claim("tier", tier(p))
                .claim("roles", in.roles())
                .claim("applications", in.applications())
                .claim("all_applications", in.allApplications())
                .claim("clients", in.clients());
        if (in.nonce() != null && !in.nonce().isBlank()) {
            b.claim("nonce", in.nonce());
        }
        String email = p.email();
        if (email != null && !email.isBlank()) {
            b.claim("email", email).claim("email_verified", true);
        }
        if (p.clientId() != null && !p.clientId().isBlank()) {
            b.claim("client_id", p.clientId());
        }
        // Portal claims (`docs/spec/portal-apps.md` §5.4): omitted — not
        // null — when absent, so a non-portal id_token never carries the
        // keys at all.
        if (in.portalClientId() != null && !in.portalClientId().isBlank()) {
            b.claim("portal_client_id", in.portalClientId());
        }
        if (in.portalAppCode() != null && !in.portalAppCode().isBlank()) {
            b.claim("portal_app_code", in.portalAppCode());
        }
        if (in.portalAppId() != null && !in.portalAppId().isBlank()) {
            b.claim("portal_app_id", in.portalAppId());
        }
        return sign(b.build(), true);
    }

    /// The `fc_session` cookie value (§3.3, Go `sessiontoken.Mint` as the
    /// provider calls it): identity only — `sub`, `email`, an empty `tier`
    /// (the provider passes only subject and email), `all_applications =
    /// false`, **no `aud`** (which is what lets the middleware's audience
    /// guard pass cookies), no `kid`. Everything else is re-resolved from the
    /// store on every request. Lifetime is [Config#sessionTtlSeconds()]
    /// (default 24 h; the deployed environment sets it to 8 h — owner ruling
    /// 2026-09-11, superseding C-Q16); cannot be revoked — logout clears the
    /// cookie.
    public String sessionToken(String principalId, String email) {
        Objects.requireNonNull(principalId, "principalId");
        if (principalId.isBlank()) throw new IllegalArgumentException("principalId must not be blank");
        Instant now = clock.instant();
        var b = new JWTClaimsSet.Builder()
                .issuer(config.issuer())
                .subject(principalId)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(config.sessionTtlSeconds())))
                .claim("tier", "")
                .claim("all_applications", false);
        if (email != null && !email.isBlank()) {
            b.claim("email", email);
        }
        return sign(b.build(), false);
    }

    private JWTClaimsSet.Builder baseAccessClaims(Principal p, Instant now, long ttlSeconds) {
        var b = new JWTClaimsSet.Builder()
                .issuer(config.issuer())
                .subject(p.id())
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .jwtID(Tsid.generate())
                .audience(config.audience())
                .claim("type", p.type().name())
                .claim("tier", tier(p))
                .claim("name", p.name());
        String email = p.type() == PrincipalType.SERVICE ? null : p.email();
        if (email != null && !email.isBlank()) {
            b.claim("email", email);
        }
        return b;
    }

    /// The `tier` claim: the principal's tenancy tier, except for a portal
    /// identity (`ptu_` subject), which is not a platform principal and has
    /// no tier — `""`, on the access token and the id_token alike (ruling
    /// `44e5633`, `auth-core.md`). `Principal.portalSubject` has to carry
    /// *some* scope to be a `Principal`; this keeps that placeholder off the
    /// wire, where a relying party would branch on it.
    private static String tier(Principal p) {
        return p.id().startsWith(PORTAL_SUBJECT_PREFIX) ? "" : p.scope().name();
    }

    /// RS256 under the current private key; `kid` stamped only where Go
    /// stamps it (access/ID tokens — never the session cookie).
    private String sign(JWTClaimsSet claims, boolean withKid) {
        // `typ: JWT` as Go's jwt library stamps on every token (parity S2).
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(com.nimbusds.jose.JOSEObjectType.JWT);
        if (withKid) {
            header.keyID(keys.kid());
        }
        var signed = new SignedJWT(header.build(), claims);
        try {
            signed.sign(new RSASSASigner(keys.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign token", e);
        }
        return signed.serialize();
    }
}
