package io.flowcatalyst.fnhost.context;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;

import io.flowcatalyst.platform.shared.database.GatedDataSource;

/// The `DataSource` a function actually holds for one `manifest.db[]` entry
/// (spec `function-context.md` §2): every checkout is delegated to whichever
/// [GatedDataSource] [DbPools] currently has live for this DSN — indirected
/// through an [AtomicReference] so [DbPools] can raise the pool's size by
/// swapping in a new, larger-gated [GatedDataSource] over the SAME
/// underlying Hikari pool ("raised on demand") without this handed-out
/// object ever changing identity under the function.
///
/// Deliberately does **not** implement `AutoCloseable` — `javax.sql.DataSource`
/// itself declares no `close()`, so there is nothing for a function to call
/// even if it tried to cast its way to one; [#unwrap]/[#isWrapperFor] refuse
/// outright, so a function can never reach the real [GatedDataSource] or the
/// Hikari pool underneath to close or reconfigure it.
final class FunctionDataSource implements DataSource {

    private final AtomicReference<GatedDataSource> gate;

    FunctionDataSource(AtomicReference<GatedDataSource> gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return gate.get().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("per-call credentials are not supported through a function's DataSource");
    }

    @Override
    public PrintWriter getLogWriter() {
        throw refuse();
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        throw refuse();
    }

    @Override
    public void setLoginTimeout(int seconds) {
        throw refuse();
    }

    @Override
    public int getLoginTimeout() {
        throw refuse();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("not supported through a function's DataSource");
    }

    /// Always refuses (spec §2: "the function receives a `DataSource` whose
    /// `close`/`unwrap` do nothing/refuse — it cannot ... reach Hikari").
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("unwrap is not supported through a function's DataSource");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }

    private static UnsupportedOperationException refuse() {
        return new UnsupportedOperationException("not supported through a function's DataSource");
    }
}
