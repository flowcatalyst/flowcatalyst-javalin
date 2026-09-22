package io.flowcatalyst.router.pool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/// One pool's deferral reservation schedule — head-of-line backpressure
/// (owner ruling 2026-09-22, `docs/go-mirror/2026-09-22-router-hol-deferral-handoff.md`
/// §3, `docs/spec/router-hol-deferral.md` §3 admission).
///
/// A reservation is a promise, not an estimate: the pool measures its own
/// completion rate and keeps a cursor, [#nextReturn], of when the last
/// deferred message was told to come back. Each deferral is booked one slot
/// after that cursor (or after the time the buffer needs to drain, whichever
/// is later), so deferred messages return spaced at the pool's own pace, in
/// the order they were deferred, and each of them bounces about once —
/// instead of everyone returning at the same moment to be bounced again in a
/// herd.
///
/// **No "come back early" hedge, deliberately.** An early return finds the
/// pool still full and has to re-book at the BACK of the schedule, which
/// both wastes the round-trip and pushes the cursor one slot further from
/// reality every time it happens.
///
/// **No cursor reset, deliberately.** A cursor in the past is simply
/// overtaken by the `max(...)` in [#delay]. Resetting it on "crossed back
/// under capacity" would fire on every completion of a pool that is being
/// kept full.
///
/// Owns its own lock, separate from [Pool]'s buffer lock: [#delay] runs on
/// the routing path ([Pool#submit]), not behind a drainer, and must not
/// queue behind buffer operations that have nothing to do with it.
final class PoolAdmission {

    /// Floors a reservation. Anything shorter is a hot loop against the
    /// broker for a pool that has not had time to free a slot.
    static final Duration MIN_DELAY = Duration.ofSeconds(5);

    /// The buffer-drain estimate when the pool has no completion in the rate
    /// window to measure from (just created, or every delivery in it is
    /// still running). Wrong-early costs one bounce; by the time it lands
    /// there is usually a rate.
    static final Duration FALLBACK_WAIT = Duration.ofSeconds(30);

    /// The minimum gap between consecutive reservations on a broker that
    /// does not itself keep a deferred group in order ([Broker#honoursDelayedReturn]
    /// false — NATS). Redelivery timers are not sub-second precise, so two
    /// reservations a few milliseconds apart could come back swapped; a
    /// second apart they cannot.
    static final Duration ORDERED_SPACING = Duration.ofSeconds(1);

    /// How far back [PoolMetrics#completionRate] looks.
    static final Duration RATE_WINDOW = Duration.ofMinutes(5);

    /// The reservation horizon when none is configured (`FC_ROUTER_DEFERRAL_MAX_DELAY_SECONDS`
    /// unset or non-positive).
    static final Duration DEFAULT_HORIZON = Duration.ofHours(1);

    /// The share of the horizon a clamped reservation is spread backwards
    /// over, so a large backlog's tail trickles in rather than arriving as
    /// one wave.
    private static final double JITTER_FRACTION = 0.25;

    private final Duration horizon;
    private final Clock clock;

    /// Guards [#nextReturn] and [#deferred] only — never held across the
    /// broker call [Pool#submit] makes before or after computing a delay.
    private final ReentrantLock lock = new ReentrantLock();

    /// The cursor: when the most recently booked reservation is due back.
    /// Starts at [Instant#EPOCH] — "nothing booked yet" — so the very first
    /// reservation is judged purely against the buffer's own drain time.
    private Instant nextReturn = Instant.EPOCH;

    private final AtomicLong deferredCount = new AtomicLong();

    PoolAdmission(Duration horizon, Clock clock) {
        this.horizon = horizon == null || horizon.isZero() || horizon.isNegative() ? DEFAULT_HORIZON : horizon;
        this.clock = clock;
    }

    /// Lifetime deferrals this pool has handed back for capacity.
    long totalDeferred() {
        return deferredCount.get();
    }

    /// Books the next reservation and returns the delay from now
    /// (`docs/spec/router-hol-deferral.md` §3):
    ///
    /// ```
    /// wait = queued / rate            (fallback 30s when no completion in window)
    /// slot = 1 / rate                 (fallback 1s)
    /// if (!brokerHonoursDelayedReturn) slot = max(slot, 1s)
    /// earliest = max(now + wait, nextReturn)
    /// reserved = earliest + slot
    /// nextReturn = reserved
    /// delay = reserved - now
    /// if (delay > horizon) delay = horizon - random(0..0.25 x horizon)
    /// delay = max(delay, 5s)
    /// ```
    ///
    /// @param queued                      messages currently buffered ([Pool#queueSize])
    /// @param rate                        [PoolMetrics#completionRate], empty when unmeasurable
    /// @param brokerHonoursDelayedReturn   [Broker#honoursDelayedReturn] for the message being deferred
    Duration delay(int queued, OptionalDouble rate, boolean brokerHonoursDelayedReturn) {
        Duration wait = FALLBACK_WAIT;
        Duration slot = ORDERED_SPACING;
        if (rate.isPresent() && rate.getAsDouble() > 0) {
            double r = rate.getAsDouble();
            wait = Duration.ofNanos(Math.round(queued / r * 1_000_000_000.0));
            slot = Duration.ofNanos(Math.round(1_000_000_000.0 / r));
        }
        if (!brokerHonoursDelayedReturn && slot.compareTo(ORDERED_SPACING) < 0) {
            slot = ORDERED_SPACING;
        }

        Duration delay;
        lock.lock();
        try {
            var now = clock.instant();
            var earliest = now.plus(wait);
            if (nextReturn.isAfter(earliest)) {
                earliest = nextReturn;
            }
            var reserved = earliest.plus(slot);
            nextReturn = reserved;
            deferredCount.incrementAndGet();
            delay = Duration.between(now, reserved);
        } finally {
            lock.unlock();
        }

        if (delay.compareTo(horizon) > 0) {
            // Spread the tail BACKWARDS over the last quarter of the
            // horizon — never forward past it.
            double jitter = ThreadLocalRandom.current().nextDouble() * JITTER_FRACTION;
            delay = horizon.minus(Duration.ofNanos(Math.round(horizon.toNanos() * jitter)));
        }
        return delay.compareTo(MIN_DELAY) < 0 ? MIN_DELAY : delay;
    }
}
