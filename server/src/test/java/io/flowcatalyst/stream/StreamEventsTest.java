package io.flowcatalyst.stream;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.stream.jfr.FanOutBatchEvent;
import io.flowcatalyst.testjfr.Recorded;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static io.flowcatalyst.stream.StreamFixture.DS;
import static org.assertj.core.api.Assertions.assertThat;

/// The `FanOutBatch` flight-recorder event (`docs/spec/jfr-events.md` §1),
/// read back out of a real recording against the embedded Postgres — same
/// idiom as `FanOutTest`, but asserting the recorded fields rather than the
/// `msg_dispatch_jobs` rows the batch produced.
class StreamEventsTest {

    private static final int BIG_BATCH = 200_000;
    private static final SubscriptionRepository SUBSCRIPTIONS = new SubscriptionRepository(DS);

    private static FanOut fanOut(Supplier<List<Subscription>> loader) {
        return new FanOut(DS, loader, Duration.ofSeconds(9999), Clock.systemUTC());
    }

    private static Supplier<List<Subscription>> fixed(String... subscriptionIds) {
        var ids = List.of(subscriptionIds);
        List<Subscription> subs = SUBSCRIPTIONS.findActiveOrderedById().stream()
                .filter(s -> ids.contains(s.id())).toList();
        return () -> subs;
    }

    @Test
    @DisplayName("a batch claiming two matching events records eventsClaimed, jobsInserted and the subscription count")
    void batchWithMatchesRecordsCounts() throws Exception {
        String type = StreamFixture.type("jfr-batch");
        String subId = StreamFixture.subscription("jfr-batch", "https://example.test/jfr-hook",
                DispatchMode.IMMEDIATE, null, type);
        StreamFixture.event(type, "test://jfr-source", null, null, null, null, null, Instant.now());
        StreamFixture.event(type, "test://jfr-source", null, null, null, null, null, Instant.now());

        var events = Recorded.from(FanOutBatchEvent.class, () -> fanOut(fixed(subId)).step(BIG_BATCH));

        assertThat(events).hasSize(1);
        var batch = events.getFirst();
        assertThat(batch.getInt("eventsClaimed")).as("both seeded events were claimed").isEqualTo(2);
        assertThat(batch.getInt("jobsInserted")).as("both matched the one subscription").isEqualTo(2);
        assertThat(batch.getInt("subscriptions")).isEqualTo(1);
        assertThat(batch.getBoolean("noSubscriptions")).isFalse();
    }

    @Test
    @DisplayName("a step that claims nothing (LIMIT 0) records no event at all")
    void stepThatClaimsNothingRecordsNoEvent() throws Exception {
        String type = StreamFixture.type("jfr-nothing");
        String subId = StreamFixture.subscription("jfr-nothing", "https://example.test/jfr-nothing-hook",
                DispatchMode.IMMEDIATE, null, type);
        StreamFixture.event(type, "test://jfr-source", null, null, null, null, null, Instant.now());

        // batchSize 0 -> the claim SQL's LIMIT 0 deterministically claims
        // nothing, regardless of what is sitting unfanned in the shared,
        // never-truncated database (CONVENTIONS §6).
        var events = Recorded.from(FanOutBatchEvent.class, () -> fanOut(fixed(subId)).step(0));

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("the claim-only path (no subscriptions) is recorded with noSubscriptions=true and jobsInserted=0")
    void claimOnlyPathRecordsNoSubscriptions() throws Exception {
        String type = StreamFixture.type("jfr-nosubs");
        String eventId = StreamFixture.event(type, "test://jfr-source", null, null, null, null, null, Instant.now());

        // One step() call -> exactly one committed batch (BIG_BATCH sweeps
        // every currently-unfanned row on the shared, never-truncated
        // database, CONVENTIONS §6, but that is still a single transaction).
        var events = Recorded.from(FanOutBatchEvent.class, () -> fanOut(List::of).step(BIG_BATCH));

        assertThat(events).hasSize(1);
        var batch = events.getFirst();
        assertThat(batch.getBoolean("noSubscriptions")).isTrue();
        assertThat(batch.getInt("jobsInserted")).isEqualTo(0);
        assertThat(batch.getInt("subscriptions")).isEqualTo(0);
        // The seeded row was in fact claimed by this pass.
        assertThat(fannedOutAt(eventId)).isTrue();
    }

    private static Boolean fannedOutAt(String eventId) {
        var v = StreamFixture.DB.select(io.flowcatalyst.db.generated.Tables.MSG_EVENTS.FANNED_OUT_AT)
                .from(io.flowcatalyst.db.generated.Tables.MSG_EVENTS)
                .where(io.flowcatalyst.db.generated.Tables.MSG_EVENTS.ID.eq(eventId)).fetchOne();
        return v != null && v.value1() != null;
    }
}
