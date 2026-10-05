package io.flowcatalyst.platform.dispatchjob;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.MsgDispatchJobAttempts;
import io.flowcatalyst.db.generated.tables.MsgDispatchJobs;
import io.flowcatalyst.db.generated.tables.MsgDispatchJobsRead;
import io.flowcatalyst.db.generated.tables.records.MsgDispatchJobAttemptsRecord;
import io.flowcatalyst.db.generated.tables.records.MsgDispatchJobsReadRecord;
import io.flowcatalyst.db.generated.tables.records.MsgDispatchJobsRecord;
import io.flowcatalyst.platform.dispatchjob.processing.ProcessingRepository;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.database.VisibilitySql;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.SortField;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS_READ;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOB_ATTEMPTS;

/// `msg_dispatch_jobs` (detail reads), `msg_dispatch_jobs_read`
/// (list / by-event / facet reads) and `msg_dispatch_job_attempts` (history
/// reads and the attempt row) via jOOQ (spec §9). Every insert into
/// `msg_dispatch_jobs` and every write of its `status` is in
/// [DispatchJobLifecycle], the one owner of the status column; this class
/// only reads the table. No domain decisions live here.
/// `asText()`/`isTextual()` are deprecated in Jackson 3 for `stringValue()`/
/// `isString()`, which are NOT equivalent (throws on non-string, `null` not
/// `""` for JSON `null`) — kept deliberately, suppressed rather than migrated.
@SuppressWarnings("deprecation")
public final class DispatchJobRepository implements ProcessingRepository {

    private static final MsgDispatchJobs T = MSG_DISPATCH_JOBS;
    private static final MsgDispatchJobsRead R = MSG_DISPATCH_JOBS_READ;
    private static final MsgDispatchJobAttempts A = MSG_DISPATCH_JOB_ATTEMPTS;

    /// List read: `limit <= 0` or `> MAX` falls back to the default (spec §8).
    static final int LIST_MAX_LIMIT = 1000;
    static final int LIST_DEFAULT_LIMIT = 100;
    /// Facet read: same guard family (spec §8).
    static final int FACET_MAX_LIMIT = 1000;
    static final int FACET_DEFAULT_LIMIT = 200;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;
    /// The scheduler's hot path and the hold-back gates run on plain JDBC
    /// (see "Hot path: plain JDBC" below), taking connections from here.
    private final DataSource dataSource;

    public DispatchJobRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    /// Filters for [#findWithFilters] (spec §4); `null` / empty list = no
    /// filter on that column. `since` / `until` are inclusive; `limit` and
    /// `offset` are guarded here; `sortAscending` flips the `created_at` order.
    /// `visibility` is not a filter but whose view this is (SQL-side tenant
    /// scoping, spec §4) and is required — a caller states it, it never
    /// defaults open.
    public record ListFilter(
            String status,
            String clientId,
            String dispatchPoolId,
            String subscriptionId,
            String code,
            String source,
            String messageGroup,
            Instant since,
            Instant until,
            boolean sortAscending,
            int limit,
            int offset,
            List<String> clientIds,
            List<String> statuses,
            List<String> codes,
            List<String> applications,
            List<String> subdomains,
            List<String> aggregates,
            Visibility visibility) {

        public ListFilter {
            clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            codes = codes == null ? List.of() : List.copyOf(codes);
            applications = applications == null ? List.of() : List.copyOf(applications);
            subdomains = subdomains == null ? List.of() : List.copyOf(subdomains);
            aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
            Objects.requireNonNull(visibility, "visibility");
        }
    }

    /// One due `PENDING` job a claim ([#claimPending]) returned — the claim's own column list, carrying just
    /// what the scheduler poller needs (the [#heldBeforeIds] hold-back and
    /// [io.flowcatalyst.platform.scheduler.PoolCodeResolver] resolution) — not the full [DispatchJob] entity,
    /// which the claim never reads. `mode` is already parsed here (spec §2 "`dispatchMode` resolution" starts
    /// from the stored raw value); every other nullable component is `null` exactly when the column is `NULL`.
    /// `queue` is the job's OWN raw stored priority claim (dispatch-job-priority spec R4) — carried through to
    /// [io.flowcatalyst.platform.scheduler.PublishedMessage] so
    /// [io.flowcatalyst.platform.scheduler.DispatchDestinationResolver] can resolve it ahead of the
    /// subscription's. `updatedAt` is the job's version as the claim read it:
    /// [DispatchJobLifecycle#markQueued] only marks a job that still has it.
    public record ClaimRow(
            String id,
            String subscriptionId,
            String messageGroup,
            DispatchMode mode,
            String dispatchPoolId,
            String clientId,
            Instant createdAt,
            int sequence,
            String queue,
            Instant updatedAt) {
    }

    /// The closed set of projection columns a facet may be taken over (spec §3).
    public enum Facet {
        STATUS(R.STATUS),
        CODE(R.CODE),
        CLIENT_ID(R.CLIENT_ID),
        DISPATCH_POOL_ID(R.DISPATCH_POOL_ID),
        SUBSCRIPTION_ID(R.SUBSCRIPTION_ID),
        KIND(R.KIND);

        private final Field<String> column;

        Facet(Field<String> column) {
            this.column = column;
        }
    }

    // ── Reads: write table ─────────────────────────────────────────────────

    /// The job `id` from the write table. Probes every partition — acceptable
    /// for a by-id detail read (spec §9). Attempts are not hydrated.
    public Optional<DispatchJob> findById(String id) {
        return dsl.selectFrom(T).where(T.ID.eq(id)).fetchOptional().map(DispatchJobRepository::toEntity);
    }

    /// The jobs with these ids, in `created_at` order (spec §9); unknown ids are simply absent.
    public List<DispatchJob> findByIds(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return dsl.selectFrom(T).where(T.ID.in(ids)).orderBy(T.CREATED_AT.asc(), T.ID.asc())
                .fetch(DispatchJobRepository::toEntity);
    }

    /// The most recent `limit` write-side rows, newest first, with their
    /// `payload` and `metadata` (spec §6, mirrors `EventRepository#findRecentRaw`).
    /// Powers the debug raw-job view (`GET /bff/debug/dispatch-jobs`), which
    /// needs the un-projected envelope the read projection drops.
    public List<DispatchJob> findRecentRaw(int limit) {
        return dsl.selectFrom(T)
                .orderBy(T.CREATED_AT.desc())
                .limit(guard(limit, LIST_MAX_LIMIT, LIST_DEFAULT_LIMIT))
                .fetch(DispatchJobRepository::toEntity);
    }

    // ── Reads: projection ──────────────────────────────────────────────────

    /// Projection rows matching every filter plus the caller's [Visibility] (spec §4),
    /// ordered by `created_at` (newest first unless `sortAscending`).
    public List<DispatchJobProjection> findWithFilters(ListFilter f) {
        Condition where = DSL.noCondition();
        if (f.status() != null) where = where.and(R.STATUS.eq(f.status()));
        if (!f.statuses().isEmpty()) where = where.and(R.STATUS.in(f.statuses()));
        if (f.clientId() != null) where = where.and(R.CLIENT_ID.eq(f.clientId()));
        if (!f.clientIds().isEmpty()) where = where.and(R.CLIENT_ID.in(f.clientIds()));
        where = where.and(VisibilitySql.toCondition(f.visibility(), R.CLIENT_ID));
        if (f.dispatchPoolId() != null) where = where.and(R.DISPATCH_POOL_ID.eq(f.dispatchPoolId()));
        if (f.subscriptionId() != null) where = where.and(R.SUBSCRIPTION_ID.eq(f.subscriptionId()));
        if (f.code() != null) where = where.and(R.CODE.eq(f.code()));
        if (!f.codes().isEmpty()) where = where.and(R.CODE.in(f.codes()));
        if (f.source() != null) where = where.and(R.SOURCE.eq(f.source()));
        if (f.messageGroup() != null) where = where.and(R.MESSAGE_GROUP.eq(f.messageGroup()));
        if (!f.applications().isEmpty()) where = where.and(R.APPLICATION.in(f.applications()));
        if (!f.subdomains().isEmpty()) where = where.and(R.SUBDOMAIN.in(f.subdomains()));
        if (!f.aggregates().isEmpty()) where = where.and(R.AGGREGATE.in(f.aggregates()));
        if (f.since() != null) where = where.and(R.CREATED_AT.ge(utc(f.since())));
        if (f.until() != null) where = where.and(R.CREATED_AT.le(utc(f.until())));
        SortField<OffsetDateTime> order = f.sortAscending() ? R.CREATED_AT.asc() : R.CREATED_AT.desc();
        return dsl.selectFrom(R)
                .where(where)
                .orderBy(order)
                .limit(guard(f.limit(), LIST_MAX_LIMIT, LIST_DEFAULT_LIMIT))
                .offset(Math.max(f.offset(), 0))
                .fetch(DispatchJobRepository::toProjection);
    }

    /// The projection rows spawned by one event, newest first (spec §3).
    public List<DispatchJobProjection> findByEventId(String eventId) {
        return dsl.selectFrom(R).where(R.EVENT_ID.eq(eventId)).orderBy(R.CREATED_AT.desc())
                .fetch(DispatchJobRepository::toProjection);
    }

    /// The distinct non-null values of one facet column, ascending, at most `limit`.
    public List<String> distinctValues(Facet facet, int limit) {
        return dsl.selectDistinct(facet.column).from(R)
                .where(facet.column.isNotNull())
                .orderBy(facet.column.asc())
                .limit(guard(limit, FACET_MAX_LIMIT, FACET_DEFAULT_LIMIT))
                .fetch(facet.column);
    }

    // ── Reads: attempts ────────────────────────────────────────────────────

    /// Every recorded attempt of a job, oldest first (spec §1.2).
    public List<Attempt> attemptsByJob(String jobId) {
        return dsl.selectFrom(A).where(A.DISPATCH_JOB_ID.eq(jobId)).orderBy(A.ATTEMPTED_AT.asc(), A.ATTEMPT_NUMBER.asc())
                .fetch(DispatchJobRepository::toAttempt);
    }

    // ── Infra writes (direct SQL, outside the use-case envelope) ───────────
    //
    // The scheduler / processing-endpoint / settled-endpoint / reaper writes
    // (dispatch-seam spec §4–7, §9): router-driven or backstop mutations, not
    // human-initiated commands, so — like Go's Repository — they bypass
    // Operation/Plan/UnitOfWork and write straight through `dsl` (auto-commit,
    // one statement). Every flip carries `created_at` alongside `id` so the
    // statement prunes to one partition, exactly as the Go queries do.

    // ── Hot path: plain JDBC ───────────────────────────────────────────────
    //
    // The hold-back statements and the delivery-time hold-back gate are
    // `PreparedStatement`s over fixed SQL text, not jOOQ: the text is constant,
    // so pgjdbc keeps one server-prepared statement per connection and
    // Postgres one cached plan; every value is a bound parameter (the status
    // values included — the one index they use, `idx_dispatch_jobs_status_group`,
    // is an ordinary index, so nothing depends on a literal being provable
    // against an index predicate). The scheduler's claim, which writes the queue
    // table, is [DispatchJobLifecycle#claimPending].

    /// The terminal-failure statuses a `BLOCK_ON_ERROR` head holds its group
    /// behind on — `FAILED`, plus the legacy `ERROR` alias
    /// ([DispatchJobStatus] §1.1) — as a SQL `IN` list, for the reaper's
    /// stranded-sibling sweep ([DispatchJobLifecycle#sweepStrandedSiblings]).
    static final String HOLDING_STATUSES_SQL = "'FAILED', 'ERROR'";

    /// The same statuses as a bound array: the hold-back reads of `msg_dispatch_jobs`.
    private static final List<String> HOLDING_STATUSES = List.of("FAILED", "ERROR");

    /// `GroupHolding` (spec §9): the ONE predicate shared by every enforcement
    /// point that decides whether a job holds the rest of its group behind it: a
    /// `FAILED`/legacy `ERROR` job, or a `PENDING` job mid-retry-backoff (a **future**
    /// `scheduled_for`; it is excluded from the ordinary claim by that same future
    /// timestamp, so a check that only looked at terminal statuses would miss it).
    /// Deliberately excludes `QUEUED`/`PROCESSING` — the ordinary in-flight flow.
    /// Both selects project `(message_group, sequence, created_at, id)`.
    /// Both halves read `msg_dispatch_jobs` through the plain index `idx_dispatch_jobs_status_group`
    /// (status, message_group, sequence, created_at, id): the `FAILED`/`ERROR` holders by `status = ANY,
    /// message_group = ANY`; the `PENDING`-with-a-future-`scheduled_for` holders as ONE ORDERED INDEX PROBE per
    /// candidate group (the first backed-off row in the group's order, `LIMIT 1`), BOUNDED by the position of the
    /// group's last candidate: only a holder positioned before a candidate can hold it, so the probe reads the
    /// group's PENDING rows ahead of the claim and never all of a deep group's due rows. Arguments: statuses,
    /// groups (first half), then the groups' last-candidate positions (group, sequence, created_at as text, id).
    private static final String HOLDERS_OF_GROUPS_SQL = """
            SELECT message_group, sequence, created_at, id FROM msg_dispatch_jobs
             WHERE status = ANY(?::text[]) AND message_group = ANY(?::text[])
            UNION ALL
            SELECT h.message_group, h.sequence, h.created_at, h.id
              FROM unnest(?::text[], ?::int[], ?::text[], ?::text[]) AS g(grp, seq, ca, jid)
             CROSS JOIN LATERAL (
                  SELECT message_group, sequence, created_at, id FROM msg_dispatch_jobs
                   WHERE status = 'PENDING' AND message_group = g.grp AND scheduled_for > NOW()
                     AND (sequence, created_at, id) < (g.seq, g.ca::timestamptz, g.jid)
                   ORDER BY sequence, created_at, id
                   LIMIT 1) h""";

    private static final String GROUP_HELD_BEFORE_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM msg_dispatch_jobs
                 WHERE status = ANY(?::text[]) AND message_group = ?
                   AND (sequence, created_at, id) < (?, ?, ?))
            OR EXISTS (
                SELECT 1 FROM msg_dispatch_jobs
                 WHERE status = 'PENDING' AND message_group = ? AND scheduled_for > NOW()
                   AND (sequence, created_at, id) < (?, ?, ?))
            """;

    private static final String EARLIEST_HOLDERS_SQL = "SELECT DISTINCT ON (message_group) message_group, sequence, created_at, id FROM ("
            + HOLDERS_OF_GROUPS_SQL + ") h ORDER BY message_group, sequence, created_at, id";

    /// A JDBC failure in one of the plain-JDBC statements, as the same
    /// unchecked type jOOQ threw when these ran through it, so callers'
    /// handling (and the 500 mapping at the processing endpoint) is unchanged.
    private static DataAccessException failed(String what, SQLException e) {
        return new DataAccessException("dispatch job " + what + " failed", e);
    }

    /// The delivery-time hold-back gate (spec §5, §9: Go `GroupHeldBefore`):
    /// is `job` positioned behind a the group holders ([#HOLDERS_OF_GROUPS_SQL]) row in the same
    /// `message_group`? The comparison is **positional**, over the same
    /// `(sequence, created_at, id)` triple the claim query orders by — never
    /// set membership, which would include the holder itself the instant its
    /// own backoff expired and the group would never move again. A job with
    /// no `message_group` cannot be held (`false`).
    public boolean groupHeldBefore(DispatchJob job) {
        return groupHeldBefore(job.messageGroup(), job.sequence(), job.createdAt(), job.id());
    }

    /// Claim-time equivalent of [#groupHeldBefore(DispatchJob)] (spec §3
    /// `filterByDispatchMode`/§9): the SAME the group holders ([#HOLDERS_OF_GROUPS_SQL]) predicate and
    /// the SAME positional `(sequence, created_at, id)` comparison, called
    /// against a [ClaimRow]'s own position rather than a hydrated
    /// [DispatchJob]. `messageGroup == null` can never be held, mirroring the
    /// other overload exactly (a `NULL` group is excluded from the claim-time
    /// check the same way it is excluded from delivery-time).
    public boolean groupHeldBefore(String messageGroup, int sequence, Instant createdAt, String id) {
        if (messageGroup == null) return false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(GROUP_HELD_BEFORE_SQL)) {
            ps.setArray(1, conn.createArrayOf("text", HOLDING_STATUSES.toArray(String[]::new)));
            ps.setString(2, messageGroup);
            ps.setInt(3, sequence);
            ps.setObject(4, utc(createdAt));
            ps.setString(5, id);
            ps.setString(6, messageGroup);
            ps.setInt(7, sequence);
            ps.setObject(8, utc(createdAt));
            ps.setString(9, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw failed("group hold-back check", e);
        }
    }

    /// Batch form of [#groupHeldBefore(String,int,Instant,String)]: ONE query
    /// for every candidate, instead of one round trip per `BLOCK_ON_ERROR`
    /// candidate (up to a whole claim's worth per tick).
    ///
    /// Asks for the EARLIEST holder ([#HOLDERS_OF_GROUPS_SQL]) of each distinct candidate
    /// group (`DISTINCT ON`, ordered by the same `(sequence, created_at, id)`
    /// triple), then applies the positional test in memory: a candidate is
    /// held iff that earliest holder is positioned strictly before it — which
    /// is exactly when SOME holder is, so the answer per candidate is the one
    /// the per-candidate query gave. Candidates without a `message_group`
    /// cannot be held. The holder query has no `created_at` bound for the
    /// same reason the per-candidate one has none: the position is
    /// `sequence`-first, so an earlier holder can have a later `created_at`.
    ///
    /// The in-memory tie-break on `id` compares strings by code unit; ids are
    /// uppercase-alphanumeric TSIDs, where that matches the database's order.
    ///
    /// @return the ids of the candidates that are held back
    public Set<String> heldBeforeIds(List<ClaimRow> candidates) {
        String[] groups = candidates.stream().map(ClaimRow::messageGroup).filter(Objects::nonNull)
                .distinct().toArray(String[]::new);
        if (groups.length == 0) return Set.of();
        Map<String, ClaimRow> earliestHolder = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(EARLIEST_HOLDERS_SQL)) {
            ps.setArray(1, conn.createArrayOf("text", HOLDING_STATUSES.toArray(String[]::new)));
            ps.setArray(2, conn.createArrayOf("text", groups));
            // each group's LAST candidate in position order bounds the queue probe
            Map<String, ClaimRow> last = new HashMap<>();
            for (ClaimRow c : candidates) {
                if (c.messageGroup() == null) continue;
                last.merge(c.messageGroup(), c, (a, b) -> positionedBefore(a, b) ? b : a);
            }
            String[] lg = new String[groups.length];
            Integer[] ls = new Integer[groups.length];
            String[] lc = new String[groups.length];
            String[] li = new String[groups.length];
            for (int i = 0; i < groups.length; i++) {
                ClaimRow c = last.get(groups[i]);
                lg[i] = groups[i];
                ls[i] = c.sequence();
                lc[i] = c.createdAt().toString();
                li[i] = c.id();
            }
            ps.setArray(3, conn.createArrayOf("text", lg));
            ps.setArray(4, conn.createArrayOf("int4", ls));
            ps.setArray(5, conn.createArrayOf("text", lc));
            ps.setArray(6, conn.createArrayOf("text", li));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String group = rs.getString(1);
                    earliestHolder.put(group, new ClaimRow(rs.getString(4), null, group, null, null, null,
                            rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getInt(2), null, null));
                }
            }
        } catch (SQLException e) {
            throw failed("hold-back query", e);
        }
        Set<String> held = new HashSet<>();
        for (ClaimRow c : candidates) {
            if (c.messageGroup() == null) continue;
            ClaimRow holder = earliestHolder.get(c.messageGroup());
            if (holder != null && positionedBefore(holder, c)) {
                held.add(c.id());
            }
        }
        return held;
    }

    /// `(sequence, created_at, id)` of `a` strictly before `b`'s.
    private static boolean positionedBefore(ClaimRow a, ClaimRow b) {
        int bySequence = Integer.compare(a.sequence(), b.sequence());
        if (bySequence != 0) return bySequence < 0;
        int byCreated = a.createdAt().compareTo(b.createdAt());
        if (byCreated != 0) return byCreated < 0;
        return a.id().compareTo(b.id()) < 0;
    }

    // ── The scheduler's claim: a plain read ─────────────────────────────────

    /// The claim: ONE plain SELECT — no lock, no transaction, no write. The due `PENDING` jobs in delivery
    /// order, outside the paused subscriptions (`$paused`), the groups remembered as held (`$held`) and this
    /// process's in-flight ids (`$inflight`). It walks `idx_dispatch_jobs_status_group` in order (the status
    /// equality prefix, then the index's own order: a Merge Append across the partitions) and stops at the
    /// limit — no Sort whatever the statistics say. `status = 'PENDING'` is a literal; under the scheduler pool's
    /// `force_custom_plan` a bind gives the same plan. No array is ever NULL (`<> ALL(NULL)` is NULL).
    static final String CLAIM_PENDING_SQL = """
            SELECT id, created_at, message_group, sequence, scheduled_for, subscription_id,
                   dispatch_pool_id, client_id, mode, queue, updated_at
              FROM msg_dispatch_jobs
             WHERE status = 'PENDING'
               AND (scheduled_for IS NULL OR scheduled_for <= NOW())
               AND (subscription_id IS NULL OR subscription_id <> ALL(?::text[]))
               AND (message_group IS NULL OR message_group <> ALL(?::text[]))
               AND id <> ALL(?::text[])
             ORDER BY message_group NULLS LAST, sequence, created_at, id
             LIMIT ?""";

    /// The order the hold-back and the lanes depend on: group (`NULL` last), sequence, creation time, id. The
    /// database orders by its collation; within a group the (sequence, created_at, id) order is what matters and
    /// is collation-independent for TSIDs; re-sorting makes the order exact for the caller.
    static final java.util.Comparator<ClaimRow> CLAIM_ORDER = java.util.Comparator
            .comparing(ClaimRow::messageGroup, java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder()))
            .thenComparingInt(ClaimRow::sequence)
            .thenComparing(ClaimRow::createdAt)
            .thenComparing(ClaimRow::id);

    /// Up to `limit` due `PENDING` jobs in delivery order (see [#CLAIM_PENDING_SQL]).
    public List<ClaimRow> claimPending(int limit, Collection<String> pausedSubscriptionIds,
                                       Collection<String> heldGroups, Collection<String> inFlightIds) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(CLAIM_PENDING_SQL)) {
            ps.setArray(1, conn.createArrayOf("text", pausedSubscriptionIds.toArray(String[]::new)));
            ps.setArray(2, conn.createArrayOf("text", heldGroups.toArray(String[]::new)));
            ps.setArray(3, conn.createArrayOf("text", inFlightIds.toArray(String[]::new)));
            ps.setInt(4, limit);
            List<ClaimRow> claims = new ArrayList<>(Math.min(limit, 1024));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claims.add(new ClaimRow(rs.getString(1), rs.getString(6), rs.getString(3),
                            DispatchMode.parse(rs.getString(9)), rs.getString(7), rs.getString(8),
                            rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getInt(4), rs.getString(10),
                            rs.getObject(11, OffsetDateTime.class).toInstant()));
                }
            }
            claims.sort(CLAIM_ORDER);
            return claims;
        } catch (SQLException e) {
            throw failed("claim", e);
        }
    }

    /// Where the backlog count saturates: "100,000+".
    public static final int PENDING_BACKLOG_CAP = 100_000;

    /// Counts `PENDING` jobs up to a cap (an index range of at most cap+1 entries of the status prefix), so a huge
    /// backlog is never scanned in full.
    static final String PENDING_BACKLOG_SQL = "SELECT count(*) FROM (SELECT 1 FROM msg_dispatch_jobs"
            + " WHERE status = 'PENDING' LIMIT " + (PENDING_BACKLOG_CAP + 1) + ") s";

    /// The first DUE `PENDING` job in claim order: its `created_at` is the "oldest waiting" the gauge reports.
    static final String OLDEST_WAITING_SQL = "SELECT created_at FROM msg_dispatch_jobs"
            + " WHERE status = 'PENDING' AND (scheduled_for IS NULL OR scheduled_for <= NOW())"
            + " ORDER BY message_group NULLS LAST, sequence, created_at, id LIMIT 1";

    /// The backlog, sampled.
    ///
    /// @param count            `PENDING` jobs, saturating at [#PENDING_BACKLOG_CAP]` + 1`
    /// @param oldestCreatedAt  `created_at` of the first due one in claim order; `null` when none
    public record Backlog(long count, Instant oldestCreatedAt) {
    }

    public Backlog pendingBacklog() {
        try (Connection conn = dataSource.getConnection()) {
            long count;
            try (PreparedStatement ps = conn.prepareStatement(PENDING_BACKLOG_SQL); ResultSet rs = ps.executeQuery()) {
                rs.next();
                count = rs.getLong(1);
            }
            Instant oldest = null;
            try (PreparedStatement ps = conn.prepareStatement(OLDEST_WAITING_SQL); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) oldest = rs.getObject(1, OffsetDateTime.class).toInstant();
            }
            return new Backlog(count, oldest);
        } catch (SQLException e) {
            throw failed("backlog", e);
        }
    }

    /// Records one delivery attempt (dispatch-seam spec §5 "Attempt
    /// recording", Go `RecordAttempt`): one row of `msg_dispatch_job_attempts`
    /// keyed by an untyped TSID. `success` derives the stored `status`
    /// column (`SUCCESS`/`FAILURE`) — a cooperative deferral (`ack:false`,
    /// 429) is recorded with `success = false` exactly like a genuine
    /// failure, but `errorType = null`: a deferral is not an error, and
    /// persisting the empty string here is the regression the migration's
    /// `error_type` column shape guards against
    /// (`TestCompleteFailure_EmptyErrorTypeLeavesItNil`, spec §13).
    public void recordAttempt(String jobId, int attemptNumber, boolean success, Integer responseCode,
                               String responseBody, String errorMessage, AttemptErrorType errorType,
                               Attempt.RequestInfo requestInfo, Instant attemptedAt, Instant completedAt, Long durationMillis) {
        dsl.insertInto(A)
                .set(A.ID, Tsid.generate())
                .set(A.DISPATCH_JOB_ID, jobId)
                .set(A.ATTEMPT_NUMBER, attemptNumber)
                .set(A.STATUS, success ? "SUCCESS" : "FAILURE")
                .set(A.RESPONSE_CODE, responseCode)
                .set(A.RESPONSE_BODY, responseBody)
                .set(A.ERROR_MESSAGE, errorMessage)
                .set(A.ERROR_TYPE, errorType == null ? null : errorType.name())
                .set(A.REQUEST_INFO, requestInfo == null ? null : JSONB.jsonb(Json.write(requestInfo)))
                .set(A.DURATION_MILLIS, durationMillis)
                .set(A.ATTEMPTED_AT, utc(attemptedAt))
                .set(A.COMPLETED_AT, utc(completedAt))
                .set(A.CREATED_AT, utc(attemptedAt))
                .execute();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /// Out-of-range limits are corrected, not rejected (spec §8).
    private static int guard(int limit, int max, int fallback) {
        return limit <= 0 || limit > max ? fallback : limit;
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static DispatchJob toEntity(MsgDispatchJobsRecord row) {
        return new DispatchJob(
                row.getId(),
                row.getExternalId(),
                kind(row.getId(), row.getKind()),
                row.getCode(),
                row.getSource(),
                row.getSubject(),
                row.getTargetUrl(),
                Protocol.parse(row.getProtocol()),
                row.getPayload(),
                row.getPayloadContentType() == null ? DispatchJob.DEFAULT_PAYLOAD_CONTENT_TYPE : row.getPayloadContentType(),
                row.getDataOnly(),
                row.getEventId(),
                row.getCorrelationId(),
                row.getClientId(),
                row.getSubscriptionId(),
                row.getServiceAccountId(),
                row.getDispatchPoolId(),
                row.getMessageGroup(),
                DispatchMode.parse(row.getMode()),
                row.getSequence(),
                row.getTimeoutSeconds(),
                row.getSchemaId(),
                row.getMaxRetries(),
                retryStrategy(row.getId(), row.getRetryStrategy()),
                status(row.getId(), row.getStatus()),
                row.getAttemptCount(),
                row.getLastError(),
                fromJsonb(row.getMetadata()),
                row.getIdempotencyKey(),
                row.getDescriptor(),
                row.getQueue(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant(),
                instant(row.getScheduledFor()),
                instant(row.getExpiresAt()),
                instant(row.getLastAttemptAt()),
                instant(row.getCompletedAt()),
                row.getDurationMillis());
    }

    private static DispatchJobProjection toProjection(MsgDispatchJobsReadRecord row) {
        return new DispatchJobProjection(
                row.getId(),
                row.getExternalId(),
                row.getSource(),
                kind(row.getId(), row.getKind()),
                row.getCode(),
                row.getSubject(),
                row.getEventId(),
                row.getCorrelationId(),
                row.getTargetUrl(),
                Protocol.parse(row.getProtocol()),
                row.getServiceAccountId(),
                row.getClientId(),
                row.getSubscriptionId(),
                row.getDispatchPoolId(),
                DispatchMode.parse(row.getMode()),
                row.getMessageGroup(),
                row.getSequence() == null ? 0 : row.getSequence(),
                row.getTimeoutSeconds() == null ? 0 : row.getTimeoutSeconds(),
                status(row.getId(), row.getStatus()),
                row.getMaxRetries(),
                retryStrategy(row.getId(), row.getRetryStrategy()),
                instant(row.getScheduledFor()),
                instant(row.getExpiresAt()),
                row.getAttemptCount(),
                instant(row.getLastAttemptAt()),
                instant(row.getCompletedAt()),
                row.getDurationMillis(),
                row.getLastError(),
                row.getIdempotencyKey(),
                row.getDescriptor(),
                fromJsonb(row.getMetadata()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /// `success` is derived from the `status` column; a `NULL` `error_type`
    /// is "none", not `UNKNOWN` (spec §1.2). `request_info` (added 2026-09-22)
    /// is `NULL` on any attempt recorded before that date — reads as `null`,
    /// not a malformed-JSON error.
    private static Attempt toAttempt(MsgDispatchJobAttemptsRecord row) {
        return new Attempt(
                row.getAttemptNumber() == null ? 0 : row.getAttemptNumber(),
                row.getAttemptedAt() == null ? Instant.EPOCH : row.getAttemptedAt().toInstant(),
                instant(row.getCompletedAt()),
                row.getDurationMillis(),
                row.getResponseCode(),
                row.getResponseBody(),
                "SUCCESS".equals(row.getStatus()),
                row.getErrorMessage(),
                row.getErrorType() == null ? null : attemptErrorType(row.getDispatchJobId(), row.getErrorType()),
                requestInfoFromJsonb(row.getRequestInfo()));
    }

    /// `NULL`, empty, or a document that fails to parse reads as no recorded
    /// request — never fails the read (a `request_info` shape mismatch must
    /// not make a job's attempt history unreadable, the same reasoning as
    /// [#fromJsonb(JSONB)]'s metadata guard).
    private static Attempt.RequestInfo requestInfoFromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isEmpty()) return null;
        try {
            return Json.MAPPER.readValue(jsonb.data(), Attempt.RequestInfo.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /// `NULL`, empty, or any non-array document reads as no metadata; a pair
    /// without a textual `key` and `value` is dropped (spec §9).
    private static List<DispatchJob.Metadata> fromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isEmpty()) return List.of();
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(jsonb.data());
        } catch (JacksonException e) {
            throw new IllegalStateException("msg_dispatch_jobs.metadata is not valid JSON", e);
        }
        if (!node.isArray()) return List.of();
        var out = new ArrayList<DispatchJob.Metadata>(node.size());
        for (JsonNode pair : node) {
            JsonNode key = pair.get("key");
            JsonNode value = pair.get("value");
            if (key != null && key.isTextual() && value != null && value.isTextual()) {
                out.add(new DispatchJob.Metadata(key.asText(), value.asText()));
            }
        }
        return List.copyOf(out);
    }

    /// [DispatchJobStatus#parse], wrapped so a corrupt stored value fails
    /// loudly with the offending row's id (X-06) instead of propagating a
    /// bare [DispatchJobStatus.UnrecognisedStatusException] with no context.
    private static DispatchJobStatus status(String rowId, String stored) {
        try {
            return DispatchJobStatus.parse(stored);
        } catch (DispatchJobStatus.UnrecognisedStatusException e) {
            throw new CorruptDispatchJobException(rowId, e);
        }
    }

    /// [DispatchJobKind#parse], wrapped the same way as [#status] (X-06).
    private static DispatchJobKind kind(String rowId, String stored) {
        try {
            return DispatchJobKind.parse(stored);
        } catch (DispatchJobKind.UnrecognisedDispatchJobKindException e) {
            throw new CorruptDispatchJobException(rowId, e);
        }
    }

    /// [RetryStrategy#parse], wrapped the same way as [#status] (X-06).
    private static RetryStrategy retryStrategy(String rowId, String stored) {
        try {
            return RetryStrategy.parse(stored);
        } catch (RetryStrategy.UnrecognisedRetryStrategyException e) {
            throw new CorruptDispatchJobException(rowId, e);
        }
    }

    /// [AttemptErrorType#parse], wrapped the same way as [#status] (X-06);
    /// `dispatchJobId` names the parent job since an attempt has no
    /// separately reported id in this exception shape.
    private static AttemptErrorType attemptErrorType(String dispatchJobId, String stored) {
        try {
            return AttemptErrorType.parse(stored);
        } catch (AttemptErrorType.UnrecognisedAttemptErrorTypeException e) {
            throw new CorruptDispatchJobException(dispatchJobId, e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
