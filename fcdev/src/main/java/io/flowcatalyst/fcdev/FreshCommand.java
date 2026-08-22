package io.flowcatalyst.fcdev;

import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.platform.shared.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;

/// `fcdev fresh` (Go `fresh.go`): remove every row from every FlowCatalyst
/// table, returning the database to an immediately-post-migration state. The
/// migration tracker is preserved so the next boot skips re-migrating. Boots
/// the embedded Postgres itself when no `--database-url` is given, so it is
/// self-contained (no need to leave `fcdev start` running). Refuses without
/// `--yes`.
@Command(name = "fresh", description = "Truncate every FlowCatalyst table (preserves schema)",
        mixinStandardHelpOptions = true, sortOptions = false)
public final class FreshCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(FreshCommand.class);

    /// The explicit list of FlowCatalyst tables `fresh` truncates — the same
    /// list, in the same order, as the Go `freshTables`. Anything not listed
    /// belongs to a consumer app and is intentionally left alone. (The
    /// duplicates — `oauth_idp_role_mappings`, `oauth_clients` — are in the
    /// Go list too; Postgres accepts a table named twice in one `TRUNCATE`.)
    public static final List<String> FRESH_TABLES = List.of(
            // Audit + event read tables (high-volume, leaf).
            "aud_logs",
            "msg_events_read",
            "msg_events",
            "msg_dispatch_jobs",
            "msg_dispatch_job_attempts",
            "msg_scheduled_job_instances",
            "msg_subscription_event_types",
            "msg_event_type_spec_versions",
            "msg_subscriptions",
            "msg_event_types",
            "msg_connections",
            "msg_dispatch_pools",
            "msg_scheduled_jobs",
            // OAuth + login state.
            "oauth_oidc_payloads",
            "oauth_oidc_login_states",
            "oauth_client_grant_types",
            "oauth_client_allowed_origins",
            "oauth_client_redirect_uris",
            "oauth_client_application_ids",
            "oauth_clients",
            "oauth_idp_role_mappings",
            "oauth_identity_provider_allowed_domains",
            "oauth_identity_providers",
            // Webauthn.
            "webauthn_credentials",
            // Auth tracking.
            "iam_login_attempts",
            "iam_password_reset_tokens",
            // IAM + tenancy relations. additional_client_ids and granted_client_ids are
            // JSONB columns on tnt_client_auth_configs, not separate junction tables.
            "tnt_client_auth_configs",
            "tnt_anchor_domains",
            "iam_principal_application_access",
            "iam_client_access_grants",
            "iam_principal_roles",
            "iam_role_permissions",
            "iam_principals",
            "iam_roles",
            "oauth_idp_role_mappings",
            "oauth_clients",
            "tnt_cors_allowed_origins",
            "iam_service_accounts",
            "app_client_configs",
            "app_applications",
            "tnt_clients",
            // Platform config.
            "app_platform_config_access",
            "app_platform_configs");

    @Option(names = "--database-url", paramLabel = "<url>", description = "Postgres URL (defaults to local embedded) (FC_DATABASE_URL)")
    String databaseUrl;

    @Option(names = "--embedded-db-port", paramLabel = "<port>", description = "embedded Postgres port (when --database-url is unset) (FC_EMBEDDED_DB_PORT; default: ${DEFAULT-VALUE})")
    int embeddedDbPort;

    @Option(names = "--embedded-db-path", paramLabel = "<dir>", description = "embedded Postgres data directory (FC_EMBEDDED_DB_PATH; default: ${DEFAULT-VALUE})")
    String embeddedDbPath;

    @Option(names = "--yes", description = "confirm truncation (required)")
    boolean yes;

    private final DevEnv env;
    private final DevPaths paths;

    public FreshCommand() {
        this(DevEnv.system());
    }

    public FreshCommand(DevEnv env) {
        this.env = env;
        this.paths = DevPaths.resolve(env.vars());
        this.databaseUrl = env.str("FC_DATABASE_URL", "");
        this.embeddedDbPort = env.integer("FC_EMBEDDED_DB_PORT", EmbeddedPg.DEFAULT_PORT);
        this.embeddedDbPath = env.str("FC_EMBEDDED_DB_PATH", paths.defaultEmbeddedPath().toString());
    }

    @Override
    public Integer call() throws IOException, SQLException {
        if (!yes) throw new IllegalStateException("refusing to truncate without --yes");

        // A null resource is skipped by try-with-resources: no embedded Postgres
        // when the developer pointed us at an external database.
        try (EmbeddedPg pg = databaseUrl.isEmpty() ? startEmbedded() : null;
             HikariDataSource pool = Database.newPool(pg != null ? pg.url() : databaseUrl, 4)) {
            // Migrate first — against an empty embedded data dir the tables don't exist yet
            // and TRUNCATE would 42P01.
            DevBootstrap.migrate(pool);
            truncate(pool);
            LOG.info("FlowCatalyst tables truncated table_count={}", FRESH_TABLES.size());

            // Re-seed so the next sign-in works without restarting, with start's defaults.
            var dev = env.mutable();
            DevBootstrap.seedAdminDefaults(dev);
            DevBootstrap.seed(pool, dev.freeze());
            LOG.info("FlowCatalyst reseeded — sign in with the bootstrap admin email={}", DevBootstrap.DEV_ADMIN_EMAIL);
        }
        return 0;
    }

    private EmbeddedPg startEmbedded() throws IOException {
        Path dataPath = Path.of(embeddedDbPath);
        EmbeddedPg.assertCompatible(dataPath);
        EmbeddedPg pg = EmbeddedPg.start(dataPath, embeddedDbPort, paths.embeddedPgCacheDir());
        LOG.info("embedded postgres started for fresh port={} path={}", pg.port(), dataPath);
        return pg;
    }

    /// One `TRUNCATE … RESTART IDENTITY CASCADE` so FK ordering doesn't matter
    /// at the SQL level.
    static void truncate(DataSource pool) throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.execute(truncateSql());
        }
    }

    static String truncateSql() {
        return "TRUNCATE TABLE " + String.join(", ", FRESH_TABLES) + " RESTART IDENTITY CASCADE";
    }
}
