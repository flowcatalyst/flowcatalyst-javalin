package io.flowcatalyst.platform.mail;

import io.flowcatalyst.db.generated.tables.MailOutbox;
import io.flowcatalyst.db.generated.tables.records.MailOutboxRecord;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MAIL_OUTBOX;

/// `mail_outbox` via jOOQ (`docs/spec/mail-outbox.md` §2). Two access
/// patterns:
///
///   - single-statement writes (insert, the mark-* methods, the retention
///     purges) go through [#dsl], which acquires and releases a pooled
///     connection per call, same as [io.flowcatalyst.platform.role.RoleRepository];
///   - [#claimDue] is the one multi-statement transaction: it needs its own
///     [Connection] so the claim's `SELECT ... FOR UPDATE SKIP LOCKED` and the
///     lease `UPDATE` that follows share the same row locks, committed
///     together (mirrors [io.flowcatalyst.platform.scheduler.PendingJobPoller#claimAndMark]).
public final class MailOutboxRepository {

    private static final Logger LOG = LoggerFactory.getLogger(MailOutboxRepository.class);
    private static final MailOutbox T = MAIL_OUTBOX;

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    private final DataSource pool;
    private final DSLContext dsl;

    public MailOutboxRepository(DataSource pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.dsl = DSL.using(pool, SQLDialect.POSTGRES);
    }

    /// Inserts one `PENDING` row with `next_attempt_at = now`
    /// ([OutboxMailService]'s whole job). Returns the generated id.
    public String insertPending(Mail mail, Instant now) {
        String id = Tsid.generate();
        dsl.insertInto(T)
                .set(T.ID, id)
                .set(T.TO_ADDR, mail.to())
                .set(T.SUBJECT, mail.subject())
                .set(T.HTML, mail.html())
                .set(T.STATUS, PENDING)
                .set(T.ATTEMPTS, 0)
                .set(T.NEXT_ATTEMPT_AT, utc(now))
                .set(T.CREATED_AT, utc(now))
                .execute();
        return id;
    }

    /// One row this repository claimed (spec §4 row 5's exclusivity target):
    /// exactly what [MailSender] needs to hand the transport, plus the
    /// current `attempts` count so it can compute the next ladder step.
    public record ClaimedMail(String id, String to, String subject, String html, int attempts) {
    }

    /// The claim query (spec §2): up to `n` rows `WHERE status = 'PENDING'
    /// AND next_attempt_at <= now ORDER BY created_at FOR UPDATE SKIP LOCKED`,
    /// and — in the SAME transaction, before it commits — a lease: their
    /// `next_attempt_at` is pushed to `now + lease` so a second claimer (a
    /// second [MailSender] instance, or this same repository's next tick)
    /// cannot re-select them before this claim's delivery attempt has had a
    /// chance to mark them `SENT`/failed/dead. `SKIP LOCKED` alone only
    /// protects two claims that overlap in time; the lease is what protects
    /// two claims separated by a commit (spec §4 row 5's mutant: drop either
    /// half and duplicates are deliverable).
    public List<ClaimedMail> claimDue(int n, Instant now, Duration lease) {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<ClaimedMail> claimed = claimDueInTx(conn, n, now, lease);
                conn.commit();
                return claimed;
            } catch (RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new MailOutboxSqlException("claimDue failed", e);
        }
    }

    /// The claim's two statements on a caller-owned connection — package-private
    /// so a test can hold one claim open while a second claimer runs against it,
    /// mirroring [io.flowcatalyst.outbox.PostgresOutboxRepository#claimInTx].
    List<ClaimedMail> claimDueInTx(Connection conn, int n, Instant now, Duration lease) {
        DSLContext tx = DSL.using(conn, SQLDialect.POSTGRES);
        List<MailOutboxRecord> rows = tx.selectFrom(T)
                .where(T.STATUS.eq(PENDING))
                .and(T.NEXT_ATTEMPT_AT.le(utc(now)))
                .orderBy(T.CREATED_AT.asc())
                .limit(n)
                .forUpdate()
                .skipLocked()
                .fetch();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = rows.stream().map(MailOutboxRecord::getId).toList();
        tx.update(T)
                .set(T.NEXT_ATTEMPT_AT, utc(now.plus(lease)))
                .where(T.ID.in(ids))
                .execute();
        return rows.stream()
                .map(r -> new ClaimedMail(r.getId(), r.getToAddr(), r.getSubject(), r.getHtml(), r.getAttempts()))
                .toList();
    }

    /// Delivered: `SENT`, `sent_at` stamped (spec §4 row 2).
    public void markSent(String id, Instant sentAt) {
        dsl.update(T)
                .set(T.STATUS, SENT)
                .set(T.SENT_AT, utc(sentAt))
                .where(T.ID.eq(id))
                .execute();
    }

    /// A failing transport, not yet at the retry cap: stays `PENDING`,
    /// `attempts` and `next_attempt_at` advance by the ladder (spec §4 row 3).
    public void markFailed(String id, int attempts, Instant nextAttemptAt, String error) {
        dsl.update(T)
                .set(T.STATUS, PENDING)
                .set(T.ATTEMPTS, attempts)
                .set(T.NEXT_ATTEMPT_AT, utc(nextAttemptAt))
                .set(T.LAST_ERROR, error)
                .where(T.ID.eq(id))
                .execute();
    }

    /// The retry cap is spent: terminal `FAILED`, no longer claimed
    /// (spec §4 row 4 — `claimDue`'s `status = 'PENDING'` predicate excludes it).
    public void markDead(String id, int attempts, String error) {
        dsl.update(T)
                .set(T.STATUS, FAILED)
                .set(T.ATTEMPTS, attempts)
                .set(T.LAST_ERROR, error)
                .where(T.ID.eq(id))
                .execute();
    }

    /// The current backlog size — [MailSender]'s `fc_mail_outbox_pending`
    /// gauge samples this once per tick.
    public int countPending() {
        return dsl.fetchCount(dsl.selectFrom(T).where(T.STATUS.eq(PENDING)));
    }

    /// `Purger` sweep: `SENT` rows older than `olderThan` (7 days, spec §2).
    public int purgeSent(Instant olderThan) {
        return dsl.deleteFrom(T)
                .where(T.STATUS.eq(SENT))
                .and(T.SENT_AT.lt(utc(olderThan)))
                .execute();
    }

    /// `Purger` sweep: `FAILED` rows older than `olderThan` (30 days, spec §2).
    /// `FAILED` carries no dedicated terminal timestamp, so `created_at` —
    /// the only timestamp every row has — is the retention clock.
    public int purgeFailed(Instant olderThan) {
        return dsl.deleteFrom(T)
                .where(T.STATUS.eq(FAILED))
                .and(T.CREATED_AT.lt(utc(olderThan)))
                .execute();
    }

    /// Test-only: the full stored row, for asserting status/attempts/timing
    /// directly rather than through a side channel.
    public record StoredMail(String id, String to, String subject, String html, String status, int attempts,
                              Instant nextAttemptAt, String lastError, Instant createdAt, Instant sentAt) {
    }

    public Optional<StoredMail> find(String id) {
        return dsl.selectFrom(T).where(T.ID.eq(id)).fetchOptional(r -> new StoredMail(
                r.getId(), r.getToAddr(), r.getSubject(), r.getHtml(), r.getStatus(), r.getAttempts(),
                r.getNextAttemptAt() == null ? null : r.getNextAttemptAt().toInstant(),
                r.getLastError(),
                r.getCreatedAt() == null ? null : r.getCreatedAt().toInstant(),
                r.getSentAt() == null ? null : r.getSentAt().toInstant()));
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            LOG.warn("mail outbox claim rollback failed", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /// Wraps a JDBC failure from [#claimDue]'s hand-managed transaction.
    public static final class MailOutboxSqlException extends RuntimeException {
        public MailOutboxSqlException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
