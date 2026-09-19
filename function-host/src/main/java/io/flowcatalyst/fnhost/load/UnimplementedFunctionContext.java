package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Config;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.FunctionAddress;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.HttpCaller;
import io.flowcatalyst.function.Secrets;

import javax.sql.DataSource;
import java.lang.System.Logger;
import java.time.Clock;
import java.util.Objects;

/// D1's [FunctionContext]. `logger`, `clock`, `address` and `version` are
/// real; every other service throws `UnsupportedOperationException` naming
/// the slice its implementation lands in (`docs/spec/function-host-core.md`
/// §2: "In D1 the host supplies a `FunctionContext` whose unimplemented
/// services throw `UnsupportedOperationException` naming the later
/// slice") — `config`/`secrets`/`dataSource`/`http`/`events` are all D4.
public final class UnimplementedFunctionContext implements FunctionContext {

    private final FunctionAddress address;
    private final int version;
    private final Clock clock;
    private final Logger logger;

    public UnimplementedFunctionContext(FunctionAddress address, int version) {
        this(address, version, Clock.systemUTC());
    }

    /// @param clock injectable so a test can fix time without touching the wall clock
    public UnimplementedFunctionContext(FunctionAddress address, int version, Clock clock) {
        this.address = Objects.requireNonNull(address, "address");
        this.version = version;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = System.getLogger("fn." + address.render());
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Config config() {
        throw unimplemented("config");
    }

    @Override
    public Secrets secrets() {
        throw unimplemented("secrets");
    }

    @Override
    public DataSource dataSource(String name) {
        throw unimplemented("dataSource");
    }

    @Override
    public HttpCaller http() {
        throw unimplemented("http");
    }

    @Override
    public Events events() {
        throw unimplemented("events");
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

    private static UnsupportedOperationException unimplemented(String service) {
        return new UnsupportedOperationException(
                service + "() lands in slice D4 (docs/spec/function-host-core.md §2)");
    }
}
