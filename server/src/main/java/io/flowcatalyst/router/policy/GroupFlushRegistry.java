package io.flowcatalyst.router.policy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/// Suppresses delivery for message groups a target has asked us to stop
/// sending — a per-message-group circuit breaker, the group-scoped sibling of
/// the per-endpoint breaker (`docs/spec/router.md` §2.11).
///
/// One registry **per pool**: the same group id in two pools suppresses
/// independently, so a flush in one pool never silences the other.
///
/// ### The safety condition, restated because it is not enforceable here
///
/// A suppressed message is **ACKed without ever being delivered**, which for
/// every backend means it is gone from the broker. That is only sound
/// because the *target* asked for it: it is asserting that it already owns
/// the records being pointed at and will re-drive them itself. A target whose
/// messages carry the only copy of the payload must never set `flushGroup` —
/// doing so is indistinguishable from data loss. Owner ruling (router Q54):
/// honour it from any target, no opt-in gate, revisit later
/// (`docs/improvements.md`).
///
/// ### Why a TTL rather than an explicit resume
///
/// Suppression expires rather than being cleared, so there is no resume
/// protocol for a target to get wrong: when the window lapses the next
/// message goes through as a **probe**, and the target either flushes again
/// or delivery resumes on its own. [#DEFAULT_TTL] applies when the target
/// names no window and [#MAX_TTL] caps it, so a target cannot silence a
/// group indefinitely.
public final class GroupFlushRegistry {

    /// Window used when a target asks to flush without naming one.
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    /// Longest a target may suppress a group for in one request.
    public static final Duration MAX_TTL = Duration.ofMinutes(5);

    /// Group id → the instant its suppression lapses. Guarded by the map's
    /// own atomicity: every mutation goes through [Map#compute] or
    /// [Map#remove], never a read-then-write, so concurrent workers on the
    /// same group cannot interleave into a lost update.
    private final Map<String, Instant> until = new ConcurrentHashMap<>();

    private final LongAdder flushes = new LongAdder();
    private final LongAdder suppressed = new LongAdder();
    private final Clock clock;

    public GroupFlushRegistry(Clock clock) {
        this.clock = clock;
    }

    /// Suppresses `group` for `ttl`, clamped to `(0, MAX_TTL]` — a
    /// non-positive `ttl` means [#DEFAULT_TTL].
    ///
    /// An empty group is a no-op: an ungrouped message has no siblings to
    /// suppress, so flushing can never create a bucket that swallows
    /// unrelated traffic.
    ///
    /// **Extending only.** A live window is never shortened, so a probe that
    /// lands mid-window cannot pull the expiry in. Returns whether the window
    /// actually moved — a re-flush that changes nothing is not counted, so
    /// the flush counter measures decisions rather than requests.
    public boolean flush(String group, Duration ttl) {
        if (group == null || group.isEmpty()) {
            return false;
        }
        var window = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
        if (window.compareTo(MAX_TTL) > 0) {
            window = MAX_TTL;
        }
        var expiry = clock.instant().plus(window);
        var applied = until.compute(group,
                (ignored, current) -> current != null && current.isAfter(expiry) ? current : expiry);
        if (!expiry.equals(applied)) {
            return false;
        }
        flushes.increment();
        return true;
    }

    /// Whether `group` is currently suppressed, evicting the entry as it
    /// expires so the next message probes the target.
    ///
    /// Counts each suppressed message, so this is the *deciding* read — the
    /// one on the delivery path. Use [#suppressedUntil] to look without
    /// counting.
    public boolean suppressed(String group) {
        if (group == null || group.isEmpty()) {
            return false;
        }
        var expiry = until.get(group);
        if (expiry == null) {
            return false;
        }
        if (!clock.instant().isBefore(expiry)) {
            // Remove only this expiry: a concurrent flush may already have
            // installed a later one, and evicting that would resume delivery
            // to a target that just asked us to stop.
            until.remove(group, expiry);
            return false;
        }
        suppressed.increment();
        return true;
    }

    /// When `group`'s suppression lapses, or empty when it is not suppressed.
    ///
    /// Counts nothing and evicts nothing — the read-only view for an operator
    /// asking "why is this group quiet?".
    public Optional<Instant> suppressedUntil(String group) {
        if (group == null || group.isEmpty()) {
            return Optional.empty();
        }
        var expiry = until.get(group);
        return expiry != null && clock.instant().isBefore(expiry) ? Optional.of(expiry) : Optional.empty();
    }

    /// Lifts suppression for `group` immediately — the operator override.
    public void clear(String group) {
        if (group != null && !group.isEmpty()) {
            until.remove(group);
        }
    }

    /// A snapshot for the monitoring surface.
    public Stats stats() {
        var now = clock.instant();
        long active = until.values().stream().filter(now::isBefore).count();
        return new Stats(active, flushes.sum(), suppressed.sum());
    }

    /// `active` counts groups suppressed right now; `flushes` and
    /// `suppressed` are lifetime totals.
    public record Stats(long active, long flushes, long suppressed) {
    }
}
