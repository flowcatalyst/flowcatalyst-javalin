package io.flowcatalyst.fnhost.load;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.Optional;

/// Live read access to the JVM's `Metaspace` memory pool
/// (`docs/spec/function-host-process.md` §3 item 1, the headroom guard) —
/// injectable so a test can drive exact used/max values and count GC
/// requests deterministically, without forking a real JVM at a real fence
/// (`MetaspaceFenceForkTest` already does that, separately, for the
/// end-to-end proof).
public interface MetaspaceGauge {

    /// Bytes currently used in the `Metaspace` pool.
    long usedBytes();

    /// The pool's configured maximum, or `-1` when unbounded
    /// (`MemoryUsage#getMax`'s own convention, mirrored here) — the
    /// headroom guard is OFF in that case: there is no wall to run into.
    long maxBytes();

    /// Requests a garbage collection. Metaspace is reclaimed only after a
    /// collection — unlike the heap, nothing frees it incrementally — so a
    /// load found below the reserve gets exactly one chance to reclaim space
    /// (unloading other functions between reconcile cycles is the usual
    /// source) before being refused. Production: `System.gc()`.
    void requestGc();

    /// The real JVM `Metaspace` [MemoryPoolMXBean], found by name.
    static MetaspaceGauge system() {
        return new SystemMetaspaceGauge();
    }

    final class SystemMetaspaceGauge implements MetaspaceGauge {
        private static final String POOL_NAME = "Metaspace";

        @Override
        public long usedBytes() {
            return pool().map(p -> p.getUsage().getUsed()).orElse(0L);
        }

        @Override
        public long maxBytes() {
            return pool().map(p -> p.getUsage().getMax()).orElse(-1L);
        }

        @Override
        public void requestGc() {
            System.gc();
        }

        private static Optional<MemoryPoolMXBean> pool() {
            return ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(p -> POOL_NAME.equals(p.getName()))
                    .findFirst();
        }
    }
}
