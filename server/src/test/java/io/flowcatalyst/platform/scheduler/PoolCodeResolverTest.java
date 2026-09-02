package io.flowcatalyst.platform.scheduler;

import org.junit.jupiter.api.Test;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [PoolCodeResolver] against a real embedded Postgres: the four `poolCode`
/// composition rows (dispatch-seam spec §2). Each test builds its own
/// resolver instance — the cache's TTL (60s) would otherwise outlive a
/// second test method's freshly-seeded rows within the same run.
class PoolCodeResolverTest {

    @Test
    void aPoolOwnedByAClientIsNamespacedByItsClientIdentifier() {
        String clientId = SchedulerFixture.client("acme" + RUN);
        // Lowercase, `dp...`-shaped — `msg_dispatch_pools` is a table
        // DispatchPoolApiTest's unscoped, sort-order-asserting list endpoint
        // also reads; an uppercase-leading code sorts differently than a
        // lowercase one under Postgres's default collation than under Java's
        // `String.compareTo`, which broke that other test's `isSorted()`
        // check the first time this file used "FAST"/"PLATFAST".
        String poolId = SchedulerFixture.pool("dpsched-fast-" + RUN, clientId, "acme" + RUN);

        String code = new PoolCodeResolver(DATA_SOURCE).resolve(poolId, null);

        assertThat(code).isEqualTo("acme" + RUN + "-dpsched-fast-" + RUN);
    }

    @Test
    void aPlatformLevelPoolPublishesUnprefixed() {
        String poolId = SchedulerFixture.pool("dpsched-platfast-" + RUN, null, null);

        // Even with a client id on the job, an unprefixed platform pool wins — the
        // pool composition never falls through to the client fallback once a pool resolves.
        String code = new PoolCodeResolver(DATA_SOURCE).resolve(poolId, "some-client-id");

        assertThat(code).isEqualTo("dpsched-platfast-" + RUN);
    }

    @Test
    void noPoolFallsBackToTheClientsDefaultPool() {
        String clientId = SchedulerFixture.client("bravo" + RUN);

        String code = new PoolCodeResolver(DATA_SOURCE).resolve(null, clientId);

        assertThat(code).isEqualTo("bravo" + RUN + "-DEFAULT-POOL");
        assertThat(PoolCodeResolver.isDefaultPoolCode(code)).isTrue();
    }

    @Test
    void neitherPoolNorClientFallsBackToTheGlobalDefault() {
        PoolCodeResolver resolver = new PoolCodeResolver(DATA_SOURCE);

        assertThat(resolver.resolve(null, null)).isEqualTo("DEFAULT-POOL");
        assertThat(resolver.resolve("unknown-pool-id", "unknown-client-id")).isEqualTo("DEFAULT-POOL");
        assertThat(PoolCodeResolver.isDefaultPoolCode("DEFAULT-POOL")).isTrue();
        assertThat(PoolCodeResolver.isDefaultPoolCode("FAST")).isFalse();
    }
}
