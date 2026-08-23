package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.subscription.DispatchMode;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The dispatch-job aggregate root — a row of the partitioned write table
/// `msg_dispatch_jobs` (spec §1.1). One delivery of one message to one
/// target URL. Jobs are created by ingest / fan-out and driven through their
/// lifecycle by the scheduler and the processing endpoint (other units); the
/// only transition this unit owns is the operator's [#requeue] (spec §2).
///
/// Immutable record: the transition returns a copy. `createdAt` is the
/// partition key and half the primary key — it never changes. `attempts`
/// are never hydrated onto the job (spec §1.1); they are read separately.
///
/// @param id                 13-char untyped TSID (no prefix — the column is `varchar(13)`)
/// @param externalId         optional caller-supplied id
/// @param kind               `EVENT` | `TASK`
/// @param code               the message code (`app:subdomain:aggregate:event` by convention, unvalidated)
/// @param source             optional origin
/// @param subject            optional subject
/// @param targetUrl          delivery URL
/// @param protocol           always `HTTP_WEBHOOK`
/// @param payload            optional body text (not on the projection)
/// @param payloadContentType body content type, `application/json` by default
/// @param dataOnly           whether only the data payload is delivered
/// @param eventId            optional source event
/// @param correlationId      optional trace id
/// @param clientId           tenant, `null` = platform-scoped
/// @param subscriptionId     optional subscription
/// @param serviceAccountId   optional delivering service account
/// @param dispatchPoolId     optional pool
/// @param messageGroup       optional FIFO key
/// @param mode               ordering mode within the group
/// @param sequence           order within the group
/// @param timeoutSeconds     delivery timeout
/// @param schemaId           optional schema (not on the projection)
/// @param maxRetries         retry budget
/// @param retryStrategy      backoff strategy
/// @param status             lifecycle state
/// @param attemptCount       attempts consumed
/// @param lastError          last failure message, `null` when none
/// @param metadata           SDK key/value tags, never `null`
/// @param idempotencyKey     optional dedup key
/// @param createdAt          creation time — the partition key
/// @param updatedAt          last change
/// @param scheduledFor       earliest (re)dispatch time, `null` = now
/// @param expiresAt          optional expiry
/// @param lastAttemptAt      optional last attempt time
/// @param completedAt        optional terminal time
/// @param durationMillis     optional end-to-end duration
public record DispatchJob(
        String id,
        String externalId,
        DispatchJobKind kind,
        String code,
        String source,
        String subject,
        String targetUrl,
        Protocol protocol,
        String payload,
        String payloadContentType,
        boolean dataOnly,
        String eventId,
        String correlationId,
        String clientId,
        String subscriptionId,
        String serviceAccountId,
        String dispatchPoolId,
        String messageGroup,
        DispatchMode mode,
        int sequence,
        int timeoutSeconds,
        String schemaId,
        int maxRetries,
        RetryStrategy retryStrategy,
        DispatchJobStatus status,
        int attemptCount,
        String lastError,
        List<Metadata> metadata,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        Instant scheduledFor,
        Instant expiresAt,
        Instant lastAttemptAt,
        Instant completedAt,
        Long durationMillis) implements HasId {

    /// The content type a job reads with when the column is `NULL` (spec §8).
    public static final String DEFAULT_PAYLOAD_CONTENT_TYPE = "application/json";

    public DispatchJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(payloadContentType, "payloadContentType");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(retryStrategy, "retryStrategy");
        Objects.requireNonNull(status, "status");
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// One SDK tag. The JSONB column stores the list as an **array of
    /// `{key, value}` pairs** — the SDK wire shape; an object would break
    /// drop-in parity (spec §1.1).
    public record Metadata(String key, String value) {
        public Metadata {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// The operator's "resend": any status → `PENDING` with immediate
    /// eligibility (`scheduledFor` cleared), a full retry budget
    /// (`attemptCount` 0) and the terminal stamps cleared. Total — no
    /// precondition (spec §2, open question 2).
    public DispatchJob requeue() {
        return new DispatchJob(id, externalId, kind, code, source, subject, targetUrl, protocol, payload,
                payloadContentType, dataOnly, eventId, correlationId, clientId, subscriptionId, serviceAccountId,
                dispatchPoolId, messageGroup, mode, sequence, timeoutSeconds, schemaId, maxRetries, retryStrategy,
                DispatchJobStatus.PENDING, 0, null, metadata, idempotencyKey, createdAt, Instant.now(),
                null, expiresAt, lastAttemptAt, null, null);
    }
}
