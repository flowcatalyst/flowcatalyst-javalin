package io.flowcatalyst.router.queue.nats;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.Message;
import io.nats.client.ConsumeOptions;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.MessageConsumer;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/// NATS JetStream-backed [Consumer] — a pull consumer against a
/// WorkQueue-retention stream (`docs/spec/router.md` §7.4), matching the Go
/// `queue/nats/nats.go` backend's *intent* (owner ruling 2026-09-07: NATS
/// must be a genuine subscription/listener, not a poller — polling is an
/// SQS/Postgres limitation, not the design).
///
/// ### A genuine listener, not a poller
///
/// [#consumer] is a standing `MessageConsumer` opened once, at construction,
/// via the simplified `ConsumerContext#consume` API: the NATS client itself
/// keeps a pull request continuously outstanding against the server (issuing
/// a fresh one automatically as messages are delivered — see
/// [ConsumeOptions]) and hands each message to [#handler] the moment it
/// arrives, on the client library's own dedicated delivery thread. There is
/// no poll cycle to time, no `expiresIn` to race, no ephemeral subscription
/// to open and close — the two throughput defects the earlier revisions of
/// this class fixed (`docs/go-mirror/2026-09-06-go-fix-list.md` G13's
/// history) cannot recur because the shape that caused them — this class
/// issuing its own timed pull requests — no longer exists.
///
/// [#handler] does exactly one thing: `[#buffer].put(msg)`. [#buffer] is a
/// `BlockingQueue` bounded at `max-messages`
/// (`NatsQueueUri#maxMessagesPerPoll`) — matching [ConsumeOptions]'s own
/// batch size, so the client never holds more than one batch's worth of
/// messages with their `ack-wait` already ticking. `put` blocking when the
/// buffer is full **is** the back-pressure that stops the client asking the
/// server for more: the delivery thread cannot return from [#handler] (and
/// therefore cannot process whatever comes next) until [#poll] has drained
/// room for it.
///
/// [#poll] itself is now trivial: [BlockingQueue#take] for the first message
/// — an **untimed** park, which is free on a virtual thread
/// (`docs/spec/admission.md` §0) and correct here because there is nothing
/// else this thread could usefully do while the queue is empty — then
/// [BlockingQueue#drainTo] for whatever else is already buffered, up to the
/// requested batch. `poll-timeout-ms` on the URI is therefore **unused** for
/// NATS: still parsed (`NatsQueueUri`), still documented, never removed as a
/// parameter (a config a caller already has must keep working), simply not
/// consulted by anything — see `docs/spec/router.md` §7.4.
///
/// [#close] must unblock a [#poll] parked in [BlockingQueue#take]: it
/// records the thread currently waiting there ([#waitingThread]) and
/// interrupts it directly, rather than relying on an external caller (a
/// [io.flowcatalyst.router.manager.ConsumerLoop] tear-down) to happen to
/// interrupt the right thread.
///
/// Every fetched message is held in [#pending], keyed by its receipt handle,
/// until [#ack] or [#nack] resolves it — a redelivery after a dropped
/// connection is simply a fresh entry under a fresh receipt.
///
/// ### Liveness while [#poll] is blocked waiting on the broker
///
/// [#poll] parking untimed in [BlockingQueue#take] means an idle queue and a
/// hung one look identical to anything that only watches whether `poll()`
/// has *returned* — including
/// [io.flowcatalyst.router.manager.ConsumerSupervisor]'s stall watchdog
/// (`docs/spec/router.md` §3.2, §5 row 47). [#lastBrokerActivity] is this
/// class's answer: it reports "now" for as long as [#connection] reads
/// [Connection.Status#CONNECTED], falling back to [#lastActivity] (the last
/// time a message actually reached this consumer) once it does not. jnats'
/// simplified `consume` API exposes no positive per-heartbeat callback — only
/// a negative `ErrorListener#heartbeatAlarm` for a *missed* one — so
/// `CONNECTED` is read as the positive signal instead: the connection's own
/// PING/PONG keepalive is independent of the JetStream idle-heartbeat and
/// will flip the status the moment the broker is actually unreachable, which
/// is exactly the case that must still be caught (a genuinely hung poll
/// stays stalled once the connection itself drops).
///
/// ### Malformed payloads
/// A message whose JetStream metadata can't be read, or whose body isn't
/// valid [Message] JSON, is termed (`Message#term()`) rather than delivered:
/// termed messages are neither acked nor nacked, so they never redeliver
/// (§7.1, §7.4). [#classify] makes this decision as a pure function so it is
/// testable without a live broker — see `NatsQueueTest`. A batch that is
/// **entirely** malformed hands [#poll]'s caller an empty `Delivered` even
/// though this class never blocks on an empty queue — the one case
/// `docs/spec/router.md` §3.2's empty-batch pause is still reachable for
/// NATS; see [ConsumerLoop][io.flowcatalyst.router.manager.ConsumerLoop].
public final class NatsQueue implements Consumer {

    private static final Logger log = LoggerFactory.getLogger(NatsQueue.class);

    private final String identifier;
    private final NatsQueueUri config;
    private final Connection connection;
    private final MessageConsumer consumer;
    private final Clock clock;

    /// The last time a message actually reached this consumer — through the
    /// standing [#consumer]'s handler in production, or through [#poll]
    /// draining messages a test seeded directly into [#buffer]
    /// (`NatsQueueTest`, CONVENTIONS §6). Seeded at construction so a
    /// freshly connected queue is never judged stale before its first
    /// delivery. Read by [#lastBrokerActivity] as the fallback for when
    /// [#connection] is not [Connection.Status#CONNECTED] — see the class
    /// doc's "Liveness while poll is blocked waiting on the broker".
    private final AtomicReference<Instant> lastActivity;

    /// Messages the standing [#consumer]'s handler has already pulled off
    /// the wire, waiting for [#poll] to hand them to the router. Bounded at
    /// `max-messages` — see the class doc's "A genuine listener, not a
    /// poller". Package-private so a test can seed it directly, the same
    /// seam [#pending] already uses (CONVENTIONS §6).
    final BlockingQueue<io.nats.client.Message> buffer;

    /// Fetched, not yet resolved. Package-private so tests can seed and
    /// inspect it directly without a live broker (CONVENTIONS §6: hand-write
    /// seams, no mocking library).
    final Map<String, io.nats.client.Message> pending = new ConcurrentHashMap<>();

    /// Set by [#close]; checked at the top of every [#poll] (CONVENTIONS §5:
    /// an explicit stop signal, never a silently-closed resource).
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /// The thread currently parked in [#poll]'s [BlockingQueue#take], or
    /// `null` — set immediately on entry to [#poll], cleared in its
    /// `finally`, so [#close] can find and interrupt it even though [#poll]
    /// runs on whatever thread the caller (a
    /// [io.flowcatalyst.router.manager.ConsumerLoop]) happens to be using.
    /// Without this, closing a queue whose poll is genuinely parked (nothing
    /// buffered, nothing else to interrupt it) would leave that thread
    /// waiting for a message the now-closed consumer will never deliver.
    private final AtomicReference<Thread> waitingThread = new AtomicReference<>();

    // Process-local counters (`queue.Metrics` contract, §2.9) — no
    // round-trip. Package-private, alongside `pending`, so tests can read
    // them directly without a live broker.
    final AtomicLong polled = new AtomicLong();
    final AtomicLong acked = new AtomicLong();
    final AtomicLong nacked = new AtomicLong();

    /// Connects, provisions the stream and durable consumer (create-or-update,
    /// matching Go's `CreateOrUpdateStream`/`CreateOrUpdateConsumer`), and
    /// starts the standing [#consumer] — see the class doc.
    ///
    /// @throws NatsQueueException connecting or provisioning failed
    public NatsQueue(String queueUri) {
        this.config = NatsQueueUri.parse(queueUri);
        this.identifier = config.identifier();
        this.clock = Clock.systemUTC();
        this.buffer = new ArrayBlockingQueue<>(config.maxMessagesPerPoll());
        this.lastActivity = new AtomicReference<>(clock.instant());
        Resources resources = connect(config, queueUri, buffer, stopped, clock, lastActivity);
        this.connection = resources.connection();
        this.consumer = resources.consumer();
    }

    /// Test-only seam: builds an instance with no live NATS connection.
    /// [#connection] and [#consumer] stay `null`; the only production paths
    /// that dereference them are [#poll] while running and [#metrics] —
    /// both guard against the test seam by checking `null` or [#stopped]
    /// first, so `ack`/`nack`/`close`/stopped-`poll` are all exercisable
    /// here (see `NatsQueueTest`).
    NatsQueue(String identifier, NatsQueueUri config) {
        this(identifier, config, null);
    }

    /// Test-only seam, variant of the constructor above that also seeds a
    /// (fake) [#consumer] — for pinning what [#close] does to it without a
    /// live broker (`NatsQueueTest`). [#connection] still stays `null`;
    /// [#close] guards that independently, so a fake consumer can be
    /// exercised without also needing a fake connection. [#buffer] is
    /// always real (a genuine `ArrayBlockingQueue`, same as production) so a
    /// test can pin its capacity and blocking behaviour directly rather than
    /// against a fake standing in for it.
    NatsQueue(String identifier, NatsQueueUri config, MessageConsumer consumer) {
        this(identifier, config, consumer, Clock.systemUTC());
    }

    /// Test-only seam, variant of the constructor above that also injects a
    /// [Clock] — for pinning [#lastBrokerActivity]'s staleness threshold
    /// without waiting out real time (`NatsQueueTest`).
    NatsQueue(String identifier, NatsQueueUri config, MessageConsumer consumer, Clock clock) {
        this.identifier = identifier;
        this.config = config;
        this.connection = null;
        this.consumer = consumer;
        this.clock = clock;
        this.buffer = new ArrayBlockingQueue<>(config.maxMessagesPerPoll());
        this.lastActivity = new AtomicReference<>(clock.instant());
    }

    private record Resources(Connection connection, MessageConsumer consumer) {
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

    private static Resources connect(NatsQueueUri config, String queueUri,
                                      BlockingQueue<io.nats.client.Message> buffer, AtomicBoolean stopped,
                                      Clock clock, AtomicReference<Instant> lastActivity) {
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
                // The standing listener — see the class doc's "A genuine
                // listener, not a poller". batchSize matches [#buffer]'s own
                // capacity: the client never holds more than one batch's
                // worth of messages in flight with their ack-wait ticking.
                ConsumerContext ctx = connection.getConsumerContext(config.streamName(), config.consumerName());
                ConsumeOptions consumeOptions = ConsumeOptions.builder()
                        .batchSize(config.maxMessagesPerPoll())
                        .build();
                MessageConsumer consumer = ctx.consume(consumeOptions, msg -> {
                    // Real broker evidence, independent of whether poll() is
                    // even blocked right now — see the class doc's "Liveness
                    // while poll is blocked waiting on the broker". Recorded
                    // before the (possibly blocking) put so a full buffer
                    // does not delay the timestamp behind the back-pressure
                    // wait.
                    lastActivity.set(clock.instant());
                    // Dropped rather than risking an indefinite block on the
                    // client library's own delivery thread once this queue
                    // is closing: nothing will ever poll() it again, and an
                    // un-acked message simply redelivers once ack-wait
                    // lapses, same as any other abandoned delivery.
                    if (!stopped.get()) {
                        buffer.put(msg);
                    }
                });
                return new Resources(connection, consumer);
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

    /// False (R5, owner ruling 2026-09-17,
    /// `docs/spec/router-deferral-handback.md`): this stream is one durable
    /// WorkQueue consumer with `max-ack-pending` 1000 and no per-group
    /// subject, so the broker enforces no group ordering — a nack never
    /// blocks a delayed head's successors the way R4 blocks Postgres — and
    /// each hand-back spends one of `max-deliver`'s limited redeliveries.
    /// Deliberately unchanged from pre-R1 behaviour: a delay-bearing
    /// deferral stays on the in-memory `DEFERRED` retry curve.
    @Override
    public boolean honoursDelayedReturn() {
        return false;
    }

    @Override
    public PollResult poll(int max) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("interrupted before polling queue " + identifier);
        }

        int batch = effectiveBatch(max, config.maxMessagesPerPoll());

        // Registered BEFORE the stopped check below (not after): a close()
        // landing between the two must still find this thread here and
        // interrupt it, or a poll() that started a moment too early would
        // park forever on a consumer nothing will ever feed again. See the
        // class doc's "[#close] must unblock a [#poll]".
        waitingThread.set(Thread.currentThread());
        io.nats.client.Message first;
        try {
            if (stopped.get()) {
                // Absorb a close()-sent interrupt that may have landed
                // between this thread registering above and this check —
                // it is reported as STOPPED, not propagated, so it must not
                // be left set on the thread for the next blocking call to
                // trip over.
                Thread.interrupted();
                return PollResult.STOPPED;
            }
            // Untimed park: free on a virtual thread (`docs/spec/admission.md`
            // §0) and correct here — there is nothing else useful this
            // thread could do while the queue is empty. Blocks until either
            // the standing consumer's handler buffers a message, or [#close]
            // interrupts this thread.
            first = buffer.take();
        } catch (InterruptedException e) {
            if (stopped.get()) {
                return PollResult.STOPPED;
            }
            throw e;
        } finally {
            waitingThread.set(null);
        }

        // A message reached this consumer — real broker evidence for
        // [#lastBrokerActivity]'s fallback, and (CONVENTIONS §6) the seam
        // `NatsQueueTest` drives directly, since its fakes have no live
        // handler to update [#lastActivity] the production way (`connect`'s
        // consume handler, above).
        lastActivity.set(clock.instant());
        List<io.nats.client.Message> messages = new ArrayList<>(batch);
        messages.add(first);
        buffer.drainTo(messages, batch - 1);

        List<QueuedMessage> delivered = new ArrayList<>(messages.size());
        for (var msg : messages) {
            handleFetched(msg, delivered);
        }
        if (!delivered.isEmpty()) {
            polled.addAndGet(delivered.size());
        }
        return PollResult.of(delivered);
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
        try {
            var meta = msg.metaData();
            streamSeq = meta.streamSequence();
            metadataOk = true;
        } catch (Exception e) {
            metadataOk = false;
        }

        apply(classify(metadataOk, streamSeq, config.streamName(), msg.getData()), msg, out);
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
    /// Both identities derive from the **stream** sequence — the message's
    /// identity in the stream, which every redelivery of it shares.
    ///
    /// Neither may involve the **consumer** sequence, which counts deliveries
    /// and therefore changes on every redelivery. Feeding that to
    /// [io.flowcatalyst.router.inflight.InFlightTracker]'s duplicate filter
    /// makes each redelivery look like a *different copy* of the message — an
    /// external requeue — and the router ACK-deletes those. JetStream then
    /// destroys its own copy every time the ack-wait lapses, leaving the
    /// in-memory copy as the only one: at-least-once quietly becomes
    /// at-most-once, and a later release or pool flush loses the message with
    /// nothing but a "no pending message for receipt" warning to show for it.
    ///
    /// This was Q19, parked pending a ruling. Go ruled by fixing it the same
    /// way (`20e9fe7`) with the loss demonstrated, so it is a defect rather
    /// than a question. SQS (`MessageId`) and Postgres (row id) were always
    /// right; the rule is written here because the tracker enforces it on
    /// backends it cannot see.
    static FetchOutcome classify(boolean metadataOk, long streamSeq, String streamName, byte[] data) {
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
        String brokerMessageId = Long.toString(streamSeq);
        return new FetchOutcome.Deliver(receipt, brokerMessageId, payload);
    }

    private void safeTerm(io.nats.client.Message msg) {
        try {
            msg.term();
        } catch (Exception e) {
            log.atWarn().setMessage("nats: term failed")
                    .addKeyValue("queue", identifier)
                    .setCause(e)
                    .log();
        }
    }

    /// Permanently removes the delivery. Best-effort: a failure — including
    /// an unknown receipt handle — is logged, never thrown (the [Consumer]
    /// contract).
    @Override
    public boolean ack(QueuedMessage message) {
        io.nats.client.Message msg = pending.remove(message.receiptHandle());
        if (msg == null) {
            log.atWarn().setMessage("nats: no pending message")
                    .addKeyValue("receipt", message.receiptHandle())
                    .addKeyValue("queue", identifier)
                    .log();
            return false;
        }
        try {
            msg.ack();
            acked.incrementAndGet();
            return true;
        } catch (Exception e) {
            log.atWarn().setMessage("nats: ack failed")
                    .addKeyValue("queue", identifier)
                    .addKeyValue("receipt", message.receiptHandle())
                    .setCause(e)
                    .log();
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
            log.atWarn().setMessage("nats: no pending message")
                    .addKeyValue("receipt", message.receiptHandle())
                    .addKeyValue("queue", identifier)
                    .log();
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
            log.atWarn().setMessage("nats: nack failed")
                    .addKeyValue("queue", identifier)
                    .addKeyValue("receipt", message.receiptHandle())
                    .setCause(e)
                    .log();
        }
    }

    /// Broker-side pending/in-flight (`ConsumerInfo`, a round-trip) plus the
    /// process-local counters. Empty on failure or when there is no live
    /// connection (the [Consumer] contract tolerates this).
    @Override
    public Optional<QueueMetrics> metrics() {
        if (consumer == null) {
            return Optional.empty();
        }
        try {
            ConsumerInfo info = consumer.getConsumerInfo();
            return Optional.of(new QueueMetrics(
                    info.getNumPending(), info.getNumAckPending(), polled.get(), acked.get(), nacked.get()));
        } catch (Exception e) {
            log.atWarn().setMessage("nats: metrics query failed")
                    .addKeyValue("queue", identifier)
                    .setCause(e)
                    .log();
            return Optional.empty();
        }
    }

    /// Independent evidence the broker is alive — see the class doc's
    /// "Liveness while poll is blocked waiting on the broker"
    /// (`docs/spec/router.md` §3.2, §5 row 47). While [#connection] reads
    /// [Connection.Status#CONNECTED] this reports the current instant: the
    /// connection's own keepalive is continuously re-proving liveness even
    /// when no JetStream message has arrived in a while, which is the
    /// ordinary shape of an idle queue, not a hung one. Once it is not
    /// `CONNECTED` — or in the test seam, where [#connection] is always
    /// `null` — this falls back to [#lastActivity], the last time a message
    /// genuinely reached this consumer.
    @Override
    public Optional<Instant> lastBrokerActivity() {
        if (connection != null && connection.getStatus() == Connection.Status.CONNECTED) {
            return Optional.of(clock.instant());
        }
        return Optional.ofNullable(lastActivity.get());
    }

    /// Terminal. Clears [#pending] and [#buffer], stops the standing
    /// [#consumer] and unblocks a [#poll] parked waiting on it, and closes
    /// the connection — subsequent deliveries redeliver once their ack-wait
    /// lapses, same as Go.
    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        pending.clear();
        buffer.clear();
        var blocked = waitingThread.get();
        if (blocked != null) {
            blocked.interrupt();
        }
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                log.atWarn().setMessage("nats: error closing consumer")
                        .addKeyValue("queue", identifier)
                        .setCause(e)
                        .log();
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.atWarn().setMessage("nats: error closing connection")
                        .addKeyValue("queue", identifier)
                        .setCause(e)
                        .log();
            }
        }
    }
}
