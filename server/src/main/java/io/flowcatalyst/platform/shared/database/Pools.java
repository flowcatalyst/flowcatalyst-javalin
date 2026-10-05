package io.flowcatalyst.platform.shared.database;

import io.flowcatalyst.http.Admission;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.server.EnvReader;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/// The physical connection pools a `Platform`/`Worker` instance opens
/// (`docs/spec/admission.md` §11.7, "Pools"): four, plus a fifth for the
/// dispatch scheduler when it is enabled — all against the SAME database —
/// isolation is the point (§11.3 ruling): a slow lane in one group can no
/// longer starve connections another group needs, and each can later point
/// at a different replica.
///
/// - `api`        (`½ B`)  — [Group#API_READ], [Group#API_WRITE], [Group#LOGIN], [Group#OIDC]
/// - `bff`        (`¼ B`)  — [Group#BFF]
/// - `dispatch`   (`¼ B`)  — [Group#DISPATCH]: every route the message router calls
/// - `background` (`4`, outside `B`) — outbox, stream, scheduled-job
///   scheduler, purger, mail sender, dispatch-job reaper, the router's own
///   housekeeping — never the request path
/// - `scheduler` (`dispatchers + 2` ordinary permits, outside `B`) — the
///   dispatch scheduler's poller and dispatcher lanes ([#schedulerPoolSizeFor]),
///   opened only when the scheduler is enabled; the scheduler may be deployed
///   with the platform or standalone, so it must not share a pool with
///   anything else
///
/// `B` (the budget) is [#DEFAULT_BUDGET] (32) unless `FC_DB_POOL_SIZE`
/// overrides it. Each of the four sizes may be overridden independently with
/// `FC_DB_POOL_SIZE_API` / `_BFF` / `_DISPATCH` / `_BACKGROUND` (and
/// `_SCHEDULER` for the scheduler pool) — the only knobs (§11.3a): every pool
/// is at least [#MIN_POOL_SIZE], whether derived from the budget or from an
/// explicit override. `NO_DB` routes never reach
/// [#forGroup] — they touch no pool.
public record Pools(GatedDataSource api, GatedDataSource bff, GatedDataSource dispatch, GatedDataSource background,
                    GatedDataSource scheduler) implements AutoCloseable {

    /// `B`'s default, unchanged from the single-pool era ([Database#DEFAULT_POOL_SIZE]).
    public static final int DEFAULT_BUDGET = Database.DEFAULT_POOL_SIZE;

    /// The background pool's fixed size, outside `B` (§11.3a item 2).
    public static final int BACKGROUND_POOL_SIZE = 4;

    /// Every pool's floor, whether derived from `B`'s share or from an
    /// explicit `FC_DB_POOL_SIZE_<GROUP>` override (§11.3a item 2, "Every
    /// group's pool is at least 2").
    public static final int MIN_POOL_SIZE = 2;

    /// The scheduler's pool is permits on top of its dispatcher lanes: one for
    /// the poller's claim and one for its hold-back query (they run one after
    /// the other, but a lane's own cache refresh must never wait behind them).
    public static final int SCHEDULER_EXTRA_PERMITS = 2;

    /// Server settings of the scheduler's pool, and of no other (dispatch-queue spec step 3b, measured):
    /// `force_custom_plan` because a generic plan cached while the queue was empty is a seq scan and is
    /// reused after a burst (850 ms per claim at 200,000 rows) — pgjdbc switches to a server-prepared
    /// statement after `prepareThreshold` executions, which is where a cached plan comes from;
    /// `enable_sort = off` because without statistics the planner prefers a seq scan plus sort of the
    /// whole queue to the ordered index walk the claim is written for.
    public static final Map<String, String> SCHEDULER_SERVER_SETTINGS = Map.of(
            "plan_cache_mode", "force_custom_plan", "enable_sort", "off");

    /// `scheduler` is `null` when the dispatch scheduler is not enabled: the
    /// pool is then never opened.
    public Pools {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(bff, "bff");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(background, "background");
    }

    /// The four request/background pools, no scheduler pool.
    public Pools(GatedDataSource api, GatedDataSource bff, GatedDataSource dispatch, GatedDataSource background) {
        this(api, bff, dispatch, background, null);
    }

    /// The smallest Hikari size whose gate leaves at least
    /// `dispatchers + `[#SCHEDULER_EXTRA_PERMITS] ordinary permits (the gate sets
    /// `max(1, size / 16)` aside for probes, so the pool is a little larger than
    /// the permits it must serve).
    public static int schedulerPoolSizeFor(int dispatchers) {
        int needed = Math.max(1, dispatchers) + SCHEDULER_EXTRA_PERMITS;
        int size = Math.max(MIN_POOL_SIZE, needed);
        while (size - GatedDataSource.reservedFor(size) < needed) {
            size++;
        }
        return size;
    }

    /// Opens all four pools against `url`, sized from `reader` per §11.3a:
    /// `B` = `FC_DB_POOL_SIZE` (default [#DEFAULT_BUDGET]); `api` = `B/2`,
    /// `bff` = `B/4`, `dispatch` = `B/4` (each overridable with
    /// `FC_DB_POOL_SIZE_API` / `_BFF` / `_DISPATCH`); `background` =
    /// [#BACKGROUND_POOL_SIZE] (overridable with `FC_DB_POOL_SIZE_BACKGROUND`),
    /// outside `B`. Every resulting size is at least [#MIN_POOL_SIZE].
    public static Pools open(String url, EnvReader reader) {
        return open(url, reader, 0);
    }

    /// As [#open(String, EnvReader)], and — when `schedulerDispatchers > 0`,
    /// i.e. the dispatch scheduler is enabled — a fifth pool for it, sized
    /// [#schedulerPoolSizeFor] unless `FC_DB_POOL_SIZE_SCHEDULER` overrides it
    /// (floor [#MIN_POOL_SIZE], like every pool; an override that leaves fewer
    /// ordinary permits than `schedulerDispatchers + 2` only makes the lanes
    /// wait for a connection at the gate, which is logged).
    public static Pools open(String url, EnvReader reader, int schedulerDispatchers) {
        int budget = Math.max(1, reader.integer("FC_DB_POOL_SIZE", DEFAULT_BUDGET));
        int apiSize = sizeFor(reader, "API", budget / 2);
        int bffSize = sizeFor(reader, "BFF", budget / 4);
        int dispatchSize = sizeFor(reader, "DISPATCH", budget / 4);
        int backgroundSize = sizeFor(reader, "BACKGROUND", BACKGROUND_POOL_SIZE);
        GatedDataSource scheduler = null;
        if (schedulerDispatchers > 0) {
            int schedulerSize = sizeFor(reader, "SCHEDULER", schedulerPoolSizeFor(schedulerDispatchers));
            scheduler = Database.newPool(url, schedulerSize, SCHEDULER_SERVER_SETTINGS);
            if (scheduler.ordinaryPermits() < schedulerDispatchers + SCHEDULER_EXTRA_PERMITS) {
                java.util.logging.Logger.getLogger(Pools.class.getName()).warning(
                        "FC_DB_POOL_SIZE_SCHEDULER=" + schedulerSize + " leaves " + scheduler.ordinaryPermits()
                                + " connections for " + schedulerDispatchers + " dispatcher lanes and the poller; "
                                + "lanes will wait for a connection");
            }
        }
        return new Pools(
                Database.newPool(url, apiSize),
                Database.newPool(url, bffSize),
                Database.newPool(url, dispatchSize),
                Database.newPool(url, backgroundSize),
                scheduler);
    }

    /// `derivedShare`, unless `FC_DB_POOL_SIZE_<group>` overrides it; the
    /// result is never below [#MIN_POOL_SIZE].
    private static int sizeFor(EnvReader reader, String group, int derivedShare) {
        int resolved = reader.integer("FC_DB_POOL_SIZE_" + group, derivedShare);
        return Math.max(MIN_POOL_SIZE, resolved);
    }

    /// Test convenience: all four physical pools gate the SAME underlying
    /// [DataSource] — a shared fixture such as `TestPg.dataSource()` — each
    /// through its own small, independent gate (admission isolation is not
    /// what a test is exercising). **Never call [#close()] on the result**
    /// when `delegate` is a long-lived shared fixture: `GatedDataSource#close()`
    /// closes `delegate` when it is itself [AutoCloseable], and a shared
    /// fixture must outlive any one test.
    public static Pools ofSingle(javax.sql.DataSource delegate) {
        return new Pools(
                GatedDataSource.over(delegate, 8),
                GatedDataSource.over(delegate, 4),
                GatedDataSource.over(delegate, 4),
                GatedDataSource.over(delegate, 4));
    }

    /// The physical pool a route's [Group] is served from (§11.3a): `API_READ`,
    /// `API_WRITE`, `LOGIN` and `OIDC` all share [#api]; `BFF` and `DISPATCH`
    /// have their own. `NO_DB` never touches a pool — passing it is a bug.
    public GatedDataSource forGroup(Group group) {
        return switch (Objects.requireNonNull(group, "group")) {
            case API_READ, API_WRITE, LOGIN, OIDC -> api;
            case BFF -> bff;
            case DISPATCH -> dispatch;
            case NO_DB -> throw new IllegalArgumentException(
                    "NO_DB routes never check out a connection; there is no pool for them");
        };
    }

    /// **The pool is chosen by the request, not by the handler class**
    /// (`docs/spec/admission.md` §11.7 part B). A [DataSource] whose
    /// `getConnection()` resolves [#forGroup] from the current request's
    /// [Group] — [Admission#CURRENT], bound by the Vert.x adapter around the
    /// whole before → handler → after chain — so every request-path
    /// repository can be built ONCE over this source and still land on the
    /// right physical pool for whichever mount (`/api/**` or `/bff/**`)
    /// called it. No scope bound is a programming error, not a fallback:
    /// background code holds its own explicit pool ([#background()] or one
    /// of the other three) and never reaches this source, so a checkout with
    /// no [Admission] bound throws rather than silently picking one. `NO_DB`
    /// never reaches here either — passing it throws, exactly as
    /// [#forGroup] already does, since [Admission] always carries a real
    /// [Group].
    public DataSource routed() {
        return new RoutedDataSource();
    }

    private final class RoutedDataSource implements DataSource {
        private DataSource resolve() {
            Admission admission = Admission.currentOrNull();
            if (admission == null) {
                throw new IllegalStateException(
                        "Pools.routed() called with no admission scope bound; background code must "
                                + "hold its own explicit pool (Pools#api/#bff/#dispatch/#background)");
            }
            return forGroup(admission.group());
        }

        @Override
        public Connection getConnection() throws SQLException {
            return resolve().getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return resolve().getConnection(username, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return api.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            api.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            api.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return api.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return api.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            return api.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || api.isWrapperFor(iface);
        }
    }

    /// Registers every open gate's collector, each its own [MultiCollector]
    /// labelled `pool` with its own name (`api` / `bff` / `dispatch` /
    /// `background`, and `scheduler` when it was opened) — a scrape of
    /// `registry` carries every `pool` value under the `fc_db_gate_waiting` /
    /// `fc_db_gate_held` names (§11.7).
    public void registerCollectors(PrometheusRegistry registry) {
        var named = new java.util.LinkedHashMap<String, GatedDataSource>();
        named.put("api", api);
        named.put("bff", bff);
        named.put("dispatch", dispatch);
        named.put("background", background);
        if (scheduler != null) named.put("scheduler", scheduler);
        for (var entry : named.entrySet()) {
            registry.register(entry.getValue().collector(entry.getKey()));
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (GatedDataSource pool : new GatedDataSource[] {api, bff, dispatch, background, scheduler}) {
            if (pool == null) continue;
            try {
                pool.close();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
