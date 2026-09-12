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

    /// **Superseded by R4** (`docs/go-mirror/2026-09-12-dispatch-rulings.md`):
    /// this used to be the gap-closing assertion — router-only + the
    /// built-in Postgres broker needed a pool through `Main`. R4 removed
    /// `Router#usesDefaultPostgresBroker` along with the fixed single-queue
    /// branch it audited: a router-only instance no longer opens a pool
    /// through `Main` just because `FC_DEFAULT_BROKER=postgres` is set — a
    /// config-URL-supplied Postgres queue opens its own pool from its own URI
    /// instead (`QueueFactory#createPostgres`). This test now pins the
    /// opposite of what it originally asserted; kept under its original name
    /// with the ruling noted, not deleted, per CONVENTIONS' preference for
    /// recording a superseded rule rather than silently erasing it.
    @Test
    void routerOnlyWithPostgresDefaultBrokerNeedsDbButNotMigrateOrSeed() {
        var e = env("FC_PLATFORM_ENABLED", "false", "FC_ROUTER_ENABLED", "true",
                "FC_DEFAULT_BROKER", "postgres");
        assertThat(Main.needsDb(e)).as("R4: the router itself never causes Main to open a pool any more").isFalse();
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

    /// **Superseded by R4**: before R4 removed `Router#usesDefaultPostgresBroker`,
    /// this pinned that a config URL wins over the default broker for
    /// `needsDb`'s own precedence. Now the router never factors into
    /// `needsDb` at all — this still holds, but for a simpler reason.
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
