package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/// [PostgresQueuePublisher] against a real embedded Postgres
/// (`docs/spec/deployed-dispatch.md` §3, unit D part 1). Before this unit,
/// this publisher wrote every job to ONE fixed queue name derived from the
/// database URL (`Server#defaultQueueUri`) — the instant a router consumed
/// the platform's own served router-config document (unit B), which
/// advertises composed names like `platform-DEFAULT`, every dev dispatch job
/// would have published where nothing is listening. These tests pin that the
/// publisher now routes per (tenant, priority) exactly like
/// [SqsDispatchPublisher], through the shared [DispatchDestinationResolver].
class PostgresQueuePublisherTest {

    /// Postgres/dev-shaped settings: `sqs=false`, no prefix — the `.fifo`
    /// suffix and length cap never apply to a row-queue name.
    private static final DispatchQueueSettings SETTINGS = new DispatchQueueSettings(false, "", "", "", "");

    @BeforeAll
    static void initQueueSchema() {
        PostgresQueue.initSchema(DATA_SOURCE);
    }

    private static PostgresQueuePublisher publisher() {
        return new PostgresQueuePublisher(DATA_SOURCE, SETTINGS);
    }

    private static PublishedMessage published(String jobId, String clientId, String subscriptionId, String messageGroupId) {
        Message message = new Message(jobId, "pool-code", "auth-token", null, MediationType.HTTP,
                "http://localhost/api/dispatch/process", messageGroupId, false, DispatchMode.IMMEDIATE);
        return new PublishedMessage(jobId, Instant.now(), clientId, subscriptionId, null, message);
    }

    private static String queueNameFor(String jobId) {
        Record row = DB.fetchOne("SELECT queue_name FROM queue_messages WHERE id = ?", jobId);
        return row == null ? null : row.get("queue_name", String.class);
    }

    // ── (1) the defect this unit exists to fix ──────────────────────────────

    /// **The load-bearing assertion.** Mutant this pins: reverting to
    /// `Server#defaultQueueUri`-style naming (a database URL string) as the
    /// row's `queue_name` — the router would then be listening on
    /// `platform-DEFAULT` while the scheduler wrote to a completely
    /// different, connection-string-shaped name, and every client-less dev
    /// dispatch job would be published where nothing is listening.
    @Test
    void clientLessJobLandsInThePlatformDefaultRowQueueNotADatabaseUrl() throws Exception {
        String jobId = "pgpub-platform-" + RUN;

        publisher().publish(List.of(published(jobId, null, null, "g")));

        String queueName = queueNameFor(jobId);
        assertThat(queueName).isEqualTo("platform-DEFAULT");
        assertThat(queueName).as("never a database connection string").doesNotContain("://");
    }

    // ── (2) client-scoped and HIGH_PRIORITY jobs land in their own queues ──

    /// Mutant this pins: dropping tenant/priority resolution entirely (every
    /// job would land back in one shared queue) — the three destinations
    /// below would then collapse to one.
    @Test
    void clientScopedAndHighPriorityJobsLandInTheirOwnComposedRowQueues() throws Exception {
        String clientIdentifier = "pgpubacme" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String defaultSub = SchedulerFixture.subscriptionWithQueue(null);
        String highSub = SchedulerFixture.subscriptionWithQueue("HIGH_PRIORITY");

        String clientJobId = "pgpub-client-" + RUN;
        String platformJobId = "pgpub-plat2-" + RUN;
        String highJobId = "pgpub-high-" + RUN;

        publisher().publish(List.of(
                published(clientJobId, clientId, defaultSub, "g1"),
                published(platformJobId, null, null, "g2"),
                published(highJobId, clientId, highSub, "g3")));

        assertThat(queueNameFor(clientJobId)).isEqualTo(clientIdentifier + "-DEFAULT");
        assertThat(queueNameFor(platformJobId)).isEqualTo("platform-DEFAULT");
        assertThat(queueNameFor(highJobId)).isEqualTo(clientIdentifier + "-HIGH_PRIORITY");
    }

    // ── (3) both publishers resolve the same destination — anti-drift ──────

    /// **Asserted against the shared resolver, so the two publishers cannot
    /// drift** (`SqsDispatchPublisherTest` carries the mirror-image
    /// assertion for the SQS side). Mutant this pins: this publisher
    /// composing its own queue name inline instead of delegating to
    /// [DispatchDestinationResolver] — a hand-rolled composition that
    /// happened to agree for the platform-only cases above but diverged for
    /// a real client + HIGH_PRIORITY job would fail this comparison even
    /// though it might still pass the two tests above in isolation.
    @Test
    void resolvesExactlyWhatTheSharedResolverComputesForTheSameJob() throws Exception {
        String clientIdentifier = "pgpubshared" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String highSub = SchedulerFixture.subscriptionWithQueue("HIGH_PRIORITY");
        String jobId = "pgpub-shared-" + RUN;

        DispatchDestinationResolver resolver = new DispatchDestinationResolver(
                new PoolCodeResolver(DATA_SOURCE), new SubscriptionPriorityCache(DATA_SOURCE), SETTINGS);
        PublishedMessage message = published(jobId, clientId, highSub, "g");
        String expected = resolver.destinationFor(message).value();

        publisher().publish(List.of(message));

        assertThat(queueNameFor(jobId))
                .as("PostgresQueuePublisher must resolve exactly what the shared resolver computes")
                .isEqualTo(expected);
    }

    // ── (4) failure semantics unchanged by per-message routing ─────────────

    /// Mutant this pins: a per-destination chunking scheme that reports only
    /// PART of a heterogeneous (multi-tenant) batch as unpublished — the
    /// documented single-statement, all-or-nothing contract (ruling O2's
    /// carve-out for this publisher) must still hold even though the batch
    /// now spans more than one row-queue.
    @Test
    void aFailingInsertReportsTheWholeHeterogeneousBatchUnpublished() {
        PostgresQueuePublisher publisher = new PostgresQueuePublisher(FAILING_DATA_SOURCE, SETTINGS);
        String jobA = "pgpub-fail-a-" + RUN;
        String jobB = "pgpub-fail-b-" + RUN;

        var thrown = catchThrowableOfType(DispatchPublisher.PublishException.class,
                () -> publisher.publish(List.of(
                        published(jobA, null, null, "g1"),
                        published(jobB, null, null, "g2"))));

        assertThat(thrown).isNotNull();
        assertThat(thrown.unpublishedJobIds())
                .as("one statement, one transaction — a failure reports the WHOLE batch unpublished")
                .containsExactlyInAnyOrder(jobA, jobB);
    }

    /// A `DataSource` whose `getConnection()` always fails — the documented
    /// failure surface `PostgresQueueRows#insertBatch` propagates as a
    /// `SQLException` (CONVENTIONS §8 "pinned with a failing `DataSource`,
    /// not prose").
    private static final DataSource FAILING_DATA_SOURCE = new DataSource() {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("boom");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("boom");
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
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    };
}
