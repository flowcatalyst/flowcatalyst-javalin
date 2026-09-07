package io.flowcatalyst.router.queue;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.nats.NatsQueue;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.router.queue.sqs.SqsQueue;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [QueueFactory] against the scheme-resolution rules of `docs/spec/router.md`
/// §7.1. No network, broker or database is contacted: [SqsQueue#create] and
/// `new NatsQueue(uri)` are exercised for real, but only their *construction*
/// path — an SQS client is never asked to make a call, and a NATS connection
/// attempt against an address nothing listens on is expected to fail fast and
/// be swallowed as [Optional#empty()], which is itself the behaviour under
/// test (CONVENTIONS §6: assert behaviour, not code existence).
///
/// The exception: a `postgres://` URI that carries its own connection now
/// opens a real dedicated pool (`createPostgres` mirrors Go's
/// `pgxpool.New(ctx, cfg.URI)`, §7.3), so those cases run against the same
/// embedded [TestPg] Postgres [io.flowcatalyst.router.queue.postgres.PostgresQueueTest]
/// uses — real behaviour, not a stub (CONVENTIONS §6).
class QueueFactoryTest {

    /// A [DataSource] that is never actually asked for a connection in these
    /// tests — [PostgresQueue]'s constructor stores it without dereferencing
    /// it — but is a real, hand-written stub rather than a mock (CONVENTIONS
    /// §6/§7: no mocking library).
    private static final DataSource UNUSED_DATA_SOURCE = new DataSource() {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not used by this test");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    };

    // ---- §7.1 scheme resolution -------------------------------------------------

    @ParameterizedTest(name = "[{2}] {0} -> {1}")
    @DisplayName("resolves the backend key per §7.1, including the SQS-over-https special case and its near misses")
    @CsvSource({
            // uri, expectedScheme, rule
            "sqs://my-queue,                                       sqs,      bare registered scheme",
            "postgres://host/db,                                   postgres, bare registered scheme",
            "nats://host:4222,                                     nats,     bare registered scheme",
            "https://sqs.us-east-1.amazonaws.com/123/q,            sqs,      https sqs standard endpoint",
            "http://sqs.us-east-1.amazonaws.com/123/q,              sqs,      http sqs standard endpoint",
            "https://sqs-fips.us-east-1.amazonaws.com/123/q,       sqs,      https sqs-fips endpoint",
            "https://sqs.us-east-1.amazonaws.com.cn/123/q,         sqs,      amazonaws.com.cn still contains .amazonaws.",
            "https://notsqs.us-east-1.amazonaws.com/123/q,         https,    near miss: host does not start with sqs.",
            "https://sqs.us-east-1.example.com/123/q,               https,    near miss: host is not under .amazonaws.",
            "https://sqsfoo.us-east-1.amazonaws.com/123/q,         https,    near miss: sqs prefix with no dot",
            "amqp://host/q,                                        amqp,     unknown scheme",
            "sqs,                                                  sqs,      no separator; whole string is the key",
            "gopher,                                                gopher,   no separator; unknown whole-string key",
    })
    void resolvesScheme(String uri, String expectedScheme, String rule) {
        assertThat(QueueFactory.resolveScheme(uri)).as(rule).isEqualTo(expectedScheme);
    }

    // ---- create() dispatch -------------------------------------------------------

    @Test
    @DisplayName("builds a PostgresQueue backed by the shared data source when the queue URI carries no connection of its own")
    void buildsPostgresConsumerFromSharedDataSource() {
        var factory = new QueueFactory(UNUSED_DATA_SOURCE);
        // No host (`URI#getHost` is null for an empty authority) — nothing of
        // its own to connect with, so this must fall back to the shared pool
        // rather than dereferencing the URI as a connection string.
        var config = new QueueConfig("postgres:///db", "my-pg-queue", 1, 30);

        var consumer = factory.create(config);

        assertThat(consumer).isPresent();
        assertThat(consumer.get()).isInstanceOf(PostgresQueue.class);
        assertThat(consumer.get().identifier()).isEqualTo("my-pg-queue");
    }

    @Test
    @DisplayName("refuses to build a postgres consumer when no data source is configured and the queue URI carries no connection of its own")
    void refusesPostgresWithoutDataSource() {
        var factory = new QueueFactory(null);
        var config = QueueConfig.of("postgres:///db");

        assertThat(factory.create(config)).isEmpty();
    }

    // ---- createPostgres: connects from the queue URI like Go (§7.3) --------------

    /// The embedded [TestPg] Postgres's own connection string — user
    /// `postgres`, no password (trust auth on localhost), database
    /// `postgres` — so a queue URI naming it looks exactly like a real
    /// operator-supplied `postgres://user:pass@host/db` queue.
    private static String testPgUri() {
        return "postgres://postgres@localhost:" + TestPg.instance().getPort() + "/postgres";
    }

    @Test
    @DisplayName("a postgres:// URI with its own host opens a dedicated pool and actually polls from it, with no shared data source at all (§7.3, Go pgxpool.New parity)")
    void buildsPostgresConsumerWithItsOwnPoolFromTheUri() throws InterruptedException {
        PostgresQueue.initSchema(TestPg.dataSource());
        String queueName = "own-pool-queue-" + UUID.randomUUID();
        var factory = new QueueFactory(null); // no shared pool at all
        var config = new QueueConfig(testPgUri(), queueName, 1, 30);

        var consumer = factory.create(config);
        assertThat(consumer).as("a URI with its own host must not need a shared data source").isPresent();
        assertThat(consumer.get()).isInstanceOf(PostgresQueue.class);

        try {
            // Inserted through TestPg's OWN connection, not the consumer's —
            // proves the dedicated pool the factory opened is a real,
            // independent connection to the SAME database, not a stub that
            // merely stored the URI.
            insertRow(queueName, "msg-1");

            Consumer.PollResult result = consumer.get().poll(10);
            assertThat(result).isInstanceOf(Consumer.PollResult.Delivered.class);
            var delivered = (Consumer.PollResult.Delivered) result;
            assertThat(delivered.messages()).extracting(QueuedMessage::id).containsExactly("msg-1");
        } finally {
            consumer.get().close();
        }
    }

    @Test
    @DisplayName("prefers the shared data source over opening a second pool when the queue URI names the identical database")
    void reusesTheSharedDataSourceWhenTheQueueUriNamesTheSameDatabase() {
        // sharedDatabaseUrl == the queue's own URI (after Database#toJdbc
        // normalisation) — Router#defaultQueueUri's exact scenario: the
        // synthesised default-broker queue's URI IS the platform's own
        // database URL rewritten to postgres://.
        var factory = new QueueFactory(UNUSED_DATA_SOURCE, testPgUri());
        var config = new QueueConfig(testPgUri(), "shared-pool-queue", 1, 30);

        var consumer = factory.create(config);

        assertThat(consumer).isPresent();
        // UNUSED_DATA_SOURCE throws on getConnection() — if the factory had
        // opened its own pool instead of reusing the shared one, the poll
        // below would throw rather than merely finding nothing.
        assertThatThrownBy(() -> consumer.get().poll(10))
                .as("this consumer must be backed by the (stub) shared data source, not a real pool")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void insertRow(String queueName, String id) {
        String sql = """
                INSERT INTO queue_messages (id, queue_name, message_group_id, visible_at, payload, created_at)
                VALUES (?, ?, NULL, ?, ?, ?)
                """;
        long now = Instant.now().getEpochSecond();
        Message payload = new Message(id, "", null, null, MediationType.HTTP,
                "https://example.test/hook", null, false, DispatchMode.IMMEDIATE);
        try (Connection conn = TestPg.dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, queueName);
            ps.setLong(3, now);
            ps.setString(4, Json.write(payload));
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("builds an SqsQueue for an https SQS endpoint without contacting AWS")
    void buildsSqsConsumer() {
        var factory = new QueueFactory(null);
        var config = new QueueConfig(
                "https://sqs.us-east-1.amazonaws.com/123456789012/my-sqs-queue", "my-sqs-queue", 1, 30);

        var consumer = factory.create(config);

        assertThat(consumer).isPresent();
        assertThat(consumer.get()).isInstanceOf(SqsQueue.class);
        assertThat(consumer.get().identifier()).isEqualTo("my-sqs-queue");
    }

    @Test
    @DisplayName("returns empty rather than throwing when a nats:// queue cannot connect")
    void returnsEmptyForUnreachableNats() {
        var factory = new QueueFactory(null);
        // Port 1 is a privileged port nothing listens on in a test sandbox;
        // NatsQueue's constructor connects eagerly and must fail fast.
        var config = QueueConfig.of("nats://127.0.0.1:1?stream=test&consumer=test");

        Optional<Consumer> consumer = factory.create(config);

        assertThat(consumer).isEmpty();
    }

    @Test
    @DisplayName("returns empty for a queue whose scheme has no registered consumer, without throwing")
    void returnsEmptyForUnknownScheme() {
        var factory = new QueueFactory(UNUSED_DATA_SOURCE);
        var config = QueueConfig.of("amqp://host/my-queue");

        assertThat(factory.create(config)).isEmpty();
    }
}
