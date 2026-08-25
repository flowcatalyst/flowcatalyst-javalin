package io.flowcatalyst.router.queue.postgres;

import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [PostgresQueue] against a real embedded Postgres (`docs/spec/router.md`
/// §7.3). Each test uses a fresh queue name so tests never see each other's
/// rows (CONVENTIONS §6) — no truncation between tests.
class PostgresQueueTest {

    private static final DataSource DS = TestPg.dataSource();

    @BeforeAll
    static void initSchema() {
        PostgresQueue.initSchema(DS);
    }

    private static String freshQueue() {
        return "test-queue-" + UUID.randomUUID();
    }

    private static Message message(String id, String groupId) {
        return new Message(id, "", null, null, MediationType.HTTP,
                "https://example.test/hook", groupId, false, DispatchMode.IMMEDIATE);
    }

    /// Inserts a row directly (there is no in-scope Publisher yet) so a test
    /// controls `visible_at` / `created_at` precisely.
    private static void insert(String queueName, String id, String groupId, String payload,
                                long visibleAt, long createdAt) {
        String sql = """
                INSERT INTO queue_messages (id, queue_name, message_group_id, visible_at, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = DS.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, queueName);
            ps.setString(3, groupId);
            ps.setLong(4, visibleAt);
            ps.setString(5, payload);
            ps.setLong(6, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void insertNowVisible(String queueName, String id, String groupId, Message payload) {
        long now = Instant.now().getEpochSecond();
        insert(queueName, id, groupId, io.flowcatalyst.platform.shared.json.Json.write(payload), now, now);
    }

    private static long rowCount(String queueName, String id) {
        try (Connection conn = DS.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM queue_messages WHERE queue_name = ? AND id = ?")) {
            ps.setString(1, queueName);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /// Waits out a one-second visibility window, with headroom for its
    /// whole-second granularity. Real time, because the
    /// window is enforced by the database's clock rather than anything the
    /// test can advance.
    private static void waitForVisibilityToLapse() {
        try {
            Thread.sleep(Duration.ofMillis(2_500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /// The recorded reason a row was rejected, or null when it was not.
    private static String errorMessageOf(String queueName, String id) {
        return columnOf(queueName, id, "error_message");
    }

    /// The stored payload — kept even when unparseable, because it is the
    /// only evidence of why it was.
    private static String payloadOf(String queueName, String id) {
        return columnOf(queueName, id, "payload");
    }

    private static String columnOf(String queueName, String id, String column) {
        try (Connection conn = DS.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT " + column + " FROM queue_messages WHERE queue_name = ? AND id = ?")) {
            ps.setString(1, queueName);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("a visible message is claimed on poll, with a receipt handle and the broker id set")
    void claimAndPoll() throws InterruptedException {
        String queue = freshQueue();
        String id = "m-" + UUID.randomUUID();
        insertNowVisible(queue, id, null, message(id, null));

        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            Consumer.PollResult result = consumer.poll(10);

            assertThat(result).isInstanceOf(Consumer.PollResult.Delivered.class);
            List<QueuedMessage> delivered = ((Consumer.PollResult.Delivered) result).messages();
            assertThat(delivered).hasSize(1);
            QueuedMessage claimed = delivered.getFirst();
            assertThat(claimed.brokerMessageId()).isEqualTo(id);
            assertThat(claimed.queueId()).isEqualTo(queue);
            assertThat(claimed.receiptHandle()).endsWith(":" + id);
            assertThat(claimed.message().id()).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("an empty queue polls empty, not an error")
    void pollingAnEmptyQueueIsEmptyNotAnError() throws InterruptedException {
        String queue = freshQueue();
        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            Consumer.PollResult result = consumer.poll(10);
            assertThat(result).isInstanceOf(Consumer.PollResult.Delivered.class);
            assertThat(((Consumer.PollResult.Delivered) result).messages()).isEmpty();
        }
    }

    @Test
    @DisplayName("a claimed message is not re-polled until its visibility lapses")
    void claimedMessageNotRePolledUntilVisibilityLapses() throws InterruptedException {
        String queue = freshQueue();
        String id = "m-" + UUID.randomUUID();
        insertNowVisible(queue, id, null, message(id, null));

        // visible_at is whole unix seconds, so a one-second window is a coin
        // flip: a claim at X.9s sets visible_at to X+1, and an "immediate"
        // re-poll a few milliseconds later can already be in second X+1 and
        // see it as visible. Three seconds puts the invisibility assertion
        // comfortably inside the window whatever the sub-second phase.
        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(3))) {
            var first = (Consumer.PollResult.Delivered) consumer.poll(10);
            assertThat(first.messages()).hasSize(1);

            var immediate = (Consumer.PollResult.Delivered) consumer.poll(10);
            assertThat(immediate.messages()).as("still invisible, not yet lapsed").isEmpty();

            Thread.sleep(3500);

            var afterLapse = (Consumer.PollResult.Delivered) consumer.poll(10);
            assertThat(afterLapse.messages()).as("visibility lapsed, redelivered").hasSize(1);
            assertThat(afterLapse.messages().getFirst().receiptHandle())
                    .as("a fresh receipt handle is issued on redelivery")
                    .isNotEqualTo(first.messages().getFirst().receiptHandle());
        }
    }

    @Test
    @DisplayName("ack permanently removes the row")
    void ackRemoves() throws InterruptedException {
        String queue = freshQueue();
        String id = "m-" + UUID.randomUUID();
        insertNowVisible(queue, id, null, message(id, null));

        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            var delivered = (Consumer.PollResult.Delivered) consumer.poll(10);
            QueuedMessage claimed = delivered.messages().getFirst();

            consumer.ack(claimed);

            assertThat(rowCount(queue, id)).isZero();
        }
    }

    @Test
    @DisplayName("acking an unknown receipt handle is logged, not thrown")
    void ackUnknownReceiptDoesNotThrow() {
        String queue = freshQueue();
        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            var bogus = QueuedMessage.of(message("nope", ""), "nope", "bogus-receipt", queue);
            consumer.ack(bogus); // must not throw
        }
    }

    @Test
    @DisplayName("nack makes the message visible again after the delay, not before")
    void nackMakesVisibleAfterDelay() throws InterruptedException {
        String queue = freshQueue();
        String id = "m-" + UUID.randomUUID();
        insertNowVisible(queue, id, null, message(id, null));

        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            var delivered = (Consumer.PollResult.Delivered) consumer.poll(10);
            QueuedMessage claimed = delivered.messages().getFirst();

            consumer.nack(claimed, Duration.ofSeconds(1));

            var tooSoon = (Consumer.PollResult.Delivered) consumer.poll(10);
            assertThat(tooSoon.messages()).as("delay not yet elapsed").isEmpty();

            Thread.sleep(1500);

            var afterDelay = (Consumer.PollResult.Delivered) consumer.poll(10);
            assertThat(afterDelay.messages()).as("delay elapsed, visible again").hasSize(1);
        }
    }

    @Test
    @DisplayName("nacking an unknown receipt handle is logged, not thrown")
    void nackUnknownReceiptDoesNotThrow() {
        String queue = freshQueue();
        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            var bogus = QueuedMessage.of(message("nope", ""), "nope", "bogus-receipt", queue);
            consumer.nack(bogus, Duration.ofSeconds(5)); // must not throw
        }
    }

    @Test
    @DisplayName("a malformed payload is marked errored, and the rest of the batch still delivers")
    void malformedPayloadIsMarkedErrored() throws InterruptedException {
        // Q17 ruled (owner, 2026-08-25). Go fails the WHOLE poll here, and the
        // row is already claimed — so it re-claims and re-fails forever and
        // every message behind it is never delivered. One bad row would stop
        // the queue permanently.
        String queue = freshQueue();
        String goodId = "good-" + UUID.randomUUID();
        String badId = "bad-" + UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        // Distinct groups (COALESCE(group,id)) so both are eligible in one poll.
        insert(queue, goodId, null, io.flowcatalyst.platform.shared.json.Json.write(message(goodId, "")), now, now);
        insert(queue, badId, null, "{not-json", now, now + 1);

        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            var delivered = (Consumer.PollResult.Delivered) consumer.poll(10);

            // The good message is unaffected by its neighbour being unusable.
            assertThat(delivered.messages()).singleElement()
                    .extracting(QueuedMessage::id).isEqualTo(goodId);
            assertThat(errorMessageOf(queue, badId))
                    .as("the failure is recorded on the row, not just logged")
                    .isNotNull();
            // Kept, not deleted: the payload is the only evidence of why it
            // was malformed.
            assertThat(payloadOf(queue, badId)).isEqualTo("{not-json");
        }
    }

    @Test
    @DisplayName("an errored row is never claimed again, however long the queue runs")
    void erroredRowIsNeverClaimedAgain() throws InterruptedException {
        String queue = freshQueue();
        String badId = "bad-" + UUID.randomUUID();
        long past = Instant.now().getEpochSecond() - 3600;
        insert(queue, badId, null, "{not-json", past, past);

        // A one-second visibility, waited out, so the row really is eligible
        // again on the second poll. That isolates error_at as the only thing
        // keeping it out; with the default visibility the test would pass
        // whether or not the guard existed. (Zero does not work: the spec
        // substitutes 30s for it.) The wait is the database's clock, which is
        // not injectable, so it is a real one.
        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(1))) {
            consumer.poll(10);
            waitForVisibilityToLapse();

            // Asserting "no message delivered" would prove nothing: a
            // re-claimed malformed row is marked and skipped, so it yields no
            // message either way. receive_count is what actually differs —
            // it counts claims, and a poison loop increments it forever.
            var second = (Consumer.PollResult.Delivered) consumer.poll(10);
            assertThat(second.messages()).isEmpty();
            assertThat(columnOf(queue, badId, "receive_count"))
                    .as("an errored row must never be claimed a second time")
                    .isEqualTo("1");
            assertThat(rowCount(queue, badId)).isOne();
        }
    }

    @Test
    @DisplayName("an errored row does not hold back its own message group")
    void erroredRowDoesNotBlockItsGroup() throws InterruptedException {
        // Ordering holds back later siblings behind an earlier one. If an
        // errored row still counted as an earlier sibling, marking it would
        // stop the queue in a subtler way than the poison loop did.
        String queue = freshQueue();
        String group = "grp-" + UUID.randomUUID();
        String badId = "a-bad-" + UUID.randomUUID();
        String goodId = "b-good-" + UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        insert(queue, badId, group, "{not-json", now, now);
        insert(queue, goodId, group, io.flowcatalyst.platform.shared.json.Json.write(message(goodId, group)), now, now + 1);

        // Waited out again, so the errored head is visible once more and
        // would still count as an earlier sibling — holding the group back —
        // unless error_at excludes it from the sibling check too.
        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(1))) {
            consumer.poll(10); // marks the bad head errored
            waitForVisibilityToLapse();

            var following = (Consumer.PollResult.Delivered) consumer.poll(10);
            assertThat(following.messages())
                    .as("the good sibling must not be held back by an errored head")
                    .singleElement()
                    .extracting(QueuedMessage::id).isEqualTo(goodId);
            // And the errored head itself was not re-claimed to get there.
            assertThat(columnOf(queue, badId, "receive_count")).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("after close, poll always answers Stopped and never touches the database")
    void closeThenPollIsStopped() throws InterruptedException {
        String queue = freshQueue();
        String id = "m-" + UUID.randomUUID();
        insertNowVisible(queue, id, null, message(id, null));

        PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30));
        consumer.close();

        assertThat(consumer.poll(10)).isEqualTo(Consumer.PollResult.STOPPED);
        assertThat(rowCount(queue, id)).as("row untouched").isEqualTo(1);
    }

    @Test
    @DisplayName("at most one message per group is claimed per poll")
    void atMostOneMessagePerGroupPerPoll() throws InterruptedException {
        String queue = freshQueue();
        String group = "grp-" + UUID.randomUUID();
        String first = "a-" + UUID.randomUUID();
        String second = "b-" + UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        insert(queue, first, group, io.flowcatalyst.platform.shared.json.Json.write(message(first, group)), now, now);
        insert(queue, second, group, io.flowcatalyst.platform.shared.json.Json.write(message(second, group)), now, now + 1);

        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            var result = (Consumer.PollResult.Delivered) consumer.poll(10);
            assertThat(result.messages()).as("only the earliest of the group").hasSize(1);
            assertThat(result.messages().getFirst().brokerMessageId()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("concurrent consumers never claim the same message twice")
    void concurrentConsumersDoNotDoubleClaim() throws InterruptedException {
        String queue = freshQueue();
        int total = 40;
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            String id = "c-" + i + "-" + UUID.randomUUID();
            ids.add(id);
            insertNowVisible(queue, id, null, message(id, null));
        }

        int workers = 8;
        Set<String> claimedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateClaims = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);

        for (int w = 0; w < workers; w++) {
            Thread.ofVirtual().start(() -> {
                try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
                    start.await();
                    for (int attempt = 0; attempt < 20; attempt++) {
                        var result = (Consumer.PollResult.Delivered) consumer.poll(5);
                        for (QueuedMessage m : result.messages()) {
                            if (!claimedIds.add(m.brokerMessageId())) {
                                duplicateClaims.incrementAndGet();
                            }
                        }
                        if (result.messages().isEmpty()) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("workers finished in time").isTrue();

        assertThat(duplicateClaims.get()).as("no message claimed by two workers").isZero();
        assertThat(claimedIds).as("every message claimed exactly once, none lost")
                .hasSize(total)
                .containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    @DisplayName("metrics reports pending and in-flight counts, and process-local counters")
    void metricsReportsPendingAndInFlight() throws InterruptedException {
        String queue = freshQueue();
        String pendingId = "p-" + UUID.randomUUID();
        String claimId = "c-" + UUID.randomUUID();
        insertNowVisible(queue, pendingId, null, message(pendingId, null));
        insertNowVisible(queue, claimId, null, message(claimId, null));

        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            consumer.poll(1); // claims exactly one (max=1), leaving the other pending

            java.util.Optional<QueueMetrics> metrics = consumer.metrics();
            assertThat(metrics).isPresent();
            assertThat(metrics.get().pending()).isEqualTo(1);
            assertThat(metrics.get().inFlight()).isEqualTo(1);
            assertThat(metrics.get().polled()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an already-interrupted thread fails poll with InterruptedException and clears the flag")
    void pollHonoursPriorInterruption() {
        String queue = freshQueue();
        try (PostgresQueue consumer = new PostgresQueue(DS, queue, Duration.ofSeconds(30))) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> consumer.poll(10)).isInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).as("flag was consumed by poll, not left dangling").isFalse();
        }
    }
}
