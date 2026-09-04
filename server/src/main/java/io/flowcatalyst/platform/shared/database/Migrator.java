package io.flowcatalyst.platform.shared.database;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/// Schema migrations for the platform database, run with Flyway at startup.
///
/// ## Baseline = the Go schema
///
/// `V1__baseline.sql` is the schema the Go service produces with its goose
/// migrations (`flowcatalyst-go/internal/migrate/sql/001..045`), captured
/// from `pg_dump --schema-only` and cleaned up. Two kinds of database reach
/// this class:
///
///   * **fresh** (empty schema): Flyway applies V1 and everything after it;
///   * **adopted from Go** (non-empty schema, no `flyway_schema_history`):
///     Flyway *baselines* the history table at version 1 without executing
///     V1 (`baselineOnMigrate` + `baselineVersion("1")`), then applies only
///     what comes after. The Go `goose_db_version` table is left exactly as
///     it is — Java never reads, writes, creates or drops it.
///
/// ## Rollback-to-Go rule
///
/// Go and Java never run at the same time, but rolling back to the Go service
/// must remain possible. Go runs against *this* schema (its goose table still
/// says "45 applied"), so until that rule is lifted every migration after V1
/// has to be **additive and ignorable by Go**: new tables, new nullable
/// columns with defaults, new indexes. Never rename, drop or retype anything
/// Go reads or writes, never touch `goose_db_version`, and keep a migration
/// transaction-safe on PostgreSQL (no `CONCURRENTLY`).
///
/// Only versioned migrations are used (`V<n>__<name>.sql`), in order,
/// validated against the history table on every start.
public final class Migrator {

    /// Flyway history table. Default name; spelled out so it is greppable.
    public static final String HISTORY_TABLE = "flyway_schema_history";

    /// Version the baseline migration carries; an adopted Go database is
    /// recorded at this version without running it.
    public static final String BASELINE_VERSION = "1";

    private Migrator() {
    }

    /// `true` inside a GraalVM native image, where Flyway's classpath
    /// scanner cannot enumerate `db/migration` (see [IndexedMigrations]).
    static final boolean NATIVE_IMAGE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    /// Builds the configured Flyway instance without running anything
    /// (for `info()` / diagnostics).
    public static Flyway flyway(DataSource dataSource) {
        return flyway(dataSource, NATIVE_IMAGE);
    }

    /// `indexed`: locate migrations through `db/migration.index` instead of
    /// scanning the classpath. What a native image needs; what the JVM
    /// path could also use, kept off there so scanning stays the reference.
    static Flyway flyway(DataSource dataSource, boolean indexed) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (indexed) {
            var index = IndexedMigrations.load();
            config = config.resourceProvider(index).javaMigrationClassProvider(index);
        }
        return config
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion(BASELINE_VERSION)
                .baselineDescription("Go schema (goose 001-045)")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
    }

    /// Migrates the database to the latest version. Safe to call on every
    /// start: a fully migrated database is a no-op; a Go database is adopted.
    public static MigrateResult migrate(DataSource dataSource) {
        return flyway(dataSource).migrate();
    }
}
