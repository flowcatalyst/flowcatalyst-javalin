# Backlog

Decisions and follow-ups that came out of reviews and agent reports. Each
item names its origin; items marked **owner** need Andrew's call.

## Design smells to fix (from the shared-code audit, 2026-08-22)

- ~~`Checks` is ~73 near-identical static one-liners over `Permissions`
  string constants.~~ **Done (2026-08-22):** `Permission` enum (code /
  context / resource / action, `parse`, the wildcard matcher) +
  `Checks.require(ac, Permission)` / `requireAny(ac, Permission…)`; the
  `can*` one-liners and `shared.auth.Permissions` are deleted. The seeder's
  `seed.Permissions` stays the seeding catalogue; `PermissionTest` pins the
  two sets equal.
- ~~`Optional` record components: `SigningKeys.previous`, `Server.fallback`.~~
  Done (owner: sealed types): `SigningKeys.KeyRotation = Single(current) |
  Rotating(current, previous)` with `verificationKeys()`; `Server.Spa =
  Embedded(frontend) | None` via `Frontend.embeddedOrNone()`.
- ~~`Server` carries a nullable `pool` whose validity depends on
  `env.platformEnabled()`~~ Done: `Server.Mode = Platform(pool) |
  Worker(pool) | RouterOnly` (`Worker` = DB-backed subsystems without the
  platform API, the shape `Main`'s `needsDb` already produced); the record
  refuses an `Env` whose `FC_PLATFORM_ENABLED` disagrees with the mode.
- ~~`Metrics.port()` NPEs before `start()`~~ Done: `Metrics.start()` returns
  a `Metrics.Running` (`port()`, `stop()`).
- `DomainEvent.eventType()` vs `EventMetadata.type()` — one datum, two names.
- `Lockfile.json()` returns the mutable root while `bytes()` clones.
- JFR events: `UnitOfWork.transact` (operation, command, outcome,
  duration), `PlatformSink.writeEvent`, `Authenticator` rejections,
  `Migrator.migrate`, `Server.Running` start/stop — plus the router's
  semantic points once the router exists.

## From the role audit
- Type-name clash: `io.flowcatalyst.platform.role.Permission` (catalogue
  entry) vs `io.flowcatalyst.platform.shared.auth.Permission` (enum) —
  rename the catalogue record (`PermissionDefinition`) when the BFF
  "define permission" endpoint is ported.
- `SyncPlatformRoles` N+1 `findByName`; `RoleRepository.persist` inserts
  role-permission rows one at a time (batch); `Role.withPermissions` stamps
  `updatedAt` while other copies don't; `DELETE /api/roles/permissions/{permission}`
  is the one write on the surface outside `Operation.run` (spec OQ 4);
  `seed.RoleDefinition.source` is a `String` not `RoleSource`.

## From the application audit
- `ApplicationRepository.clientExists` / `servicePrincipalIdFor` are
  temporary cross-ownership reads (tnt_clients / iam_principals) — replace
  with `ClientRepository.findById(..).isPresent()` and a principal read once
  principal lands. `ProvisionServiceAccount` / `provision-login-client`
  not ported until serviceaccount + principal + OAuth client exist.

## From the client audit
- Two "access to this client" gates with different wire codes:
  `ClientApi.requireAnchorOrClientAccess` → `FORBIDDEN` (spec-pinned) vs
  `Checks.requireClientAccess` → `SCOPE_FORBIDDEN` — owner ruling when a
  second surface needs it. `deactivate` parses a body only to validate it
  (OQ 2). `ClientNote` is domain + JSONB + wire shape at once (tripwire test
  added).

## From the cors audit
- Origin host case-sensitivity vs browsers' lower-cased `Origin` header
  (spec OQ 5); `*` admitted anywhere in the host — wildcard semantics should
  become a pinned `Origin.matches(header)` on the aggregate when the CORS
  filter is specced (owner decision: implement the filter).
- Envelope: `validate` cannot hand its parsed value to `execute` (forces a
  re-parse in every create) — consider a `Validated<C>` return later.

## From the subscription port
- `DispatchMode` (IMMEDIATE / NEXT_ON_ERROR / BLOCK_ON_ERROR) currently lives in
  `io.flowcatalyst.platform.subscription`; the router (and the dispatch
  scheduler that publishes `Message.dispatchMode`) need the same enum — give
  it a shared home (`io.flowcatalyst.platform.shared.messaging`?) when the
  router lands; see router spec Q1 ruling for the semantics.

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

**router** (`docs/spec/router.md` §13): 50 questions; **Q1 ruled** (NEXT_ON_ERROR continues past a failed head; BLOCK_ON_ERROR ACKs the queued siblings and leaves the group pending platform-side until the error clears — deliberate deviation from Go). Sub-question open on Q1: failed head retried independently vs failed immediately (ties to Q2). Remaining 49 pending.
