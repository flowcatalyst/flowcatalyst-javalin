package io.flowcatalyst.fnhost.load;

import java.util.Objects;

/// Before-the-wall guard (`docs/spec/function-host-process.md` §3 item 1):
/// asked to warm-load far more functions than fit, the per-function
/// metaspace-OOM RECOVERY path (closing the loader, logging) can itself need
/// metaspace that is not there once the pool is deep enough into exhaustion
/// — that is what let an `OutOfMemoryError` escape `FnHost.start()` before
/// this guard existed. [#check] refuses a load WITHOUT attempting it — no
/// class definition, no reflection, nothing that could itself throw — the
/// moment free metaspace drops below a reserve, so the host never gets close
/// enough to that wall for the recovery path to matter.
///
/// Reserve = `max(RESERVE_MIN_BYTES, RESERVE_FRACTION * pool max)` — 64 MiB
/// or 5% of the pool's own `-XX:MaxMetaspaceSize`, whichever is larger.
/// Both are named constants, NOT env-configurable
/// (`feedback_no_tuning.md`: "a default that needs knobs is a defect"): 64
/// MiB is comfortably above the ~4.4 MB-per-typical-function figure measured
/// in `docs/function-runner-report.md` B1 — the reserve is what the HOST
/// ITSELF needs to keep logging, heartbeating and serving what is already
/// loaded, not a per-function budget — and 5% keeps the reserve
/// proportional on a host fenced much larger than the 64 MiB floor.
public final class MetaspaceGuard {

    static final long RESERVE_MIN_BYTES = 64L * 1024 * 1024;
    static final double RESERVE_FRACTION = 0.05;

    private final MetaspaceGauge gauge;

    /// At most ONE `System.gc()` request per reconcile cycle
    /// (`function-host-process.md` §3 item 1: "never per load" — a reconcile
    /// can attempt hundreds of loads in one cycle, and a GC per attempt
    /// would be its own performance defect). [#beginCycle] resets this;
    /// [Reconciler#reconcileOnce] calls it once, at the top of every cycle.
    private volatile boolean gcRequestedThisCycle;

    public MetaspaceGuard(MetaspaceGauge gauge) {
        this.gauge = Objects.requireNonNull(gauge, "gauge");
    }

    public static MetaspaceGuard system() {
        return new MetaspaceGuard(MetaspaceGauge.system());
    }

    /// Call once at the start of every reconcile cycle — allows exactly one
    /// more `System.gc()` request in the cycle that follows.
    public void beginCycle() {
        gcRequestedThisCycle = false;
    }

    /// One headroom check, read fresh every call (never cached) so a load
    /// later in the same cycle sees space freed by an earlier unload/close.
    /// [Result#hasHeadroom()] is what a caller branches on before ever
    /// calling [JvmFunctionLoader#load]; [Result#detail()] is the refusal's
    /// human-readable text.
    public Result check() {
        long max = gauge.maxBytes();
        if (max < 0) {
            return new Result(true, -1, -1, -1); // unbounded ⇒ guard off
        }
        long reserve = reserveBytes(max);
        long used = gauge.usedBytes();
        long free = max - used;
        if (free >= reserve) {
            return new Result(true, free, reserve, max);
        }
        // Below reserve: metaspace is only reclaimed after a collection (unlike the
        // heap), so give this cycle exactly one chance to reclaim space — from functions
        // unloaded earlier in THIS cycle, or simply fragmentation — before refusing.
        if (!gcRequestedThisCycle) {
            gcRequestedThisCycle = true;
            gauge.requestGc();
            used = gauge.usedBytes();
            free = max - used;
        }
        return new Result(free >= reserve, free, reserve, max);
    }

    static long reserveBytes(long max) {
        return Math.max(RESERVE_MIN_BYTES, Math.round(max * RESERVE_FRACTION));
    }

    public record Result(boolean hasHeadroom, long freeBytes, long reserveBytes, long maxBytes) {
        public String detail() {
            if (maxBytes < 0) {
                return "metaspace unbounded, guard off";
            }
            return "free=" + freeBytes + " reserve=" + reserveBytes + " max=" + maxBytes;
        }
    }
}
