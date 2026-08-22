package io.flowcatalyst.server;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.server.Server.Mode;
import io.flowcatalyst.server.Server.Spa;
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

        // The platform database is only needed by subsystems that read/write
        // Postgres: a router-only or MCP-only instance skips connect/migrate/seed.
        boolean needsDb = env.platformEnabled() || env.streamEnabled() || env.schedulerEnabled()
                || env.scheduledJobEnabled() || env.outboxEnabled();

        HikariDataSource pool = null;
        Mode mode;
        if (needsDb) {
            // TODO(port): AWS Secrets Manager DB mode (DB_SECRET_ARN + DB_HOST) and credential rotation.
            pool = Database.newPool(env.databaseUrl(), Math.max(4, Runtime.getRuntime().availableProcessors()));
            LOG.info("postgres connected");
            Migrator.migrate(pool);
            LOG.info("migrations applied");
            new Seeder(pool).run();
            LOG.info("seed complete");
            mode = env.platformEnabled() ? new Mode.Platform(pool) : new Mode.Worker(pool);
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

        HikariDataSource poolToClose = pool;
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            LOG.info("shutdown signal received");
            running.stop();
            if (poolToClose != null) poolToClose.close();
        }));
        running.awaitStop();
    }
}
