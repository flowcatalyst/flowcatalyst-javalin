package io.flowcatalyst.server.dbsecret;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// Polls [SecretSource] on an interval and pushes the current credentials
/// into a running [HikariDataSource] via `HikariConfigMXBean`, so a rotated
/// RDS password is picked up without a restart (spec §3).
///
/// **New connections use the current credentials; existing connections are
/// untouched** — `setUsername`/`setPassword` only change what Hikari hands to
/// the JDBC driver on the *next* physical connection it opens (Go: pgx
/// `BeforeConnect` has the identical effect). The refresher never evicts or
/// closes connections itself.
///
/// The initial fetch (at [#start]) is fatal on failure — `Main` must not
/// finish starting on a broken secret. Every fetch after that is logged at
/// WARN and leaves the previous credentials in place; there is no cached
/// failure state to inspect.
public final class DbSecretRefresher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DbSecretRefresher.class);

    private final HikariDataSource pool;
    private final SecretSource source;
    private final String arn;
    private final ScheduledExecutorService scheduler;

    private DbSecretRefresher(HikariDataSource pool, SecretSource source, String arn,
            ScheduledExecutorService scheduler) {
        this.pool = pool;
        this.source = source;
        this.arn = arn;
        this.scheduler = scheduler;
    }

    /// Fetches once immediately — a failure here propagates, fatal (spec §3)
    /// — then, when `intervalMs > 0`, schedules a re-fetch every `intervalMs`
    /// on a single daemon-thread scheduler (`db-secret-refresher`); `<= 0`
    /// disables the periodic refresh but the initial fetch still runs.
    public static DbSecretRefresher start(HikariDataSource pool, SecretSource source, String arn, long intervalMs) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(arn, "arn");

        var scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("db-secret-refresher").unstarted(r));
        var refresher = new DbSecretRefresher(pool, source, arn, scheduler);
        try {
            refresher.apply(DbSecretFetcher.fetch(source, arn));
        } catch (RuntimeException e) {
            scheduler.shutdownNow();
            throw e;
        }
        if (intervalMs > 0) {
            scheduler.scheduleWithFixedDelay(refresher::refreshNow, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        return refresher;
    }

    /// One refresh attempt: fetch + apply, or log-and-keep-the-old-pair on
    /// failure. Package-private so a test can drive a refresh synchronously
    /// instead of waiting on the schedule; this is exactly what the periodic
    /// tick calls.
    void refreshNow() {
        try {
            apply(DbSecretFetcher.fetch(source, arn));
        } catch (RuntimeException e) {
            LOG.warn("DB secret refresh failed; keeping current credentials arn={}", arn, e);
        }
    }

    private void apply(DbSecretFetcher.Credentials creds) {
        var config = pool.getHikariConfigMXBean();
        config.setUsername(creds.username());
        config.setPassword(creds.password());
    }

    /// Stops the periodic refresh. Idempotent.
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
