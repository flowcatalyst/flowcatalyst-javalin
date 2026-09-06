# native-config — GraalVM reachability metadata for fcdev (2026-09-06)

`reachability-metadata.json` was captured by running the fcdev **shaded jar**
(not the native binary — the tracing agent only runs on a JVM) under the
native-image tracing agent against a fresh `--embedded-db-path`, with
`fcdev/pom.xml`'s `native` profile wired to load it via
`-H:ConfigurationFileDirectories` **alongside** `server/native-config`
(both directories, comma-separated — fcdev embeds the whole server, so it
needs everything that profile's reachability metadata covers too):

    JAVA_HOME=$(mise where java@oracle-graalvm-25.0.4.1)
    $JAVA_HOME/bin/java --enable-preview \
      -agentlib:native-image-agent=config-merge-dir=fcdev/native-config \
      -jar fcdev/target/flowcatalyst-fcdev-0.0.1-SNAPSHOT.jar start \
      --api-port <port> --metrics-port <port> \
      --embedded-db-port <port> --embedded-db-path <fresh dir> \
      --pid-file <scratch>/fcdev.pid

...then, against that running instance: `GET /health`, `GET /index.html`
(the embedded SPA), `POST /auth/login` (the seeded dev admin,
`admin@flowcatalyst.local` / `DevPassword123!`), `POST /api/event-types`
with the login session cookie, and a graceful stop (`SIGTERM` to the agent
JVM — the same shutdown path `fcdev stop` drives: Javalin, the router, the
stream projectors, HikariCP, then `EmbeddedPostgres`'s own `pg_ctl stop`).
`fcdev init` was run separately, as a **plain** JVM call (no agent) against
the same running database — it never touches zonky (no embedded-Postgres
code on its path; it just connects over JDBC), and the tracing agent's
`config-merge-dir` can only be held by one writer at a time, so a second
concurrent agent process against the still-running `start` would fail to
attach. Two passes were merged into the file here.

This covers what's specific to the **dev monolith in production** — the
server module's own tests never exercise these, because zonky's
`embedded-postgres` is server-side **test scope only** (`server/pom.xml`;
production `fc-server` never embeds a database) — plus the wider route
surface (login, event-types) fcdev's own verification walked that the
server's capture run didn't happen to cover. A diff against
`server/native-config/reachability-metadata.json` after this capture showed
62 reflection entries unique to this file: `com.mysql.cj.jdbc.*` (the
outbox `create-table` JDBC driver), `io.zonky.test.db.postgres.embedded.*`
resource globs (`postgres-darwin-*.txz`, `org/postgresql/driverconfig.properties`
— zonky's own resource lookups, not reflection: see "resources" in the
JSON), every fcdev `@Command` class (belt-and-suspenders alongside
picocli-codegen's build-time output, `META-INF/native-image/picocli-generated/fcdev/`
under `fcdev/target/classes`, which the tracing agent does not replace —
`NativeReflectConfig` at build time already registers our own records/enums,
this file is for everything runtime-reflective that neither of those covers),
RSA key interfaces (JWT signing), and jOOQ record types the server's own
capture route never touched (`MsgScheduledJobsRecord`,
`OauthIdentityProvidersRecord`, …).

**Not captured**: a genuinely first-time `MavenCentralPgBinaryResolver`
download-and-extract (this machine's `~/Library/Caches/flowcatalyst/embedded-pg`
was already warm from earlier work, and `DevPaths` does not let
`--embedded-db-path`/env override the cache directory on macOS — only the
data directory). If a real first install ever throws "not registered for
reflection" during the download/verify/extract step, re-run the agent after
clearing that cache directory, merge, rebuild.

Re-capture (merge) after exercising any code path that fails in the fcdev
image with a "not registered" error, exactly as `server/native-config`
documents.
