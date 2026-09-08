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
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.SortField;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS_READ;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOB_ATTEMPTS;

/// `msg_dispatch_jobs` (detail reads + [Persist]), `msg_dispatch_jobs_read`
/// (list / by-event / facet reads) and `msg_dispatch_job_attempts` (history)
/// via jOOQ (spec §9). The status flips owned by the scheduler and the
/// processing endpoint are **not** here (spec §10). Pure CRUD — no domain
/// decisions live here.
/// `asText()`/`isTextual()` are deprecated in Jackson 3 for `stringValue()`/
/// `isString()`, which are NOT equivalent (throws on non-string, `null` not
/// `""` for JSON `null`) — kept deliberately, suppressed rather than migrated.
@SuppressWarnings("deprecation")
public final class DispatchJobRepository implements Persist<DispatchJob>, ProcessingRepository {

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

    public DispatchJobRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
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

    /// One `PENDING` row claimed by [#claimPending] — the dispatch-seam
    /// spec §3 claim query's own column list, carrying just what the
    /// scheduler poller needs (paused-subscription filter, the
    /// [#groupHeldBefore(String,int,Instant,String)] hold-back, and
    /// [io.flowcatalyst.platform.scheduler.PoolCodeResolver] resolution) —
    /// not the full [DispatchJob] entity, which the claim query never reads.
    /// `mode` is already parsed here (spec §2 "`dispatchMode` resolution"
    /// starts from the stored raw value); every other nullable component is
    /// `null` exactly when the column is `NULL`.
    public record ClaimRow(
            String id,
            String subscriptionId,
            String messageGroup,
            DispatchMode mode,
            String dispatchPoolId,
            String clientId,
            Instant createdAt,
            int sequence) {
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
        return dsl.selectFrom(A).where(A.DISPATCH_JOB_ID.eq(jobId)).orderBy(A.ATTEMPT_NUMBER.asc())
                .fetch(DispatchJobRepository::toAttempt);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id, created_at)` — the partition key is
    /// half the primary key, so the statement prunes to one partition. `id`
    /// and `created_at` are insert-only; `updated_at` is stamped `now()` here
    /// (which marks the row dirty for the projector); `queued_at` /
    /// `projected_at` are not listed and therefore untouched (spec §9).
    @Override
    public void persist(DispatchJob j, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.EXTERNAL_ID, j.externalId());
        row.put(T.SOURCE, j.source());
        row.put(T.KIND, j.kind().name());
        row.put(T.CODE, j.code());
        row.put(T.SUBJECT, j.subject());
        row.put(T.EVENT_ID, j.eventId());
        row.put(T.CORRELATION_ID, j.correlationId());
        row.put(T.METADATA, toJsonb(j.metadata()));
        row.put(T.TARGET_URL, j.targetUrl());
        row.put(T.PROTOCOL, j.protocol().name());
        row.put(T.PAYLOAD, j.payload());
        row.put(T.PAYLOAD_CONTENT_TYPE, j.payloadContentType());
        row.put(T.DATA_ONLY, j.dataOnly());
        row.put(T.SERVICE_ACCOUNT_ID, j.serviceAccountId());
        row.put(T.CLIENT_ID, j.clientId());
        row.put(T.SUBSCRIPTION_ID, j.subscriptionId());
        row.put(T.MODE, j.mode().name());
        row.put(T.DISPATCH_POOL_ID, j.dispatchPoolId());
        row.put(T.MESSAGE_GROUP, j.messageGroup());
        row.put(T.SEQUENCE, j.sequence());
        row.put(T.TIMEOUT_SECONDS, j.timeoutSeconds());
        row.put(T.SCHEMA_ID, j.schemaId());
        row.put(T.STATUS, j.status().name());
        row.put(T.MAX_RETRIES, j.maxRetries());
        row.put(T.RETRY_STRATEGY, j.retryStrategy().wire());
        row.put(T.SCHEDULED_FOR, utc(j.scheduledFor()));
        row.put(T.EXPIRES_AT, utc(j.expiresAt()));
        row.put(T.ATTEMPT_COUNT, j.attemptCount());
        row.put(T.LAST_ATTEMPT_AT, utc(j.lastAttemptAt()));
        row.put(T.COMPLETED_AT, utc(j.completedAt()));
        row.put(T.DURATION_MILLIS, j.durationMillis());
        row.put(T.LAST_ERROR, j.lastError());
        row.put(T.IDEMPOTENCY_KEY, j.idempotencyKey());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, j.id())
                .set(T.CREATED_AT, utc(j.createdAt()))
                .set(row)
                .onConflict(T.ID, T.CREATED_AT).doUpdate().set(row)
                .execute();
    }

    /// Removes the row by its full primary key (partition-pruned). No
    /// operation deletes jobs today (spec §9).
    @Override
    public void delete(DispatchJob j, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES)
                .deleteFrom(T).where(T.ID.eq(j.id())).and(T.CREATED_AT.eq(utc(j.createdAt())))
                .execute();
    }

    // ── Writes (infra ingest — no unit of work, sdk-ingest spec §1/§4) ──────

    /// One batch insert for `POST /api/dispatch-jobs`(`/batch`):
    /// `ON CONFLICT (id, created_at) DO NOTHING` (spec §4.1) — the table is
    /// partitioned on `created_at`, so the conflict target names both halves
    /// of the primary key. A repeated SDK-supplied id silently drops that
    /// row without aborting the rest. One JDBC batch, one round trip; empty
    /// input is a no-op.
    public void insertBatch(List<DispatchJob> jobs) {
        if (jobs.isEmpty()) return;
        var queries = jobs.stream().map(this::insertQuery).toList();
        dsl.batch(queries).execute();
    }

    private org.jooq.Insert<MsgDispatchJobsRecord> insertQuery(DispatchJob j) {
        return dsl.insertInto(T)
                .set(T.ID, j.id())
                .set(T.EXTERNAL_ID, j.externalId())
                .set(T.SOURCE, j.source())
                .set(T.KIND, j.kind().name())
                .set(T.CODE, j.code())
                .set(T.SUBJECT, j.subject())
                .set(T.EVENT_ID, j.eventId())
                .set(T.CORRELATION_ID, j.correlationId())
                .set(T.METADATA, toJsonb(j.metadata()))
                .set(T.TARGET_URL, j.targetUrl())
                .set(T.PROTOCOL, j.protocol().name())
                .set(T.PAYLOAD, j.payload())
                .set(T.PAYLOAD_CONTENT_TYPE, j.payloadContentType())
                .set(T.DATA_ONLY, j.dataOnly())
                .set(T.SERVICE_ACCOUNT_ID, j.serviceAccountId())
                .set(T.CLIENT_ID, j.clientId())
                .set(T.SUBSCRIPTION_ID, j.subscriptionId())
                .set(T.MODE, j.mode().name())
                .set(T.DISPATCH_POOL_ID, j.dispatchPoolId())
                .set(T.MESSAGE_GROUP, j.messageGroup())
                .set(T.SEQUENCE, j.sequence())
                .set(T.TIMEOUT_SECONDS, j.timeoutSeconds())
                .set(T.SCHEMA_ID, j.schemaId())
                .set(T.STATUS, j.status().name())
                .set(T.MAX_RETRIES, j.maxRetries())
                .set(T.RETRY_STRATEGY, j.retryStrategy().wire())
                .set(T.SCHEDULED_FOR, utc(j.scheduledFor()))
                .set(T.EXPIRES_AT, utc(j.expiresAt()))
                .set(T.ATTEMPT_COUNT, j.attemptCount())
                .set(T.LAST_ATTEMPT_AT, utc(j.lastAttemptAt()))
                .set(T.COMPLETED_AT, utc(j.completedAt()))
                .set(T.DURATION_MILLIS, j.durationMillis())
                .set(T.LAST_ERROR, j.lastError())
                .set(T.IDEMPOTENCY_KEY, j.idempotencyKey())
                .set(T.CREATED_AT, utc(j.createdAt()))
                .set(T.UPDATED_AT, utc(j.updatedAt()))
                .onConflict(T.ID, T.CREATED_AT).doNothing();
    }

    // ── Infra writes (direct SQL, outside the use-case envelope) ───────────
    //
    // The scheduler / processing-endpoint / settled-endpoint / reaper writes
    // (dispatch-seam spec §4–7, §9): router-driven or backstop mutations, not
    // human-initiated commands, so — like Go's Repository — they bypass
    // Operation/Plan/UnitOfWork and write straight through `dsl` (auto-commit,
    // one statement). Every flip carries `created_at` alongside `id` so the
    // statement prunes to one partition, exactly as the Go queries do.

    /// The terminal-failure statuses a `BLOCK_ON_ERROR` head holds its group
    /// behind on — `FAILED`, plus the legacy `ERROR` alias
    /// ([DispatchJobStatus] §1.1) — shared verbatim between [#groupHolding]
    /// (the claim-time / delivery-time gate) and [#SWEEP_STRANDED_SIBLINGS_SQL]
    /// (the reaper's backstop sweep, spec §7) so the two predicates cannot
    /// silently drift apart: a sweep that recognised only `FAILED` would
    /// permanently strand siblings behind a legacy `ERROR` head, since that
    /// head still blocks at claim time.
    private static final String[] HOLDING_STATUSES = {"FAILED", "ERROR"};

    /// `GroupHolding` (spec §9): the ONE status predicate shared by every
    /// enforcement point that decides whether a row holds the rest of its
    /// group behind it — `FAILED`/legacy `ERROR` ([#HOLDING_STATUSES]), OR
    /// `PENDING` with a **future** `scheduled_for` (mid-retry-backoff;
    /// excluded from the ordinary claim query by that same future timestamp,
    /// so a check that only looked at terminal statuses would miss it).
    /// Deliberately excludes `QUEUED`/`PROCESSING` — the ordinary in-flight flow.
    private static Condition groupHolding(Field<String> status, Field<OffsetDateTime> scheduledFor) {
        return status.in(HOLDING_STATUSES)
                .or(status.eq("PENDING").and(scheduledFor.isNotNull()).and(scheduledFor.gt(DSL.currentOffsetDateTime())));
    }

    /// The delivery-time hold-back gate (spec §5, §9: Go `GroupHeldBefore`):
    /// is `job` positioned behind a [#groupHolding] row in the same
    /// `message_group`? The comparison is **positional**, over the same
    /// `(sequence, created_at, id)` triple the claim query orders by — never
    /// set membership, which would include the holder itself the instant its
    /// own backoff expired and the group would never move again. A job with
    /// no `message_group` cannot be held (`false`).
    public boolean groupHeldBefore(DispatchJob job) {
        if (job.messageGroup() == null) return false;
        return dsl.fetchExists(dsl.selectOne().from(T)
                .where(T.MESSAGE_GROUP.eq(job.messageGroup()))
                .and(groupHolding(T.STATUS, T.SCHEDULED_FOR))
                .and(DSL.row(T.SEQUENCE, T.CREATED_AT, T.ID)
                        .lt(DSL.row(job.sequence(), utc(job.createdAt()), job.id()))));
    }

    /// Claim-time equivalent of [#groupHeldBefore(DispatchJob)] (spec §3
    /// `filterByDispatchMode`/§9): the SAME [#groupHolding] predicate and the
    /// SAME positional `(sequence, created_at, id)` comparison, called
    /// against a [ClaimRow]'s own position rather than a hydrated
    /// [DispatchJob] — the poller has only the claim query's columns at this
    /// point, and hydrating a full entity just to reuse the other overload
    /// would be a second query for no new information. `messageGroup == null`
    /// can never be held, mirroring the other overload exactly (a `NULL`
    /// group is excluded from the claim-time check the same way it is
    /// excluded from delivery-time).
    public boolean groupHeldBefore(String messageGroup, int sequence, Instant createdAt, String id) {
        if (messageGroup == null) return false;
        return dsl.fetchExists(dsl.selectOne().from(T)
                .where(T.MESSAGE_GROUP.eq(messageGroup))
                .and(groupHolding(T.STATUS, T.SCHEDULED_FOR))
                .and(DSL.row(T.SEQUENCE, T.CREATED_AT, T.ID)
                        .lt(DSL.row(sequence, utc(createdAt), id))));
    }

    /// The scheduler's claim query (spec §3, step 2): `SELECT ... FOR UPDATE
    /// SKIP LOCKED`, ordered `message_group ASC NULLS LAST, sequence,
    /// created_at, id` so the order is TOTAL — the positional hold-back
    /// above depends on it. `tx` MUST be the caller's own open transaction
    /// (not auto-commit): the claim's row locks are only useful held across
    /// the mark-QUEUED [#markQueued] that follows, in the same transaction,
    /// released together at commit.
    public List<ClaimRow> claimPending(DbTx tx, int batchSize) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        return txDsl.select(T.ID, T.SUBSCRIPTION_ID, T.MESSAGE_GROUP, T.MODE, T.DISPATCH_POOL_ID, T.CLIENT_ID,
                        T.CREATED_AT, T.SEQUENCE)
                .from(T)
                .where(T.STATUS.eq(DispatchJobStatus.PENDING.name()))
                .and(T.SCHEDULED_FOR.isNull().or(T.SCHEDULED_FOR.le(DSL.currentOffsetDateTime())))
                .orderBy(T.MESSAGE_GROUP.asc().nullsLast(), T.SEQUENCE.asc(), T.CREATED_AT.asc(), T.ID.asc())
                .limit(batchSize)
                .forUpdate()
                .skipLocked()
                .fetch(r -> new ClaimRow(
                        r.get(T.ID),
                        r.get(T.SUBSCRIPTION_ID),
                        r.get(T.MESSAGE_GROUP),
                        DispatchMode.parse(r.get(T.MODE)),
                        r.get(T.DISPATCH_POOL_ID),
                        r.get(T.CLIENT_ID),
                        r.get(T.CREATED_AT).toInstant(),
                        r.get(T.SEQUENCE) == null ? 0 : r.get(T.SEQUENCE)));
    }

    /// Marks the survivors of one poll tick `QUEUED` (spec §3, step 4), in
    /// the SAME transaction as [#claimPending] — no status guard needed, the
    /// rows are already locked and known `PENDING`. Bounded by the batch's
    /// own `created_at` span so the `created_at`-partitioned table prunes to
    /// the partitions the claimed rows actually span, exactly as
    /// [#claimPending]'s lock did.
    public void markQueued(DbTx tx, List<String> ids, Instant spanStart, Instant spanEnd) {
        if (ids.isEmpty()) return;
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.update(T)
                .set(T.STATUS, DispatchJobStatus.QUEUED.name())
                .set(T.UPDATED_AT, utc(Instant.now()))
                .where(T.ID.in(ids))
                .and(T.CREATED_AT.ge(utc(spanStart)))
                .and(T.CREATED_AT.le(utc(spanEnd)))
                .execute();
    }

    /// Publish failure recovery (spec §3, step 6): reverts a claimed batch
    /// `QUEUED` → `PENDING` in one statement, guarded `status = 'QUEUED'` so
    /// a row the processing endpoint already advanced past `QUEUED` (or that
    /// a concurrent stale-recovery/settled/reaper sweep already reset) is
    /// left untouched. Runs AFTER the claim transaction has committed (spec
    /// §3, step 5) — auto-commit, like every other infra write in this file.
    /// Returns the ids actually reverted.
    public List<String> revertQueuedToPending(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return dsl.update(T)
                .set(T.STATUS, DispatchJobStatus.PENDING.name())
                .set(T.UPDATED_AT, utc(Instant.now()))
                .where(T.ID.in(ids))
                .and(T.STATUS.eq(DispatchJobStatus.QUEUED.name()))
                .returning(T.ID)
                .fetch(T.ID);
    }

    /// [io.flowcatalyst.platform.scheduler.StaleQueuedJobPoller]'s sweep
    /// (spec §3 timing table `StaleAfter`, §4): every row stuck `QUEUED`
    /// with `updated_at` older than `olderThan` reverts to `PENDING` —
    /// recovers a crash between mark-QUEUED and a successful publish, or a
    /// broker drop. Returns the ids reverted.
    public List<String> reclaimStaleQueued(Instant olderThan) {
        return dsl.update(T)
                .set(T.STATUS, DispatchJobStatus.PENDING.name())
                .set(T.UPDATED_AT, utc(Instant.now()))
                .where(T.STATUS.eq(DispatchJobStatus.QUEUED.name()))
                .and(T.UPDATED_AT.lt(utc(olderThan)))
                .returning(T.ID)
                .fetch(T.ID);
    }

    /// Atomically claims a job for one delivery (dispatch-seam spec §5): the
    /// same `PROCESSING` flip [#markInProgress] used to do, but guarded on the
    /// status it is flipping FROM, so the row count answers "did I win this
    /// delivery?". Only `PENDING`/`QUEUED` is claimable — a row already
    /// `PROCESSING` belongs to a delivery still in flight, and a terminal row
    /// is finished — so a concurrent redelivery of the same job updates no row
    /// and its caller must not call the subscriber. A positive status list
    /// rather than an exclusion list: an unrecognised stored value is then
    /// un-claimable rather than deliverable.
    ///
    /// @return `true` when this call won the claim (exactly one row updated)
    public boolean claimForDelivery(String id, Instant createdAt) {
        Instant now = Instant.now();
        return dsl.update(T)
                .set(T.STATUS, DispatchJobStatus.PROCESSING.name())
                .set(T.LAST_ATTEMPT_AT, utc(now))
                .set(T.UPDATED_AT, utc(now))
                .where(T.ID.eq(id))
                .and(T.CREATED_AT.eq(utc(createdAt)))
                .and(T.STATUS.in(DispatchJobStatus.PENDING.name(), DispatchJobStatus.QUEUED.name()))
                .execute() == 1;
    }

    /// Delivery succeeded (spec §4): `PROCESSING` → `COMPLETED`, stamps
    /// `completed_at`/`duration_millis`.
    public void markCompleted(String id, Instant createdAt, Instant completedAt, Long durationMillis) {
        dsl.update(T)
                .set(T.STATUS, DispatchJobStatus.COMPLETED.name())
                .set(T.COMPLETED_AT, utc(completedAt))
                .set(T.DURATION_MILLIS, durationMillis)
                .set(T.UPDATED_AT, utc(Instant.now()))
                .where(T.ID.eq(id)).and(T.CREATED_AT.eq(utc(createdAt)))
                .execute();
    }

    /// Retries exhausted (spec §4): `PROCESSING` → `FAILED`, records `lastError`.
    public void markFailed(String id, Instant createdAt, String lastError) {
        dsl.update(T)
                .set(T.STATUS, DispatchJobStatus.FAILED.name())
                .set(T.LAST_ERROR, lastError)
                .set(T.UPDATED_AT, utc(Instant.now()))
                .where(T.ID.eq(id)).and(T.CREATED_AT.eq(utc(createdAt)))
                .execute();
    }

    /// Retryable failure, budget remains (spec §4): `PROCESSING` → `PENDING`,
    /// bumps `attempt_count` and records `lastError` — unlike [#reschedule],
    /// this DOES spend retry budget.
    public void scheduleRetry(String id, Instant createdAt, Instant scheduledFor, int attemptCount, String lastError) {
        dsl.update(T)
                .set(T.STATUS, DispatchJobStatus.PENDING.name())
                .set(T.SCHEDULED_FOR, utc(scheduledFor))
                .set(T.ATTEMPT_COUNT, attemptCount)
                .set(T.LAST_ERROR, lastError)
                .set(T.UPDATED_AT, utc(Instant.now()))
                .where(T.ID.eq(id)).and(T.CREATED_AT.eq(utc(createdAt)))
                .execute();
    }

    /// Cooperative deferral, or the delivery-time hold-back revert (spec §4,
    /// §5): `PROCESSING`/`QUEUED` → `PENDING` at `scheduledFor`. **No**
    /// `attempt_count` bump — back-pressure/hold-back, not a failure.
    public void reschedule(String id, Instant createdAt, Instant scheduledFor) {
        dsl.update(T)
                .set(T.STATUS, DispatchJobStatus.PENDING.name())
                .set(T.SCHEDULED_FOR, utc(scheduledFor))
                .set(T.UPDATED_AT, utc(Instant.now()))
                .where(T.ID.eq(id)).and(T.CREATED_AT.eq(utc(createdAt)))
                .execute();
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
                               Instant attemptedAt, Instant completedAt, Long durationMillis) {
        dsl.insertInto(A)
                .set(A.ID, Tsid.generate())
                .set(A.DISPATCH_JOB_ID, jobId)
                .set(A.ATTEMPT_NUMBER, attemptNumber)
                .set(A.STATUS, success ? "SUCCESS" : "FAILURE")
                .set(A.RESPONSE_CODE, responseCode)
                .set(A.RESPONSE_BODY, responseBody)
                .set(A.ERROR_MESSAGE, errorMessage)
                .set(A.ERROR_TYPE, errorType == null ? null : errorType.name())
                .set(A.DURATION_MILLIS, durationMillis)
                .set(A.ATTEMPTED_AT, utc(attemptedAt))
                .set(A.COMPLETED_AT, utc(completedAt))
                .set(A.CREATED_AT, utc(attemptedAt))
                .execute();
    }

    /// The settled endpoint's idempotent batch reset (spec §6, Go
    /// `SettleAcked`): every id in `QUEUED`/`PROCESSING` flips to `PENDING`
    /// with `scheduled_for` cleared and `last_error = reason`; a row already
    /// advanced past those two statuses (already settled, or the reaper beat
    /// this call to it) is left untouched — that `status IN (...)` guard is
    /// the whole idempotency contract, shared verbatim with
    /// [#sweepStrandedSiblings]. Returns the ids actually changed.
    public List<String> settleAcked(List<String> ids, String reason) {
        if (ids.isEmpty()) return List.of();
        return dsl.update(T)
                .set(T.STATUS, DispatchJobStatus.PENDING.name())
                .set(T.SCHEDULED_FOR, (OffsetDateTime) null)
                .set(T.LAST_ERROR, reason)
                .where(T.ID.in(ids))
                .and(T.STATUS.in("QUEUED", "PROCESSING"))
                .returning(T.ID)
                .fetch(T.ID);
    }

    /// The reaper's backstop sweep (spec §7, Go
    /// `DispatchJobSweepStrandedSiblings`): every `BLOCK_ON_ERROR` row in
    /// `QUEUED`/`PROCESSING` whose `message_group` has an earlier
    /// `FAILED`/legacy-`ERROR` head — positional over `(sequence, created_at,
    /// id)`, so a sibling positioned BEFORE the head is never touched — is
    /// reset to `PENDING`. A `QUEUED` sibling is reset regardless of age; a
    /// `PROCESSING` sibling only once `updated_at` is older than
    /// `processingLiveBefore` (a fresh `PROCESSING` row is presumed a
    /// genuine in-flight delivery). `NEXT_ON_ERROR`/`IMMEDIATE` rows are
    /// never matched. Idempotent — a row already reset no longer matches
    /// `status IN ('QUEUED','PROCESSING')`. Returns the ids reset.
    ///
    /// Note the join checks `h.status = ANY(?)`, bound to [#HOLDING_STATUSES]
    /// — the same array [#groupHolding] builds its `IN` predicate from — not
    /// a second, independently-typed `'FAILED', 'ERROR'` literal; the two
    /// gates share one Java constant so they cannot drift (spec §7, §9). Not
    /// the full [#groupHolding] predicate — a head mid-backoff (`PENDING` +
    /// future `scheduled_for`) self-resolves once that timer fires and needs
    /// no reaper.
    public List<String> sweepStrandedSiblings(Instant processingLiveBefore, String reason) {
        return dsl.fetch(SWEEP_STRANDED_SIBLINGS_SQL, HOLDING_STATUSES, utc(processingLiveBefore), reason)
                .getValues(T.ID.getName(), String.class);
    }

    private static final String SWEEP_STRANDED_SIBLINGS_SQL = """
            WITH stranded AS (
                SELECT s.id, s.created_at
                  FROM msg_dispatch_jobs s
                  JOIN msg_dispatch_jobs h
                    ON h.message_group = s.message_group
                   AND h.status = ANY(?)
                   AND (h.sequence, h.created_at, h.id) < (s.sequence, s.created_at, s.id)
                 WHERE s.mode = 'BLOCK_ON_ERROR'
                   AND s.message_group IS NOT NULL
                   AND s.status IN ('QUEUED', 'PROCESSING')
                   AND (s.status <> 'PROCESSING' OR s.updated_at < ?::timestamptz)
            )
            UPDATE msg_dispatch_jobs j
               SET status = 'PENDING', scheduled_for = NULL, last_error = ?::text, updated_at = now()
              FROM stranded st
             WHERE j.id = st.id AND j.created_at = st.created_at
            RETURNING j.id
            """;

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
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /// `success` is derived from the `status` column; a `NULL` `error_type`
    /// is "none", not `UNKNOWN` (spec §1.2).
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
                row.getErrorType() == null ? null : attemptErrorType(row.getDispatchJobId(), row.getErrorType()));
    }

    /// The metadata list as the SDK's JSON array of `{key, value}` pairs; `[]` when empty.
    private static JSONB toJsonb(List<DispatchJob.Metadata> metadata) {
        return JSONB.jsonb(Json.write(metadata));
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
