package io.flowcatalyst.platform.dispatch;

import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.Test;

import static io.flowcatalyst.platform.dispatch.RouterConfigFixture.DS;
import static io.flowcatalyst.platform.dispatch.RouterConfigFixture.RUN;
import static org.assertj.core.api.Assertions.assertThat;

/// [RouterConfigDocumentBuilder] against a real embedded Postgres. Every
/// tenant/identifier here carries [RouterConfigFixture#RUN] so a test never
/// sees another test's (or another test class's — TestPg has no truncation
/// between tests) rows.
class RouterConfigDocumentBuilderTest {

    private static final DispatchQueueSettings SQS = new DispatchQueueSettings(
            true, "FC-" + RUN, "", "123456789012", "us-east-1");
    private static final DispatchQueueSettings POSTGRES = new DispatchQueueSettings(
            false, "fc-dev-" + RUN, "postgres://test/db", "", "");

    private static RouterConfigDocumentBuilder builder(DispatchQueueSettings settings) {
        return new RouterConfigDocumentBuilder(DS, settings);
    }

    /// Pins: every `msg_dispatch_pools` row is included regardless of
    /// `status`, namespaced exactly as [io.flowcatalyst.platform.scheduler.PoolCodeResolver]
    /// composes it (R7: platform-level gets the `platform-` prefix). A
    /// mutant that filters this list to `ACTIVE` only would drop the
    /// SUSPENDED and ARCHIVED rows asserted here.
    @Test
    void poolsIncludeEveryStatusNamespacedLikePoolCodeResolver() {
        String clientId = Tsid.generate();
        String identifier = "acme" + RUN;
        RouterConfigFixture.pool("dpx-fast-" + RUN, clientId, identifier, "ACTIVE", 5, 100);
        RouterConfigFixture.pool("dpx-plat-" + RUN, null, null, "SUSPENDED", 3, null);
        RouterConfigFixture.pool("dpx-arch-" + RUN, clientId, identifier, "ARCHIVED", 7, 50);

        var pools = builder(POSTGRES).build().processingPools();

        assertThat(pools).filteredOn(p -> p.code().equals(identifier + "-dpx-fast-" + RUN))
                .as("ACTIVE, client-owned").singleElement()
                .isEqualTo(new PoolSpec(identifier + "-dpx-fast-" + RUN, 5, 100));
        assertThat(pools).filteredOn(p -> p.code().equals("platform-dpx-plat-" + RUN))
                .as("SUSPENDED pool still included, platform-prefixed (R7)").singleElement()
                .isEqualTo(new PoolSpec("platform-dpx-plat-" + RUN, 3, 0));
        assertThat(pools).filteredOn(p -> p.code().equals(identifier + "-dpx-arch-" + RUN))
                .as("ARCHIVED pool still included").singleElement()
                .isEqualTo(new PoolSpec(identifier + "-dpx-arch-" + RUN, 7, 50));
    }

    /// Pins: a client with a pool row but no subscription still gets a
    /// `DEFAULT` queue — "dispatch work" is the union of pool and active-subscription
    /// client ids, not subscriptions alone.
    @Test
    void aClientWithOnlyAPoolStillGetsADefaultQueue() {
        String identifier = "foxtrot" + RUN;
        RouterConfigFixture.pool("dpx-ft-" + RUN, Tsid.generate(), identifier, "ACTIVE", 2, null);

        var queues = builder(SQS).build().queues();

        assertThat(queues).anySatisfy(q -> assertThat(q.queueName()).isEqualTo("FC-" + RUN + "-" + identifier + "-DEFAULT.fifo"));
    }

    /// Pins: a client with an active subscription but no pool still gets a
    /// `DEFAULT` queue.
    @Test
    void aClientWithOnlyAnActiveSubscriptionStillGetsADefaultQueue() {
        String identifier = "golf" + RUN;
        RouterConfigFixture.subscription(Tsid.generate(), identifier, "DEFAULT", "ACTIVE");

        var queues = builder(SQS).build().queues();

        assertThat(queues).anySatisfy(q -> assertThat(q.queueName()).isEqualTo("FC-" + RUN + "-" + identifier + "-DEFAULT.fifo"));
    }

    /// Pins R6 read leniency at the document-building boundary: a `PAUSED`
    /// (inactive) subscription's `HIGH_PRIORITY` value must never surface a
    /// `HIGH_PRIORITY` queue — only `ACTIVE` subscriptions count. A mutant
    /// that read every subscription regardless of status would add a
    /// `HIGH_PRIORITY` queue here that this test forbids.
    @Test
    void aPausedHighPrioritySubscriptionDoesNotProduceAHighPriorityQueue() {
        String identifier = "hotel" + RUN;
        RouterConfigFixture.pool("dpx-ht-" + RUN, Tsid.generate(), identifier, "ACTIVE", 1, null);
        RouterConfigFixture.subscription(Tsid.generate(), identifier, "HIGH_PRIORITY", "PAUSED");

        var queues = builder(SQS).build().queues();

        assertThat(queues).noneMatch(q -> q.queueName().equals("FC-" + RUN + "-" + identifier + "-HIGH_PRIORITY.fifo"));
        assertThat(queues).anyMatch(q -> q.queueName().equals("FC-" + RUN + "-" + identifier + "-DEFAULT.fifo"));
    }

    /// The counter that must change (CLAUDE.md testing policy): a client
    /// with only a `DEFAULT` active subscription advertises exactly one
    /// queue; adding a second, `HIGH_PRIORITY` active subscription for the
    /// SAME tenant grows that to two. Asserting 1 then 2 — not merely "2 at
    /// the end" — is what a mutant that always emits both queues (or never
    /// adds the second) cannot pass.
    @Test
    void addingAHighPriorityActiveSubscriptionGrowsTheTenantsQueueCountFromOneToTwo() {
        String identifier = "india" + RUN;
        String clientId = Tsid.generate();
        RouterConfigFixture.subscription(clientId, identifier, "DEFAULT", "ACTIVE");

        long before = queuesForTenant(builder(SQS).build(), identifier);
        assertThat(before).as("DEFAULT-only: one queue").isEqualTo(1);

        RouterConfigFixture.subscription(clientId, identifier, "HIGH_PRIORITY", "ACTIVE");

        long after = queuesForTenant(builder(SQS).build(), identifier);
        assertThat(after).as("DEFAULT + HIGH_PRIORITY: two queues").isEqualTo(2);
    }

    private static long queuesForTenant(RouterConfig config, String identifier) {
        return config.queues().stream()
                .filter(q -> q.queueName().startsWith("FC-" + RUN + "-" + identifier + "-"))
                .count();
    }

    /// Pins: an identifier long enough to push the composed SQS name past 80
    /// characters is refused for that tenant only — the document is still
    /// returned (never thrown out of [RouterConfigDocumentBuilder#build]),
    /// every other tenant (here, `platform`) is still present, and no queue
    /// name containing the over-long identifier appears anywhere. A mutant
    /// that let [io.flowcatalyst.platform.shared.dispatch.DispatchQueueName.QueueNameTooLongException]
    /// propagate would fail this test with an uncaught exception instead of
    /// a clean result.
    @Test
    void omitsATenantWhoseNameWouldExceedTheSqsLimitRatherThanFailingTheWholeDocument() {
        String tooLong = "z".repeat(90) + RUN;
        RouterConfigFixture.subscription(Tsid.generate(), tooLong, "DEFAULT", "ACTIVE");

        RouterConfig config = builder(SQS).build();

        assertThat(config.queues()).noneMatch(q -> q.queueName().contains(tooLong));
        assertThat(config.queues())
                .as("other tenants, including the always-present platform one, still made it in")
                .anyMatch(q -> q.queueName().equals("FC-" + RUN + "-platform-DEFAULT.fifo"));
    }

    /// Pins R5: the `platform` tenant's `DEFAULT` queue is always present,
    /// independent of any seeded row.
    @Test
    void platformTenantAlwaysHasADefaultQueue() {
        RouterConfig config = builder(SQS).build();

        assertThat(config.queues()).anyMatch(q -> q.queueName().equals("FC-" + RUN + "-platform-DEFAULT.fifo"));
    }

    /// Postgres-backed queues in one document all share the database URL as
    /// `queueUri`, carrying the composed name in `queueName` instead — the
    /// normal Postgres shape (`RouterConfig#merge` only keys by `queueUri`
    /// *across* merged sources, not within one document).
    @Test
    void postgresQueuesShareTheDatabaseUrlAsQueueUri() {
        String identifier = "juliet" + RUN;
        RouterConfigFixture.subscription(Tsid.generate(), identifier, "DEFAULT", "ACTIVE");

        var queues = builder(POSTGRES).build().queues();

        assertThat(queues).allMatch(q -> q.queueUri().equals("postgres://test/db"));
        assertThat(queues).anyMatch(q -> q.queueName().equals("fc-dev-" + RUN + "-platform-DEFAULT"));
        assertThat(queues).anyMatch(q -> q.queueName().equals("fc-dev-" + RUN + "-" + identifier + "-DEFAULT"));
    }
}
