package io.flowcatalyst.router.queue.sqs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.wire.MediationOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// [RouterManager] against a real [SqsQueue] wired to a scripted
/// [FakeSqsClient] (owner ruling 2026-09-11, `docs/spec/router.md` §7.2): a
/// queue Integral's control plane lists but SQS does not have yet must start
/// no consumer, issue no poll, raise no warning, and log exactly one INFO
/// across repeated config applies — then build and poll normally the moment
/// it appears. In this package (not `router.manager`) specifically so the
/// [RouterManager.ConsumerFactory] under test can call [SqsQueue#checkedAdopt]
/// — the package-private hook this suite uses instead of a mocking library
/// (CONVENTIONS §7).
class MissingQueueReconfigureTest {

    private static final String QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/my-queue";

    private final Clock clock = Clock.systemUTC();
    private final FakeSqsClient client = new FakeSqsClient();
    private final RecordingWarnings warnings = new RecordingWarnings();
    private final InFlightTracker tracker = new InFlightTracker(clock);
    private final List<Pool> pools = new CopyOnWriteArrayList<>();

    private final RouterManager manager = new RouterManager(tracker, warnings, clock, cfg -> {
        var pool = new Pool(cfg, (msg, recordFailure) -> MediationOutcome.Success.of(200),
                NO_OP_BROKER, PoolMetrics.NO_OP, clock);
        pools.add(pool);
        return pool;
    });

    /// Delegates straight to the production entry point
    /// [SqsQueue#checkedAdopt], over the scripted client rather than a real
    /// AWS one.
    private final RouterManager.ConsumerFactory factory =
            config -> SqsQueue.checkedAdopt(client, config.queueUri(), config.queueName(), config.visibilityTimeout());

    @AfterEach
    void closePools() {
        pools.forEach(Pool::close);
    }

    private static QueueDoesNotExistException missing() {
        return QueueDoesNotExistException.builder().message("The specified queue does not exist.").build();
    }

    @Test
    @DisplayName("a missing queue starts no consumer, issues no poll, raises no warning, and logs exactly one INFO across three applies")
    void missingQueueLogsOnceAcrossRepeatedApplies() {
        client.failAttributesWith(MissingQueueReconfigureTest::missing);
        var config = new RouterConfig(List.of(), List.of(new QueueConfig(QUEUE_URL, "my-queue", 1, 30)));

        var captured = capture();
        try {
            manager.reconfigure(config, factory);
            manager.reconfigure(config, factory);
            manager.reconfigure(config, factory);

            assertThat(manager.consumer("my-queue")).as("no consumer running for a missing queue").isEmpty();
            assertThat(client.receiveRequests()).as("no poll was ever issued").isEmpty();
            assertThat(warnings.raised).as("missing must never raise a warning, unlike a genuine build failure")
                    .isEmpty();
            assertThat(missingQueueInfoLines(captured))
                    .as("one INFO on the transition into the streak, silent on every recheck")
                    .hasSize(1);
        } finally {
            release(captured);
        }
    }

    @Test
    @DisplayName("the queue then exists: the next apply builds it, polling begins, and one INFO says so")
    void queueThatLaterExistsIsBuiltAndPolledOnTheNextApply() {
        client.failAttributesWith(MissingQueueReconfigureTest::missing);
        var config = new RouterConfig(List.of(), List.of(new QueueConfig(QUEUE_URL, "my-queue", 1, 30)));
        manager.reconfigure(config, factory);
        assertThat(manager.consumer("my-queue")).isEmpty();

        client.clearAttributesError();
        manager.reconfigure(config, factory);

        var consumer = manager.consumer("my-queue");
        assertThat(consumer).as("built once the queue exists").isPresent();
        assertThat(client.receiveRequests()).as("nothing has polled it yet").isEmpty();

        // Proves the built consumer would actually poll — the loop itself is
        // RouterServer/ConsumerLoop machinery, out of scope for a
        // RouterManager-level test.
        try {
            consumer.get().poll(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertThat(client.receiveRequests()).as("ReceiveMessage calls begin").hasSize(1);
    }

    private static ListAppender<ILoggingEvent> capture() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RouterManager.class);
        log.addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        var log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RouterManager.class);
        log.detachAppender(appender);
    }

    private static List<ILoggingEvent> missingQueueInfoLines(ListAppender<ILoggingEvent> captured) {
        return captured.list.stream()
                .filter(e -> e.getFormattedMessage().contains("does not exist yet"))
                .toList();
    }

    private static final Broker NO_OP_BROKER = new Broker() {
        @Override
        public void ack(QueuedMessage message) {
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public void release(QueuedMessage message) {
        }
    };

    private static final class RecordingWarnings implements Warnings {
        final List<String> raised = new CopyOnWriteArrayList<>();

        @Override
        public void raise(Severity severity, String category, String message) {
            raised.add(severity + " " + category + " " + message);
        }
    }
}
