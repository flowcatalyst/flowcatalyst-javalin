# Java mirror of Go F9 follow-up and the F8 extras (2026-10-12)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` (F8, "F9 follow-up") and Go commits
`45537af` (071), `4c23adf` (072), `b3d2c45` (docs) for part A, and `09b33e8`, `70c9ca7`, `e08eee4`,
`8b1cb0f` for part B. Predecessors: `2026-10-11-event-catalogue.md` (found the 22 older schemas
that reject real output) and `2026-10-11-platform-bug-fixes-2.md`. Every commit is on `main`, not
pushed.

## Commits

| Commit | Change |
|---|---|
| `91aaa8d3` | A1. The 16 catalogued codes nothing emits are dropped; V26 removes them from existing databases and archives their bindings. |
| `8a6c20ff` | A2. 28 older schemas take Go's corrected forms; V27 replaces them on existing databases while each is still as seeded. |
| `510e250e` | B1. The service-account token mint is bounded by the role ceiling. |
| `f161e287` | B2. Service-account provisioning is bounded by the role ceiling. |
| `fb5771b8` | B3. The application principal sync is bounded by the role ceiling too. |
| `37993383` | B2 follow-up. The three function-host integration tests' provisioning admin holds the real application-service permissions. |
| (this file) | Ledger. |

## A1. Unemitted event types pruned (`91aaa8d3`)

**The set.** Catalogued minus emitted, using the `EmittedCatalogueTest` scan, is exactly Go's 16:

- `platform:iam:role:{created,updated,deleted}`, `platform:iam:roles:synced`
- `platform:iam:anchor-domain:{created,deleted}`, `platform:iam:auth-config:{created,updated,deleted}`
- `platform:admin:idp:{created,updated,deleted}`, `platform:admin:edm:{created,updated,deleted}`
- `platform:iam:user:password-reset-requested`

It was checked three ways:

- **Whole-repo grep** (excluding `target/`, `node_modules/`, `.git/`). Each code appears only in
  `PlatformEventTypes`, `PlatformEventSchemas`, the go-seed fixture and the go-mirror ledgers. The
  `platform:iam:anchor-domain:*`, `auth-config:*` and `role:*` hits elsewhere are permissions
  (`…:view`, `…:manage`, `…:update`). No SPA, SDK or test reference was found.
- **History.** `git log -S` finds each code only in the bootstrap `0343aded`. There it appears only
  in the seed, its fixture and `docs/spec/seeder.md`. No Java code ever emitted one.
- **Instance audit.** This is the capture below: no event of the 16 codes was emitted in a full
  server suite run.

The catalogue is now 140 codes. These are Go's 138 minus its 21 codes Java does not emit, plus
Java's 23 own codes (`2026-10-11-event-catalogue.md`).

**Test.** `EmittedCatalogueTest.everyCataloguedEventTypeIsEmitted` is Go's
`TestEveryCataloguedEventTypeIsEmitted` (catalogued ⊆ emitted). It sits beside
`everyEmittedEventTypeIsCatalogued`. Before the fix it failed, listing the 16 codes.

**V26** (`V26__prune_unemitted_event_types.sql`) is Go 071's Up SQL statement for statement:

- It creates `msg_subscription_event_types_archive` (`IF NOT EXISTS`, so either platform may run
  first) and removes the 16 rows with their schema versions in one statement of data-modifying
  CTEs.
- A binding that names one of the codes, or points at one of the rows, is moved to the archive
  with reason `071_event_type_not_emitted`. It is not re-pointed.
- Wildcard bindings and stored events are untouched.
- Flyway has no Down. Go's 071 Down restores the bindings.

Tests and supporting changes:

- `PruneUnemittedEventTypesMigrationTest` runs on a scratch database. It inserts all 16 old rows
  plus one emitted row, and bindings by row and code, by code only, wildcard, and to an emitted
  type. It checks that the types and schemas are gone and the emitted type is kept, that the two
  bindings are archived exactly as they were and the others kept, that the seeder does not add the
  rows back, and that a re-run is a no-op. It also checks that the SQL names exactly the 16 codes.
  Mutant: archive step dropped; the test fails.
- `SchemaFingerprintTest` and `GoAdoptionTest`: the Go fingerprint fixture is at goose 57, before
  071, so the archive table's nine lines are named exactly (`ARCHIVE_TABLE_LINES_GO_071`). They are
  asserted present in Java and absent from the fixture. Drop the list when the fixture is
  re-captured from Go at 071 or later.
- Other updates: jOOQ regenerated (`MsgSubscriptionEventTypesArchive`); `fcdev fresh` truncates the
  archive, as Go's does; migration counts pinned in `MigratorTest` and `GoAdoptionTest`.
- The go-seed fixture loses the 32 rows (16 `eventtype`, 16 `schema`), and its catalogue counts go
  from 156 to 140.

## A2. 28 older schemas fixed (`8a6c20ff`)

**Test first.** `EmittedEventSchemaTest` now samples every catalogued code: 140, previously the 81
added for F9.

- It builds each event record twice: with every component set, and with the components its
  operation can leave null unset (the per-code `MAY_BE_ABSENT` map, read off the factories).
- It validates `data()` through `Json.MAPPER` against the schema.
- The schema's optional properties must be exactly the `MAY_BE_ABSENT` set.
- New `MAY_BE_ABSENT` entries for the older codes: `client:deactivated.reason`;
  `eventtype:created.{description,clientId}` (`clientId` is never set since F2);
  `eventtype:updated.description`; `eventtype:schema-finalised.deprecatedVersion`;
  `{connection,subscription}:synced.clientId`;
  `user:logged-in.{identityProviderCode,federatedClaims}`.

Before the fix it failed on **27 codes**:

- The 22 that the previous capture found rejecting real output: dispatch-pool `poolId`, eventtype
  `specVersion`, `user:created` fields, and so on.
- Five more that accepted real output but were imprecise:
  - `user:updated`: `name` optional, `email` never sent.
  - `user:deactivated`: `reason` never sent.
  - `application:updated`: `description` never sent, `name` optional.
  - `connection:deleted`: `clientId` never sent.
  - `eventtype:updated`: `name` optional.

**Fix.** Java's data for every one of them is Go's, so each takes Go `4c23adf`'s corrected schema
verbatim. `platform:iam:client:applications-updated` takes it too: Go's lists became nullable, and
Java's `[]` is admitted either way. That makes Go's 28 codes exactly. `optU32` lost its last
user.

`SeederTest.specVersionsMatchGo` pins the Java seed equal to Go's corrected forms. The fixture's 28
`schema` rows are copied verbatim from 072's `new_schema` values.

**V27** (`V27__platform_event_schema_fixes.sql`) is Go 072's Up `UPDATE` verbatim. It replaces a
1.0 schema only while the stored schema still equals the seeded one. That is safe on Java
databases for these reasons:

- No commit to `PlatformEventSchemas.java` ever removed a line for these codes; each has had one
  form since the bootstrap.
- Every historical version of the go-seed fixture has exactly one form per code, and it equals
  072's `old_schema` as a JSON tree with array order significant, as `jsonb` compares.
- `SeederTest` pinned the Java seed to that fixture at each commit.

An edited schema is left alone. There is no Down. If Go's 072 has already run on a database, the
stored schema is the corrected form, so V27 is a no-op.

`EventSchemaFixesMigrationTest` covers V27:

- Each corrected schema equals the seed's, and two old forms are spelled out.
- All 28 seeded old forms are replaced and an edited one is kept.
- A re-run is a no-op, and the seeder attaches no second schema.
- Mutant: the as-seeded guard dropped; the test fails on the edited schema.

**Real output.** `PlatformSink.dataJson` was instrumented to append each persisted event's type and
data to a file. The instrumentation was in a throwaway worktree at `8a6c20ff` and was never
committed; the worktree was removed afterwards. The whole server suite was run with it, and every
captured event was validated against the catalogued schema with the same subset validator, in
Python:

- The suite was green: 5,703 tests, 0 failures, 0 errors, 1 skipped.
- 2,384 events of 147 types were captured.
- **All 140 catalogued codes** were captured from real operation runs, with **0 mismatches**.
- **None** of the 16 pruned codes was emitted.
- The three uncatalogued `platform:` types captured are test fixtures:
  `platform:admin:{repo,api,legacy}<run>:seeded`.
- `platform:iam:user:logged-in` was captured this time; the previous capture had missed it.

## B. F8 extras

Java already had the `RoleCeiling` and used it for service-account and user role assignment and
for the platform-route principal sync. It also had the provisioning gate (anchor +
`SERVICE_ACCOUNT_CREATE` + `APPLICATION_UPDATE`). Three gaps remained against Go F8, and each test
below failed with its fix reverted (mutants listed):

| Go | Gap in Java | Commit | Fix | Tests (mutant → fails) |
|---|---|---|---|---|
| `09b33e8` (mint) | `MintServiceAccountToken.mint` flattened the account's roles and minted with no ceiling | `510e250e` | After the account/principal checks and before flattening, the ceiling applies over every role the account holds. Response: 403 `ROLE_ABOVE_CALLER`, "You may only mint a token for a service account whose roles' permissions you hold yourself: <roles>" (Go's text). No token is issued and no `token-minted` event is written. An unwired lookup returns 500 `ROLES`. `mint` now takes the caller and a `RolePermissions` lookup; the API passes `Auth.current()` and `State.rolePermissions()` | `ServiceAccountApiTest.mintTokenIsBoundedByTheCallersPermissions`, `ServiceAccountOperationsTest.mintTokenIsBoundedByTheCallersPermissions` (check stubbed to "nothing above") |
| `8b1cb0f` (provisioning) | The handler had the gate but no ceiling on `platform:application-service` | `f161e287` | `ProvisionServiceAccount.requireProvisionWithinCaller` (Go's `RequireProvisionWithinCaller`) runs in the handler after the gate. It returns 403 `ROLE_ABOVE_CALLER` with the standard ceiling message naming `platform:application-service`. The operation is unchanged | `ApplicationApiTest.provisionServiceAccountIsBoundedByTheApplicationServiceRole` (call removed). The test class now seeds the real role; the gate test's 201 caller also holds the role's permissions |
| `e08eee4` (app-scoped sync) | `SyncPrincipals.requireCeiling` skipped `POST /api/applications/{appCode}/principals/sync` | `fb5771b8` | The ceiling now covers both routes: every role added or removed, `removeUnlisted` included; the whole sync is refused and nothing is written | `PrincipalOperationsTest.anApplicationsSyncIsBoundedByTheRoleCeiling`, `SdkSyncApiTest.principalsSyncIsBoundedByTheRoleCeiling` (exemption restored; `PrincipalApiTest.platformSyncIsBoundedByTheCeiling` fails too) |
| `70c9ca7` (user roles) | none: Java `AssignRoles` already applied the ceiling | — | — | — |

### Divergences (noted, not changed)

- **"(3) target-user reach".** Java already refuses an out-of-reach existing user on both sync
  routes (`SYNC_TARGET_FORBIDDEN`, `Access.administers`; the `removeUnlisted` sweep skips such users
  instead). It also confines the app route to the application's own roles (`ROLE_APP_FORBIDDEN`).
  Go has neither; it is listed in Go's "New findings, not fixed". The only F8 piece Java lacked
  there was the ceiling on the app route, which is what B3 adds. Java therefore stays ahead of Go
  on reach.
- **Where the sync ceiling runs.**
  - Java checks per payload entry inside the loop. Go collects every change and checks once after
    the loop. Both refuse the whole sync before any write.
  - Java's message names the offending entry's roles. Go's names every role above the caller
    across the whole sync.
  - Precedence differs only between errors on different entries: Java reports the first entry's
    error, whether it is the ceiling, `UNKNOWN_ROLE`, `ROLE_APP_FORBIDDEN` or
    `SYNC_TARGET_FORBIDDEN`.
- **Super-admin short-circuit.** Go's `RolesAboveCaller` returns early for `IsSuperAdmin`. Java's
  `RoleCeiling` matches `platform:*:*:*` through `Permission.grants`, with the same result for
  four-segment platform permissions.
- **SPA.** Go's F8 SPA changes (`90e4113`) are not in the Java SPA: `isRoleAboveUser` locks in the
  role pickers, and Provision Service Account is offered only under
  `canProvisionApplicationServiceAccount`. The same is true of Go's F10 SPA change (`5a712d7`). The
  API refusals are in place, so the SPA would surface the 403. This brief covered the server checks
  only. Java has no `/ui`.
- **Rotating a service account's OAuth client secret** (`POST /api/oauth-clients/{id}/rotate-secret`:
  anchor + `oauth-client:regenerate-secret`) hands out a credential with the account's whole
  authority and applies no role ceiling. This is the same gap as Go's open finding, so Java has it
  too. It was not fixed here.

## Verification

- **Seed, migration and catalogue tests**, run after each of A1 and A2:
  `SchemaFingerprintTest`, `MigratorTest`, `GoAdoptionTest`, `PruneUnemittedEventTypesMigrationTest`,
  `EventSchemaFixesMigrationTest`, `EmittedCatalogueTest`, `EmittedEventSchemaTest`,
  `PlatformCatalogueTest`, `SeederTest`, `ClientEventCodesMigrationTest`,
  `SeededSchemaVersionTest`, and fcdev `DevBootstrapTest`. All green.
- **B classes**: `ServiceAccountApiTest`, `ServiceAccountOperationsTest`, `ApplicationApiTest`,
  `ApplicationOperationsTest`, `PrincipalOperationsTest`, `PrincipalApiTest` and `SdkSyncApiTest`
  were green after the fixes.
- **Full reactor, first run** (`mvn clean test` at `fb5771b8`): server green, but **function-host
  failed 6 tests**:
  - `FunctionHostListenerIntegrationTest` ×3, `FunctionHostArtifactUploadIntegrationTest` and
    `FunctionHostReconcilerIntegrationTest` failed because of B2. They provision a host account as
    an admin holding the placeholder `platform:application-service:*:*`, which matches none of that
    role's permissions. The placeholder never mattered before: the later `PUT …/roles` keeps the
    provisioned role, so the ceiling never checked it.
  - `FnHostStartupResilienceTest` (a 404 on `/health`, with no platform API involved) did not
    recur.

  `37993383` gives those admins the seeded role's permissions. The function-host module then
  passed: 485 tests, 2 skipped.
- **Full reactor, `mvn clean test` at `37993383`: BUILD SUCCESS**, 0 failures, 0 errors:

  | Module | Tests | Skipped |
  |---|---|---|
  | server | 5,708 | 1 |
  | function-host | 485 | 2 |
  | fcdev | 274 | 0 |
  | parity | 56 | 1 (`ParityRunTest` skips without a Go source) |
  | sdk | 93 | 1 |
  | usecase | 46 | 0 |
  | function-api | 145 | 0 |
  | eventloop-check | 16 | 0 |

- **fcdev callers.** `StartFunctionsIntegrationTest` and `FnCliEndToEndTest` provision as
  `platform:*:*:*` and were unaffected.

## Not verified

- V26 and V27 ran on embedded scratch databases only (through Flyway on every test database, and
  their SQL re-executed over old-seed rows), never on a deployed database.
- The Go/Java parity harness was not run. The catalogue lists will now differ from Go only by the
  21 Go-only and 23 Java-only codes, since both sides dropped the 16. The 28 schema bodies are
  equal.
- No browser run; no SPA change was made.
