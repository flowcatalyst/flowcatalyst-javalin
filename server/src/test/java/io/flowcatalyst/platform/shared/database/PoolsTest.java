package io.flowcatalyst.platform.shared.database;

import io.flowcatalyst.http.Admission;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/admission.md` §11.7 / §11.3a: four physical pools opened from
/// one budget `B`, sized `api = B/2`, `bff = B/4`, `dispatch = B/4`,
/// `background = 4` (outside `B`), each overridable with
/// `FC_DB_POOL_SIZE_<GROUP>`, every pool at least [Pools#MIN_POOL_SIZE].
class PoolsTest {

    private static String url() {
        return TestPg.instance().getJdbcUrl("postgres", "postgres");
    }

    @Test
    void opensFourPoolsSizedFromTheDefaultBudget() {
        // Mutant: swap a share, e.g. api = B/4 instead of B/2 — this fails.
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of()))) {
            assertThat(pools.api().poolSize()).isEqualTo(Pools.DEFAULT_BUDGET / 2);
            assertThat(pools.bff().poolSize()).isEqualTo(Pools.DEFAULT_BUDGET / 4);
            assertThat(pools.dispatch().poolSize()).isEqualTo(Pools.DEFAULT_BUDGET / 4);
            assertThat(pools.background().poolSize()).isEqualTo(Pools.BACKGROUND_POOL_SIZE);
        }
    }

    @Test
    void fcDbPoolSizeRescalesEveryShareTogether() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of("FC_DB_POOL_SIZE", "40")))) {
            assertThat(pools.api().poolSize()).isEqualTo(20);
            assertThat(pools.bff().poolSize()).isEqualTo(10);
            assertThat(pools.dispatch().poolSize()).isEqualTo(10);
            // background is fixed, outside B.
            assertThat(pools.background().poolSize()).isEqualTo(Pools.BACKGROUND_POOL_SIZE);
        }
    }

    @Test
    void perGroupOverrideWinsOverTheDerivedShare() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of(
                "FC_DB_POOL_SIZE_API", "9",
                "FC_DB_POOL_SIZE_BFF", "5",
                "FC_DB_POOL_SIZE_DISPATCH", "6",
                "FC_DB_POOL_SIZE_BACKGROUND", "7")))) {
            assertThat(pools.api().poolSize()).isEqualTo(9);
            assertThat(pools.bff().poolSize()).isEqualTo(5);
            assertThat(pools.dispatch().poolSize()).isEqualTo(6);
            assertThat(pools.background().poolSize()).isEqualTo(7);
        }
    }

    @Test
    void everyPoolIsAtLeastTwoEvenFromATinyBudgetOrOverride() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of(
                "FC_DB_POOL_SIZE", "1",
                "FC_DB_POOL_SIZE_BACKGROUND", "1")))) {
            // budget=1 -> api=0, bff=0, dispatch=0 before the floor.
            assertThat(pools.api().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
            assertThat(pools.bff().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
            assertThat(pools.dispatch().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
            assertThat(pools.background().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
        }
    }

    @Test
    void forGroupMapsToTheRightPhysicalPoolAndNoDbHasNone() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of()))) {
            assertThat(pools.forGroup(Group.API_READ)).isSameAs(pools.api());
            assertThat(pools.forGroup(Group.API_WRITE)).isSameAs(pools.api());
            assertThat(pools.forGroup(Group.LOGIN)).isSameAs(pools.api());
            assertThat(pools.forGroup(Group.OIDC)).isSameAs(pools.api());
            assertThat(pools.forGroup(Group.BFF)).isSameAs(pools.bff());
            assertThat(pools.forGroup(Group.DISPATCH)).isSameAs(pools.dispatch());
            assertThatThrownBy(() -> pools.forGroup(Group.NO_DB)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /// The scheduler's pool: leaves at least `dispatchers + 2` ordinary permits
    /// (the gate sets `max(1, size/16)` aside for probes), and is the SMALLEST
    /// such size. Mutant: size it as `dispatchers + 2` outright — the probe
    /// reservation then eats into the permits and the first assertion fails.
    @Test
    void theSchedulerPoolLeavesDispatchersPlusTwoOrdinaryPermitsAndNoMore() {
        for (int dispatchers = 1; dispatchers <= 200; dispatchers++) {
            int size = Pools.schedulerPoolSizeFor(dispatchers);
            var gate = GatedDataSource.over(TestPg.dataSource(), size);
            assertThat(gate.ordinaryPermits()).as("dispatchers=%d size=%d", dispatchers, size)
                    .isGreaterThanOrEqualTo(dispatchers + 2);
            if (size > Pools.MIN_POOL_SIZE) {
                var smaller = GatedDataSource.over(TestPg.dataSource(), size - 1);
                assertThat(smaller.ordinaryPermits()).as("one smaller is too small (dispatchers=%d)", dispatchers)
                        .isLessThan(dispatchers + 2);
            }
        }
    }

    @Test
    void theSchedulerPoolAtTheDefaultTenDispatchersIsThirteenWithTwelveOrdinaryPermits() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of()), 10)) {
            assertThat(pools.scheduler()).isNotNull();
            assertThat(pools.scheduler().poolSize()).isEqualTo(13);
            assertThat(pools.scheduler().reserved()).isEqualTo(1);
            assertThat(pools.scheduler().ordinaryPermits()).isEqualTo(12);
            // The other four are untouched by it.
            assertThat(pools.background().poolSize()).isEqualTo(Pools.BACKGROUND_POOL_SIZE);
            assertThat(pools.api().poolSize()).isEqualTo(Pools.DEFAULT_BUDGET / 2);
        }
    }

    @Test
    void fcDbPoolSizeSchedulerOverridesTheDerivedSizeWithTheUsualFloor() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of("FC_DB_POOL_SIZE_SCHEDULER", "20")), 10)) {
            assertThat(pools.scheduler().poolSize()).isEqualTo(20);
        }
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of("FC_DB_POOL_SIZE_SCHEDULER", "1")), 10)) {
            assertThat(pools.scheduler().poolSize()).isEqualTo(Pools.MIN_POOL_SIZE);
        }
    }

    /// Mutant: open it unconditionally — a platform without the scheduler holds
    /// idle connections it never uses.
    @Test
    void theSchedulerPoolIsNotOpenedWhenTheSchedulerIsDisabled() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of("FC_DB_POOL_SIZE_SCHEDULER", "20")))) {
            assertThat(pools.scheduler()).isNull();
        }
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of()), 0)) {
            assertThat(pools.scheduler()).isNull();
            var registry = new PrometheusRegistry();
            pools.registerCollectors(registry);
            assertThat(poolLabels(registry)).containsExactlyInAnyOrder("api", "bff", "dispatch", "background");
        }
    }

    @Test
    void theSchedulerPoolHasItsOwnLabelledGateSeries() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of("FC_DB_POOL_SIZE", "8")), 4)) {
            var registry = new PrometheusRegistry();
            pools.registerCollectors(registry);
            assertThat(poolLabels(registry)).containsExactlyInAnyOrder("api", "bff", "dispatch", "background",
                    "scheduler");
        }
    }

    private static Set<String> poolLabels(PrometheusRegistry registry) {
        return registry.scrape().stream()
                .filter(s -> s.getMetadata().getName().equals("fc_db_gate_waiting"))
                .flatMap(s -> ((io.prometheus.metrics.model.snapshots.GaugeSnapshot) s).getDataPoints().stream())
                .map(dp -> dp.getLabels().get("pool"))
                .collect(Collectors.toSet());
    }

    @Test
    void theCollectorExposesFourLabelledSeries() {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of("FC_DB_POOL_SIZE", "8")))) {
            var registry = new PrometheusRegistry();
            pools.registerCollectors(registry);

            // Four SEPARATE registered collectors, so scrape() returns four separate
            // same-named GaugeSnapshots (`MetricSnapshots` does not merge across
            // collectors, `MetricSnapshots.Builder#metricSnapshot`) — collect the
            // `pool` label across every one of them, not just the first.
            MetricSnapshots snapshot = registry.scrape();
            Set<String> poolLabels = snapshot.stream()
                    .filter(s -> s.getMetadata().getName().equals("fc_db_gate_waiting"))
                    .flatMap(s -> ((io.prometheus.metrics.model.snapshots.GaugeSnapshot) s).getDataPoints().stream())
                    .map(dp -> dp.getLabels().get("pool"))
                    .collect(Collectors.toSet());

            assertThat(poolLabels).containsExactlyInAnyOrder("api", "bff", "dispatch", "background");
        }
    }

    /// `docs/spec/admission.md` §11.7 part B: "the pool is chosen by the request, not by
    /// the handler class". `routed()` resolves the physical pool from `Admission.CURRENT`'s
    /// group, so the SAME repository instance (here, a raw counting `DataSource` consumer
    /// standing in for one — `BackgroundPoolWiringTest`'s own pattern) lands on `bff` for a
    /// BFF request and `api` for an API_READ one. Mutant: `routed()` hard-coded to always
    /// return `api` — the BFF assertion below fails (0 connections on the bff counter).
    @Test
    void routedResolvesThePhysicalPoolFromTheCurrentRequestsGroupThroughOneSharedSource() throws Exception {
        var api = new CountingDataSource(TestPg.dataSource());
        var bff = new CountingDataSource(TestPg.dataSource());
        var dispatch = new CountingDataSource(TestPg.dataSource());
        var background = new CountingDataSource(TestPg.dataSource());
        try (Pools pools = new Pools(
                GatedDataSource.over(api, 4), GatedDataSource.over(bff, 4),
                GatedDataSource.over(dispatch, 4), GatedDataSource.over(background, 4))) {
            DataSource routed = pools.routed();

            // one shared "repository" — routed() itself — used from two different groups.
            ScopedValue.where(Admission.CURRENT, new Admission("/bff/dashboard", Group.BFF)).run(() -> {
                try (Connection c = routed.getConnection()) {
                    assertThat(c).isNotNull();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(bff.connections.get()).as("a BFF request resolves the BFF physical pool").isEqualTo(1);
            assertThat(api.connections.get()).as("not the API pool").isZero();

            ScopedValue.where(Admission.CURRENT, new Admission("/api/clients", Group.API_READ)).run(() -> {
                try (Connection c = routed.getConnection()) {
                    assertThat(c).isNotNull();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(api.connections.get()).as("an API_READ request resolves the API physical pool").isEqualTo(1);
            assertThat(bff.connections.get()).as("unchanged by the second request").isEqualTo(1);
            assertThat(dispatch.connections.get()).isZero();
            assertThat(background.connections.get()).isZero();
        }
    }

    @Test
    void routedWithNoAdmissionScopeBoundThrows() {
        try (Pools pools = Pools.ofSingle(TestPg.dataSource())) {
            assertThatThrownBy(() -> pools.routed().getConnection()).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void routedForNoDbThrowsJustLikeForGroupDoes() {
        try (Pools pools = Pools.ofSingle(TestPg.dataSource())) {
            ScopedValue.where(Admission.CURRENT, new Admission("/health", Group.NO_DB)).run(() ->
                    assertThatThrownBy(() -> pools.routed().getConnection()).isInstanceOf(IllegalArgumentException.class));
        }
    }

    /// Counts every `getConnection()` call, delegating to a real (migrated, shared)
    /// `DataSource` — same shape as `BackgroundPoolWiringTest`'s own fixture.
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

    private static String show(javax.sql.DataSource ds, String setting) throws Exception {
        try (var c = ds.getConnection(); var st = c.createStatement(); var rs = st.executeQuery("SHOW " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /// The scheduler's pool, and only it, plans with `plan_cache_mode = force_custom_plan` and
    /// `enable_sort = off` (dispatch-queue spec step 3b §5). Mutant: set them on every pool.
    @Test
    void onlyTheSchedulersPoolCarriesThePlannerSettings() throws Exception {
        try (Pools pools = Pools.open(url(), new EnvReader(Map.of()), 10)) {
            assertThat(show(pools.scheduler(), "plan_cache_mode")).isEqualTo("force_custom_plan");
            assertThat(show(pools.scheduler(), "enable_sort")).isEqualTo("off");
            for (var other : List.of(pools.api(), pools.bff(), pools.dispatch(), pools.background())) {
                assertThat(show(other, "plan_cache_mode")).isEqualTo("auto");
                assertThat(show(other, "enable_sort")).isEqualTo("on");
            }
        }
    }
}
