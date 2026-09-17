package io.flowcatalyst.router.queue.sqs;

import tools.jackson.core.JacksonException;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.ConsumerBuild;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.Message;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/// SQS-backed [Consumer] (`docs/spec/router.md` §7.2).
///
/// SQS is at-least-once and standard (non-FIFO) queues have no dedup, so a
/// message this consumer has already acked can be redelivered before its
/// `MessageId` is forgotten by AWS. Two in-memory maps carry the bookkeeping
/// that makes that safe:
///
///   - [#pendingDelete]: `MessageId -> ackedAt`, checked on every poll so a
///     redelivery of an already-acked message is deleted immediately instead
///     of being routed to the mediator a second time.
///   - [#receiptToMessageId]: `receiptHandle -> (MessageId, polledAt)`, kept
///     only long enough to answer "did I already deliver this receipt", and
///     pruned on a size threshold rather than by age (unlike the map above)
///     because a live poll can hand out far more receipts than acked
///     MessageIds in the same window.
///
/// Both maps are bounded by [#PENDING_DELETE_TTL] / [#RECEIPT_MAP_PRUNE_THRESHOLD]
/// — see [#pruneMapsLocked()] for the two different pruning rules.
public final class SqsQueue implements Consumer {

    private static final Logger log = LoggerFactory.getLogger(SqsQueue.class);

    /// How long an acked `MessageId` is remembered so a redelivery is
    /// short-circuited to a plain delete instead of being handed back out.
    static final Duration PENDING_DELETE_TTL = Duration.ofMinutes(15);

    /// [#receiptToMessageId] is only pruned once it grows past this many
    /// entries — it is expected to churn on its own as receipts are acked,
    /// so age-based pruning on every poll (like [#pendingDelete]) would just
    /// be wasted work in the common case.
    static final int RECEIPT_MAP_PRUNE_THRESHOLD = 1000;

    static final int DEFAULT_VISIBILITY_TIMEOUT_SECONDS = 30;

    /// SQS's own ceiling on a message's total invisibility, in whole seconds
    /// (12 hours) — [#nack]'s clamp (R3, owner ruling 2026-09-17,
    /// `docs/spec/router-deferral-handback.md`).
    static final long MAX_VISIBILITY_SECONDS = 43_200;

    /// AWS's maximum `WaitTimeSeconds` — also the router's expected poll
    /// block time (`docs/spec/router.md` §3.2).
    static final int WAIT_TIME_SECONDS = 20;

    private static final int MAX_RECEIVE_BATCH = 10;

    private final SqsClient client;
    private final String queueUrl;
    private final String identifier;
    private final int visibilityTimeoutSeconds;
    private final Clock clock;

    /// Guards both maps below. Held only for map mutation/lookup, never
    /// across a network call.
    private final Object mapLock = new Object();
    private final Map<String, Instant> pendingDelete = new HashMap<>();
    private final Map<String, ReceiptMapping> receiptToMessageId = new HashMap<>();

    /// Set once by [#close()]; never cleared. Plain `volatile` is enough —
    /// it is read-mostly and the single writer only ever transitions
    /// false -> true.
    private volatile boolean stopped = false;

    /// True while SQS calls are failing, so a stack trace is logged once per
    /// streak instead of once per message: an outage fails the delete for
    /// every message in flight, and a trace each time is volume rather than
    /// information. Cleared by the next delete that succeeds.
    private volatile boolean sqsFailing = false;

    private final AtomicLong polled = new AtomicLong();
    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong nacked = new AtomicLong();

    private record ReceiptMapping(String messageId, Instant polledAt) {
    }

    public SqsQueue(SqsClient client, String queueUrl, String configuredName, int visibilityTimeoutSeconds, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.queueUrl = Objects.requireNonNull(queueUrl, "queueUrl");
        this.identifier = (configuredName != null && !configuredName.isBlank())
                ? configuredName
                : queueNameFromUrl(queueUrl);
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds > 0
                ? visibilityTimeoutSeconds
                : DEFAULT_VISIBILITY_TIMEOUT_SECONDS;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// Builds the real client: region from the queue URL's host when it
    /// looks like an SQS endpoint, otherwise the SDK's default region chain
    /// (§7.2 "Build"). An SQS queue must be reached in its own region, and
    /// this works even when `AWS_REGION`/`AWS_DEFAULT_REGION` isn't set in
    /// the environment.
    public static SqsQueue create(String queueUrl, String configuredName, int visibilityTimeoutSeconds) {
        var builder = SqsClient.builder();
        regionFromUrl(queueUrl).ifPresent(region -> builder.region(Region.of(region)));
        return adopt(builder.build(), queueUrl, configuredName, visibilityTimeoutSeconds);
    }

    /// Wraps a freshly built client, closing it if the wrapping fails.
    ///
    /// The client owns an HTTP connection pool and its threads, and until the
    /// constructor returns nothing holds a reference to it. A queue URL the
    /// constructor rejects would therefore strand one — and [QueueFactory]
    /// turns that throw into an empty `Optional` and lets the reconfigure
    /// loop retry the same queue on the next config poll, so the strand
    /// repeats for the life of the process rather than happening once.
    static SqsQueue adopt(SqsClient client, String queueUrl, String configuredName, int visibilityTimeoutSeconds) {
        try {
            return new SqsQueue(client, queueUrl, configuredName, visibilityTimeoutSeconds, Clock.systemUTC());
        } catch (RuntimeException e) {
            try {
                client.close();
            } catch (RuntimeException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    /// The production [io.flowcatalyst.router.manager.RouterManager.ConsumerFactory]
    /// entry point for SQS (owner ruling 2026-09-11, `docs/spec/router.md`
    /// §7.2): builds its own region-aware client, exactly like [#create],
    /// then checks the queue exists before adopting it.
    public static ConsumerBuild createChecked(String queueUrl, String configuredName, int visibilityTimeoutSeconds) {
        var builder = SqsClient.builder();
        regionFromUrl(queueUrl).ifPresent(region -> builder.region(Region.of(region)));
        return checkedAdopt(builder.build(), queueUrl, configuredName, visibilityTimeoutSeconds);
    }

    /// As [#createChecked], but over an already-built client — the hook
    /// [io.flowcatalyst.router.queue.sqs.SqsQueueTest] uses with a scripted
    /// fake client, since there is no SQS in the test environment and
    /// CONVENTIONS §7 rules out a mocking library.
    ///
    /// Building a consumer for an SQS queue first checks the queue exists
    /// (`GetQueueAttributes` on its URL — [#exists]). Missing ⇒ no consumer
    /// is built at all: [ConsumerBuild.Missing], a third outcome distinct
    /// from built and failed, never a warning-raising failure.
    ///
    /// A non-"does not exist" failure of the existence check itself
    /// (network, throttling, auth) is deliberately **not** treated as
    /// missing: the consumer is adopted exactly as if no check had been
    /// made, so a transient AWS error can never silently stop consumption —
    /// the ordinary poll-failure path (and its CONNECTION warning) covers a
    /// genuine outage once polling starts.
    static ConsumerBuild checkedAdopt(SqsClient client, String queueUrl, String configuredName,
                                      int visibilityTimeoutSeconds) {
        try {
            if (!exists(client, queueUrl)) {
                client.close();
                return ConsumerBuild.MISSING;
            }
        } catch (RuntimeException e) {
            log.atDebug().setMessage("sqs queue-existence check failed; building the consumer anyway")
                    .addKeyValue("url", queueUrl)
                    .addKeyValue("reason", e.toString())
                    .log();
        }
        return ConsumerBuild.of(adopt(client, queueUrl, configuredName, visibilityTimeoutSeconds));
    }

    /// Whether `queueUrl` currently exists on the broker. `GetQueueAttributes`
    /// is the cheapest call that both confirms existence and reaches the
    /// broker at all — `QueueDoesNotExistException` is the SDK's own signal
    /// for "no such queue" and is the only outcome this treats as absence;
    /// every other exception propagates for the caller to judge.
    static boolean exists(SqsClient client, String queueUrl) {
        try {
            client.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build());
            return true;
        } catch (SqsException e) {
            if (isQueueMissing(e)) {
                return false;
            }
            throw e;
        }
    }

    /// Both spellings AWS uses for "no such queue": the JSON protocol's
    /// `QueueDoesNotExist` (which the SDK maps to [QueueDoesNotExistException])
    /// and the legacy query-protocol `AWS.SimpleQueueService.NonExistentQueue`,
    /// the code the staging router actually logged (2026-09-04). Matching the
    /// raw code too means a gap in the SDK's mapping cannot turn an expected
    /// absence back into a paging CONNECTION warning.
    ///
    /// Public for the same reason as [#regionFromUrl]:
    /// [io.flowcatalyst.platform.scheduler.SqsDispatchPublisher] needs the
    /// exact same missing-queue detection for the lazy-creation path
    /// (`docs/spec/deployed-dispatch.md` §3 settled item 3) and must not grow
    /// a second, possibly-divergent copy of this AWS-spelling table.
    public static boolean isQueueMissing(SqsException e) {
        if (e instanceof QueueDoesNotExistException) {
            return true;
        }
        var details = e.awsErrorDetails();
        var code = details == null ? null : details.errorCode();
        return "QueueDoesNotExist".equals(code) || "AWS.SimpleQueueService.NonExistentQueue".equals(code);
    }

    /// Extracts the AWS region from an SQS queue URL whose host is
    /// `sqs.<region>.amazonaws.com` (or `sqs-fips.<region>.amazonaws.com[.cn]`).
    /// Empty when `uri` isn't a recognisable SQS endpoint (e.g. a non-AWS
    /// test URI, or a `LocalStack` host).
    ///
    /// Public so [io.flowcatalyst.platform.dispatch.DispatchQueueSettings]
    /// can derive the region for the platform's own composed SQS queue URLs
    /// from `FC_DISPATCH_QUEUE_URL` without a second parser
    /// (`docs/spec/deployed-dispatch.md` §3).
    public static Optional<String> regionFromUrl(String uri) {
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = host.split("\\.");
        if (parts.length >= 4 && parts[0].startsWith("sqs") && parts[2].equals("amazonaws")) {
            return Optional.of(parts[1]);
        }
        return Optional.empty();
    }

    /// The last `/`-separated segment of the queue URL, used as the
    /// identifier when no name is configured.
    static String queueNameFromUrl(String url) {
        String[] parts = url.split("/", -1);
        if (parts.length == 0) {
            return "unknown";
        }
        return parts[parts.length - 1];
    }

    @Override
    public String identifier() {
        return identifier;
    }

    @Override
    public PollResult poll(int max) throws InterruptedException {
        if (stopped) {
            return PollResult.STOPPED;
        }
        if (Thread.interrupted()) {
            throw new InterruptedException("sqs poll interrupted before ReceiveMessage");
        }

        int batchSize = Math.min(max, MAX_RECEIVE_BATCH);
        ReceiveMessageResponse response;
        try {
            response = client.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(batchSize)
                    .visibilityTimeout(visibilityTimeoutSeconds)
                    .waitTimeSeconds(WAIT_TIME_SECONDS)
                    .messageSystemAttributeNames(MessageSystemAttributeName.ALL)
                    .messageAttributeNames("All")
                    .build());
        } catch (RuntimeException e) {
            if (causedByInterruption(e)) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("sqs ReceiveMessage interrupted");
            }
            if (e instanceof SqsException sqs && isQueueMissing(sqs)) {
                // The queue was deleted after this consumer started polling it
                // (owner ruling 2026-09-11, `docs/spec/router.md` §7.2): an
                // expected outcome, not a broker failure — no CONNECTION
                // warning. ConsumerLoop ends its loop and detaches this consumer
                // so the next config sync rechecks the queue.
                return PollResult.QUEUE_MISSING;
            }
            throw e;
        }

        List<software.amazon.awssdk.services.sqs.model.Message> messages = response.messages();
        if (messages.isEmpty()) {
            return PollResult.empty();
        }

        pruneMapsLocked();

        List<QueuedMessage> results = new ArrayList<>(messages.size());
        for (var sqsMessage : messages) {
            String messageId = sqsMessage.messageId();
            String receiptHandle = sqsMessage.receiptHandle();

            if (messageId != null) {
                boolean alreadyAcked;
                synchronized (mapLock) {
                    alreadyAcked = pendingDelete.containsKey(messageId);
                }
                if (alreadyAcked) {
                    // Redelivery of a message we already acked — get rid of
                    // it again without routing it to the mediator a second
                    // time. Not counted as acked: it already was.
                    if (receiptHandle != null) {
                        deleteQuietly(receiptHandle);
                    }
                    continue;
                }
            }

            Message parsed = parseBody(sqsMessage.body());
            if (parsed == null) {
                // Malformed or empty body: it can never be delivered and
                // would otherwise redeliver forever, so it is acked and
                // dropped rather than returned (§7.2 Poll row).
                if (receiptHandle != null) {
                    deleteAndCount(receiptHandle);
                }
                continue;
            }
            if (receiptHandle == null) {
                // No token to ever acknowledge this delivery with — nothing
                // safe to do but drop it; it will be redelivered and this
                // consumer will try again once SQS supplies a handle.
                log.atWarn().setMessage("sqs message had no receipt handle; dropping")
                        .addKeyValue("message_id", messageId)
                        .addKeyValue("queue", identifier)
                        .log();
                continue;
            }

            if (messageId != null) {
                synchronized (mapLock) {
                    receiptToMessageId.put(receiptHandle, new ReceiptMapping(messageId, Instant.now(clock)));
                }
            }
            results.add(QueuedMessage.of(parsed, messageId == null ? "" : messageId, receiptHandle, identifier));
        }

        if (!results.isEmpty()) {
            polled.addAndGet(results.size());
        }
        return PollResult.of(results);
    }

    private Message parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readValue(body, Message.class);
        } catch (JacksonException e) {
            // No cause: a Jackson parse failure's trace is the same frames
            // every time, and this fires once per bad message. The message —
            // what failed and where — is the whole of the information.
            log.atWarn().setMessage("sqs malformed message body")
                    .addKeyValue("queue", identifier)
                    .addKeyValue("reason", e.getMessage())
                    .log();
            return null;
        }
    }

    @Override
    public boolean ack(QueuedMessage message) {
        try {
            String receiptHandle = message.receiptHandle();
            synchronized (mapLock) {
                receiptToMessageId.remove(receiptHandle);
            }
            String messageId = message.brokerMessageId();
            if (messageId != null && !messageId.isBlank()) {
                synchronized (mapLock) {
                    pendingDelete.put(messageId, Instant.now(clock));
                }
            }
            deleteAndCount(receiptHandle);
            return true;
        } catch (RuntimeException e) {
            // Ack must never throw (Consumer#ack) — a broker hiccup here
            // cannot be allowed to fail a delivery that already succeeded.
            transportFailure("sqs ack failed", e).addKeyValue("message_id", message.id()).log();
            return false;
        }
    }

    /// Honours the delay via `ChangeMessageVisibility` (R3, owner ruling
    /// 2026-09-17, `docs/spec/router-deferral-handback.md`).
    ///
    /// This used to be a no-op: the router retried a failing message
    /// in-process, keeping it in its group's pipeline with its own backoff
    /// rather than releasing it to the broker, so shortening SQS's own
    /// visibility timeout here would have let SQS redeliver the message
    /// while this process was still retrying it — a concurrent duplicate.
    /// That is no longer the shape of every call site: a deferral naming a
    /// delay (R1/R2) is now handed back on its *first* occurrence rather
    /// than retried, and every [io.flowcatalyst.router.pool.Broker#nack]
    /// call is a hand-back regardless — [io.flowcatalyst.router.manager.QueueBroker#nack]
    /// removes the tracker entry before this method ever runs. By the time
    /// this call happens the router has already given up ownership, so a
    /// redelivery once the delay elapses is a fresh delivery, not a
    /// duplicate of a retry still running here.
    ///
    /// `delay` is floored at zero and clamped to [#MAX_VISIBILITY_SECONDS],
    /// measured from when this consumer first polled the receipt (SQS counts
    /// its own ceiling from the original receive, not from this call) —
    /// [#receiptToMessageId] carries that instant; a receipt already pruned
    /// or never recorded gets the full ceiling. Best-effort, per the
    /// [io.flowcatalyst.router.queue.Acknowledger] contract: a failure (a
    /// stale receipt, `ReceiptHandleIsInvalid`) is logged at WARN and
    /// swallowed, and the message returns at its natural visibility timeout —
    /// the same outcome this method always had. `nacked` counts every call
    /// whether or not the broker confirmed it, same as [#ack].
    @Override
    public void nack(QueuedMessage message, Duration delay) {
        try {
            long seconds = (delay == null || delay.isNegative()) ? 0 : delay.toSeconds();
            long clamped = Math.min(seconds, remainingVisibilitySeconds(message.receiptHandle()));
            client.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout((int) clamped)
                    .build());
        } catch (RuntimeException e) {
            transportFailure("sqs ChangeMessageVisibility failed", e).addKeyValue("message_id", message.id()).log();
        } finally {
            nacked.incrementAndGet();
        }
    }

    /// How much of SQS's [#MAX_VISIBILITY_SECONDS] ceiling `receiptHandle`
    /// has left, counted from when it was polled. A receipt not currently in
    /// [#receiptToMessageId] (pruned, or never recorded because the delivery
    /// carried no `MessageId`) gets the full ceiling — there is nothing here
    /// to say it should be any shorter.
    private long remainingVisibilitySeconds(String receiptHandle) {
        Instant polledAt;
        synchronized (mapLock) {
            var mapping = receiptToMessageId.get(receiptHandle);
            polledAt = mapping == null ? null : mapping.polledAt();
        }
        if (polledAt == null) {
            return MAX_VISIBILITY_SECONDS;
        }
        long elapsed = Duration.between(polledAt, Instant.now(clock)).toSeconds();
        return Math.max(0, MAX_VISIBILITY_SECONDS - elapsed);
    }

    @Override
    public Optional<QueueMetrics> metrics() {
        GetQueueAttributesResponse response;
        try {
            response = client.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                    .build());
        } catch (RuntimeException e) {
            // Broker-side metrics are a courtesy, not load-bearing — a
            // round-trip failure here is tolerated (§7.1 Metrics row).
            log.debug("sqs GetQueueAttributes failed for queue {}: {}", identifier, e.toString());
            return Optional.empty();
        }
        long pending = parseAttribute(response, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        long inFlight = parseAttribute(response, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE);
        return Optional.of(new QueueMetrics(pending, inFlight, polled.get(), acked.get(), nacked.get()));
    }

    private static long parseAttribute(GetQueueAttributesResponse response, QueueAttributeName name) {
        String value = response.attributes().get(name);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public void close() {
        // Flag only — no client close (§7.2 "Stop"). SqsQueue does not
        // assume it is the sole owner of the SqsClient it was built with.
        stopped = true;
    }

    /// Deletes without counting it as an ack — used for a redelivery of a
    /// message this consumer already acked (§7.2 Poll row). Never throws.
    private void deleteQuietly(String receiptHandle) {
        try {
            client.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build());
        } catch (RuntimeException e) {
            transportFailure("sqs DeleteMessage (redelivery cleanup) failed", e).log();
        }
    }

    /// Deletes and counts it as an ack on success only. Used both by the
    /// public [#ack] and by the malformed-body skip path in [#poll]. Never
    /// throws.
    private void deleteAndCount(String receiptHandle) {
        try {
            client.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build());
            acked.incrementAndGet();
            sqsFailing = false;
        } catch (RuntimeException e) {
            transportFailure("sqs DeleteMessage failed", e).log();
        }
    }

    /// Test-only window into the map sizes. `ack` resolves the acked
    /// message's id from the [QueuedMessage] it is given rather than from
    /// [#receiptToMessageId] (see the class doc), so that map's pruning has
    /// no other externally observable effect — this is how the tests verify
    /// it without reaching into the field directly.
    int receiptMapSizeForTest() {
        synchronized (mapLock) {
            return receiptToMessageId.size();
        }
    }

    int pendingDeleteSizeForTest() {
        synchronized (mapLock) {
            return pendingDelete.size();
        }
    }

    /// The two pruning rules differ (§7.2 "Maps" row):
    ///
    ///   - [#pendingDelete] is pruned of entries older than
    ///     [#PENDING_DELETE_TTL] on every non-empty poll.
    ///   - [#receiptToMessageId] is pruned of entries older than the same
    ///     TTL, but only once it exceeds [#RECEIPT_MAP_PRUNE_THRESHOLD]
    ///     entries — it does not need age-based pruning on the common path
    ///     because acking a message already removes its entry.
    private void pruneMapsLocked() {
        Instant now = Instant.now(clock);
        synchronized (mapLock) {
            pendingDelete.entrySet().removeIf(e -> Duration.between(e.getValue(), now).compareTo(PENDING_DELETE_TTL) > 0);
            if (receiptToMessageId.size() > RECEIPT_MAP_PRUNE_THRESHOLD) {
                receiptToMessageId.entrySet()
                        .removeIf(e -> Duration.between(e.getValue().polledAt(), now).compareTo(PENDING_DELETE_TTL) > 0);
            }
        }
    }

    /// Interruption during a blocking SDK call doesn't surface as
    /// [InterruptedException] directly — the SDK's HTTP layer wraps it. This
    /// walks the cause chain so cancellation-by-interruption (CONVENTIONS §8)
    /// still works for a call that has no checked exception of its own.
/// A transport failure: the cause on the first of a streak, its `toString`
    /// afterwards. Callers add their own fields and call `log()`.
    private LoggingEventBuilder transportFailure(String message, Throwable e) {
        var event = log.atWarn().setMessage(message).addKeyValue("queue", identifier);
        if (sqsFailing) {
            return event.addKeyValue("reason", String.valueOf(e));
        }
        sqsFailing = true;
        return event.setCause(e);
    }

        private static boolean causedByInterruption(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
