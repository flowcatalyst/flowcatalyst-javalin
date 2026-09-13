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
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/// The four physical connection pools a `Platform`/`Worker` instance opens
/// (`docs/spec/admission.md` §11.7, "Pools"), all against the SAME database —
/// isolation is the point (§11.3 ruling): a slow lane in one group can no
/// longer starve connections another group needs, and each can later point
/// at a different replica.
///
/// - `api`        (`½ B`)  — [Group#API_READ], [Group#API_WRITE], [Group#LOGIN], [Group#OIDC]
/// - `bff`        (`¼ B`)  — [Group#BFF]
/// - `dispatch`   (`¼ B`)  — [Group#DISPATCH]: every route the message router calls
/// - `background` (`4`, outside `B`) — outbox, stream, scheduler, scheduled-job
///   scheduler, purger, mail sender, dispatch-job reaper, the router's own
///   housekeeping — never the request path
///
/// `B` (the budget) is [#DEFAULT_BUDGET] (32) unless `FC_DB_POOL_SIZE`
/// overrides it. Each of the four sizes may be overridden independently with
/// `FC_DB_POOL_SIZE_API` / `_BFF` / `_DISPATCH` / `_BACKGROUND` — the only
/// knobs (§11.3a): every pool is at least [#MIN_POOL_SIZE], whether derived
/// from the budget or from an explicit override. `NO_DB` routes never reach
/// [#forGroup] — they touch no pool.
public record Pools(GatedDataSource api, GatedDataSource bff, GatedDataSource dispatch, GatedDataSource background)
        implements AutoCloseable {

    /// `B`'s default, unchanged from the single-pool era ([Database#DEFAULT_POOL_SIZE]).
    public static final int DEFAULT_BUDGET = Database.DEFAULT_POOL_SIZE;

    /// The background pool's fixed size, outside `B` (§11.3a item 2).
    public static final int BACKGROUND_POOL_SIZE = 4;

    /// Every pool's floor, whether derived from `B`'s share or from an
    /// explicit `FC_DB_POOL_SIZE_<GROUP>` override (§11.3a item 2, "Every
    /// group's pool is at least 2").
    public static final int MIN_POOL_SIZE = 2;

    public Pools {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(bff, "bff");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(background, "background");
    }

    /// Opens all four pools against `url`, sized from `reader` per §11.3a:
    /// `B` = `FC_DB_POOL_SIZE` (default [#DEFAULT_BUDGET]); `api` = `B/2`,
    /// `bff` = `B/4`, `dispatch` = `B/4` (each overridable with
    /// `FC_DB_POOL_SIZE_API` / `_BFF` / `_DISPATCH`); `background` =
    /// [#BACKGROUND_POOL_SIZE] (overridable with `FC_DB_POOL_SIZE_BACKGROUND`),
    /// outside `B`. Every resulting size is at least [#MIN_POOL_SIZE].
    public static Pools open(String url, EnvReader reader) {
        int budget = Math.max(1, reader.integer("FC_DB_POOL_SIZE", DEFAULT_BUDGET));
        int apiSize = sizeFor(reader, "API", budget / 2);
        int bffSize = sizeFor(reader, "BFF", budget / 4);
        int dispatchSize = sizeFor(reader, "DISPATCH", budget / 4);
        int backgroundSize = sizeFor(reader, "BACKGROUND", BACKGROUND_POOL_SIZE);
        return new Pools(
                Database.newPool(url, apiSize),
                Database.newPool(url, bffSize),
                Database.newPool(url, dispatchSize),
                Database.newPool(url, backgroundSize));
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

    /// Registers all four gates' collectors, each its own [MultiCollector]
    /// labelled `pool` with its own name (`api` / `bff` / `dispatch` /
    /// `background`) — a scrape of `registry` carries all four `pool` values
    /// under the `fc_db_gate_waiting` / `fc_db_gate_held` names (§11.7).
    public void registerCollectors(PrometheusRegistry registry) {
        for (var entry : java.util.Map.of("api", api, "bff", bff, "dispatch", dispatch, "background", background)
                .entrySet()) {
            registry.register(entry.getValue().collector(entry.getKey()));
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (GatedDataSource pool : new GatedDataSource[] {api, bff, dispatch, background}) {
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
