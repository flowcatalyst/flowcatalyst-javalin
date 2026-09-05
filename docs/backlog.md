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
  principal lands.
- ~~`ProvisionServiceAccount` / `provision-login-client` not ported until
  serviceaccount + principal + OAuth client exist~~ **Done (2026-09-05,
  application-provisioning brief):** both routes land; `LockfileCoverageTest`
  reports 245/245, `REQUIRED_COVERAGE = 1.0`. **Deliberate deviation from
  Go, recorded per the brief:** `provision-login-client`'s `allowedOrigins`
  field is declared on `ProvisionLoginClientRequest` in the lockfile but Go
  never reads it (a Go defect — the field is dead on that side); the Java
  handler stores it on the created OAuth client via `CreateOAuthClient`'s
  existing `allowedOrigins` support. Also widened visibility, outside this
  unit's originally-listed files: `serviceaccount.operations.WebhookSecrets`
  and `oauthclient.operations.Secrets` (both were package-private; their
  static mint/encrypt methods are now `public` so `ProvisionServiceAccount`,
  in `application.operations`, can call them — no behaviour changed).

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
- ~~Two sibling sealed visibility types (`EventRepository.Visibility`,
  `DispatchJobRepository.AccessScope`) with the same anchor/clients builder
  duplicated in two Apis → one shared type (`AuthContext.visibility()` in
  `shared/auth`) and one SQL predicate.~~ Done: `shared/auth/Visibility`
  (`Everything | Tenants`), `AuthContext.visibility()`,
  `shared/database/VisibilitySql.toCondition`. `RequeueCommand` with `[null]` ids →
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

## Go HEAD defects found adopting migrations 046–052 (2026-09-05, **owner**)

- **Go's seeder cannot seed a fresh database at HEAD.**
  `internal/platform/seed/event_types.go:159` inserts `schema_type = 'JSON'`;
  migration 051's `chk_msg_event_type_spec_versions_schema_type` allows only
  `JSON_SCHEMA | XSD | XML_SCHEMA | PROTO | PROTOBUF`, so `fcdev start` on an
  empty database fails its own seed (reproduced live). Java's `Seeder` had
  the same literal and now writes `SchemaType.JSON_SCHEMA` — a **deliberate
  deviation** (correctness over conformance); `go-seed-expected.tsv` updated
  for the 72 schema rows. Go needs the one-word fix.
- ~~`iam_login_attempts` partitions are created once, at migration 049~~
  **Corrected 2026-09-05:** Go's always-on **purger** (`StartPurger`, every
  minute) calls `EnsureQuarterlyPartition(now)` and `(now + 3 months)` and
  drops quarters older than three years — the stream `PartitionManager`
  deliberately excludes this table. The Java purger is Phase 2 work
  (`docs/spec/scheduled-job-scheduler.md` §4); until it lands, a Java-only
  deployment stops getting new quarters after the ones migration 049
  pre-created. No owner question after all.

- **Event deduplication never fires across requests.** `msg_events`'
  unique index is `(deduplication_id, created_at)` (partition key) and
  `created_at` is stamped per insert, so an SDK replay with the same
  `deduplicationId` lands a second event. `docs/spec/sdk-ingest.md` §5 D6.
- **Dispatch-job ingest is unusable by non-anchors.**
  `internal/platform/shared/sdk/{dispatch_jobs_batch,dispatch_job_create}.go`
  gate on `CanWritePermission(ac, "WRITE_DISPATCH_JOBS")` — a permission
  string no seeded role grants (the catalogue has
  `platform:messaging:batch:dispatch-jobs-write`) and `requirePermission`
  has no aliases, so every non-anchor SDK service account gets 403
  `PERMISSION_REQUIRED`. Java (`docs/spec/sdk-ingest.md` §5 D1) checks the
  seeded permission — deliberate deviation. One-line Go fix.

- **X-06 at the wire (owner yes/no).** The Java sweep kept the pre-existing
  lenient WIRE parse under `parseWire` for role `/by-source/{source}`
  (unknown → DATABASE), `CreateIdentityProvider` type, platform-config
  `SetProperty` value type, and `UpdateConnection` status — because the
  specs record those as open questions. Go's `6cbe708` rejects unknown wire
  values with 400 in the same places. Reject at the wire too?

- **serviceaccount, when the `auth` aggregate lands (2 items):** (1)
  `CreateServiceAccountWithCredentials` must mint a real OAuth client for
  the account and return its secret once; until then the response carries
  `oauth.clientId = oauth.clientSecret = "unavailable:auth-not-ported"`
  (`ServiceAccountApi.OAUTH_UNAVAILABLE`). (2) The token mint writes **no
  audit row** ("who obtained a credential for which account", spec §8 step
  8): `AuditLogRepository` is write-only through the unit of work and the
  mint emits no domain event. Either give the mint an event (spec §6 has
  none) or an audit-only plan. Security-relevant gap; owner to rule.

## Go changes to mirror the Batch A auth rulings (2026-09-05, **owner is fixing Go too**)

Rulings that change behaviour relative to Go HEAD, with the Go site. `docs/auth-rulings.md` "Rulings — Batch A" has the full wording.

| Ruling | Go change | Go site |
|---|---|---|
| C-Q19 | HS256 fallback secret must be ≥ 32 bytes; refuse to start otherwise | `internal/platform/auth/authservice/authservice.go` (`SecretKey`, ~L182; the "non-empty" check near L330) |
| C-Q20 | Empty `GrantTypes` ⇒ **no** grant allowed (was: every grant). Data: set grants on existing rows; `fcdev init` and any seeder write them explicitly | `internal/platform/auth/oauthapi/token.go:880` (`len(client.GrantTypes) == 0` branch) |
| C-Q22 | `/oauth/authorize`: `state` > 116 chars → redirect `invalid_request` before any write (was: insert fails → `server_error`) | `oauthapi/authorize.go`, before the `PendingAuth:` payload write |
| C-Q23 | Backoff store error ⇒ **deny** the login (503), not proceed; rate-limit store stays fail-open | `internal/platform/auth/login/endpoint.go:401` (`err == nil && !d.Allowed` — an error currently falls through to allow) |
| C-Q24 | `/auth/me` `status` populated with the principal's real status (was always `""`) | `login/endpoint.go:347` |
| C-Q26 | Introspection `client_id` = the minting OAuth client (RFC 7662), not `claims.Clients[0]` | `oauthapi/introspect_revoke.go:94-95` |
| C-Q27 | `/oauth/authorize` per-client 429 in the RFC 6749 error shape (was the platform envelope); SDK/SPA parsing to follow | `oauthapi/authorize.go:61` (`ratelimit.WriteTooManyRequests`) |
| I-Q5 | Session-mint failure after a correct password → the `ErrorModel` 500 envelope, fixed message, cause logged (was plain-text 500); same as A-18 | identity `SessionWriter` (auth-identity.md §4.6) |
| I-Q21 | `GET /auth/2fa/trusted-devices` items on the platform `Time` shape, `principalId` dropped | `internal/platform/mfa/entity.go:44,69,100` (JSON tags) |
| I-Q11 | Remember-device: only for internally managed identities, structurally absent for external-IdP domains, default off, on by explicit domain policy, audit rows for the policy change and each enrolment/revocation; a store error never enables it | `login/twofactor.go:89` (`rememberAllowed := mapping != nil && mapping.RememberDeviceEnabled`) + the domain-mapping default for `RememberDeviceEnabled` |
| A-19 (found 2026-09-05) | **Go's enforced lock is anchored to the oldest failure of the ceiling set**, not the last failure: `loginbackoff.Check` computes both `lockEnds` and `countEnds` from `GlobalCeilingTrippedAt` (the ceiling-th most recent failure). `docs/spec/login-backoff-lock.md` §3 specifies `lockEnds = lastGlobalFailureAt + GlobalLockSecs`. With 100 failures spread over 50 minutes Go's "lock" ended 35 minutes before the trip. Java follows the spec (`BackoffCheck`, pinned by `theLockIsEnforcedEvenAfterTheCountWouldHaveCleared`) | `internal/platform/auth/loginbackoff/loginbackoff.go:160-190` — add a `LastFailureAt` read and anchor `lockEnds` to it |
| I-Q12 | `/auth/2fa/verify` enforces the domain's `Allowed2FAMethods` (was enrolment-only) | `login/twofactor*.go` verify path; `mapping.Allowed2FAMethods` |

### Batch B additions (2026-09-05)

| Ruling | Go change | Go site |
|---|---|---|
| I-Q1 | Refresh the cached OIDC client when the IdP row changes (invalidate on update + TTL) | `internal/platform/auth/bridge/oidc.go` provider cache |
| I-Q3 | `OIDC_VERIFY`: fixed message to the browser, library error text to the log | `bridge/login_endpoint.go` callback verify branch |
| I-Q6 | Refuse a CLIENT/PARTNER email-domain mapping without `primaryClientId` at create/update | `emaildomainmapping` operations |
| I-Q9 / C-Q29 | `GET /auth/check-domain`: omit `authorizationUrl` instead of fabricating `issuer + "/authorize"` | auth-identity.md §4.10 site |
| I-Q10 | Consume the portal login flow at the callback sink, not at SSO start | `portal` SSO start/callback (auth-identity.md §5.6) |
| I-Q16 | Fallback brand `FlowCatalyst` | `notify/notify.go:43` |
| I-Q22 | System actor `"system"` everywhere (JIT, role sync currently write `""`) | `bridge/login_endpoint.go:661,687,803` |

### Batch C additions (2026-09-05)

| Ruling | Go change | Go site |
|---|---|---|
| defect 10 | 2FA trusted-device / method DELETE → 404 when nothing deleted | `login/twofactor_selfservice.go` revoke handlers |
| I-Q13 | Persist passkey sign counter + `last_used_at`, reject a backwards counter, emit `passkey:authenticated` | webauthn finish-login path (auth-identity.md §7.5) |
| I-Q14 | Admin `send-password-reset` gains an option to clear MFA enrolments + trusted devices, notify the user, audit | `principal` admin reset op + `mfa` service |
| I-Q15 | `Date`, `Message-ID`, RFC 2047 subject on outgoing mail | mail transport (auth-identity.md §9) |
| I-Q17 | Purge expired PINs / trusted devices / reset tokens / approvals on the purger tick | `StartPurger` sweeps |
| I-Q24 | `authenticate/begin` 429 in the platform `TOO_MANY_REQUESTS` envelope | webauthn API rate-limit path |
| I-Q25 | Passkey events under `platform:iam` | webauthn events source |
| defect 11 | Write `EXPIRED` (purger) and the reviewer `note` on approval requests | `resetapproval` ops + purger |

Backlog-only (no Go change now): C-Q18 wire rate-limit policies + callers for introspect, revoke and check-domain; C-Q28 access-token denylist (cache first, table fallback). Not deviations: C-Q1 — Go already emits the real login time (`authservice.go:511`); the Java port follows.

## Owner questions collected from specs

Each spec's "load-bearing or accident?" list, summarised; the full wording is
in the spec.

**principal — `PrincipalApi` is 1051 lines over 29 routes** (design smell,
2026-08-27 audit; describe-don't-refactor): the same shape `RouterApi` had
before its split — a wall of route registrations, handlers in the middle,
DTOs below. The split done for `RouterApi` on 2026-08-26 (per-resource route
groups, each registering its own routes, plus a `Wire` DTO holder) applies
cleanly here: the natural groups are principal CRUD, roles, client-access,
application-access, developer-credential, and the password/2FA operations.
Not done as part of the audit because the audit brief says describe smells
rather than refactor them, and because the aggregate has only just acquired
its first API test — the split is much safer with that in place than it
would have been before.

**dispatch mode — two enums, opposite defaults** (found 2026-08-27 in the Go
drift check; needs one line from the owner): Java has **two** `DispatchMode`
enums and they disagree about the same concept.

| | default when absent/unknown | unknown value |
|---|---|---|
| `router.wire.DispatchMode` | `NEXT_ON_ERROR` | logged |
| `platform.subscription.DispatchMode` | `IMMEDIATE` | silent |

The router's was changed by the owner ruling of 2026-08-25 ("wanting
concurrency and getting ordering is visible and cheap to fix; needing
ordering and silently getting none is invisible and lands in the target's
data"). The subscription one was not. **Go applied the same ruling at every
layer** in `89b195e` — "`ParseDispatchMode`'s fallback, fan-out's mode string,
`subscription.New`, and the mode column's DEFAULT" — so Java is now the only
side where a stored subscription with an absent or misspelled mode silently
gets no ordering.

Two enums in one codebase with opposite defaults for one concept is a defect
whichever default is right; `subscription.md` §9a already plans to merge them
into a shared `messaging` package. The ruling to make is only whether the
merged enum defaults to `NEXT_ON_ERROR` (extending the existing ruling, and
converging with Go) — which also changes how **existing rows** with a null or
unrecognised mode read. Not changed unilaterally because the spec carries it
as an open question ("load-bearing or accident?", `subscription.md` §1 Q7) and
because it alters stored-data interpretation.

Related and separate: `subscription.md` §217 records that create **always**
stores `IMMEDIATE` and ignores the input's `mode`, which limits how much the
parse default actually reaches today.

**principal** (demonstrated 2026-08-26 by the new `PrincipalApiTest`, needs a
ruling — spec §11 Q3/Q4): two cross-tenant/read-leak questions, both now
pinned by tests so a ruling either way shows up as a test flipping.

- **The `/{id}/…` sub-routes are not client-scoped, while the by-id read is.**
  `GET /api/principals/{id}` denies a clientA administrator reading a clientB
  principal (403 `FORBIDDEN`, §3's table), and `GET /api/principals` hides it
  entirely — but `GET /api/principals/{id}/roles` answers **200** for the same
  caller and target. An administrator blocked from reading a principal can
  still enumerate its roles. The by-id check exists and works; the sub-routes
  simply never got it.
- **Role/application-access/developer-credential mutations are an existence
  oracle.** They have no coarse handler gate and load before authorising, so a
  caller with no user permission at all gets 404 for an invented id and a
  different status for a real one — enumerable over the principal table.

**sdksync / scheduledjob** (found 2026-08-26 while porting the sync surface,
needs a ruling): `archiveUnlisted` on
`POST /api/applications/{appCode}/scheduled-jobs/sync` sweeps the **whole
`clientId` scope** — `SyncScheduledJobs` reads
`repo.findInScope(ClientFilter.scope(cmd.clientId()))`, which does not narrow
by application. So two applications sharing a client can archive each other's
jobs, and a sync with `clientId: null` sweeps **every platform-scoped job on
the instance**. The route is mounted under `/api/applications/{appCode}` and
the command already carries `applicationId`, so the narrowing is available
and simply unused.

This is **Go's behaviour too** — its own sync tests carry the warning "never
the nil (platform) scope, which would sweep other tests' jobs" — so by the
standing rule the behaviour is kept, and Java matches. But it is a
cross-tenant data hazard reachable from a normal SDK call, and it bit
immediately: the Java `SdkSyncApiTest` archived 20 of other tests' jobs on
its first full-suite run. Options: (a) keep, and document the scope on the
route; (b) narrow the sweep to `clientId + applicationId`, a deliberate
deviation needing the Go side too; (c) refuse `archiveUnlisted` on the
platform scope unless the caller is an anchor.

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

**→ Change spec written: `docs/spec/oauthapi-fixes.md`** — three grouped fixes
(userinfo + discovery route move; `client_credentials` accepting Basic;
per-client rate limiting for Basic-only clients), each with its defect, fix,
rationale and regression test. Owner approved 2026-08-24; **to be made in Go
first**, then ported. Owner also asked whether Basic auth is wanted at all:
kept for now because discovery already advertises it, RFC 6749 §2.3.1 makes it
a MUST for an authorization server, and standard third-party OIDC libraries
default to it — removing it is a separate deliberate decision that would also
have to withdraw the discovery advertisement, and only makes sense once it is
confirmed that no third party integrates directly (everyone via a FlowCatalyst SDK).

**AUTH-CORE Q4 — real defect, fix landing in Go first (2026-08-24).**
`/oauth/userinfo` sits inside the Authenticator group, which refuses any
`token_use=identity` bearer; an ordinary (non-`APIAccess`) OIDC client's
authorization_code grant mints exactly that, so the canonical relying-party
sequence is 401'd before the handler. Latent only because `APIAccess` clients
get `token_use=api` and no FlowCatalyst SDK calls userinfo. Fix: move
`RegisterUserinfoRoutes` to the public group (discovery too, as hardening).
The Java port follows the corrected Go and adds a regression test for an
identity token against userinfo. Note the Authenticator is **not** a gate —
it attaches context and calls next; only an explicit unacceptable Bearer
hard-fails. See `docs/spec/auth-core.md` Q4 for the full corrected analysis.

**AUTH-CORE Q5/Q6 — Basic auth is NOT router-only** (checked 2026-08-24).
Three unrelated things are called "basic auth": (1) the `/router/*` monitoring
dashboard's operational HTTP Basic gate; (2) OAuth client authentication on
`authorization_code` / `refresh_token` / introspect / revoke, which **does**
accept Basic (and Basic wins); (3) the `client_credentials` grant, which reads
the body only and refuses Basic with "Missing client_id" while discovery
advertises `client_secret_basic`. Removing Basic would break RFC 6749 §2.3.1
and standard third-party OIDC libraries. Recommended: route
`client_credentials` through `authenticateClient` (three lines) and resolve the
client id from Basic *before* the rate-limit decision so Basic-authenticating
clients are per-client limited, not IP-only.

**auth core** (`docs/spec/auth-core.md` §19, 29 questions; artifact published): top calls — `/oauth/token`/introspect/revoke/userinfo/discovery/JWKS mounted inside the Authenticator (Q4); `client_secret_basic` advertised but body-only creds (Q6); refresh-TTL config dead, 7 d compile-time (Q16); `expires_in` literal 3600 (Q15); `GlobalLockSecs` is only `Retry-After` (Q11); `PendingAuth` rows never consumed (Q3); `ratelimit.Prune` never called + 3 orphan buckets (Q12/Q18); RFC deviations 401-vs-400, envelope mix (Q7/Q27); `defaultScopes` string/array (Q9); `auth_time`=`iat`, `email_verified` always true (Q1/Q2). No Go tests for login/introspect/revoke/userinfo/change-password/login-history/stores/purger → conformance suite must cover.

**auth identity** (`docs/spec/auth-identity.md` §19, 25 questions + §18 15 observed defects; artifact published): passkey sign-counter/`last_used_at` never persisted and `passkey:authenticated` never emitted (Q13); four expiring tables never purged (Q17); portal SSO consumes the flow at start (Q10); JIT `CLIENT_REQUIRED` when a CLIENT/PARTNER mapping has no primary client (Q6); all-dangling allowedRoleIds ⇒ every claim role rejected (Q8); `/auth/2fa/verify` ignores the domain's allowed-method list (Q12); admin reset tokens never `requires_factor`, approval queue dormant (Q14/Q19); SessionWriter 500 plain text + `OIDC_VERIFY` leaks lib text (Q5/Q3); legacy `?provider_id=` / GET check-domain (Q7/Q9); bridge OIDC client cache never invalidated (Q1).

**principal** (`docs/spec/principal.md` §11, 12 questions — the security-critical
aggregate; **audit pass not yet run, no `PrincipalApiTest` yet**). The one to
rule on first: **Q3 — role / application-access / developer-credential
mutations have no coarse handler gate and load the target before authorizing,
so an unauthorised caller can distinguish "exists" from "does not exist"
(existence oracle).** Also: Q1 `AssignRoles` rewrites every assignment as
`ADMIN_ASSIGNED`, silently adopting IdP- and SDK-sourced rows; Q2
`SyncPrincipals` strips `SDK_SYNC` roles from every USER not in the payload
regardless of application; Q4 by-id reads check only `USER_VIEW`, not client
scope; Q8 `SendPasswordReset` bypasses the envelope (no event, no audit).

**AUDIT-TRAIL DEFECT FOUND AND FIXED (2026-08-24)** — fourteen `PrincipalEvents`
records declared a record component `principalId` naming the event's *subject*,
which silently overrode `DomainEvent.principalId()` (the *actor*). `aud_logs.principal_id`
and `msg_events.context_data` therefore recorded who was acted on, not who
acted, for every principal operation. Fixed by renaming the component to
`userId`, reading the actor from `metadata()` in both sinks, and adding a guard
test that fails if any event record ever shadows a `DomainEvent` accessor again.

**GO DRIFT (2026-08-24)** — `../flowcatalyst-go` has moved past the commit the
router spec was extracted from (`1e9d465`). Three commits change router/dispatch
behaviour: `f1fc427` (only BLOCK_ON_ERROR holds a group — the Q1 ruling, now
implemented upstream), `5bb46df` (delivery-time blocked-group hold-back at
`/api/dispatch/process`), `eff2a29` (new `flushGroup` mediation response +
`GroupFlushRegistry`, a wire-contract change). See `docs/spec/router.md` §0.
Nothing ported so far is invalidated; re-extract §2/§3/§6/§7 before the router
port. Re-check for further drift at every data-plane unit.

**router** (`docs/spec/router.md` §13): 50 questions; **Q1 ruled** (NEXT_ON_ERROR continues past a failed head; BLOCK_ON_ERROR ACKs the queued siblings and leaves the group pending platform-side until the error clears — deliberate deviation from Go). Q1 sub-question resolved by the human-review flow (ignore/completed/resend re-queues the group). **Q2 ruled**: no terminal give-up — messages live until the queue expires them; backoff + circuit breaker are the protection. **Q3 ruled**: collapse the in-call retries and pool backoff into ONE named retry policy, behaviour-preserving, pinned by a conformance test. Remaining 47 pending.

## `ServiceAccountCode` vs the `app:<code>` convention (2026-09-05, **owner**)

`ServiceAccountCode.parse` rejects `:` (it validates user-chosen codes), yet
the platform's own convention for an application's service account is
`app:<applicationCode>` — Go's `fcdev init` writes it, and the Java
`AttachServiceAccount` test fixture uses the same literal. The Java
`fcdev init` constructs the value object directly to store it, bypassing
the parser. Question: should the value object admit a reserved `app:`
namespace (and reject it from the API's create path), or should the
convention change? Until ruled, the direct construction stays, commented.

## `iam_login_attempts` has no retention (2026-08-24)

Surfaced while fixing Q12. No `DELETE` exists for `iam_login_attempts`
anywhere in the Go tree, yet the login backoff queries it with
`since cutoff` on every attempt — an ever-growing index on the hot path.

Unlike `iam_rate_limit_events` this may be a deliberate security audit
trail, so it is a question rather than a defect. **Owner question:** keep
as history (then it needs indexes sized independently of the table, and
archival is separate), or purge on a retention of days — well above
`GlobalWindowSecs` (3600) so audit value is not destroyed to serve the
limiter? See `docs/spec/auth-retention.md` §5.

## I-Q16 applied to the public platform name too (2026-09-05, **owner to confirm**)

Ruling I-Q16 fixed the notification fallback brand as `FlowCatalyst`.
`Branding.DEFAULT_PLATFORM_NAME` — what `/api/config/platform` answers
when no platform name is configured, and the e-mail theme's fallback —
was `Flowcatalyst`, so two fallbacks would have disagreed. The Java port
uses `FlowCatalyst` for both. Only unconfigured installations see it;
say if the public endpoint should keep the old spelling.

## Readiness and alarms beyond C-Q23 (2026-09-05)

`/health` now carries readiness checks (a failing one is 503 `DOWN`) and
the first check is the login-attempt partitions. Candidates for later, not
done: a pool-connectivity check (a `SELECT 1` with a short timeout), the
signing key's presence, and the rate-limit store when Redis lands. The
alarm counters are process-global (`AuthAlarms`); a `/auth/2fa/verify`
backoff-store error should increment the same counter (the Sonnet route
unit is told to reuse `LoginApi`'s pattern).

## Full-suite one-offs under machine load (2026-09-05)

Two consecutive `mvn -pl server clean test` runs on main each failed in
one unrelated class while the host load average sat at 8–10 (the owner's
IDEs), and the third run was green (3245 tests). Neither reproduced alone,
and they were different classes each time, so they are recorded here as
harness smells rather than treated as regressions:

- `OutboxFixture.<clinit>` failed (every outbox test then reported
  `NoClassDefFoundError`); the fixture's static block runs
  `initSchema()` against the shared embedded Postgres, and a slow start
  under load is the likely cause. Worth catching the real exception in the
  fixture so the report names it instead of the follow-on class-init error.
- `ServiceAccountApiTest`: all 15 requests answered **404** in 0.08 s, and
  the one test expecting 404 passed. **Root cause found later the same
  day:** a third run had `ProcessApiTest` read `This is a SOCKS Proxy,
  Not An HTTP Proxy` — the JDK client's `localhost` resolved to an address
  family on which a local proxy, not Jetty, held the ephemeral port.
  `TestHttp` now binds and connects on `127.0.0.1` and its readiness probe
  must answer a per-instance nonce, rebinding on a foreign answer
  (`TestHttp.ForeignServer`). The outbox fixture one-off is still
  unexplained and still worth catching the real exception for.

## Deferred out of the port

- **Outbox Mongo backend** (`FC_OUTBOX_BACKEND=mongo`, Go
  `internal/outbox/mongo`). Owner ruling 2026-09-05: out of the port, Postgres
  only; revisit if a consumer app needs it.

Platform improvements that are deliberately **not** porting work live in
`docs/improvements.md` — currently auth-core Q13 (no refresh-family
revocation on authorization-code replay). Nothing there blocks a port unit;
Java reproduces the current Go behaviour and the improvement is a separate,
later decision.

## Router completion drive — design smells (2026-09-02)

- ~~Two `DispatchMode` enums~~ — merged into
  `platform.shared.dispatch.DispatchMode` (X-01) on 2026-09-02.
- **`PoolTest.rateLimitWarnsOnceForARun` is load-sensitive.** It failed twice
  while a second Maven build ran on the machine and could not be reproduced
  idle (4/4 green, also with 32 carriers); the throttle path was made one
  act (reserve → record → wait) and the assertion now prints the pool's
  counters on failure. If it fails again, the message says what stalled.
- ~~Router tests leak parked virtual threads~~ — root cause was the
  terminal path: nothing closed a manager's pools, so every worker parked on
  a permit outlived its test. `RouterManager.close()` and a terminal
  `RouterServer.close()` (drain, hand back, then close pools) fixed it; a
  thread dump after the router package now shows zero leaked virtual threads
  (2026-09-02).
- **`Message.dispatchMode` is a raw nullable component with an overriding
  accessor**, so `equals`/`hashCode` compare the raw value while every reader
  sees the normalised one. Documented in the record; a sealed
  `Absent | Specified(mode)` wrapper would be cleaner once the positional
  constructor call sites can be touched in one go.
- **Redis client construction is duplicated** between `server.Router.redisFor`
  and `server.Server.schedulerLeader` (Jedis pooled provider, retry bounds).
  One helper in `server/` should own it.
- **No SQS or NATS dispatch publisher.** The scheduler publishes to Postgres
  or a no-op; Go is the same today. Needed before a production deployment on
  either broker.
- **Subscriber deliveries go out unsigned** — `DeliveryCredentials.none()`
  until the `serviceaccount` aggregate is ported (Go resolves job →
  subscription → application → service-account credentials).
- **Processing endpoint 5xx classification** is uniform (every non-2xx/429
  consumes budget), not the router's 502/503/504-vs-other split; open owner
  question in `docs/spec/dispatch-seam.md` §14.

## From the parity-harness design (2026-09-05, **owner**)

- **`$schema` on every JSON response.** huma's schema-link transformer adds a
  `"$schema": "<base>/schemas/<Model>.json"` member to every response Go
  sends; Java never emits it. The frontend's generated types declare it
  optional and no SDK reads it (checked 2026-09-05). Owner: is its absence a
  wire break for any client, or do we let it go? Until ruled the parity
  harness carries one wildcard allow-list entry (`**/$schema`,
  `parity-harness.md` §10).
- ~~**`FC_JWT_ACCESS_TOKEN_TTL_SECS` is not read by Java**~~ **Done** (same day) — (`TokenIssuer.ACCESS_TTL_SECONDS`
  is the constant 3600). Go sets the minted `exp` and the advertised
  `expires_in` from it together. The env-parity check in `cutover.md` §4 found
  it as the only server knob missing; wire it after the C4 merge (Env field →
  `TokenIssuer` → every `expires_in`), one test pinning both values.
- **Introspection `client_id` (parity S2, 2026-09-05).** Java answers the
  token's `azp` (RFC 7662: the client the token was issued to); Go answers
  the first entry of the `clients` tenant claim and omits it for the anchor
  wildcard. Owner: keep the RFC meaning (recommended — the Go value is a
  tenant id under an OAuth name) or mirror Go? Allow-listed meanwhile.
- **Trusted-device cookie on password change (parity S2).** Both sides
  revoke the trusted-device rows; Java also expires the `__Host-fc_td`
  cookie in the browser, Go leaves it. Deliberate; Go-mirror candidate.
- **`client_credentials` on a client without a principal answers 500
  `server_error` "Client not properly configured" on both sides.** RFC 6749
  §5.2 wants a 400 (`unauthorized_client`). Go defect mirrored for parity;
  Go-mirror candidate — fix both together.
- **`/api/me` `name` for a service principal (parity S2).** Go answers `""`
  for a `client_credentials` token's principal; Java answers the service
  account's name. Go accident (the token's `name` claim is the principal
  row's name, which Go's provisioning leaves blank); Java kept, allow-listed.
- **`rememberDeviceAllowed` on the gated login response (parity S2).** Java
  adds it under I-Q11 so the SPA can hide the remember-device checkbox when
  the domain forbids it; Go emits nothing. Deliberate; Go-mirror candidate.
- **Batch ingest with one invalid item (parity S1-C).** Java rejects the
  whole batch (`IngestApiTest`); Go writes the valid items and reports
  `SUCCESS` for the invalid one. Go defect; Java kept; allow-listed.
- **Audit-log `principalId` default (parity S1-C).** Java defaults an absent
  `principalId` to the caller; Go stores NULL, so Go's by-principal filter
  misses those rows. Go defect; Java kept; allow-listed.
- **WebAuthn ceremony option defaults (parity S1-B).** go-webauthn omits
  `attestation`, `userVerification`, `excludeCredentials`, `extensions`,
  `hints` and advertises COSE algorithms `-7,-35,-36,-257,-258,-259,-37,-38,-39,-8`;
  yubico emits `none`/`preferred`/`[]`/`credProps`/`[]` and cannot offer the
  PS* or Ed448 algorithms. Browsers accept both shapes; the timeout is now
  300 s on both sides. Owner: any client outside a browser reading these?
  Allow-listed meanwhile.
