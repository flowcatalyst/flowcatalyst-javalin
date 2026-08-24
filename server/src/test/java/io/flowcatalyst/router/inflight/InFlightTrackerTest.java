package io.flowcatalyst.router.inflight;

import io.flowcatalyst.router.inflight.InFlightTracker.Registration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router.md` §2.3 and §4.2.
///
/// The three-way classification is the whole point of this component, so the
/// tests are organised around what each outcome *obliges the caller to do* —
/// deliver, drop, or ACK the duplicate — since getting that wrong either
/// loses a message or delivers it twice.
class InFlightTrackerTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InFlightTracker tracker = new InFlightTracker(clock);

    @Test
    @DisplayName("an unseen message is new, and this process owns it")
    void firstCopyIsNew() {
        assertThat(tracker.register(message("m1", "b1", "r1"))).isEqualTo(Registration.NEW);
        assertThat(tracker.size()).isOne();
    }

    @Test
    @DisplayName("the same broker delivery arriving again is a redelivery, and swaps the handle")
    void sameBrokerIdIsRedelivery() {
        tracker.register(message("m1", "b1", "r1"));
        clock.advance(Duration.ofMinutes(3));

        var outcome = tracker.register(message("m1", "b1", "r2"));

        assertThat(outcome).isInstanceOf(Registration.Redelivery.class);
        // The old handle is now stale at the broker; acknowledging with it
        // would silently leave the message to redeliver forever.
        assertThat(tracker.freshestHandle("m1")).contains("r2");
        assertThat(tracker.size()).isOne();
    }

    @Test
    @DisplayName("a different broker delivery of a message we own is an external requeue")
    void differentBrokerIdIsExternalRequeue() {
        // Another instance released it, or the broker duplicated it. This
        // copy must be ACKed on its OWN handle and not delivered.
        tracker.register(message("m1", "b1", "r1"));

        var outcome = tracker.register(message("m1", "b2", "r2"));

        assertThat(outcome).isInstanceOf(Registration.ExternalRequeue.class);
        // The owner keeps its handle: it is still working on b1.
        assertThat(tracker.freshestHandle("m1")).contains("r1");
    }

    @Test
    @DisplayName("a blank broker id on either side is treated as a redelivery, not a requeue")
    void blankBrokerIdFallsBackToRedelivery() {
        // The cautious fallback: mis-classifying a redelivery as an external
        // requeue would ACK a delivery the owner is still working on.
        // Dropping a copy is recoverable; deleting a live one is not.
        tracker.register(message("m1", "", "r1"));
        assertThat(tracker.register(message("m1", "b2", "r2")))
                .isInstanceOf(Registration.Redelivery.class);

        var other = new InFlightTracker(clock);
        other.register(message("m2", "b1", "r1"));
        assertThat(other.register(message("m2", "", "r2")))
                .isInstanceOf(Registration.Redelivery.class);
    }

    @Test
    @DisplayName("an entry with no broker id is still tracked by application id")
    void blankBrokerIdIsNotIndexedByBroker() {
        tracker.register(message("m1", "", "r1"));
        tracker.register(message("m2", "", "r2"));

        // Two blank ids must not collide into one broker-index entry.
        assertThat(tracker.size()).isEqualTo(2);
        assertThat(tracker.freshestHandle("m1")).contains("r1");
        assertThat(tracker.freshestHandle("m2")).contains("r2");
    }

    @Test
    @DisplayName("ensureTracked restores an entry the reaper pruned")
    void ensureTrackedRestores() {
        var message = message("m1", "b1", "r1");
        tracker.register(message);
        clock.advance(Duration.ofHours(1));
        tracker.reapIdle(Duration.ofMinutes(15));
        assertThat(tracker.size()).isZero();

        assertThat(tracker.ensureTracked(message)).isTrue();
        assertThat(tracker.size()).isOne();
    }

    @Test
    @DisplayName("ensureTracked tells a losing copy that it does not own the pipeline")
    void ensureTrackedRejectsAnotherCopy() {
        tracker.register(message("m1", "b1", "r1"));

        assertThat(tracker.ensureTracked(message("m1", "b2", "r2"))).isFalse();
        assertThat(tracker.ensureTracked(message("m1", "b1", "r1"))).isTrue();
    }

    @Test
    @DisplayName("ensureTracked never swaps the handle")
    void ensureTrackedKeepsTheFreshestHandle() {
        // The stored handle may be fresher than the caller's: a redelivery
        // could have swapped it while the caller waited for a slot.
        tracker.register(message("m1", "b1", "r1"));
        tracker.register(message("m1", "b1", "r2"));

        tracker.ensureTracked(message("m1", "b1", "r1"));

        assertThat(tracker.freshestHandle("m1")).contains("r2");
    }

    @Test
    @DisplayName("a retrying entry is never reaped, however long it takes")
    void retryingEntriesSurviveTheReaper() {
        // They are slow on purpose. Reaping one would let a duplicate through
        // while the original is still being worked.
        tracker.register(message("m1", "b1", "r1"));
        tracker.register(message("m2", "b2", "r2"));
        tracker.markRetrying("m1");

        clock.advance(Duration.ofHours(4));

        assertThat(tracker.reapIdle(Duration.ofMinutes(15))).isOne();
        assertThat(tracker.freshestHandle("m1")).isPresent();
        assertThat(tracker.freshestHandle("m2")).isEmpty();
    }

    @Test
    @DisplayName("the reaper ages on last seen, not on when the message started")
    void reaperAgesOnLastSeen() {
        // A message being redelivered is alive, however long ago it started.
        tracker.register(message("m1", "b1", "r1"));
        clock.advance(Duration.ofMinutes(14));
        tracker.register(message("m1", "b1", "r2")); // redelivery refreshes liveness
        clock.advance(Duration.ofMinutes(14));

        assertThat(tracker.reapIdle(Duration.ofMinutes(15))).isZero();

        clock.advance(Duration.ofMinutes(2));
        assertThat(tracker.reapIdle(Duration.ofMinutes(15))).isOne();
    }

    @Test
    @DisplayName("a non-positive reap window is a no-op")
    void nonPositiveReapWindow() {
        tracker.register(message("m1", "b1", "r1"));
        clock.advance(Duration.ofDays(30));

        assertThat(tracker.reapIdle(Duration.ZERO)).isZero();
        assertThat(tracker.reapIdle(Duration.ofMinutes(-5))).isZero();
        assertThat(tracker.size()).isOne();
    }

    @Test
    @DisplayName("removing releases both indexes, and is idempotent")
    void removeReleasesBothIndexes() {
        tracker.register(message("m1", "b1", "r1"));

        tracker.remove("m1");
        tracker.remove("m1");

        assertThat(tracker.size()).isZero();
        // The broker index must be clear too, or the id could never be
        // registered again.
        assertThat(tracker.register(message("m1", "b1", "r3"))).isEqualTo(Registration.NEW);
    }

    @Test
    @DisplayName("removing a superseded entry does not un-track the live one")
    void removeDoesNotEvictALaterOwner() {
        tracker.register(message("m1", "b1", "r1"));
        tracker.remove("m1");
        tracker.register(message("m1", "b1", "r2"));

        tracker.remove("m1");

        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("the snapshot reports elapsed time and retry state for operators")
    void snapshotForMonitoring() {
        tracker.register(message("m1", "b1", "r1"));
        tracker.markRetrying("m1");
        clock.advance(Duration.ofSeconds(90));

        var entry = tracker.snapshot().getFirst();

        assertThat(entry.messageId()).isEqualTo("m1");
        assertThat(entry.retrying()).isTrue();
        assertThat(entry.elapsedSeconds(clock.instant())).isEqualTo(90);
        // Unresolved, so an operator can see the message named no pool rather
        // than seeing the fallback and assuming it did.
        assertThat(entry.poolCode()).isEmpty();
    }

    @Test
    @DisplayName("concurrent copies of one message elect exactly one owner")
    void concurrentRegistrationElectsOneOwner() throws Exception {
        int threads = 64;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var news = new AtomicInteger();

        IntStream.range(0, threads).forEach(i -> Thread.ofVirtual().start(() -> {
            try {
                start.await();
                if (tracker.register(message("m1", "b" + i, "r" + i)) instanceof Registration.New) {
                    news.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }));
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        // Anything else means two workers deliver the same message.
        assertThat(news.get()).isOne();
        assertThat(tracker.size()).isOne();
    }

    private InFlightMessage message(String messageId, String brokerId, String receipt) {
        var now = clock.instant();
        return new InFlightMessage(messageId, brokerId, "", "queue-1", now, now, "", "1", receipt, 0);
    }

    private static final class TestClock extends Clock {
        private Instant now;

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
