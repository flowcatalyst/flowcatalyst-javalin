package io.flowcatalyst.platform.scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.LongSupplier;

/// The groups the poller has just found held back (a `BLOCK_ON_ERROR` group behind a failed or backed-off
/// head), remembered for [#TTL] so the claim skips them instead of taking their rows, finding them held and
/// putting them back on every pass: a batch-full of held rows at the head of the order would otherwise starve
/// everything behind it (dispatch-queue spec step 3b §4). After the TTL a group is tried again. Capped at
/// [#CAP] groups, the oldest dropped. The poller's thread only.
final class HeldGroups {

    static final Duration TTL = Duration.ofSeconds(5);
    static final int CAP = 10_000;

    private final LongSupplier nanoClock;
    private final int cap;
    /// group -> when it was last found held (nanos); insertion order = age order (a re-held group moves to the end).
    private final LinkedHashMap<String, Long> held = new LinkedHashMap<>();

    HeldGroups() {
        this(System::nanoTime, CAP);
    }

    HeldGroups(LongSupplier nanoClock, int cap) {
        this.nanoClock = nanoClock;
        this.cap = cap;
    }

    /// Notes that these groups were just found held.
    void remember(Collection<String> groups) {
        long now = nanoClock.getAsLong();
        for (String g : groups) {
            held.remove(g);
            held.put(g, now);
        }
        while (held.size() > cap) {
            Iterator<String> oldest = held.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /// The groups still remembered (older entries are forgotten), for the claim to skip.
    List<String> current() {
        long cutoff = nanoClock.getAsLong() - TTL.toNanos();
        for (Iterator<Long> it = held.values().iterator(); it.hasNext(); ) {
            if (it.next() <= cutoff) it.remove();
            else break; // insertion order is age order: the rest are younger
        }
        return new ArrayList<>(held.keySet());
    }

    void clear() {
        held.clear();
    }

    int size() {
        return held.size();
    }
}
