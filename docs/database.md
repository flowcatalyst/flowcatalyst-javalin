# Database: schema, migrations, jOOQ

The Java server is a drop-in replacement for `flowcatalyst-go` and shares its
PostgreSQL database. Go manages the schema with goose
(`internal/migrate/sql/001..045`); Java manages it with **Flyway, starting from
a DDL baseline of the Go schema**.

## Ground rules

* **Identical schema.** `V1__baseline.sql` *is* the Go schema — a cleaned-up
  `pg_dump --schema-only` of a database migrated by Go. The fingerprint test
  (below) keeps it that way.
* **Rollback to Go must stay possible.** Go and Java never run at the same
  time, but the Go binary must be able to start against a database Java has
  migrated. Therefore:
  * `goose_db_version` is never created, read, written or dropped by Java. On
    an adopted Go database it stays exactly as Go left it.
  * every migration after V1 must be **additive and ignorable by Go** (new
    tables, new nullable/defaulted columns, new indexes). Never rename, drop
    or retype anything Go reads or writes.
  * migrations must be transaction-safe on PostgreSQL (no `CONCURRENTLY`).

## Files

| What | Where |
| --- | --- |
| Migrations | `server/src/main/resources/db/migration/V<n>__<name>.sql` |
| Runner | `io.flowcatalyst.platform.shared.database.Migrator` |
| Pool factory | `io.flowcatalyst.platform.shared.database.Database` (accepts Go's `postgresql://user:pass@host:port/db?sslmode=…` URL or a JDBC URL) |
| Generated jOOQ code | `server/src/main/java/io/flowcatalyst/db/generated` (committed, like Go commits sqlc output) |
| Test fixture (embedded PG) | `server/src/test/java/io/flowcatalyst/testpg/TestPg.java` |
| Captured Go schema | `server/src/test/resources/db/go-schema.sql` (+ `go-schema-fingerprint.txt`) |
| Baseline generator | `tools/make-baseline.py` (pg_dump of a Go DB → `V1__baseline.sql`; only needed if the baseline is ever re-captured) |

## How `Migrator` behaves

`Migrator.migrate(dataSource)` runs Flyway with `locations=classpath:db/migration`,
`baselineOnMigrate=true`, `baselineVersion=1`, `validateOnMigrate=true`,
`outOfOrder=false`, history table `flyway_schema_history`.

* **Fresh database** → V1 (and anything after) is applied.
* **Database created by Go** (non-empty, no history table) → Flyway records a
  `BASELINE` row at version 1 *without executing V1*, then applies only
  migrations > 1. `goose_db_version` is untouched.
* **Already migrated** → no-op (validated).

## Partitions

Seven tables are monthly `RANGE (created_at)` partitions, exactly as in Go
migrations 019/022: `msg_events`, `msg_events_read`, `msg_dispatch_jobs`,
`msg_dispatch_jobs_read`, `msg_dispatch_job_attempts`,
`msg_scheduled_job_instances`, `msg_scheduled_job_instance_logs`.

The baseline does not contain dated partition tables. Its last statement is a
`DO $$ … $$` block (ported from 019/022) that creates `<parent>_YYYY_MM`
partitions for (this month − 1) … (this month + 3) relative to `now()`.
Forward-rolling and retention are a runtime concern (Go:
`internal/stream/partition_manager.go`; the Java port does the same).

## Tests

```sh
export JAVA_HOME=$(mise where graalvm)
mvn -q -B -pl server -Dtest='Migrator*,*Fingerprint*,*Adoption*' -Dsurefire.failIfNoSpecifiedTests=false test
```

* `MigratorTest` — fresh embedded PG → V1 applied, second run is a no-op, the
  seven parents exist with ≥ 5 partitions and valid indexes, no goose table.
* `SchemaFingerprintTest` — Java-migrated schema vs the committed Go
  fingerprint (tables, columns, constraints, indexes incl. definitions and
  validity, sequences; dated partitions and the goose/flyway tables ignored).
* `GoAdoptionTest` — load `go-schema.sql` + goose rows, run `Migrator` →
  baselined at 1, zero migrations executed, goose rows intact.

`TestPg` starts ONE zonky embedded PostgreSQL 18 per JVM and migrates it once.
As in Go's `testpg`, there is **no truncation between tests** — seed your own
rows under fresh ids and assert on that subset.

Regenerate the fingerprint fixture after re-capturing `go-schema.sql` from a
freshly migrated Go database:

```sh
mvn -pl server -Dtest=SchemaFingerprintTest -Dfc.regenerateFingerprint=true test
```

## jOOQ code generation

Generated against the migrated schema (embedded PG + `Migrator`), tables and
records only (no POJOs/DAOs), package `io.flowcatalyst.db.generated`,
`jsonb → org.jooq.JSONB`, `timestamptz → java.time.OffsetDateTime`. Excluded:
`flyway_schema_history`, `goose_db_version` and the dated partitions
(`.*_\d{4}_\d{2}`). The `*_id_seq` sequences backing `serial` columns are not
generated (jOOQ's default `includeSystemSequences=false`), hence no
`Sequences.java`.

```sh
# regenerate (writes into server/src/main/java, commit the result)
mvn -pl server -Pjooq-codegen process-test-classes

# CI check: regenerate into a temp dir and diff against the committed code
tools/jooq-verify.sh
```

The generator is `server/src/test/java/io/flowcatalyst/tools/JooqCodegen.java`;
the Maven profile `jooq-codegen` in `server/pom.xml` adds `org.jooq:jooq-codegen`
to the exec classpath only (it is not a project dependency).

## Adding a migration

1. `server/src/main/resources/db/migration/V<n>__<snake_name>.sql`, additive
   only (see ground rules).
2. `mvn -pl server -Pjooq-codegen process-test-classes` and commit the
   regenerated code.
3. Run the migration tests; `tools/jooq-verify.sh` in CI guards drift.
