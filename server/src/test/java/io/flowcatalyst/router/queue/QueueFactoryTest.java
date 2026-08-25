package io.flowcatalyst.router.queue;

import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.queue.nats.NatsQueue;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.router.queue.sqs.SqsQueue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Optional;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/// [QueueFactory] against the scheme-resolution rules of `docs/spec/router.md`
/// §7.1. No network, broker or database is contacted: [SqsQueue#create] and
/// `new NatsQueue(uri)` are exercised for real, but only their *construction*
/// path — an SQS client is never asked to make a call, and a NATS connection
/// attempt against an address nothing listens on is expected to fail fast and
/// be swallowed as [Optional#empty()], which is itself the behaviour under
/// test (CONVENTIONS §6: assert behaviour, not code existence).
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
    @DisplayName("builds a PostgresQueue for a postgres:// queue when a data source is configured")
    void buildsPostgresConsumer() {
        var factory = new QueueFactory(UNUSED_DATA_SOURCE);
        var config = new QueueConfig("postgres://ignored/db", "my-pg-queue", 1, 30);

        var consumer = factory.create(config);

        assertThat(consumer).isPresent();
        assertThat(consumer.get()).isInstanceOf(PostgresQueue.class);
        assertThat(consumer.get().identifier()).isEqualTo("my-pg-queue");
    }

    @Test
    @DisplayName("refuses to build a postgres consumer when no data source is configured, without throwing")
    void refusesPostgresWithoutDataSource() {
        var factory = new QueueFactory(null);
        var config = QueueConfig.of("postgres://ignored/db");

        assertThat(factory.create(config)).isEmpty();
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
