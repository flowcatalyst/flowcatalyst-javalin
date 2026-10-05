package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.Map;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_QUEUE;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS_READ;
import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOB_ATTEMPTS;

/// Seeds dispatch-job rows directly — there is no writer in this unit yet
/// (ingest, the scheduler and the processing endpoint are later units), so
/// the fixture inserts the **documented row shapes** (spec §9): a write row,
/// its read projection, and attempt rows. `created_at` is `now()`-based so
/// every row lands in an existing monthly partition. Each fixture owns a
/// per-JVM namespace (the `code` carries it) so tests never see one
/// another's rows.
public final class DispatchJobFixture {

    public static final DataSource DS = TestPg.dataSource();
    public static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);

    /// Per-JVM namespace.
    public static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private DispatchJobFixture() {
    }

    /// `<tag><RUN>:orders:order:created` — a unique code per test family.
    public static String code(String tag) {
        return tag + RUN + ":orders:order:created";
    }

    /// What to seed; every optional column `null` unless given.
    public record Seed(
            String id,
            String code,
            String clientId,
            String status,
            Instant createdAt,
            String eventId,
            String subscriptionId,
            String dispatchPoolId,
            String messageGroup,
            int attemptCount,
            Instant scheduledFor,
            Instant completedAt,
            Long durationMillis,
            String lastError,
            String payload,
            String metadataJson,
            String source,
            String mode,
            int sequence,
            Instant updatedAt,
            String kind,
            String retryStrategy,
            String descriptor) {

        /// A `PENDING`, platform-scoped, `IMMEDIATE`-mode, `EVENT`-kind,
        /// `exponential`-retry seed with nothing but the essentials.
        public static Seed of(String code) {
            Instant now = Instant.now();
            return new Seed(Tsid.generate(), code, null, "PENDING", now, null, null, null, null,
                    0, null, null, null, null, null, null, null, "IMMEDIATE", 99, now, "EVENT", "exponential", null);
        }

        public Seed withClientId(String v) {
            return new Seed(id, code, v, status, createdAt, eventId, subscriptionId, dispatchPoolId, messageGroup,
                    attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload, metadataJson, source,
                    mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withScheduledFor(Instant v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId, messageGroup,
                    attemptCount, v, completedAt, durationMillis, lastError, payload, metadataJson, source,
                    mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withStatus(String v) {
            return new Seed(id, code, clientId, v, createdAt, eventId, subscriptionId, dispatchPoolId, messageGroup,
                    attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload, metadataJson, source,
                    mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withCreatedAt(Instant v) {
            return new Seed(id, code, clientId, status, v, eventId, subscriptionId, dispatchPoolId, messageGroup,
                    attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload, metadataJson, source,
                    mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withEventId(String v) {
            return new Seed(id, code, clientId, status, createdAt, v, subscriptionId, dispatchPoolId, messageGroup,
                    attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload, metadataJson, source,
                    mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withSubscriptionId(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, v, dispatchPoolId, messageGroup,
                    attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload, metadataJson, source,
                    mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withDispatchPoolId(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, v, messageGroup,
                    attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload, metadataJson, source,
                    mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withMessageGroup(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId, v,
                    attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload, metadataJson, source,
                    mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        /// The terminal-failure stamps a processing endpoint would have left.
        public Seed failed(int attempts, String error) {
            return new Seed(id, code, clientId, "FAILED", createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attempts, createdAt.plusSeconds(5), createdAt.plusSeconds(10), 777L, error,
                    payload, metadataJson, source, mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withPayload(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, v, metadataJson,
                    source, mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        /// Raw JSON text for the `metadata` column (`null` → SQL `NULL`).
        public Seed withMetadataJson(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload, v,
                    source, mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        public Seed withSource(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload,
                    metadataJson, v, mode, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        /// `IMMEDIATE` / `NEXT_ON_ERROR` / `BLOCK_ON_ERROR` (spec §9); write-row only — the
        /// projection row's `mode` stays the hardcoded `IMMEDIATE` [#seedProjection] always wrote.
        public Seed withMode(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload,
                    metadataJson, source, v, sequence, updatedAt, kind, retryStrategy, descriptor);
        }

        /// Position within the message group — the `GroupHolding`/reaper positional ordering key.
        public Seed withSequence(int v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload,
                    metadataJson, source, mode, v, updatedAt, kind, retryStrategy, descriptor);
        }

        /// The write row's `updated_at` — independent of `createdAt`, so a
        /// reaper test can seed a `PROCESSING` row that looks stale (or fresh).
        public Seed withUpdatedAt(Instant v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload,
                    metadataJson, source, mode, sequence, v, kind, retryStrategy, descriptor);
        }

        /// `EVENT` / `TASK` (spec §1.1, X-06) — written verbatim to both
        /// the write row and the projection row, so a corruption test can
        /// force any raw string past the entity/Java layer.
        public Seed withKind(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload,
                    metadataJson, source, mode, sequence, updatedAt, v, retryStrategy, descriptor);
        }

        /// `immediate` / `fixed` / `exponential` (X-06) — written verbatim to
        /// both the write row and the projection row.
        public Seed withRetryStrategy(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload,
                    metadataJson, source, mode, sequence, updatedAt, kind, v, descriptor);
        }

        /// `descriptor` — written to both the write row and the projection
        /// row (catch-up-2026-09-22.md C1).
        public Seed withDescriptor(String v) {
            return new Seed(id, code, clientId, status, createdAt, eventId, subscriptionId, dispatchPoolId,
                    messageGroup, attemptCount, scheduledFor, completedAt, durationMillis, lastError, payload,
                    metadataJson, source, mode, sequence, updatedAt, kind, retryStrategy, v);
        }
    }

    /// Inserts the write row **and** its projection row (as the projector
    /// would have), returns the id.
    public static String seed(Seed s) {
        seedWriteRow(s);
        seedProjection(s);
        return s.id();
    }

    /// The write-table row (`msg_dispatch_jobs`): `id`, `code`,
    /// `target_url`, `created_at`, `updated_at` are the mandatory columns; the
    /// rest default or are nullable (spec §9). When the row is `PENDING` its queue
    /// row is written too, unclaimed ([#syncQueue]) — the queue is exact whatever
    /// a test seeds, so the scheduler can claim what a test seeded.
    public static String seedWriteRow(Seed s) {
        insertWriteRow(s);
        syncQueue(java.util.List.of(s.id()));
        return s.id();
    }

    /// Makes `msg_dispatch_queue` exact for these jobs after a test changed them
    /// directly (test-only: production code writes the queue only in the lifecycle):
    /// a job that is not `PENDING` (or no longer exists) has no queue row, and a
    /// `PENDING` job has one mirroring it — `version = updated_at` . Use
    /// after any direct insert or update of `msg_dispatch_jobs`.
    public static void syncQueue(java.util.Collection<String> jobIds) {
        if (jobIds.isEmpty()) return;
        String[] ids = jobIds.toArray(String[]::new);
        DB.execute("DELETE FROM msg_dispatch_queue q WHERE q.job_id = ANY(?::text[]) AND NOT EXISTS ("
                + "SELECT 1 FROM msg_dispatch_jobs j WHERE j.id = q.job_id AND j.created_at = q.job_created_at"
                + " AND j.status = 'PENDING')", (Object) ids);
        DB.execute("INSERT INTO msg_dispatch_queue AS q (job_id, job_created_at, message_group, sequence, scheduled_for,"
                + " subscription_id, dispatch_pool_id, client_id, mode, queue, version)"
                + " SELECT id, created_at, message_group, sequence, scheduled_for, subscription_id, dispatch_pool_id,"
                + " client_id, mode, queue, updated_at FROM msg_dispatch_jobs WHERE id = ANY(?::text[]) AND status = 'PENDING'"
                + " ON CONFLICT (job_id) DO UPDATE SET job_created_at = EXCLUDED.job_created_at,"
                + " message_group = EXCLUDED.message_group, sequence = EXCLUDED.sequence,"
                + " scheduled_for = EXCLUDED.scheduled_for, subscription_id = EXCLUDED.subscription_id,"
                + " dispatch_pool_id = EXCLUDED.dispatch_pool_id, client_id = EXCLUDED.client_id, mode = EXCLUDED.mode,"
                + " queue = EXCLUDED.queue, version = EXCLUDED.version", (Object) ids);
    }

    /// Sets a job's status directly (test-only, behind the lifecycle's back) and keeps the
    /// queue exact; `updated_at` is bumped, as every lifecycle transition does.
    public static void setStatus(String jobId, String status) {
        DB.execute("UPDATE msg_dispatch_jobs SET status = ?, updated_at = now() WHERE id = ?", status, jobId);
        syncQueue(java.util.List.of(jobId));
    }

    /// The write row ALONE, with no queue row, whatever its status: the table as an older
    /// binary (or the migration's backfill test) leaves it.
    public static String seedWriteRowOnly(Seed s) {
        return insertWriteRow(s);
    }

    /// Sets every job currently in one of `fromStatuses` to `status` directly (test-only) and
    /// keeps the queue exact; a test's cleanup between cases.
    public static void setStatusWhere(String status, String... fromStatuses) {
        var ids = DB.fetch("UPDATE msg_dispatch_jobs SET status = ?, updated_at = now() WHERE status = ANY(?::text[]) RETURNING id",
                status, (Object) fromStatuses).getValues(0, String.class);
        syncQueue(ids);
    }

    private static String insertWriteRow(Seed s) {
        DB.insertInto(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.ID, s.id())
                .set(MSG_DISPATCH_JOBS.CODE, s.code())
                .set(MSG_DISPATCH_JOBS.SOURCE, s.source())
                .set(MSG_DISPATCH_JOBS.TARGET_URL, "https://hook.example/" + s.id())
                .set(MSG_DISPATCH_JOBS.CLIENT_ID, s.clientId())
                .set(MSG_DISPATCH_JOBS.EVENT_ID, s.eventId())
                .set(MSG_DISPATCH_JOBS.SUBSCRIPTION_ID, s.subscriptionId())
                .set(MSG_DISPATCH_JOBS.DISPATCH_POOL_ID, s.dispatchPoolId())
                .set(MSG_DISPATCH_JOBS.MESSAGE_GROUP, s.messageGroup())
                .set(MSG_DISPATCH_JOBS.MODE, s.mode())
                .set(MSG_DISPATCH_JOBS.SEQUENCE, s.sequence())
                .set(MSG_DISPATCH_JOBS.STATUS, s.status())
                .set(MSG_DISPATCH_JOBS.KIND, s.kind())
                .set(MSG_DISPATCH_JOBS.RETRY_STRATEGY, s.retryStrategy())
                .set(MSG_DISPATCH_JOBS.ATTEMPT_COUNT, s.attemptCount())
                .set(MSG_DISPATCH_JOBS.SCHEDULED_FOR, utc(s.scheduledFor()))
                .set(MSG_DISPATCH_JOBS.COMPLETED_AT, utc(s.completedAt()))
                .set(MSG_DISPATCH_JOBS.DURATION_MILLIS, s.durationMillis())
                .set(MSG_DISPATCH_JOBS.LAST_ERROR, s.lastError())
                .set(MSG_DISPATCH_JOBS.PAYLOAD, s.payload())
                .set(MSG_DISPATCH_JOBS.METADATA, s.metadataJson() == null ? null : JSONB.jsonb(s.metadataJson()))
                .set(MSG_DISPATCH_JOBS.DESCRIPTOR, s.descriptor())
                .set(MSG_DISPATCH_JOBS.CREATED_AT, utc(s.createdAt()))
                .set(MSG_DISPATCH_JOBS.UPDATED_AT, utc(s.updatedAt() == null ? s.createdAt() : s.updatedAt()))
                .execute();
        return s.id();
    }

    /// The queue row of `jobId` as a column map, or `null` when there is none.
    public static Map<String, Object> queueRow(String jobId) {
        var rec = DB.fetchOne(MSG_DISPATCH_QUEUE, MSG_DISPATCH_QUEUE.JOB_ID.eq(jobId));
        return rec == null ? null : rec.intoMap();
    }

    /// The invariant for one job: a queue row exists iff the job is `PENDING`, and
    /// when it exists it mirrors the job (`version = updated_at`, `scheduled_for`,
    /// group, sequence, pool, client, subscription, mode, queue) and .
    public static void assertQueueMirrorsJob(String jobId, String label) {
        var job = DB.fetchOne(MSG_DISPATCH_JOBS, MSG_DISPATCH_JOBS.ID.eq(jobId));
        org.assertj.core.api.Assertions.assertThat(job).as(label + ": job exists").isNotNull();
        Map<String, Object> q = queueRow(jobId);
        if (!"PENDING".equals(job.getStatus())) {
            org.assertj.core.api.Assertions.assertThat(q).as(label + ": job is " + job.getStatus() + ", no queue row").isNull();
            return;
        }
        org.assertj.core.api.Assertions.assertThat(q).as(label + ": PENDING job has a queue row").isNotNull();
        var a = org.assertj.core.api.Assertions.assertThat(q).as(label + ": queue row mirrors the job");
        a.containsEntry("job_created_at", job.getCreatedAt());
        a.containsEntry("version", job.getUpdatedAt());
        a.containsEntry("scheduled_for", job.getScheduledFor());
        a.containsEntry("message_group", job.getMessageGroup());
        a.containsEntry("sequence", job.getSequence());
        a.containsEntry("dispatch_pool_id", job.getDispatchPoolId());
        a.containsEntry("client_id", job.getClientId());
        a.containsEntry("subscription_id", job.getSubscriptionId());
        a.containsEntry("mode", job.getMode());
        a.containsEntry("queue", job.getQueue());
    }

    /// The projection row only (`msg_dispatch_jobs_read`): fewer defaults
    /// than the write table, so `kind`, `protocol`, `mode`, `status`,
    /// `max_retries`, `updated_at` are supplied; the facet columns are the
    /// split parts of the code (spec §1.3).
    public static String seedProjection(Seed s) {
        String[] parts = s.code().split(":", -1);
        DB.insertInto(MSG_DISPATCH_JOBS_READ)
                .set(MSG_DISPATCH_JOBS_READ.ID, s.id())
                .set(MSG_DISPATCH_JOBS_READ.CODE, s.code())
                .set(MSG_DISPATCH_JOBS_READ.SOURCE, s.source())
                .set(MSG_DISPATCH_JOBS_READ.TARGET_URL, "https://hook.example/" + s.id())
                .set(MSG_DISPATCH_JOBS_READ.CLIENT_ID, s.clientId())
                .set(MSG_DISPATCH_JOBS_READ.EVENT_ID, s.eventId())
                .set(MSG_DISPATCH_JOBS_READ.SUBSCRIPTION_ID, s.subscriptionId())
                .set(MSG_DISPATCH_JOBS_READ.DISPATCH_POOL_ID, s.dispatchPoolId())
                .set(MSG_DISPATCH_JOBS_READ.MESSAGE_GROUP, s.messageGroup())
                .set(MSG_DISPATCH_JOBS_READ.KIND, s.kind())
                .set(MSG_DISPATCH_JOBS_READ.PROTOCOL, "HTTP_WEBHOOK")
                .set(MSG_DISPATCH_JOBS_READ.MODE, "IMMEDIATE")
                .set(MSG_DISPATCH_JOBS_READ.STATUS, s.status())
                .set(MSG_DISPATCH_JOBS_READ.MAX_RETRIES, 3)
                .set(MSG_DISPATCH_JOBS_READ.RETRY_STRATEGY, s.retryStrategy())
                .set(MSG_DISPATCH_JOBS_READ.ATTEMPT_COUNT, s.attemptCount())
                .set(MSG_DISPATCH_JOBS_READ.SCHEDULED_FOR, utc(s.scheduledFor()))
                .set(MSG_DISPATCH_JOBS_READ.COMPLETED_AT, utc(s.completedAt()))
                .set(MSG_DISPATCH_JOBS_READ.DURATION_MILLIS, s.durationMillis())
                .set(MSG_DISPATCH_JOBS_READ.LAST_ERROR, s.lastError())
                .set(MSG_DISPATCH_JOBS_READ.APPLICATION, parts.length > 0 ? parts[0] : null)
                .set(MSG_DISPATCH_JOBS_READ.SUBDOMAIN, parts.length > 1 ? parts[1] : null)
                .set(MSG_DISPATCH_JOBS_READ.AGGREGATE, parts.length > 2 ? parts[2] : null)
                .set(MSG_DISPATCH_JOBS_READ.DESCRIPTOR, s.descriptor())
                .set(MSG_DISPATCH_JOBS_READ.METADATA, JSONB.jsonb(s.metadataJson() == null ? "[]" : s.metadataJson()))
                .set(MSG_DISPATCH_JOBS_READ.CREATED_AT, utc(s.createdAt()))
                .set(MSG_DISPATCH_JOBS_READ.UPDATED_AT, utc(s.createdAt()))
                .execute();
        return s.id();
    }

    /// One attempt row as the processing endpoint records it: untyped TSID
    /// id, `status` `SUCCESS` / `FAILURE` (spec §1.2, §10).
    public static void seedAttempt(String jobId, int number, boolean success, Integer responseCode,
                                   String errorMessage, String errorType, Instant attemptedAt) {
        DB.insertInto(MSG_DISPATCH_JOB_ATTEMPTS)
                .set(MSG_DISPATCH_JOB_ATTEMPTS.ID, Tsid.generate())
                .set(MSG_DISPATCH_JOB_ATTEMPTS.DISPATCH_JOB_ID, jobId)
                .set(MSG_DISPATCH_JOB_ATTEMPTS.ATTEMPT_NUMBER, number)
                .set(MSG_DISPATCH_JOB_ATTEMPTS.STATUS, success ? "SUCCESS" : "FAILURE")
                .set(MSG_DISPATCH_JOB_ATTEMPTS.RESPONSE_CODE, responseCode)
                .set(MSG_DISPATCH_JOB_ATTEMPTS.RESPONSE_BODY, success ? "ok" : null)
                .set(MSG_DISPATCH_JOB_ATTEMPTS.ERROR_MESSAGE, errorMessage)
                .set(MSG_DISPATCH_JOB_ATTEMPTS.ERROR_TYPE, errorType)
                .set(MSG_DISPATCH_JOB_ATTEMPTS.DURATION_MILLIS, 42L)
                .set(MSG_DISPATCH_JOB_ATTEMPTS.ATTEMPTED_AT, utc(attemptedAt))
                .set(MSG_DISPATCH_JOB_ATTEMPTS.COMPLETED_AT, utc(attemptedAt.plusMillis(42)))
                .set(MSG_DISPATCH_JOB_ATTEMPTS.CREATED_AT, utc(Instant.now()))
                .execute();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
