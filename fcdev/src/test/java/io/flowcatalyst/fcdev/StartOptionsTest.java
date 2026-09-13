package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The Go `addStartFlags` seeding: env → flag default → explicit flag wins.
class StartOptionsTest {

    private static final DevPaths PATHS = DevPaths.resolve(Map.of(), "Linux", "/home/dev");

    private static StartOptions parse(Map<String, String> env, String... args) {
        var sub = new StartCommand.Sub(DevEnv.of(env));
        new CommandLine(sub, new EnvFactory(DevEnv.of(env))).parseArgs(args);
        return sub.opts;
    }

    @Test
    void defaultsMatchGoWhenNothingIsSet() {
        var o = new StartOptions(DevEnv.of(Map.of()), PATHS);
        assertThat(o.apiPort()).isEqualTo(8080);
        assertThat(o.metricsPort()).isEqualTo(9090);
        assertThat(o.embeddedDb()).isTrue();
        assertThat(o.embeddedDbPort()).isEqualTo(15432);
        assertThat(o.embeddedDbPath()).isEqualTo(Path.of("/home/dev/.config/flowcatalyst/embedded-pg").toString());
        assertThat(o.embeddedDbReset()).isFalse();
        assertThat(o.databaseUrl()).isEmpty();
        assertThat(o.scheduler()).isTrue();
        assertThat(o.scheduledJob()).isTrue();
        assertThat(o.stream()).isTrue();
        assertThat(o.outbox()).isFalse();
        assertThat(o.router()).isTrue();
        assertThat(o.mcp()).isFalse();
        assertThat(o.pidFile()).isEqualTo(Path.of("/home/dev/.config/flowcatalyst/fcdev.pid").toString());
    }

    @Test
    void environmentSeedsTheDefaults() {
        var env = Map.ofEntries(
                Map.entry("FC_API_PORT", "9000"),
                Map.entry("FC_METRICS_PORT", "9100"),
                Map.entry("FC_EMBEDDED_DB", "no"),
                Map.entry("FC_EMBEDDED_DB_PORT", "25432"),
                Map.entry("FC_EMBEDDED_DB_PATH", "/var/fc/pg"),
                Map.entry("FC_DATABASE_URL", "postgresql://u@h/db"),
                Map.entry("FC_SCHEDULER_ENABLED", "0"),
                Map.entry("FC_SCHEDULED_JOB_ENABLED", "off"),
                Map.entry("FC_STREAM_PROCESSOR_ENABLED", "FALSE"),
                Map.entry("FC_OUTBOX_ENABLED", "yes"),
                Map.entry("FC_ROUTER_ENABLED", " false "),
                Map.entry("FC_MCP_ENABLED", "on"),
                Map.entry("FC_DEV_PID_FILE", "/run/fcdev.pid"));
        var o = new StartOptions(DevEnv.of(env), PATHS);
        assertThat(o.apiPort()).isEqualTo(9000);
        assertThat(o.metricsPort()).isEqualTo(9100);
        assertThat(o.embeddedDb()).isFalse();
        assertThat(o.embeddedDbPort()).isEqualTo(25432);
        assertThat(o.embeddedDbPath()).isEqualTo("/var/fc/pg");
        assertThat(o.databaseUrl()).isEqualTo("postgresql://u@h/db");
        assertThat(o.scheduler()).isFalse();
        assertThat(o.scheduledJob()).isFalse();
        assertThat(o.stream()).isFalse();
        assertThat(o.outbox()).isTrue();
        assertThat(o.router()).isFalse();
        assertThat(o.mcp()).isTrue();
        assertThat(o.pidFile()).isEqualTo("/run/fcdev.pid");
    }

    @Test
    void unparseableEnvironmentFallsBackToTheDefault() {
        var o = new StartOptions(DevEnv.of(Map.of("FC_API_PORT", "eighty", "FC_ROUTER_ENABLED", "maybe")), PATHS);
        assertThat(o.apiPort()).isEqualTo(8080);
        assertThat(o.router()).isTrue();
    }

    @Test
    void explicitFlagsWinOverEnvironment() {
        var o = parse(Map.of("FC_API_PORT", "9000", "FC_ROUTER_ENABLED", "true", "FC_OUTBOX_ENABLED", "false"),
                "--api-port", "7000", "--router=false", "--outbox", "--embedded-db-reset",
                "--embedded-db-path", "/tmp/x", "--database-url", "postgresql://a@b/c", "--pid-file", "/tmp/p.pid",
                "--embedded-db-port", "0", "--metrics-port", "0", "--mcp=true", "--scheduler=false");
        assertThat(o.apiPort()).isEqualTo(7000);
        assertThat(o.metricsPort()).isZero();
        assertThat(o.router()).isFalse();
        assertThat(o.outbox()).isTrue();
        assertThat(o.embeddedDbReset()).isTrue();
        assertThat(o.embeddedDbPath()).isEqualTo("/tmp/x");
        assertThat(o.databaseUrl()).isEqualTo("postgresql://a@b/c");
        assertThat(o.pidFile()).isEqualTo("/tmp/p.pid");
        assertThat(o.embeddedDbPort()).isZero();
        assertThat(o.mcp()).isTrue();
        assertThat(o.scheduler()).isFalse();
        // untouched flags keep their env-seeded / built-in defaults
        assertThat(o.stream()).isTrue();
        assertThat(o.scheduledJob()).isTrue();
    }

    @Test
    void rootCommandCarriesTheSameFlagSet() {
        var root = new FcDev(DevEnv.of(Map.of("FC_METRICS_PORT", "9999")));
        new CommandLine(root, new EnvFactory(DevEnv.of(Map.of()))).parseArgs("--api-port", "8181", "--stream=false");
        assertThat(root.opts.apiPort()).isEqualTo(8181);
        assertThat(root.opts.metricsPort()).isEqualTo(9999);
        assertThat(root.opts.stream()).isFalse();
    }

    @Test
    void devEnvProjectsTheFlagsOntoTheServerEnv() {
        var o = parse(Map.of("FC_DEFAULT_BROKER", "", "FC_JWT_ISSUER", "http://dev.local"),
                "--api-port", "7000", "--metrics-port", "7001", "--router=false", "--outbox", "--mcp");
        var dev = DevEnv.of(Map.of("FC_DEFAULT_BROKER", "", "FC_JWT_ISSUER", "http://dev.local")).mutable();
        var env = StartCommand.devEnv(dev, o, "postgresql://postgres:postgres@localhost:15432/flowcatalyst?sslmode=disable");
        assertThat(env.apiPort()).isEqualTo(7000);
        assertThat(env.metricsPort()).isEqualTo(7001);
        assertThat(env.databaseUrl()).isEqualTo("postgresql://postgres:postgres@localhost:15432/flowcatalyst?sslmode=disable");
        assertThat(env.platformEnabled()).isTrue();
        assertThat(env.authAllowTestHeaders()).isTrue();
        assertThat(env.routerEnabled()).isFalse();
        assertThat(env.outboxEnabled()).isTrue();
        assertThat(env.mcpEnabled()).isTrue();
        assertThat(env.schedulerEnabled()).isTrue();
        assertThat(env.scheduledJobEnabled()).isTrue();
        assertThat(env.streamEnabled()).isTrue();
        assertThat(env.defaultBroker()).isEqualTo("postgres");
        assertThat(env.jwtIssuer()).isEqualTo("http://dev.local"); // explicit FC_* overrides survive
        // R3′ (`docs/spec/router-config-auth.md`): fcdev points its own
        // config URL at its own API listener, where the authenticated
        // document lives — never the internal one (R3, withdrawn).
        assertThat(env.routerConfigUrl()).isEqualTo("http://localhost:7000/api/dispatch/router-config");
    }

    @Test
    void explicitDefaultBrokerIsNotOverridden() {
        var o = new StartOptions(DevEnv.of(Map.of()), PATHS);
        var env = StartCommand.devEnv(DevEnv.of(Map.of("FC_DEFAULT_BROKER", "none")).mutable(), o, "postgresql://x@y/z");
        assertThat(env.defaultBroker()).isEqualTo("none");
    }

    /// Mutant this pins: hardcoding the config URL default so it always wins
    /// — an operator who already pointed `FLOWCATALYST_CONFIG_URL` elsewhere
    /// (Integral, a shared config service) must keep that value.
    @Test
    void explicitConfigUrlIsNotOverridden() {
        var o = new StartOptions(DevEnv.of(Map.of()), PATHS);
        var env = StartCommand.devEnv(
                DevEnv.of(Map.of("FLOWCATALYST_CONFIG_URL", "http://integral.example/router-config")).mutable(),
                o, "postgresql://x@y/z");
        assertThat(env.routerConfigUrl()).isEqualTo("http://integral.example/router-config");
    }

    /// The config URL is built from the API port, which is always known
    /// (R3′) — an ephemeral `--metrics-port 0` no longer has any bearing on
    /// it. Under R3 this case had to leave the setting unset because the
    /// document lived on the internal listener; that refusal is gone.
    @Test
    void metricsPortZeroStillSynthesisesTheConfigUrlOffTheApiPort() {
        var o = parse(Map.of(), "--metrics-port", "0");
        var env = StartCommand.devEnv(DevEnv.of(Map.of()).mutable(), o, "postgresql://x@y/z");
        assertThat(env.routerConfigUrl()).isEqualTo("http://localhost:8080/api/dispatch/router-config");
    }

    /// `--api-port 0` (ephemeral) IS the port the URL is built from, and it is
    /// not knowable when `devEnv` runs. Mutant this pins: synthesising
    /// `http://localhost:0/...` — a URL that can never work fails only once
    /// the router polls it, rather than obviously at a glance.
    @Test
    void apiPortZeroDoesNotSynthesiseABogusConfigUrl() {
        var o = parse(Map.of(), "--api-port", "0");
        var env = StartCommand.devEnv(DevEnv.of(Map.of()).mutable(), o, "postgresql://x@y/z");
        assertThat(env.routerConfigUrl()).isEmpty();
    }
}
