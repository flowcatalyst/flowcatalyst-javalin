package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// A row of the read projection `msg_dispatch_jobs_read` (spec §1.3): the
/// slim, indexed copy of a job the list / by-event / facet reads are served
/// from. Maintained by the stream projector — never written by this unit.
/// It is a different table with a different column set, so it is a
/// different record: no `payload`, `metadata`, `schemaId`,
/// `payloadContentType` or `dataOnly`.
///
/// The wire's `application / subdomain / aggregate` are derived from `code`
/// via [CodeFacets] ([#facets]); the filters of the same names match the
/// projection's own columns.
public record DispatchJobProjection(
        String id,
        String externalId,
        String source,
        DispatchJobKind kind,
        String code,
        String subject,
        String eventId,
        String correlationId,
        String targetUrl,
        Protocol protocol,
        String serviceAccountId,
        String clientId,
        String subscriptionId,
        String dispatchPoolId,
        DispatchMode mode,
        String messageGroup,
        int sequence,
        int timeoutSeconds,
        DispatchJobStatus status,
        int maxRetries,
        RetryStrategy retryStrategy,
        Instant scheduledFor,
        Instant expiresAt,
        int attemptCount,
        Instant lastAttemptAt,
        Instant completedAt,
        Long durationMillis,
        String lastError,
        String idempotencyKey,
        String descriptor,
        List<DispatchJob.Metadata> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public DispatchJobProjection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(retryStrategy, "retryStrategy");
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// The code-derived facets the wire shape carries.
    public CodeFacets facets() {
        return CodeFacets.of(code);
    }
}
