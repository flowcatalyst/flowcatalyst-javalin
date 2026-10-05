package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.db.generated.tables.MsgDispatchJobs;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.platform.dispatchjob.jfr.DispatchTransitionRefusedEvent;
import io.flowcatalyst.platform.dispatchjob.processing.ProcessingTransitions;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;

/// The ONE owner of `msg_dispatch_jobs.status` (lifecycle spec, step 1 of 3).
/// Every insert into the table and every write of its `status` column lives in
/// this file; fan-out, API ingest, the delivery callback, the scheduler, the
/// reaper, the settled hook and the operator actions all call it.
/// [DispatchJobRepository] keeps the reads. `DispatchJobLifecycleEnforcementTest`
/// fails the build when any other production file writes the table.
///
/// ## Two primitives
///
/// Exactly two private primitives decide a job's status with respect to
/// `PENDING`, and each issues ONE statement that also keeps
/// `msg_dispatch_queue` exact (dispatch-queue spec, step 2 of 3):
///
/// - [#enterPending] — the single place a job becomes, or is refreshed as,
///   `PENDING` (retry, deferral, hold, settled-return, reaper sweep, operator
///   requeue).
/// - [#leavePending] — the single place a job stops being `PENDING` (scheduler
///   mark-`QUEUED`, callback claim-for-delivery, a terminal outcome that may
///   find the job `PENDING`).
///
/// The public operations are thin, named wrappers that pick the [Transition]
/// (which names the statuses it may move FROM), the [Selector] and the extra
/// columns. Creation (`PENDING` by insert) has no selector and is the INSERT
/// statements at the bottom of this class — the one place a row is born.
///
/// ## The queue table
///
/// `msg_dispatch_queue` has exactly one row for every job whose status is
/// `PENDING` and none for any other job; the row mirrors the job's current
/// values and its `version` is the job's `updated_at`. It is written ONLY here
/// (and by the partition manager's drop and `fcdev fresh`, named in
/// `DispatchJobLifecycleEnforcementTest`), by explicit statements — no
/// triggers — each ONE SQL statement, a data-modifying CTE, so the job change
/// and the queue change are atomic with no new transaction and no extra round
/// trip (a caller that passes a transaction keeps doing so):
///
/// - create: `WITH ins AS (INSERT INTO msg_dispatch_jobs … ON CONFLICT DO NOTHING
///   RETURNING …) INSERT INTO msg_dispatch_queue … SELECT … FROM ins ON CONFLICT
///   (job_id) DO NOTHING` — only rows actually inserted get a queue row;
/// - [#enterPending]: `WITH moved AS (UPDATE … RETURNING …) INSERT INTO
///   msg_dispatch_queue … SELECT … FROM moved ON CONFLICT (job_id) DO UPDATE …
///   RETURNING …` — entering or re-entering `PENDING` always refreshes `version`
///   and `scheduled_for` and resets `claimed_at`;
/// - [#leavePending]: `WITH moved AS (UPDATE … RETURNING …), gone AS (DELETE FROM
///   msg_dispatch_queue …) SELECT … FROM moved` — delete-if-exists, since a
///   terminal outcome or a delivery claim may or may not find the job `PENDING`;
/// - transitions with `PENDING` on neither side (operator cancel / complete from
///   `FAILED`) write no queue row.
///
/// Mark-`QUEUED` additionally removes a queue row at the claimed `version` for a
/// job in its batch even when the job row itself did not match (its status
/// moved on without the queue row being refreshed). A queue row whose version
/// differs (the job re-entered `PENDING` since the claim) is left alone.
///
/// **A known, accepted anomaly.** Under READ COMMITTED a statement's CTE sees
/// the snapshot taken when the statement started. If an *enter* and a *leave*
/// for the SAME job race, and the leave blocks on the job's row lock behind the
/// enter, the leave's `DELETE` cannot see the queue row the enter has just
/// inserted, so a queue row can outlive a job that is no longer `PENDING`. The
/// opposite error (a `PENDING` job with no queue row, i.e. a lost job) cannot
/// happen this way: an enter that waits behind a leave re-inserts with `ON
/// CONFLICT`, which does see committed rows. The stale extra row is harmless
/// and self-heals — the scheduler's mark-`QUEUED` removes it (rule above), and
/// [#reconcileQueue] sweeps it. The hot paths are deliberately NOT wrapped in
/// transactions to avoid it. [#queueDrift] counts both kinds of disagreement.
///
/// ## The claim (dispatch-queue spec, step 3b)
///
/// The scheduler claims from this table, not from `msg_dispatch_jobs`, by DELETING
/// the rows it takes: [#claimPending] is two plain statements (S1 reads the ids in
/// order, S2 deletes them and `RETURNING` is the claim — two claimers can never
/// both get a row). A claimed job is `PENDING` with no queue row until
/// [#markQueued] makes it `QUEUED`, or [#restore] puts its row back (from the job
/// table, so a job that moved on is not resurrected) when it was not published.
/// A claimer that dies leaves such jobs; [#reconcileQueue] (and the leader's
/// start-up pass) restores them. The invariant: every queue row belongs to a
/// `PENDING` job with the same values; a `PENDING` job without a row is in the
/// claiming process's in-flight set, or reconcile restores it. The table's
/// `claimed_at` column is unused (always `NULL`; dropped later).
///
/// ## Rules
///
/// 1. Every transition names its FROM statuses in the SQL `WHERE`
///    ([Transition#from]); the table of [Transition] constants IS the
///    lifecycle. A callback outcome moves a job only from a LIVE status, so a
///    late callback never overwrites or resurrects a settled job. Operator
///    requeue is the one transition allowed from any status; operator
///    cancel/complete move from `FAILED` (and its legacy alias `ERROR`) only.
/// 2. A transition that matches no row is not an error: it is counted
///    (`fc_dispatch_job_transition_refused_total{transition}`), logged at
///    debug, and emitted as a JFR event.
/// 3. Every transition stamps `updated_at`.
/// 4. No generic "save the entity" path writes status.
///
/// ## Statement text
///
/// Hot-path statements are plain JDBC: a constant text keeps one
/// server-prepared statement per connection (see the note in
/// [DispatchJobRepository]). There are no partial indexes on the dispatch path
/// any more, so nothing depends on a literal being provable against an index
/// predicate; the transitions' status guards are still literals in the text (a
/// `Transition` names them), every other value is bound. The statements are
/// assembled from fixed fragments in this file — never from request input.
///
/// The `executor` of the primitives is either a pooled connection (the
/// instance methods; the pool this lifecycle was built over — the routed data
/// source, or the scheduler's own pool) or a caller's open transaction (the
/// `Connection` overloads, the unit-of-work writers).
public final class DispatchJobLifecycle implements ProcessingTransitions {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchJobLifecycle.class);

    private static final MsgDispatchJobs T = MSG_DISPATCH_JOBS;

    /// The columns every primitive returns: what a queue table would be keyed
    /// and ordered by. `updated_at` is the new row version.
    static final String RETURNING = "j.id, j.created_at, j.message_group, j.sequence, j.scheduled_for, "
            + "j.subscription_id, j.dispatch_pool_id, j.client_id, j.mode, j.queue, j.updated_at";

    /// [#RETURNING] without the alias, for reading a CTE's rows back (`FROM moved`).
    private static final String MOVED_COLUMNS = "id, created_at, message_group, sequence, scheduled_for, "
            + "subscription_id, dispatch_pool_id, client_id, mode, queue, updated_at";

    /// `msg_dispatch_queue`'s columns a job's values fill, in [#MOVED_COLUMNS] order.
    private static final String QUEUE_COLUMNS = "job_id, job_created_at, message_group, sequence, scheduled_for, "
            + "subscription_id, dispatch_pool_id, client_id, mode, queue, version";

    /// The same columns as the queue statement returns them, in [Moved]'s order.
    private static final String QUEUE_RETURNING = "q.job_id, q.job_created_at, q.message_group, q.sequence, "
            + "q.scheduled_for, q.subscription_id, q.dispatch_pool_id, q.client_id, q.mode, q.queue, q.version";

    /// The tail of every statement that puts a row in the queue from a CTE of
    /// jobs (`moved` / `ins`): `INSERT INTO msg_dispatch_queue (…) SELECT … FROM <cte>`.
    private static String queueInsertFrom(String cte) {
        return "INSERT INTO msg_dispatch_queue AS q (" + QUEUE_COLUMNS + ") SELECT " + MOVED_COLUMNS + " FROM " + cte;
    }

    /// Entering `PENDING` for a job that may already have a queue row: refresh
    /// every mirrored value and reset the claim.
    private static final String QUEUE_UPSERT = " ON CONFLICT (job_id) DO UPDATE SET"
            + " job_created_at = EXCLUDED.job_created_at, message_group = EXCLUDED.message_group,"
            + " sequence = EXCLUDED.sequence, scheduled_for = EXCLUDED.scheduled_for,"
            + " subscription_id = EXCLUDED.subscription_id, dispatch_pool_id = EXCLUDED.dispatch_pool_id,"
            + " client_id = EXCLUDED.client_id, mode = EXCLUDED.mode, queue = EXCLUDED.queue,"
            + " version = EXCLUDED.version";

    /// Leaving `PENDING`: delete the queue row of every job the UPDATE moved, if there is one.
    private static final Gone GONE_MOVED = new Gone("DELETE FROM msg_dispatch_queue q USING moved m WHERE q.job_id = m.id",
            List.of());

    /// The statement that removes queue rows when a transition leaves `PENDING`, and its bound values.
    private record Gone(String sql, List<Object> params) {
    }

    /// A job a primitive changed, as the [#RETURNING] columns read.
    public record Moved(String id, Instant createdAt, String messageGroup, int sequence, Instant scheduledFor,
                        String subscriptionId, String dispatchPoolId, String clientId, String mode, String queue,
                        Instant updatedAt) {
    }

    /// Status sets, in a holder of their own so the [Transition] constants can read
    /// them without initialising this class from inside its own static initialiser.
    private static final class Statuses {
        /// PENDING, QUEUED, PROCESSING, plus the legacy alias IN_PROGRESS of PROCESSING.
        static final List<String> LIVE = List.of("PENDING", "QUEUED", "PROCESSING", "IN_PROGRESS");
        /// FAILED, plus the legacy alias ERROR.
        static final List<String> FAILED_HEADS = List.of("FAILED", "ERROR");
    }

    /// Every status transition of the table, with the statuses it may move a
    /// job FROM (`null` = any) and the status it ends in. This is the
    /// lifecycle in one place; `DispatchJobLifecycleTest` pins every
    /// (transition, from-status) pair.
    public enum Transition {
        /// Birth by insert (no from-status; never touches an existing row).
        CREATE(null, "PENDING"),
        /// Scheduler: the broker accepted the job. Optimistic on the claimed version as well.
        MARK_QUEUED(List.of("PENDING"), "QUEUED"),
        /// Callback: one delivery claim. Not before `scheduled_for`.
        CLAIM_FOR_DELIVERY(List.of("PENDING", "QUEUED"), "PROCESSING"),
        /// Callback outcome: delivered.
        COMPLETE(Statuses.LIVE, "COMPLETED"),
        /// Callback outcome: retries exhausted / credentials refused.
        FAIL(Statuses.LIVE, "FAILED"),
        /// Callback outcome: retryable failure, budget remains.
        SCHEDULE_RETRY(Statuses.LIVE, "PENDING"),
        /// Callback outcome: cooperative deferral, or the delivery-time hold-back revert.
        RESCHEDULE(Statuses.LIVE, "PENDING"),
        /// Settled endpoint: the router acked the head; return siblings to PENDING.
        SETTLE_ACKED(List.of("QUEUED", "PROCESSING"), "PENDING"),
        /// Reaper: siblings stranded behind a failed head.
        SWEEP_STRANDED(List.of("QUEUED", "PROCESSING"), "PENDING"),
        /// Scheduler: a job the broker accepted that nothing has moved for the stale threshold.
        STALE_QUEUED(List.of("QUEUED"), "PENDING"),
        /// Operator resend: from any status.
        REQUEUE(null, "PENDING"),
        /// Operator ignore: a failed job becomes cancelled.
        CANCEL(Statuses.FAILED_HEADS, "CANCELLED"),
        /// Operator "handled out of band": a failed job becomes completed.
        OPERATOR_COMPLETE(Statuses.FAILED_HEADS, "COMPLETED");

        private final List<String> from;
        private final String to;

        Transition(List<String> from, String to) {
            this.from = from;
            this.to = to;
        }

        /// The statuses this transition may move a job from; empty = any status.
        public List<String> from() {
            return from == null ? List.of() : from;
        }

        public boolean fromAnyStatus() {
            return from == null;
        }

        /// The status a job ends in.
        public String to() {
            return to;
        }

        boolean allows(String status) {
            return from == null || from.contains(status);
        }

        /// ` AND j.status = 'X'` / ` AND j.status IN ('X', 'Y')`; empty for any status.
        String guardSql() {
            if (from == null) return "";
            if (from.size() == 1) return " AND j.status = '" + from.get(0) + "'";
            var sb = new StringBuilder(" AND j.status IN (");
            for (int i = 0; i < from.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('\'').append(from.get(i)).append('\'');
            }
            return sb.append(')').toString();
        }
    }

    // ── Process-global refusal counters ────────────────────────────────────

    private static final Map<Transition, LongAdder> REFUSED = new EnumMap<>(Transition.class);

    static {
        for (Transition t : Transition.values()) REFUSED.put(t, new LongAdder());
    }

    /// Transitions refused (matched no row) since the process started.
    public static long refused(Transition t) {
        return REFUSED.get(t).sum();
    }

    /// `fc_dispatch_job_transition_refused_total{transition}`: a late callback
    /// meeting a settled job, a claim that lost its race, a stale
    /// mark-`QUEUED`. Process-global like every Prometheus counter.
    public static MultiCollector collector() {
        return () -> {
            var b = CounterSnapshot.builder().name("fc_dispatch_job_transition_refused_total")
                    .help("Dispatch job status transitions that matched no row (job in a status the transition may not leave, or already moved on).");
            for (Transition t : Transition.values()) {
                b.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                        .labels(Labels.of("transition", t.name().toLowerCase(java.util.Locale.ROOT)))
                        .value(REFUSED.get(t).sum()).build());
            }
            return MetricSnapshots.builder().metricSnapshot(b.build()).build();
        };
    }

    // ── Construction ───────────────────────────────────────────────────────

    private final DataSource dataSource;

    /// `dataSource`: whatever pool the caller owns — the routed source, a
    /// background pool, or the scheduler's own pool.
    public DispatchJobLifecycle(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    // ── Executor and selector ──────────────────────────────────────────────

    private interface SqlWork<R> {
        R run(Connection conn) throws SQLException;
    }

    /// Where a statement runs: a pooled connection, or the caller's open transaction.
    private interface Exec {
        <R> R with(SqlWork<R> work) throws SQLException;
    }

    private Exec pool() {
        return new Exec() {
            @Override
            public <R> R with(SqlWork<R> work) throws SQLException {
                try (Connection c = dataSource.getConnection()) {
                    return work.run(c);
                }
            }
        };
    }

    private static Exec on(Connection tx) {
        return new Exec() {
            @Override
            public <R> R with(SqlWork<R> work) throws SQLException {
                return work.run(tx);
            }
        };
    }

    /// Which rows a primitive acts on. Fixed SQL fragments plus bound values:
    /// one job by `(id, created_at)`, a list of ids, or a predicate sweep.
    /// `with` is a leading CTE definition without the `WITH` keyword
    /// (`name AS (…)`), placed before the primitive's own `moved` CTE.
    /// Parameter order in the statement is `withParams`, `updated_at`, the
    /// changes' params, `fromParams`, `whereParams`, then the queue delete's.
    private record Selector(String with, List<Object> withParams, String from, List<Object> fromParams,
                            String where, List<Object> whereParams, String id, Instant createdAt, int requested) {

        static Selector one(String id, Instant createdAt) {
            return new Selector(null, List.of(), null, List.of(), "j.id = ? AND j.created_at = ?",
                    List.of(id, utc(createdAt)), id, createdAt, 1);
        }

        static Selector ids(List<String> ids) {
            return new Selector(null, List.of(), null, List.of(), "j.id = ANY(?)",
                    List.of((Object) ids.toArray(String[]::new)), null, null, ids.size());
        }

        Selector and(String condition) {
            return new Selector(with, withParams, from, fromParams, where + " AND " + condition, whereParams,
                    id, createdAt, requested);
        }
    }

    /// Extra columns a transition writes besides `status` and `updated_at`:
    /// a fixed `SET` fragment and its bound values.
    private record Changes(String sql, List<Object> params) {
        static final Changes NONE = new Changes("", List.of());

        static Changes of(String sql, Object... params) {
            return new Changes(sql, java.util.Arrays.asList(params));
        }
    }

    // ── The two primitives ─────────────────────────────────────────────────

    /// The single place a job becomes (or is refreshed as) `PENDING`: the job's
    /// UPDATE and the queue row's upsert are ONE statement.
    private static List<Moved> enterPending(Exec ex, Transition t, Selector sel, Changes changes, Instant updatedAt) {
        return transition(ex, t, "PENDING", true, GONE_MOVED, sel, changes, updatedAt);
    }

    /// The single place a job stops being `PENDING`: the job's UPDATE and the
    /// queue row's delete are ONE statement. `gone` names which queue rows go.
    private static List<Moved> leavePending(Exec ex, Transition t, String to, Selector sel, Changes changes,
                                            Instant updatedAt) {
        return transition(ex, t, to, false, GONE_MOVED, sel, changes, updatedAt);
    }

    private static List<Moved> leavePending(Exec ex, Transition t, String to, Gone gone, Selector sel,
                                            Changes changes, Instant updatedAt) {
        return transition(ex, t, to, false, gone, sel, changes, updatedAt);
    }

    /// One statement:
    ///
    ///     WITH [<selector CTE>,] moved AS (UPDATE msg_dispatch_jobs j SET status = <literal>,
    ///         updated_at = … WHERE <selector> AND status <from> RETURNING <columns>)
    ///     entering: INSERT INTO msg_dispatch_queue … SELECT … FROM moved ON CONFLICT (job_id) DO UPDATE … RETURNING …
    ///     leaving:  , gone AS (DELETE FROM msg_dispatch_queue …) SELECT … FROM moved
    ///
    /// `updatedAt == null` stamps the database clock.
    private static List<Moved> transition(Exec ex, Transition t, String to, boolean enter, Gone gone, Selector sel,
                                          Changes changes, Instant updatedAt) {
        var sql = new StringBuilder(512);
        var params = new ArrayList<Object>();
        sql.append("WITH ");
        if (sel.with() != null) {
            sql.append(sel.with()).append(", ");
            params.addAll(sel.withParams());
        }
        sql.append("moved AS (UPDATE msg_dispatch_jobs j SET status = '").append(to).append("', updated_at = ");
        if (updatedAt == null) {
            sql.append("now()");
        } else {
            sql.append('?');
            params.add(updatedAt);
        }
        if (!changes.sql().isEmpty()) {
            sql.append(", ").append(changes.sql());
            params.addAll(changes.params());
        }
        if (sel.from() != null) {
            sql.append(" FROM ").append(sel.from());
            params.addAll(sel.fromParams());
        }
        sql.append(" WHERE ").append(sel.where());
        params.addAll(sel.whereParams());
        sql.append(t.guardSql()).append(" RETURNING ").append(RETURNING).append(")");
        if (enter) {
            sql.append(' ').append(queueInsertFrom("moved")).append(QUEUE_UPSERT).append(" RETURNING ").append(QUEUE_RETURNING);
        } else {
            sql.append(", gone AS (").append(gone.sql()).append(") SELECT ").append(MOVED_COLUMNS).append(" FROM moved");
            params.addAll(gone.params());
        }
        try {
            return ex.with(conn -> {
                List<Moved> moved = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    bind(conn, ps, params);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) moved.add(moved(rs));
                    }
                }
                if (moved.size() < sel.requested() && sel.requested() != Integer.MAX_VALUE) {
                    refusedRows(conn, t, sel, sel.requested() - moved.size());
                }
                return moved;
            });
        } catch (SQLException e) {
            throw new DataAccessException("dispatch job " + t.name().toLowerCase(java.util.Locale.ROOT) + " failed", e);
        }
    }

    private static void bind(Connection conn, PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) {
            switch (p) {
                case String[] a -> ps.setArray(i, conn.createArrayOf("text", a));
                case Instant ts -> ps.setObject(i, utc(ts));
                case null -> ps.setObject(i, null);
                default -> ps.setObject(i, p);
            }
            i++;
        }
    }

    private static Moved moved(ResultSet rs) throws SQLException {
        return new Moved(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getString(3),
                rs.getInt(4), instant(rs.getObject(5, OffsetDateTime.class)), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, OffsetDateTime.class).toInstant());
    }

    /// A transition matched fewer rows than it was asked for: count it, log it
    /// (with the status found when it is one job and debug is on), emit JFR.
    private static void refusedRows(Connection conn, Transition t, Selector sel, int n) {
        REFUSED.get(t).add(n);
        String found = null;
        if (sel.id() != null && LOG.isDebugEnabled()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM msg_dispatch_jobs WHERE id = ? AND created_at = ?")) {
                ps.setString(1, sel.id());
                ps.setObject(2, utc(sel.createdAt()));
                try (ResultSet rs = ps.executeQuery()) {
                    found = rs.next() ? rs.getString(1) : "(no such job)";
                }
            } catch (SQLException ignored) {
                // best effort: only for the log line
            }
        }
        LOG.atDebug().setMessage("dispatch job transition refused")
                .addKeyValue("transition", t.name())
                .addKeyValue("job_id", sel.id())
                .addKeyValue("status_found", found)
                .addKeyValue("refused", n)
                .log();
        var event = new DispatchTransitionRefusedEvent();
        if (event.shouldCommit()) {
            event.transition = t.name();
            event.jobId = sel.id();
            event.refused = n;
            event.commit();
        }
    }

    // ── Scheduler ──────────────────────────────────────────────────────────

    /// Marks jobs the broker accepted `QUEUED`, in bulk, on a pooled
    /// connection with no transaction. Bounded by the batch's own `created_at`
    /// span so the `created_at`-partitioned table prunes to the partitions the
    /// rows span.
    ///
    /// **Optimistic on the version the claim read** (`status = 'PENDING' AND
    /// updated_at = claimed updated_at`). The claim holds no lock, so the
    /// router can deliver a job and the callback can move it on before this
    /// statement runs — and not only forward: it can reschedule it back to
    /// `PENDING` (a retry, a deferral, a `BLOCK_ON_ERROR` hold). A status guard
    /// alone would then mark a job `QUEUED` whose message is already gone, and
    /// nothing recovers `QUEUED`. Every transition here stamps `updated_at`,
    /// so the version differs after any of them.
    ///
    /// @param published rows exactly as [#claimPending] returned them
    /// @return the rows actually updated — fewer than `published.size()` when
    ///         some had already moved on
    public int markQueued(List<ClaimRow> published) {
        if (published.isEmpty()) return 0;
        String[] ids = new String[published.size()];
        String[] versions = new String[published.size()];
        Instant spanStart = null;
        Instant spanEnd = null;
        for (int i = 0; i < ids.length; i++) {
            ClaimRow c = published.get(i);
            ids[i] = c.id();
            versions[i] = c.updatedAt().toString();
            if (spanStart == null || c.createdAt().isBefore(spanStart)) spanStart = c.createdAt();
            if (spanEnd == null || c.createdAt().isAfter(spanEnd)) spanEnd = c.createdAt();
        }
        var sel = new Selector(null, List.of(), "unnest(?::text[], ?::text[]) AS v(id, version)",
                List.of(ids, versions),
                "j.id = ANY(?) AND j.id = v.id AND j.updated_at = v.version::timestamptz"
                        + " AND j.created_at >= ? AND j.created_at <= ?",
                List.of(ids, spanStart, spanEnd), null, null, ids.length);
        // The queue rows to remove: those of the jobs the UPDATE moved, plus any row
        // of the batch still at the claimed version (a stale one whose job moved on
        // without the row being refreshed). A row at another version belongs to a
        // job that re-entered PENDING since the claim: left alone.
        // The version is carried through `d` and re-checked by the DELETE itself: a stale-row id's version
        // match was made in the sub-select, on the statement's snapshot, but if the job re-entered PENDING
        // meanwhile the DELETE waits on the row lock, re-evaluates under READ COMMITTED against the REFRESHED
        // row, and would delete it (a PENDING job with no queue row). Rows the UPDATE moved need no check:
        // the statement holds the job's row lock, so the job cannot re-enter PENDING under it.
        var gone = new Gone("DELETE FROM msg_dispatch_queue q USING ("
                + "SELECT m.id, NULL::timestamptz AS version FROM moved m"
                + " UNION ALL SELECT b.id, b.version::timestamptz FROM unnest(?::text[], ?::text[]) AS b(id, version)"
                + " JOIN msg_dispatch_queue q2 ON q2.job_id = b.id AND q2.version = b.version::timestamptz"
                + ") d WHERE q.job_id = d.id AND (d.version IS NULL OR q.version = d.version)", List.of(ids, versions));
        return leavePending(pool(), Transition.MARK_QUEUED, "QUEUED", gone, sel, Changes.NONE, Instant.now()).size();
    }

    // ── The claim and its housekeeping (queue table) ───────────────────────

    /// S1 of the claim: the ids to take, in order. Walks `idx_dispatch_queue_order` and stops at `LIMIT`;
    /// the due, paused and held-group filters are skipped over, never sorted. Arrays always bound.
    private static final String CLAIM_SELECT_SQL = """
            SELECT job_id FROM msg_dispatch_queue
             WHERE (scheduled_for IS NULL OR scheduled_for <= NOW())
               AND (subscription_id IS NULL OR subscription_id <> ALL(?::text[]))
               AND (message_group IS NULL OR message_group <> ALL(?::text[]))
             ORDER BY message_group NULLS LAST, sequence, job_created_at, job_id
             LIMIT ?""";

    /// S2: deletes the ids S1 chose; the rows it RETURNS are the claim. A row refreshed to a future
    /// `scheduled_for` between S1 and S2 is not taken.
    private static final String CLAIM_DELETE_SQL = "DELETE FROM msg_dispatch_queue WHERE job_id = ANY(?::text[])"
            + " AND (scheduled_for IS NULL OR scheduled_for <= NOW()) RETURNING " + QUEUE_COLUMNS;

    /// The order [#claimPending] returns its rows in, and the order the hold-back and the lanes depend on:
    /// group (`NULL` last), sequence, job creation time, id. `RETURNING` is unordered, so the result is
    /// sorted here. Across groups the order carries no meaning (a group lives in one lane); within a group it
    /// is the database's own order exactly (TSID ids compare the same in any collation).
    static final java.util.Comparator<ClaimRow> CLAIM_ORDER = java.util.Comparator
            .comparing(ClaimRow::messageGroup, java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder()))
            .thenComparingInt(ClaimRow::sequence)
            .thenComparing(ClaimRow::createdAt)
            .thenComparing(ClaimRow::id);

    /// Claims up to `limit` waiting jobs, in order: due (`scheduled_for` null or past), not of a paused
    /// subscription (a row with no subscription is never excluded), and not of a group in `heldGroups` (groups
    /// the poller has just found held back; ungrouped rows are never excluded). Two plain statements on
    /// pooled connections, autocommit, no transaction and no locking clause. Empty collections bind empty
    /// arrays, never NULL (`<> ALL(NULL)` is NULL and would exclude every row).
    ///
    /// The `BLOCK_ON_ERROR` hold-back is deliberately NOT here: it stays the caller's positional check
    /// ([DispatchJobRepository#heldBeforeIds]) over the claimed rows, and a row it holds goes back through
    /// [#restore].
    public List<ClaimRow> claimPending(int limit, java.util.Collection<String> pausedSubscriptionIds,
                                       java.util.Collection<String> heldGroups) {
        try (Connection conn = dataSource.getConnection()) {
            List<String> ids = new ArrayList<>(Math.min(limit, 1024));
            try (PreparedStatement ps = conn.prepareStatement(CLAIM_SELECT_SQL)) {
                ps.setArray(1, conn.createArrayOf("text", pausedSubscriptionIds.toArray(String[]::new)));
                ps.setArray(2, conn.createArrayOf("text", heldGroups.toArray(String[]::new)));
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getString(1));
                }
            }
            if (ids.isEmpty()) return List.of();
            return takeRows(conn, ids);
        } catch (SQLException e) {
            throw new DataAccessException("dispatch job claim failed", e);
        }
    }

    /// S2 on its own: deletes these queue rows if still due and returns them, sorted by [#CLAIM_ORDER].
    static List<ClaimRow> takeRows(Connection conn, List<String> ids) throws SQLException {
        List<ClaimRow> claims = new ArrayList<>(ids.size());
        try (PreparedStatement ps = conn.prepareStatement(CLAIM_DELETE_SQL)) {
            ps.setArray(1, conn.createArrayOf("text", ids.toArray(String[]::new)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claims.add(new ClaimRow(rs.getString(1), rs.getString(6), rs.getString(3),
                            io.flowcatalyst.platform.shared.dispatch.DispatchMode.parse(rs.getString(9)),
                            rs.getString(7), rs.getString(8), rs.getObject(2, OffsetDateTime.class).toInstant(),
                            rs.getInt(4), rs.getString(10), rs.getObject(11, OffsetDateTime.class).toInstant()));
                }
            }
        }
        claims.sort(CLAIM_ORDER);
        return claims;
    }

    /// Puts claimed jobs back in the queue (a job that was not published, was dropped as poisoned, withheld
    /// by the doomed check or the hold-back, was published but not markable, or was still in flight at
    /// shutdown). From the JOB table, one statement: a job that is no longer `PENDING` is not resurrected,
    /// and a queue row that already exists (a newer one, from a refresh) is left alone.
    ///
    /// @return the queue rows created
    public int restore(java.util.Collection<ClaimRow> claimed) {
        if (claimed.isEmpty()) return 0;
        String[] ids = new String[claimed.size()];
        String[] createdAts = new String[claimed.size()];
        Instant spanStart = null;
        Instant spanEnd = null;
        int i = 0;
        for (ClaimRow c : claimed) {
            ids[i] = c.id();
            createdAts[i++] = c.createdAt().toString();
            if (spanStart == null || c.createdAt().isBefore(spanStart)) spanStart = c.createdAt();
            if (spanEnd == null || c.createdAt().isAfter(spanEnd)) spanEnd = c.createdAt();
        }
        return update(RESTORE_SQL, "restore", ids, createdAts, spanStart, spanEnd);
    }

    private static final String RESTORE_SQL = "INSERT INTO msg_dispatch_queue AS q (" + QUEUE_COLUMNS + ")"
            + " SELECT j.id, j.created_at, j.message_group, j.sequence, j.scheduled_for, j.subscription_id,"
            + " j.dispatch_pool_id, j.client_id, j.mode, j.queue, j.updated_at"
            + " FROM msg_dispatch_jobs j"
            + " JOIN unnest(?::text[], ?::text[]) AS u(id, created_at) ON j.id = u.id AND j.created_at = u.created_at::timestamptz"
            + " WHERE j.created_at >= ? AND j.created_at <= ? AND j.status = 'PENDING'"
            + " ON CONFLICT (job_id) DO NOTHING";

    private int update(String sql, String what, Object... params) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(conn, ps, java.util.Arrays.asList(params));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("dispatch queue " + what + " failed", e);
        }
    }

    /// A stale-`QUEUED` sweep: every job still `QUEUED` whose `updated_at` is before `cutoff` goes back to
    /// `PENDING` (a message lost after the broker accepted it, or a status update that never came). The
    /// duplicate this can cause is dropped by the router or skipped by the delivery callback. Unbounded,
    /// like the Go implementation's; the status prefix of `idx_dispatch_jobs_status_group` finds the rows.
    ///
    /// @return the jobs returned to `PENDING`
    public int recoverStaleQueued(Instant cutoff) {
        var sel = new Selector(null, List.of(), null, List.of(), "j.updated_at < ?", List.of(cutoff), null, null,
                Integer.MAX_VALUE);
        return enterPending(pool(), Transition.STALE_QUEUED, sel, Changes.NONE, null).size();
    }

    /// What one [#reconcileQueue] pass repaired.
    ///
    /// @param inserted `PENDING` jobs that had no queue row
    /// @param deleted  queue rows whose job is missing or not `PENDING`
    /// @param refreshed queue rows that differed from their job
    public record Reconciled(int inserted, int deleted, int refreshed) {
        public boolean isClean() {
            return inserted == 0 && deleted == 0 && refreshed == 0;
        }
    }

    private static final String RECONCILE_INSERT_SQL = "INSERT INTO msg_dispatch_queue AS q (" + QUEUE_COLUMNS + ")"
            + " SELECT j.id, j.created_at, j.message_group, j.sequence, j.scheduled_for, j.subscription_id,"
            + " j.dispatch_pool_id, j.client_id, j.mode, j.queue, j.updated_at"
            + " FROM msg_dispatch_jobs j"
            + " WHERE j.status = 'PENDING' AND j.updated_at < NOW() - make_interval(secs => ?)"
            + " AND j.id <> ALL(?::text[])"
            + " AND NOT EXISTS (SELECT 1 FROM msg_dispatch_queue x WHERE x.job_id = j.id)"
            + " LIMIT ? ON CONFLICT (job_id) DO NOTHING";

    private static final String RECONCILE_DELETE_SQL = "DELETE FROM msg_dispatch_queue q WHERE q.job_id IN ("
            + "SELECT q2.job_id FROM msg_dispatch_queue q2"
            + " WHERE NOT EXISTS (SELECT 1 FROM msg_dispatch_jobs j"
            + " WHERE j.id = q2.job_id AND j.created_at = q2.job_created_at AND j.status = 'PENDING')"
            + " LIMIT ?)";

    private static final String RECONCILE_REFRESH_SQL = "WITH stale AS ("
            + "SELECT q2.job_id FROM msg_dispatch_queue q2"
            + " JOIN msg_dispatch_jobs j2 ON j2.id = q2.job_id AND j2.created_at = q2.job_created_at"
            + " AND j2.status = 'PENDING'"
            + " WHERE (q2.version <> j2.updated_at OR q2.scheduled_for IS DISTINCT FROM j2.scheduled_for)"
            + " LIMIT ?) "
            + "UPDATE msg_dispatch_queue q SET message_group = j.message_group, sequence = j.sequence,"
            + " scheduled_for = j.scheduled_for, subscription_id = j.subscription_id,"
            + " dispatch_pool_id = j.dispatch_pool_id, client_id = j.client_id, mode = j.mode, queue = j.queue,"
            + " version = j.updated_at"
            + " FROM stale s, msg_dispatch_jobs j"
            + " WHERE q.job_id = s.job_id AND j.id = q.job_id AND j.created_at = q.job_created_at"
            + " AND j.status = 'PENDING'";

    /// Reconcile (a) alone: a `PENDING` job with no queue row whose `updated_at` is older than `jobAge` gets
    /// one, except the ids in `inFlight` (the calling process's own: jobs being published are PENDING with no
    /// row by design). This is the crash recovery too: a job whose claimer died is exactly such a job. The
    /// leader's start-up pass uses `Duration.ZERO` and an empty `inFlight` and repeats until it inserts nothing.
    ///
    /// @return the queue rows inserted (at most `maxRows`)
    public int restoreMissing(int maxRows, java.time.Duration jobAge, java.util.Collection<String> inFlight) {
        return update(RECONCILE_INSERT_SQL, "reconcile insert", jobAge.toMillis() / 1000.0,
                (Object) inFlight.toArray(String[]::new), maxRows);
    }

    /// The reconcile sweep: repairs what a bug, a crash, or an older binary writing the table left behind —
    /// three statements, each bounded to `maxRows`:
    ///
    ///  (a) [#restoreMissing];
    ///  (b) a queue row whose job is missing or not `PENDING` is deleted;
    ///  (c) a queue row whose `version` (or `scheduled_for`) differs from its job is refreshed from the job.
    ///
    /// The job-age guard leaves a job that is being written right now alone. A non-zero (b) or (c) is a bug
    /// worth a WARN; a non-zero (a) is a crash's leftovers or a failed restore.
    public Reconciled reconcileQueue(int maxRows, java.time.Duration jobAge, java.util.Collection<String> inFlight) {
        int inserted = restoreMissing(maxRows, jobAge, inFlight);
        int deleted = update(RECONCILE_DELETE_SQL, "reconcile delete", maxRows);
        int refreshed = update(RECONCILE_REFRESH_SQL, "reconcile refresh", maxRows);
        return new Reconciled(inserted, deleted, refreshed);
    }

    /// The scheduler's backlog, sampled.
    ///
    /// @param depth             jobs waiting: due queue rows
    /// @param oldestEnqueuedAt  when the oldest of them entered the queue; `null` when none
    public record QueueBacklog(long depth, Instant oldestEnqueuedAt) {
    }

    /// `count(*)` and the oldest `enqueued_at` of the due rows.
    public QueueBacklog queueBacklog() {
        String sql = "SELECT count(*), min(enqueued_at) FROM msg_dispatch_queue"
                + " WHERE (scheduled_for IS NULL OR scheduled_for <= NOW())";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new QueueBacklog(rs.getLong(1), instant(rs.getObject(2, OffsetDateTime.class)));
        } catch (SQLException e) {
            throw new DataAccessException("dispatch queue backlog failed", e);
        }
    }

    // ── Delivery callback (ProcessingTransitions) ──────────────────────────

    /// Atomically claims a job for one delivery: `PENDING`/`QUEUED` →
    /// `PROCESSING`, so the answer to "did I win this delivery?" is whether a
    /// row changed. A positive status list: an unrecognised stored value is
    /// un-claimable rather than deliverable.
    ///
    /// **Not before it is due**: a row whose `scheduled_for` is still in the
    /// future is not claimable either. The scheduler publishes a batch before
    /// marking it `QUEUED` (and holds no lock meanwhile), so a failed mark after
    /// the publish leaves the row `PENDING` with a copy at the broker; if the
    /// first copy's attempt then fails and schedules a retry, a stale copy
    /// would otherwise claim the `PENDING` row at once and make the retry
    /// early, skipping its backoff. Refusing it cannot strand the job: the next
    /// poller tick publishes it again once due.
    ///
    /// @return `true` when this call won the claim (exactly one row updated)
    @Override
    public boolean claimForDelivery(String id, Instant createdAt) {
        Instant now = Instant.now();
        var sel = Selector.one(id, createdAt).and("(j.scheduled_for IS NULL OR j.scheduled_for <= now())");
        return !leavePending(pool(), Transition.CLAIM_FOR_DELIVERY, "PROCESSING", sel,
                Changes.of("last_attempt_at = ?", now), now).isEmpty();
    }

    /// Delivery succeeded: a live job → `COMPLETED`, stamps `completed_at` / `duration_millis`.
    @Override
    public void markCompleted(String id, Instant createdAt, Instant completedAt, Long durationMillis) {
        leavePending(pool(), Transition.COMPLETE, "COMPLETED", Selector.one(id, createdAt),
                Changes.of("completed_at = ?, duration_millis = ?", completedAt, durationMillis), Instant.now());
    }

    /// Retries exhausted (or credentials refused): a live job → `FAILED`, records `lastError`.
    @Override
    public void markFailed(String id, Instant createdAt, String lastError) {
        leavePending(pool(), Transition.FAIL, "FAILED", Selector.one(id, createdAt),
                Changes.of("last_error = ?", lastError), Instant.now());
    }

    /// Retryable failure, budget remains: a live job → `PENDING` at `scheduledFor`,
    /// bumps `attempt_count` and records `lastError` — unlike [#reschedule], this
    /// DOES spend retry budget.
    @Override
    public void scheduleRetry(String id, Instant createdAt, Instant scheduledFor, int attemptCount, String lastError) {
        enterPending(pool(), Transition.SCHEDULE_RETRY, Selector.one(id, createdAt),
                Changes.of("scheduled_for = ?, attempt_count = ?, last_error = ?", scheduledFor, attemptCount, lastError),
                Instant.now());
    }

    /// Cooperative deferral, or the delivery-time hold-back revert: a live job
    /// → `PENDING` at `scheduledFor`. **No** `attempt_count` bump — back-pressure
    /// or hold-back, not a failure.
    @Override
    public void reschedule(String id, Instant createdAt, Instant scheduledFor) {
        enterPending(pool(), Transition.RESCHEDULE, Selector.one(id, createdAt),
                Changes.of("scheduled_for = ?", scheduledFor), Instant.now());
    }

    // ── Settled hook and reaper ────────────────────────────────────────────

    /// The settled endpoint's idempotent batch reset: every id in
    /// `QUEUED`/`PROCESSING` flips to `PENDING` with `scheduled_for` cleared
    /// and `last_error = reason`; a row already advanced past those two
    /// statuses (settled, or the reaper beat this call to it) is left
    /// untouched — the from-status guard is the whole idempotency contract.
    /// Stamps `updated_at` (database clock). Returns the ids actually changed.
    public List<String> settleAcked(List<String> ids, String reason) {
        if (ids.isEmpty()) return List.of();
        return enterPending(pool(), Transition.SETTLE_ACKED, Selector.ids(ids),
                Changes.of("scheduled_for = NULL, last_error = ?::text", reason), null)
                .stream().map(Moved::id).toList();
    }

    /// The reaper's backstop sweep: every `BLOCK_ON_ERROR` row in
    /// `QUEUED`/`PROCESSING` whose `message_group` has an earlier
    /// `FAILED`/legacy-`ERROR` head — positional over `(sequence, created_at,
    /// id)`, so a sibling positioned BEFORE the head is never touched — is
    /// reset to `PENDING`. A `QUEUED` sibling is reset regardless of age; a
    /// `PROCESSING` sibling only once `updated_at` is older than
    /// `processingLiveBefore`. `NEXT_ON_ERROR`/`IMMEDIATE` rows are never
    /// matched. Idempotent. The holder predicate is built from
    /// [DispatchJobRepository#HOLDING_STATUSES_SQL], the same fragment the
    /// claim-time gate is built from, so the two cannot drift. Returns the ids reset.
    public List<String> sweepStrandedSiblings(Instant processingLiveBefore, String reason) {
        var sel = new Selector(SWEEP_STRANDED_CTE, List.of(processingLiveBefore), "stranded st", List.of(),
                "j.id = st.id AND j.created_at = st.created_at", List.of(), null, null, Integer.MAX_VALUE);
        return enterPending(pool(), Transition.SWEEP_STRANDED, sel,
                Changes.of("scheduled_for = NULL, last_error = ?::text", reason), null)
                .stream().map(Moved::id).toList();
    }

    private static final String SWEEP_STRANDED_CTE = """
            stranded AS (
                SELECT s.id, s.created_at
                  FROM msg_dispatch_jobs s
                  JOIN msg_dispatch_jobs h
                    ON h.message_group = s.message_group
                   AND h.status IN (%s)
                   AND (h.sequence, h.created_at, h.id) < (s.sequence, s.created_at, s.id)
                 WHERE s.mode = 'BLOCK_ON_ERROR'
                   AND s.message_group IS NOT NULL
                   AND s.status IN ('QUEUED', 'PROCESSING')
                   AND (s.status <> 'PROCESSING' OR s.updated_at < ?::timestamptz)
            )""".formatted(DispatchJobRepository.HOLDING_STATUSES_SQL);

    // ── Operator actions (inside the unit of work's transaction) ───────────

    /// A [Persist] for the operator's resend: the job returns to `PENDING`
    /// with a full retry budget — `attempt_count = 0`, `last_error`,
    /// `scheduled_for`, `completed_at` and `duration_millis` cleared — from
    /// ANY status, in the unit of work's transaction beside its event and
    /// audit rows. Writes nothing but those columns and `updated_at`.
    public static Persist<DispatchJob> requeueWriter() {
        return new TxWriter("requeue") {
            @Override
            void write(DispatchJob j, Connection conn) {
                requeue(conn, j.id(), j.createdAt());
            }
        };
    }

    /// A [Persist] for the operator's ignore: `FAILED` (or legacy `ERROR`) →
    /// `CANCELLED`, stamping `completed_at`. A job that is not `FAILED` any
    /// more fails the write and rolls the unit of work back — the check the
    /// operation made in memory now holds in the SQL.
    public static Persist<DispatchJob> cancelWriter() {
        return new TxWriter("cancel") {
            @Override
            void write(DispatchJob j, Connection conn) {
                settleFailed(conn, Transition.CANCEL, j.id(), j.createdAt());
            }
        };
    }

    /// A [Persist] for the operator's "handled out of band": `FAILED` (or
    /// legacy `ERROR`) → `COMPLETED`; same shape as [#cancelWriter].
    public static Persist<DispatchJob> completeWriter() {
        return new TxWriter("complete") {
            @Override
            void write(DispatchJob j, Connection conn) {
                settleFailed(conn, Transition.OPERATOR_COMPLETE, j.id(), j.createdAt());
            }
        };
    }

    private abstract static class TxWriter implements Persist<DispatchJob> {
        private final String what;

        TxWriter(String what) {
            this.what = what;
        }

        abstract void write(DispatchJob j, Connection conn);

        @Override
        public void persist(DispatchJob j, DbTx tx) {
            write(j, tx.connection());
        }

        @Override
        public void delete(DispatchJob j, DbTx tx) {
            throw new UnsupportedOperationException("dispatch jobs are not deleted (" + what + " writer)");
        }
    }

    /// Operator requeue on the caller's transaction. Returns whether a row changed.
    static boolean requeue(Connection tx, String id, Instant createdAt) {
        return !enterPending(on(tx), Transition.REQUEUE, Selector.one(id, createdAt),
                Changes.of("attempt_count = 0, last_error = NULL, scheduled_for = NULL, completed_at = NULL, duration_millis = NULL"),
                Instant.now()).isEmpty();
    }

    /// `FAILED`/`ERROR` → `CANCELLED` or `COMPLETED` on the caller's
    /// transaction. Not a primitive call: neither side is `PENDING`.
    private static void settleFailed(Connection tx, Transition t, String id, Instant createdAt) {
        Instant now = Instant.now();
        String sql = "UPDATE msg_dispatch_jobs j SET status = '" + t.to() + "', completed_at = ?, updated_at = ?"
                + " WHERE j.id = ? AND j.created_at = ?" + t.guardSql() + " RETURNING " + RETURNING;
        try (PreparedStatement ps = tx.prepareStatement(sql)) {
            ps.setObject(1, utc(now));
            ps.setObject(2, utc(now));
            ps.setString(3, id);
            ps.setObject(4, utc(createdAt));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        } catch (SQLException e) {
            throw new DataAccessException("dispatch job " + t.name().toLowerCase(java.util.Locale.ROOT) + " failed", e);
        }
        refusedRows(tx, t, Selector.one(id, createdAt), 1);
        throw new IllegalStateException("dispatch job " + id + " is not FAILED; " + t.name() + " refused");
    }

    // ── Creation ───────────────────────────────────────────────────────────

    /// One batch insert of server-minted jobs, always `PENDING`:
    /// `ON CONFLICT (id, created_at) DO NOTHING` — the table is partitioned on
    /// `created_at`, so the conflict target names both halves of the primary
    /// key. That target does **not** stop a second row with an existing `id`
    /// and a later `created_at`: a caller-supplied id goes through
    /// [#insertNew] instead. One JDBC batch, one round trip; empty input is a no-op.
    public void insertBatch(List<DispatchJob> jobs) {
        if (jobs.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            insertJobs(conn, jobs);
        } catch (SQLException e) {
            throw new DataAccessException("dispatch job insert failed", e);
        }
    }

    /// Why [#insertNew] wrote nothing.
    public sealed interface InsertRefusal {
        /// These caller-supplied ids already name a job.
        record IdsTaken(List<String> ids) implements InsertRefusal {
            public IdsTaken {
                ids = List.copyOf(ids);
            }
        }
    }

    /// The advisory-lock class [#insertNew] serialises supplied ids under
    /// (`pg_advisory_xact_lock(int, int)`'s first key; the second is the
    /// id's `hashtext`).
    static final int SUPPLIED_ID_LOCK_CLASS = 0x646A6964; // "djid"

    /// `POST /api/dispatch-jobs/batch` with caller-supplied ids
    /// (`docs/spec/security-fixes-2026-09-24.md` S3.3): refuses the whole
    /// batch when any of `suppliedIds` already names a job, so an id can
    /// never name two rows — [DispatchJobRepository#findById] reads it with
    /// `fetchOptional`, and a second row would make the first job unreadable
    /// for ever. The primary key is `(id, created_at)` (the table is
    /// partitioned on `created_at`), so the database cannot enforce this
    /// itself; instead, in one transaction, each supplied id's advisory lock
    /// is taken (two concurrent requests supplying the same new id serialise
    /// here), the ids are looked up across every partition, and only then is
    /// the batch inserted. `suppliedIds` must already be free of duplicates
    /// within the batch (the caller refuses those itself).
    public Result<Integer, InsertRefusal> insertNew(List<DispatchJob> jobs, List<String> suppliedIds) {
        if (jobs.isEmpty()) return Result.ok(0);
        if (suppliedIds.isEmpty()) {
            insertBatch(jobs);
            return Result.ok(jobs.size());
        }
        String[] ids = suppliedIds.stream().distinct().sorted().toArray(String[]::new);
        return DSL.using(dataSource, SQLDialect.POSTGRES).transactionResult(cfg -> {
            DSLContext tx = DSL.using(cfg);
            // Locks in hash order: two batches sharing several ids take them in the same order.
            tx.fetch("SELECT pg_advisory_xact_lock(?, k) FROM (SELECT DISTINCT hashtext(x) AS k FROM unnest(?::text[]) AS x) s ORDER BY k",
                    SUPPLIED_ID_LOCK_CLASS, ids);
            List<String> taken = tx.selectDistinct(T.ID).from(T).where(T.ID.in(ids)).orderBy(T.ID).fetch(T.ID);
            if (!taken.isEmpty()) {
                return Result.<Integer, InsertRefusal>err(new InsertRefusal.IdsTaken(taken));
            }
            tx.connection(conn -> insertJobs(conn, jobs));
            return Result.<Integer, InsertRefusal>ok(jobs.size());
        });
    }

    /// One job fan-out raised for one (event, subscription) match.
    public record FanOutJob(String id, String code, String source, String subject, String eventId,
                            String correlationId, String clientId, String messageGroup, String payload,
                            String targetUrl, boolean dataOnly, String serviceAccountId, String subscriptionId,
                            String dispatchPoolId, int sequence, int timeoutSeconds, int maxRetries, String mode,
                            String idempotencyKey, String queue, String descriptor, String metadataJson,
                            Instant createdAt) {
    }

    /// Fan-out's multi-row insert, always `PENDING`, on the caller's open
    /// transaction (the claim of the events and these jobs commit together):
    /// one statement, `ON CONFLICT (id, created_at) DO NOTHING`, and a queue row
    /// for every job actually inserted (see the class doc). Empty input is a no-op.
    public static void insertFanOut(Connection tx, List<FanOutJob> jobs) {
        if (jobs.isEmpty()) return;
        var sql = new StringBuilder(256 + jobs.size() * 64);
        sql.append("WITH ins AS (INSERT INTO msg_dispatch_jobs (").append(FAN_OUT_COLUMNS).append(") VALUES ");
        for (int i = 0; i < jobs.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(FAN_OUT_VALUES);
        }
        sql.append(" ON CONFLICT (id, created_at) DO NOTHING RETURNING ").append(MOVED_COLUMNS).append(") ")
                .append(queueInsertFrom("ins")).append(" ON CONFLICT (job_id) DO NOTHING");
        try (PreparedStatement ps = tx.prepareStatement(sql.toString())) {
            int i = 1;
            for (FanOutJob j : jobs) {
                OffsetDateTime createdAt = utc(j.createdAt());
                Object[] values = {j.id(), j.code(), j.source(), j.subject(), j.eventId(), j.correlationId(),
                        j.clientId(), j.messageGroup(), j.payload(), j.targetUrl(), j.dataOnly(), j.serviceAccountId(),
                        j.subscriptionId(), j.dispatchPoolId(), j.sequence(), j.timeoutSeconds(), j.maxRetries(),
                        j.mode(), j.idempotencyKey(), j.queue(), j.descriptor(), j.metadataJson(), createdAt, createdAt};
                for (Object v : values) ps.setObject(i++, v);
            }
            ps.execute();
        } catch (SQLException e) {
            throw new DataAccessException("dispatch job fan-out insert failed", e);
        }
    }

    /// The fan-out insert's columns, in order; `protocol` and `status` are
    /// literals in [#FAN_OUT_VALUES]. Born `PENDING`, always.
    private static final String FAN_OUT_COLUMNS = "id, code, source, subject, event_id, correlation_id, client_id, "
            + "message_group, payload, target_url, data_only, service_account_id, subscription_id, dispatch_pool_id, "
            + "sequence, timeout_seconds, max_retries, mode, protocol, status, idempotency_key, queue, descriptor, "
            + "metadata, created_at, updated_at";

    /// One fan-out row: 24 bound values (`metadata` cast to `jsonb`) and the two literals.
    private static final String FAN_OUT_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "'HTTP_WEBHOOK', 'PENDING', ?, ?, ?, ?::jsonb, ?, ?)";

    /// The single-job insert's columns, in order; `status` is the literal `'PENDING'`.
    private static final String JOB_COLUMNS = "id, external_id, source, kind, code, subject, event_id, correlation_id, "
            + "metadata, target_url, protocol, payload, payload_content_type, data_only, service_account_id, client_id, "
            + "subscription_id, mode, dispatch_pool_id, message_group, sequence, timeout_seconds, schema_id, status, "
            + "max_retries, retry_strategy, scheduled_for, expires_at, attempt_count, last_attempt_at, completed_at, "
            + "duration_millis, last_error, idempotency_key, descriptor, queue, created_at, updated_at";

    /// 37 bound values and the `status` literal, in [#JOB_COLUMNS] order.
    private static final String JOB_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /// The create statement of one job: the insert and the queue row, one statement.
    private static final String INSERT_JOB_SQL = "WITH ins AS (INSERT INTO msg_dispatch_jobs (" + JOB_COLUMNS
            + ") VALUES " + JOB_VALUES + " ON CONFLICT (id, created_at) DO NOTHING RETURNING " + MOVED_COLUMNS + ") "
            + queueInsertFrom("ins") + " ON CONFLICT (job_id) DO NOTHING";

    /// One JDBC batch of [#INSERT_JOB_SQL], one round trip, each statement atomic
    /// (job and queue row together); on the caller's connection and transaction, if any.
    private static void insertJobs(Connection conn, List<DispatchJob> jobs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_JOB_SQL)) {
            for (DispatchJob j : jobs) {
                Object[] values = {j.id(), j.externalId(), j.source(), j.kind().name(), j.code(), j.subject(),
                        j.eventId(), j.correlationId(), Json.write(j.metadata()), j.targetUrl(), j.protocol().name(),
                        j.payload(), j.payloadContentType(), j.dataOnly(), j.serviceAccountId(), j.clientId(),
                        j.subscriptionId(), j.mode().name(), j.dispatchPoolId(), j.messageGroup(), j.sequence(),
                        j.timeoutSeconds(), j.schemaId(), j.maxRetries(), j.retryStrategy().wire(),
                        utc(j.scheduledFor()), utc(j.expiresAt()), j.attemptCount(), utc(j.lastAttemptAt()),
                        utc(j.completedAt()), j.durationMillis(), j.lastError(), j.idempotencyKey(), j.descriptor(),
                        j.queue(), utc(j.createdAt()), utc(j.updatedAt())};
                int i = 1;
                for (Object v : values) ps.setObject(i++, v);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── Queue drift (tests and diagnostics only) ───────────────────────────

    /// What [#queueDrift] counts.
    ///
    /// @param missingOrStale `PENDING` jobs with no queue row, or whose queue row's
    ///                       `version` / `scheduled_for` differs from the job's. A job
    ///                       the dispatcher can never see — must be zero.
    /// @param orphaned       queue rows whose job is not `PENDING` or does not exist.
    ///                       The documented race can leave a few; harmless, self-healing.
    public record QueueDrift(long missingOrStale, long orphaned) {
        public boolean isClean() {
            return missingOrStale == 0 && orphaned == 0;
        }
    }

    /// Compares the queue with the jobs, one query each. For tests and
    /// diagnostics: it scans, and is NOT called on any hot path.
    public QueueDrift queueDrift() {
        return queueDrift(null);
    }

    /// [#queueDrift()] restricted to these job ids (`null` = all).
    public QueueDrift queueDrift(java.util.Collection<String> jobIds) {
        return queueDrift(jobIds, List.of());
    }

    /// As [#queueDrift(java.util.Collection)], not counting `PENDING` jobs in `ignore` as missing: the
    /// claiming process's in-flight ids are `PENDING` with no queue row by design.
    public QueueDrift queueDrift(java.util.Collection<String> jobIds, java.util.Collection<String> ignore) {
        String scopeJ = jobIds == null ? "" : " AND j.id = ANY(?)";
        String scopeQ = jobIds == null ? "" : " AND q.job_id = ANY(?)";
        String missing = "SELECT count(*) FROM msg_dispatch_jobs j LEFT JOIN msg_dispatch_queue q ON q.job_id = j.id"
                + " WHERE j.status = 'PENDING' AND j.id <> ALL(?)" + scopeJ
                + " AND (q.job_id IS NULL OR q.version <> j.updated_at OR q.scheduled_for IS DISTINCT FROM j.scheduled_for)";
        String orphans = "SELECT count(*) FROM msg_dispatch_queue q WHERE NOT EXISTS ("
                + "SELECT 1 FROM msg_dispatch_jobs j WHERE j.id = q.job_id AND j.status = 'PENDING')" + scopeQ;
        try (Connection conn = dataSource.getConnection()) {
            return new QueueDrift(count(conn, missing, ignore, jobIds), count(conn, orphans, null, jobIds));
        } catch (SQLException e) {
            throw new DataAccessException("queue drift check failed", e);
        }
    }

    private static long count(Connection conn, String sql, java.util.Collection<String> ignore,
                              java.util.Collection<String> jobIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (ignore != null) ps.setArray(i++, conn.createArrayOf("text", ignore.toArray(String[]::new)));
            if (jobIds != null) ps.setArray(i, conn.createArrayOf("text", jobIds.toArray(String[]::new)));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
