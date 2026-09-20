package io.flowcatalyst.fnhost.load;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/// Deterministic [MetaspaceGauge] test double (`docs/spec/function-host-process.md`
/// §3 item 1) — `used`/`max` are set directly by the test, no real MXBean and
/// no forked JVM (`MetaspaceFenceForkTest` covers that end to end,
/// separately). [#gcRequests] counts [#requestGc] calls so a test can pin
/// "at most one per reconcile cycle"; [#onGc] lets a test simulate a
/// collection actually freeing space (by lowering `used` from inside the
/// callback) to prove [MetaspaceGuard] re-reads after requesting one rather
/// than trusting its own stale numbers.
public final class FakeMetaspaceGauge implements MetaspaceGauge {

    private final AtomicLong used = new AtomicLong();
    private final AtomicLong max = new AtomicLong(-1);
    private final AtomicInteger gcRequests = new AtomicInteger();
    private volatile Runnable onGc = () -> {
    };

    public FakeMetaspaceGauge used(long bytes) {
        used.set(bytes);
        return this;
    }

    public FakeMetaspaceGauge max(long bytes) {
        max.set(bytes);
        return this;
    }

    public FakeMetaspaceGauge onGc(Runnable action) {
        this.onGc = action;
        return this;
    }

    public int gcRequests() {
        return gcRequests.get();
    }

    @Override
    public long usedBytes() {
        return used.get();
    }

    @Override
    public long maxBytes() {
        return max.get();
    }

    @Override
    public void requestGc() {
        gcRequests.incrementAndGet();
        onGc.run();
    }
}
