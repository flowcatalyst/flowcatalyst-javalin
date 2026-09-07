package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins [Main#needsDb] / [Main#needsMigrateAndSeed]: which deployments get a
/// database pool at all, and which of those additionally run Flyway +
/// the seeder. The gap this closes — `FC_PLATFORM_ENABLED=false
/// FC_ROUTER_ENABLED=true FC_DEFAULT_BROKER=postgres` never built a pool, so
/// the router's own Postgres broker (`docs/spec/router.md` §8.4) started
/// with no consumer (`QueueFactory.createPostgres` logged "needs postgres
/// but no database is configured" and returned empty).
class MainTest {

    private static Env env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Env.load(m);
    }

    @Test
    void platformAloneNeedsDbAndMigrateAndSeed() {
        var e = env(); // FC_PLATFORM_ENABLED defaults true
        assertThat(Main.needsDb(e)).isTrue();
        assertThat(Main.needsMigrateAndSeed(e)).isTrue();
    }

    @Test
    void workerSubsystemsAloneNeedDbAndMigrateAndSeed() {
        for (String flag : new String[] {
                "FC_SCHEDULER_ENABLED", "FC_STREAM_PROCESSOR_ENABLED",
                "FC_SCHEDULED_JOB_ENABLED", "FC_OUTBOX_ENABLED"}) {
            var e = env("FC_PLATFORM_ENABLED", "false", flag, "true");
            assertThat(Main.needsDb(e)).as(flag).isTrue();
            assertThat(Main.needsMigrateAndSeed(e)).as(flag).isTrue();
        }
    }

    /// The gap: router-only + the built-in Postgres broker must get a pool
    /// — this is the assertion that fails against the pre-fix `needsDb`,
    /// which ignored the router entirely.
    @Test
    void routerOnlyWithPostgresDefaultBrokerNeedsDbButNotMigrateOrSeed() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true",
                "FC_DEFAULT_BROKER", "postgres");
        assertThat(Main.needsDb(e)).isTrue();
        // load-bearing: queue_messages is created by PostgresQueue.initSchema,
        // not Flyway — a router-only instance must never run platform
        // migrations or the seeder against a database that may host nothing
        // else.
        assertThat(Main.needsMigrateAndSeed(e)).isFalse();
    }

    @Test
    void routerOnlyWithoutPostgresDefaultBrokerNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true");
        assertThat(Main.needsDb(e)).isFalse();
        assertThat(Main.needsMigrateAndSeed(e)).isFalse();
    }

    @Test
    void routerOnlyWithNonPostgresDefaultBrokerNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true",
                "FC_DEFAULT_BROKER", "sqs");
        assertThat(Main.needsDb(e)).isFalse();
    }

    /// A config URL wins over the default broker (`Router#usesDefaultPostgresBroker`
    /// mirrors `Router#configSource`'s own precedence) — a router pointed at a
    /// config service never grows a Postgres broker just because
    /// FC_DEFAULT_BROKER is also set to postgres.
    @Test
    void routerOnlyWithConfigUrlAndPostgresBrokerNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true",
                "FC_DEFAULT_BROKER", "postgres", "FLOWCATALYST_CONFIG_URL", "http://config.local/router");
        assertThat(Main.needsDb(e)).isFalse();
    }

    @Test
    void postgresDefaultBrokerWithoutRouterEnabledNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_DEFAULT_BROKER", "postgres");
        assertThat(Main.needsDb(e)).isFalse();
    }

    @Test
    void mcpAloneNeedsNoDb() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_MCP_ENABLED", "true");
        assertThat(Main.needsDb(e)).isFalse();
        assertThat(Main.needsMigrateAndSeed(e)).isFalse();
    }

    @Test
    void platformPlusRouterPostgresBrokerStillMigratesAndSeeds() {
        var e = env("FC_ROUTER_ENABLED", "true", "FC_DEFAULT_BROKER", "postgres");
        assertThat(Main.needsDb(e)).isTrue();
        assertThat(Main.needsMigrateAndSeed(e)).isTrue();
    }
}
