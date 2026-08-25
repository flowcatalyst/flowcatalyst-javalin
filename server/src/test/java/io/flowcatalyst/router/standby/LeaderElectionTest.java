package io.flowcatalyst.router.standby;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Leader election (`docs/spec/router.md` §10.1).
///
/// Leadership gates message delivery, so the asymmetry between the two ways
/// of being wrong is the whole design: believing you are leader when you are
/// not means two routers delivering the same messages to the same targets,
/// while believing you are not when you are costs a failover delay. Most of
/// these tests are about the first mistake being impossible.
class LeaderElectionTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FakeStore store = new FakeStore();

    private LeaderElection election(LeaderElection.Config config) {
        return new LeaderElection(config, store, clock);
    }

    private LeaderElection.Config enabled() {
        return new LeaderElection.Config(true, "fc:server:leader", "instance-a",
                Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("with standby disabled the instance is leader without contacting the store")
    void disabledIsAlwaysLeader() {
        try (var election = election(LeaderElection.Config.disabled())) {
            election.start();

            assertThat(election.isLeader()).isTrue();
            assertThat(store.calls.get()).as("a single-instance deployment needs no Redis").isZero();
        }
    }

    @Test
    @DisplayName("acquiring the lock makes this instance leader")
    void acquiringMakesLeader() {
        try (var election = election(enabled())) {
            election.start();

            assertThat(election.isLeader()).isTrue();
            assertThat(store.holder).isEqualTo("instance-a");
        }
    }

    @Test
    @DisplayName("a lock already held elsewhere leaves this instance a follower")
    void contestedLockMakesFollower() {
        store.holder = "instance-b";

        try (var election = election(enabled())) {
            election.start();

            assertThat(election.isLeader()).isFalse();
            assertThat(store.holder).as("another instance keeps it").isEqualTo("instance-b");
        }
    }

    @Test
    @DisplayName("a store error demotes rather than assuming leadership")
    void storeErrorDemotes() {
        // The whole safety property. If we cannot prove we are leader, we are
        // not — because the alternative is two routers delivering.
        try (var election = election(enabled())) {
            election.start();
            assertThat(election.isLeader()).isTrue();

            store.failing = true;
            election.contendNow();

            assertThat(election.isLeader()).isFalse();
        }
    }

    @Test
    @DisplayName("a refresh that says the lock moved on demotes immediately")
    void lostLockDemotes() {
        try (var election = election(enabled())) {
            election.start();

            // This instance stalled long enough for the TTL to lapse and
            // another to take over.
            store.holder = "instance-b";
            election.contendNow();

            assertThat(election.isLeader()).isFalse();
        }
    }

    @Test
    @DisplayName("a follower keeps trying and takes over when the lock frees up")
    void followerTakesOverWhenFreed() {
        store.holder = "instance-b";
        try (var election = election(enabled())) {
            election.start();
            assertThat(election.isLeader()).isFalse();

            store.holder = null; // the leader died and its key expired
            election.contendNow();

            assertThat(election.isLeader()).isTrue();
        }
    }

    @Test
    @DisplayName("an unreachable store stops the router starting at all")
    void unreachableStoreRefusesToStart() {
        // Starting without knowing whether we are leader IS the split-brain
        // case, so refusing to start is the safe answer.
        store.pingFails = true;

        try (var election = election(enabled())) {
            assertThatThrownBy(election::start).isInstanceOf(RuntimeException.class);
            assertThat(election.isLeader()).isFalse();
        }
    }

    @Test
    @DisplayName("only transitions are published, not every heartbeat")
    void onlyTransitionsArePublished() {
        // Re-running the gain path on every beat would restart the config
        // watcher ten times a minute.
        var changes = new CopyOnWriteArrayList<Boolean>();
        try (var election = election(enabled())) {
            election.onChange(change -> changes.add(change.leader()));
            election.start();

            election.contendNow();
            election.contendNow();

            assertThat(changes).containsExactly(true);
        }
    }

    @Test
    @DisplayName("both directions are published, with the time they happened")
    void gainAndLossArePublished() {
        var changes = new CopyOnWriteArrayList<LeaderElection.Change>();
        try (var election = election(enabled())) {
            election.onChange(changes::add);
            election.start();

            clock.advance(Duration.ofSeconds(10));
            store.holder = "instance-b";
            election.contendNow();

            assertThat(changes).hasSize(2);
            assertThat(changes.getFirst().leader()).isTrue();
            assertThat(changes.getLast().leader()).isFalse();
            assertThat(changes.getLast().at()).isEqualTo(clock.instant());
        }
    }

    @Test
    @DisplayName("one failing listener does not stop the others hearing a transition")
    void aFailingListenerIsIsolated() {
        var heard = new AtomicBoolean();
        try (var election = election(enabled())) {
            election.onChange(change -> {
                throw new IllegalStateException("listener is broken");
            });
            election.onChange(change -> heard.set(true));

            election.start();

            assertThat(heard).isTrue();
        }
    }

    @Test
    @DisplayName("closing releases the lock so a restart fails over immediately")
    void closeReleasesTheLock() {
        // Otherwise a rolling restart leaves the cluster leaderless for the
        // whole TTL.
        var election = election(enabled());
        election.start();
        assertThat(store.holder).isEqualTo("instance-a");

        election.close();

        assertThat(store.holder).isNull();
        assertThat(election.isLeader()).isFalse();
    }

    @Test
    @DisplayName("a follower closing does not release someone else's lock")
    void followerCloseLeavesTheLockAlone() {
        store.holder = "instance-b";
        var election = election(enabled());
        election.start();

        election.close();

        assertThat(store.holder).as("not ours to release").isEqualTo("instance-b");
    }

    @Test
    @DisplayName("a heartbeat at or beyond the TTL is rejected at construction")
    void heartbeatMustBeShorterThanTheTtl() {
        // Equal would let the lock expire between beats, handing leadership
        // away on a perfectly healthy system.
        assertThatThrownBy(() -> new LeaderElection.Config(
                true, "k", "id", Duration.ofSeconds(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeaderElection.Config(
                true, "k", "id", Duration.ofSeconds(30), Duration.ofSeconds(45)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// A lock store with one holder, so "who has it" is directly assertable.
    private static final class FakeStore implements LockStore {
        volatile String holder;
        volatile boolean failing;
        volatile boolean pingFails;
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean acquire(String key, String value, Duration ttl) {
            calls.incrementAndGet();
            failIfAsked();
            if (holder == null) {
                holder = value;
                return true;
            }
            return holder.equals(value);
        }

        @Override
        public boolean refresh(String key, String value, Duration ttl) {
            calls.incrementAndGet();
            failIfAsked();
            return value.equals(holder);
        }

        @Override
        public void release(String key, String value) {
            calls.incrementAndGet();
            if (value.equals(holder)) {
                holder = null;
            }
        }

        @Override
        public void ping() {
            calls.incrementAndGet();
            if (pingFails) {
                throw new IllegalStateException("redis unreachable");
            }
        }

        private void failIfAsked() {
            if (failing) {
                throw new IllegalStateException("redis error");
            }
        }
    }

    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
