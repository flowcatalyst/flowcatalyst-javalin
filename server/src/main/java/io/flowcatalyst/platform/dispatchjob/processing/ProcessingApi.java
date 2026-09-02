package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.jfr.DispatchProcessedEvent;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.subscription.DispatchMode;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
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
/// | malformed/empty `messageId` | 400 | `true` |
/// | bad/missing bearer token | 401 | `false` — the one deliberate NACK |
/// | DB error loading the job | 500 | `false` |
/// | job row gone | 200 | `true` |
/// | job already terminal | 200 | `true`, no delivery |
/// | `groupHeldBefore` DB error | 500 | `false` |
/// | group held | 200 | `true`, `"message":"group blocked"` |
/// | `reschedule` fails while held | 500 | `false` |
/// | any delivery attempt (delivered/deferred/failed) | 200 | `true` |
public final class ProcessingApi {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingApi.class);

    /// Backoff between delivery attempts (spec §3's timing table): index
    /// `attemptNumber - 1`, clamped to the last rung.
    private static final int[] BACKOFF_LADDER_SECONDS = {5, 15, 30, 60, 120};

    private ProcessingApi() {
    }

    /// The handler's dependencies. `credentials` defaults to
    /// [DeliveryCredentials#none] and `clock` to [Clock#systemUTC] — both
    /// overridable for tests.
    public record State(DispatchJobRepository repo, HmacTokenVerifier verifier, SubscriberDelivery delivery,
                         DeliveryCredentials credentials, Clock clock) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(verifier, "verifier");
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(clock, "clock");
        }

        public State(DispatchJobRepository repo, HmacTokenVerifier verifier, SubscriberDelivery delivery) {
            this(repo, verifier, delivery, DeliveryCredentials.none(), Clock.systemUTC());
        }
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.post("/api/dispatch/process", ctx -> serve(ctx, s));
    }

    private static void serve(Context ctx, State s) {
        ProcessRequest req;
        try {
            req = Json.MAPPER.readValue(ctx.bodyAsBytes(), ProcessRequest.class);
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
            LOG.warn("dispatch process: bad auth token, job_id={}", jobId);
            ack(ctx, 401, false, "unauthorized");
            return;
        }

        DispatchJob job;
        try {
            job = s.repo().findById(jobId).orElse(null);
        } catch (RuntimeException e) {
            // Transient DB error — NACK so the queue redelivers.
            LOG.error("dispatch process: load job failed, job_id={}", jobId, e);
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
            LOG.error("dispatch process: blocked-group check failed, job_id={}", job.id(), e);
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
            LOG.error("dispatch process: blocked-group revert failed, job_id={}", job.id(), e);
            return new GroupHoldOutcome.RevertFailed();
        }
        return new GroupHoldOutcome.Blocked();
    }

    private static void deliver(Context ctx, State s, DispatchJob job) {
        try {
            s.repo().markInProgress(job.id(), job.createdAt());
        } catch (RuntimeException e) {
            // Best-effort (spec §5 open question 2, Go's own scope): the
            // handler proceeds to deliver and always ACKs regardless.
            LOG.warn("dispatch process: mark in-progress failed, job_id={}", job.id(), e);
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
        try {
            var resolved = s.credentials().resolve(job);
            return resolved == null ? DeliveryCredentials.Resolved.NONE : resolved;
        } catch (RuntimeException e) {
            // Resolver failure degrades to bare delivery with a warning, not
            // a hard failure (spec §5).
            LOG.warn("dispatch process: delivery-creds lookup failed; delivering unsigned, job_id={}", job.id(), e);
            return DeliveryCredentials.Resolved.NONE;
        }
    }

    private static void recordAttempt(State s, String jobId, int attemptNumber, DeliveryResult result,
                                       Instant attemptedAt, Instant completedAt, long durationMillis) {
        try {
            switch (result) {
                case DeliveryResult.Delivered delivered -> s.repo().recordAttempt(jobId, attemptNumber, true,
                        delivered.status(), delivered.body(), null, null, attemptedAt, completedAt, durationMillis);
                case DeliveryResult.Deferred ignored -> s.repo().recordAttempt(jobId, attemptNumber, false,
                        null, null, "subscriber deferred delivery", null, attemptedAt, completedAt, durationMillis);
                case DeliveryResult.Failed failed -> s.repo().recordAttempt(jobId, attemptNumber, false,
                        failed.status(), null, failed.message(), failed.errorType(), attemptedAt, completedAt, durationMillis);
            }
        } catch (RuntimeException e) {
            // Best-effort, same as MarkInProgress above — a recording
            // failure must not change the delivery decision (spec §5).
            LOG.warn("dispatch process: record attempt failed, job_id={}", jobId, e);
        }
    }

    /// Advances the job's status per spec §5's outcome table.
    private static void advance(State s, DispatchJob job, int attemptNumber, DeliveryResult result, long durationMillis) {
        switch (result) {
            case DeliveryResult.Delivered ignored -> {
                try {
                    s.repo().markCompleted(job.id(), job.createdAt(), Instant.now(s.clock()), durationMillis);
                } catch (RuntimeException e) {
                    LOG.warn("dispatch process: mark completed failed, job_id={}", job.id(), e);
                }
            }
            case DeliveryResult.Deferred deferred -> {
                try {
                    // No retry budget spent — cooperative back-pressure, not
                    // a failure (spec §4, §9's second invariant).
                    s.repo().reschedule(job.id(), job.createdAt(),
                            Instant.now(s.clock()).plusSeconds(deferred.delaySeconds()));
                } catch (RuntimeException e) {
                    LOG.warn("dispatch process: reschedule failed, job_id={}", job.id(), e);
                }
            }
            case DeliveryResult.Failed failed -> {
                if (attemptNumber >= job.maxRetries()) {
                    try {
                        s.repo().markFailed(job.id(), job.createdAt(), failed.message());
                    } catch (RuntimeException e) {
                        LOG.warn("dispatch process: mark failed failed, job_id={}", job.id(), e);
                    }
                } else {
                    Instant scheduledFor = Instant.now(s.clock()).plusSeconds(backoffFor(attemptNumber));
                    try {
                        s.repo().scheduleRetry(job.id(), job.createdAt(), scheduledFor, attemptNumber, failed.message());
                    } catch (RuntimeException e) {
                        LOG.warn("dispatch process: schedule retry failed, job_id={}", job.id(), e);
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
    private static String bearerToken(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        String token = header.startsWith(prefix) ? header.substring(prefix.length()) : header;
        return token.isEmpty() ? null : token;
    }

    private static void ack(Context ctx, int status, boolean ackValue, String message) {
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
