package io.flowcatalyst.platform.dispatchjob;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.MsgDispatchJobAttempts;
import io.flowcatalyst.db.generated.tables.MsgDispatchJobs;
import io.flowcatalyst.db.generated.tables.MsgDispatchJobsRead;
import io.flowcatalyst.db.generated.tables.records.MsgDispatchJobAttemptsRecord;
import io.flowcatalyst.db.generated.tables.records.MsgDispatchJobsReadRecord;
import io.flowcatalyst.db.generated.tables.records.MsgDispatchJobsRecord;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.database.VisibilitySql;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.subscription.DispatchMode;
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
public final class DispatchJobRepository implements Persist<DispatchJob> {

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
                DispatchJobKind.parse(row.getKind()),
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
                RetryStrategy.parse(row.getRetryStrategy()),
                DispatchJobStatus.parse(row.getStatus()),
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
                DispatchJobKind.parse(row.getKind()),
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
                DispatchJobStatus.parse(row.getStatus()),
                row.getMaxRetries(),
                RetryStrategy.parse(row.getRetryStrategy()),
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
                row.getErrorType() == null ? null : AttemptErrorType.parse(row.getErrorType()));
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

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
