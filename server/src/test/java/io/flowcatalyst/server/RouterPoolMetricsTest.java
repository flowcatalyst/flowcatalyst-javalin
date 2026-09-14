package io.flowcatalyst.server;

import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.config.RouterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Staging, 2026-09-14: the dashboard and Prometheus showed zero deliveries
/// for every pool while the queue counters moved. `Router` copied the pool
/// factory's collector map once at construction, so a pool created later —
/// which is every pool: the first config fetch happens after the router is
/// built — never had a collector the monitoring surface could see. The map
/// handed out must be the factory's own, live.
class RouterPoolMetricsTest {

    private Router router;

    @AfterEach
    void close() {
        if (router != null) router.close();
    }

    @Test
    @DisplayName("Router.poolMetrics() sees the collector of a pool created after the router was built")
    void poolMetricsIsALiveViewOfTheFactorysMap() {
        Env env = Env.load(Map.of(
                "FC_ROUTER_ENABLED", "true",
                "FC_PLATFORM_ENABLED", "false",
                "FLOWCATALYST_DEV_MODE", "true"));
        router = Router.build(env, null, Clock.systemUTC());
        Map<String, ?> view = router.poolMetrics();
        assertThat(view).as("sanity: nothing has been configured yet").doesNotContainKey("LATE-POOL");

        // The way production creates pools: a config applied after build.
        router.manager().reconfigure(new RouterConfig(List.of(new PoolSpec("LATE-POOL", 2, 0)), List.of()),
                queue -> { throw new AssertionError("no queues in this config"); });

        assertThat(router.poolMetrics()).as("mutant: the map was copied at construction").containsKey("LATE-POOL");
        assertThat(view).as("the previously handed-out map is the same live view").containsKey("LATE-POOL");
    }
}
