package io.flowcatalyst.router.standby;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// Decides which instance runs the router (`docs/spec/router.md` §10.1).
///
/// ### The one property that matters
///
/// **Every failure demotes.** A store that errors, times out, or answers
/// anything but a clear "yes, still yours" makes this instance a follower
/// immediately. That is not defensive tidiness: leadership gates message
/// delivery, so believing you are leader when you are not means two routers
/// delivering the same messages to the same targets. Believing you are *not*
/// leader when you are costs a failover delay, and that is the cheaper
/// mistake by a wide margin.
///
/// ### What it does not prevent
///
/// A partitioned leader keeps delivering until its next refresh fails —
/// bounded by the heartbeat interval, not by the lock TTL. At most one
/// instance ever *holds* the key, but the outgoing leader's in-flight work
/// continues until it notices. Worst case without any leader is TTL plus one
/// heartbeat.
public final class LeaderElection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);

    /// How long a held lock survives without a refresh (spec constant 49).
    public static final Duration LOCK_TTL = Duration.ofSeconds(30);

    /// How often leadership is re-asserted. Comfortably inside the TTL so a
    /// single slow round-trip does not cost leadership.
    public static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /// @param enabled  false means single-instance: leader immediately, and
    ///                 the store is never contacted
    /// @param lockKey  the shared key instances contend for
    /// @param instanceId a value unique to this process, so a refresh can
    ///                 tell "still mine" from "someone else's"
    public record Config(boolean enabled, String lockKey, String instanceId,
                         Duration lockTtl, Duration heartbeat) {

        public static Config disabled() {
            return new Config(false, "fc:server:leader", UUID.randomUUID().toString(), LOCK_TTL, HEARTBEAT);
        }

        public static Config of(String lockKey) {
            return new Config(true, lockKey, UUID.randomUUID().toString(), LOCK_TTL, HEARTBEAT);
        }

        public Config {
            if (lockKey == null || lockKey.isBlank()) {
                throw new IllegalArgumentException("lockKey is required");
            }
            if (instanceId == null || instanceId.isBlank()) {
                throw new IllegalArgumentException("instanceId is required");
            }
            if (heartbeat.compareTo(lockTtl) >= 0) {
                // A heartbeat at or beyond the TTL would let the lock expire
                // between beats, handing leadership away on a healthy system.
                throw new IllegalArgumentException("heartbeat must be shorter than lockTtl");
            }
        }
    }

    /// A leadership transition. Only transitions are published — a steady
    /// state is not news, and re-running the gain path on every heartbeat
    /// would restart the config watcher ten times a minute.
    public record Change(boolean leader, Instant at) {
    }

    private final Config config;
    private final LockStore store;
    private final Clock clock;
    private final AtomicBoolean leader = new AtomicBoolean();
    private final List<Consumer<Change>> listeners = new CopyOnWriteArrayList<>();
    private volatile Thread heartbeatThread;

    public LeaderElection(Config config, LockStore store, Clock clock) {
        this.config = config;
        this.store = store;
        this.clock = clock;
    }

    public boolean isLeader() {
        return leader.get();
    }

    public String instanceId() {
        return config.instanceId();
    }

    /// Registers a listener for transitions. Called on the heartbeat thread,
    /// so a listener that blocks delays the next beat — they are expected to
    /// hand off rather than do work inline.
    public void onChange(Consumer<Change> listener) {
        listeners.add(listener);
    }

    /// Starts contending. Returns once the first decision has been made, so a
    /// caller can act on [#isLeader] immediately rather than racing the loop.
    ///
    /// @throws IllegalStateException if the store is unreachable — the router
    ///         must not start rather than start without knowing whether it is
    ///         leader, which would be the split-brain case
    public void start() {
        if (!config.enabled()) {
            // Single instance: leader from the outset, and the store is never
            // contacted at all.
            log.info("standby disabled; running as leader");
            setLeader(true);
            return;
        }
        store.ping();
        contend();
        heartbeatThread = Thread.ofVirtual().name("leader-election").start(this::heartbeatLoop);
    }

    private void heartbeatLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(config.heartbeat());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            contend();
        }
    }

    /// One round of "am I leader?".
    ///
    /// A held lock is refreshed; an unheld one is acquired. Either answering
    /// no, or the store failing, demotes.
    /// Runs one contention round on the caller's thread.
    ///
    /// Package-private so a test can drive the decision without waiting out a
    /// heartbeat — the interval is a constant, while what happens in a round
    /// is the behaviour.
    void contendForTest() {
        contend();
    }

    private void contend() {
        try {
            boolean held = leader.get()
                    ? store.refresh(config.lockKey(), config.instanceId(), config.lockTtl())
                    : store.acquire(config.lockKey(), config.instanceId(), config.lockTtl());
            if (!held && leader.get()) {
                // Lost it — most likely this instance stalled long enough for
                // the TTL to lapse and another to take over.
                log.warn("lost leadership of {}", config.lockKey());
            }
            setLeader(held);
        } catch (RuntimeException e) {
            // Fail-safe: we cannot prove we are leader, so we are not.
            log.warn("leader election failed against {}; demoting", config.lockKey(), e);
            setLeader(false);
        }
    }

    private void setLeader(boolean now) {
        if (leader.getAndSet(now) == now) {
            return;
        }
        var change = new Change(now, clock.instant());
        log.info("leadership {}", now ? "gained" : "lost");
        listeners.forEach(listener -> {
            try {
                listener.accept(change);
            } catch (RuntimeException e) {
                // One bad listener must not stop the others hearing about a
                // transition they may need to act on.
                log.warn("leadership listener failed", e);
            }
        });
    }

    /// Stops contending and gives up the lock if we hold it, so a rolling
    /// restart fails over immediately rather than waiting out the TTL.
    @Override
    public void close() {
        var thread = heartbeatThread;
        if (thread != null) {
            thread.interrupt();
        }
        if (config.enabled() && leader.get()) {
            try {
                store.release(config.lockKey(), config.instanceId());
            } catch (RuntimeException e) {
                // The TTL will clear it; a failed release costs a slower
                // failover, not correctness.
                log.warn("could not release leadership of {}", config.lockKey(), e);
            }
        }
        setLeader(false);
    }
}
