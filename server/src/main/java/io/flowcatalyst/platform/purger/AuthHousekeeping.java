package io.flowcatalyst.platform.purger;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import static io.flowcatalyst.db.generated.Tables.IAM_PASSWORD_RESET_TOKENS;
import static io.flowcatalyst.db.generated.Tables.IAM_RESET_APPROVAL_REQUESTS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_LOGIN_STATES;
import static io.flowcatalyst.db.generated.Tables.PORTAL_LOGIN_FLOWS;

/// The purger's sweeps over auth tables that have no Java owner yet
/// (`docs/spec/scheduled-job-scheduler.md` §4; `auth-identity.md` §14 with
/// rulings I-Q17 and defect 11). Each is one idempotent statement; the
/// OIDC bridge, portal and password-reset units inherit these rather than
/// adding their own.
public final class AuthHousekeeping {

    private final DSLContext dsl;

    public AuthHousekeeping(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    public int deleteExpiredOidcLoginStates(Instant before) {
        return dsl.deleteFrom(OAUTH_OIDC_LOGIN_STATES).where(OAUTH_OIDC_LOGIN_STATES.EXPIRES_AT.lt(utc(before))).execute();
    }

    public int deleteExpiredPortalLoginFlows(Instant before) {
        return dsl.deleteFrom(PORTAL_LOGIN_FLOWS).where(PORTAL_LOGIN_FLOWS.EXPIRES_AT.lt(utc(before))).execute();
    }

    public int deleteExpiredPasswordResetTokens(Instant before) {
        return dsl.deleteFrom(IAM_PASSWORD_RESET_TOKENS).where(IAM_PASSWORD_RESET_TOKENS.EXPIRES_AT.lt(utc(before))).execute();
    }

    /// Ruling defect 11: a PENDING approval past its expiry is marked
    /// `EXPIRED` — the record stays, the queue stops showing it.
    public int expirePendingApprovalRequests(Instant before) {
        return dsl.update(IAM_RESET_APPROVAL_REQUESTS)
                .set(IAM_RESET_APPROVAL_REQUESTS.STATUS, "EXPIRED")
                .where(IAM_RESET_APPROVAL_REQUESTS.STATUS.eq("PENDING"))
                .and(IAM_RESET_APPROVAL_REQUESTS.EXPIRES_AT.lt(utc(before)))
                .execute();
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
