package io.flowcatalyst.platform.ingest;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobKind;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.Protocol;
import io.flowcatalyst.platform.dispatchjob.RetryStrategy;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.List;

/// Applies the ingest defaults for one dispatch-job item (sdk-ingest spec
/// §4.1, §4.2) in the domain, so the singular create and one batch item
/// persist identically (Go `jobFromItem`). `clientId` is passed through
/// unresolved — the tenant guard is the API handler's job, checked against
/// the mapped job's own `clientId()` after this returns.
public final class DispatchJobIngestMapper {

    private DispatchJobIngestMapper() {
    }

    /// One inbound item. `sequence` mirrors the batch wire's value-typed
    /// field (0 = unspecified, defaults to 99); `sequenceOverride` is the
    /// singular contract's pointer-typed field (`null` = unspecified, any
    /// other value — including an explicit `0` — wins over the default).
    /// `retryStrategy` / `idempotencyKey` are singular-only on the wire; the
    /// batch route always passes `null` for them, taking the exponential /
    /// no-key default.
    public record RawItem(
            String id,
            String externalId,
            String kind,
            String code,
            String source,
            String subject,
            String targetUrl,
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
            String mode,
            int sequence,
            Integer sequenceOverride,
            int timeoutSeconds,
            int maxRetries,
            String retryStrategy,
            List<DispatchJob.Metadata> metadata,
            String idempotencyKey) {
    }

    /// @throws UseCaseException validation `INVALID_KIND` | `INVALID_RETRY_STRATEGY`
    public static DispatchJob toJob(RawItem it) {
        DispatchJobKind kind = DispatchJobKind.parseStrict(it.kind());
        DispatchMode mode = DispatchMode.parse(it.mode());
        RetryStrategy retryStrategy = RetryStrategy.parseStrict(it.retryStrategy());
        String payloadContentType = blank(it.payloadContentType()) == null
                ? DispatchJob.DEFAULT_PAYLOAD_CONTENT_TYPE
                : it.payloadContentType();
        int sequence = it.sequence() == 0 ? 99 : it.sequence();
        if (it.sequenceOverride() != null) sequence = it.sequenceOverride();
        int timeoutSeconds = it.timeoutSeconds() == 0 ? 30 : it.timeoutSeconds();
        int maxRetries = it.maxRetries() == 0 ? 3 : it.maxRetries();
        String id = blank(it.id()) == null ? Tsid.generate() : it.id();
        // code/targetUrl are wire-optional on the batch item (Go's batch loop performs no
        // presence check on them, unlike the singular contract, which validates before ever
        // reaching this mapper); the aggregate's invariant forbids null, matching the NOT NULL
        // columns, so an absent value reads as "" rather than throwing (Go's zero-value behaviour).
        String code = it.code() == null ? "" : it.code();
        String targetUrl = it.targetUrl() == null ? "" : it.targetUrl();

        Instant now = Instant.now();
        return new DispatchJob(
                id, it.externalId(), kind, code, it.source(), it.subject(), targetUrl,
                Protocol.HTTP_WEBHOOK, it.payload(), payloadContentType, it.dataOnly(), it.eventId(),
                it.correlationId(), it.clientId(), it.subscriptionId(), it.serviceAccountId(), it.dispatchPoolId(),
                it.messageGroup(), mode, sequence, timeoutSeconds, null, maxRetries, retryStrategy,
                DispatchJobStatus.PENDING, 0, null, it.metadata(), it.idempotencyKey(), now, now,
                null, null, null, null, null);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
