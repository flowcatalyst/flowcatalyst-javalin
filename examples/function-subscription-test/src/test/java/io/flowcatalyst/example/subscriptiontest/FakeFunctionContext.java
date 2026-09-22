package io.flowcatalyst.example.subscriptiontest;

import io.flowcatalyst.fnhost.context.MapConfig;
import io.flowcatalyst.fnhost.context.MapSecrets;
import io.flowcatalyst.function.Config;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.FunctionAddress;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.HttpCaller;
import io.flowcatalyst.function.Secrets;

import javax.sql.DataSource;
import java.lang.System.Logger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

/// A minimal, real [FunctionContext] test double — not [io.flowcatalyst.fnhost.context.HostFunctionContext],
/// which needs a full [io.flowcatalyst.fnhost.reconcile.DesiredDocument.Entry] +
/// live [io.flowcatalyst.fnhost.reconcile.ControlPlane] to build
/// ([io.flowcatalyst.fnhost.context.ContextFactory#build]) — more machinery
/// than this module's one integration test needs to prove `HelloFunction`
/// itself works through the REAL loader ([io.flowcatalyst.fnhost.load.JvmFunctionLoader])
/// on the REAL shrunk jar. Reuses the host's own [MapConfig]/[MapSecrets] (both
/// public) so config/secret lookup semantics — `require` throwing
/// `IllegalStateException` naming the key, `Secrets#toString` printing keys
/// only — are the production ones, not a second copy.
final class FakeFunctionContext implements FunctionContext {

    private final FunctionAddress address;
    private final int version;
    private final Config config;
    private final Secrets secrets;
    private final Events events;
    private final CapturingLogger logger;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);

    FakeFunctionContext(FunctionAddress address, int version, Map<String, String> config,
            Map<String, String> secrets, Events events) {
        this.address = Objects.requireNonNull(address, "address");
        this.version = version;
        this.config = new MapConfig(config);
        this.secrets = new MapSecrets(secrets);
        this.events = Objects.requireNonNull(events, "events");
        this.logger = new CapturingLogger("fn." + address.render());
    }

    CapturingLogger capturingLogger() {
        return logger;
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

    private DataSource eventsDb;

    FakeFunctionContext withDataSource(DataSource ds) {
        this.eventsDb = ds;
        return this;
    }

    @Override
    public DataSource dataSource(String name) {
        if (!"events".equals(name) || eventsDb == null) {
            throw new IllegalArgumentException("not a declared db[] entry: " + name);
        }
        return eventsDb;
    }

    @Override
    public HttpCaller http() {
        return call -> {
            throw new UnsupportedOperationException("HelloFunction never calls ctx.http()");
        };
    }

    @Override
    public Events events() {
        return events;
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
}
