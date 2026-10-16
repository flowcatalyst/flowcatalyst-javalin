# Java mirror of Go F19: application service accounts sync their own dispatch pools (2026-10-15)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` F19 (owner ruling 2026-10-15); Go commit
`489f52c`, migrations `078_dispatch_pool_application_owner.sql` and
`079_application_service_dispatch_pool_sync.sql`. Predecessor:
`2026-10-15-platform-bug-fixes-9.md`. Commit is on `main`, not pushed. **F19: mirrored.**

## Was the defect present in Java?

The gap Go found, **yes, once the permission is granted** (and it is now, so the mirror closes it
in the same change). `SyncDispatchPools` matched on code over every pool in the table with no
owner, so an application service account holding the permission could overwrite any pool by
sending its code. The `removeUnlisted` sweep had no anchor gate at all in Java (Go had
`ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP`, X-02(d)), so any caller holding `dispatch-pool:sync` or
`:manage` could archive every pool on the platform. The handler not calling the application check
was not exploitable by itself: the operation's authorize phase already refused an unbound caller
with the same 403 `FORBIDDEN` (`Access.checkApplicationAccess`).

## Changes

- `Permissions.APPLICATION_SERVICE` gains `platform:messaging:dispatch-pool:sync` (the messaging
  permission itself; the route has no application-service variant). Fresh databases get it from the
  seed; `V31__application_service_dispatch_pool_sync.sql` (Go 079's statement, idempotent) grants it
  to the existing `platform:application-service` role, because the seeder never rewrites an existing
  role. Every existing application service account must mint a new token to hold it.
- `V30__dispatch_pool_application_owner.sql` (Go 078's statements, idempotent):
  `msg_dispatch_pools.application_id VARCHAR(17)` nullable, indexed, **no foreign key**.
  `DispatchPool` gains `applicationId` (not on the wire); jOOQ regenerated; both migrations are in
  `db/migration.index`.
- `SyncDispatchPools`:
  - matches only client-less pools by code (a client's pool of the same code is not the match);
  - a new pool is created owned by the target application;
  - a caller that is not all-applications may change only pools the target application owns; any
    other code (another application's, a platform or pre-ownership pool with no owner, or a code held
    by a client's pool) is refused, all-or-nothing, 403 `DISPATCH_POOL_NOT_OWNED`;
  - an all-applications caller syncs any pool; unowned pools it touches are handed to the target
    application, an owned pool is never reassigned;
  - `removeUnlisted`: a non-anchor, non-super-admin caller is refused 403
    `ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP` (new in Java); an all-applications caller sweeps
    platform-wide; an anchor-tier application-scoped caller archives only its own pools;
  - **kept from Java**: a function's pool (`protectedIds`, from `fn_trigger_objects`) is checked
    first and silently skipped, before the ownership rule, in both the update and the sweep. Go has no
    such guard.
- `SdkSyncApi.syncDispatchPools` calls `Checks.checkApplicationAccess` (parity; no test can tell it
  from the operation's own check).
- **Existing pools have no owner.** After deploy an application service account is refused
  `DISPATCH_POOL_NOT_OWNED` for the pools that already exist. One sync by an admin credential per
  application (no `removeUnlisted`, the application's pool list) hands them over; from then on the
  application's own account syncs them.
- **Wire**: no schema or description change, `openapi.lock.json` untouched. New 403
  `DISPATCH_POOL_NOT_OWNED` and `ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP` (the latter only reachable by
  non-anchor admins that could previously sweep).

## Tests (mutation-checked)

`DispatchPoolOperationsTest` (own pools created owned and re-synced; takeover of another
application's pool, a platform pool and a mixed list refused with nothing written; a client's pool
code unavailable and never matched; non-anchor sweep refused; anchor-tier application caller sweeps
only its own; all-applications anchor sweeps platform-wide; admin syncs any pool, claims unowned,
never reassigns owned), `SdkSyncApiTest` (HTTP: own application 200; another application, unbound and
CLIENT-scoped callers 403 `FORBIDDEN`; takeover 403 `DISPATCH_POOL_NOT_OWNED`; admin 200),
`ApplicationServiceDispatchPoolSyncMigrationTest` (V30 column, index, no FK, existing pools unowned;
V31 grants once, only to the application-service role, idempotent), `PlatformCatalogueTest` (role
permission total 213 to 214; the role holds the permission), `MigratorTest` / `GoAdoptionTest`
(migration counts 29 to 31), `SchemaFingerprintTest` / `GoAdoptionTest` (the column and index are
named, exact allowances over the goose-57 fixture, like V26's archive table).

Mutants killed: ownership check off (update branch); sweep ownership filter off; client-code guard
off; anchor gate off; unowned pools not claimed; new pools created unowned; owned pools reassigned;
permission removed from the seed (`PlatformCatalogueTest`). The handler's `checkApplicationAccess`
removed: **no failure**, the operation's authorize phase holds the line (as in Go).

## Not verified

No run against a deployed or dev database; the SDK generated clients were not touched. Go's seed
mirror tests (`roles_test.go`) fail six tests when the permission is dropped; here `SdkSyncApiTest`
authenticates by test headers, so only `PlatformCatalogueTest` sees the seed.
