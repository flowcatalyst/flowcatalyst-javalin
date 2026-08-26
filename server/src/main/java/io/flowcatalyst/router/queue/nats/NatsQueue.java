package io.flowcatalyst.router.queue.nats;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.Message;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.FetchConsumeOptions;
import io.nats.client.FetchConsumer;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/// NATS JetStream-backed [Consumer] — a pull consumer against a
/// WorkQueue-retention stream (`docs/spec/router.md` §7.4), matching the Go
/// `queue/nats/nats.go` backend.
///
/// A durable pull consumer, provisioned (create-or-update) at construction
/// time, is fetched from in batches bounded by `max-messages` and
/// `poll-timeout-ms`. Every fetched message is held in [#pending], keyed by
/// its receipt handle, until [#ack] or [#nack] resolves it — a redelivery
/// after a dropped connection is simply a fresh entry under a fresh receipt.
///
/// ### Malformed payloads
/// A message whose JetStream metadata can't be read, or whose body isn't
/// valid [Message] JSON, is termed (`Message#term()`) rather than delivered:
/// termed messages are neither acked nor nacked, so they never redeliver
/// (§7.1, §7.4). [#classify] makes this decision as a pure function so it is
/// testable without a live broker — see `NatsQueueTest`.
public final class NatsQueue implements Consumer {

    private static final Logger log = LoggerFactory.getLogger(NatsQueue.class);

    private final String identifier;
    private final NatsQueueUri config;
    private final Connection connection;
    private final ConsumerContext consumerContext;

    /// Fetched, not yet resolved. Package-private so tests can seed and
    /// inspect it directly without a live broker (CONVENTIONS §6: hand-write
    /// seams, no mocking library).
    final Map<String, io.nats.client.Message> pending = new ConcurrentHashMap<>();

    /// Set by [#close]; checked at the top of every [#poll] (CONVENTIONS §5:
    /// an explicit stop signal, never a silently-closed resource).
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    // Process-local counters (`queue.Metrics` contract, §2.9) — no
    // round-trip. Package-private, alongside `pending`, so tests can read
    // them directly without a live broker.
    final AtomicLong polled = new AtomicLong();
    final AtomicLong acked = new AtomicLong();
    final AtomicLong nacked = new AtomicLong();

    /// Connects, provisions the stream and durable consumer (create-or-update,
    /// matching Go's `CreateOrUpdateStream`/`CreateOrUpdateConsumer`), and
    /// resolves the pull-consumer context.
    ///
    /// @throws NatsQueueException connecting or provisioning failed
    public NatsQueue(String queueUri) {
        this.config = NatsQueueUri.parse(queueUri);
        this.identifier = config.identifier();
        Resources resources = connect(config, queueUri);
        this.connection = resources.connection();
        this.consumerContext = resources.consumerContext();
    }

    /// Test-only seam: builds an instance with no live NATS connection.
    /// [#connection] and [#consumerContext] stay `null`; the only production
    /// paths that dereference them are [#poll] while running and
    /// [#metrics] — both guard against the test seam by checking `null` or
    /// [#stopped] first, so `ack`/`nack`/`close`/stopped-`poll` are all
    /// exercisable here (see `NatsQueueTest`).
    NatsQueue(String identifier, NatsQueueUri config) {
        this.identifier = identifier;
        this.config = config;
        this.connection = null;
        this.consumerContext = null;
    }

    private record Resources(Connection connection, ConsumerContext consumerContext) {
    }

    /// Runs the provisioning that turns an open connection into a usable
    /// queue, closing that connection if the provisioning fails.
    ///
    /// Without this, a stream or consumer that cannot be provisioned — a name
    /// the account may not create, a JetStream-disabled server — leaves a live
    /// connection with **no reference to it**. Nothing notices: [QueueFactory]
    /// logs the failure and returns empty, and the reconfigure loop tries the
    /// same queue again on the next config poll. Since the connection is built
    /// with `maxReconnects(-1)`, each orphan also keeps reconnecting forever.
    /// One misconfigured queue therefore leaks a connection per poll, without
    /// bound, for as long as the process runs.
    /// Takes the connection's `close` rather than the connection so the
    /// behaviour is reachable from a test without standing up a broker.
    static <R> R adopting(Closing connection, Provisioning<R> provisioning)
            throws IOException, JetStreamApiException, InterruptedException {
        try {
            return provisioning.run();
        } catch (IOException | JetStreamApiException | InterruptedException | RuntimeException e) {
            try {
                connection.close();
            } catch (InterruptedException closing) {
                Thread.currentThread().interrupt();
                e.addSuppressed(closing);
            } catch (RuntimeException closing) {
                // Reporting why provisioning failed beats reporting why the
                // cleanup did; the original is the actionable one.
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    @FunctionalInterface
    interface Provisioning<R> {
        R run() throws IOException, JetStreamApiException, InterruptedException;
    }

    @FunctionalInterface
    interface Closing {
        void close() throws InterruptedException;
    }

    private static Resources connect(NatsQueueUri config, String queueUri) {
        try {
            Options options = new Options.Builder()
                    .servers(config.servers().toArray(new String[0]))
                    .connectionTimeout(Duration.ofSeconds(10))
                    .reconnectWait(Duration.ofSeconds(2))
                    .maxReconnects(-1)
                    .build();
            Connection connection = Nats.connect(options);
            // From here the connection is OPEN and nothing owns it yet — see
            // [#adopting].
            return adopting(connection::close, () -> {
                JetStreamManagement jsm = connection.jetStreamManagement();
                createOrUpdateStream(jsm, config);
                createOrUpdateConsumer(jsm, config);
                return new Resources(connection,
                        connection.getConsumerContext(config.streamName(), config.consumerName()));
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NatsQueueException("nats: interrupted while connecting to " + queueUri, e);
        } catch (IOException | JetStreamApiException e) {
            throw new NatsQueueException("nats: failed to initialise queue for " + queueUri, e);
        }
    }

    private static void createOrUpdateStream(JetStreamManagement jsm, NatsQueueUri config)
            throws IOException, JetStreamApiException {
        StorageType storageType = "memory".equalsIgnoreCase(config.storage()) ? StorageType.Memory : StorageType.File;
        StreamConfiguration streamConfig = StreamConfiguration.builder()
                .name(config.streamName())
                .subjects(config.subject())
                .retentionPolicy(RetentionPolicy.WorkQueue)
                .storageType(storageType)
                .replicas(config.replicas())
                .maxAge(config.maxAge())
                .build();
        try {
            jsm.addStream(streamConfig);
        } catch (JetStreamApiException e) {
            // Already exists — update in place, matching Go's create-or-update.
            jsm.updateStream(streamConfig);
        }
    }

    private static void createOrUpdateConsumer(JetStreamManagement jsm, NatsQueueUri config)
            throws IOException, JetStreamApiException {
        ConsumerConfiguration consumerConfig = ConsumerConfiguration.builder()
                .durable(config.consumerName())
                .ackWait(config.ackWait())
                .maxDeliver(config.maxDeliver())
                .maxAckPending(config.maxAckPending())
                .filterSubject(config.subject())
                .build();
        jsm.addOrUpdateConsumer(config.streamName(), consumerConfig);
    }

    @Override
    public String identifier() {
        return identifier;
    }

    @Override
    public PollResult poll(int max) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("interrupted before polling queue " + identifier);
        }
        if (stopped.get()) {
            return PollResult.STOPPED;
        }

        int batch = effectiveBatch(max, config.maxMessagesPerPoll());
        FetchConsumeOptions options = FetchConsumeOptions.builder()
                .maxMessages(batch)
                .expiresIn(config.pollTimeout().toMillis())
                .build();

        List<QueuedMessage> delivered = new ArrayList<>();
        // Closed by hand rather than with try-with-resources: FetchConsumer's
        // close() declares InterruptedException, so an implicit close can
        // throw one that masks whatever the body threw — including a
        // *different* InterruptedException, which would make an interrupt
        // during cleanup indistinguishable from one during the fetch.
        // Closing in a finally, and swallowing only the close's own failure,
        // keeps the body's outcome authoritative.
        FetchConsumer fetch;
        try {
            fetch = consumerContext.fetch(options);
        } catch (Exception e) {
            log.warn("nats: fetch failed on queue {}", identifier, e);
            return PollResult.empty();
        }
        try {
            io.nats.client.Message msg;
            while ((msg = fetch.nextMessage()) != null) {
                handleFetched(msg, delivered);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            // A real fetch error. A timeout waiting for messages is not one —
            // it simply yields an empty batch, same as Go.
            log.warn("nats: fetch failed on queue {}", identifier, e);
            return PollResult.empty();
        } finally {
            closeQuietly(fetch);
        }

        if (!delivered.isEmpty()) {
            polled.addAndGet(delivered.size());
        }
        return PollResult.of(delivered);
    }

    /// Closes a fetch without letting its failure replace the poll's own
    /// outcome. An interrupt during close is restored rather than swallowed.
    private void closeQuietly(FetchConsumer fetch) {
        try {
            fetch.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("nats: closing fetch failed on queue {}", identifier, e);
        }
    }

    /// Classifies one fetched message and acts on the verdict.
    ///
    /// Package-private so a test can drive the **action**, not just the
    /// decision: `classify` returning `Malformed` is worth nothing unless the
    /// message is actually termed, and an un-termed malformed message
    /// redelivers forever.
    void handleFetched(io.nats.client.Message msg, List<QueuedMessage> out) {
        boolean metadataOk;
        long streamSeq = 0;
        long consumerSeq = 0;
        try {
            var meta = msg.metaData();
            streamSeq = meta.streamSequence();
            consumerSeq = meta.consumerSequence();
            metadataOk = true;
        } catch (Exception e) {
            metadataOk = false;
        }

        apply(classify(metadataOk, streamSeq, consumerSeq, config.streamName(), msg.getData()), msg, out);
    }

    /// Acts on a classification.
    ///
    /// Separate from [#handleFetched] and package-private so both branches
    /// are testable: reading JetStream metadata cannot be faked without a
    /// live server, which would otherwise leave the *action* — terming a
    /// malformed message, holding a delivered one — asserted only by
    /// inspection. A malformed message that is classified but never termed
    /// redelivers forever.
    void apply(FetchOutcome outcome, io.nats.client.Message msg, List<QueuedMessage> out) {
        switch (outcome) {
            case FetchOutcome.Malformed ignored -> safeTerm(msg);
            case FetchOutcome.Deliver deliver -> {
                pending.put(deliver.receipt(), msg);
                out.add(QueuedMessage.of(deliver.payload(), deliver.brokerMessageId(), deliver.receipt(), identifier));
            }
        }
    }

    /// The number of messages to ask JetStream for this poll: `max`, capped
    /// to (and defaulted from, when `max <= 0`) the URI's `max-messages` —
    /// matches Go's `batch := int(max); if batch <= 0 || batch >
    /// cfg.MaxMessagesPerPoll { batch = cfg.MaxMessagesPerPoll }`.
    static int effectiveBatch(int max, int configuredMax) {
        return (max <= 0 || max > configuredMax) ? configuredMax : max;
    }

    /// Pure classification of one fetched message — no NATS I/O, no
    /// side effects. `metadataOk` is `false` when `Message#metaData()` threw
    /// (the message can't be tracked, so it must be termed regardless of its
    /// body).
    ///
    /// The broker id encodes both sequences as `<streamSeq>:<consumerSeq>`.
    // TODO(Q19, docs/spec/router.md §7.4/§13): a redelivery carries a fresh
    // consumer sequence, so its broker id differs from the original
    // delivery's — the router's dedup classifies it as an external requeue
    // rather than a redelivery. Kept as-is pending the owner's ruling.
    static FetchOutcome classify(boolean metadataOk, long streamSeq, long consumerSeq, String streamName, byte[] data) {
        if (!metadataOk) {
            return FetchOutcome.Malformed.INSTANCE;
        }
        Message payload;
        try {
            payload = Json.MAPPER.readValue(data, Message.class);
        } catch (tools.jackson.core.JacksonException e) {
            return FetchOutcome.Malformed.INSTANCE;
        }
        String receipt = streamName + ":" + streamSeq;
        String brokerMessageId = streamSeq + ":" + consumerSeq;
        return new FetchOutcome.Deliver(receipt, brokerMessageId, payload);
    }

    private void safeTerm(io.nats.client.Message msg) {
        try {
            msg.term();
        } catch (Exception e) {
            log.warn("nats: term failed on queue {}", identifier, e);
        }
    }

    /// Permanently removes the delivery. Best-effort: a failure — including
    /// an unknown receipt handle — is logged, never thrown (the [Consumer]
    /// contract).
    @Override
    public boolean ack(QueuedMessage message) {
        io.nats.client.Message msg = pending.remove(message.receiptHandle());
        if (msg == null) {
            log.warn("nats: no pending message for receipt {} on queue {}", message.receiptHandle(), identifier);
            return false;
        }
        try {
            msg.ack();
            acked.incrementAndGet();
            return true;
        } catch (Exception e) {
            log.warn("nats: ack failed on queue {} for receipt {}", identifier, message.receiptHandle(), e);
            return false;
        }
    }

    /// NAKs, with `delay` when positive. **Advisory**: the effective
    /// redelivery wait is bounded by `ack-wait-secs` on the connection URI
    /// (§7.4 "Ignored config" — `QueueConfig.VisibilityTimeout` plays no
    /// part here), so `delay` only ever shortens or lengthens the *next*
    /// redelivery within that broker-side window; it is not a guarantee the
    /// broker keeps to the second ([Consumer#nack], §7's note on broker
    /// cadence). Best-effort: never throws.
    @Override
    public void nack(QueuedMessage message, Duration delay) {
        io.nats.client.Message msg = pending.remove(message.receiptHandle());
        if (msg == null) {
            log.warn("nats: no pending message for receipt {} on queue {}", message.receiptHandle(), identifier);
            return;
        }
        try {
            if (delay != null && delay.compareTo(Duration.ZERO) > 0) {
                msg.nakWithDelay(delay);
            } else {
                msg.nak();
            }
            nacked.incrementAndGet();
        } catch (Exception e) {
            log.warn("nats: nack failed on queue {} for receipt {}", identifier, message.receiptHandle(), e);
        }
    }

    /// Broker-side pending/in-flight (`ConsumerInfo`, a round-trip) plus the
    /// process-local counters. Empty on failure or when there is no live
    /// connection (the [Consumer] contract tolerates this).
    @Override
    public Optional<QueueMetrics> metrics() {
        if (consumerContext == null) {
            return Optional.empty();
        }
        try {
            ConsumerInfo info = consumerContext.getConsumerInfo();
            return Optional.of(new QueueMetrics(
                    info.getNumPending(), info.getNumAckPending(), polled.get(), acked.get(), nacked.get()));
        } catch (Exception e) {
            log.warn("nats: metrics query failed on queue {}", identifier, e);
            return Optional.empty();
        }
    }

    /// Terminal. Clears [#pending] and closes the connection — subsequent
    /// deliveries redeliver once their ack-wait lapses, same as Go.
    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        pending.clear();
        if (connection != null) {
            try {
                connection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("nats: error closing connection for queue {}", identifier, e);
            }
        }
    }
}
