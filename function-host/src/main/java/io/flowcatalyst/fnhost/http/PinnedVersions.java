package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.context.SettingsFingerprint;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// A small LRU (spec `function-invocation.md` §2, `function-host-listener.md`
/// §4): versions loaded for a versioned smoke-test call, beside the
/// registry, NEVER the registry's live slot. Capacity 8. Thread-safe;
/// [#getOrLoad] loads at most once per (address, version) under this
/// object's own lock (a `synchronized` map is enough here — versioned calls
/// are the rare, human-driven path, not the hot one [io.flowcatalyst.fnhost.load.FunctionRegistry]
/// serves).
public final class PinnedVersions {

    static final int CAPACITY = 8;

    private final Reconciler reconciler;
    private final Object lock = new Object();

    private record Key(FunctionAddress address, int version) {
    }

    /// D4b (`docs/spec/function-context.md` §2): the settings fingerprint a
    /// cached entry was loaded with, so [#sweep] can tell a genuine settings
    /// edit (same address/version, different config/secrets) from nothing
    /// having changed — "pinned: simply evict so the next call reloads".
    private record Cached(LoadedFunction function, String settingsFingerprint) {
    }

    /// `accessOrder=true`: eldest-accessed-first iteration, so the head is
    /// always the LRU eviction candidate once the map exceeds [#CAPACITY].
    private final LinkedHashMap<Key, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    public PinnedVersions(Reconciler reconciler) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    }

    /// Returns the cached [LoadedFunction] for (address, version) if present,
    /// else loads it fresh via [Reconciler#loadPinned] and caches it,
    /// evicting the least-recently-used entry first if the cache is full.
    /// `null` when `entry`'s version cannot be loaded (not prepared, or
    /// refused) — the caller reports `FUNCTION_UNAVAILABLE`/`VERSION_NOT_AVAILABLE`.
    public LoadedFunction getOrLoad(DesiredDocument.Entry entry) {
        Objects.requireNonNull(entry, "entry");
        Key key = new Key(entry.address(), entry.version());
        synchronized (lock) {
            Cached cached = cache.get(key); // reorders for LRU
            if (cached != null) {
                return cached.function();
            }
            LoadedFunction loaded = reconciler.loadPinned(entry);
            if (loaded == null) {
                return null;
            }
            if (cache.size() >= CAPACITY) {
                evictOldest();
            }
            cache.put(key, new Cached(loaded, fingerprintOf(entry)));
            return loaded;
        }
    }

    private static String fingerprintOf(DesiredDocument.Entry entry) {
        return SettingsFingerprint.of(entry.config(), entry.secrets());
    }

    private void evictOldest() {
        Iterator<Map.Entry<Key, Cached>> it = cache.entrySet().iterator();
        if (it.hasNext()) {
            Cached victim = it.next().getValue();
            it.remove();
            victim.function().close();
        }
    }

    /// Drops and closes every entry whose version is no longer present in
    /// the reconciler's current document ("closed when its version leaves
    /// desired state", spec §4), AND every entry whose settings changed
    /// underneath it (D4b/X5: "pinned: simply evict so the next call
    /// reloads") — called after each reconcile.
    public void sweep() {
        synchronized (lock) {
            Iterator<Map.Entry<Key, Cached>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Key, Cached> e = it.next();
                DesiredDocument.Entry current = reconciler.entryFor(e.getKey().address(), e.getKey().version());
                if (current == null || !fingerprintOf(current).equals(e.getValue().settingsFingerprint())) {
                    it.remove();
                    e.getValue().function().close();
                }
            }
        }
    }

    /// Test seam: how many entries are currently cached.
    int sizeForTest() {
        synchronized (lock) {
            return cache.size();
        }
    }

    public void close() {
        synchronized (lock) {
            cache.values().forEach(c -> c.function().close());
            cache.clear();
        }
    }
}
