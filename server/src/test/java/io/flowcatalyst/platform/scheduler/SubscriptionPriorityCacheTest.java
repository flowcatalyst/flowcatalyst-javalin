package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.shared.dispatch.QueuePriority;
import org.junit.jupiter.api.Test;

import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [SubscriptionPriorityCache] against a real embedded Postgres: every
/// ruling-R6 fallback case a claimed job's subscription can present. Each
/// test builds its own cache instance — the 60s TTL would otherwise outlive
/// a second test method's freshly-seeded row within the same run.
class SubscriptionPriorityCacheTest {

    @Test
    void resolvesTheStoredPriorityCaseInsensitively() {
        String subId = SchedulerFixture.subscriptionWithQueue("HIGH_PRIORITY");

        assertThat(new SubscriptionPriorityCache(DATA_SOURCE).priorityFor(subId)).isEqualTo(QueuePriority.HIGH_PRIORITY);
    }

    @Test
    void aNullStoredValueReadsAsDefault() {
        String subId = SchedulerFixture.subscriptionWithQueue(null);

        assertThat(new SubscriptionPriorityCache(DATA_SOURCE).priorityFor(subId)).isEqualTo(QueuePriority.DEFAULT);
    }

    /// Ruling R6: legacy Go-era text neither validation can reach reads as
    /// DEFAULT, never an error — the accepted-cost downgrade the owner chose
    /// over refusing to publish.
    @Test
    void unrecognisedLegacyTextReadsAsDefault() {
        String subId = SchedulerFixture.subscriptionWithQueue("workers-high");

        assertThat(new SubscriptionPriorityCache(DATA_SOURCE).priorityFor(subId)).isEqualTo(QueuePriority.DEFAULT);
    }

    /// Ruling R6's other two cases: no subscription at all, and a
    /// subscription id this cache has no row for (never created, or
    /// deleted) — both read as DEFAULT, never `null` and never a lookup
    /// failure.
    @Test
    void noSubscriptionAndAnUnresolvableSubscriptionIdBothReadAsDefault() {
        SubscriptionPriorityCache cache = new SubscriptionPriorityCache(DATA_SOURCE);

        assertThat(cache.priorityFor(null)).isEqualTo(QueuePriority.DEFAULT);
        assertThat(cache.priorityFor("no-such-subscription-id")).isEqualTo(QueuePriority.DEFAULT);
    }
}
