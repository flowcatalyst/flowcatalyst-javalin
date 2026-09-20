package io.flowcatalyst.fnhost.load;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [MetaspaceGuard] in isolation (`docs/spec/function-host-process.md` §3
/// item 1) — a deterministic [FakeMetaspaceGauge] instead of a real MXBean,
/// so every boundary is pinned exactly rather than raced against a real
/// JVM's own allocator (`MetaspaceGuard`'s use from a real [ManagementFactory]
/// pool, and the real end-to-end recovery, are `MetaspaceFenceForkTest`'s
/// job).
class MetaspaceGuardTest {

    private static final long MIB = 1024L * 1024;

    // ── reserve formula: max(64 MiB, 5% of pool max) ────────────────────────

    @Test
    void reserveIsTheFloorWhenFivePercentIsSmaller() {
        // 512 MiB * 5% = 25.6 MiB < the 64 MiB floor.
        assertThat(MetaspaceGuard.reserveBytes(512 * MIB))
                .as("mutant: use 5% alone, dropping the 64 MiB floor").isEqualTo(64 * MIB);
    }

    @Test
    void reserveIsFivePercentWhenThatIsLarger() {
        // 4096 MiB * 5% = 204.8 MiB > the 64 MiB floor.
        assertThat(MetaspaceGuard.reserveBytes(4096 * MIB))
                .as("mutant: use the 64 MiB floor alone, dropping the 5% share")
                .isEqualTo(Math.round(4096 * MIB * 0.05));
    }

    // ── unbounded max ⇒ guard off ────────────────────────────────────────────

    @Test
    void unboundedMaxTurnsTheGuardOff() {
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(-1).used(Long.MAX_VALUE / 2);
        MetaspaceGuard guard = new MetaspaceGuard(gauge);

        MetaspaceGuard.Result result = guard.check();

        assertThat(result.hasHeadroom()).as("mutant: treat -1 as a real, tiny max instead of unbounded").isTrue();
        assertThat(gauge.gcRequests()).as("mutant: request a GC even when the guard is off").isZero();
    }

    // ── threshold boundary: exactly at the reserve is fine, one byte under is not ──

    @Test
    void exactlyAtTheReserveHasHeadroomWithoutEverRequestingAGc() {
        long max = 512 * MIB;
        long reserve = MetaspaceGuard.reserveBytes(max); // 64 MiB
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(max).used(max - reserve); // free == reserve exactly
        MetaspaceGuard guard = new MetaspaceGuard(gauge);

        assertThat(guard.check().hasHeadroom()).as("mutant: use > instead of >= at the boundary").isTrue();
        // The FAST path (no GC needed) must be what answers this — not "happened to still pass
        // after wastefully requesting a GC it didn't need", which would mask a boundary mutant
        // in the fast-path comparison alone.
        assertThat(gauge.gcRequests())
                .as("mutant: off-by-one in the fast-path check masked by the slow path's own correct >=")
                .isZero();
    }

    @Test
    void oneByteUnderTheReserveHasNoHeadroom() {
        long max = 512 * MIB;
        long reserve = MetaspaceGuard.reserveBytes(max);
        // free = reserve - 1: below the reserve, and the fake never reclaims on GC, so it
        // stays below after the guard's one allowed retry too.
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(max).used(max - reserve + 1);
        MetaspaceGuard guard = new MetaspaceGuard(gauge);

        MetaspaceGuard.Result result = guard.check();

        assertThat(result.hasHeadroom()).as("mutant: allow a load one byte inside the reserve").isFalse();
        assertThat(result.detail()).as("mutant: an empty/useless detail string").contains("free=", "reserve=", "max=");
    }

    // ── at most one GC request per cycle, and it re-reads afterward ─────────

    @Test
    void oneGcIsRequestedWhenBelowReserveAndTheGuardReReadsAfterIt() {
        long max = 512 * MIB;
        long reserve = MetaspaceGuard.reserveBytes(max);
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(max).used(max - reserve + 1); // just below
        MetaspaceGuard guard = new MetaspaceGuard(gauge);

        // Below reserve and staying there: exactly one GC is requested by this ONE check() call.
        MetaspaceGuard.Result stillBelow = guard.check();
        assertThat(stillBelow.hasHeadroom()).isFalse();
        assertThat(gauge.gcRequests()).as("mutant: never request a GC at all").isEqualTo(1);

        // A second check() call in the SAME cycle (beginCycle() not called again): no further
        // GC request — "at most one per cycle", not "at most one per call that stays below".
        guard.check();
        assertThat(gauge.gcRequests()).as("mutant: request a GC on every call instead of once per cycle")
                .isEqualTo(1);

        // A GC that actually reclaims space is observed on the SAME check() call that
        // requested it (proving the guard re-reads rather than trusting stale numbers) —
        // proven fresh, with a gauge whose GC callback frees enough space.
        FakeMetaspaceGauge reclaiming = new FakeMetaspaceGauge().max(max).used(max - reserve + 1)
                .onGc(() -> { });
        MetaspaceGuard reclaimingGuard = new MetaspaceGuard(reclaiming);
        reclaiming.onGc(() -> reclaiming.used(0)); // the GC frees everything
        MetaspaceGuard.Result afterReclaim = reclaimingGuard.check();
        assertThat(afterReclaim.hasHeadroom())
                .as("mutant: never re-read after requesting the GC — this call would stay below").isTrue();
        assertThat(reclaiming.gcRequests()).isEqualTo(1);
    }

    @Test
    void beginCycleAllowsExactlyOneMoreGcRequest() {
        long max = 512 * MIB;
        long reserve = MetaspaceGuard.reserveBytes(max);
        FakeMetaspaceGauge gauge = new FakeMetaspaceGauge().max(max).used(max - reserve + 1);
        MetaspaceGuard guard = new MetaspaceGuard(gauge);

        guard.check();
        guard.check();
        assertThat(gauge.gcRequests()).isEqualTo(1);

        guard.beginCycle();
        guard.check();
        assertThat(gauge.gcRequests()).as("mutant: beginCycle() does not actually reset the per-cycle flag")
                .isEqualTo(2);
    }
}
