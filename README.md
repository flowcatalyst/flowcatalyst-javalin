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
