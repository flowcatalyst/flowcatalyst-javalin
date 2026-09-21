# Spec — application-owned connections, code-first connection sync (owner rulings 2026-09-21)

**The behavioural contract is `docs/go-mirror/2026-09-21-code-first-connections-handoff.md`** — the
owner's hand-off, copied verbatim from `../flowcatalyst-go/docs/`. Read it first; every rule there
binds Java, and this spec does not repeat it. This file adds what is Java's own: where things land,
what the hand-off leaves implicit, the slices, and the tests. It amends `connection.md`,
`subscription.md` and `sdksync.md`; update those three in the slice that changes them (the
"code unique per client … **load-bearing or accident?**" question in `connection.md` §1 is now
answered: per application *and* client, enforced by the database, NULL a real value).

Go reference, `main` at `07184da`: `56aea7c` (subscription sync by `connectionCode` — **Java does
not have this either**), `291ae7a`, `f06ebc2`, `774a18b`, `abcd9fa` (router log line). Go's
uncommitted work at the time of writing is SDK-only (a client-side partial-failure result) and asks
nothing of the server. **Owner, 2026-09-21 (later): the SDK work is committed** — Go `be8a52f` +
`a2c093b` (java-sdk), `f7b472d` (laravel-sdk), `bb660d5` (typescript-sdk), with `bb1b483`/`a10bc5c`
before them — so the SDK copies are slice **K4** (§4a).

Evidence, not authority (CONVENTIONS §8): Go's `connection/operations/sync.go`,
`subscription/operations/sync.go`, `connection/repository.go`, and the `_pg_test.go` files the
hand-off names. Where Go and the hand-off disagree, the hand-off wins and the disagreement is reported.

## 1. Schema — `V12__connection_application_scope.sql`

Adopted from Go's `056_connection_application_scope.sql`, statement for statement, **including both
pre-checks** (a Java-first deploy must refuse a colliding database exactly as a Go-first one does,
naming the groups). Idempotent throughout, so either platform may go first. It must also undo what
`V1__baseline.sql` does on a fresh database: V1 creates `idx_msg_connections_code_client` /
`idx_msg_subscriptions_code_client`; V12 drops them and creates the two `uq_…` expression indexes.
V1 itself is not edited (it is the baseline Flyway checksums).

- `migration.index`, `MigratorTest`, `GoAdoptionTest` (V12 is a **no-op on a Go database at goose
  56** and does the work on one at 55 — see §5), jOOQ regenerated
  (`JAVA_HOME=$(mise where java) mvn -pl server -Pjooq-codegen …` — find the profile in `server/pom.xml`;
  never hand-edit generated code).
- **The Go schema fixture is re-dumped at goose 56** (recipe: memory/STATUS — Docker `postgres:18`,
  Go `fc-server` with every `FC_*_ENABLED=false` until "migrations applied", `pg_dump --schema-only
  --no-owner --no-privileges`, then `-Dfc.regenerateFingerprint=true`). `SchemaFingerprintTest`
  must end with **no** new divergence allowance.
- On `function-service` the functions migration moves to **V13** when this merges (it is unreleased;
  same move as V11→V12 on 2026-09-21), and `FunctionTriggerSync`'s subscription lookups move to the
  three-part key. Not this unit's work — recorded so the merge is not a surprise.

## 2. The three-part key, in one place

`(application_code, client_id, code)`, **NULL is a value**: every lookup compares with
`IS NOT DISTINCT FROM` (jOOQ `isNotDistinctFrom`). `ConnectionRepository.findByCodeAndClient` and
`SubscriptionRepository.findByCodeAndClient` are **replaced** by `findByCode(code, applicationCode,
clientId)` — not overloaded: a two-part lookup left callable is the bug waiting to happen. Every
caller is migrated (`CreateConnection`, `UpdateConnection`, `CreateSubscription`, the syncs, any
BFF/MCP reader). Plus `findByApplicationAndClient(applicationCode, clientId)` on both, NULL client
matching NULL only, and `SubscriptionRepository.findCodesByConnectionId`.

`Connection` gains `applicationCode` (optional) and `source` — a `ConnectionSource` enum
`CODE | API | UI` with a **strict** stored read (`CorruptConnectionException` on anything else: the
column has a CHECK, so an unknown value is corruption, not leniency — CONVENTIONS X-06).

## 3. Operations and routes

As the hand-off states them. Java specifics:

- **Create/update** (`applicationCode`): application existence → `404 Application_NOT_FOUND`; access
  → `403`; duplicate under the new key → `409 CODE_EXISTS`. Update is set-if-provided and re-runs
  the duplicate check **only when the key actually changes** (an update that re-sends the same
  `applicationCode` must not 409 against itself). Responses carry `applicationCode` (omitted when
  null, as the lockfile has it) and `source`.
- **`SyncConnections`** — a `TxOperation`/sync plan like `SyncSubscriptions`; command record named
  `SyncConnectionsCommand` (the audit `operation` column). Order inside execute, pinned because
  each step's error is observable: application found → service account present
  (`400 APPLICATION_SERVICE_ACCOUNT_REQUIRED`) → load owned rows → upsert → removal candidates →
  **all** reference checks before **any** delete (`409 CONNECTION_REFERENCED` refuses the whole
  sync: nothing created or updated either — it is one transaction). A UI-authored row with a listed
  code is skipped silently and its code still appears in `syncedCodes`; it is not counted.
  `service_account_id` is re-stamped to the application's on **update** too.
- **Route** `POST /api/applications/{appCode}/connections/sync` in `SdkSyncApi`, `Group.API_WRITE`;
  permission gate = any of the five the hand-off lists (`Checks.requireAny`); `clientId` resolves by
  id **or** identifier slug → `404`; resolution happens before authorization so the access check
  sees the id.
- **`SyncSubscriptions`**: `clientId`, `connectionCode`, `sharedConnection`, the two-step lookup
  within the explicit namespace, `CONNECTION_MISMATCH`, `CONNECTION_SCOPE_MISMATCH` (both clauses),
  `SHARED_CONNECTION_REQUIRES_CODE`, client-scoped matching/creation/removal. **A request using none
  of the new fields behaves exactly as before** — the existing `SyncSubscriptions` tests stay green
  unmodified except where they construct the repository lookup.
- Events: `ConnectionsSynced` (`platform:admin:connection:synced`, subject
  `platform.connections.<appCode>`, message group `platform:connections:<appCode>`, data
  `{applicationCode, clientId?, created, updated, deleted, syncedCodes}`); `SubscriptionsSynced`
  gains `clientId?`. **Never shadow a `DomainEvent` accessor** (`DomainEventContractTest`).
- Seeds: the five permissions, the two role grants, the event type + JSON schema, the
  `subscription:synced` schema's `clientId` (`additionalProperties: false`). `SeederTest`'s Go
  fixtures (`go-seed-expected.tsv`, and whatever pins permissions/roles) are regenerated from Go
  `07184da`, not hand-edited. The hand-off's rollout step 3 (`POST /bff/roles/sync-platform`)
  applies to Java unchanged — check the Java route grants the new permissions to existing roles,
  and pin it.
- **Lockfile**: `server/src/main/resources/openapi/openapi.lock.json` ← Go `api/openapi.lock.json`
  at `07184da` (copied, never edited); `make sdk-spec` carries it to `sdk/openapi/openapi.json` and
  the two `clients/*/openapi` copies. (**Found in K1:** `frontend/openapi/openapi.json` is *not* one
  of them — `frontend/openapi-ts.config.ts` reads the server's lockfile directly, nothing writes that
  file, and it has not changed since the 2026-09-14 SPA import. Left alone; a stale orphan the owner
  may want deleted.) This goes **first**: `SchemaValidation` validates request
  bodies against the lockfile with `additionalProperties` as the lockfile has it, so the new fields
  are rejected until it lands. `LockfileCoverageTest` must stay at 100 %.
- `abcd9fa`: the router's release log line says how long the message is held and who asked —
  mirror the fields in the Java router's equivalent line (logs are Go-shaped by ruling).
- Parity: a scenario under `parity/` for connection sync and the new subscription-sync fields
  (Java-first until the owner runs it against Go); `parity/surface.json` gains the route.

## 4. Slices (one builder per tree; each ends green and mutation-checked)

| Slice | Contents |
|---|---|
| **K1** | lockfile + copies; V12; fixture re-dump; jOOQ; `Connection` fields; the three-part key in both repositories and every caller; create/update `applicationCode`; response fields; `connection.md`/`subscription.md` amended |
| **K2** | seeds (permissions, roles, event type + schemas) and the roles-sync check; `SyncConnections` + route + events; `sdksync.md` amended |
| **K3** | `SyncSubscriptions` changes; router log line; parity scenario + surface |
| **K4** | the SDKs — §4a |

## 4a. Slice K4 — the SDKs (after K1: K1 rewrites the OpenAPI copies inside the same directories)

Two different jobs (`docs/sdk-release-plan.md`, `docs/go-mirror/2026-09-14-sdk-copies.md`):

- **`clients/typescript-sdk`, `clients/laravel-sdk` are copies.** Bring them to Go `HEAD` file for
  file (same excludes as `tools/sdk-drift.sh`: `node_modules`, `dist`, `vendor`, `.DS_Store`,
  `openapi-processed.json`); files Go deleted are deleted here (`RoleAssignmentDto` →
  `RoleAssignmentDTO` is a rename that a case-insensitive filesystem will fight — do it with
  `git mv` through a temporary name). **Done when `tools/sdk-drift.sh` exits 0.** Their own test
  suites run if the toolchain is present (`npm test` / `composer test`); say which ran.
  Version files and changelogs come across as Go has them; **no release is cut** — which repo
  releases is still the owner's open question (`docs/sdk-release-plan.md`).
- **`sdk/` is a port, not a copy** (Jackson 3, the sibling `usecase` module; `tools/sdk-drift.sh`
  does not cover it). Port what Go's `clients/java-sdk` gained: `connectionCode` /
  `sharedConnection` on subscription definitions; connection definitions in code and the connection
  sync call; per-client sync (`clientId`); merged definition sets; and a sync with a failed category
  **throws `DefinitionSyncException` carrying what did sync** (`SdkError.PartialFailure`). Go's
  `ConnectionSyncTest` and `MergedSyncTest` are the tests to port first. Pin, with a mutant each:
  the partial result survives the failure (drop it ⇒ test fails); a failed category does not stop
  the remaining categories (stop at first ⇒ fails); `clientId` absent is omitted from the wire, not
  sent as null (the server's `SchemaValidation` would refuse `null`).
- An end-to-end pin, once K2/K3 have landed: the Java `sdk/` synchronizer against the in-process
  platform — connections then subscriptions by `connectionCode`, for one client — produces the rows
  the hand-off describes. This is the test that would have caught the original defect (a
  subscription that could not be defined in code).

## 5. Tests — port Go's scoped-removal tests first; one mutant per condition

| # | Behaviour | Mutant |
|---|---|---|
| C1 | V12 on a fresh database: old indexes gone, new ones present with the exact expression; two rows `(NULL, NULL, 'x')` now collide (they did not before — assert the **insert fails**) | omit a `COALESCE`; keep an old index |
| C2 | V12 refuses a colliding database, naming the group, and changes nothing (columns may exist; **indexes untouched**) — for connections and for subscriptions | drop either pre-check |
| C3 | V12 is a no-op on a Go-56 database and completes a Go-55 one (`GoAdoptionTest`) | — |
| C4 (note) | the `code` part is `NOT NULL`, so `eq` vs `isNotDistinctFrom` on it is an **equivalent mutant** — written uniformly, not pinned, and said so in both repositories | — |
| C4 | lookups treat NULL as a value: `(app=A, client=NULL, code)` does not find the shared `(NULL, NULL, code)` row, nor client B's; each of the three parts | `eq` instead of `isNotDistinctFrom` on each part |
| C5b | update's duplicate check runs within the row's **own client** (a client-scoped row moved onto an application collides with that client's row there: 409, not the index's 500) — found by the orchestrator's mutant | look among the global rows |
| C5 | create/update: unknown application 404; no access 403; same code under another application **succeeds**; same three-part key 409; update to a colliding key 409; update re-sending its own key succeeds; `applicationCode` cannot be cleared | each |
| C6 | connection sync ownership: never updates or removes a UI row with the same key; never sees a shared row, another application's, another client's, or (client-less sync) any client-scoped row — **assert the untouched rows byte-for-byte after the sync, and the counts** | drop each of the three filters (application, client, source) separately |
| C7 | `service_account_id` is the application's on create **and** update, never the caller's; no application service account ⇒ 400 and nothing written | use the caller's; skip the re-stamp on update |
| C8 | `removeUnlisted`: removes only owned API/CODE rows; one referenced candidate refuses the **whole** sync with 409 naming connection and subscription codes — **no row created, updated or deleted** | check references after deleting; check only the first candidate |
| C9 | authorization: application access required; client access when `clientId` given; a client-less sync by a **non-anchor** with application access succeeds; slug and id both resolve; unknown client 404 | each |
| C10 | permission gate: each of the five permissions alone admits; none ⇒ 403 | remove one from the any-of |
| C11 | subscription sync scoping: client A's sync never sees/updates/removes client B's or the global rows, and vice versa; created rows carry the client | load by application only |
| C12 | connection namespace: bare code ⇒ this application's only (a shared connection of the same code is **not** found ⇒ 404); `sharedConnection` ⇒ shared only; client-scoped sync prefers its client's row then the global one; client-less sync never resolves a client-scoped row | add a fallback between namespaces; skip the client-first step |
| C13 | `CONNECTION_MISMATCH`; `CONNECTION_SCOPE_MISMATCH` both clauses (other client; global sync with a client-scoped id; other application; a **shared** connection by id is allowed); `SHARED_CONNECTION_REQUIRES_CODE` | each |
| C14 | a legacy subscription-sync request (no new field) produces the same rows and events as before | — (existing tests, unmodified) |
| C15 | events: per-row events + one rollup with the documented data; the seeded schemas validate the emitted data (`additionalProperties:false` — an undeclared `clientId` would fail) | omit `clientId` from the schema |
| C16 | roles sync grants the new permissions to pre-existing `messaging-admin` / `application-service` roles | — |
