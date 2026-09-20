package io.flowcatalyst.fnhost.context;

import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.platform.function.Manifest;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Builds a [HostFunctionContext] from a desired-state entry (spec
/// `function-context.md` §2) — the one place that turns `manifest.db[]`
/// declarations into provisioned [DbPools] entries, eagerly, at load time
/// (see [DbPools]'s own doc for why eager). On any database failure, every
/// pool this call itself acquired is released before the
/// [ContextLoadException] propagates — a half-built context is never handed
/// back for the caller to leak.
public final class ContextFactory {

    private final DbPools dbPools;
    private final HttpClient httpClient;
    private final Clock clock;

    public ContextFactory(DbPools dbPools, HttpClient httpClient, Clock clock) {
        this.dbPools = Objects.requireNonNull(dbPools, "dbPools");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// Production convenience: [DbPools] sized from `maxDbPools`
    /// (`FC_FN_MAX_DB_POOLS`), [AllowlistHttpCaller#newSharedClient] (redirects
    /// disabled), the system clock.
    public static ContextFactory production(int maxDbPools) {
        return new ContextFactory(new DbPools(maxDbPools), AllowlistHttpCaller.newSharedClient(), Clock.systemUTC());
    }

    /// @param token reference-counts this context's database pool usage in
    ///              [DbPools] — the caller's [io.flowcatalyst.fnhost.load.LoadedFunction],
    ///              so [HostFunctionContext#close] releases exactly this
    ///              load attempt's share when that function is unloaded
    /// @throws ContextLoadException a declared `db[]` entry's DSN is
    ///                               unsupported (`DB_UNSUPPORTED`) or this
    ///                               host is already at `FC_FN_MAX_DB_POOLS`
    ///                               distinct pools (`DB_POOL_LIMIT`)
    public HostFunctionContext build(DesiredDocument.Entry entry, Object token) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(token, "token");

        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        List<String> acquired = new ArrayList<>();
        for (Manifest.DbRef ref : entry.manifest().db()) {
            String rawDsn = entry.secrets().get(ref.secretRef());
            if (rawDsn == null) {
                release(acquired, token);
                throw new ContextLoadException("DB_UNSUPPORTED",
                        "no value stored for db '" + ref.name().value() + "'s secretRef");
            }
            DbPools.Acquired a;
            try {
                a = dbPools.acquire(rawDsn, ref.poolSize(), token);
            } catch (Dsn.UnsupportedDsnException e) {
                release(acquired, token);
                throw new ContextLoadException("DB_UNSUPPORTED",
                        "db '" + ref.name().value() + "': " + e.getMessage());
            } catch (DbPools.PoolLimitException e) {
                release(acquired, token);
                throw new ContextLoadException("DB_POOL_LIMIT",
                        "db '" + ref.name().value() + "': " + e.getMessage());
            }
            acquired.add(a.poolIdentity());
            dataSources.put(ref.name().value(), a.dataSource());
        }

        io.flowcatalyst.function.FunctionAddress apiAddress = toApiAddress(entry.address());
        AllowlistHttpCaller http = new AllowlistHttpCaller(httpClient, entry.manifest().httpAllow(), clock);
        return new HostFunctionContext(apiAddress, entry.version(), new MapConfig(entry.config()),
                new MapSecrets(entry.secrets()), dataSources, http, clock, dbPools, acquired, token);
    }

    private void release(List<String> acquired, Object token) {
        for (String identity : acquired) {
            dbPools.release(identity, token);
        }
    }

    private static io.flowcatalyst.function.FunctionAddress toApiAddress(
            io.flowcatalyst.platform.function.FunctionAddress platformAddress) {
        return new io.flowcatalyst.function.FunctionAddress(
                platformAddress.application().value(), platformAddress.service().value(),
                platformAddress.name().value());
    }
}
