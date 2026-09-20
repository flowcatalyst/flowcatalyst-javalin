package io.flowcatalyst.fnhost.context;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.platform.shared.database.GatedDataSource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/// One HikariCP pool per distinct, normalised DSN, shared by every loaded
/// function version that declares it (spec `function-context.md` §2).
/// Reference-counted by loaded version (the token passed to [#acquire] /
/// [#release] — [io.flowcatalyst.fnhost.load.LoadedFunction]'s own identity,
/// one per load attempt, so a settings reload that loads a fresh version
/// counts as a fresh user even when the DSN is unchanged): the pool's
/// `maximumPoolSize` is the largest `poolSize` any CURRENT user declared,
/// raised on demand as a new, larger user joins, and the pool closes the
/// moment the last user releases it — never before, never sooner.
///
/// **Eager provisioning** (the slice's choice, spec §2 leaves it open): a
/// pool is created or joined at LOAD time, before [io.flowcatalyst.function.Function#init]
/// runs, not lazily on the function's first [io.flowcatalyst.function.FunctionContext#dataSource]
/// call. A bad DSN or a pool-count overflow is then a load failure like any
/// other (spec §6: "load failures ... appear as FAILED heartbeat entries
/// ... and the old version keeps serving") rather than a runtime surprise
/// deep inside a function that already passed its own health checks days
/// earlier.
///
/// Bounded by `maxPools` ([io.flowcatalyst.fnhost.reconcile.HostEnv]'s
/// `FC_FN_MAX_DB_POOLS`, default 16) — a brand-new DSN past the limit is
/// [PoolLimitException], never an eviction of a pool already in use.
public final class DbPools implements AutoCloseable {

    private final int maxPools;
    private final Object lock = new Object();
    private final Map<String, Entry> pools = new LinkedHashMap<>();

    public DbPools(int maxPools) {
        if (maxPools < 1) {
            throw new IllegalArgumentException("maxPools must be at least 1, was " + maxPools);
        }
        this.maxPools = maxPools;
    }

    /// One acquisition: the [DataSource] a function receives for one
    /// `manifest.db[]` entry, plus what [#release] needs to find the same
    /// pool again.
    public record Acquired(String poolIdentity, DataSource dataSource) {
    }

    /// @throws Dsn.UnsupportedDsnException `rawDsn` is not a supported PostgreSQL DSN
    /// @throws PoolLimitException          a brand-new DSN would exceed `maxPools`
    public Acquired acquire(String rawDsn, int poolSize, Object token) {
        Objects.requireNonNull(token, "token");
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be at least 1, was " + poolSize);
        }
        Dsn dsn = Dsn.parse(rawDsn);
        String identity = dsn.identity();
        synchronized (lock) {
            Entry entry = pools.get(identity);
            if (entry == null) {
                if (pools.size() >= maxPools) {
                    throw new PoolLimitException(
                            "at most " + maxPools + " distinct database pools may be open on this host");
                }
                entry = Entry.open(dsn, poolSize);
                pools.put(identity, entry);
            }
            entry.addUser(token, poolSize);
            return new Acquired(identity, new FunctionDataSource(entry.gate));
        }
    }

    /// Decrements `token`'s reference on `poolIdentity`'s pool; closes and
    /// drops it once no user remains. A no-op for an identity/token pair
    /// this instance never (or no longer) holds — [HostFunctionContext#close]
    /// is idempotent-safe to call this more than once.
    public void release(String poolIdentity, Object token) {
        Objects.requireNonNull(poolIdentity, "poolIdentity");
        Objects.requireNonNull(token, "token");
        synchronized (lock) {
            Entry entry = pools.get(poolIdentity);
            if (entry == null) {
                return;
            }
            if (entry.removeUser(token)) {
                pools.remove(poolIdentity);
                entry.close();
            }
        }
    }

    /// Test seam: how many distinct pools are currently open (X7: "assert
    /// one Hikari pool").
    public int poolCountForTest() {
        synchronized (lock) {
            return pools.size();
        }
    }

    /// Test seam: the current `maximumPoolSize` Hikari reports for the pool
    /// backing `rawDsn`, or `-1` if there is none — X7's "size = the larger
    /// poolSize" assertion.
    public int currentMaxPoolSizeForTest(String rawDsn) {
        Dsn dsn = Dsn.parse(rawDsn);
        synchronized (lock) {
            Entry entry = pools.get(dsn.identity());
            return entry == null ? -1 : entry.hikari.getMaximumPoolSize();
        }
    }

    /// Test seam: identifies the actual [HikariDataSource] instance backing
    /// `rawDsn` right now — stronger than [#poolCountForTest] alone, which a
    /// bug that silently replaces the map's value under the same key (rather
    /// than genuinely reusing the same pool) would not by itself catch.
    public int hikariIdentityForTest(String rawDsn) {
        Dsn dsn = Dsn.parse(rawDsn);
        synchronized (lock) {
            Entry entry = pools.get(dsn.identity());
            return entry == null ? -1 : System.identityHashCode(entry.hikari);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            for (Entry entry : pools.values()) {
                entry.close();
            }
            pools.clear();
        }
    }

    private static final class Entry {
        final HikariDataSource hikari;
        final AtomicReference<GatedDataSource> gate;
        final Map<Object, Integer> users = new LinkedHashMap<>();

        private Entry(HikariDataSource hikari, AtomicReference<GatedDataSource> gate) {
            this.hikari = hikari;
            this.gate = gate;
        }

        static Entry open(Dsn dsn, int poolSize) {
            GatedDataSource initial = Database.newPool(dsn.raw(), poolSize);
            return new Entry(initial.hikari(), new AtomicReference<>(initial));
        }

        /// Adds/updates `token`'s declared `poolSize` and resizes the pool up
        /// if this raises the max any current user needs ("raised on demand,
        /// never above the client ceiling the platform already enforced" —
        /// enforcement of that ceiling happened when the manifest's `poolSize`
        /// was itself resolved against it; this class only ever takes the max
        /// of what it is handed).
        void addUser(Object token, int poolSize) {
            users.put(token, poolSize);
            resize();
        }

        /// @return true once no user remains — the caller then closes this entry
        boolean removeUser(Object token) {
            users.remove(token);
            if (users.isEmpty()) {
                return true;
            }
            resize();
            return false;
        }

        /// Verified: [com.zaxxer.hikari.HikariConfigMXBean#setMaximumPoolSize]
        /// raises a running Hikari pool's ceiling live — Hikari's own pool
        /// housekeeping thread grows toward it. This does not, by itself,
        /// widen [GatedDataSource]'s own admission semaphore (sized once at
        /// construction from `hikari.getMaximumPoolSize()`), so a fresh
        /// [GatedDataSource] is built over the SAME [HikariDataSource] and
        /// swapped into [#gate] — one Hikari pool throughout (same object,
        /// same `poolName`, same physical connections), a new gate sized to
        /// match. The displaced [GatedDataSource] is never closed here (that
        /// would close the shared Hikari pool); it is simply discarded — any
        /// caller still holding its `AtomicReference` read reads the new one
        /// on its next checkout.
        void resize() {
            int newMax = users.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            if (newMax != hikari.getMaximumPoolSize()) {
                hikari.getHikariConfigMXBean().setMaximumPoolSize(newMax);
                gate.set(GatedDataSource.over(hikari));
            }
        }

        void close() {
            gate.get().close(); // closes the shared HikariDataSource too
        }
    }

    /// A brand-new DSN would exceed `FC_FN_MAX_DB_POOLS` (spec §2: "one more
    /// is a load failure `DB_POOL_LIMIT`, not an eviction of a pool in use").
    public static final class PoolLimitException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        PoolLimitException(String message) {
            super(message);
        }
    }
}
