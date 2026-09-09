package io.flowcatalyst.platform.bff.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.event.Event;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.event.api.EventApi.ContextEntryDTO;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.DISPATCH_JOB_VIEW_RAW;
import static io.flowcatalyst.platform.shared.auth.Permission.EVENT_VIEW_RAW;

/// The `/bff/debug/*` raw views (missed-port fix, see `docs/backlog.md`):
/// unfiltered, most-recent-first dumps of the write-side tables
/// (`msg_events`, `msg_dispatch_jobs`) that the regular list routes project
/// away — the SPA's `RawEventListPage` / `RawDispatchJobListPage` bind these
/// bare arrays directly. Go: `internal/platform/event/api/api.go:339+` /
/// `internal/platform/dispatchjob/api/api.go:207+`, `listDebugRaw`.
///
/// Distinct from `/api/events/list-raw` / `/api/dispatch-jobs/list-raw`
/// (which share the [EVENT_VIEW_RAW] / [DISPATCH_JOB_VIEW_RAW] gate but read
/// the projected tables with the ordinary filters): these two routes are the
/// UN-projected write-side envelope, no tenant scoping — the permission is
/// the whole gate (Go has none either).
///
/// | Method | Path | Gate | Status |
/// |---|---|---|---|
/// | GET | `/bff/debug/events` | `event:view-raw` | 200 bare array of [RawEventResponse] |
/// | GET | `/bff/debug/dispatch-jobs` | `dispatch-job:view-raw` | 200 bare array of [RawDispatchJobResponse] |
public final class DebugBff {

    /// `size` absent or `<= 0` → this default (Go `listDebugRaw`); the
    /// repository's own guard then re-clamps an out-of-range value to ITS
    /// default (100), not this one — `size=5000` therefore yields 100 rows,
    /// not 1000, even though the repository's doc string says "max 1000".
    /// Mirrored from Go verbatim; flagged as a likely defect, not fixed here.
    static final int DEFAULT_SIZE = 50;

    private DebugBff() {
    }

    public record State(EventRepository events, DispatchJobRepository dispatchJobs) {
        public State {
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(dispatchJobs, "dispatchJobs");
        }
    }

    public static void register(Routes routes, State s) {
        routes.get("/bff/debug/events", Auth.scoped(ctx -> listEvents(ctx, s)));
        routes.get("/bff/debug/dispatch-jobs", Auth.scoped(ctx -> listDispatchJobs(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void listEvents(Exchange ctx, State s) {
        Checks.require(Auth.current(), EVENT_VIEW_RAW);
        int limit = size(ctx);
        ctx.json(s.events().findRecentRaw(limit).stream().map(RawEventResponse::from).toList());
    }

    private static void listDispatchJobs(Exchange ctx, State s) {
        Checks.require(Auth.current(), DISPATCH_JOB_VIEW_RAW);
        int limit = size(ctx);
        ctx.json(s.dispatchJobs().findRecentRaw(limit).stream().map(RawDispatchJobResponse::from).toList());
    }

    /// `size` absent, blank or unparseable reads as `0`, which then falls
    /// back to [#DEFAULT_SIZE] — the repository's own guard handles the
    /// over-max clamp (spec: mirror Go's `rawListInput.Size` exactly).
    private static int size(Exchange ctx) {
        String raw = ctx.queryParam("size");
        if (raw == null || raw.isBlank()) return DEFAULT_SIZE;
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException notAnInt) {
            return DEFAULT_SIZE;
        }
        return parsed <= 0 ? DEFAULT_SIZE : parsed;
    }

    // ── Wire DTOs ─────────────────────────────────────────────────────────

    /// The debug raw-event wire shape (Go `RawEventResponse`). Distinct from
    /// `EventRead`/`EventResponse`: the type column is named `eventType`
    /// here (the SPA binds `field="eventType"`), and `subject` /
    /// `deduplicationId` are omitted for an EMPTY string, not just a `NULL`
    /// column — matching Go's `rawFromEntity`, which nils the pointer on
    /// `""` for those two fields only.
    public record RawEventResponse(
            String id,
            String specVersion,
            String eventType,
            String source,
            String subject,
            Instant time,
            JsonNode data,
            String messageGroup,
            String correlationId,
            String causationId,
            String deduplicationId,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ContextEntryDTO> contextData,
            String clientId) {

        public RawEventResponse {
            contextData = contextData == null ? List.of() : List.copyOf(contextData);
        }

        public static RawEventResponse from(Event e) {
            return new RawEventResponse(
                    e.id(), e.specVersion(), e.type(), e.source(),
                    blankToNull(e.subject()), e.time(), e.data(),
                    e.messageGroup(), e.correlationId(), e.causationId(),
                    blankToNull(e.deduplicationId()),
                    e.context().stream().map(c -> new ContextEntryDTO(c.key(), c.value())).toList(),
                    e.clientId());
        }

        private static String blankToNull(String v) {
            return v == null || v.isEmpty() ? null : v;
        }
    }

    /// The debug raw-job wire shape (Go `RawDispatchJobResponse`):
    /// `payloadLength` is the UTF-8 byte length of `payload` (Go takes
    /// `len()` of the Go string, which counts bytes, not runes — a
    /// `String#length()` would silently disagree on multibyte payloads).
    /// `attemptHistoryCount` is always `0`: neither the Go entity nor this
    /// port's [DispatchJob] hydrates attempts on a repository read (they are
    /// fetched separately, by job id) — the field is vestigial in Go and
    /// stays vestigial here rather than paying for an extra per-row query
    /// the spec did not ask for.
    public record RawDispatchJobResponse(
            String id,
            String externalId,
            String source,
            String kind,
            String code,
            String subject,
            String eventId,
            String correlationId,
            String targetUrl,
            String protocol,
            String clientId,
            String subscriptionId,
            String serviceAccountId,
            String dispatchPoolId,
            String messageGroup,
            String mode,
            int sequence,
            String status,
            int attemptCount,
            int maxRetries,
            String lastError,
            int timeoutSeconds,
            String retryStrategy,
            String idempotencyKey,
            Instant createdAt,
            Instant updatedAt,
            Instant scheduledFor,
            Instant completedAt,
            String payloadContentType,
            int payloadLength,
            int attemptHistoryCount) {

        public static RawDispatchJobResponse from(DispatchJob j) {
            return new RawDispatchJobResponse(
                    j.id(), j.externalId(), j.source(), j.kind().name(), j.code(), j.subject(),
                    j.eventId(), j.correlationId(), j.targetUrl(), j.protocol().name(),
                    j.clientId(), j.subscriptionId(), j.serviceAccountId(), j.dispatchPoolId(),
                    j.messageGroup(), j.mode().name(), j.sequence(), j.status().name(), j.attemptCount(),
                    j.maxRetries(), j.lastError(), j.timeoutSeconds(), j.retryStrategy().wire(),
                    j.idempotencyKey(), j.createdAt(), j.updatedAt(), j.scheduledFor(), j.completedAt(),
                    j.payloadContentType(), payloadLength(j.payload()), 0);
        }

        private static int payloadLength(String payload) {
            return payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
