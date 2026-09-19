package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.Iterator;
import java.util.LinkedHashMap;
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

    public FunctionRegistry(int maxLoaded) {
        if (maxLoaded < 1) {
            throw new IllegalArgumentException("maxLoaded must be at least 1, was " + maxLoaded);
        }
        this.maxLoaded = maxLoaded;
        // accessOrder=true: iteration order is least- to most-recently accessed,
        // so the head of live.entrySet() is always the LRU eviction candidate.
        this.live = new LinkedHashMap<>(16, 0.75f, true);
    }

    /// The live [LoadedFunction] for `address`, or `null`. Counts as an
    /// access for LRU purposes.
    public LoadedFunction get(FunctionAddress address) {
        synchronized (lock) {
            Entry entry = live.get(address);
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
            Entry previous = live.put(address, new Entry(function, warm));
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

    private record Entry(LoadedFunction function, boolean warm) {

        private Entry {
            Objects.requireNonNull(function, "function");
        }
    }
}
