package io.flowcatalyst.platform.portalauth;

import io.flowcatalyst.db.generated.tables.PortalLoginFlows;
import io.flowcatalyst.db.generated.tables.records.PortalLoginFlowsRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.PORTAL_LOGIN_FLOWS;

/// `portal_login_flows` via jOOQ (spec `auth-identity.md` §3.2). An
/// infrastructure row store, not an aggregate — no unit of work, no
/// events, no audit, exactly like [io.flowcatalyst.platform.auth.grant.GrantStore]:
/// every write is a single statement, and the sign-in redemption is one
/// atomic `DELETE … RETURNING` so exactly one of two racing callers can
/// consume a flow.
public final class PortalLoginFlowRepository {

    private static final PortalLoginFlows T = PORTAL_LOGIN_FLOWS;

    private final DSLContext dsl;

    public PortalLoginFlowRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    public void insert(PortalLoginFlow f) {
        dsl.insertInto(T)
                .set(T.ID, f.id())
                .set(T.OAUTH_CLIENT_ID, f.oauthClientId())
                .set(T.PORTAL_CLIENT_ID, f.portalClientId())
                .set(T.REDIRECT_URI, f.redirectUri())
                .set(T.SCOPE, f.scope())
                .set(T.STATE, f.state())
                .set(T.NONCE, f.nonce())
                .set(T.CODE_CHALLENGE, f.codeChallenge())
                .set(T.CODE_CHALLENGE_METHOD, f.codeChallengeMethod())
                .set(T.CREATED_AT, utc(f.createdAt()))
                .set(T.EXPIRES_AT, utc(f.expiresAt()))
                .execute();
    }

    /// A live row, read without consuming (spec §3.2: a wrong password or a
    /// domain check must not burn the flow).
    public Optional<PortalLoginFlow> findLive(String id) {
        Record row = dsl.selectFrom(T).where(T.ID.eq(id)).and(T.EXPIRES_AT.gt(DSL.currentOffsetDateTime())).fetchOne();
        return Optional.ofNullable(row).map(r -> toEntity((PortalLoginFlowsRecord) r));
    }

    /// Atomic single-use consume: `DELETE … WHERE id = ? AND expires_at >
    /// now() RETURNING` (spec §3.2, §11.2). Empty when the flow is missing,
    /// expired, or was already consumed by a racing request.
    public Optional<PortalLoginFlow> consume(String id) {
        var row = dsl.deleteFrom(T)
                .where(T.ID.eq(id))
                .and(T.EXPIRES_AT.gt(DSL.currentOffsetDateTime()))
                .returning()
                .fetchOne();
        return Optional.ofNullable(row).map(PortalLoginFlowRepository::toEntity);
    }

    private static PortalLoginFlow toEntity(PortalLoginFlowsRecord row) {
        return new PortalLoginFlow(
                row.getId(),
                row.getOauthClientId(),
                row.getPortalClientId(),
                row.getRedirectUri(),
                row.getScope(),
                row.getState(),
                row.getNonce(),
                row.getCodeChallenge(),
                row.getCodeChallengeMethod(),
                row.getCreatedAt().toInstant(),
                row.getExpiresAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
