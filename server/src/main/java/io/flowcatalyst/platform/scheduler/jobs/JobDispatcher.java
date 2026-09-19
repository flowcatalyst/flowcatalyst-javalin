package io.flowcatalyst.platform.scheduler.jobs;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstance;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.TriggerKind;
import io.flowcatalyst.platform.scheduler.jobs.jfr.JobFiredEvent;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.wire.WebhookSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/// The dispatcher (`docs/spec/scheduled-job-scheduler.md` §3): delivers up to
/// `batchSize` `QUEUED` instances a tick, oldest first, jobs loaded once per
/// tick per id (memoised in [#dispatchOnce]).
public final class JobDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(JobDispatcher.class);

    /// Step 3's exact message (spec §3).
    static final String NO_TARGET_URL = "No target URL configured for job";

    /// Step 1's exact message (spec §3).
    static final String ORPHAN = "ScheduledJob no longer exists";

    private final ScheduledJobRepository jobs;
    private final ScheduledJobInstanceRepository instances;
    private final HttpClient client;
    private final Duration timeout;
    private final Function<String, Optional<OutboundCredentials>> credentials;
    private final BooleanSupplier leader;
    private final int batchSize;
    private final Clock clock;

    public JobDispatcher(ScheduledJobRepository jobs, ScheduledJobInstanceRepository instances, HttpClient client,
                          Duration timeout, Function<String, Optional<OutboundCredentials>> credentials,
                          BooleanSupplier leader, int batchSize, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.batchSize = batchSize;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// Runs one tick. A non-leader tick is a no-op, not an error (spec §1).
    public void dispatchOnce() {
        if (!leader.getAsBoolean()) {
            return;
        }
        List<ScheduledJobInstance> batch = instances.listQueuedOldestFirst(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        Map<String, Optional<ScheduledJob>> jobCache = new HashMap<>();
        for (ScheduledJobInstance instance : batch) {
            dispatchOne(instance, jobCache);
        }
    }

    private void dispatchOne(ScheduledJobInstance instance, Map<String, Optional<ScheduledJob>> jobCache) {
        Optional<ScheduledJob> job = jobCache.computeIfAbsent(instance.scheduledJobId(), jobs::findById);
        if (job.isEmpty()) {
            // Step 1: orphan — no markInFlight; markDeliveryFailed directly, terminal.
            try {
                instances.markDeliveryFailed(instance.id(), ORPHAN, true);
                recordFired(instance.id(), instance.jobCode(), 0, "ORPHAN", true, 0, false);
                LOG.atWarn().setMessage("delivery exhausted retries")
                        .addKeyValue("instance_id", instance.id())
                        .addKeyValue("reason", ORPHAN)
                        .log();
            } catch (RuntimeException e) {
                LOG.atWarn().setMessage("failed to mark orphan instance DELIVERY_FAILED; left for next tick")
                        .addKeyValue("instance_id", instance.id())
                        .setCause(e)
                        .log();
            }
            return;
        }
        ScheduledJob j = job.get();
        int attemptsAfter;
        try {
            attemptsAfter = instances.markInFlight(instance.id());
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("markInFlight failed for scheduled job instance; left for next tick")
                    .addKeyValue("instance_id", instance.id())
                    .setCause(e)
                    .log();
            return;
        }
        if (j.targetUrl() == null || j.targetUrl().isBlank()) {
            fail(instance.id(), instance.jobCode(), NO_TARGET_URL, attemptsAfter, j.deliveryMaxAttempts(), false, 0);
            return;
        }
        deliver(j, instance, attemptsAfter);
    }

    private void deliver(ScheduledJob job, ScheduledJobInstance instance, int attemptsAfter) {
        byte[] body = Json.write(envelope(job, instance)).getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(job.targetUrl()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
        } catch (RuntimeException e) {
            fail(instance.id(), instance.jobCode(), "Network/HTTP error: " + e.getMessage(), attemptsAfter,
                    job.deliveryMaxAttempts(), false, 0);
            return;
        }
        // Whether the signature header was actually added — "signed" on the
        // flight-recorder event means exactly this, not merely that a bearer
        // token was attached.
        boolean signed = applyCredentials(builder, job, body);

        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            fail(instance.id(), instance.jobCode(), "Network/HTTP error: " + e.getMessage(), attemptsAfter,
                    job.deliveryMaxAttempts(), signed, 0);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                instances.markDelivered(instance.id());
            } catch (RuntimeException e) {
                LOG.atWarn().setMessage("markDelivered failed for scheduled job instance; left for next tick")
                        .addKeyValue("instance_id", instance.id())
                        .setCause(e)
                        .log();
                return;
            }
            recordFired(instance.id(), instance.jobCode(), attemptsAfter, "DELIVERED", true, status, signed);
            return;
        }
        byte[] responseBody = response.body() == null ? new byte[0] : response.body();
        String snippet = new String(responseBody, 0, Math.min(responseBody.length, 500), StandardCharsets.UTF_8);
        fail(instance.id(), instance.jobCode(), "HTTP " + status + " (expected 2xx): " + snippet, attemptsAfter,
                job.deliveryMaxAttempts(), signed, status);
    }

    /// Step 7: `terminal = attemptsAfter >= deliveryMaxAttempts`. The
    /// flight-recorder event is committed only after `markDeliveryFailed`
    /// has returned — never before, and not at all if it threw, since then
    /// nothing is actually known to have happened to the row.
    private void fail(String instanceId, String jobCode, String message, int attemptsAfter, int deliveryMaxAttempts,
                       boolean signed, int statusCode) {
        boolean terminal = attemptsAfter >= deliveryMaxAttempts;
        try {
            instances.markDeliveryFailed(instanceId, message, terminal);
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("markDeliveryFailed failed for scheduled job instance; left for next tick")
                    .addKeyValue("instance_id", instanceId)
                    .setCause(e)
                    .log();
            return;
        }
        recordFired(instanceId, jobCode, attemptsAfter, "FAILED", terminal, statusCode, signed);
        if (terminal) {
            LOG.atWarn().setMessage("delivery exhausted retries")
                    .addKeyValue("instance_id", instanceId)
                    .addKeyValue("reason", message)
                    .log();
        }
    }

    /// Records the firing's outcome, if anyone is recording
    /// (`docs/spec/jfr-events.md` §3). `shouldCommit()` first so a disabled
    /// recording costs one virtual call and no field writes.
    private static void recordFired(String instanceId, String jobCode, int attempt, String outcome, boolean terminal,
                                     int statusCode, boolean signed) {
        var event = new JobFiredEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.instanceId = instanceId;
        event.jobCode = jobCode;
        event.attempt = attempt;
        event.outcome = outcome;
        event.terminal = terminal;
        event.statusCode = statusCode;
        event.signed = signed;
        event.commit();
    }

    /// Step 5: the five degraded-credentials cases all deliver anyway,
    /// WARN-logged.
    ///
    /// @return whether the signature header (`X-FlowCatalyst-Signature`) was
    /// actually added — the fact the flight-recorder event's `signed` field
    /// needs, distinct from whether a bearer token was attached.
    private boolean applyCredentials(HttpRequest.Builder builder, ScheduledJob job, byte[] body) {
        String applicationId = job.applicationId();
        if (applicationId == null || applicationId.isBlank()) {
            LOG.atWarn().setMessage("scheduled job has no application linkage; delivering unsigned — "
                            + "re-sync the job from its application")
                    .addKeyValue("code", job.code())
                    .log();
            return false;
        }
        Optional<OutboundCredentials> resolved;
        try {
            resolved = credentials.apply(applicationId);
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("outbound credentials lookup failed for scheduled job; delivering unsigned")
                    .addKeyValue("code", job.code())
                    .setCause(e)
                    .log();
            return false;
        }
        if (resolved.isEmpty()) {
            LOG.atWarn().setMessage("scheduled job has no active service account credentials; delivering unsigned")
                    .addKeyValue("code", job.code())
                    .log();
            return false;
        }
        OutboundCredentials creds = resolved.get();
        boolean hasToken = creds.token() != null && !creds.token().isEmpty();
        boolean hasSecret = creds.signingSecret() != null && !creds.signingSecret().isEmpty();
        if (hasSecret && !hasToken) {
            LOG.atWarn().setMessage("scheduled job has a signing secret but no bearer token; delivering with signature only")
                    .addKeyValue("code", job.code())
                    .log();
        } else if (hasToken && !hasSecret) {
            LOG.atWarn().setMessage("scheduled job has a bearer token but no signing secret; delivering unsigned")
                    .addKeyValue("code", job.code())
                    .log();
        }
        if (hasToken) {
            builder.header("Authorization", "Bearer " + creds.token());
        }
        if (hasSecret) {
            String timestamp = WebhookSigner.timestamp(clock.instant());
            String signature = signFiring(creds.signingSecret(), timestamp, body);
            builder.header("X-FlowCatalyst-Signature", signature);
            builder.header("X-FlowCatalyst-Timestamp", timestamp);
        }
        return hasSecret;
    }

    /// `hex(HMAC-SHA256(secret, timestamp || body))` (spec §3 step 5) — the
    /// same contract [WebhookSigner] already implements for the router, so
    /// this is reuse, not a second implementation, kept as a small named,
    /// pure static function so the dispatcher's test can call it directly.
    static String signFiring(String secret, String timestamp, byte[] body) {
        return WebhookSigner.sign(secret, timestamp, body);
    }

    private static WebhookEnvelope envelope(ScheduledJob job, ScheduledJobInstance instance) {
        return new WebhookEnvelope(job.id(), job.code(), instance.id(), instance.scheduledFor(), instance.firedAt(),
                instance.triggerKind(), instance.correlationId(), job.payload(), job.tracksCompletion(),
                job.timeoutSeconds(), job.concurrent());
    }

    /// The webhook envelope (spec §3 step 4), pinned byte-for-byte by
    /// `TestWebhookEnvelope_CamelCaseKeys`: field names verbatim, camelCase.
    /// Optional fields (`scheduledFor`, `correlationId`, `payload`,
    /// `timeoutSeconds`) are `null` and dropped by [Json]'s `NON_ABSENT`
    /// inclusion; `tracksCompletion` and `concurrent` are primitives and
    /// always written.
    record WebhookEnvelope(String jobId, String jobCode, String instanceId, Instant scheduledFor, Instant firedAt,
                           TriggerKind triggerKind, String correlationId, JsonNode payload, boolean tracksCompletion,
                           Integer timeoutSeconds, boolean concurrent) {
    }
}
