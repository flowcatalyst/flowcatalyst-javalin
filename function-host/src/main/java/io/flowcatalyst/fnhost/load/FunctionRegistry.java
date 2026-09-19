package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// One live [LoadedFunction] per [FunctionAddress] (`docs/spec/function-host-core.md`
/// §2.4). Bounded by `maxLoaded`: a *warm* entry (kept resident by the
/// platform's own warm-capacity policy) is never evicted; a *lazy* one is,
/// least-recently-accessed first, to make room for a new address.
public final class FunctionRegistry {

    /// Guards `live` — every read and every mutation of it (including the
    /// access-order reordering that `LinkedHashMap#get` performs) takes
    /// this lock. No method here calls back into a [LoadedFunction] while
    /// holding it.
    private final Object lock = new Object();

    private final int maxLoaded;
    private final LinkedHashMap<FunctionAddress, Entry> live;
    private final Clock clock;

    public FunctionRegistry(int maxLoaded) {
        this(maxLoaded, Clock.systemUTC());
    }

    /// @param clock injectable so a reconciler test can move "now" for the
    ///              idle-eviction clause (`docs/spec/function-host-reconciler.md`
    ///              §1.2 step 4) without sleeping
    public FunctionRegistry(int maxLoaded, Clock clock) {
        if (maxLoaded < 1) {
            throw new IllegalArgumentException("maxLoaded must be at least 1, was " + maxLoaded);
        }
        this.maxLoaded = maxLoaded;
        this.clock = Objects.requireNonNull(clock, "clock");
        // accessOrder=true: iteration order is least- to most-recently accessed,
        // so the head of live.entrySet() is always the LRU eviction candidate.
        this.live = new LinkedHashMap<>(16, 0.75f, true);
    }

    /// The live [LoadedFunction] for `address`, or `null`. Counts as an
    /// access for LRU purposes AND stamps the entry's last-accessed time —
    /// this is the method D3's invoke path (and [#ensureLoaded]-style callers)
    /// use. A caller that is only INSPECTING the registry — the reconciler's
    /// own bookkeeping — must use [#peek] instead, or every reconcile cycle
    /// would itself count as an access and no lazy entry could ever go idle.
    public LoadedFunction get(FunctionAddress address) {
        synchronized (lock) {
            Entry entry = live.get(address);
            if (entry == null) {
                return null;
            }
            entry.lastAccessed = clock.instant();
            return entry.function();
        }
    }

    /// [#get] without the access-order reordering or the last-accessed
    /// stamp — for a caller that is inspecting the registry, not invoking
    /// through it (`docs/spec/function-host-reconciler.md` §1.2).
    public LoadedFunction peek(FunctionAddress address) {
        synchronized (lock) {
            Entry entry = live.get(address);
            return entry == null ? null : entry.function();
        }
    }

    /// One entry, as of the moment [#snapshot] was taken.
    public record Snapshot(FunctionAddress address, LoadedFunction function, boolean warm, Instant lastAccessed) {
    }

    /// A point-in-time copy of every live entry — never mutates access
    /// order or the last-accessed stamp (same reasoning as [#peek]).
    public List<Snapshot> snapshot() {
        synchronized (lock) {
            List<Snapshot> out = new ArrayList<>(live.size());
            for (Map.Entry<FunctionAddress, Entry> e : live.entrySet()) {
                out.add(new Snapshot(e.getKey(), e.getValue().function(), e.getValue().warm(), e.getValue().lastAccessed));
            }
            return out;
        }
    }

    /// Removes and returns `address`'s live entry, or `null` if none — the
    /// caller closes the returned [LoadedFunction] (this never closes
    /// anything itself, same division of labour as [#put]'s displaced
    /// return).
    public LoadedFunction remove(FunctionAddress address) {
        synchronized (lock) {
            Entry entry = live.remove(address);
            return entry == null ? null : entry.function();
        }
    }

    /// [#put(LoadedFunction, boolean)] with `warm = false`.
    public LoadedFunction put(LoadedFunction function) {
        return put(function, false);
    }

    /// Registers `function` as the live version for its address. Returns
    /// the version it displaced for that same address, if any — the caller
    /// closes it once its in-flight invocations finish
    /// ([LoadedFunction#retain]/[LoadedFunction#release]).
    ///
    /// @param warm whether this entry is exempt from LRU eviction
    /// @throws IllegalStateException if registering a new address would
    ///                                exceed `maxLoaded` and every existing
    ///                                entry is warm — the platform's own
    ///                                warm-capacity check should have
    ///                                prevented this from being reachable
    public LoadedFunction put(LoadedFunction function, boolean warm) {
        Objects.requireNonNull(function, "function");
        FunctionAddress address = function.address();
        synchronized (lock) {
            if (!live.containsKey(address) && live.size() >= maxLoaded) {
                evictOneLazyEntryOrThrow();
            }
            Entry previous = live.put(address, new Entry(function, warm, clock.instant()));
            return previous == null ? null : previous.function();
        }
    }

    private void evictOneLazyEntryOrThrow() {
        Iterator<Map.Entry<FunctionAddress, Entry>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<FunctionAddress, Entry> candidate = it.next();
            if (!candidate.getValue().warm()) {
                it.remove();
                return;
            }
        }
        throw new IllegalStateException("function registry is at capacity (" + maxLoaded
                + ") and every loaded entry is warm; the platform's warm-capacity check "
                + "should have prevented this from being reached");
    }

    /// Not a record: `lastAccessed` is mutated in place by [#get] (always
    /// under `lock`) rather than replacing the map entry on every access.
    private static final class Entry {
        private final LoadedFunction function;
        private final boolean warm;
        private Instant lastAccessed;

        Entry(LoadedFunction function, boolean warm, Instant lastAccessed) {
            this.function = Objects.requireNonNull(function, "function");
            this.warm = warm;
            this.lastAccessed = Objects.requireNonNull(lastAccessed, "lastAccessed");
        }

        LoadedFunction function() {
            return function;
        }

        boolean warm() {
            return warm;
        }
    }
}
