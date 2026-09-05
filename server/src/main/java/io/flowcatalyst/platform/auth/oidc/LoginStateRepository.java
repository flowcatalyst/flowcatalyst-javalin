package io.flowcatalyst.platform.auth.oidc;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_LOGIN_STATES;

/// `oauth_oidc_login_states` (§3.1): insert, then **exactly one** consume —
/// a `DELETE … WHERE state = ? AND expires_at > now() RETURNING …`, so a
/// replayed or expired state reads as absent and two concurrent callbacks
/// cannot both proceed. The purger sweeps what is never consumed.
public final class LoginStateRepository {

    private static final io.flowcatalyst.db.generated.tables.OauthOidcLoginStates T = OAUTH_OIDC_LOGIN_STATES;

    private final DSLContext dsl;
    private final Clock clock;

    public LoginStateRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public LoginStateRepository(DataSource dataSource, Clock clock) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void insert(LoginState s) {
        LoginState.OAuthChain o = s.oauth() == null ? new LoginState.OAuthChain(null, null, null, null, null, null, null) : s.oauth();
        dsl.insertInto(T)
                .set(T.STATE, s.state())
                .set(T.EMAIL_DOMAIN, s.emailDomain())
                .set(T.IDENTITY_PROVIDER_ID, s.identityProviderId())
                .set(T.EMAIL_DOMAIN_MAPPING_ID, s.emailDomainMappingId())
                .set(T.NONCE, s.nonce())
                .set(T.CODE_VERIFIER, s.codeVerifier())
                .set(T.RETURN_URL, blankToNull(s.returnUrl()))
                .set(T.OAUTH_CLIENT_ID, blankToNull(o.clientId()))
                .set(T.OAUTH_REDIRECT_URI, blankToNull(o.redirectUri()))
                .set(T.OAUTH_SCOPE, blankToNull(o.scope()))
                .set(T.OAUTH_STATE, blankToNull(o.state()))
                .set(T.OAUTH_CODE_CHALLENGE, blankToNull(o.codeChallenge()))
                .set(T.OAUTH_CODE_CHALLENGE_METHOD, blankToNull(o.codeChallengeMethod()))
                .set(T.OAUTH_NONCE, blankToNull(o.nonce()))
                .set(T.PORTAL_CLIENT_ID, blankToNull(s.portalClientId()))
                .set(T.CREATED_AT, utc(s.createdAt()))
                .set(T.EXPIRES_AT, utc(s.expiresAt()))
                .execute();
    }

    /// The live row for `state`, deleted as it is read; empty for unknown,
    /// replayed or expired.
    public Optional<LoginState> consume(String state) {
        if (state == null || state.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return dsl.deleteFrom(T).where(T.STATE.eq(state)).and(T.EXPIRES_AT.gt(utc(now)))
                .returning()
                .fetchOptional(r -> new LoginState(r.getState(), r.getEmailDomain(), r.getIdentityProviderId(),
                        r.getEmailDomainMappingId(), r.getNonce(), r.getCodeVerifier(), r.getReturnUrl(),
                        new LoginState.OAuthChain(r.getOauthClientId(), r.getOauthRedirectUri(), r.getOauthScope(),
                                r.getOauthState(), r.getOauthCodeChallenge(), r.getOauthCodeChallengeMethod(), r.getOauthNonce()),
                        r.getPortalClientId(), r.getCreatedAt().toInstant(), r.getExpiresAt().toInstant()));
    }

    private static String blankToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
