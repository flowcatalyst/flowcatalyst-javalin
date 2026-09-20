package io.flowcatalyst.fnhost.context;

import io.flowcatalyst.function.Config;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.FunctionAddress;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.HttpCaller;
import io.flowcatalyst.function.Secrets;

import javax.sql.DataSource;
import java.lang.System.Logger;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// D4b's real [FunctionContext] (spec `function-context.md` §2): built once
/// per loaded version by [ContextFactory#build] and passed to `init` and
/// every `handle` on that [io.flowcatalyst.fnhost.load.LoadedFunction] — the
/// SAME instance for the version's whole lifetime, so an in-flight call
/// never observes a half-updated context (a settings change loads a
/// brand-new [io.flowcatalyst.fnhost.load.LoadedFunction] with a brand-new
/// context instead of mutating this one in place).
///
/// [#events()] still throws — `events()` lands in slice D4c (spec §3).
///
/// [AutoCloseable]: releases every database pool this context's
/// [#dataSource] entries acquired. Called exactly once, by
/// [io.flowcatalyst.fnhost.load.LoadedFunction#close] — never called
/// directly by a function, which never sees this type, only the
/// [FunctionContext] interface.
public final class HostFunctionContext implements FunctionContext, AutoCloseable {

    private final FunctionAddress address;
    private final int version;
    private final Config config;
    private final Secrets secrets;
    private final Map<String, DataSource> dataSources;
    private final HttpCaller http;
    private final Clock clock;
    private final Logger logger;
    private final DbPools dbPools;
    private final List<String> acquiredPoolIdentities;
    private final Object dbPoolToken;

    HostFunctionContext(FunctionAddress address, int version, Config config, Secrets secrets,
                         Map<String, DataSource> dataSources, HttpCaller http, Clock clock,
                         DbPools dbPools, List<String> acquiredPoolIdentities, Object dbPoolToken) {
        this.address = Objects.requireNonNull(address, "address");
        this.version = version;
        this.config = Objects.requireNonNull(config, "config");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.dataSources = Map.copyOf(dataSources);
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dbPools = Objects.requireNonNull(dbPools, "dbPools");
        this.acquiredPoolIdentities = List.copyOf(acquiredPoolIdentities);
        this.dbPoolToken = Objects.requireNonNull(dbPoolToken, "dbPoolToken");
        this.logger = new HostLogger(address.render());
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Config config() {
        return config;
    }

    @Override
    public Secrets secrets() {
        return secrets;
    }

    /// @throws IllegalArgumentException `name` is not one of this version's
    ///                                   declared `manifest.db[].name` entries
    @Override
    public DataSource dataSource(String name) {
        DataSource ds = dataSources.get(name);
        if (ds == null) {
            throw new IllegalArgumentException("no database named '" + name + "' declared by this function's manifest");
        }
        return ds;
    }

    @Override
    public HttpCaller http() {
        return http;
    }

    @Override
    public Events events() {
        throw new UnsupportedOperationException("events: slice D4c");
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public FunctionAddress address() {
        return address;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public void close() {
        for (String identity : acquiredPoolIdentities) {
            dbPools.release(identity, dbPoolToken);
        }
    }
}
