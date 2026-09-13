package io.flowcatalyst.platform.shared.database;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.http.Admission;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/// Tier 1 — the pool gate (`docs/spec/admission.md` §1). A [DataSource] over
/// the real pool whose `getConnection()` first takes an **untimed** permit
/// from a semaphore sized exactly to the pool, so a caller never reaches
/// HikariCP's timed handoff wait (a `parkNanos` through the JDK's
/// delay-scheduler thread, ~3 kernel switches per waiter on one core — the
/// cost measured in `../test-size/RESULTS.md`). A permit holder always finds
/// a free connection. The gate's size is the pool's by identity; there is no
/// knob.
///
/// - `reserved = max(1, poolSize / 16)` permits are set aside for
///   [#forProbes()] so `/health` and `/ready` stay truthful under saturation.
/// - Inside a request scope ([Admission#CURRENT]), a checkout while already
///   holding a connection throws before waiting — the nested-acquire guard.
public final class GatedDataSource implements DataSource, AutoCloseable {
    private final DataSource delegate;
    private final HikariDataSource hikari;
    private final int poolSize;
    private final int reserved;
    private final Lane ordinary;
    private final Lane probes;

    private GatedDataSource(DataSource delegate, HikariDataSource hikari, int poolSize) {
        if (poolSize < 1) throw new IllegalArgumentException("pool size must be positive: " + poolSize);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.hikari = hikari;
        this.poolSize = poolSize;
        this.reserved = reservedFor(poolSize);
        this.ordinary = new Lane("ordinary", poolSize - reserved);
        this.probes = new Lane("probe", reserved);
    }

    /// The production gate over a Hikari pool, sized to its `maximumPoolSize`.
    public static GatedDataSource over(HikariDataSource hikari) {
        return new GatedDataSource(hikari, hikari, hikari.getMaximumPoolSize());
    }

    /// A gate over any [DataSource] with an explicit size, for tests.
    public static GatedDataSource over(DataSource delegate, int poolSize) {
        return new GatedDataSource(delegate, null, poolSize);
    }

    /// One sixteenth of the pool, at least one — except that a pool of one
    /// connection (fcdev's one-off commands) reserves nothing, and probes
    /// then share the ordinary lane.
    static int reservedFor(int poolSize) {
        return poolSize < 2 ? 0 : Math.max(1, poolSize / 16);
    }

    public int poolSize() {
        return poolSize;
    }

    public int reserved() {
        return reserved;
    }

    /// Permits for ordinary callers: `poolSize − reserved`. The request workers'
    /// main pool is sized to this so a worker never waits at the gate.
    public int ordinaryPermits() {
        return poolSize - reserved;
    }

    /// The Hikari pool underneath, for credential rotation
    /// (`DbSecretRefresher`); `null` when built over a plain [DataSource].
    public HikariDataSource hikari() {
        return hikari;
    }

    /// The view the readiness probes use: its checkouts take from the
    /// reserved permits and never from the ordinary ones.
    public DataSource forProbes() {
        return new ProbeView();
    }

    public int waiting() {
        return ordinary.waiting.get();
    }

    public int held() {
        return ordinary.held.get();
    }

    public int probesHeld() {
        return probes.held.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return checkout(ordinary);
    }

    private Connection checkout(Lane lane) throws SQLException {
        Admission admission = Admission.currentOrNull();
        if (admission != null && admission.mode() == Admission.Mode.PINNED && admission.held() > 0) {
            // Re-entrant checkout: a request has one connection. A repository read inside a
            // transaction-scoped operation joins the transaction the outer checkout holds
            // (it sees the transaction's own writes, as it must) instead of taking a second
            // connection — which under a full gate is a deadlock, the case the corpus hit at
            // five sites on 2026-09-06. The handle's close() releases nothing and it may not
            // commit, roll back or change auto-commit: the outer owns the transaction.
            //
            // PER_STATEMENT scopes (API_READ, BFF — admission.md §11.7 part B) never take
            // this branch: nothing is pinned to join, and every checkout — however many are
            // concurrently outstanding — goes through the ordinary path below and is
            // returned to the pool independently when its own Connection closes.
            Connection outer = admission.heldConnections().get(0);
            Connection pooled = outer instanceof GatedConnection g ? g.delegate() : outer;
            return new GatedConnection(pooled, () -> { }, false);
        }
        lane.waiting.incrementAndGet();
        try {
            lane.permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for a database connection", "57014", e);
        } finally {
            lane.waiting.decrementAndGet();
        }
        lane.held.incrementAndGet();
        Connection pooled;
        try {
            pooled = delegate.getConnection();
        } catch (SQLException | RuntimeException e) {
            lane.held.decrementAndGet();
            lane.permits.release();
            throw e;
        }
        var holder = new Connection[1];
        GatedConnection gated = new GatedConnection(pooled, () -> {
            lane.held.decrementAndGet();
            lane.permits.release();
            if (admission != null) admission.released(holder[0]);
        });
        holder[0] = gated;
        if (admission != null) admission.checkedOut(gated);
        return gated;
    }

    /// `fc_db_gate_waiting{pool,lane}` and `fc_db_gate_held{pool,lane}`, `pool`
    /// naming which of [io.flowcatalyst.platform.shared.database.Pools]'s four
    /// physical sources this gate is (`api` / `bff` / `dispatch` / `background`)
    /// so the two group-sized gates on the request path — and the background
    /// one — are distinguishable in the same registry (`docs/spec/admission.md`
    /// §11.7).
    public MultiCollector collector(String poolName) {
        java.util.Objects.requireNonNull(poolName, "poolName");
        return () -> {
            var w = GaugeSnapshot.builder().name("fc_db_gate_waiting")
                    .help("Callers parked (untimed) on the pool gate, by pool and lane.");
            var h = GaugeSnapshot.builder().name("fc_db_gate_held")
                    .help("Pool-gate permits currently held, by pool and lane.");
            for (Lane lane : new Lane[] {ordinary, probes}) {
                var labels = Labels.of("pool", poolName, "lane", lane.name);
                w.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labels).value(lane.waiting.get()).build());
                h.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labels).value(lane.held.get()).build());
            }
            return MetricSnapshots.builder().metricSnapshot(w.build()).metricSnapshot(h.build()).build();
        };
    }

    /// Back-compat for a single-pool caller (fcdev one-off commands, tests):
    /// names the series `"default"`.
    public MultiCollector collector() {
        return collector("default");
    }

    @Override
    public void close() {
        if (hikari != null) hikari.close();
        else if (delegate instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                throw new IllegalStateException("closing the delegate data source", e);
            }
        }
    }

    // ── DataSource plumbing nothing here uses ─────────────────────────────

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("per-call credentials are not supported through the pool gate");
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
        if (iface.isInstance(this)) return iface.cast(this);
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    private static final class Lane {
        final String name;
        final Semaphore permits;
        final AtomicInteger waiting = new AtomicInteger();
        final AtomicInteger held = new AtomicInteger();

        Lane(String name, int permits) {
            this.name = name;
            this.permits = new Semaphore(permits);
        }
    }

    private final class ProbeView implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return checkout(reserved == 0 ? ordinary : probes);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return GatedDataSource.this.getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return GatedDataSource.this.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            GatedDataSource.this.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            GatedDataSource.this.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return GatedDataSource.this.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return GatedDataSource.this.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return GatedDataSource.this.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return GatedDataSource.this.isWrapperFor(iface);
        }
    }
}
