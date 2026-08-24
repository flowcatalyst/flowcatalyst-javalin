package io.flowcatalyst.router.queue.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

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
        return new SqsQueue(builder.build(), queueUrl, configuredName, visibilityTimeoutSeconds, Clock.systemUTC());
    }

    /// Extracts the AWS region from an SQS queue URL whose host is
    /// `sqs.<region>.amazonaws.com` (or `sqs-fips.<region>.amazonaws.com[.cn]`).
    /// Empty when `uri` isn't a recognisable SQS endpoint (e.g. a non-AWS
    /// test URI, or a `LocalStack` host).
    static Optional<String> regionFromUrl(String uri) {
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
                log.warn("sqs message {} on queue {} had no receipt handle; dropping", messageId, identifier);
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
        } catch (JsonProcessingException e) {
            log.warn("sqs malformed message body on queue {}: {}", identifier, e.getMessage());
            return null;
        }
    }

    @Override
    public void ack(QueuedMessage message) {
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
        } catch (RuntimeException e) {
            // Ack must never throw (Consumer#ack) — a broker hiccup here
            // cannot be allowed to fail a delivery that already succeeded.
            log.warn("sqs ack failed for queue {} message {}: {}", identifier, message.id(), e.toString());
        }
    }

    /// Deliberately a no-op beyond the counter (§7.2 Nack/Defer row). The
    /// router retries failed messages in-process — it keeps a failing
    /// message in its group's pipeline with its own backoff rather than
    /// releasing it back to the broker — so this must NOT shorten SQS's
    /// visibility timeout. Doing so would let SQS redeliver the message (to
    /// this consumer or another replica) while the router is still retrying
    /// it in memory, producing a concurrent duplicate delivery. Instead the
    /// message simply stays invisible until its own visibility timeout
    /// lapses naturally; any redelivery that follows is either deduplicated
    /// by broker `MessageId` upstream (the in-flight tracker swaps the fresh
    /// receipt handle onto the copy it is already tracking) or, once this
    /// consumer has since acked the message, short-circuited by
    /// [#pendingDelete] in [#poll]. `delay` is ignored by design; the
    /// counter exists for observability only.
    @Override
    public void nack(QueuedMessage message, Duration delay) {
        nacked.incrementAndGet();
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
            log.warn("sqs DeleteMessage (redelivery cleanup) failed for queue {}: {}", identifier, e.toString());
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
        } catch (RuntimeException e) {
            log.warn("sqs DeleteMessage failed for queue {}: {}", identifier, e.toString());
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
    private static boolean causedByInterruption(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
