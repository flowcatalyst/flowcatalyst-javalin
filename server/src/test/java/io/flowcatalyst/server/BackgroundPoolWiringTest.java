package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/admission.md` §11.7: "every background subsystem gets
/// `pools.background()`", never a request-path pool. Pins **pool identity**
/// (which physical [GatedDataSource] a subsystem's connection came from), not
/// merely that the subsystem started — a subsystem handed the wrong pool
/// still runs, it just contends with the request path (exactly the defect
/// the split exists to prevent).
///
/// [Mode.Worker] never builds [Platform] (`Server#buildApiAndReaper`'s
/// `Mode.Worker _, Mode.RouterOnly _ -> {}` branch), so with the router off
/// nothing at all touches `api`/`bff`/`dispatch` in this test — every
/// connection is attributable to a background subsystem. The outbox
/// processor's `PostgresOutboxRepository#initSchema` runs synchronously
/// inside `Server#start` (before the async poll loop even exists), so the
/// assertion needs no wait/retry.
///
/// Mutant: in `Server#start`, change `case Mode.Worker(var pools) ->
/// pools.background();` to `pools.api();` → `backgroundConnections` stays 0
/// and `apiConnections` becomes positive, failing the test.
class BackgroundPoolWiringTest {

    @Test
    void outboxAndPurgerCheckOutFromTheBackgroundPoolNotApi() {
        var api = new CountingDataSource(TestPg.dataSource());
        var bff = new CountingDataSource(TestPg.dataSource());
        var dispatch = new CountingDataSource(TestPg.dataSource());
        var background = new CountingDataSource(TestPg.dataSource());
        var pools = new Pools(
                GatedDataSource.over(api, 4),
                GatedDataSource.over(bff, 4),
                GatedDataSource.over(dispatch, 4),
                GatedDataSource.over(background, 4));

        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "false",
                "FC_ROUTER_ENABLED", "false",
                "FC_OUTBOX_ENABLED", "true",
                "FC_OUTBOX_PLATFORM_URL", "http://localhost:1"));
        var running = new Server(env, new Server.Mode.Worker(pools), Server.Spa.none(), new PrometheusRegistry()).start();
        try {
            assertThat(background.connections.get())
                    .as("outbox/purger connections through the background pool")
                    .isGreaterThan(0);
            assertThat(api.connections.get()).as("no request-path pool touched in Worker mode").isZero();
            assertThat(bff.connections.get()).as("no request-path pool touched in Worker mode").isZero();
            assertThat(dispatch.connections.get()).as("no request-path pool touched in Worker mode").isZero();
        } finally {
            running.stop();
        }
    }

    /// Counts every `getConnection()` call, delegating the connection itself
    /// to a real (migrated, shared) `DataSource` — `TestPg.dataSource()`
    /// several times over, since [Pools] needs four distinct instances to
    /// gate independently but the underlying database is the same fixture
    /// every other test in this module shares.
    private static final class CountingDataSource implements DataSource {
        private final DataSource delegate;
        final AtomicInteger connections = new AtomicInteger();

        CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            connections.incrementAndGet();
            return delegate.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
