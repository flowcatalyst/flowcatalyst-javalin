package io.flowcatalyst.router.observability;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/// The bounded, operator-facing warning store (`docs/spec/router.md` §2.7,
/// §9.5, constant 45).
///
/// Bounded two ways: by count ([#MAX_WARNINGS], oldest 10% evicted on
/// overflow before insert) and by age ([#cleanup] auto-acks then drops
/// anything past [#MAX_WARNING_AGE]). [#cleanup] is not scheduled by this
/// class — the Go runs it from a 5-minute ticker (constant 47's
/// `WarningCleanupInterval`, owned by the not-yet-ported lifecycle loop);
/// callers drive the cadence.
///
/// Acknowledgement has two distinct readers, and conflating them is a real
/// defect risk (§9.4/§9.5):
///
///   - [#unacknowledged] — every unacked warning, **any age**. This is
///     "what /warnings shows unacked," not what health uses.
///   - [#active] — unacked **and** no older than a caller-supplied age.
///     `HealthReport.activeWarnings` calls this with 30 minutes (constant
///     46); it is what actually drives the dashboard's `Warning` status.
///   - [#critical] — unacked CRITICAL, any age. `HealthReport.criticalWarnings`
///     calls this; a single unacked CRITICAL makes the router `Degraded`
///     until acknowledged or aged out at 8 h (§9.4).
public final class WarningStore implements Warnings {

    public static final Duration MAX_WARNING_AGE = Duration.ofHours(8);
    public static final int MAX_WARNINGS = 1000;

    /// How long an unacknowledged warning holds the router's attention before
    /// `cleanup()` marks it read on the operator's behalf.
    ///
    /// **Owner ruling 2026-08-25: one hour.** It must be shorter than
    /// [#MAX_WARNING_AGE] to do anything at all — Go sets both to 8h, so
    /// `cleanup()` auto-acknowledges a warning in the very same pass that then
    /// deletes it for being 8 hours old, and nothing can ever read it in the
    /// auto-acknowledged state (`docs/spec/router.md` constant 45, "makes
    /// auto-ack moot"). The setting existed, had a test, and did nothing.
    ///
    /// One hour is the answer to a real operational question: how long should
    /// a single unacknowledged CRITICAL keep the router reporting `Degraded`
    /// when nobody has looked at it? Long enough to be noticed on a shift,
    /// short enough not to mask the next genuine one. The warning stays
    /// **visible in history for the full 8 hours** either way — acknowledging
    /// it stops it driving health, it does not hide it.
    public static final Duration AUTO_ACKNOWLEDGE_AGE = Duration.ofHours(1);

    /// The materially shorter TTL an `INFO`-severity notice gets (spec §7.1
    /// X-04) instead of [#MAX_WARNING_AGE]. `INFO` is the routine "this
    /// changed" tier (breaker CLOSED, rate limiter back to unlimited) rather
    /// than something an operator needs to keep finding hours later, and
    /// without a shorter clock a flood of it can crowd out real warnings
    /// before anyone looks — the store's whole point per §7.1 is that
    /// *everything* lands here first, including categories that used to skip
    /// it, so this is the safety valve that keeps that inclusiveness cheap.
    public static final Duration MAX_INFO_AGE = Duration.ofHours(1);

    /// The `Warnings` interface (which this class does not own) has no
    /// `source` parameter — every raiser shares one `Warnings` instance
    /// (`ConsumerSupervisor`, `ConsumerLoop`, `RouterManager` all take a
    /// plain `Warnings`), so per-call-site sourcing like Go's
    /// `"HttpMediator"` / `"StallDetector"` cannot be recovered here. A
    /// fixed source is the only option available through this interface.
    private static final String SOURCE = "router";

    /// One stored operational notice. `acknowledgedAt` is `null` until
    /// acknowledged.
    public record Notice(UUID id, String category, Severity severity, String message, String source,
                          Instant createdAt, boolean acknowledged, Instant acknowledgedAt) {

        private Notice acknowledge(Instant at) {
            return new Notice(id, category, severity, message, source, createdAt, true, at);
        }

        private long ageMinutes(Instant now) {
            return Duration.between(createdAt, now).toMinutes();
        }
    }

    /// A snapshot for the `/warnings` monitoring API — every stored
    /// warning, acknowledged or not, in insertion (creation) order.
    public record Snapshot(List<Notice> warnings) {

        public Snapshot {
            warnings = List.copyOf(warnings);
        }
    }

    private final Clock clock;

    /// Guards `warnings` — `raise`, `acknowledge` and `cleanup` all need to
    /// read the size / age / acknowledged state and mutate it as one step.
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID, Notice> warnings = new LinkedHashMap<>();

    public WarningStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void raise(Severity severity, String category, String message) {
        var now = clock.instant();
        var notice = new Notice(UUID.randomUUID(), category, severity, message, SOURCE, now, false, null);
        lock.lock();
        try {
            if (warnings.size() >= MAX_WARNINGS) {
                evictOldestLocked();
            }
            warnings.put(notice.id(), notice);
        } finally {
            lock.unlock();
        }
    }

    /// Removes the oldest 10% of stored warnings by `createdAt`. Caller
    /// must hold [#lock]. No-op below 10 warnings (`size / 10 == 0`),
    /// matching the Go.
    private void evictOldestLocked() {
        int toRemove = warnings.size() / 10;
        if (toRemove == 0) {
            return;
        }
        warnings.values().stream()
                .sorted(Comparator.comparing(Notice::createdAt))
                .limit(toRemove)
                .map(Notice::id)
                .toList()
                .forEach(warnings::remove);
    }

    /// Flips one warning to acknowledged. Returns `false` if no warning has
    /// that id.
    public boolean acknowledge(UUID id) {
        var now = clock.instant();
        lock.lock();
        try {
            var existing = warnings.get(id);
            if (existing == null) {
                return false;
            }
            warnings.put(id, existing.acknowledge(now));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /// Auto-acks anything unacked past [#AUTO_ACKNOWLEDGE_AGE], then drops
    /// anything past its severity's max age — [#MAX_INFO_AGE] for `INFO`,
    /// [#MAX_WARNING_AGE] for everything else. Idempotent; intended to be
    /// driven by a periodic caller (see class doc) rather than run here.
    public void cleanup() {
        var now = clock.instant();
        lock.lock();
        try {
            for (var entry : warnings.entrySet()) {
                var w = entry.getValue();
                if (!w.acknowledged() && w.ageMinutes(now) > AUTO_ACKNOWLEDGE_AGE.toMinutes()) {
                    entry.setValue(w.acknowledge(now));
                }
            }
            warnings.values().removeIf(w -> w.ageMinutes(now) > maxAgeMinutes(w.severity()));
        } finally {
            lock.unlock();
        }
    }

    private static long maxAgeMinutes(Severity severity) {
        return (severity == Severity.INFO ? MAX_INFO_AGE : MAX_WARNING_AGE).toMinutes();
    }

    /// Every stored warning, for the `/warnings` API.
    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(new ArrayList<>(warnings.values()));
        } finally {
            lock.unlock();
        }
    }

    /// Every unacknowledged warning, any age. See class doc for how this
    /// differs from [#active].
    public List<Notice> unacknowledged() {
        lock.lock();
        try {
            return warnings.values().stream().filter(w -> !w.acknowledged()).toList();
        } finally {
            lock.unlock();
        }
    }

    /// Unacknowledged warnings no older than `maxAge`, floored to whole
    /// minutes the same way the Go's `AgeMinutes()` does — a warning at
    /// 30 min 59 s is still "≤ 30 min" for a 30-minute `maxAge`. This is
    /// what `HealthReport.activeWarnings` counts (§9.4).
    public List<Notice> active(Duration maxAge) {
        var now = clock.instant();
        long limitMinutes = maxAge.toMinutes();
        lock.lock();
        try {
            return warnings.values().stream()
                    .filter(w -> !w.acknowledged() && w.ageMinutes(now) <= limitMinutes)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /// Unacknowledged CRITICAL warnings, any age — what
    /// `HealthReport.criticalWarnings` counts, and what makes the router
    /// `Degraded`/`NOT_READY` until one is acknowledged or ages out (§9.4).
    public List<Notice> critical() {
        lock.lock();
        try {
            return warnings.values().stream()
                    .filter(w -> !w.acknowledged() && w.severity() == Severity.CRITICAL)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public int count() {
        lock.lock();
        try {
            return warnings.size();
        } finally {
            lock.unlock();
        }
    }
}
