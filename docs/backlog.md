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

## From the dispatchjob port (**owner**) **Ruled 2026-09-06 #12: requeue stays total; cancel/complete routes after cutover.**
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

## OAuth client secrets are reversibly encrypted, not hashed (2026-09-08, **owner**) **Ruled and DONE 2026-09-08: hash them.** Java `5d0972f`, Go `64b8170`. Verify-only secrets are `hashed:v1:<base64 HMAC-SHA256>`; webhook/OIDC/TOTP secrets stay encrypted because the platform uses them. Migration is invisible: verification accepts any legacy shape and rewrites the row *after* authentication has already succeeded, so a failed write never costs a login. The text below is kept as the reasoning.

Found doing a hashing/encryption inventory of the codebase. `ClientAuthentication.acceptClientSecret`
/ `verifySecretRef` (`platform/auth/oauth/ClientAuthentication.java`) only ever
does `decrypt(secretRef)` then `MessageDigest.isEqual(plaintext, provided)` —
a pure verify-only comparison, structurally identical to a password check.
Nothing ever re-sends a client secret anywhere. Storing it via
`shared.encryption.Encryption` (AES-256-GCM, reversible under
`FLOWCATALYST_APP_KEY`) buys nothing over one-way hashing here, and costs
something real: a leaked `FLOWCATALYST_APP_KEY` instantly and fully recovers
every OAuth client secret in the database in plaintext. Hashed (even a keyed
HMAC-SHA256 pepper, not full Argon2id — these are already 32 random bytes,
not human-guessable passwords, so brute force isn't the threat model), a
leaked app key would recover nothing.

This is inherited from Go as-is (`docs/spec/auth-core.md` line 273 / 867 cite
Go's `OC:371-394`), not something re-justified when it was ported — exactly
the "Go is evidence of what Go does, not of what is right" case
([[feedback_correctness_over_conformance]]).

Contrast: `WebhookCredentials` (signing secret, bearer token, outbound
basic-auth password / API key — `platform/serviceaccount/WebhookCredentials.java`)
correctly *must* stay reversible, because the platform is an active user of
those secrets — it computes an HMAC signature with the signing secret on
every outbound delivery, or attaches the bearer token/API key as a live
`Authorization` header when calling the subscriber's endpoint. Nothing to
change there.

**Ruled 2026-09-08: hash them**, with the lazy encrypt→hash rewrite on next
successful auth (no backfill job, no re-issuing). Landed both sides the same
day. Nothing redisplays a client secret, so nothing needed the plaintext.

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

- **Go's seeder cannot seed a fresh database at HEAD.** **Fixed in Go `ba45035`.**
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

- **Event deduplication never fires across requests.** **Fixed in Go `491d961` (ingest-time lookup; ON CONFLICT stays).** `msg_events`'
  unique index is `(deduplication_id, created_at)` (partition key) and
  `created_at` is stamped per insert, so an SDK replay with the same
  `deduplicationId` lands a second event. `docs/spec/sdk-ingest.md` §5 D6.
- **Dispatch-job ingest is unusable by non-anchors.** **Ruled 2026-09-06 #9: Go adopts the seeded permission.** **Fixed in Go `ba45035`.**
  `internal/platform/shared/sdk/{dispatch_jobs_batch,dispatch_job_create}.go`
  gate on `CanWritePermission(ac, "WRITE_DISPATCH_JOBS")` — a permission
  string no seeded role grants (the catalogue has
  `platform:messaging:batch:dispatch-jobs-write`) and `requirePermission`
  has no aliases, so every non-anchor SDK service account gets 403
  `PERMISSION_REQUIRED`. Java (`docs/spec/sdk-ingest.md` §5 D1) checks the
  seeded permission — deliberate deviation. One-line Go fix.

- **X-06 at the wire (owner yes/no).** **Ruled 2026-09-06 #19: reject with 400.** The Java sweep kept the pre-existing
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

## `ServiceAccountCode` vs the `app:<code>` convention (2026-09-05, **owner**) **Ruled 2026-09-06 #16: reserved `app:` namespace.**

`ServiceAccountCode.parse` rejects `:` (it validates user-chosen codes), yet
the platform's own convention for an application's service account is
`app:<applicationCode>` — Go's `fcdev init` writes it, and the Java
`AttachServiceAccount` test fixture uses the same literal. The Java
`fcdev init` constructs the value object directly to store it, bypassing
the parser. Question: should the value object admit a reserved `app:`
namespace (and reject it from the API's create path), or should the
convention change? Until ruled, the direct construction stays, commented.

## `iam_login_attempts` has no retention (2026-08-24) **Ruled 2026-09-06 #17: keep as history.**

Surfaced while fixing Q12. No `DELETE` exists for `iam_login_attempts`
anywhere in the Go tree, yet the login backoff queries it with
`since cutoff` on every attempt — an ever-growing index on the hot path.

Unlike `iam_rate_limit_events` this may be a deliberate security audit
trail, so it is a question rather than a defect. **Owner question:** keep
as history (then it needs indexes sized independently of the table, and
archival is separate), or purge on a retention of days — well above
`GlobalWindowSecs` (3600) so audit value is not destroyed to serve the
limiter? See `docs/spec/auth-retention.md` §5.

## I-Q16 applied to the public platform name too (2026-09-05, **owner to confirm**) **Ruled 2026-09-06 #18: keep `FlowCatalyst`.**

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
- **The two `BLOCK_ON_ERROR` PoolTests raced their own submits** (2026-09-09).
  **Fixed.** Recorded because the first reading of it was wrong in an
  instructive way.

  `blockOnErrorSettlesSiblingsWhenGateIsOn` failed once in ~15 full runs with

  ```
  Expecting ["rejected-group-blocked", "delivered", "delivered", "delivered"]
  to contain only ["rejected-group-blocked"]
  ```

  Three siblings *delivered* where the mode says they must be ACKed untried
  looks like the guarantee of the 2026-08-25 ruling failing, and it was
  initially filed here as a possible product defect on the reasoning that CPU
  starvation cannot manufacture deliveries that should not happen. **That
  reasoning was wrong.**

  `OrderedGroups:193` builds the failure as
  `new HeadFailure.BlockGroup(head, takeAndReleaseGroup(head.group()))` — the
  siblings are a snapshot taken at the instant the head fails, and the group
  is then *released*. The test fires four `submit` calls back to back and
  assumes the head fails last. Under load it can fail first: the snapshot is
  empty, only the head is ACKed `rejected-group-blocked`, and m1–m3 then
  arrive as a **fresh** group with m1 as its head — delivered normally, which
  is correct. Starvation manufactures the deliveries indirectly, by reordering
  the head's failure ahead of the siblings' arrival.

  Reproduced deliberately: 10 CPU spinners against 14 cores, 1 failure in 8
  runs of `PoolTest` — which surfaced `blockOnErrorReleasesSiblingsWhenGateIsOff`
  wearing the same root cause as a *timeout* instead (siblings that get
  delivered are never nacked, so its `await` waits out its 5 s). One race, two
  tests, two symptoms.

  Both now hold the head inside the mediator (`mediator.block()` / `unblock()`,
  the barrier this file already uses elsewhere) until every sibling is queued
  behind it. Same contention afterwards: **0 failures in 8 runs.**

  No production change: siblings queued behind a failed head are blocked, and a
  message arriving after the group resolved is new work that should be tried.

- ~~`ConsumerLoopTest.resumesPromptlyWhenCapacityReturns` and
  `PoolTest.rateLimitWarnsOnceForARun` are load-sensitive~~ — **fixed
  2026-09-09.** Neither was a concurrency defect: both failed on the shared
  `await` **deadline** (5s in `PoolTest`, 10s in `ConsumerLoopTest`, used
  across 48 and 28 call sites). That deadline is *liveness*, not performance —
  a healthy run returns the moment the condition holds — so it was only ever
  measuring the machine. Both are now 60s, which costs nothing on success and
  changes only how long a genuinely stuck test takes to report.

  One assertion needed more than a bigger deadline.
  `resumesPromptlyWhenCapacityReturns` asserts `elapsed < 100ms` after the
  capacity signal, a real wall-clock claim that starvation defeats however
  long the deadline is. It now calibrates against the machine: measure a bare
  virtual-thread handoff (park, signal, best of five), stand down with an
  assumption if that exceeds 50ms — the machine cannot time anything — and
  otherwise budget `max(100ms, handoff × 10)` capped at 500ms, always well
  under the 1s fixed pause the assertion exists to exclude.

  Not weakened, on two counts: the mutant still dies (reinstating
  `Thread.sleep(POLL_ERROR_PAUSE)` in place of the gate park fails the test),
  and on an idle machine the handoff measures ~67µs so the budget stays at
  its 100ms floor — identical strictness to before. Evidence: 5/5 clean runs
  of both classes under 10 CPU spinners with **zero** stand-downs, plus a
  green reactor. The failure message now prints the calibrated budget and the
  measured handoff, so a recurrence will say which half went wrong.
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
- **Processing endpoint 5xx classification** is uniform (every non-2xx/429
  consumes budget), not the router's 502/503/504-vs-other split; open owner
  question in `docs/spec/dispatch-seam.md` §14.

## From the parity-harness design (2026-09-05, **owner**)

- **`$schema` on every JSON response.** **Ruled 2026-09-06 #1: let it go.** huma's schema-link transformer adds a
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
- **Introspection `client_id` (parity S2, 2026-09-05).** **Ruled 2026-09-06 #3: RFC meaning; Go takes the patch.** Java answers the
  token's `azp` (RFC 7662: the client the token was issued to); Go answers
  the first entry of the `clients` tenant claim and omits it for the anchor
  wildcard. Owner: keep the RFC meaning (recommended — the Go value is a
  tenant id under an OAuth name) or mirror Go? Allow-listed meanwhile.
- **Trusted-device cookie on password change (parity S2).** Both sides
  revoke the trusted-device rows; Java also expires the `__Host-fc_td`
  cookie in the browser, Go leaves it. Deliberate; Go-mirror candidate.
- **`client_credentials` on a client without a principal answers 500 **Ruled 2026-09-06 #11: 400 `unauthorized_client` both sides.**
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
- **Batch ingest with one invalid item (parity S1-C).** **Ruled 2026-09-06 #10a: partial success with per-item results; the outbox poller reads them.** Java rejects the
  whole batch (`IngestApiTest`); Go writes the valid items and reports
  `SUCCESS` for the invalid one. Go defect; Java kept; allow-listed.
- **Audit-log `principalId` default (parity S1-C).** **Ruled 2026-09-06 #10b: required on both sides.** Java defaults an absent
  `principalId` to the caller; Go stores NULL, so Go's by-principal filter
  misses those rows. Go defect; Java kept; allow-listed.
- **WebAuthn ceremony option defaults (parity S1-B).** **Ruled 2026-09-06 #4: accept the difference.** go-webauthn omits
  `attestation`, `userVerification`, `excludeCredentials`, `extensions`,
  `hints` and advertises COSE algorithms `-7,-35,-36,-257,-258,-259,-37,-38,-39,-8`;
  yubico emits `none`/`preferred`/`[]`/`credProps`/`[]` and cannot offer the
  PS* or Ed448 algorithms. Browsers accept both shapes; the timeout is now
  300 s on both sides. Owner: any client outside a browser reading these?
  Allow-listed meanwhile.
- **OpenAPI documents (parity S3).** **Ruled 2026-09-06 #2: Java serves the lockfile verbatim.** `/api/openapi.json`, `.yaml`, `/q/openapi`
  and the developer BFF's platform spec differ structurally: Go serves huma's
  generated document, Java its own generator's. Recommended: Java serves the
  vendored lockfile verbatim (it *is* Go's document) plus the A-22 route.
  Owner: does any client parse these at runtime (frontend codegen is build-time)?
- **BFF scheduled-job list leaks across clients on Go (parity S3).** Without a
  `clientIds` filter Go's `/bff/scheduled-jobs` shows another client's job to
  a client-scoped caller; Java confines (bff.md §7). Go defect; allow-listed.
- **`/api/me` `name` is the email on Go (parity S3)** for a principal created
  with a name; Java answers the stored name. Go accident; allow-listed.
- **Send-email-code with no factor (parity S3).** Go answers `NO_EMAIL_2FA`
  for a principal with no factor at all; Java `NO_MFA` first. Go accident;
  allow-listed; Go-mirror candidate.
- **Platform-scoped scheduled jobs for a client-scoped caller (parity S3).** **Ruled 2026-09-06 #6: hidden from client users.**
  Java's shared visibility rule lists `client_id IS NULL` rows to every
  caller; Go's BFF scheduled-job list hides them from a client-scoped one
  (while, in the same list, showing another client's job — the leak above).
  Owner: are platform-scoped jobs visible to client users? Allow-listed.
- **Go's fcdev cannot boot a fresh database (blocks the Go column of the **Fixed in Go `ba45035` — the Go column of the e2e is unblocked.**
  frontend e2e).** `fcdev start` seeds `schema_type = 'JSON'` against its own
  migration 051 CHECK (the seeder defect already listed above). The parity
  harness works around it because it owns the database; the e2e runner goes
  through `fcdev start`, which seeds and exits. Until Go's seeder is fixed,
  `pnpm e2e:both` reports the Go side as failed to start and `pnpm e2e:java`
  is the usable command. Owner: the Go fix is one literal in
  `internal/platform/seed/event_types.go`.

## SDK on Jackson 3 (owner note, 2026-09-06) — **done the same day**

Turned out simpler than the note below: nothing in `sdk` or `fcdev` used the
generated client at all (the SDK's own `io.flowcatalyst.sdk.http` is the
client). The generator now emits models only; a nine-line hand-written
`io.flowcatalyst.sdk.generated.ApiClient` supplies the two static helpers the
models' `toUrlQueryString` call; the two Jackson 2 dependencies are gone.
`mvn dependency:tree` on `sdk` shows `tools.jackson` databind/core and the
`jackson-annotations` jar Jackson 3 itself depends on, nothing else.


The `sdk` module's own code is already Jackson 3 (`tools.jackson.*`); the
only Jackson 2 in it is what `openapi-generator-maven-plugin` 7.14's `java`
/ `native` templates emit — not the models (their 2,548 imports are all
`com.fasterxml.jackson.annotation`, which Jackson 3 databind still uses) but
the seven supporting files we ask it to generate: `ApiClient`,
`RFC3339DateFormat`, `RFC3339InstantDeserializer`, `RFC3339JavaTimeModule`
(+ `ApiException`, `ApiResponse`, `Pair`, which import nothing Jackson). The
generator has no Jackson 3 template yet. To drop Jackson 2 from the SDK:
set `generateSupportingFiles=false`, keep the models, and write our own
Jackson 3 `ApiClient` + date handling (Jackson 3 databind reads RFC 3339
`Instant`s natively — no jsr310 module), then remove
`com.fasterxml.jackson.core:jackson-databind` and `jackson-datatype-jsr310`
from `sdk/pom.xml`. The `server` module keeps its Jackson 2 databind only
for yubico's WebAuthn library, a separate matter. Small unit; do it before
the SDK is published.

## Owner questions raised 2026-09-06 (contract source, WebAuthn library)

- **TypeBox as the source of the wire schemas.** Today the contract is the
  OpenAPI lockfile Go's huma emits; the SPA generates TS types from it
  (`openapi-ts`), the Java SDK generates models from it, the Java server's
  request-schema-validation filter validates against it at runtime, and the
  parity harness treats Go as the oracle. TypeBox could become the *source*
  that produces that same OpenAPI document (TypeBox → JSON Schema → OpenAPI),
  which keeps every consumer unchanged and gives TS runtime validators for
  free. What it does not remove: the Java server's hand-written DTO records
  (not generated today) and the Java SDK's generated models. Decision, not
  code: which repo owns the contract, and it belongs after cutover while Go
  is the oracle.
- **A WebAuthn library without Jackson 2.** yubico `webauthn-server-core`
  needs Jackson 2 databind at runtime (1.3 MB, runtime scope only; the
  server's own JSON is Jackson 3). `webauthn4j` is the maintained
  alternative and also depends on Jackson 2 (databind + CBOR). The only
  Jackson-2-free path is our own verifier over what is already here
  (`com.upokecenter:cbor`, Nimbus for COSE keys): clientDataJSON checks,
  authenticator-data flags/counter, `none`/`packed` attestation, ES256/RS256/
  EdDSA signatures — a few hundred lines plus FIDO test vectors, and the one
  place a hand-rolled implementation earns a security review. **Ruled (owner,
  2026-09-06): keep yubico; a hand-rolled verifier is not worth it in so
  sensitive an area. The one runtime-scope Jackson 2 databind jar in the
  server artifact stays for that reason and no other.**
- **Event-type `clientScoped` is dropped on create, both sides (frontend **Ruled 2026-09-06 #7: honour the field.**
  e2e, 2026-09-06).** The SPA's create drawer sends `clientScoped: true`;
  Go's `eventtype/entity.go:208` hard-codes `false` at construction and the
  Java port mirrors it, so the detail page reads "Client Scoped: No". A
  product defect pre-dating the port; `catalogue.spec.ts` carries a
  `test.fail` that flips to an unexpected pass when either side honours the
  field. Owner: honour it (Java first, then a Go mirror) or remove the toggle.
- **Dispatch-pool "Delete" confirm copy (SPA).** The dialog says the pool
  will be archived; both servers delete the row. Frontend repo fix.
- **The SPA cannot create an INTERNAL identity provider against the current **Ruled 2026-09-06 #8: Go DTO optional + lockfile re-dump.**
  contract (frontend e2e, 2026-09-06).** `IdentityProviderCreateDrawer.vue`
  sends `oidcMultiTenant` only for OIDC providers, but the lockfile (Go's
  huma schema, `CreateIdentityProviderRequest.required`) lists it — so Go
  answers 400 VALIDATION, and Java now does too. A frontend/contract
  mismatch that predates the port: either the drawer always sends
  `oidcMultiTenant: false`, or Go's DTO makes it optional and the lockfile
  is re-dumped. The e2e flow pins it as an expected failure meanwhile.

## HTTP/2 and HTTP/3 on the server listeners (owner requirement, 2026-09-06) — **done the same night (`58187c8`, `docs/spec/http-transport.md`); only the graceful-stop delay below remains**

The owner: "we need to enable HTTP/2/3". Today the Java server speaks
HTTP/1.1 only (Javalin 7.2.3 on Jetty 12, default connector); Go's inbound
server gets HTTP/2 for free from `net/http` over TLS and has no HTTP/3; the
only HTTP/2 either side has on purpose is the router's *outbound* mediator
client (`router.md` §HTTP/2 via ALPN). Java work, Phase 5b:

- **HTTP/2**: `h2` over TLS via ALPN when the server terminates TLS itself
  (`jetty-alpn-server` + `jetty-http2-server`, a `FC_TLS_*` key/cert pair),
  and **`h2c`** (cleartext HTTP/2 with prior knowledge / upgrade) on the API
  listener for the load-balancer-terminates-TLS deployment — ALB speaks
  HTTP/2 to targets only as h2c. Both connectors keep HTTP/1.1 alongside.
- **HTTP/3**: Jetty's `jetty-http3-server` over QUIC (`jetty-quic-server`,
  the `quiche` native library bundled per platform) on a UDP port, with the
  `Alt-Svc: h3=":<port>"` header advertised from the TCP listeners. Only
  meaningful where the server terminates TLS (a QUIC endpoint needs the
  certificate); no load balancer here forwards HTTP/3 to targets. The native
  library needs a GraalVM native-image entry (`native-config/`).
- Tests: a JDK `HttpClient` with `Version.HTTP_2` against the TLS listener
  and a prior-knowledge h2c client against the plain one; an HTTP/3 probe
  needs a client that speaks it (Jetty's `jetty-http3-client` in test scope).
- Spec first (`docs/spec/http-transport.md`: connectors, env knobs, the
  `Alt-Svc` rule, metrics listener stays HTTP/1.1), then a Sonnet unit.

## Audit-log batch: an over-long `entityId` is a 500 on both sides (2026-09-06)

Found writing the 10b scenario: `aud_logs.entity_id` is `varchar(17)` and
neither side checks the length, so an item with a longer `entityId` fails
the batch insert — 500 `REPO` on Go, an unhandled `DataException` on Java —
instead of a per-item `BAD_REQUEST`. Same defect both sides; the SDKs pass
application-chosen ids through. Fix together: validate the length per item
(the column is a TSID-width string, so probably the column is the real bug —
an audit entity id is whatever the application calls its entity) or widen
the column in a migration on both sides. Owner: which.

## Native fcdev: published docs are not served from the image (2026-09-06)

The native `fcdev` logs `published docs under docs/published are unreadable;
serving none` at start (`PublishedDocs` walks the resource directory as a
filesystem path, which a native image's `resource:` URL is not) — every
other surface verified. Same code path as the native fc-server; fix by
listing the published docs through an index file at build time, as
`IndexedMigrations` does for Flyway. Small; not blocking.

## HTTP/3 sessions hold the graceful stop (2026-09-06)

With `FC_HTTP3_ENABLED=true`, after an h3 exchange whose client simply
went away (curl exits without a QUIC CONNECTION_CLOSE), `Server.stop`
waits the whole `SHUTDOWN_GRACE` (31 s measured) before the connectors
close — idle, or after an h2 exchange, the stop is immediate. Overriding
`QuicheServerConnector.shutdown()` and closing its connected end points
changed nothing, so the waiting `Graceful` is inside Jetty's QUIC/HTTP3
session stack (`Http3Test` tolerates the timeout with a pointer here).
Impact: a slower stop on instances that served h3 — HTTP/3 is off by
default and the ALB topology never enables it on the target. Fix: find the
Graceful bean (a thread dump during the stall shows `Server.doStop:711`
waiting), or set a short QUIC idle timeout if Jetty exposes one; possibly a
Jetty issue.

## Sync rollup audit rows sort after Go's (2026-09-06)

`AuditLogRepository`'s by-principal list now orders `(performed_at desc, id desc)` like Go's
(it ordered by `performed_at` alone and matched Go by luck). The rollup and the per-row audit
rows of one sync share `performed_at`, and Java's sync operations build the rollup event *after*
the per-row events (higher TSID) where Go builds it first, so at equal timestamps the pair
appears swapped. Six allow-list entries in `parity/expected-diffs.json` (`by-principal`) carry
it. To retire them: construct the rollup event before the per-row events in the nine `Sync*`
operations (the `TxScopedUnitOfWork.commitSync` write order is pinned and is not the lever —
ids come from the events).

## Session path per-request CPU: verify once, render once (owner: "document as a to-do, don't do it", 2026-09-07)

`bench/real/RESULTS.md` round 14: on the 1 KB single-item endpoint at 1 CPU, Java spends
321 µs of CPU per request (JIT) against Go's 153 µs, and the two largest buckets are the same in
the JIT and native profiles — the RS256 verify of the session JWT (29–34%) and jOOQ rendering
the three session-path queries from the AST on every request (~20%). Neither fix is a knob.

1. **Verify a session token once.** A bounded verdict cache keyed by a hash of the token: fixed
   number of slots, each entry expiring at the token's own `exp`, least-recently-used eviction when
   full, backed by Caffeine (already a dependency). A hit skips the RSA verify and the JWT parse;
   revocation and the session's own DB checks are unchanged (they run after the verify today and
   keep running). Memory: one slot ≈ token hash + claims record, ~1 KB; the slot count is derived,
   not configured (e.g. the request-worker count × 1,000, i.e. the number of distinct sessions the
   process can be serving at once), so a 1-CPU deployment holds ~30k entries ≈ 30 MB worst case.
   The owner's questions to settle before building: fixed slots yes; LRU on full yes; whether the
   cache should be per-process only (yes — it holds verdicts on tokens, not sessions, so nothing
   to share). Expected gain: ~30% of the Java request, and the same verify is ~14% of Go's.
2. **Render the session-path queries once, and fewer of them.** Measured with `pg_stat_user_tables`
   (200 requests, counters settled — idle backends flush ~10 s late): the endpoint runs **eight
   statements per request, identical on Java and Go** — five for the session/authorisation lookups
   (`iam_principals`, `iam_principal_roles`, `iam_roles`, `iam_principal_application_access`,
   `iam_client_access_grants`; `iam_role_permissions` is a 151-row seq scan every time) and three
   for the item (`msg_event_types` and `msg_event_type_spec_versions`, 72-row seq scans each on
   this tiny table). jOOQ renders all eight from the AST on every call. Render them once — jOOQ
   static SQL (`dsl.resultQuery(String, binds)` on a `Query` rendered at startup) or plain JDBC on
   that path only — keeping the row mapping; and fold the five authorisation lookups into one or
   two joins, which is a Go mirror item too (`docs/go-mirror/`). Expected gain: ~20% from rendering
   alone; the round-trip reduction helps every runtime equally.

Together these are the gap between Java JIT and Go on this endpoint (48% → ~100% of Go's
throughput at 1 CPU). Native `-O2` additionally pays 3× the JIT on the RSA verify (GraalVM CE has
no Montgomery-multiply intrinsics on arm64), so item 1 matters most for the native binary.

## Config merge across sources keys queues by URI (Go behaviour, 2026-09-07)

Fixed 2026-09-07: a *single* config source now passes through `RouterConfig.merge` unchanged
instead of being keyed, so several queue names sharing one `queueUri` (the normal Postgres shape)
all survive. Across *several* sources the `queueUri` key still applies and still collapses distinct
queue names that share a URI to the first source's definition — Go does the same
(`mergeConfigs`/keying by URI in `../flowcatalyst-go/internal/router/config_sync.go`), so this is
left as spec-conformant; a Go mirror item only if multi-source Postgres configs with shared URIs
ever turn out to matter in practice.

## JSpecify + NullAway (owner to-do, 2026-09-07 — agreed)

Adopt JSpecify nullness annotations and NullAway (an Error Prone check) so nullness is enforced
by `javac`, not by tests. Why: the owner's stated Java weakness versus Rust is what the compiler
cannot catch; nullness is the largest such class and this closes it at build time with no runtime
or reflection cost (dependency mindset: build-time only). Shape:

1. `@NullMarked` at package level (`package-info.java`) across `usecase`, `sdk`, `server`, `fcdev`;
   `@Nullable` only where a null is meaningful (e.g. `MediationTransport.Response.retryAfter`,
   `Message.authToken` "present-but-empty is distinct from absent"). `org.jspecify:jspecify` is
   already on the classpath transitively — declare it explicitly.
2. Error Prone + NullAway wired into `maven-compiler-plugin` (`-Xplugin:ErrorProne
   -XepOpt:NullAway:AnnotatedPackages=io.flowcatalyst`), severity ERROR under the existing
   `-Werror`; verify the versions support JDK 25 (`--enable-preview` is on) before committing to it.
   Exclude generated code (jOOQ `io.flowcatalyst.db.generated`, picocli-generated) via
   `NullAway:ExcludedClassAnnotations`/package excludes; Jackson-populated records need
   `@Nullable` components rather than exclusion.
3. Roll out package by package (largest signal first: `router`, `platform/shared/auth`,
   `http`), fixing real findings as they surface; each package is one commit. Record any
   finding that is a genuine defect (a null that could reach production) in `docs/STATUS.md`.
4. Native image: annotations are compile-time only; confirm the GraalVM build is unaffected
   (no new reachability metadata).

Not started. Estimated as a Sonnet unit per module with orchestrator review of every `@Nullable`
added — an annotation placed to silence the checker is worse than none.

## OAuth client secrets are reversibly encrypted where a keyed hash would do (2026-09-08, owner question)

`ClientAuthentication` only ever decrypts the stored client secret to compare it (`MessageDigest.isEqual`)
— verify-only, structurally a password check — yet the secret is stored AES-GCM-encrypted under
`FLOWCATALYST_APP_KEY` (inherited from Go, `docs/spec/auth-core.md`). A leaked app key therefore
recovers every OAuth client secret in plaintext. Webhook credentials (signing secret, bearer token,
API key, outbound basic-auth password) genuinely need reversibility — the platform uses them on every
outbound call — and stay encrypted. Ruling to take: store OAuth client secrets as a keyed hash
(HMAC-SHA256 with the app key as pepper is enough for 32 random bytes; Argon2id is unnecessary for
non-human secrets), migrate existing rows on next use or by a one-off rotation, and mirror the change
in Go (`docs/go-mirror/`). **Done 2026-09-08 — Java `5d0972f`, Go `64b8170`.**
Duplicate of the entry above; kept because it states the webhook-credentials
contrast (those genuinely need reversibility and stay encrypted).

## The dev mail transports log the login/2FA PIN (2026-09-08, **owner**) **Ruled and DONE 2026-09-08: body only in dev.**

Found during the structured-logging conversion. Two "no SMTP configured"
fallbacks log the whole rendered mail body, which contains the one-time PIN
and the password-reset link:

- `platform/auth/mfa/MailSender.java:23` — `log.info("mail transport not
  configured; would send to={} subject={} body={}", to, subject, html)`
- `platform/mail/MailService.java:16` — the same at `warn`.

Both say so in their own doc comments, so it is deliberate: with no mail
server, reading the PIN out of the log is how a developer completes a login.
The risk is that it is not *conditional on being a developer* — a production
deploy that simply has no SMTP settings will write live 2FA PINs and reset
links to the log at info/warn, where they are shipped to whatever aggregates
them.

**Ruled 2026-09-08: gate the body on dev.** Both transports now take
`includeBody`, and `MailService.fromEnv` — the one production actually
resolves — passes `FLOWCATALYST_DEV_MODE` (the flag that already existed, so
no new knob). Recipient and subject are still logged either way, so "was the
mail attempted?" stays answerable; only the rendered HTML is withheld.
`SmtpMailServiceTest` pins both halves, including that the body never appears
in the message text under either setting. Both files are off the
`StructuredLoggingTest` allowlist now — nothing is interpolated any more.

## Failures are logged as strings, so the stack trace is thrown away (2026-09-08) **DONE 2026-09-08.**

Also from the structured-logging conversion, and reported independently by
three of the four agents. A recurring pattern passes `e.getMessage()` or
`e.toString()` as a message argument instead of the `Throwable`:

- `platform/portalauth/PortalSso.java`, `platform/serviceaccount/api/ServiceAccountApi.java`,
  `platform/shared/auth/SigningKeys.java`, most of `platform/auth/oidc/OidcBridgeApi.java`,
  several `router/queue/sqs` and `router/config/http` sites, and
  `fcdev/DevBootstrap`, `McpCommand`, `EmbeddedPg`, `StartCommand`.

The conversion preserved the behaviour exactly (these became a `reason`
key-value, not `setCause`), because promoting them to a real cause would
change what is logged and that was outside its scope. But the effect is that
none of these failures has a `throwable` field: no stack trace, no cause
chain, just a flattened string. For a failure path that is the interesting
half of the record.

Related: `fcdev/FcDev.java` attaches the cause **only** when debug logging is
on; with the default level an unhandled top-level fcdev failure logs a
message and no stack trace at all.

**Swept 2026-09-08** (owner approved): 21 sites across 11 files now
`setCause(e)` instead of a stringified `reason`, so each carries a real
`throwable` field with its cause chain. The compiler is the check that every
one of them was genuinely a `Throwable`. `HttpError` was left alone — it
already attached the cause, and its short `reason` is a queryable summary
beside it, not a replacement.

`fcdev/FcDev.java`'s two branches are collapsed into one call that always
attaches the cause; the debug-gated version meant an ordinary `fcdev` failure
logged a message and no stack trace at all.

## Attaching causes made the repeating failure paths noisier (2026-09-08) **DONE 2026-09-09: one trace per streak.**

Consequence of the `setCause` sweep above, worth a decision rather than a
silent revert. Several of the 21 sites sit on per-message or per-attempt
paths, so a *sustained* failure now emits a full stack trace per occurrence
where it previously emitted one line:

| Site | Fires once per |
|---|---|
| `router/manager/ConsumerLoop.java` "poll failed" | poll iteration, for as long as the broker is unreachable |
| `router/queue/sqs/SqsQueue.java` ack / DeleteMessage failed | message, so every message during an AWS outage |
| `router/queue/sqs/SqsQueue.java` "sqs malformed message body" | bad message, so a whole batch from a broken producer |
| `router/config/http/HttpConfigSource.java` "config fetch attempt failed" | retry against a bad config URL |

The stack trace is worth most on the *first* occurrence and is nearly pure
volume after that. `ConsumerLoop` already keeps a `pollFailing` latch for
exactly this reason (it gates `warnings.raise`), so gating the cause on the
same latch there is close to free; the SQS and config-source sites would each
need one.

**Ruled 2026-09-09: option (b)** — the cause on the first failure of a
streak, the exception's `toString` afterwards. Every attempt is still logged
and still names the error; only the repeated stack trace goes. Each site
rides state that already existed rather than adding a parallel flag:

| Site | Latch |
|---|---|
| `ConsumerLoop` "poll failed" | the existing `pollFailing`, set on the first failure and cleared by the first poll that succeeds |
| `HttpConfigSource` fetch / invalid JSON | a new per-URL `causeLogged` set, cleared by `recordSuccess` |
| `SqsQueue` ack + both DeleteMessage paths | a new `sqsFailing`, cleared by the next delete that succeeds |

`HttpConfigSource` deliberately does **not** ride its existing `failing`
set, which was the first attempt: that set is only entered when there is a
last-known-good to fall back on, so a URL that has never once succeeded — the
misconfigured-URL case, retried every 5 s and never recovering on its own —
would never be in it and would log a trace on every retry forever. Caught by
writing the test for it; `causeLogged` tracks the streak on its own terms.

`sqs malformed message body` deliberately keeps **no** cause: it fires once
per bad message and a Jackson parse failure's trace is the same frames every
time, so the message — what failed and where — is the whole of the
information.

Both halves are pinned and mutation-checked: `ConsumerLoopTest` asserts one
trace across a failing streak with every poll still logged, and
`SqsQueueTest` asserts the same *and* that a delete which succeeds ends the
streak, so a fresh outage gets its own trace. Removing the latch, or removing
its reset, each fails a test.

## `Seeder`'s bootstrap-admin warning cannot be mechanically converted (2026-09-08)

`platform/seed/Seeder.java:324` reads `"no bootstrap admin configured — set {}
+ {} to create one email_set={} password_set={}"`. The first two placeholders
are joined mid-sentence by a literal `+`, so removing them leaves "set  +  to
create one". It needs a rewrite (the two env var names are constants and
belong in the message text, the two booleans are fields), not a mechanical
conversion. On the `StructuredLoggingTest` allowlist until then.

## The full `mvn clean test` suite is red on two collation assertions (2026-09-08) **FIXED 2026-09-09.**

Found by a control run on a clean tree (no local changes), so this is on
`main` as it stands, not something a change introduced:

| Test | Assertion |
|---|---|
| `AuditLogRepositoryTest.facetsAreDistinctNonNullAscending` | `"it.97e1cd70.skipped.…"` is not ≤ `"Oauthclient"` |
| `SubscriptionApiTest.createThenReadByIdAndInList` | `"fanout-wildcard-…"` is not ≤ `"Raw-2e2a7a"` |

Both pass **in isolation** and fail in the full suite, and in both the
offending pair is one row of the test's own plus one seeded by a *different*
test. The cause is not ordering flakiness: the rows come back in Postgres'
collation order (case-insensitive: `fanout` < `Raw`) and the test asserts
Java's natural `String` order (case-sensitive: `'R'` 0x52 < `'f'` 0x66). With
only its own lower-case rows present the two orders agree, which is why
isolation hides it.

So the assertion was testing the JVM's collation against the database's.

**Fixed 2026-09-09 in the tests, not the SQL.** `COLLATE "C"` was considered
and rejected: Go issues the same `ORDER BY` against the same database, which
is why the parity corpus runs 0 DIFF on these routes, so changing Java's
ordering would *introduce* a parity difference. The database's collation is
the contract.

Both tests now hand their returned values back to Postgres
(`select v from unnest(?::text[]) order by v`) and assert the repository had
already returned them in that order. That is locale-independent, stays
correct if the deployment's collation differs, and is independent of what the
query selects or filters — dropping the `ORDER BY` in either repository still
fails its test (mutation-checked both ways).

No production defect was behind this: nothing re-sorts in Java on either
path, and the audit cursor uses a SQL-side `row(performed_at, id)`
comparison, so the keyset-pagination-under-a-different-collation bug this
resembles is not present.

## Go's `EncryptSecretRef` is not idempotent over a `hashed:` ref (2026-09-08, Go-side)

Noticed while mirroring the encryption rulings. Go's `EncryptSecretRef`
passes through `encrypted:` and the external schemes, then encrypts whatever
is left — so an incoming `hashed:v1:<mac>` is **sealed as though it were
plaintext**, giving `encrypted:<sealed "hashed:v1:…">`. Java's parse treats
`hashed:` as an at-rest claim and passes it through (and rejects a payload
that is not exactly 32 base64 bytes).

Reachable by POSTing `hashed:v1:…` as `oidcClientSecretRef` or a
service-account credential. Consequence is mild — the stored value simply
never verifies — but it is the same class as the closed-prefix ruling of
2026-09-08. Deliberately **not** part of the
2026-09-08 encryption rulings (`docs/go-mirror/README.md`), which were scoped
to the four rulings you gave; a one-line prefix check fixes it whenever you want it.

## The per-queue Postgres pool is sized from the wrong quantity (2026-09-08, measured)

`QueueFactory.createPostgres` sizes a queue's own pool `max(4, availableProcessors())`, mirroring Go's
`pgxpool` default `max(4, NumCPU())`. Measured in a `--cpus=1` container: Java's `availableProcessors()`
honours the CFS quota and returns **1** (pool = 4); Go's `NumCPU()` ignores it and returns **14**
(pool = 14). Over eight queues that is 32 connections against 112, and it is the whole of the
Postgres-broker gap: Java 2,361 deliveries/s with Postgres at 274% CPU, Go 5,456/s with Postgres at
901% (`bench/router/RESULTS.md`). Java is not slower; it is a quarter as parallel because it reads the
quota correctly.

The rule itself is the defect: a broker consumer's connection need is set by how many acks can be in
flight, not by how many CPUs the router was given. Ruling to take, then mirror in Go: size the
per-queue pool from the admission concurrency the queue feeds (capped), or fix it at a small constant
independent of CPU. Whatever is chosen must be derived, not an env knob (`feedback_no_tuning`).

## Endpoint groups: own admission pool, own DB pool, own listener (owner direction, 2026-09-08)

Today one listener serves everything and `io.flowcatalyst.http.Group` {LOGIN, OIDC, DISPATCH, INGEST,
NO_DB} only picks a `RequestWorkers` lane in front of one shared `GatedDataSource`. The owner wants the
grouping to go all the way down, so a deployment can split or prioritise:

| group | endpoints | DB |
|---|---|---|
| ingest / dispatch | what the message router POSTs (processing endpoint, settled) | own pool |
| BFF | what the SPA calls | own pool |
| API | the ordinary REST surface | own pool |
| SSE | event streams | **none** |

Ruled by the owner already:
- **SSE has no pool and no DB connection.** One virtual thread per connection parked *untimed* on a
  bounded per-subscriber queue (`take()`, never `poll(timeout)`: an untimed park is a continuation
  unmount, no timer, no kernel switch — the finding behind the Tier-1 gate). The publisher `offer()`s;
  a full queue means the client cannot keep up and its stream is closed. Keepalive is ONE tick on the
  event loop's timer wheel for all subscribers, never a timer per connection. A snapshot needed at
  subscribe time is a single query before streaming starts, borrowed from the API group.
- **Per-group pool sizes are configurable per deployment.** This is the one number the owner has always
  accepted (`feedback_no_tuning`: "the DB pool size is something for every version"), now per group.
- **Queue-depth metrics**: how many requests are waiting for a worker, per group, exported so a
  deployment can see which group is starved. Needs `RequestWorkers` to expose per-pool queue length and
  in-flight count, and a collector next to the existing gate/worker gauges.

Ruled 2026-09-08 (owner):
- **One port. All groups on it.** Separate ports are complexity without a point.
- **Split the API by transaction span, not by URL shape**: a write holds its connection for the whole
  request because the transaction spans it; a read does not need to.
- The group is declared per route (`Routes.in(Group)`), as today.

Open for a ruling before building:
1. **Ruled 2026-09-13: one Hikari pool per group.** Sizing proposal in `docs/spec/admission.md`
   §11.3a (one budget per instance, shares by hold time, workers = pool by identity, bounded queues
   with 503 + Retry-After, corrected by the queue metrics) — awaiting the owner's confirmation of the
   default split.
2. **Whether a non-transactional read releases its connection between statements.** Today `Admission`
   holds one connection per request and nested checkouts join it (the re-entrant handle), so a read
   doing eight statements holds a connection across the CPU between them — and that CPU is not small:
   the session profile put jOOQ rendering at ~20% and the JWT verify at ~30% of the request. Releasing
   between statements would cut read hold time by roughly half at the cost of one (untimed, therefore
   cheap) gate acquire per statement. It changes no consistency guarantee: those statements are not in
   a transaction today.
3. Whether the read group's pool points at a read replica. This is the real payoff of the read/write
   split and it is only possible once reads are identified.
Not started; SSE is not implemented at all yet.

## Defect: `/api/dispatch/process` claims a job without a lock — FIXED in Java 2026-09-08, open in Go (go-mirror G14)

Found while settling the batching design above (owner: *"we MUST check the db and get a transaction lock
on the record"*). The handler reads the job with a plain `findById`, tests `isTerminal()` in application
code, then flips the row to `PROCESSING` with an `UPDATE ... WHERE id = ? AND created_at = ?` carrying
**no status guard** — and treats a failure of that flip as best-effort, logging and delivering anyway.
Read-then-act with no lock and no guard: two concurrent deliveries of the same job (a queue redelivery
racing an in-flight attempt, or a router restart re-sending) both pass the terminal check, both flip the
row, and both call the subscriber. The subscriber sees the delivery twice.

`docs/spec/dispatch-seam.md` §(leader-gating table) asserts the opposite — the processing endpoint is
listed as *"guarded by its own status checks; safe under concurrent instances"*. That is true of the
settled endpoint, whose `UPDATE ... AND status IN ('QUEUED','PROCESSING')` really is the idempotency
contract, and untrue here: this is the one write on the seam with no such guard.

- Java: `ProcessingApi#deliver` + `DispatchJobRepository#markInProgress`
- Go: `internal/platform/dispatchjob/processing/processing.go:219` +
  `internal/sqlc/queries/dispatchjob.sql` (`DispatchJobMarkInProgress`) — identical shape, identical hole

**Java: done** (`DispatchJobRepository#claimForDelivery`, `ProcessingApi#deliver`). The claim is a single
conditional `UPDATE ... AND status IN ('PENDING','QUEUED')` whose row count decides delivery; a lost
claim ACKs with `already claimed` and makes no call; a claim that throws NACKs instead of delivering.
Pinned by `ProcessingApiTest#twoConcurrentCallbacksForOneJobDeliverToTheSubscriberExactlyOnce` — with
the status predicate removed the subscriber is called twice, which is the defect reproduced.
**Go: still open**, tracked as G14.

Fix (Go): make the claim atomic and let it decide whether to deliver. Either
`UPDATE ... SET status='PROCESSING' WHERE id = ? AND created_at = ? AND status NOT IN (<terminal>)` and
deliver only when one row was affected, or the `SELECT ... FOR UPDATE` the batching design needs anyway.
Stop swallowing the failure: a claim that changes no row means someone else owns this delivery, and the
answer is `ack:true` with no call. Correct the spec's leader-gating table in the same change. Pinning
test: two concurrent `process` calls for one job, assert the subscriber is called exactly once (mutant:
drop the status guard from the claim and the count becomes two).

## Smell: `reschedule` has no status guard either (Java and Go, noticed 2026-09-08)

Spotted while fixing the unguarded claim above; narrower, and NOT fixed in the same change because it is
a design question rather than a missing predicate.

`DispatchJobRepository#reschedule` sets `status='PENDING', scheduled_for=?` on `id + created_at` with no
status predicate, and it has two callers that want different guards:
- the delivery-time hold-back revert, which fires **before** any claim and should only move a row that is
  still `PENDING`/`QUEUED`;
- the `Deferred` outcome, which fires **after** a delivery and legitimately moves a row out of
  `PROCESSING`.

The reachable hole needs three jobs in one message group and two overlapping callbacks for one of them:
callback A finds the group clear, claims, and is delivering; the group then blocks; callback B for the
same job finds it held and reverts the row to `PENDING` underneath A's in-flight delivery. The poller can
then re-claim and republish it while A is still talking to the subscriber. Rare, pre-existing, and it
ends with the row `COMPLETED` either way, so the visible symptom is a duplicate delivery rather than a
lost job.

Fix when touched: give the two call sites separate methods with their own status guards
(`revertHeldBack` guarded on `PENDING`/`QUEUED`, `deferAfterDelivery` guarded on `PROCESSING`) rather
than one guard that has to satisfy both. Same shape in Go (`Reschedule`, `repository.go`).

## Batch the dispatch-job fetch behind the mediation endpoints (owner design, 2026-09-08)

The endpoints the message router POSTs to (`/api/dispatch/process`, `/api/dispatch/settled`) load one
dispatch job per request, then make the outbound call to the subscriber. Under load that is one query
and one connection per in-flight mediation. Owner's design: queue the incoming requests in-process and
have a batcher fetch them together.

Shape agreed:
- **K batchers, self-clocking, never timed. Batch size is an OUTCOME, not a setting.** Not one batcher:
  a lone batcher makes every arrival wait for the in-flight query. Run K batchers; a request waits only
  when all K are busy and then leaves with whichever frees first, so nothing ever waits for a batch to
  *fill*. Under light load a request is dispatched alone as a batch of one and pays nothing; batches grow
  only when arrivals outpace the database, which is when grouping is free.
  **Correcting an earlier version of this note (2026-09-08):** it said "up to ~20 batchers each taking
  ~20 records", which implies 400 mediation requests in flight and cannot happen. One router polls
  `ConsumerLoop.MAX_POLL` = 10 messages and routes them into a pool running
  `RouterManager.DEFAULT_POOL_CONCURRENCY` = 20 deliveries concurrently, so in-flight mediation requests
  W = the sum of pool concurrencies, ~20 for one default pool. With K batchers the mean batch is W/K —
  so K = 20 would make every batch one row and buy nothing at all. **Choose K for the database (it IS
  this group's connection count) and let batch size fall out of contention. Never target a batch size.**
  No timer is armed, so no timed park (§1 of `docs/spec/admission.md`); a fixed "20 rows or 100 ms" would
  add 100 ms to every request on an idle system, and a load-detecting switch is a second way of deciding
  something contention already decides correctly. If particular messages must never queue behind others,
  give them a priority lane that bypasses batching, not a global mode.
- **The router's poll batch is not the unit either (owner asked, 2026-09-08).** Tempting, since the poll
  already produces a natural batch and would need no timer. But the poll batch is dissolved before the
  first mediation call: `ConsumerLoop` hands it to `RouterManager.route`, which spreads it across pools
  whose workers deliver under a concurrency semaphore, per-group ordering and a rate limit. Delivering a
  poll batch as a unit would mean undoing the pool, and one response would be held open for the slowest
  subscriber in the batch, head-of-line-blocking unrelated subscribers. Self-clocking gives the same
  "no timer" property without coupling the two services.
- **The batch fetch is a claim, and the claim commits before the handler runs.** A row lock belongs to a
  transaction on one connection and cannot be handed to another; committing is the only way to release
  it. So the lock is not what the delivery runs under — the `status` column is. One statement does the
  whole claim: `UPDATE msg_dispatch_jobs SET status='PROCESSING' ... WHERE (id, created_at) IN (...)
  AND status NOT IN ('PROCESSING', <terminal>) RETURNING ...`. It takes the row locks itself, returns
  exactly the rows this batch won, and commits as a single statement. No explicit `SELECT ... FOR UPDATE`
  and no transaction block. Order the id list by `created_at, id` so two overlapping batches cannot
  deadlock on opposite lock orders.
  Three outcomes per waiter, all distinguishable: row returned means we own the delivery; row absent from
  the result but present in the table means terminal or already claimed, so `ack:true` with no call; row
  absent from the table means the job is gone, also `ack:true`.
  **Commit before handing to the handlers, never after** — a commit after delivery would hold the
  transaction, the connection and the row lock across the outbound call to the subscriber, which is the
  exact thing this design exists to avoid.
  Blocking: a plain `UPDATE` waits on a row another transaction has locked, but under this rule every
  lock holder is a claim that commits without doing any network work, so the wait is bounded by a single
  statement. `SELECT ... FOR UPDATE SKIP LOCKED` followed by the update is the fallback if that ever
  measures badly (it supersedes the earlier note here rejecting `SKIP LOCKED`; the rejection was about
  how to *interpret* a skipped row, and the status predicate already answers that).
  Cost of committing early: a crash between the claim and the outcome write leaves the row `PROCESSING`
  with nobody working on it, recovered by `DispatchJobReaper` (`DEFAULT_PROCESSING_LIVE_AFTER`,
  currently 45 minutes). That is the correct trade against holding a lock across a subscriber call.
- **Batch the status writes too — they pay more than the reads.** Each is a transaction, so twenty writes
  are twenty begin/commit cycles on twenty connections; one `UPDATE ... FROM (VALUES ...)` collapses them
  into one. The batch is all-or-nothing with every waiter failing together (or retried individually), and
  the same job must not appear twice in a batch — collapse duplicates last-write-wins before building the
  statement.
- **In-process primitives, not a message bus.** A bounded queue, one batcher virtual thread per group,
  and a `CompletableFuture` per request; the request thread parks untimed on its own future and the
  batcher completes it. The Vert.x event bus would add addressing, codecs and cluster routing for an
  in-process fan-in, and Vert.x is no longer the listener.
- **The request path then holds no connection at all.** Only the batcher borrows one, so this group's
  pool is sized by K, the number of batchers, rather than by request concurrency, and "never
  hold a connection across an external wait" becomes structural rather than a discipline.
- Failure semantics up front: a failed batch query fails every waiter in it; a missing row fails only
  its own waiter; the queue is bounded and rejects with 503 when full rather than growing; the terminal
  write that records the outcome batches through the same mechanism.

**Closed (owner, 2026-09-08): no full-payload option on the router.** Carrying the job in the message
would remove the fetch, but the fetch is not optional: the handler must take a transaction lock on the
row to claim it, so it has to go to the database whether or not it already has the payload. The
identifier indirection is also what keeps the router accurate — the payload is read fresh at delivery,
so a job cancelled or amended after publication is not delivered stale — and keeps the router a relay
that never inspects payloads. Not to be revisited.

**Not to be built yet.** The expensive part of these endpoints is the outbound call to the subscriber,
which cannot be batched, and at production rates (~200/s) the fetch is not the constraint. The trigger
is connection-hold time on this group against request duration (§11.6) showing the fetch taking a
meaningful share of a busy group's pool.

## Router: a queue that does not exist yet is polled every second, forever (2026-09-11)

**This is expected, not a fault.** Integral's control plane (`/api/config`,
`ControlPlaneController`) advertises a queue for every subscription record's
`FC-{env}-{tenant}-{queue}.fifo`. Integral creates each queue lazily, on its
first send (`MessageRouterService::sendBatchCreatingQueueIfMissing`). So a
queue no message has been sent to — e.g. a `…-workers-high` variant
(`QueueWorkerTypeEnum::HIGH`) for a tenant that has never produced one — is
listed but does not exist. Confirmed by the owner, 2026-09-11.

Go (staging, 2026-09-04,
`FC-staging-ceramic-release-staging-workers-high.fifo`) logs `consumer poll
error … NonExistentQueue` as a WARN every second, indefinitely. Java raises a
`CONNECTION` warning on the first failure, which **now reaches Teams**: every
never-used queue would page on every restart or leadership change. That is
worse than Go's log noise.

Proposal, needing an owner ruling because it deviates from Go:
- **Classify:** treat `NonExistentQueue` as *not yet created*, distinct from
  connection failures.
- **No alert:** raise no `CONNECTION` warning for it. Log one INFO line when
  the queue is first found missing, and one when it appears.
- **Back off:** retry with a growing gap, 1 s doubling to a cap of about 60 s,
  so a queue Integral creates is consumed within a minute.
- **Unchanged:** genuine connection failures keep today's warning.

Test: a scripted consumer returning that error. Assert that no warning is
raised, that the gap between attempts grows to the cap, that exactly one INFO
line is logged, and that polling returns to normal once the queue exists.
Separately, assert a connection error still raises `CONNECTION`.

## A service account cannot run an event-type sync: rollup audit write fails (both sides, noticed 2026-09-13)

`POST /api/applications/{code}/event-types/sync` as an application's own
service account (client-credentials token, `platform:application-service`,
which holds the sync permission) answers **500 `AUDIT_WRITE` "rollup audit
write failed" on Go and Java alike**, even with an empty `eventTypes` list.
Seen in the parity corpus while re-pointing the profile-only scenario's
service step (`parity/scenarios/platform/profile-only.json`,
`service-passes-gated-route`). The same route as the admin session works
(`applications/sdk-sync.json`). Sync by a service account is the SDK's
whole purpose, so this is a real defect, not a corpus artefact — probably
the rollup audit row assuming a USER actor (an email or user-identity
column). Reproduce in a unit test, find the column, fix on the Java side,
hand to Go.

## Platform-config property routes: grant model without a permission code (noticed 2026-09-13)

`GET/PUT/DELETE /api/config/{app}/{section}/{property}` and
`GET /api/platform-config/{app}` gate on a per-application access grant
(`platformconfig` `Access`): a non-anchor principal granted access to an
application reads or writes that application's config holding no permission
code at all, while anchors need `platform:admin:config:view/update` only for
the grant-management routes (`docs/spec/reach-only-routes.md`). Two models
coexist on one aggregate. Owner question: should the property routes also
require the config codes (grant = reach, code = authority, consistent with
the 2026-09-13 ruling), which would mean every grant holder also needs a
role carrying them?

## Service-account list order differs between the sides (noticed 2026-09-13)

`GET /api/service-accounts`: Java orders by `code` ascending
(`ServiceAccountRepository`), Go by something else — the parity run that
introduced an account whose code sorts *between* existing ones
(`app:parity-authz-…` between `app:parity` and `app:parity-cc-…`) came back
with Go listing it first and Java in code order. Every earlier corpus row
happened to sort the same both ways, which is why this never showed. Pick
one key (code is the stable, human-predictable one) and mirror it; until
then the `authz` scenario deletes what it created so later list steps stay
clean.

## Every anchor-scoped principal passes every permission check (both sides, noticed 2026-09-13)

**RESOLVED 2026-09-13 (Java side).** Owner ruling: permissions always come
from roles, at every tier; anchor scope governs reach only, never authority.
`Checks.require`/`requireAny` no longer short-circuit on `isAnchor()`;
`requireUserAdmin` still lets an anchor reach any target but now also
requires a user-write permission; `requireAdmin` (anchor OR the wildcard) is
removed, its one caller (`DashboardBff.stats`) now gates
`requireAnchor` + `requireAny(CLIENT_VIEW, APPLICATION_VIEW)`. Full spec and
tests: `docs/spec/permissions-from-roles.md`. The nine reach-only route
classes that spec's §3a found (gated on `requireAnchor` alone, so the
withdrawn bypass had nothing to have been bypassing) are gated as of
`docs/spec/reach-only-routes.md`, 2026-09-13. §4 of that spec (service-account
**reach** — today every service principal is created `ANCHOR` regardless of
`ServiceAccount.clientIds`) was deliberately out of scope for this unit and
specified separately; **done 2026-09-13**, Java side: `docs/spec/service-account-reach.md`
(`clientIds` now decide the linked principal's tier — none stays `ANCHOR`,
one is `CLIENT`, several is `PARTNER`). The Go mirror is still unresolved.

`Checks.require` / `requireAny` return early on `isAnchor()` (Go
`internal/platform/shared/auth/auth.go:409` is identical: `a.IsAnchor() ||
a.HasPermission(perm)`). A provisioned application service account is an
anchor-scoped `SERVICE` principal, so its `platform:application-service`
role — deliberately the least-privilege set (event create, event-type
view/create) — is decorative: the account passes the gate on every admin
route, dispatch jobs, users, audit logs included. Found while building the
router-config route (`docs/spec/router-config-auth.md`), which checks the
permission directly for that reason and is the only route where a role on a
service account currently means anything.

Needs an owner ruling because it changes both sides: should the anchor
auto-grant apply to `USER` principals only (a service account holds exactly
its roles' permissions), or should service accounts be created with a
narrower scope? Either way the parity corpus should gain a step proving a
provisioned service account is refused an admin route.

## Invite confirm signs the user in without a login-attempt row (2026-09-14)

`app-managed-invitations.md` §4: a `POST /auth/password-reset/confirm` of an
INVITE token on a domain without 2FA now sets `fc_session` itself. Go writes
no `iam_login_attempts` row for that sign-in, so `/auth/login-history` and
the backoff timeline never see it; Java mirrors Go. Owner question: should
the invite sign-in record a SUCCESS attempt (type `PASSWORD`? a new type?)
so the history is complete? Both sides change together.

## The e2e runner reuses a stale fcdev jar (2026-09-14)

`e2e/runner/build.ts` `buildJavaFcdev` reuses any existing
`fcdev/target/*.jar` without comparing it to the source; a run after a
server change silently tests yesterday's binary unless
`E2E_FORCE_JAVA_BUILD=1` is set (it cost an hour of false-bug chasing on
2026-09-14). Fix: rebuild when any tracked file under `server/`, `fcdev/`,
`sdk/`, `usecase/` is newer than the jar (or always rebuild — the reactor
package is ~1 min), and say which in the run banner.

## The two-CPU p99 tail is admission queueing, not memory (2026-09-14)

`bench/real/RESULTS.md` round 16: at 2 CPUs / 200 connections Java's p50 is
ahead of Go (66 vs 84 ms) while its p99 is five times Go's (~550 vs 102 ms),
and the tail does not move with the heap ceiling (512 MiB → 1,740 MiB) or
show up in GC pauses (max 13.5 ms). That shape is a queue: the per-group
request workers (`admission.md` §11.7, reads at twice the pool) hold requests
while Go's goroutine-per-request lets them contend on the DB pool directly.
Worth a measured look before cutover (verification plan Phase 2/3): the
worker multiplier for reads, or the queue's ordering, decides the tail the
SPA sees under load. Not a tuning knob — a design measurement.

## fc-router CPU is unbounded in ECS (2026-09-14, owner: leave for now)

`compute/index.ts` declares `routerCpu` (256) but never applies it, so the
router task definition reads `cpu: 0` — no quota, no shares. Pinning one core
on the EC2 launch type means task-level `cpu: "1024"` (a hard CFS quota
under the agent's default `ECS_ENABLE_TASK_CPU_MEM_LIMIT=true`) plus the
container's `cpu` shares from the same value; the same number also becomes
the placement reservation (half an m7g.large). The platform and worker
tasks have the same dead `cpu` config. Owner deferred on 2026-09-14; the
edit is a three-file change, drafted and reverted.

## Drop-in pass follow-ups (2026-09-14, `docs/audit/2026-09-14-drop-in-pass.md`)

- **RULED 2026-09-23: 60 s.** Java's default is 60 (CRITICAL still bypasses
  batching); the owner sets the router task's IaC value to 60.
  Was: `NOTIFICATION_BATCH_INTERVAL` — the router task sets 300;
  Go never reads it (20 msgs / 10 s hard-coded); Java honours it. Cutover
  changes Teams batching from 10 s to 300 s unless the IaC is set to 10.
- **Ruling:** structured-log shape — Go flat slog JSON (`time`, `level`,
  `msg`, fields top-level) vs Java logback JSON (`timestamp`,
  `formattedMessage`, nested `kvpList`, `loggerName`, `threadName`, `mdc`,
  `throwable`). Every log query and alert parses one or the other.
  Recommend a logback layout matching Go's shape before cutover (one unit).
- fcdev: no `completion` subcommand (Go has one); version flag `-v` at the
  root, `-V` on subcommands. Cosmetic.
- Browser flow `platform/documentation` failed once at `E2E_RETRIES=0`,
  passed on retry; cause not found.

## Subscription binding `filter` is accepted on the wire and dropped (2026-09-19)

`EventTypeBinding.filter` has no column (`SubscriptionRepository`) — every
subscription, not just functions'. A function manifest's `subscriptions[]`
entries carried a `filter` key until `docs/spec/function-invocation.md` §3
was amended (2026-09-19/20 rulings) to make it an unknown field
(`MANIFEST_UNKNOWN_FIELD`) rather than promise filtering that never
happens. The underlying gap — no filter storage anywhere in the platform —
is still open for the admin/SDK subscription surface too.

## Event ingest does not check who owns the event type (2026-09-20, **owner**)

`POST /api/events` and `/api/events/batch` (`IngestApi`) gate on the coarse `BATCH_EVENTS_WRITE`
permission only: any principal holding it can emit **any application's** event types. The owner's
ruling for functions (R13, `docs/spec/function-context.md` §3.1) is that an emitter may emit only
event types it owns, *and that this should hold on the outbox/ingest path too*. That is a change to
a Go-parity route every SDK and outbox producer uses, so it is its own unit on `main`: spec what
"owns" means (application-scoped service account → its applications' types; users?), and the
rollout (log-then-enforce?). The function emit route (`POST /control/functions/events`) enforces it
from day one.

**Ruled 2026-09-21: parked — trust our own emitters.** Every producer on the ingest path today is
first-party (our SDKs and outbox processors, each an authenticated service account holding
`BATCH_EVENTS_WRITE`); an ownership lookup per event would add complexity and cost to the hottest
write path to defend against ourselves. Functions stay enforced (R13) because they run code the
platform did not write. **Reopen when** a producer that is not ours gets ingest credentials — a
partner integration, a tenant-issued service account — and then as log-then-enforce.

## Seeded event schemas that the events themselves do not satisfy (2026-09-21, **owner**, both repos)

Found while pinning `platform:admin:connection:synced` against its seeded JSON schema. The seeded
schema for `platform:admin:connection:created` **requires** `endpoint` and `serviceAccountId`; the
event (`ConnectionCreated`, Java and Go alike) carries `connectionId`, `code`, `name` and nothing
else. `connection:updated`'s schema requires `code`; the event carries `connectionId` and `name`.
Every schema is `additionalProperties: false`, so a consumer that validates a platform event
against the schema the platform itself publishes for it **rejects every one of these events**.

Nothing checks this today: the catalogue is seeded from one hand-written table
(`PlatformEventSchemas` / Go `seed/event_schemas.go`) and the events are written elsewhere. Only
the two `:synced` rollups are pinned (this unit). **Suggested unit:** one test per platform — every
`DomainEvent` record the platform can emit, built from a representative aggregate, validates
against the seeded schema for its type — then fix whichever side is wrong, type by type (the
schema is probably the stale one: connections lost their `endpoint` when subscriptions took it).
It is a wire contract with subscribers, so which side moves is the owner's call.

## JDK: stay on 25 LTS with `--enable-preview` until 29 LTS (owner ruling 2026-09-22)

The reactor compiles with preview features on for `StructuredTaskScope` (seven `main` sites: the
router's `Concurrently`, `RouterServer`, `HttpConfigSource`; `StreamProcessor`, `Projector`;
`ScheduledJobScheduler`; the function host's `Reconciler`). The owner wants the feature and keeps
the flag; a preview class file runs only on the exact release that compiled it, so every launcher
passes `--enable-preview` (`make run`, both Docker entrypoints, the native build args).

**Ruling: no move to 26/27/28. Move directly from 25 to 29 LTS in September 2027.** Per JEP 543
(Candidate, 2026-09-17) the JDK 27 preview (JEP 533: `join()` gains an exception type parameter,
after 26's minor changes) is the final shape and 28 finalises it without further change, so 29
carries the final API. JDK 25 itself never receives the revisions — preview APIs are frozen at the
release that ships them; 25.0.x updates are fixes only.

**The unit, when 29 GA lands:** re-fit the seven sites to the final API (the `join()` type
parameter and whatever 26 changed); remove `--enable-preview` from the root `pom.xml` compiler
args and surefire `argLine`, `fcdev/pom.xml` and `server/pom.xml` native build args, both
`docker/entrypoint.sh` files and the Makefile; bump `mise`, both Docker base images and the GraalVM
toolchain; full reactor from clean plus both native builds. After it the jar is no longer pinned to
one release. Re-check JEP 543's status before then — if finalisation slipped past 28, the re-fit is
the same but the flag stays.

## Function API gaps the SPA exposed (2026-09-22, package H) **DONE 2026-09-22 (`82f91318`, unit S of `docs/spec/function-backlog-2026-09-22.md`; SPA follow-up unit U).**

Found while building the function screens (`docs/spec/function-ui.md`):

1. **No list route for policies.** `GET /api/function-policies/{owner}` only; the Policies page fans
   out one call per owner (platform + every client). Add `GET /api/function-policies` (reach-scoped,
   paged) and an `updatedAt` on `PolicyResponse`, which has no timestamp on the wire.
2. **No read of a domain by hostname.** `GET /api/function-domains` is an owner-scoped list; the
   detail drawer carries the owner across navigation as a query parameter and a bare deep link to
   another owner's domain does not resolve. Add `GET /api/function-domains/{hostname}`.
3. **`POST …/versions`'s 400 under-documents its codes.** `functions.openapi.json` lists
   `ARTIFACT_REF_REQUIRED, MANIFEST_REQUIRED, ENDPOINT_INVALID`; the operation also answers the
   manifest-validation family (`MANIFEST_INVALID`, `MANIFEST_UNKNOWN_FIELD`, `RUNTIME_INVALID`, …
   per `function-registry.md` §4.3). The conformance test only checks codes it exercises — extend
   O4 to drive one manifest-validation refusal so the description must name it.

Each is a small `main` unit; the SPA already handles the codes generically, so none blocks it.

## `fn config set` / `fn secret set` on a function that does not exist yet (2026-09-22) **DONE 2026-09-22 (`8ff9270f`).**

They answer `Function_NOT_FOUND`, so the natural order — set the values, then `fn deploy` — fails,
and the developer must `fn publish` first to create the function, set the values, then `deploy`
to promote. Found walking `examples/function-hello` on a fresh platform. Fix: the two commands
create the function the way `publish` does (`Publisher.ensureFunctionExists`, honouring
`--no-create`), so values can be set before the first publish. Small `fcdev` unit; pin with a
test that `config set` on an unknown address creates it and stores the value.

## Function hosts that stopped without deregistering accumulate (2026-09-22) **DONE 2026-09-22 (`82f91318`: purged after a day from the heartbeat).**

A host's id is its process (`fcdev-<pid>` locally; a task id in ECS), and a host that stops
without deregistering — every `fcdev` restart, every ECS task replacement — leaves its row with its
last heartbeat, shown `stale` after the live window. Inert (desired state and routing ignore stale
hosts) but the list grows for ever. Purge rows stale for more than a day, from the reaper or the
heartbeat handler.

## The function settings routes should expose the CANDIDATE version's declared keys (2026-09-22) **DONE 2026-09-22 (`82f91318`: `?version=`, `declaredBy`).**

`GET /api/functions/{address}/config` and `…/secrets` compute `declared`/`missing` from the LIVE
manifest, but `PromoteVersion.requireSettingsPresent` checks the candidate's, so before a
function's first promote the routes say nothing is declared while promote refuses with
`SETTINGS_MISSING`. The SPA (H5, `FunctionConfigSecretsTab.vue`) reconstructs the union from
`listVersions` + one `getVersion` per version on every tab load. Fix on the server: an optional
`?version=` on both routes, or `declared` computed over live ∪ newest PUBLISHED/READY, and
`missing` against the candidate. Then the SPA drops its N+1.

## Function domains: zone claims and alias prefixes (owner rulings 2026-09-22, queued after the backlog above)

Owner wants subdomain-per-app URLs (`myapp.mybusinessdomain.com`) and preview hostnames per alias.
Ruled:

1. **Zone claims.** A claim names a zone (`mybusinessdomain.com`), verified once by a TXT record
   at the zone; every hostname under it belongs to the claimant. Route entries name any hostname in
   a zone the owner holds. Whether the exact-hostname claim survives beside it (the `.localhost`
   dev case) is a design choice for the spec, not a ruling.
2. **Alias prefixes, opt-in per route.** A public route lists the alias prefixes it accepts
   (`"aliasPrefixes": ["qa", "staging"]`); with none listed the hostname matches exactly and
   nothing else. With prefixes, `qa-myapp.mybusinessdomain.com` serves the `qa` alias's version of
   the function that owns `myapp.mybusinessdomain.com`; a prefix without a matching alias is 404;
   an exact claim always wins over a prefix match.
3. **Aliases are HTTP-only.** Subscriptions, schedules and the dispatch pool stay bound to `live`;
   a prefixed hostname is a preview environment for HTTP traffic.

Also queued with it, the manifest authoring aids: a JSON Schema for `manifest.json` served by the
platform and committed, a validate/dry-run route returning the promote plan, an SPA form that edits,
validates and exports the manifest (and publishes with it), and `fcdev fn init`. The manifest file
stays the source of truth; the UI is an editor.

Production side (owner, IaC): a wildcard certificate and a `*.mybusinessdomain.com` rule to the
function hosts.

## function-host intermittent failures recurred under contention (2026-09-22, evening) — ROOT CAUSE FOUND 2026-09-24

**Cause:** tests bound the host on the wildcard (`0.0.0.0:0`) and dialled `127.0.0.1`. On macOS,
with SO_REUSEADDR (Netty's and the JDK's default), a wildcard port-0 bind can land on a port a
`127.0.0.1` listener already holds — measured 200 in 20,000 against 200 held ports (the reverse
order never collides) — and the kernel hands `127.0.0.1:port` connections to the more specific
listener: the harness's fake control plane / JWKS server answering **404** (h4), or one closing,
answering **nothing** (h3's "received no bytes"). Linux refuses that bind, and production uses
fixed ports, so it is test-only. **Fix:** every function-host test binds the host on `127.0.0.1`
(`Options#withHost`, both listeners); `LoopbackBindTest` pins that a loopback bind refuses a held
port and that no test binds the wildcard. The history below is kept for the record.


During the backlog units, a `-pl fcdev -am` reactor run in a worktree (concurrent with the main
tree's server suite) saw `FnHttpServerPublicListenerTest` fail once and `FnHttpServerTest` fail once,
both passing on rerun. The worktree was removed before its surefire reports were kept, so the
response bodies are lost — **keep `target/surefire-reports` of a failed run before removing a
worktree.** `FnHttpServerTest` h4's assertion prints the body since 2026-09-22; the next occurrence
names the code. Candidate sources of a 404 on the private listener are enumerated in
`FnHttpServer` (`NOT_FOUND` for a non-`/functions` path, `FUNCTION_NOT_FOUND` when `liveEntry` is
null, `ENDPOINT_NOT_FOUND`, `VERSION_NOT_AVAILABLE`) — reconcile is synchronous in the harness, so
`FUNCTION_NOT_FOUND` would mean the document was not set; the others are deterministic on the
fixture. Looped uncontended 8× (`FnHttpServerTest` + `FnHttpServerPublicListenerTest`, 58 tests): one
failure in run 3, a DIFFERENT symptom — `h3_secretRotationWindow`'s FIRST request (a POST, fresh
`HttpClient`, 51 ms after `start`) died with `HTTP/1.1 header parser received no bytes` / `EOF
reached while reading`: the server closed the connection without answering. Report kept at the
session scratchpad `fnhttp-loop/reports-run3`. Ruled out since: (a) the JDK client's `Upgrade: h2c`
first-request attempt against the host's `setHttp2ClearTextEnabled(true)` — 1,200 first requests
(GET/POST × HTTP/2-default/HTTP/1.1) against a warm host, 0 failures; (b) a just-started host with a
lazy, not-yet-loaded function — 120 fresh hosts (start → first POST → close), 0 failures. Still
open: something that closes an accepted connection early only under a full class run (a previous
test's `close(3s)` still tearing down its Vert.x on shared event-loop threads? the JVM's port-0
reuse handing a just-freed port to the next server while the old `HttpServer.close()` is still in
flight?). Next: run the class with `-Dvertx.logger-delegate-factory-class-name` debug on the
connection lifecycle, or make the harness log the server's `connectionHandler` close events with
the port, and diff against the failing test's port.

## Declared access policies on the operation envelope (proposed 2026-09-23, **deferred by owner**)

A task proposed replacing the 130 `Authorize.publicAccess()` sites with a typed, declared
`AccessPolicy` enforced once in the envelope, a `Public` allowlist, an ArchUnit rule and a
registry-driven "no principal ⇒ forbidden" test, plus Postgres RLS via a `TenantTx`. Assessment:
the premise "most operations are effectively public" is false — authorisation lives in the HTTP
layer (348 `Auth.scoped` handlers, 373 `Checks.*` gates over 445 routes, audited against Go on
2026-09-13) and in the reach checks inside `execute`; the real defect is that the envelope's
`authorize` step is dead and authorisation is split across three places. Worth doing later, in
this shape: `Requires(Permission…)` + a named `reach` step on the operation, enforced in the
envelope; `Public` allowlisted; missing policy a boot failure; ArchUnit forbids `Checks` outside
the envelope; a **route-registry** test (not hand-written) calls every route with no principal and
with a no-reach client principal. **RLS/TenantTx: not for the platform** — the reach model is not
one-tenant-per-request (anchor, client links, applications, platform-owned NULL rows), the schema
is shared with Go, and it would duplicate the reach check rather than add one; it fits a function's
own database. Owner 2026-09-23: defer.

## Pool-ownership rule withdrawn; DNS verification of domains withdrawn (owner clarification 2026-09-23)

Owner: "tenants can't deploy their own functions. We deploy our own functions that run for our
tenant solutions." Consequences: (1) the pool-ownership spec (`9da065ab`, a client's functions
confined to granted pools) rested on untrusted client code in the shared JVM — withdrawn, spec
deleted; a pool is an operational placement choice, not a security boundary. (2) TXT verification
of domain claims defended one tenant from another claiming its domain — with one operator claiming
every domain there is nobody to defend against; every claim is verified at claim time and the DNS
path is removed (`docs/spec/function-domains-no-dns.md`). Model, for the docs: functions are the
operator's trusted code; tenants never deploy code; a client-owned function is one that runs for
that tenant's solutions; the signer allow-list is the operator's CI; ceilings protect a pool from a
mistake.

## Go catch-up owed: two small auth commits (2026-09-23) — LANDED 2026-09-23

Both ported. Correction to item 1 below: `OidcClients.invalidate` **is** wired — `Platform`
passes `oidcClients::invalidate` as `IdentityProviderApi`'s change hook (the earlier grep failed
on a zsh glob). The real gap was cross-node, plus rotation: `sameSecret` compared only whether a
secret was present, so a rotated secret saved on another node kept the old one until the 10-minute
TTL. The cache is now one entry per provider, reused only while the `Shape` (issuer, client id,
secret ref, multi-tenant, issuer pattern) is unchanged; `invalidate` stays, to drop a deleted
provider's entry early. A provider with no secret logs an INFO. Pinned by `OidcClientsCacheTest`
(counts discovery round-trips; each Shape field, the shape check and `invalidate` mutant-checked).
Item 2: INFO on the ineligible branch and on the approval-queue branch, principal id + reason
class, never the address; pinned by `PasswordResetApiTest.aSkippedResetRequestLogs…` (both lines
and an address-leak mutant killed). Only the federated clause is reachable today (`findByEmail`
returns USER principals with an address); the other two stay as defensive clauses.


Go `main` moved past the 2026-09-22 catch-up (`b60d75c`). Two commits are not in Java; both were
checked against the Java code on 2026-09-23 and both have a real Java-side gap:

1. **Go `7ab071c` — an edited IdP secret takes effect on the next login, not the next restart.**
   Go's bridge cached the resolved OIDC client per `issuer|clientId` with no invalidation and no
   TTL; a secret saved after first use never reached the process (prod: `AADSTS7000218` until
   restart). Go widened the cache key to cover every field that shapes the client (issuer, client
   id, secret ref, multi-tenant, issuer pattern) and logs a provider that resolves with no secret.
   **Java is partly protected and partly not**: `OidcClients.client(idp)` re-checks `sameSecret` on
   every cache hit and has a TTL, so the exact prod symptom does not reproduce — but the key is
   still `issuerUrl|clientId`, so an edit to `oidcMultiTenant` / the issuer pattern / other shaping
   fields serves a stale client until the TTL expires, and **`OidcClients.invalidate(String
   identityProviderId)` has no caller anywhere in `server/src` (main or test)** — ruling Q1's
   mechanism is dead code. Fix: widen the key as Go did (then `invalidate` becomes unnecessary and
   should be deleted rather than left uncalled), or wire `invalidate` into the IdP update
   operation. Pin with a test that edits each shaping field and asserts a fresh client.
2. **Go `89f1a08` — a reset request for an ineligible account logs why no email was sent.**
   `PasswordResetApi.request`'s ineligible branch (`!p.isUser() || p.isFederated() || no email`)
   returns silently, so "no reset email arrived" cannot be told from a delivery failure; the
   neighbouring branches (unknown address, send failure) both log. Add an INFO with the principal
   id and the reason class, never the address. The strong-factor "queued for approval" branch
   above it returns silently too — same treatment.

Go `c8ebd8e` is a sqlc regeneration (no behaviour). Go `c224f1a` and `359df6b` are already in Java.

## Config permissions landed; two follow-ups (2026-09-23)

`docs/spec/config-permissions.md` (ruling CFG-PERM-2026-09-23) is on main: config reads need
`platform:admin:config:view`, writes/deletes `platform:admin:config:manage` (replaces `update`, V17
renames existing rows), no anchor pass, secrets unmasked only for `manage`; the three access-grant
routes are gone; an invite sign-in writes a `USER_LOGIN` SUCCESS row.

- **Drop `app_platform_config_access` after cutover.** Java no longer reads or writes it; kept only
  because Go shares the schema. One migration, once Go is off.
- **SDKs regenerated from the Java lockfile (2026-09-24).** Both copies now match it: the three
  access endpoints are gone and `syncConnections` / `signDispatchJob` plus several additive fields
  (connections, dispatch jobs, service accounts) arrived — they had been generated from Go's spec.
  The Laravel generator is pinned (`jane-php/open-api-3` `7.11.2` in `require-dev`; the untracked
  `composer.lock` is right for a library, and the pin makes a fresh checkout reproduce the committed
  code — a floating 7.x rewrites every endpoint). **Not released:** the owner picks the bump
  (removing endpoints is breaking; on 0.x that is a minor: TS 0.11.27 → 0.12.0, Laravel 0.10.26 →
  0.11.0) and runs `scripts/release.sh`. The CI staleness check promised by `docs/sdk-release-plan.md` §2.2 is now the `sdks`
  job in `ci.yml` (first run on GitHub unobserved — watch it after the push).

## `flowcatalyst-function-api` is not published anywhere (2026-09-24) — RULED: fcdev ships it

Owner: fcdev rather than a Maven repository. `fn init` writes the jar (zipped from function-api's
classes at fcdev build time) and a minimal pom into the project's `lib/m2`, which the generated pom
declares as a file repository; versioned as the fcdev release that carried it. A test runs `mvn
package` on a fresh scaffold. Native fcdev includes the resource (`fn-init/.*`) — not yet checked
with a native build. Original question:

`fcdev fn init` generates a standalone Maven project depending on `flowcatalyst-function-api`
(`provided`) at the reactor version, but nothing is published to a Maven repository
(`docs/sdk-release-plan.md` §4.2: the Java SDK is bump-and-tag only), so an author outside a
platform checkout cannot build it; `fn init` says so and prints `mvn -pl function-api install`.
Owner question: publish `function-api` (GitHub Packages, the plan's rejected §2.4 option, or
Maven Central), or ship its jar with fcdev (`fn init` writes it and the pom references it)?

## fcdev 0.9.0: this repo owns the release stream (2026-09-24, **owner action needed**)

`docs/spec/fcdev-release-0.9.md` landed: `VERSION` → `0.9.0` (above Go's 0.8.38, so `fcdev upgrade`
sees it as newer), `UpgradeCommand.DEFAULT_REPO` → `flowcatalyst/flowcatalyst-javalin`,
`scripts/release.sh dev <bump>` / `make release-fcdev` tag `fcdev/vX.Y.Z`, and
`.github/workflows/release-fcdev.yml` now fails the release if the pushed tag's version doesn't
match `VERSION` at that ref. Also new: a native `fcdev start` with functions on and no host jar
statically resolvable fetches `fc-fnhost.jar` on first use, for its own version, checksum required
(`UpgradeCommand#fetchOwnFunctionHostJar`); `fcdev upgrade`'s own `fc-fnhost.jar` fetch now requires
the checksum too (previously optional); and the native child-process branch now refuses a `java`
below feature version 25 (`FnHostLauncher#MIN_JAVA_FEATURE_VERSION`) instead of launching it and
dying with `UnsupportedClassVersionError` in the child.

- **Owner action: disable the Go repository's `.github/workflows/release-fcdev.yml`.** Both
  repositories' workflows publish under the identical `fcdev/v*` tag prefix and identical asset
  names — leaving Go's enabled risks two different binaries racing to publish the same release once
  both remotes exist and both are tagged.
- **Owner action / user note:** a developer still on the Go-built `fcdev` binary either reinstalls
  from this repository, or sets `FC_DEV_UPGRADE_REPO=flowcatalyst/flowcatalyst` to keep pointing
  their existing binary's `fcdev upgrade` at the Go repository's releases.
- Not done here (out of this unit's scope): the manifest authoring aids (JSON Schema + dry-run
  route + SPA form/export + `fn init` improvements) and the function-host intermittent test failure
  — both already tracked elsewhere in this file / `docs/STATUS.md`.

## Function database connections can go back to the pool mid-transaction (2026-09-24, W4 review)

JVM and Wasm functions alike: a statement that opens a server-side transaction in autocommit
(`BEGIN` sent as SQL, or `BEGIN; INSERT …` as one string) returns its pooled connection with the
transaction still open — Hikari rolls back on return only when *it* saw autocommit off — so the next
borrower's statements run inside it, hold its locks, and may never commit. Session state (`SET
search_path`, `SET statement_timeout`) persists the same way. Not adversarial under the trust model
(all functions are the operator's), but a cross-invocation footgun. Cheap guard, one place for both
runtimes: on return, check pgjdbc's `PgConnection#getTransactionState()` and `ROLLBACK` if not
`IDLE`, and `DISCARD ALL` / `RESET ALL` if session settings matter — a `DbPools` wrapper around the
`DataSource` it hands out. Also from W4: no per-call connection cap, so a guest holding `poolSize`
open transactions waits on itself until the deadline.

## Overnight review 2026-09-24/25 — owner questions and deliberate deferrals

Four read-only reviews (auth surface, functions + delivery, Result/exception use, duplication) ran
overnight; fixes landed per `docs/spec/security-fixes-2026-09-24.md` and in the commits of that night.
What is left needs a ruling or was judged not worth changing:

**Owner questions**
1. **SSRF policy for delivery URLs** — subscription endpoints, scheduled-job and ingest target URLs
   are only checked as `^https?://.+`; a tenant can target internal addresses (loopback, link-local
   incl. 169.254.169.254, the function host's private listener) and read up to 64 KiB of the
   response back through the attempts API. Block private/link-local/ULA outside dev mode, checked at
   create and against the resolved address at send (DNS rebinding)? Deployed dispatch uses internal
   Service Connect aliases — those would need an explicit allowance.
2. **Router auth open by default** — with `FC_ROUTER_AUTH_USER` unset, `/messages`, breaker resets
   and `/api/test/*` are open on the API port (documented, `Env.java`). Refuse to start outside dev
   mode unless `AUTH_MODE=NONE` is explicit?
3. **nOAuth on multi-tenant OIDC** — with a multi-tenant Entra IdP and no tenant pin, identity comes
   from the mutable `email` claim (and `preferred_username` fallback; `email_verified` ignored).
   Require a pinned tenant, and/or `email_verified`? (Entra often omits `email_verified`.)
4. **Principal sync `passwordHash`** (S1) — applied only on create, and on an existing principal only
   for a super-admin caller. Confirm, or drop it for existing principals entirely.
5. **Refresh replay leeway** (S2 review) — 10 s: a token rotated out moments ago may be presented
   again (SDK requests racing at expiry, a retry after a lost response) and gets a sibling in the
   same family; after that it is reuse and revokes the family. Strict alternative: every second
   presentation revokes (signs out users of the Laravel/TS SDKs, which refresh without a lock).
6. **`/oauth/authorize` Bearer fallback** (C-Q25) — kept but narrowed to session tokens. Drop it?
7. **2FA email-challenge budget** — reuses the password-reset policies (20/h per IP, 5/h per
   address). `/auth/password-setup/request` now spends the same budgets under its own keys
   (`dbe3ad9c`) — revisit if the numbers change.
8. **Unmapped email domain at `/auth/oidc/login`** answers 500 `OIDC_RESOLVE_FAILED` (spec
   auth-identity §4.3, Go parity) — a user's typo, not a server fault. 400/404 instead?
9. **Function outbound HTTP response cap** — `AllowlistHttpCaller` reads the whole body; an
   allowlisted host returning GBs exhausts the host. A fixed cap changes the author contract.
10. **Listener idle timeouts** — neither the platform nor the function host sets one; a slow-body
    client holds a connection indefinitely. A plain idle timeout would cut long invocations that
    are working silently, so it needs a request-phase-aware design.
11. **Public API shapes** — `EventEmitException` (function-api: an expected outcome as an unchecked
    exception) and the SDK's `WebhookSignature.verify` (void + throw) contradict CONVENTIONS §8 but
    are published contracts mirrored in the TS/Laravel SDKs.
12. **Versioned call to a not-yet-prepared candidate** answers 404 `VERSION_NOT_AVAILABLE` like a
    refused one; a 503 + Retry-After would tell a caller to wait.

13. **Seed roles lost provisioning** (S1) — provisioning a service account, its roles and its
    token now need `SERVICE_ACCOUNT_CREATE`/`UPDATE`, which no seed role but super-admin holds.
    Give them to `platform:admin` / `iam-admin`?
14. **No ceiling on role assignment** (S1) — anyone with a user-write permission may assign any
    role, `platform:super-admin` included (and `SERVICE_ACCOUNT_UPDATE` may grant an SA super-admin
    and mint for it). Only assign authority you hold? And should `USER_ASSIGN_ROLES`, not the
    user-write any-of, gate role routes?
15. **Cross-application role permissions refused** (S1.5) — a role may hold only its own
    application's permissions; an existing SDK role that grants another application's permissions
    will fail its next sync. Check live roles before deploying.
16. **`/bff/event-types/sync-platform` takes `applicationCode` from the body** and syncs the
    platform definitions into it with `removeUnlisted` — anyone with `EVENT_TYPE_SYNC` (in practice
    super-admins) can wipe an application's event types. Restrict to `platform`?
17. **S3 owner notes** — fan-out jobs to a subscription with no account/connection are still signed
    via the event type's application prefix (a client admin can ingest an event of another app's
    type and receive it signed); the single-client default for an absent `clientId` on ingest;
    `/sign` now 403 when the signer is out of reach; duplicate-id 409 vs a per-item result; the
    duplicate check covers only the write table. Specs `subscription.md` / `connection.md` still say
    "not validated" for the account reference.
18. ~~**Spec docs stale after S1**~~ — swept 2026-09-25 (`0ec420b9`, `dd8089b4`): principal,
    bff, application, serviceaccount, auth-core §9, subscription, connection.
19. **Go**: the service-principal-id-as-account-id defect exists in Go's connection sync too (no
    hand-off written — Go is being retired).

**Reviewed and deliberately left**
- `MarkVersionReady`'s `VERSION_NOT_PUBLISHED` conflict caught in the heartbeat: one named constant,
  one documented catch, inside the envelope's own contract; a sealed result would mean redesigning
  `TxOperation`.
- `FunctionAddress.parse` / `DnsLabel.parse` catches in `FunctionControlApi` / `CreateFunction`:
  validation-error remaps at the envelope boundary, not control flow.
- `QueueMetrics` / `ProcessingTimeMetrics` positional `long`s (the duplication review): every call
  site agrees today; low value.
