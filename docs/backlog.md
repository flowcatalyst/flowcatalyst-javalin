# Backlog

Decisions and follow-ups that came out of reviews and agent reports. Each
item names its origin; items marked **owner** need Andrew's call.

## Design smells to fix (from the shared-code audit, 2026-08-22)

- `Checks` is ~73 near-identical static one-liners over `Permissions`
  string constants. Replace with a `Permission` enum (code, resource,
  action) + `Checks.require(ac, Permission…)` / `requireAny(...)`; keep the
  `canXxx` names as thin delegates until every aggregate is ported, then
  delete them. Do after the eventtype audit lands (it uses `Checks`).
- `Optional` record components: `SigningKeys.previous`, `Server.fallback`.
  Use `List<PublicKeyEntry>` (0..1) and a sealed `Spa = Embedded | None`
  (or `Frontend` nullable-with-doc). Touches `Platform`, `Main`, `fcdev`.
- `Server` carries a nullable `pool` whose validity depends on
  `env.platformEnabled()`; a sealed `Mode = Platform(pool) | RouterOnly`
  makes the `IllegalStateException` in `buildApi` unrepresentable.
- `Metrics.port()` NPEs before `start()` — adopt the `Running` handle shape.
- `DomainEvent.eventType()` vs `EventMetadata.type()` — one datum, two names.
- `Lockfile.json()` returns the mutable root while `bytes()` clones.
- JFR events: `UnitOfWork.transact` (operation, command, outcome,
  duration), `PlatformSink.writeEvent`, `Authenticator` rejections,
  `Migrator.migrate`, `Server.Running` start/stop — plus the router's
  semantic points once the router exists.

## Server API the fcdev module wished existed (fcdev agent)

- `Server.Running.stop()` must stop and drain subsystems once they exist.
- `Seeder.run()` returning a summary (counts, admin created) so
  `fcdev fresh` can print Go's summary.
- A shared `EnvKeys` constants class for the `FC_*` names (fcdev and
  `Env` both use string literals).
- `Frontend`: expose *why* the SPA is missing; `Version.override(String)`
  so fcdev's `/health` reports `0.8.23`, not the server's version.

## Port work queued

- Platform aggregates (36 remaining, wire order in `Platform.register`):
  client, role, application, principal, portalusers, resetapproval,
  serviceaccount, auth/OAuth clients, OAuth provider endpoints, OIDC bridge
  + portal auth, cors, connection, subscription, dispatchpool, sdksync,
  event, audit, docs, dispatchjob, identityprovider, emaildomain,
  loginattempt, platformconfig, process, scheduledjob, webauthn, bff/*,
  me, clientselection, sdk batch endpoints; public routes (login/logout,
  publicapi, password reset, /oauth/authorize, /api/dispatch/process).
- DB-backed `ClaimsResolver` (session cookie path) + role → permission
  flattening.
- CORS filter driven by the allowlist (owner decision: implement).
- Pagination → one standard envelope (**owner**: wire change; lockfile bump
  + SDK/frontend regen).
- Data plane: router (spec in progress → review → implement), stream,
  outbox, dispatch scheduler, scheduled-job scheduler, queue backends,
  standby, ALB, MCP, purger. Each: spec → implement → audit.
- `fcdev`: `init`, `mcp` (+ credential bootstrap in `start`), `outbox`,
  `upgrade` are stubs.
- AWS Secrets Manager DB mode + credential rotation in `Main`.
- `--enable-preview` for the server module when the router's
  `StructuredTaskScope` lands (keep usage localised).

## Owner questions collected from specs

Each spec's "load-bearing or accident?" list, summarised; the full wording is
in the spec.

**eventtype** (`docs/spec/eventtype.md` §10): `clientId` carried on the
aggregate/command/event but never persisted ⇒ CLIENT-scoped principals can
never update/delete (post-create writes are effectively anchor-only);
`?clientId=` list filter suppresses the `CURRENT` default while filtering
nothing; create gated by *any* write permission; sync ignores `schema` /
`createdBy`; `/schemas` and `/versions` are aliases of one handler; lenient
enum reads default silently; `DELETE` summary says "Archive".

**fcdev** (`docs/spec/fcdev.md`): PID-file write failure is a warning;
port `0` = free port is a Java addition; 168 MB fat jar (bundled PG) vs
resolving only the host binary at JBang install (~50 MB); stubs exit 2 like
usage errors; `--embedded-db-reset` deletes backups too; `--database-url`
with `--embedded-db=true` silently skips embedded PG; `fresh` does not
truncate `msg_processes` / `tnt_email_domain_mappings` (and lists two
duplicates); `stop` 150 ms poll / 5 s post-SIGKILL wait are not flags.

**seeder / password-hash** (`docs/spec/seeder.md`, `docs/spec/password-hash.md`):
event-type names overwritten on every start (catalogue sync or accident?);
bootstrap admin only when no anchor user exists; see the spec for the rest.

**router** (`docs/spec/router.md`): pending — the spec is being written;
its open-questions section is the review gate before any router Java.
