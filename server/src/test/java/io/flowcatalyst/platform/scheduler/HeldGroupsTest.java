package io.flowcatalyst.platform.scheduler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// The poller's 5-second memory of held groups (dispatch-queue spec step 3b §4): a held group is skipped by
/// the claim for 5 seconds and tried again after; the map is capped, the oldest dropped.
class HeldGroupsTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    @Test
    void aHeldGroupIsRememberedForFiveSecondsAndThenTriedAgain() {
        var held = new HeldGroups(now::get, 100);
        held.remember(List.of("g1"));
        assertThat(held.current()).containsExactly("g1");

        now.addAndGet(4_900_000_000L);
        assertThat(held.current()).as("still inside the 5 s").containsExactly("g1");

        now.addAndGet(200_000_000L);
        assertThat(held.current()).as("after 5 s the group is tried again").isEmpty();
        assertThat(held.size()).isZero();
    }

    @Test
    void foundHeldAgainRestartsTheFiveSeconds() {
        var held = new HeldGroups(now::get, 100);
        held.remember(List.of("g1", "g2"));
        now.addAndGet(3_000_000_000L);
        held.remember(List.of("g1"));
        now.addAndGet(3_000_000_000L);

        assertThat(held.current()).as("g2 expired, g1 was re-found held").containsExactly("g1");
    }

    @Test
    void theMapIsCappedAndTheOldestGroupsAreDropped() {
        var held = new HeldGroups(now::get, 3);
        for (int i = 0; i < 5; i++) {
            held.remember(List.of("g" + i));
            now.addAndGet(1_000L);
        }

        assertThat(held.current()).containsExactly("g2", "g3", "g4");
        assertThat(held.size()).isEqualTo(3);
    }

    @Test
    void theProductionCapIsTenThousand() {
        assertThat(HeldGroups.CAP).isEqualTo(10_000);
        assertThat(HeldGroups.TTL.toSeconds()).isEqualTo(5);
    }
}
