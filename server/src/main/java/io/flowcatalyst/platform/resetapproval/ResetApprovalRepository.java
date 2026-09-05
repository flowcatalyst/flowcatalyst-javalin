package io.flowcatalyst.platform.resetapproval;

import io.flowcatalyst.db.generated.tables.records.IamResetApprovalRequestsRecord;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.IAM_RESET_APPROVAL_REQUESTS;

/// `iam_reset_approval_requests` via jOOQ (spec §3.7). No junctions — pure
/// single-table CRUD, plus the one guarded transition ([#decide]) the
/// approve/deny operation relies on.
public final class ResetApprovalRepository implements Persist<ResetApprovalRequest> {

    private static final io.flowcatalyst.db.generated.tables.IamResetApprovalRequests T = IAM_RESET_APPROVAL_REQUESTS;

    private final DSLContext dsl;

    public ResetApprovalRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<ResetApprovalRequest> findById(String id) {
        return dsl.selectFrom(T).where(T.ID.eq(id)).fetchOptional(ResetApprovalRepository::toEntity);
    }

    /// §8.6: every `PENDING`, unexpired row, oldest first. An anchor
    /// ([Visibility.Everything]) sees all of them; otherwise only the
    /// caller's own client(s) — `client_id = ANY(clients)`, an empty list
    /// seeing **none**. Deliberately not [io.flowcatalyst.platform.shared.database.VisibilitySql]:
    /// that helper also matches a `NULL` client id ("platform-scoped rows"),
    /// which does not apply here — the queue never creates a row without a
    /// client id (anchor users get no approval path), so there is nothing
    /// to fall back to, and falling back would be a visibility bug waiting
    /// for a row that should never exist.
    public List<ResetApprovalRequest> findPending(Visibility visibility) {
        Objects.requireNonNull(visibility, "visibility");
        Condition scope = switch (visibility) {
            case Visibility.Everything _ -> DSL.noCondition();
            case Visibility.Tenants t -> t.clientIds().isEmpty() ? DSL.falseCondition() : T.CLIENT_ID.in(t.clientIds());
        };
        Instant now = Instant.now();
        return dsl.selectFrom(T)
                .where(T.STATUS.eq(ResetApprovalStatus.PENDING.name()))
                .and(T.EXPIRES_AT.gt(utc(now)))
                .and(scope)
                .orderBy(T.CREATED_AT.asc())
                .fetch(ResetApprovalRepository::toEntity);
    }

    /// Whether `principalId` already has an unexpired `PENDING` request — the
    /// queue's duplicate-suppression check (spec §8.6).
    public boolean hasPendingFor(String principalId) {
        Instant now = Instant.now();
        return dsl.fetchExists(dsl.selectOne().from(T)
                .where(T.PRINCIPAL_ID.eq(principalId))
                .and(T.STATUS.eq(ResetApprovalStatus.PENDING.name()))
                .and(T.EXPIRES_AT.gt(utc(now))));
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    @Override
    public void persist(ResetApprovalRequest r, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.insertInto(T)
                .set(T.ID, r.id())
                .set(T.PRINCIPAL_ID, r.principalId())
                .set(T.CLIENT_ID, r.clientId())
                .set(T.STATUS, r.status().name())
                .set(T.RESET_2FA, r.reset2fa())
                .set(T.NOTE, r.note())
                .set(T.DECIDED_BY, r.decidedBy())
                .set(T.DECIDED_AT, utcOrNull(r.decidedAt()))
                .set(T.EXPIRES_AT, utc(r.expiresAt()))
                .set(T.CREATED_AT, utc(r.createdAt()))
                .onConflict(T.ID).doUpdate()
                .set(T.STATUS, r.status().name())
                .set(T.RESET_2FA, r.reset2fa())
                .set(T.NOTE, r.note())
                .set(T.DECIDED_BY, r.decidedBy())
                .set(T.DECIDED_AT, utcOrNull(r.decidedAt()))
                .execute();
    }

    @Override
    public void delete(ResetApprovalRequest r, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(r.id())).execute();
    }

    /// The guarded decision (spec §8.6, §11.10): only a row that is still
    /// `PENDING` and unexpired is updated — the row count returned here IS
    /// the "already decided" signal. This runs as its own statement rather
    /// than through [#persist] / the [Persist] write path on purpose: every
    /// exception a [Persist#persist] implementation throws is wrapped by
    /// `TxScopedUnitOfWork` into a generic internal `PERSIST` (500) error, so
    /// a guard living there could never surface as the business-rule 400 the
    /// spec calls for. Keeping the guard here — checked by the caller's row
    /// count, not a pre-loaded copy's in-memory status — is also what makes
    /// [ResetApprovalRequest#approve] / [#deny] genuinely redundant for a
    /// concurrent double-decision: removing the `status = 'PENDING'` clause
    /// below is a real, independently-observable regression (see the
    /// repository test), not one an in-memory check would mask.
    ///
    /// @return the number of rows updated: `1` on success, `0` when the
    ///         request was already decided or has expired
    public int decide(String id, ResetApprovalStatus status, String decidedBy, String note, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(now, "now");
        return dsl.update(T)
                .set(T.STATUS, status.name())
                .set(T.DECIDED_BY, decidedBy)
                .set(T.NOTE, note)
                .set(T.DECIDED_AT, utc(now))
                .where(T.ID.eq(id))
                .and(T.STATUS.eq(ResetApprovalStatus.PENDING.name()))
                .and(T.EXPIRES_AT.gt(utc(now)))
                .execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static ResetApprovalRequest toEntity(IamResetApprovalRequestsRecord row) {
        return new ResetApprovalRequest(
                row.getId(),
                row.getPrincipalId(),
                row.getClientId(),
                ResetApprovalStatus.parse(row.getStatus()),
                Boolean.TRUE.equals(row.getReset_2fa()),
                row.getNote(),
                row.getDecidedBy(),
                instantOrNull(row.getDecidedAt()),
                row.getExpiresAt().toInstant(),
                row.getCreatedAt().toInstant());
    }

    private static Instant instantOrNull(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utcOrNull(Instant instant) {
        return instant == null ? null : utc(instant);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
