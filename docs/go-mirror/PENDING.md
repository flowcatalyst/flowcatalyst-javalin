# Go -> Java mirror: PAUSED, and what is outstanding

**PAUSED by the owner on 2026-10-16.** Go platform changes are no longer being mirrored into this
repo until the owner resumes. This file records exactly what Java is missing as of Go `bf81422f`
(F21) and Java `81f43a38`, so the mirror can restart without re-deriving the gap. Nothing here is
implemented; this commit changes docs only.

Audited 2026-10-16 from: Go `docs/platform-bug-fixes.md` (every entry F1..F21, including each
"Java mirror"/"Java hand-off" note and "New findings"), the Go git log from `c74650d` (F17) to
`bf81422f`, every ledger in this directory (`2026-10-10-platform-bug-fixes.md` through
`2026-10-16-platform-bug-fixes-11.md`), `tools/sdk-drift.sh` and `tools/frontend-drift.sh` output,
and a grep of the Java classes named below. Items marked "unverified" were read, not run.

## What is already mirrored (so nobody redoes it)

Go F1..F10 and F12..F19 are mirrored (ledgers 1-11; F11 is the other direction: Go copied Java's
function event codes). Also mirrored from the post-F17 Go log: F18 `52c51e6` and F19 `489f52c`
(ledgers 10, 11), `FC_FN_CLI_CREDENTIALS_PATH` `8dd021f` (Java `3fe0649d`), the credential
confirm modal `c38933f` (Java `35bec741`), the compact `ConfirmDialog` `77d35c9` (Java `1cfe715b`),
the `fcdev start` boot-failure Postgres cleanup `dc26ff9` (Java `24fc118e`, equivalent). Go
F-ledger statuses that are "Go-only by design" are listed at the end.

## How to resume

1. Read this file, then `README.md` in this directory, then the Go ledger entry named in the row
   (`../flowcatalyst-go/docs/platform-bug-fixes.md`, "Java hand-off" text) and, for F20,
   `../flowcatalyst-go/docs/wire-contract.md` "Application admin: scoped reads, users and invitations".
2. **One batch per agent, in the order below.** Do not combine batches: each batch ends with its own
   ledger file in this directory (`2026-10-NN-platform-bug-fixes-12.md`, `-13.md`, ...) and one
   commit per logical change on `main`, not pushed.
3. Follow the F-ledger conventions of ledgers 9-11: section "Was the defect present in Java?",
   "Changes" (commit hashes), "Tests (mutation-checked)" (each load-bearing test re-run against a
   mutant of the fix), "Deviations from Go", "Not verified". Port the Go tests named in the table.
4. Verify against the Go docs, not memory: diff the OpenAPI specs (Go `api/openapi.lock.json` against
   `server/src/main/resources/openapi/openapi.lock.json`), read the wire shapes from Go
   `docs/wire-contract.md`, and update `docs/spec/*.md` (`event.md`, `dispatchjob.md`, `role.md`,
   `application.md`, `app-managed-invitations.md`) where behaviour changes.
5. Migrations: Java is at `V33`. The next is **V34**. Go `080` -> Java `V34` (Go numbering ran ahead
   because Go 070-074 are event-type catalogue migrations Java did in earlier batches). Bump the
   counts in `MigratorTest`, `GoAdoptionTest`, `SchemaFingerprintTest`.
6. Re-run `tools/frontend-drift.sh` and `tools/sdk-drift.sh` at the end of any batch that touches the
   SPA or the OpenAPI lock.
7. Tell the owner, per batch, which deployed databases need a migration run and which application
   service accounts must mint a new token (permissions are copied into the token at mint time).

## Unmirrored items

Size: S under half a day, M about a day, L several days. "Go tests" are under
`../flowcatalyst-go/internal/...` and need `-tags=integration` to run there.

| # | Item | Go commit(s) | Size | Depends on |
|---|---|---|---|---|
| A1 | F20 part 1: six application-service permissions, migration, scoped event/dispatch-job reads | `8376f6e1` | M | F18 (done) |
| A2 | F20 part 2: application users and invitations endpoints | `8376f6e1` | L | A1 |
| B | F21: role and permission reads confined to the caller's application | `bf81422f` | M | A1 (shared helper), F18 `application_id` (done, V33) |
| C | `fn.dispatch` function capability | `5796e829`, `dfd52008`, `feb4ef96` | L | none (Java function host) |
| D | Runner identity fix for delete then recreate at one address | `9d017689` (runner part) | S | verify the defect exists first |
| E1 | fcdev: reconcile dev-bootstrap principals' roles every start | `83c6b82d` | S | none |
| E2 | fcdev: `fn delete` with typed confirmation | `9d017689` (CLI part) | S | none |
| E3 | fcdev: connection profiles and the remote-target guard for `fn` | `410b38c4` | L | none |
| F | OpenAPI `x-sdk` tagging, per-SDK app/admin documents, spec stubs for plain-handler routes | `21f9734d` | M | after A, B (tag the new routes) |
| G | Dispatch-job requeue by filter (API + SPA dialog). **Absorbed by K4/K7**: port the final Go shape, not `165feb6d` | `165feb6d`, `5496c3ff` (2026-10-09) | M | see K4 |
| H | SDK copies and generated clients (owner decides release stance) | `a59aab77`, `8376f6e1` (java-sdk resources), `cba73be8`, `1729294c`, `7f60a020` | M | after A, B, F |
| I | Frontend follow-ons (function drawer dispatches, generated types) | `5796e829`, `ddf50c83`, `8376f6e1`, `21f9734d` | S | after A, B, C |
| J | Owner decision: change-only confinement (F12) | `7f87172` | S | owner ruling |
| K1 | Dispatch-job write permissions split (`requeue`, `redrive-completed`, `cancel`, `complete`, `edit-payload`), seed, grant migration (Go 085) | `ad37de21` | S | none |
| K2 | Mandatory `created_at` window on dispatch-job list-shaped reads (`FC_DISPATCH_JOB_MAX_WINDOW_DAYS`, `X-Window-*`, `window{}`) | `ad37de21` | M | none |
| K3 | Requeue status guard (in-flight never; COMPLETED only by confirmed redrive), `REDRIVE` transition, event/audit fields | `ad37de21` | M | K1 |
| K4 | Requeue by filter: preview + run (absorbs G), with window, guard, redrive | `ad37de21` (final shape of `165feb6d`) | L | K1, K2, K3, K6 filter struct |
| K5 | Edit payload `PUT /{id}/payload` + `payload-edited` event + audit masking | `29e8cb11`, `ad37de21` | S | K1 |
| K6 | Event type / subscription / connection filters, `connection_id` on the read projection, index swap (Go 086), cached filter-options | `bbbb4bb3` | M | K2 |
| K7 | SPA sync of the dispatch-jobs area (+ G's SPA, collapsible nav `a7497448`); hand merge, NOT `pull-frontend-from-go.sh` | `29e8cb11`, `ad37de21`, `bbbb4bb3`, `3f40c8d4`, `a7497448`, `165feb6d`/`5496c3ff` | M | after K1-K6 |
| L1 | F34: `token_use` allowlist (api or absent) on the bearer, the session cookie and the function-endpoint verifier | `8165e614` | S | none |
| L2 | F40 + introspect ownership: `/oauth/revoke` only revokes the authenticated client's tokens (`FC_OAUTH_REVOKE_ENFORCE_CLIENT` default true); `FC_OAUTH_INTROSPECT_CLIENT_AUTH_ONLY` default false | `81a6f588` | S | none |
| L3 | F33: IdP role mapping must name an existing role at write (`400 ROLE_NOT_FOUND`); a stale mapping is skipped with a warning at login | `281d40d3` | S | none |
| L4 | F28: service-account `scope` must agree with its client links (`400 SCOPE_REACH_MISMATCH`) | `ebb18542` | S | none |
| L5 | F31: `attemptCount` counts the terminal attempt; scheduled-job sync rollup subject `platform.scheduledjobs.<app>` | `4f2b3908` | S | none |
| L6 | F30: unchanged roles, platform roles, connections, subscriptions are not rewritten or counted as updated | `87cbc30f` | M | none |
| L7 | F29: router-config `ETag`/`304`, 30 s poll default, paced missing-queue recheck, in-process nudge | `0fb7f0c7` | M | pace the recheck before dropping the interval |
| L8 | F41: `FC_OIDC_REJECT_UNVERIFIED_EMAIL` (default off), `403 EMAIL_NOT_VERIFIED` | `dabca1d8` | S | none |
| L9 | F42: `FC_OIDC_ISSUER_PATTERN_STRICT` (default off) + once-per-pattern warning | `dabca1d8` | S | none |
| L10 | F39: `FC_SESSION_COOKIE_SAME_SITE` (lax default; strict; none) on the existing `SessionCookie` | `53031d69` | S | none |
| L11 | F32: amend A2 so another application's code, an unbound credential and an unknown code answer `404 Application_NOT_FOUND`, not 403 | `49ba1494` | S (inside A2) | A2 |
| L12 | Tests only (production code already matches Go): F36 (leeway kept, do NOT remove), F37 concurrency, F43 passkey, F44 fuzz, redirect-matcher matrix, upstream JWKS rotation, kid-less id_token | `377fe770`, `d2827d89`, `26b78d4f`, `a24f549b` | S | none |
| L13 | Decision: F38 gate is `FLOWCATALYST_DEV_MODE` in Java, `APP_ENV` in Go; document or also honour `APP_ENV` | `65e6787f` | S | owner |
| L14 | F45 dependency audit (advisory; Java has its own libraries), JWT leeway 60 s on upstream id_tokens (decision) | `d7114f37` | S | owner |

### A1. F20 part 1: permissions, migration, scoped reads (M)

- **Behaviour/wire.** Six new permissions on the built-in `platform:application-service` role:
  `platform:application-service:event:view`, `:dispatch-job:view`, `:user:view`, `:user:create`,
  `:invitation:view`, `:invitation:manage`. Migration (Go `080_application_service_admin_pages.sql`):
  grants them to the existing role, idempotent, Down removes exactly these. The existing read routes
  `GET /api/events`, `/api/events/{id}`, `GET /api/dispatch-jobs`, `/{id}`, `/{id}/attempts`,
  `/event/{eventId}` additionally admit the application-service view. A caller admitted **only** by
  it is confined to the applications in its `applications` claim: SQL `application = ANY(codes)` on
  the read projections (event owner = first segment of its type, job owner = first segment of its
  `code`); by-id of another application's row is `404` (same as missing); an application-scoped
  caller bound to none, or to ids that resolve to none, is `403 FORBIDDEN`; a console-permission
  holder (`EVENT_VIEW`, `DISPATCH_JOB_VIEW`) is unchanged; an all-applications caller is not
  narrowed; client reach stays on top. One rule, Go `sdk.AppScope.ConfinedCodes`
  (`internal/platform/shared/sdk/app_scope.go`).
- **Java files.** `server/.../platform/seed/Permissions.java` (`APPLICATION_SERVICE`, around line 207:
  append the six), `seed/PlatformRoles.java` (role at ~line 216), new
  `server/src/main/resources/db/migration/V34__application_service_admin_pages.sql`,
  `shared/auth/Checks.java` (add the confinement helper beside `requireApplicationRead`/
  `canReadAllApplications`; Java resolves `AuthContext.canAccessApplication(id)`/`applications()`,
  so the id-to-code resolution needs `ApplicationRepository`), `shared/auth/Permission.java`
  (enum entries), `event/api/EventApi.java` (gates at lines ~88-104), `event/EventRepository.java`,
  `dispatchjob/api/DispatchJobApi.java` (gates at lines ~190-267), `dispatchjob/DispatchJobRepository.java`.
  `msg_events_read.application` already exists (V1 index `idx_msg_events_read_application`); confirm
  `msg_dispatch_jobs_read.application` before relying on it.
- **Go tests to port.** `event/api/app_read_pg_test.go`, `dispatchjob/api/app_read_pg_test.go`,
  `migrate/application_service_admin_pages_pg_test.go`, `shared/auth/app_admin_perms_test.go`,
  `seed/roles_test.go`, `internal/server/app_admin_pages_pg_test.go` (whole stack with a scoped
  bearer). Java side: `EventApiTest`, `DispatchJobApiTest`, `PlatformCatalogueTest` (role/permission
  totals: 214 -> 220), migration tests.
- **Deploy note.** Every application service account must mint a new token to hold the permissions.

### A2. F20 part 2: application users and invitations (L)

- **Behaviour/wire.** New routes, all confined by the A1 rule and by `{appCode}` binding (a foreign
  code, an unbound credential and, for scoped callers, an unknown code all answer
  `403 "Not authorised for application '<code>'"` **(superseded by Go F32, row L11: all three answer
  `404 Application_NOT_FOUND "Application not found: <code>"`; 403 stays only for a missing permission)**):
  `GET/POST /api/applications/{appCode}/users`, `GET .../users/{id}`, `GET .../invitations`,
  `POST .../invitations/{id}/resend`, `DELETE .../invitations/{id}`. Visible users: CLIENT-scope
  users homed at a client the application is **enabled** for (`app_client_configs.enabled`) who hold
  at least one role named `<app>:...`; only the application's own roles are shown. Invitation =
  owned user who is active, INTERNAL, has no password and no external identity; PENDING while an
  invite token is unexpired, else EXPIRED. Invite (`POST .../users`) requires at least one of the
  application's roles (`403 ROLE_APP_FORBIDDEN`, unknown `400 UNKNOWN_ROLE`, none `400 ROLES_REQUIRED`),
  CLIENT scope only, F8 ceiling, source `ADMIN_ASSIGNED`, the client must be enabled for the
  application and reachable by the caller (one 403 answer), `409 EMAIL_EXISTS`, then the existing
  invite mint (replaces earlier tokens) after commit. Revoke = deactivate the user (existing use
  case) then delete the user's `purpose = 'invite'` rows in `iam_password_reset_tokens`; resend of a
  deactivated user is `404`. Expired invitations stay listed. The IAM `platform:iam:user:view`/
  `create` are also admitted on the user routes, confined the same way.
- **Java files.** No `appusers` package exists. Add `platform/appusers/` (API + repository with the
  one visible-set predicate), an `InviteApplicationUser` operation in `platform/principal/operations/`
  (reuse `AssignRoles`' ceiling and `RoleName`; mint through `passwordreset/ResetLinks.invite...`,
  `ResetToken.Purpose.INVITE`), `principal/InviteEmailer.java`, route registration beside
  `principal/api/PrincipalApi.java`, `shared/auth/Checks.java`. Spec: extend
  `docs/spec/app-managed-invitations.md`.
- **Go tests to port.** `appusers/api_pg_test.go` (visible set with ten negative users, filters,
  pagination, binding on all six routes, no sensitive fields, invite/resend/revoke),
  `internal/server/app_admin_pages_pg_test.go`, plus the mutation list in the F20 ledger entry.
- **Owner rulings still marked "to confirm" in Go** (F20 decisions 1-5): whose users, cross-application
  deliveries visible to the owning application, revoke deactivates, invite requires a role, expired
  invitations stay listed. Ask before porting if any changed.

### B. F21: role and permission reads confined (M)

- **Behaviour/wire.** Go before the fix: `GET /api/roles` etc. required the IAM `role:view` only and
  the seeded `platform:application-service:role:view`/`:permission:view` admitted nothing. Now those
  two admit the reads, confined to the caller's bound applications by the A1 rule: `GET /api/roles`,
  `/{id}` (id or name), `/by-code/{code}`, `/by-source/{source}` (filtered), `/by-application/{applicationId}`
  (bound id ok, any other or unknown id = one `403 APPLICATION_ACCESS_REQUIRED`),
  `/filters/applications` (only bound codes), `/{roleName}/permissions` (foreign role = `404`), the
  permission catalog `GET /api/roles/permissions` and `/{permission}` (only permissions whose first
  segment is a bound application code; others `404`). A role is the application's when its
  `application_id` is bound, or, for a NULL id, `application_code` equals a bound code; a row linked
  to another application's id is never matched by code. IAM-view holders are unchanged, even if
  application-bound. `/bff/roles/*` unchanged. No new permission, no migration.
  Also confirm Java's `application:view` reads (`Checks.requireApplicationRead`) are confined like
  Go's `CanReadApplication` (tests only in Go).
- **Java files.** `role/api/RoleApi.java` (every read does `Checks.require(Auth.current(), ROLE_VIEW)`
  at lines ~102-199), `role/RoleRepository.java`, `role/PermissionRepository.java`,
  `shared/auth/Checks.java`, `application/api/ApplicationApi.java` (tests).
- **Go tests to port.** `role/api/app_read_pg_test.go`, `application/api/app_read_pg_test.go`.
  Java: `RoleApiTest`, `ApplicationApiTest`.
- **Ordering.** After A1 (shares the confinement helper). The `application_id` backfill it relies on
  is Java V33 (done).

### C. `fn.dispatch` (L)

Go: the function runner's `dispatch.create` ABI op 11, describe `dispatches: [{target, path}]`,
`POST /control/functions/dispatch-jobs`, publish check `DISPATCH_NOT_OWNED`, per-call checks
(`DISPATCH_NOT_DECLARED`, `DISPATCH_TARGET_NOT_OWNED`, `DISPATCH_TARGET_CLIENT_MISMATCH`,
`DISPATCH_TARGET_NOT_FOUND`, `DISPATCH_TARGET_UNAVAILABLE`), the job written (`kind` TASK,
source `function:{address}`, `dataOnly` default true, `fn.*` provenance metadata). The full
checklist is `../flowcatalyst-go/docs/java-handoff-2026-10-15-function-dispatch.md`; behaviour is in
Go `docs/function-runner-plan.md` sections 5.3, 5.4, 6.3, 8.3 and 8.7. Java's function host is a
different design (JVM functions, `function-api` + `function-host`), so "ABI op 11" becomes a
`Dispatch`-style capability on `function-api` `FunctionContext` (next to `Events`/`OutboundEvent`),
carried by `function-host/.../context/ControlPlaneEvents.java` and `reconcile/HttpControlPlane.java`
to a new `POST /control/functions/dispatch-jobs` in
`server/.../platform/function/api/FunctionControlApi.java` (beside the `/control/functions/events`
route, line ~119), with the manifest (`platform/function/Manifest.java`) carrying the declared
dispatches and `function/operations/PublishVersion.java` refusing foreign targets. Job creation
goes through the existing dispatch-job create path. Go tests: `internal/platform/function/control/dispatch_test.go`,
`internal/functions/control/client_test.go`, `internal/functions/runner/runner_test.go`,
`internal/functions/abi/abi_test.go`. Not idempotent by design (no job dedup anywhere). Wire: new
control route and schemas `Dispatch`, `DispatchRequest`, `DispatchResponse`, `KV` in the lock.

### D. Runner identity fix (S, verify first)

Go `9d017689` (runner.go): deleting and recreating a function at the same address between two
desired-state polls left the old function (old id) in the runner, so the new version stayed
PUBLISHED forever and heartbeats named a function the platform no longer had. Java keys by
`(address, version)` in `function-host/.../reconcile/Reconciler.java`
(`Key(address, version)`, `registry.peek(address)`), so the same defect may exist: reproduce with
delete -> recreate identical bytes, then fix by comparing the desired function id to the loaded
one. Go test: `internal/functions/runner/lifecycle_test.go`.

### E. fcdev (Java: `fcdev/src/main/java/io/flowcatalyst/fcdev`)

- **E1 reconcile (S), Go `83c6b82d`.** An existing `fcdev-*` dev principal was only re-secreted, so
  a dev database created by an older fcdev kept its old role (the fn CLI got `403` on application
  view). Go now makes the fixed dev principals hold exactly the requested role on every start
  (delete other roles, insert, log the change). Java `seed/FunctionDevBootstrap.java` (lines
  ~113-120) and the router-credential bootstrap only `insertInto(IAM_PRINCIPAL_ROLES) ... onConflictDoNothing`,
  so stale roles persist. Go test: `cmd/fcdev/router_bootstrap_pg_test.go`. Java tests:
  `DevBootstrapTest`, `DevBootstrapRouterCredentialsTest`.
- **E2 `fn delete` (S), Go `9d017689` (CLI).** Prints what goes and requires the address typed
  back; `--yes` for scripts; refused on a non-terminal without it. Java `fn/FnCommand.java` has no
  `delete` subcommand (the API `DELETE /api/functions/{address}` exists, `FunctionApi.java` line
  ~182). Go test: `cmd/fcdev/fn_delete_test.go`.
- **E3 profiles (L), Go `410b38c4`.** `fcdev profile` stores named platform URL + env tag + secret
  reference (keychain, `env:`, `file:`, `command:`), never a secret value; `fcdev fn --profile`;
  non-loopback targets without a profile are refused; prod mutations need a typed confirmation (or
  `--yes` plus `allow_noninteractive`); `deploy --dry-run`. Java has no profile concept
  (`fn/FnCommand.java` takes `--platform-url`/`--client-id` directly; `fn/FnCredentials.java`,
  `fn/FnClient.java`); whether Java refuses a non-loopback target is unverified. Go design:
  `docs/fcdev-profiles.md`; tests `cmd/fcdev/fn_profile_test.go`. Check `docs/fcdev.md` here.
- Already mirrored: `FC_FN_CLI_CREDENTIALS_PATH` (`DevPaths.java`, `DevPathsTest`), boot-failure
  Postgres cleanup (`StartBootFailureTest`).

### F. OpenAPI `x-sdk` tagging (M)

Go `21f9734d`: `x-sdk` vendor extension (`app` | `admin`; absent = internal) from an explicit
classification (`tools/dump-spec/sdkclass.go` + golden), spec-only stubs describing the plain-handler
routes SDKs call (dispatch-jobs create/batch, audit-logs batch, `/api/me`, OAuth/OIDC), and
`make sdk-spec` writing `openapi.app.json`/`openapi.admin.json` per SDK. Java has no `x-sdk`
anywhere; its lock is `server/src/main/resources/openapi/openapi.lock.json` and `Makefile` target
`sdk-spec` (lines ~140-143) copies it unchanged. Mirror: classification in the Java spec generator
(not located here; find where the lock is produced), the stubs for plain Javalin routes, the
Makefile split, a golden-classification test. Also tag A2/B's routes. Go tests: `tools/dump-spec/sdkclass_test.go`,
`internal/platform/shared/sdk/spec_test.go`, `spec_pg_test.go`.

### G. Dispatch-job requeue by filter (M)

Go `165feb6d` (API) and `5496c3ff` (SPA), 2026-10-09: `POST /api/dispatch-jobs/requeue-by-filter/preview`
and `/requeue-by-filter` (confirmed count, capped), `dispatchjob/operations/resend_by_filter.go`,
repository filter plan; SPA `RequeueByFilterDialog.vue`, `requeueByFilter.ts`,
`DispatchJobListPage.vue`. Explicitly excluded from the first mirror
(`2026-10-10-platform-bug-fixes.md` line 7) and never done. Java has only `POST /api/dispatch-jobs/requeue`
(`DispatchJobApi.java` line ~73). Go tests: `dispatchjob/api/requeue_by_filter_pg_test.go`,
`dispatchjob/requeue_filter_plan_pg_test.go`, `frontend/tests/dispatch-jobs-requeue.test.ts`.

### H. SDK copies (M, owner stance needed)

`tools/sdk-drift.sh` currently reports the Go `clients/java-sdk` ahead of `sdk/`:
`ApplicationUsersResource`, `DispatchJobsResource`, `EventsResource` (F20), outbox DTOs, sync
classes, `openapi.app.json`/`openapi.admin.json`, and conformance tests; the TypeScript and Laravel
copies also lag (Laravel `admin()` facade, platform-config and client-association resources, Go
`cba73be8`, `1729294c`). The script itself notes `sdk/openapi/openapi.json` is not Go's lock until
the Java lock catches up (A, B, F). Python SDK (`7f60a020`) and the Go SDK (`pkg/fcsdk`) are Go-only.
Pull with `tools/pull-frontend-from-go.sh`-style syncing only when the owner says SDK releases
resume; see `2026-09-14-sdk-copies.md`.

### I. Frontend follow-ons (S)

`tools/frontend-drift.sh` shows 74 differing lines today, mostly pre-existing deliberate
differences (Java-only function pages, Go-only `uiRedirect.ts`). New work caused by the batches
above: the function drawer's declared-dispatches list (`FunctionDetailDrawer.vue`,
`functionHelpers.ts`, test `functions-helpers.test.ts` from `ddf50c83`) follows C; regenerated
`frontend/src/api/generated/types.gen.ts` follows A, B, F. Java's functions SPA diverges from Go's
(its own `generated-functions`), so port by hand, not by rsync.

### J. Owner decision outstanding: F12 change-only confinement

Go confines a role's permissions to its application checking **only what a write adds**; Java
checks the **whole supplied list**, so a non-super-admin cannot re-save a legacy role that holds a
foreign permission (see `2026-10-12-platform-bug-fixes-3.md` "Divergences" and
`2026-10-14-platform-bug-fixes-6.md` line ~148). Go marks it "to mirror if the owner keeps Go's
choice". No work until ruled.

### K. Dispatch-job operator tooling (K1..K7): see `2026-10-19-dispatch-job-operator-tooling.md`

Full hand-off written 2026-10-19 against Go `3f40c8d4`: every endpoint, parameter, default, limit, status and error code,
the SQL guards, migrations 085/086 and the Java numbering (next free `V<n>`; V34 is reserved for A1 if it lands first),
permission codes and role grants, event/schema fields, audit masking, env var, the Go tests to port by name, the Java
classes each change lands in, and the material differences from Java today (notably: Java's requeue is *total* by
documented ruling and is reversed by K3; all three writes are gated on `view`; no window; no requeue-by-filter; the
SPA copy is far behind and must be merged by hand). Behaviour change for existing API clients: an unbounded
`GET /api/dispatch-jobs` becomes the last 24 hours. Recommended order: K1, K2, K3, K5, K6, K4, K7. Go functions work in
the same range (F35, version pruning, `clientCodes`, runner plumbing) is deliberately out of scope (the Java function
runner is on hold). F28..F34 and F36..F45 of the Go ledger, and the Rust-found identity items, were audited against
Java on 2026-10-20: see L below. F22..F27 and F35 are Go function-runner work and remain unaudited by design.

### L. Identity, token, sync and router-config fixes (L1..L14): see `2026-10-20-identity-and-sync-fixes.md`

Audited 2026-10-20 against Go `04b9c564` and Java `d2289701`, every item with the Java file:line it was read at, the
exact Go behaviour to match (status codes, error codes, env switch names and defaults), the Go tests to port by name,
and whether it ships default-on or behind a switch. No migration is needed (Java stays at `V33`; `V34` is A1's).
Already matching Go, no code change: F36 (Java is *ahead*: it keeps the 10 s refresh replay leeway Go declined; do not
remove it), F37, F38 (different gate, L13), F43 (passkey counter, persisted and refused), F44 (Java's `RelativeRedirect`
is stricter), and from the Go vs Rust review: JWT leeway 0 on platform tokens, no bearer cache, password-login timing
equalisation, URL-aware wildcard redirect matcher. Recommended order: L1, L2, L3, L4, L5, L6, L7, L8, L9, L10 (L11
with A2; L12 tests any time; L13 and L14 need the owner). Behaviour changes for existing API clients are listed in
section 19 of the hand-off; the notable ones are `attemptCount` one higher on settled jobs, the scheduled-job sync
rollup event subject losing `.synced.`, sync `updated` counts reflecting rows written, and routers polling every 30 s.

## Known behavioural divergences today (an operator running Java instead of Go)

Application-facing (the framework's M4e application admin pages call the platform as the
application's own service account):

- An application service account **cannot read events or dispatch jobs**: Java `EventApi`/
  `DispatchJobApi` require `EVENT_VIEW`/`DISPATCH_JOB_VIEW` only, so the call is `403 PERMISSION_REQUIRED`
  (Go: `200`, confined to the application).
- **No application user or invitation endpoints**: `/api/applications/{appCode}/users` and
  `.../invitations*` are `404` on Java.
- An application service account **cannot read roles or the permission catalog**: `RoleApi` checks
  `ROLE_VIEW` on every read and the seeded application-service `role:view`/`permission:view` admit
  nothing (same state Go was in before F21). The inverse hazard also holds on Java today: an
  application-bound principal holding the IAM `role:view` reads every application's roles (Go keeps
  that for the console permission too, by design).
- The six `application-service:*` permissions do not exist in Java's seed, so tokens minted by Java
  for an application service account never carry them.

Function service:

- Java functions cannot create dispatch jobs (no `fn.dispatch`, no declared `dispatches` in the
  manifest, no `/control/functions/dispatch-jobs`).
- Possible stuck-PUBLISHED function after delete and recreate at one address (unverified, item D).

fcdev:

- No `fcdev profile`; whether `fn` refuses a non-loopback target is unverified.
- No `fcdev fn delete` (use the API directly or `fn retire`).
- A dev database whose `fcdev-*` principals were created with an older role keeps the old role.

Console and tooling:

- The dispatch-job list cannot requeue "all jobs matching this filter" (only selected ids).
- The OpenAPI lock has no `x-sdk` tags and no per-SDK app/admin split.
- SDK sources in `sdk/`, `clients/*` lag Go's (item H).

Divergences from earlier batches that remain (see each ledger's "Deviations"/"Divergences"):
change-only confinement (J); F14 item 3 message wording for a code-resolved client refusal was
mirrored, F13 and F15 differences are in ledgers 4 and 6; the Java SDKs' models for `skippedRoles`
and `passwordHashIgnored` (ledger 6 line ~201).

Where **Java is ahead of Go** (do not "fix" these by mirroring Go): the IdP role mapping ceiling
(Go "New findings": mappings to `platform:super-admin` are unbounded in Go), the dispatch-job
platform-scoped refusal (`requireWritableClient`; Go "New findings"), and Java's own function event
design (Go adopted it in F11).

Not audited here (older hand-offs the Go side wrote into this directory; their implementation status
is recorded, if at all, in `docs/STATUS.md`): `2026-09-22-router-hol-deferral-handoff.md`,
`2026-09-22-delivery-credentials-handoff.md`, `2026-09-22-catch-up-handoff.md`,
`2026-09-22-stale-sweep-and-reaper.md`. Check STATUS before assuming they are open.

## Go features with no Java equivalent by design

- `/ui` (the Datastar server-rendered console), its CSRF guard, compression, `patchDialogSlot` morph
  fixes (Go bug-fix ledger entries "Dialog slot morph", "ui:" commits) and everything under
  `internal/ui`.
- `internal/schemacodegen` (Go-side code generation from stored schemas).
- The wazero function runner (`internal/functions`, `fcdev fn run`) as an implementation; Java's
  equivalent is the JVM `function-host` (behavioural parity is the requirement, not code parity).
- huma-specific behaviour: `$schema` in responses, `RelaxRequestBodies`, 415-to-400 mapping, the chi
  versus huma route split behind F16's mixed handlers (Java has its own Javalin equivalents,
  mirrored in ledger 7).
- The Go SDK `pkg/fcsdk`, the Python SDK, the Rust SDK conformance suite, and the Go release tooling
  (`make release-*`, SDK version tags).
- The embedded frontend handler (`frontend/embed.go`, `handler.go`) and its staleness trap; Java
  serves the built SPA from `server/.../Frontend.java`.
- Go router and scheduler internals (lane structure, benchmark rig); router parity is tracked by
  behaviour in the 2026-09 hand-offs, not by code.
