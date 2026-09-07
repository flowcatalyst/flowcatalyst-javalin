package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.server.Server.Mode;
import io.flowcatalyst.server.Server.Spa;
import io.flowcatalyst.server.dbsecret.DbSecretDsn;
import io.flowcatalyst.server.dbsecret.DbSecretFetcher;
import io.flowcatalyst.server.dbsecret.DbSecretMode;
import io.flowcatalyst.server.dbsecret.DbSecretRefresher;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// `fc-server`: the unified production server. Single jar; every subsystem is
/// independently togglable via `FC_*_ENABLED` so the same image can be
/// deployed as the API tier, a worker tier, or both. `fcdev` wraps the same
/// [Server] with embedded Postgres + dev defaults.
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) throws Exception {
        Logging.init();
        Env env = Env.load();
        LOG.info("starting fc-server platform={} router={} scheduler={} stream={} outbox={} mcp={} standby={} api_port={} metrics_port={}",
                env.platformEnabled(), env.routerEnabled(), env.schedulerEnabled(), env.streamEnabled(),
                env.outboxEnabled(), env.mcpEnabled(), env.standbyEnabled(), env.apiPort(), env.metricsPort());

        // The platform database is needed by any subsystem that reads/writes
        // Postgres — including a router-only instance running the built-in
        // Postgres broker (`docs/spec/router.md` §8.4): it still writes/reads
        // `queue_messages` even with FC_PLATFORM_ENABLED=false. A router-only
        // or MCP-only instance with no Postgres broker skips connect entirely.
        boolean needsDb = needsDb(env);
        // Migrations and the seeder are platform-shaped work: `queue_messages`
        // is created by PostgresQueue.initSchema (Router.configSource), not
        // Flyway, so a router-only instance must never run either against a
        // database that may host nothing but that one table.
        boolean needsMigrateAndSeed = needsMigrateAndSeed(env);

        GatedDataSource pool = null;
        DbSecretRefresher dbSecretRefresher = null;
        Mode mode;
        if (needsDb) {
            // AWS Secrets Manager DB mode (docs/spec/db-secret.md): when DB_SECRET_ARN +
            // DB_HOST are set (and no explicit FC_DATABASE_URL/DATABASE_URL), resolve the
            // connection from the secret instead of env.databaseUrl(), and start a
            // refresher that pushes rotated credentials into new pool connections.
            // A failure here (bad provider, unreachable/malformed secret) is fatal —
            // exit before any subsystem starts, exactly as the failed-startup path below.
            var databaseUrl = env.databaseUrl();
            DbSecretMode.Applicable secretMode = null;
            try {
                if (DbSecretMode.resolve(EnvReader.system()) instanceof DbSecretMode.Applicable applicable) {
                    secretMode = applicable;
                    var creds = DbSecretFetcher.fetch(DbSecretFetcher.aws(applicable.arn()), applicable.arn());
                    databaseUrl = DbSecretDsn.build(creds.username(), creds.password(), applicable.host(),
                            creds.port(), applicable.dbPort(), applicable.dbName());
                    LOG.info("resolved database URL from AWS Secrets Manager");
                }
            } catch (RuntimeException e) {
                LOG.error("resolve DB secret failed", e);
                System.exit(1);
                return;
            }

            pool = Database.newPool(databaseUrl);
            LOG.info("postgres connected");

            if (secretMode != null) {
                try {
                    dbSecretRefresher = DbSecretRefresher.start(pool.hikari(), DbSecretFetcher.aws(secretMode.arn()),
                            secretMode.arn(), secretMode.refreshIntervalMs());
                } catch (RuntimeException e) {
                    LOG.error("DB secret refresher init failed", e);
                    System.exit(1);
                    return;
                }
            }

            if (needsMigrateAndSeed) {
                Migrator.migrate(pool);
                LOG.info("migrations applied");
                new Seeder(pool).run();
                LOG.info("seed complete");
            } else {
                LOG.info("router-only Postgres broker: skipping platform migrations and seed "
                        + "(queue_messages is created by PostgresQueue.initSchema)");
            }
            mode = env.platformEnabled() ? new Mode.Platform(pool)
                    : needsMigrateAndSeed ? new Mode.Worker(pool)
                    : Mode.routerOnly(pool);
        } else {
            LOG.info("no database-backed subsystem enabled; skipping postgres connect/migrate/seed router={} mcp={}",
                    env.routerEnabled(), env.mcpEnabled());
            mode = Mode.routerOnly();
        }

        // Serve the embedded Vue SPA only when the platform API is enabled and the
        // frontend was built in — a router-only or worker-only instance must not
        // serve a dashboard whose OIDC login it cannot satisfy.
        Spa spa = switch (mode) {
            case Mode.Platform _ -> Frontend.embeddedOrNone();
            case Mode.Worker _, Mode.RouterOnly _ -> Spa.none();
        };
        if (spa instanceof Spa.Embedded) LOG.info("embedded Vue SPA available");

        var running = new Server(env, mode, spa, PrometheusRegistry.defaultRegistry).start();

        GatedDataSource poolToClose = pool;
        DbSecretRefresher dbSecretRefresherToClose = dbSecretRefresher;
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            LOG.info("shutdown signal received");
            running.stop();
            if (dbSecretRefresherToClose != null) dbSecretRefresherToClose.close();
            if (poolToClose != null) poolToClose.close();
        }));
        running.awaitStop();
    }

    /// Whether this instance needs a Postgres pool at all: any DB-backed
    /// background subsystem ([#needsMigrateAndSeed]), or the router running
    /// its own built-in Postgres broker (`docs/spec/router.md` §8.4,
    /// [Router#usesDefaultPostgresBroker]) — a router-only deployment with
    /// `FC_ROUTER_ENABLED=true FC_DEFAULT_BROKER=postgres` still needs a pool
    /// even though `FC_PLATFORM_ENABLED` and every worker flag are off.
    static boolean needsDb(Env env) {
        return needsMigrateAndSeed(env) || (env.routerEnabled() && Router.usesDefaultPostgresBroker(env));
    }

    /// Whether platform migrations and the seeder should run against the
    /// pool. Deliberately **not** widened by the router's own Postgres
    /// broker: `queue_messages` is created by `PostgresQueue.initSchema`,
    /// not Flyway, and a router-only instance's database may host nothing
    /// else — running Flyway/the seeder against it would be pure surprise.
    static boolean needsMigrateAndSeed(Env env) {
        return env.platformEnabled() || env.streamEnabled() || env.schedulerEnabled()
                || env.scheduledJobEnabled() || env.outboxEnabled();
    }
}
