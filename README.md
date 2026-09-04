# FlowCatalyst (Java)

A Java 25 drop-in replacement for the FlowCatalyst platform in
[`../flowcatalyst-go`](../flowcatalyst-go): same Postgres schema, same
`openapi.lock.json`, same env vars, ports, signatures, ids and SDKs — built
Revolut-style: plain constructors, records and sealed types, jOOQ, virtual
threads, no DI container, no annotation magic.

| Module | Artifact | What |
|---|---|---|
| `usecase/` | `io.flowcatalyst:flowcatalyst-usecase` | The use-case envelope (`Operation` → `Plan` → `UnitOfWork`), `DomainEvent`, `UseCaseError`, TSID, the consumer-app `OutboxSink`. Shared by the platform and by consumer apps. |
| `sdk/` | `io.flowcatalyst:flowcatalyst-sdk` | The Java client SDK (copied from `flowcatalyst-go/clients/java-sdk`), on top of `usecase`. |
| `server/` | `io.flowcatalyst:flowcatalyst-server` | `fc-server`: platform API, router, stream, schedulers, outbox, MCP — toggled by `FC_*_ENABLED`. |

## Build

Java 25 is required (`JAVA_HOME=$(mise where graalvm)` locally).

```sh
mvn -q test                         # everything (embedded Postgres downloads on first run)
mvn -q -pl server test              # one module
mvn -pl server -Pjooq-codegen process-test-classes   # regenerate jOOQ code after a migration
tools/jooq-verify.sh                # CI drift check for the generated code
```

## Run

Two deliverables come out of the same tree, the same split as the Go repo:

| | What | How |
|---|---|---|
| `fc-server` | The production server. The platform API is on by default; every other subsystem is **off** until its `FC_*_ENABLED` is set, and the router only runs the built-in Postgres broker when `FC_DEFAULT_BROKER=postgres`; otherwise it takes its queues from `FLOWCATALYST_CONFIG_URL`. | `mvn -q -DskipTests -pl server -am package` → `java --enable-preview -jar server/target/flowcatalyst-server-0.0.1-SNAPSHOT-exec.jar` |
| `fcdev` | The developer monolith: fc-server plus embedded Postgres, dev defaults, and the `start\|stop\|fresh\|db upgrade` lifecycle. See [`docs/fcdev.md`](docs/fcdev.md). | `mvn -q -DskipTests package` → `java --enable-preview -jar fcdev/target/flowcatalyst-fcdev-0.0.1-SNAPSHOT.jar start` |

Both are plain executable jars; JBang is optional for `fcdev`. The
[`Dockerfile`](Dockerfile) builds the fc-server image (same ports and health
check as the Go one).

fc-server can also be a native binary (GraalVM, opt-in profile, see
`docs/STATUS.md` for what it took):

```sh
mise install java@oracle-graalvm-25.0.4.1
JAVA_HOME=$(mise where java@oracle-graalvm-25.0.4.1) mvn -q -DskipTests -pl server -am -Pnative package
server/target/fc-server
```

A new SQL migration must also be listed in `server/src/main/resources/db/migration.index`;
`IndexedMigrationsTest` fails until it is.

## Docs

- [`docs/usecase-envelope.md`](docs/usecase-envelope.md) — the intent of the use-case / audit machinery and the Go → Java mapping, with a worked example.
- [`docs/database.md`](docs/database.md) — Flyway baseline of the Go schema, schema-fingerprint tests, jOOQ codegen.
- The Go repo's `CONVENTIONS.md` still applies (one operation per file, one events file per aggregate, no SQL outside repositories, no business logic in api/).

## Status

Foundations in place: envelope, Flyway baseline + Go-adoption test, jOOQ
codegen, Env/config, logging, signing keys, JWT authenticator, error
envelope, apicommon, JSON (microsecond timestamps), spec routes, SPA
fallback, listeners (`/health`, `/ready`, `/metrics`). Aggregates are being
ported one at a time from `internal/platform/<aggregate>` — see
`io.flowcatalyst.server.Platform` for the wiring order and
`LockfileCoverageTest` for how much of the contract is implemented.
