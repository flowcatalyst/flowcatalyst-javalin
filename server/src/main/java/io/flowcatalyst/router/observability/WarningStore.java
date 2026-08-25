package io.flowcatalyst.router.observability;

import io.flowcatalyst.router.observability.Warnings;

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

    // TODO(Q45-ish): Go's AutoAcknowledgeAge defaults to MaxWarningAge (both
    // 8h), so cleanup() auto-acks a warning in the very same pass that then
    // deletes it for being past MaxWarningAge — auto-ack never has an
    // observable effect on anything a caller could read in between.
    // docs/spec/router.md constant 45 flags this as "makes auto-ack moot."
    // Kept as-is rather than fixed silently.
    public static final Duration AUTO_ACKNOWLEDGE_AGE = MAX_WARNING_AGE;

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
    /// anything past [#MAX_WARNING_AGE]. Idempotent; intended to be driven
    /// by a periodic caller (see class doc) rather than run here.
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
            warnings.values().removeIf(w -> w.ageMinutes(now) > MAX_WARNING_AGE.toMinutes());
        } finally {
            lock.unlock();
        }
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
