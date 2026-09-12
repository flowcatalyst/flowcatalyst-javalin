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

    /// Ruling R7 (`docs/go-mirror/2026-09-12-dispatch-rulings.md`): a
    /// platform-level pool now publishes `platform-{code}`, superseding the
    /// old unprefixed form — the router merges this document with several
    /// Integral tenant configs, first-code-wins, so an unprefixed platform
    /// pool could silently collide with an Integral tenant's pool of the
    /// same name.
    @Test
    void aPlatformLevelPoolPublishesWithThePlatformPrefix() {
        String poolId = SchedulerFixture.pool("dpsched-platfast-" + RUN, null, null);

        // Even with a client id on the job, the platform-prefixed pool wins — the
        // pool composition never falls through to the client fallback once a pool resolves.
        String code = new PoolCodeResolver(DATA_SOURCE).resolve(poolId, "some-client-id");

        assertThat(code).isEqualTo("platform-dpsched-platfast-" + RUN);
    }

    @Test
    void noPoolFallsBackToTheClientsDefaultPool() {
        String clientId = SchedulerFixture.client("bravo" + RUN);

        String code = new PoolCodeResolver(DATA_SOURCE).resolve(null, clientId);

        assertThat(code).isEqualTo("bravo" + RUN + "-DEFAULT-POOL");
        assertThat(PoolCodeResolver.isDefaultPoolCode(code)).isTrue();
    }

    /// Ruling R7: the fully-unresolvable case (no pool resolves, no client
    /// resolves either) now composes `platform-DEFAULT-POOL` — superseding
    /// the old bare `DEFAULT-POOL` — so it self-synthesises through
    /// `RouterManager`'s generic `-DEFAULT-POOL` suffix rule exactly like
    /// every other tenant's fallback, rather than depending on
    /// `RouterManager`'s separate always-injected bare pool of that name.
    @Test
    void neitherPoolNorClientFallsBackToThePlatformDefault() {
        PoolCodeResolver resolver = new PoolCodeResolver(DATA_SOURCE);

        assertThat(resolver.resolve(null, null)).isEqualTo("platform-DEFAULT-POOL");
        assertThat(resolver.resolve("unknown-pool-id", "unknown-client-id")).isEqualTo("platform-DEFAULT-POOL");
        assertThat(PoolCodeResolver.isDefaultPoolCode("platform-DEFAULT-POOL")).isTrue();
        assertThat(PoolCodeResolver.isDefaultPoolCode("DEFAULT-POOL")).isTrue();
        assertThat(PoolCodeResolver.isDefaultPoolCode("FAST")).isFalse();
    }

    /// [PoolCodeResolver#clientIdentifier] is [SqsDispatchPublisher]'s tenant
    /// lookup, reusing the SAME cached snapshot [#resolve] reads. Pinned
    /// directly (not just through the publisher) because it is its own
    /// public contract: a resolved client, an unresolved one, and `null`
    /// input are three distinct outcomes a caller can observe.
    @Test
    void clientIdentifierResolvesFromTheSameCachedSnapshotOrNullWhenUnresolved() {
        String clientId = SchedulerFixture.client("charlie" + RUN);
        PoolCodeResolver resolver = new PoolCodeResolver(DATA_SOURCE);

        assertThat(resolver.clientIdentifier(clientId)).isEqualTo("charlie" + RUN);
        assertThat(resolver.clientIdentifier("unknown-client-id")).isNull();
        assertThat(resolver.clientIdentifier(null)).isNull();
    }
}
