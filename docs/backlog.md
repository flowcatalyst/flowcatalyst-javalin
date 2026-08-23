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

## From the platformconfig audit
- GLOBAL config-coordinate uniqueness is not DB-enforced (NULLs-distinct
  unique index) → concurrent first sets race; fix = `NULLS NOT DISTINCT`
  index (schema change, **owner**; spec OQ 10). Audit row stores SECRET
  values in clear (spec OQ 4, **owner**).

## From the subscription port
- `DispatchMode` (IMMEDIATE / NEXT_ON_ERROR / BLOCK_ON_ERROR) currently lives in
  `io.flowcatalyst.platform.subscription`; the router (and the dispatch
  scheduler that publishes `Message.dispatchMode`) need the same enum — give
  it a shared home (`io.flowcatalyst.platform.shared.messaging`?) when the
  router lands; see router spec Q1 ruling for the semantics.

## From the audit-log audit
- ~~Query-parameter parse errors (`{message, location: "query.<name>", value}`)
  are built in both `PageQuery.intParam` and `AuditLogApi.pageSize` —
  expose one helper in `apicommon` ("Query-parameter parse errors are one
  helper").~~ **Done (2026-08-23):** `apicommon.QueryParams.intParam(ctx,
  name) → OptionalInt` (throwing form) / `intParam(ctx, name, errors)`
  (accumulating form) + `QueryParams.validation(errors)`; `PageQuery.from`,
  `AuditLogApi.pageSize` and `LoginAttemptApi.pageSize` use it —
  `QueryParamsTest` / `PageQueryTest` / both Api tests pin the bytes.
  Still open: `AuditLogRepository.ListFilter` has unused components
  (`clientId/since/until/offset`, spec OQ 3). `AuditLog.operationJson` is a
  mutable `JsonNode` in a record.

## From the emaildomainmapping port
- `EmailDomainMappingRepository.resetOidcUsersToInternal` writes
  `iam_principals`/`iam_principal_roles` (move-provider to INTERNAL) — move
  into the principal aggregate when it lands. Temporary IDP read lookups
  there too. Seeded schema catalogue names `platform:admin:edm:*` while the
  emitted type is `platform:admin:email-domain-mapping:*` (**owner**).
  `/lookup` is ungated (spec OQ 1, **owner**). `EmailDomain.parse` accepts
  `.`, `example.`, `.com`, `exa_mple.com` (pinned, **owner**).

## From the dispatchjob port (**owner**)
- Router Q1 mapping: *resend* = `POST /api/dispatch-jobs/requeue`; *ignore*
  (FAILED→CANCELLED) and *completed* (FAILED→COMPLETED) have NO routes in the
  lockfile today — two new operations + lockfile routes needed (wire change:
  bump lockfile, regen SDKs/frontend). `requeue()` is total (resets
  PROCESSING/QUEUED/COMPLETED too) and gated by *view* — precondition/gate
  rulings in `docs/spec/dispatchjob.md` §11.

## From the dispatchjob audit
- Two sibling sealed visibility types (`EventRepository.Visibility`,
  `DispatchJobRepository.AccessScope`) with the same anchor/clients builder
  duplicated in two Apis → one shared type (`AuthContext.visibility()` in
  `shared/auth`) and one SQL predicate. `RequeueCommand` with `[null]` ids →
  500 not 400 (**owner**: unknown id vs 400). `filter-options` facets not
  tenant-scoped (spec OQ 7, **owner**).

## From the encryption port
- `fcdev` `DevBootstrap.ensureAppKeyFile` should call
  `Encryption.generateKey()` (bytes identical today; one source of truth).
- Owner questions in `docs/spec/encryption.md` ([owner?] tags): fatal vs
  silent on a malformed key, whitespace stripping, v0-nonce-starting-0x01
  fallback, closed external-scheme list, `encrypted:<non-base64>` rejection,
  `literal:` on decrypt, `needsReEncryption` on junk, `reEncrypt` shape.

## From the identityprovider audit
- `identityprovider/operations/DomainRouting.moveTo` restates the mapping
  aggregate's "move a mapping to a provider" rule (re-point + emit
  `provider-changed` + reset OIDC users when the target authenticates with
  passwords) that `MoveEmailDomainMappingProvider` also spells out. Make it
  one public helper in `emaildomainmapping.operations` — e.g.
  `MoveMapping.to(scoped, repo, mapping, targetId, targetIsInternal, ec, auditCommand) → int usersReset`
  — called by both (the mapping aggregate owns the rule; the IdP aggregate
  only chooses the target). Do it when the mapping package is next touched.
- `identityprovider/api/ClientSecretEncryption` (`Enabled | Disabled`,
  `atRest(incoming)`) is the "disabled ⇒ reject `Plain`" policy
  `docs/spec/encryption.md` §5 describes, bound to this aggregate only by its
  two messages (`oidcClientSecretRef: …`, `cannot store OIDC client secret:
  …`). When a second secret-bearing aggregate lands (OAuth client secrets,
  webhook signing keys, TOTP), move it to `shared.encryption` as
  `SecretRefPolicy` with a `what` parameter for the message and
  `UseCaseException.validation` instead of the Api's `HttpError.badRequest`
  spelling; delete the per-aggregate copy.

## From the loginattempt audit
- ~~`AuditLogCursor` duplicates the new shared `apicommon.KeysetCursor`
  (`(at, id)`, same token layout; `parse` → `Optional`, the 400 `CURSOR`
  policy belongs at `AuditLogApi.after`). Migration is mechanical but
  touches `AuditLog.cursor()`, `AuditLogRepository.findWithCursor`,
  `AuditLogApi.after` and ~45 lines of `AuditLogTest` cursor tests (the
  malformed table moves to `KeysetCursorTest`, which already carries it;
  `AuditLogApiTest` already pins the 400) — do it in one pass, then delete
  `AuditLogCursor`.~~ **Done (2026-08-23):** `AuditLog.cursor()` returns
  `KeysetCursor`, `AuditLogRepository.findWithCursor(…, KeysetCursor, …)`,
  `AuditLogApi.after` applies `parse(token).orElseThrow(CURSOR)`;
  `AuditLogCursor` deleted; the audit token bytes are unchanged
  (`KeysetCursorTest` pins the pre-migration tokens and the `aud_` rows of
  the lenient / malformed tables).
- ~~`pageSize` parsing (`absent → 50`, out of range → 50, non-integer →
  `VALIDATION` `{message: "invalid integer", location: "query.pageSize",
  value}`) and `queryParam` are copied verbatim between `AuditLogApi` and
  `LoginAttemptApi`; with the `PageQuery.intParam` duplicate already listed
  under the audit-log audit that is three copies — one `apicommon` helper
  (`QueryParams.intOrDefault(ctx, name, default, max)` + the error shape).~~
  **Done (2026-08-23):** the parse + error shape is
  `apicommon.QueryParams.intParam` (see the audit-log audit entry); the
  `absent / out of range → 50` resolution stays a two-line policy in each
  Api's `pageSize` (it is the route's default, not transport). The one-line
  `queryParam` (absent/empty → `null`) is still duplicated — not worth a
  helper.
- `LoginAttempt.attempt(type, outcome, failureReason, identifier,
  principalId, ipAddress, userAgent)` is seven positional arguments, five of
  them `String` — an easy swap at the auth-port call sites. Consider two
  intent-named factories (`success(type, identifier, principalId, ip, ua)` /
  `failure(type, identifier, reason, principalId, ip, ua)`) or a small
  `Details` record when the first caller lands.

## From the docs audit
- `SyncAppDocs` is a `TxOperation` whose execute phase is one raw
  `dbTx()` write (`AppDocRepository.replaceForApplication`) — no domain
  event, no `aud_logs` row (spec §5, open question 5, kept as Go). Every
  other sync (`SyncEventTypes`, roles…) emits a `<X>Synced` rollup; if the
  owner wants the docs sync in the audit trail, add `AppDocsSynced` to a new
  `operations/AppDocEvents.java` and emit it via `scoped.emitEvent` — the
  repository call stays as is.
- `AppDocRepository.replaceForApplication` reads the "existing" snapshot on
  the pooled `DSLContext` while the caller's transaction is open (the same
  shape as every `Operation` execute phase, which reads before the `Plan`'s
  transaction). Two concurrent syncs of one application therefore race
  last-writer-wins at row level (ON CONFLICT serialises the upserts; the
  unlisted-slug delete can remove a row the other sync just inserted).
  Harmless for a single SDK per application; a `SELECT … FOR UPDATE` on the
  application's rows through `txDsl` would serialise them if it ever matters.
- `PublishedDocs.listMarkdown` enumerates a classpath *directory*, which only
  works where the class loader hands out a `file:` or `jar:` URL with a
  directory entry (maven-jar-plugin does; a native image or an exotic loader
  would serve an empty platform index, by design). A committed
  `docs/published/index` manifest (one filename per line) would make the
  corpus loader-independent at the cost of a second place to list pages.

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
