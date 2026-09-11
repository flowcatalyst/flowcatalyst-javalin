package io.flowcatalyst.router.queue.sqs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer.PollResult;
import io.flowcatalyst.router.queue.Consumer.PollResult.Delivered;
import io.flowcatalyst.router.queue.ConsumerBuild;
import io.flowcatalyst.router.queue.QueueMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [SqsQueue] against a hand-written [FakeSqsClient] (`docs/spec/router.md`
/// §7.2) — there is no SQS in the test environment and CONVENTIONS §7 rules
/// out a mocking library.
class SqsQueueTest {

    private static final String QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/my-queue";

    private final FakeSqsClient client = new FakeSqsClient();
    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));

    private SqsQueue queue() {
        return new SqsQueue(client, QUEUE_URL, null, 0, clock);
    }

    private static software.amazon.awssdk.services.sqs.model.Message sqsMessage(
            String messageId, String receiptHandle, String body) {
        return software.amazon.awssdk.services.sqs.model.Message.builder()
                .messageId(messageId)
                .receiptHandle(receiptHandle)
                .body(body)
                .build();
    }

    private static List<QueuedMessage> delivered(PollResult result) {
        assertThat(result).isInstanceOf(Delivered.class);
        return ((Delivered) result).messages();
    }

    // --- poll: happy path -------------------------------------------------

    @Test
    @DisplayName("a client whose queue is rejected is closed rather than stranded")
    void rejectedQueueClosesTheClient() {
        // The client owns an HTTP connection pool and its threads. Nothing
        // references it until the constructor returns, and QueueFactory turns
        // the throw into an empty Optional — so the reconfigure loop retries
        // the same bad queue on every config poll and strands another one.
        assertThatThrownBy(() -> SqsQueue.adopt(client, null, "orders", 30))
                .isInstanceOf(NullPointerException.class);

        assertThat(client.closed).isTrue();
    }

    @Test
    @DisplayName("a client that is accepted stays open")
    void acceptedQueueKeepsTheClient() {
        var queue = SqsQueue.adopt(client, "https://sqs.eu-west-1.amazonaws.com/1/orders", null, 30);

        assertThat(queue.identifier()).isEqualTo("orders");
        assertThat(client.closed).isFalse();
    }

    @Test
    @DisplayName("poll parses a message body into a QueuedMessage carrying the receipt and broker id")
    void pollParsesBodyIntoQueuedMessage() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-1", "receipt-1", "{\"id\":\"msg-1\"}"))
                .build());

        SqsQueue sqs = queue();
        List<QueuedMessage> messages = delivered(sqs.poll(10));

        assertThat(messages).hasSize(1);
        QueuedMessage qm = messages.get(0);
        assertThat(qm.message().id()).isEqualTo("msg-1");
        assertThat(qm.brokerMessageId()).isEqualTo("mid-1");
        assertThat(qm.receiptHandle()).isEqualTo("receipt-1");
        assertThat(qm.queueId()).isEqualTo(sqs.identifier());
        assertThat(qm.attempts()).isZero();
    }

    @Test
    @DisplayName("poll requests min(max, 10) messages with the configured visibility and a 20s long-poll")
    void pollRequestShapeMatchesSpec() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder().messages(List.of()).build());
        client.enqueueReceive(ReceiveMessageResponse.builder().messages(List.of()).build());

        SqsQueue sqs = new SqsQueue(client, QUEUE_URL, null, 45, clock);
        sqs.poll(50);
        sqs.poll(3);

        assertThat(client.receiveRequests()).hasSize(2);
        assertThat(client.receiveRequests().get(0).maxNumberOfMessages()).isEqualTo(10);
        assertThat(client.receiveRequests().get(1).maxNumberOfMessages()).isEqualTo(3);
        assertThat(client.receiveRequests().get(0).visibilityTimeout()).isEqualTo(45);
        assertThat(client.receiveRequests().get(0).waitTimeSeconds()).isEqualTo(20);
    }

    @Test
    @DisplayName("a zero configured visibility timeout defaults to 30 seconds")
    void defaultVisibilityTimeoutIsThirtySeconds() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder().messages(List.of()).build());
        new SqsQueue(client, QUEUE_URL, null, 0, clock).poll(10);

        assertThat(client.receiveRequests().get(0).visibilityTimeout()).isEqualTo(30);
    }

    @Test
    @DisplayName("an empty poll is a normal empty result, not an error")
    void emptyPollIsNotAnError() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder().messages(List.of()).build());

        PollResult result = queue().poll(10);

        assertThat(delivered(result)).isEmpty();
    }

    // --- malformed / empty bodies ------------------------------------------

    @Test
    @DisplayName("a malformed body is acked and skipped, never returned")
    void malformedBodyIsAckedAndSkipped() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-bad", "receipt-bad", "not json"))
                .build());

        SqsQueue sqs = queue();
        List<QueuedMessage> messages = delivered(sqs.poll(10));

        assertThat(messages).isEmpty();
        assertThat(client.deleteRequests()).extracting(r -> r.receiptHandle()).containsExactly("receipt-bad");
        assertThat(sqs.metrics()).get().extracting(QueueMetrics::acked).isEqualTo(1L);
    }

    @Test
    @DisplayName("an empty body is acked and skipped, never returned")
    void emptyBodyIsAckedAndSkipped() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-empty", "receipt-empty", ""))
                .build());

        SqsQueue sqs = queue();
        List<QueuedMessage> messages = delivered(sqs.poll(10));

        assertThat(messages).isEmpty();
        assertThat(client.deleteRequests()).extracting(r -> r.receiptHandle()).containsExactly("receipt-empty");
    }

    // --- pending-delete: suppress redelivery of an already-acked message --

    @Test
    @DisplayName("the pending-delete map suppresses a redelivery of an already-acked message")
    void pendingDeleteSuppressesRedeliveryOfAckedMessage() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-A", "receipt-A1", "{\"id\":\"msg-A\"}"))
                .build());
        SqsQueue sqs = queue();
        QueuedMessage first = delivered(sqs.poll(10)).get(0);
        sqs.ack(first);
        assertThat(sqs.metrics()).get().extracting(QueueMetrics::acked).isEqualTo(1L);

        // SQS redelivers the same MessageId under a fresh receipt handle.
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-A", "receipt-A2", "{\"id\":\"msg-A\"}"))
                .build());
        List<QueuedMessage> redelivered = delivered(sqs.poll(10));

        assertThat(redelivered).isEmpty();
        assertThat(client.deleteRequests())
                .extracting(r -> r.receiptHandle())
                .contains("receipt-A1", "receipt-A2");
        // The redelivery's own delete is not counted as a second ack.
        assertThat(sqs.metrics()).get().extracting(QueueMetrics::acked).isEqualTo(1L);
    }

    // --- map pruning (the two rules differ) --------------------------------

    @Test
    @DisplayName("pendingDelete entries older than 15 minutes are pruned on the next non-empty poll")
    void pendingDeleteIsPrunedByAgeOnEveryNonEmptyPoll() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-A", "receipt-A1", "{\"id\":\"msg-A\"}"))
                .build());
        SqsQueue sqs = queue();
        sqs.ack(delivered(sqs.poll(10)).get(0));
        assertThat(sqs.pendingDeleteSizeForTest()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(16));

        // Any non-empty poll prunes, regardless of what it delivers.
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-B", "receipt-B1", "{\"id\":\"msg-B\"}"))
                .build());
        sqs.poll(10);

        assertThat(sqs.pendingDeleteSizeForTest()).isZero();

        // The pruned entry no longer suppresses a redelivery of mid-A.
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-A", "receipt-A2", "{\"id\":\"msg-A\"}"))
                .build());
        List<QueuedMessage> redelivered = delivered(sqs.poll(10));
        assertThat(redelivered).extracting(QueuedMessage::brokerMessageId).containsExactly("mid-A");
    }

    @Test
    @DisplayName("receiptToMessageId is pruned by age only once it exceeds 1000 entries")
    void receiptMapPrunedOnlyPastSizeThreshold() throws InterruptedException {
        SqsQueue sqs = queue();

        // Fill the map to exactly the threshold with entries that will be
        // 16 minutes old by the time pruning is eligible to run.
        for (int batch = 0; batch < 100; batch++) {
            var messages = new software.amazon.awssdk.services.sqs.model.Message[10];
            for (int i = 0; i < 10; i++) {
                String n = batch + "-" + i;
                messages[i] = sqsMessage("mid-" + n, "receipt-" + n, "{\"id\":\"msg-" + n + "\"}");
            }
            client.enqueueReceive(ReceiveMessageResponse.builder().messages(messages).build());
            sqs.poll(10);
        }
        assertThat(sqs.receiptMapSizeForTest()).isEqualTo(1000);

        clock.advance(Duration.ofMinutes(16));

        // Size is still exactly at the threshold (not over it) going into
        // this poll, so the age-based prune does not fire yet.
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-1001", "receipt-1001", "{\"id\":\"msg-1001\"}"))
                .build());
        sqs.poll(10);
        assertThat(sqs.receiptMapSizeForTest()).isEqualTo(1001);

        // Now over the threshold: this poll prunes every entry older than
        // 15 minutes — the original 1000, timestamped before the clock
        // advanced — before adding its own delivery. mid-1001 was recorded
        // at the current clock time (no further advance since), so it is
        // not yet 15 minutes old and survives.
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-1002", "receipt-1002", "{\"id\":\"msg-1002\"}"))
                .build());
        sqs.poll(10);
        assertThat(sqs.receiptMapSizeForTest()).isEqualTo(2);
    }

    // --- ack ----------------------------------------------------------------

    @Test
    @DisplayName("ack deletes the message and counts it as acked only on success")
    void ackDeletesAndCountsOnlyOnSuccess() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-1", "receipt-1", "{\"id\":\"msg-1\"}"))
                .build());
        SqsQueue sqs = queue();
        QueuedMessage qm = delivered(sqs.poll(10)).get(0);

        sqs.ack(qm);

        assertThat(client.deleteRequests()).extracting(r -> r.receiptHandle()).containsExactly("receipt-1");
        assertThat(sqs.metrics()).get().extracting(QueueMetrics::acked).isEqualTo(1L);
        assertThat(sqs.pendingDeleteSizeForTest()).isEqualTo(1);
        assertThat(sqs.receiptMapSizeForTest()).isZero();
    }

    @Test
    @DisplayName("ack never throws, and does not count a failed delete as acked")
    void ackDoesNotThrowAndDoesNotCountOnFailure() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-1", "receipt-1", "{\"id\":\"msg-1\"}"))
                .build());
        SqsQueue sqs = queue();
        QueuedMessage qm = delivered(sqs.poll(10)).get(0);

        client.failDeleteWith(SdkClientException.create("boom"));

        sqs.ack(qm);

        assertThat(client.deleteRequests()).hasSize(1);
        assertThat(sqs.metrics()).get().extracting(QueueMetrics::acked).isEqualTo(0L);
    }

    @Test
    @DisplayName("a delete outage logs one stack trace per streak, and a fresh streak logs its own")
    void deleteFailuresLogTheCauseOncePerStreak() throws InterruptedException {
        // An outage fails the delete for every message in flight. A stack
        // trace each time is volume, not information — but every failure must
        // still be logged, must still name the error, and a *new* outage must
        // not be silent about its cause.
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-1", "r-1", "{\"id\":\"msg-1\"}"),
                        sqsMessage("mid-2", "r-2", "{\"id\":\"msg-2\"}"),
                        sqsMessage("mid-3", "r-3", "{\"id\":\"msg-3\"}"),
                        sqsMessage("mid-4", "r-4", "{\"id\":\"msg-4\"}"),
                        sqsMessage("mid-5", "r-5", "{\"id\":\"msg-5\"}"))
                .build());
        SqsQueue sqs = queue();
        var msgs = delivered(sqs.poll(10));

        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        var log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SqsQueue.class);
        log.addAppender(captured);
        try {
            client.failDeleteWith(SdkClientException.create("boom"));
            sqs.ack(msgs.get(0));
            sqs.ack(msgs.get(1));
            sqs.ack(msgs.get(2));

            assertThat(deleteFailures(captured)).as("every failure is still logged").hasSize(3);
            assertThat(tracesIn(captured)).as("one trace for the streak").hasSize(1);
            assertThat(deleteFailures(captured).getFirst().getThrowableProxy()).as("and it is the first").isNotNull();
            assertThat(deleteFailures(captured).get(1).getKeyValuePairs())
                    .as("the later ones still name the error")
                    .anySatisfy(kv -> assertThat(String.valueOf(kv.value)).contains("boom"));

            // A delete that succeeds ends the streak...
            client.failDeleteWith(null);
            sqs.ack(msgs.get(3));

            // ...so the next outage is a new streak and gets its own trace.
            client.failDeleteWith(SdkClientException.create("boom again"));
            sqs.ack(msgs.get(4));
            assertThat(tracesIn(captured))
                    .as("a fresh outage must not be silent about its cause")
                    .hasSize(2);
        } finally {
            log.detachAppender(captured);
        }
    }

    private static List<ILoggingEvent> deleteFailures(ListAppender<ILoggingEvent> captured) {
        return captured.list.stream().filter(e -> e.getFormattedMessage().contains("DeleteMessage failed")).toList();
    }

    private static List<ILoggingEvent> tracesIn(ListAppender<ILoggingEvent> captured) {
        return deleteFailures(captured).stream().filter(e -> e.getThrowableProxy() != null).toList();
    }

    // --- nack -----------------------------------------------------------------

    @Test
    @DisplayName("nack is a no-op beyond the counter — it never deletes or changes visibility")
    void nackIsANoOp() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-1", "receipt-1", "{\"id\":\"msg-1\"}"))
                .build());
        SqsQueue sqs = queue();
        QueuedMessage qm = delivered(sqs.poll(10)).get(0);

        sqs.nack(qm, Duration.ofSeconds(5));

        assertThat(client.deleteRequests()).isEmpty();
        assertThat(sqs.metrics()).get().extracting(QueueMetrics::nacked).isEqualTo(1L);
    }

    // --- a queue that does not exist yet (owner ruling 2026-09-11) --------

    @Test
    @DisplayName("checkedAdopt answers Missing, and closes the client, when the queue does not exist")
    void checkedAdoptAnswersMissingForANonExistentQueue() {
        client.failAttributesWith(() -> QueueDoesNotExistException.builder()
                .message("The specified queue does not exist.").build());

        ConsumerBuild result = SqsQueue.checkedAdopt(client, QUEUE_URL, null, 30);

        assertThat(result).isInstanceOf(ConsumerBuild.Missing.class);
        assertThat(client.closed).as("nothing holds this client once the queue is missing").isTrue();
        assertThat(client.receiveRequests()).as("no poll ever issued for a queue that was never adopted").isEmpty();
    }

    @Test
    @DisplayName("checkedAdopt checks existence before adopting a queue that IS there")
    void checkedAdoptBuildsWhenTheQueueExists() {
        // No attributesError configured: getQueueAttributes succeeds, exactly
        // as it would for a queue that exists.
        ConsumerBuild result = SqsQueue.checkedAdopt(client, QUEUE_URL, "orders", 30);

        assertThat(result).isInstanceOf(ConsumerBuild.Built.class);
        assertThat(((ConsumerBuild.Built) result).consumer().identifier()).isEqualTo("orders");
        assertThat(client.attributesRequests())
                .as("the existence check actually asked the broker").hasSize(1);
        assertThat(client.closed).isFalse();
    }

    @Test
    @DisplayName("checkedAdopt builds the consumer anyway when the existence check fails for a reason OTHER than 'does not exist'")
    void checkedAdoptBuildsOnAnUnrelatedExistenceCheckFailure() {
        // A transient AWS error (network, throttling, auth) must never
        // silently stop consumption — the ordinary poll-failure path (and
        // its CONNECTION warning) covers a genuine outage once polling
        // starts.
        client.failAttributesWith(SdkClientException.create("throttled"));

        ConsumerBuild result = SqsQueue.checkedAdopt(client, QUEUE_URL, "orders", 30);

        assertThat(result).as("an unknown existence-check failure must not stop the consumer starting")
                .isInstanceOf(ConsumerBuild.Built.class);
        assertThat(client.closed).isFalse();
    }

    @Test
    @DisplayName("a poll that finds the queue gone returns QueueMissing, not an exception, and raises nothing")
    void pollReturnsQueueMissingWhenTheQueueIsDeleted() throws InterruptedException {
        client.failNextReceiveWith(() -> QueueDoesNotExistException.builder()
                .message("The specified queue does not exist.").build());

        PollResult result = queue().poll(10);

        assertThat(result).isEqualTo(PollResult.QUEUE_MISSING);
    }

    /// The code the staging router actually logged (2026-09-04), as a plain
    /// SqsException rather than the SDK's typed subclass — so recognising
    /// absence does not depend on the SDK mapping every spelling.
    private static SqsException legacyNonExistentQueue() {
        return (SqsException) SqsException.builder()
                .message("The specified queue does not exist.")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("AWS.SimpleQueueService.NonExistentQueue").build())
                .build();
    }

    @Test
    @DisplayName("the legacy NonExistentQueue error code also counts as missing, at build and at poll")
    void legacyNonExistentQueueCodeIsMissingToo() throws InterruptedException {
        client.failAttributesWith(() -> legacyNonExistentQueue());
        assertThat(SqsQueue.checkedAdopt(client, QUEUE_URL, null, 30)).isInstanceOf(ConsumerBuild.Missing.class);

        var fresh = new FakeSqsClient();
        fresh.failNextReceiveWith(() -> legacyNonExistentQueue());
        assertThat(SqsQueue.adopt(fresh, QUEUE_URL, "orders", 30).poll(10)).isEqualTo(PollResult.QUEUE_MISSING);
    }

    @Test
    @DisplayName("any other SqsException from a poll still propagates as a failure")
    void otherSqsErrorsStillPropagate() {
        client.failNextReceiveWith(() -> (SqsException) SqsException.builder().message("denied")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build());

        assertThatThrownBy(() -> queue().poll(10)).isInstanceOf(SqsException.class);
    }

    // --- close / stopped -------------------------------------------------------

    @Test
    @DisplayName("after close, poll always answers Stopped and never calls ReceiveMessage")
    void closeThenPollIsStopped() throws InterruptedException {
        SqsQueue sqs = queue();
        sqs.close();

        PollResult result = sqs.poll(10);

        assertThat(result).isEqualTo(PollResult.STOPPED);
        assertThat(client.receiveRequests()).isEmpty();
    }

    // --- interruption ------------------------------------------------------

    @Test
    @DisplayName("interruption surfacing through the SDK call is restored and rethrown as InterruptedException")
    void interruptionDuringReceiveIsPropagated() {
        client.failNextReceiveWith(() -> SdkClientException.create("interrupted", new InterruptedException()));
        SqsQueue sqs = queue();

        try {
            assertThatThrownBy(() -> sqs.poll(10)).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear so this doesn't bleed into later tests on a pooled thread.
            Thread.interrupted();
        }
    }

    // --- metrics --------------------------------------------------------------

    @Test
    @DisplayName("metrics combines the broker's queue attributes with the process-local counters")
    void metricsCombinesBrokerAttributesAndCounters() throws InterruptedException {
        client.enqueueReceive(ReceiveMessageResponse.builder()
                .messages(sqsMessage("mid-1", "receipt-1", "{\"id\":\"msg-1\"}"))
                .build());
        SqsQueue sqs = queue();
        sqs.poll(10);
        client.queueAttributes(Map.of(
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "7",
                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "2"));

        Optional<QueueMetrics> metrics = sqs.metrics();

        assertThat(metrics).contains(new QueueMetrics(7, 2, 1, 0, 0));
    }

    @Test
    @DisplayName("metrics is empty, not thrown, when GetQueueAttributes fails")
    void metricsIsEmptyOnFailure() {
        client.failAttributesWith(SdkClientException.create("boom"));

        assertThat(queue().metrics()).isEmpty();
    }

    // --- region extraction -------------------------------------------------

    @Test
    @DisplayName("region is extracted from an sqs.<region>.amazonaws.com host")
    void regionFromStandardUrl() {
        assertThat(SqsQueue.regionFromUrl("https://sqs.us-east-1.amazonaws.com/123456789012/my-queue"))
                .contains("us-east-1");
    }

    @Test
    @DisplayName("region is extracted from an sqs-fips.<region>.amazonaws.com host")
    void regionFromFipsUrl() {
        assertThat(SqsQueue.regionFromUrl("https://sqs-fips.us-gov-west-1.amazonaws.com/123456789012/my-queue"))
                .contains("us-gov-west-1");
    }

    @Test
    @DisplayName("region is extracted from a .amazonaws.com.cn host")
    void regionFromChinaUrl() {
        assertThat(SqsQueue.regionFromUrl("https://sqs.cn-north-1.amazonaws.com.cn/123456789012/my-queue"))
                .contains("cn-north-1");
    }

    @Test
    @DisplayName("region is absent for a URL that is not a recognisable SQS endpoint")
    void regionAbsentForNonSqsUrl() {
        assertThat(SqsQueue.regionFromUrl("http://localhost:4566/000000000000/my-queue")).isEmpty();
        assertThat(SqsQueue.regionFromUrl("not a url")).isEmpty();
    }

    // --- identifier ----------------------------------------------------------

    @Test
    @DisplayName("identifier is the configured queue name when one is given")
    void identifierUsesConfiguredName() {
        SqsQueue sqs = new SqsQueue(client, QUEUE_URL, "configured-name", 0, clock);
        assertThat(sqs.identifier()).isEqualTo("configured-name");
    }

    @Test
    @DisplayName("identifier falls back to the last URL segment when no name is configured")
    void identifierFallsBackToUrlSegment() {
        SqsQueue sqs = new SqsQueue(client, QUEUE_URL, null, 0, clock);
        assertThat(sqs.identifier()).isEqualTo("my-queue");
    }

    @Test
    @DisplayName("a blank configured name also falls back to the last URL segment")
    void identifierFallsBackWhenConfiguredNameBlank() {
        SqsQueue sqs = new SqsQueue(client, QUEUE_URL, "  ", 0, clock);
        assertThat(sqs.identifier()).isEqualTo("my-queue");
    }

    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
