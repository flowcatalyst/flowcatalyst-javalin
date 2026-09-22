package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.jfr.DispatchProcessedEvent;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/// `POST /api/dispatch/process` (dispatch-seam spec §5) — the router's
/// mediation target for every dispatch job: the router never talks to the
/// real subscriber, it delivers a signed `{"messageId":"<id>"}` here and
/// this endpoint is the actual webhook client. It loads the job, delivers to
/// `target_url` via [SubscriberDelivery], records the attempt, and advances
/// `msg_dispatch_jobs.status` — always answering the router `ack:true`
/// except the two paths spec §5 names (bad/missing auth token; a DB error
/// loading the job), so retries are entirely platform-owned via
/// `scheduled_for`, never via the queue's own redelivery.
///
/// Public route — registered OUTSIDE the platform JWT middleware
/// (`Platform.isPublicPath`), like
/// [io.flowcatalyst.platform.dispatchjob.settled.SettledApi]: the router
/// carries no platform JWT and self-verifies the scheduler-signed per-job
/// HMAC bearer instead ([HmacTokenVerifier]).
///
/// | Condition | HTTP | `ack` |
/// |---|---|---|
/// | request body over [#MAX_REQUEST_BODY_BYTES] (4 KiB, Go's own guard) | 400 | `true` |
/// | malformed/empty `messageId` | 400 | `true` |
/// | bad/missing bearer token | 401 | `false` — the one deliberate NACK |
/// | DB error loading the job | 500 | `false` |
/// | job row gone | 200 | `true` |
/// | job already terminal | 200 | `true`, no delivery |
/// | `groupHeldBefore` DB error | 500 | `false` |
/// | group held | 200 | `true`, `"message":"group blocked"` |
/// | `reschedule` fails while held | 500 | `false` |
/// | job already claimed by a concurrent delivery | 200 | `true`, no delivery |
/// | claim DB error | 500 | `false` |
/// | any delivery attempt (delivered/deferred/failed) | 200 | `true` |
public final class ProcessingApi {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingApi.class);

    /// Backoff between delivery attempts (spec §3's timing table): index
    /// `attemptNumber - 1`, clamped to the last rung.
    private static final int[] BACKOFF_LADDER_SECONDS = {5, 15, 30, 60, 120};

    /// Request body size cap (Go's own guard on this endpoint) — the body is
    /// always a tiny `{"messageId":"…"}`, so anything past a few hundred
    /// bytes is already pathological; 4 KiB leaves generous headroom without
    /// letting a caller make this handler buffer an arbitrarily large body.
    static final int MAX_REQUEST_BODY_BYTES = 4 * 1024;

    private ProcessingApi() {
    }

    /// The handler's dependencies. `repo` is typed as [ProcessingRepository]
    /// — the handful of methods this handler actually calls — rather than
    /// the concrete `DispatchJobRepository`, so a test can inject a failure
    /// at exactly one call without mocking the whole repository (audit
    /// finding, test-gap). `credentials` defaults to
    /// [DeliveryCredentials#none] and `clock` to [Clock#systemUTC] — both
    /// overridable for tests.
    public record State(ProcessingRepository repo, HmacTokenVerifier verifier, SubscriberDelivery delivery,
                         DeliveryCredentials credentials, Clock clock) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(verifier, "verifier");
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(clock, "clock");
        }

        public State(ProcessingRepository repo, HmacTokenVerifier verifier, SubscriberDelivery delivery) {
            this(repo, verifier, delivery, DeliveryCredentials.none(), Clock.systemUTC());
        }
    }

    public static void register(Routes routes, State s) {
        routes.post("/api/dispatch/process", ctx -> serve(ctx, s));
    }

    private static void serve(Exchange ctx, State s) {
        // Reject on the declared Content-Length BEFORE buffering the body — same reasoning as
        // SettledApi's cap (audit finding); `contentLength()` is -1 for a chunked body with no
        // declared length, so the post-read check below still catches that case.
        if (ctx.contentLength() > MAX_REQUEST_BODY_BYTES) {
            ack(ctx, 400, true, "invalid messageId");
            return;
        }
        byte[] requestBody = ctx.bodyAsBytes();
        if (requestBody.length > MAX_REQUEST_BODY_BYTES) {
            ack(ctx, 400, true, "invalid messageId");
            return;
        }
        ProcessRequest req;
        try {
            req = Json.MAPPER.readValue(requestBody, ProcessRequest.class);
        } catch (JacksonException e) {
            ack(ctx, 400, true, "invalid messageId");
            return;
        }
        String jobId = req.messageId() == null ? "" : req.messageId().trim();
        if (jobId.isEmpty()) {
            ack(ctx, 400, true, "invalid messageId");
            return;
        }

        String token = bearerToken(ctx);
        if (token == null || !s.verifier().verify(jobId, token)) {
            // The one deliberate NACK: a forged callback must not be able to
            // trigger a delivery, and the router (which always carries a
            // valid token) never hits this branch.
            LOG.atWarn().setMessage("dispatch process: bad auth token")
                    .addKeyValue("job_id", jobId)
                    .log();
            ack(ctx, 401, false, "unauthorized");
            return;
        }

        DispatchJob job;
        try {
            job = s.repo().findById(jobId).orElse(null);
        } catch (RuntimeException e) {
            // Transient DB error — NACK so the queue redelivers.
            LOG.atError().setMessage("dispatch process: load job failed")
                    .addKeyValue("job_id", jobId)
                    .setCause(e)
                    .log();
            ack(ctx, 500, false, "load failed");
            return;
        }
        if (job == null) {
            ack(ctx, 200, true, "job not found");
            return;
        }
        if (job.isTerminal()) {
            emit(jobId, "AlreadyTerminal", 0, false);
            ack(ctx, 200, true, null);
            return;
        }

        GroupHoldOutcome hold = heldByGroup(s, job);
        switch (hold) {
            case GroupHoldOutcome.NotHeld ignored -> deliver(ctx, s, job);
            case GroupHoldOutcome.Blocked ignored -> {
                emit(job.id(), "Held", 0, true);
                ack(ctx, 200, true, "group blocked");
            }
            case GroupHoldOutcome.CheckFailed ignored -> ack(ctx, 500, false, "blocked check failed");
            case GroupHoldOutcome.RevertFailed ignored -> ack(ctx, 500, false, "revert failed");
        }
    }

    /// The delivery-time `GroupHeldBefore` gate's outcome (spec §5, §9).
    private sealed interface GroupHoldOutcome {
        record NotHeld() implements GroupHoldOutcome {
        }

        record Blocked() implements GroupHoldOutcome {
        }

        record CheckFailed() implements GroupHoldOutcome {
        }

        record RevertFailed() implements GroupHoldOutcome {
        }
    }

    /// For `BLOCK_ON_ERROR` jobs with a non-empty group only — `IMMEDIATE`
    /// and `NEXT_ON_ERROR` skip the query entirely (spec §5), matching the
    /// scheduler's own claim-time filter.
    private static GroupHoldOutcome heldByGroup(State s, DispatchJob job) {
        if (job.mode() != DispatchMode.BLOCK_ON_ERROR
                || job.messageGroup() == null || job.messageGroup().isEmpty()) {
            return new GroupHoldOutcome.NotHeld();
        }
        boolean blocked;
        try {
            blocked = s.repo().groupHeldBefore(job);
        } catch (RuntimeException e) {
            LOG.atError().setMessage("dispatch process: blocked-group check failed")
                    .addKeyValue("job_id", job.id())
                    .setCause(e)
                    .log();
            return new GroupHoldOutcome.CheckFailed();
        }
        if (!blocked) {
            return new GroupHoldOutcome.NotHeld();
        }
        try {
            // Revert to PENDING, immediately re-eligible once the group
            // unblocks — no retry budget spent (spec §9's second invariant).
            s.repo().reschedule(job.id(), job.createdAt(), Instant.now(s.clock()));
        } catch (RuntimeException e) {
            // Revert failed: NACK, not ack, or the job would sit QUEUED with
            // no queue message until stale recovery (spec §5, §9's first
            // invariant).
            LOG.atError().setMessage("dispatch process: blocked-group revert failed")
                    .addKeyValue("job_id", job.id())
                    .setCause(e)
                    .log();
            return new GroupHoldOutcome.RevertFailed();
        }
        return new GroupHoldOutcome.Blocked();
    }

    private static void deliver(Exchange ctx, State s, DispatchJob job) {
        // The claim, not the earlier isTerminal() read, is what decides whether
        // this call owns the delivery: the read is unlocked and a concurrent
        // redelivery can pass it too. A claim that changes no row means another
        // delivery of this job is in flight (or it finished) — ACK and make no
        // call, or the subscriber sees the same delivery twice.
        boolean claimed;
        try {
            claimed = s.repo().claimForDelivery(job.id(), job.createdAt());
        } catch (RuntimeException e) {
            // NOT best-effort any more: a failed claim leaves ownership
            // unknown, and delivering anyway is exactly the duplicate this
            // guard exists to prevent. NACK and let the queue redeliver.
            LOG.atError().setMessage("dispatch process: claim failed")
                    .addKeyValue("job_id", job.id())
                    .setCause(e)
                    .log();
            ack(ctx, 500, false, "claim failed");
            return;
        }
        if (!claimed) {
            emit(job.id(), "AlreadyClaimed", 0, false);
            ack(ctx, 200, true, "already claimed");
            return;
        }

        int attemptNumber = job.attemptCount() + 1;
        Instant attemptStart = Instant.now(s.clock());
        DeliveryCredentials.Resolved credentials = resolveCredentials(s, job);
        DeliveryResult result = s.delivery().deliver(job, credentials, attemptStart);
        Instant completedAt = Instant.now(s.clock());
        long durationMillis = Duration.between(attemptStart, completedAt).toMillis();

        recordAttempt(s, job.id(), attemptNumber, result, attemptStart, completedAt, durationMillis);
        advance(s, job, attemptNumber, result, durationMillis);

        emit(job.id(), resultKind(result), attemptNumber, false);
        ack(ctx, 200, true, null);
    }

    private static DeliveryCredentials.Resolved resolveCredentials(State s, DispatchJob job) {
        DeliveryCredentials.Resolved resolved;
        try {
            resolved = s.credentials().resolve(job);
            if (resolved == null) {
                resolved = DeliveryCredentials.Resolved.NONE;
            }
        } catch (RuntimeException e) {
            // Resolver failure degrades to bare delivery with a warning, not
            // a hard failure (spec §3) — a distinct log line from the "bare
            // with a reason" WARN below: this one means the lookup itself
            // never answered, that one means it answered "no credentials".
            LOG.atWarn().setMessage("dispatch process: delivery-creds lookup failed; delivering unsigned")
                    .addKeyValue("job_id", job.id())
                    .setCause(e)
                    .log();
            return DeliveryCredentials.Resolved.bare("credential lookup failed: " + e.getMessage());
        }
        // Never unsigned silently (hand-off 2026-09-22): a resolver that
        // actually explained why it is bare (a non-empty reason — every real
        // resolution path sets one) gets a WARN naming it, so an operator
        // sees why a delivery is about to go out unsigned instead of finding
        // out from the subscriber's 401 three retries later. [DeliveryCredentials#none]'s
        // deliberate "no lookups at all" sentinel carries no reason and is
        // NOT this case — a test fixture that wants no credentials at all is
        // not "a resolver found nothing".
        if (resolved.isBare() && resolved.reason() != null && !resolved.reason().isEmpty()) {
            LOG.atWarn().setMessage("dispatch process: delivering unsigned")
                    .addKeyValue("job_id", job.id())
                    .addKeyValue("subscription_id", job.subscriptionId())
                    .addKeyValue("reason", resolved.reason())
                    .log();
        }
        return resolved;
    }

    private static void recordAttempt(State s, String jobId, int attemptNumber, DeliveryResult result,
                                       Instant attemptedAt, Instant completedAt, long durationMillis) {
        try {
            switch (result) {
                case DeliveryResult.Delivered delivered -> s.repo().recordAttempt(jobId, attemptNumber, true,
                        delivered.status(), delivered.body(), null, null, delivered.request(), attemptedAt,
                        completedAt, durationMillis);
                case DeliveryResult.Deferred deferred -> s.repo().recordAttempt(jobId, attemptNumber, false,
                        deferred.status(), null, "subscriber deferred delivery", null, deferred.request(),
                        attemptedAt, completedAt, durationMillis);
                // The response body is kept on a failed attempt too (2026-09-22 addendum):
                // the subscriber's stated reason, not just the status that earned it.
                case DeliveryResult.Failed failed -> s.repo().recordAttempt(jobId, attemptNumber, false,
                        failed.status(), failed.body(), failed.message(), failed.errorType(), failed.request(),
                        attemptedAt, completedAt, durationMillis);
            }
        } catch (RuntimeException e) {
            // Best-effort, same as MarkInProgress above — a recording
            // failure must not change the delivery decision (spec §5).
            LOG.atWarn().setMessage("dispatch process: record attempt failed")
                    .addKeyValue("job_id", jobId)
                    .setCause(e)
                    .log();
        }
    }

    /// Advances the job's status per spec §5's outcome table.
    private static void advance(State s, DispatchJob job, int attemptNumber, DeliveryResult result, long durationMillis) {
        switch (result) {
            case DeliveryResult.Delivered ignored -> {
                try {
                    s.repo().markCompleted(job.id(), job.createdAt(), Instant.now(s.clock()), durationMillis);
                } catch (RuntimeException e) {
                    LOG.atWarn().setMessage("dispatch process: mark completed failed")
                            .addKeyValue("job_id", job.id())
                            .setCause(e)
                            .log();
                }
            }
            case DeliveryResult.Deferred deferred -> {
                try {
                    // No retry budget spent — cooperative back-pressure, not
                    // a failure (spec §4, §9's second invariant).
                    s.repo().reschedule(job.id(), job.createdAt(),
                            Instant.now(s.clock()).plusSeconds(deferred.delaySeconds()));
                } catch (RuntimeException e) {
                    LOG.atWarn().setMessage("dispatch process: reschedule failed")
                            .addKeyValue("job_id", job.id())
                            .setCause(e)
                            .log();
                }
            }
            case DeliveryResult.Failed failed -> {
                if (failed.status() != null && (failed.status() == 401 || failed.status() == 403)) {
                    // Fail fast (hand-off 2026-09-22 addendum): a retry sends the SAME
                    // credentials, so three attempts only delay the same answer — this is
                    // terminal on the FIRST attempt, never subject to the retry ladder or
                    // maxRetries below. attempt_count is left as the ordinary Failed path
                    // would leave it (not bumped by markFailed) — a requeue does not reset
                    // it, so bumping here would make a requeued job fail immediately.
                    try {
                        s.repo().markFailed(job.id(), job.createdAt(), failed.message());
                    } catch (RuntimeException e) {
                        LOG.atWarn().setMessage("dispatch process: mark failed failed")
                                .addKeyValue("job_id", job.id())
                                .setCause(e)
                                .log();
                    }
                    LOG.atWarn().setMessage("dispatch failed (subscriber refused credentials; not retried)")
                            .addKeyValue("job_id", job.id())
                            .addKeyValue("status", failed.status())
                            .addKeyValue("attempt", attemptNumber)
                            .log();
                } else if (attemptNumber >= job.maxRetries()) {
                    try {
                        s.repo().markFailed(job.id(), job.createdAt(), failed.message());
                    } catch (RuntimeException e) {
                        LOG.atWarn().setMessage("dispatch process: mark failed failed")
                                .addKeyValue("job_id", job.id())
                                .setCause(e)
                                .log();
                    }
                } else {
                    Instant scheduledFor = Instant.now(s.clock()).plusSeconds(backoffFor(attemptNumber));
                    try {
                        s.repo().scheduleRetry(job.id(), job.createdAt(), scheduledFor, attemptNumber, failed.message());
                    } catch (RuntimeException e) {
                        LOG.atWarn().setMessage("dispatch process: schedule retry failed")
                                .addKeyValue("job_id", job.id())
                                .setCause(e)
                                .log();
                    }
                }
            }
        }
    }

    /// Index `attemptNumber - 1`, clamped to the ladder's last rung (spec
    /// §3's timing table, Go `backoffFor`).
    private static int backoffFor(int attemptNumber) {
        int index = Math.max(attemptNumber - 1, 0);
        index = Math.min(index, BACKOFF_LADDER_SECONDS.length - 1);
        return BACKOFF_LADDER_SECONDS[index];
    }

    private static String resultKind(DeliveryResult result) {
        return switch (result) {
            case DeliveryResult.Delivered ignored -> "Delivered";
            case DeliveryResult.Deferred ignored -> "Deferred";
            case DeliveryResult.Failed ignored -> "Failed";
        };
    }

    private static void emit(String jobId, String result, int attempt, boolean held) {
        var event = new DispatchProcessedEvent();
        event.jobId = jobId;
        event.result = result;
        event.attempt = attempt;
        event.held = held;
        event.commit();
    }

    /// `TrimPrefix(Authorization, "Bearer ")`: `null` when the header is
    /// absent or the (trimmed) token is empty — Go's `token == ""` check.
    private static String bearerToken(Exchange ctx) {
        String header = ctx.header("Authorization");
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        String token = header.startsWith(prefix) ? header.substring(prefix.length()) : header;
        return token.isEmpty() ? null : token;
    }

    private static void ack(Exchange ctx, int status, boolean ackValue, String message) {
        ctx.status(status).json(new ProcessResponse(ackValue, message));
    }

    // ── Wire DTOs (spec §5) ────────────────────────────────────────────────

    /// `{"messageId": "<dispatch-job id>"}`.
    private record ProcessRequest(String messageId) {
    }

    /// `{"ack": bool, "message"?: string}`.
    private record ProcessResponse(boolean ack, String message) {
    }
}
