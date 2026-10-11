# Java mirror of the Go platform bug-fix second batch (2026-10-11)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md`, "Follow-up: the findings fixed (second
batch)" F1–F7, and Go commits `5d63669`, `0a8df19`, `25c3ed6`, `0ae0988`, `3f0dba4`, `fb36438`,
`62f71d0` (docs), then `e02159b`, `18c0c27`, `ce7a0bb` (F7, landed during this pass). Plus the two
Java-only findings the first mirror (`2026-10-10-platform-bug-fixes.md`) recorded, which the owner
asked to fix. Predecessor ledger: `2026-10-10-platform-bug-fixes.md`.

Procedure per entry, as before: read the Go diff, check whether Java has the defect, fix it to the
same wire behaviour with a test that fails on the old code, and break the fix on purpose to watch
that test fail. Every commit is on `main`, not pushed.

## Owner rulings for this pass (2026-10-11)

- (a) Service-account token/secret rotations are **updates** (`SERVICE_ACCOUNT_UPDATE`); service
  account create/update/delete need **anchor** scope, as Go.
- (b) Role assignment on users stays `USER_ASSIGN_ROLES` (Go moved to it too, F7).
- (c) platformconfig: Java left as is; recorded below.
- (d) Fix the two Java-only findings: `DeleteApplication` reference check (Go's rule and code), and
  the event-type sync applying schemas if Go's does (it does).

## Ledger

| Go | Item | Java had it? | Java commit | Tests (fail before the fix) | Notes |
|---|---|---|---|---|---|
| `5d63669` | F1: scheduled-job `/log`, `/complete` refuse the application's own service account | **Yes** (any of the messaging create/update/delete only) | `71736a7a` | `ScheduledJobApiTest.anApplicationsServiceAccountReportsOnlyOnItsOwnJobsFirings`; mutants: confinement dropped, no-application job admitted | `Checks.requireScheduledJobInstanceReportAny` / `requireScheduledJobInstanceReport`. The messaging grants keep the any-of. |
| `0a8df19` | F2: event-type `clientId` on create dropped; non-anchor scope bypass | **Yes** (same bypass: a client user naming its own client got 201 and a platform-wide type) | `f4055736` | `EventTypeApiTest.createIgnoresTheClientIdAndNeedsAnchorReach` (event carried `clientId`, client create 201), `EventTypeOperationsTest.createNeedsAnchorReach`, `EventTypesBffTest` create; mutant: authorize = public access (4 tests fail) | `CreateCommand` has no client; authorize = `checkScopeAccess(ac, null)`; wire field kept and ignored; lockfile + `types.gen.ts` carry Go's description. Parity scenario `event-types` updated (both sides 403 now). |
| `25c3ed6` | F3: client events catalogued as `platform:iam:client:*`, emitted as `platform:admin:client:*` | **Yes** (same catalogue) | `3dd0ec9b` | `EmittedCatalogueTest` (new: scans every `*Events.java`; fails on the old catalogue), `ClientEventCodesMigrationTest` (rename, ids, bindings, schemas, edited schema kept, re-run, seeder, clash); mutants: no clash guard, no edited-schema guard, no binding re-point | Migration **V25** = Go 070's Up SQL (idempotent; no Down under Flyway). Catalogue 73 → 75. Go-seed fixture hand-edited from Go's seed at `25c3ed6`. Java's known uncatalogued list has **80** entries (Go 79): Java adds its function, reset-approval, token-minted and requeue events and lacks Go's platform-config access grant/revoke and function-runner codes. |
| `0ae0988` | F4: service-account writes outside the anchor-and-permission rule | **Yes**, more so (create/update/delete/deactivate had no anchor check; rotations any of three permissions, no anchor) | `45515f63` | `ServiceAccountApiTest.everyWriteNeedsAnchorReach`, `anApplicationScopedCallerManagesOnlyItsOwnApplicationsAccounts`, `eachWriteNeedsTheOperationsPermission` over all ten write routes; 5 fail on the old code; mutants: confinement dropped, anchor dropped, the mint's confinement call removed | Ruling (a). Handler: permission then anchor (Go's order). Application confinement in `serviceaccount/operations/Access` (`requireWrite`, `requireApplicationReach`) in each operation's authorize phase; the mint calls it from its handler. |
| `3f0dba4` | F5: code-first sync does not store the client identifier | **Yes** | `a3c5e33a` | `SyncConnectionsTest`/`SubscriptionOperationsTest.syncStoresTheClientIdentifier` | One lookup per sync; updated rows not backfilled. |
| `fb36438` | F6: SPA offers Sync Platform Events and role editors to users the BFF refuses | **Yes** (same SPA; Java's BFF has the same gates since `22d57563`) | `9f9a7b82` | `frontend/tests/admin-permissions.test.ts`; mutant: anchor check dropped | Go's diff verbatim except the `DashboardPage.vue` hunk, applied by hand. |
| `e02159b` | F7 API: user role changes need `user:assign-roles` | **No** — Java already checks `USER_ASSIGN_ROLES` on the three role routes and in `AssignRoles` (`principal/operations/Access.requireRoleAssigner`) | — | — | Ruling (b). Go's own doc says no Java change. |
| `18c0c27` | F7 SPA: role controls offered without `user:assign-roles` | **Yes** (same SPA) | `a06f5ef2` | `frontend/tests/admin-permissions.test.ts` "user role assignment"; mutant: predicate always true | Go's diff verbatim except `UserDetailBody.vue` (Java's had no auth store), applied by hand. |
| — (Java finding) | `DeleteApplication` has no reference check | **Yes** | `c9e0dec1` | `ApplicationOperationsTest.deleteIsRefusedWhileAnythingReferencesTheApplication` (each kind alone, the combined message, disabled config removed); mutants: disabled configs counted, disabled configs not removed | Ruling (d). Go's rule (decisions #53/#55): 409 `APPLICATION_HAS_REFERENCES`, Go's message; enabled configs block, disabled ones are deleted with the application. Order: `APPLICATION_ACTIVE`, references, then Java's `APPLICATION_HAS_FUNCTIONS`. |
| `57cf4d3` (older Go) | Event-type sync ignores schemas; tally always zeros | **Yes** | `8023a88b` | `EventTypeOperationsTest.syncAppliesSchemasAsVersionOneAndTalliesThem`, `EventTypeTest.syncSchemaMintsRewritesOrLeavesVersionOne`, `EventTypesBffTest` (second run all unchanged); mutants: schema ignored (old behaviour), plain JSON equality (1 vs 1.0), schema change not an update | Ruling (d). `EventType.syncSchema`; tally on `EventTypesSynced` (not in its data, as Go); the SDK sync sends no schema (as Go). |
| — | Embedded SPA | — | `c0b2143c` | — | `make frontend` at `a06f5ef2` (`frontend.source-commit`). |

## Notes and deviations

- **F4 check order.** Java runs an operation's `validate` before `authorize`, so a confined caller
  sending an invalid body to another application's account gets the 400 where Go answers the 403
  first (Go checks before decoding the body). Same for create: an invalid body from a confined
  caller naming another application is 400 in Java, 403 in Go. Only the precedence of two errors
  differs; nothing is admitted that Go refuses.
- **F3 migration has no Down.** Flyway runs forward only; Go 070's Down (rename back) has no Java
  counterpart. If the Go binary is run against the same database later, its goose 070 is a no-op.
- **F2 leftovers** (as Go): the event-type read paths' client filter (`EventTypeReads`) reads a
  client that is never stored — dead code, left. `EventType.withClientId` removed (nothing sets it).
- **Native-image reachability metadata** (`server/` and `fcdev/native-config`) still lists
  `eventtype.operations.CreateCommand#clientId`; it is tracing-agent output (already stale for
  `clientScoped` on the server side) and was not hand-edited.
- **platformconfig** (ruling c): unchanged from the first mirror's note — Java gates config on
  `platform:admin:config:view/manage` from roles with no per-application confinement; Go confines
  application-scoped anchors (`fcce86c`). Recorded, not changed.

## Go "new findings" that apply to Java too (not fixed, not in scope)

- `DELETE /api/event-types/{id}` is documented "Archive" and the TS SDK calls it `archive()`, but it
  hard-deletes (only an archived or never-published type).
- The emitted-but-uncatalogued event types (80 in Java, listed in `EmittedCatalogueTest`) and the
  16 catalogued-but-never-emitted codes (`platform:admin:edm:*`, `platform:admin:idp:*`,
  `platform:iam:anchor-domain:*`, `platform:iam:auth-config:*`, `platform:iam:role:*`,
  `platform:iam:roles:synced`, `platform:iam:user:password-reset-requested`) — same in Java.
- Service-account role assignment: Java **is** bounded (`RoleCeiling`, `serviceAccountRolesAreBoundedByTheCallersPermissions`),
  unlike Go's finding — a Java strength, nothing to do.
- Not checked in Java this pass: the SPA route permissions no role grants, the dashboard's OpenAPI
  sync exposure, the `GET /api/dispatch-jobs` default size.

## Verification

- Full reactor, `mvn clean test` at `1303c169` (the code of `c0b2143c`; only the embedded SPA's
  source-commit stamp differs): **BUILD SUCCESS** — server 5,694 tests (1 skipped), function-host
  485 (2 skipped), fcdev 274, parity 56 (1 skipped; `ParityRunTest` skips without a Go source), sdk
  93 (1 skipped), usecase 46, function-api 145, eventloop-check 16; 0 failures, 0 errors. (A first
  run failed 3 tests — `MigratorTest`, `GoAdoptionTest` pin the migration count — fixed in F3.)
- Frontend: vitest 29 files / 103 tests passing; `vue-tsc -b` clean; oxlint 0 errors (the one
  pre-existing warning in `useReturnTo.ts`).
- Parity harness, embedded Postgres, free ports, Go binaries built from a clean Go worktree (not the
  owner's working tree): before = Java `2bb1c457` (temporary worktree) vs Go `62f71d0`: **16
  DIFF/ERROR** (14 DIFF, 2 ERROR; `ParityRunTest` failed on the ERRORs). After = Java `1303c169` vs
  the same Go binaries: **10 DIFF, 0 ERROR**, `ParityRunTest` passes; closed 6 (sync-platform
  schema tally/total, the catalogue list at `/items/51` in `S0 smoke` and `bff`, the three
  event-type confinement steps incl. both Go-side ERRORs), **none new**. Against Go HEAD `ce7a0bb`
  (F7): the same 10. The 10 left are outside this batch and were all present before: Java's
  platform role sync removes a role (`bff-sync-platform-roles` `/removed`), the docs index has no
  Go-only `functions` page (×3) and one page's content, an email-domain-mapping update's
  `primaryClientId`, the OAuth client list's application fields, a principal list total (49/50), the
  SA mint token's `name` claim after a rename, one endpoint-URL message wording.
- Mutation checks: every server-side and SPA fix above had its load-bearing test re-run against a
  mutant of the fix (named in each row and commit message); each mutant was killed.

## Not verified

- No browser run of the SPA changes (vitest, vue-tsc, oxlint only).
- V25 ran on embedded scratch databases only (through Flyway on every test database, and its SQL
  re-executed over old-seed rows), never on a deployed database.
- SDK spec copies (`clients/*/openapi`, `sdk/openapi`) not refreshed (F2's lock change is a
  description only), as in Go.
