# Java mirror of Go F10 on the platform role sync: the permission ceiling (2026-10-14)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` F10 (the operations table, row
`SyncPlatformRoles`), F12 (confinement order) and F15 (the SDK role sync's rule, already mirrored);
Go `internal/platform/role/ceiling.go`, `role/operations/sync.go` `SyncPlatformRoles`, test
`role/operations/ops_pg_test.go` `TestSyncPlatformRoles_BoundedByCallersPermissions`; Go commit
`c56a68c`. Owner approval 2026-10-14. Predecessor: `2026-10-14-platform-bug-fixes-7.md`. Every
commit is on `main`, not pushed.

## Commits

| Commit | Change |
|---|---|
| `7f7cc90e` | `SyncPlatformRoles` bounded by the caller's platform permissions; tests. |
| (this file) | Ledger. |

## Go's behaviour, as found

- **Where**: only in the operation `SyncPlatformRoles`, reached only by the anchor-only
  `POST /bff/roles/sync-platform` (gate: `RequireAnchor` plus `role:create`, `role:update`,
  `role:delete`). Authorization stays `usecaseop.Public`; the ceiling runs inside `Execute`.
- **The boot-time sync has no caller and no ceiling.** Go's seeder (`seed.seedRoles`) does not run
  the operation: it inserts missing catalogue roles by direct SQL, skip-if-name-exists, and never
  rewrites or sweeps. No system principal exists or is needed ("seed, fcdev, migrations: direct SQL,
  system paths; unchanged", F10 table; `ceiling.go`: the system's role writers "act for no caller").
- **What is collected**, in order, into one list:
  1. per catalogue entry, in catalogue order: an existing `CODE` role →
     `ChangedPermissions(stored, catalogue)` (added, then removed); a same-named non-`CODE` role →
     skipped with a warning, **not collected**; no role → every permission of the new role;
  2. then every stale `CODE` role (absent from the catalogue) that is swept → all its permissions;
     a stale role still assigned → kept with a warning, **not collected**.
- **Order**: confinement (F12, 400 `PERMISSION_OUTSIDE_APPLICATION`, per role as it is visited, no
  super-admin exception) → the sweep (assignment counts) → **one** ceiling check → the write plan.
  The platform sync never refuses `ROLE_HAS_ASSIGNMENTS` (it skips); that refusal is the SDK sync's.
- **Rule**: `role.RequirePermissionsWithinCaller(auth.FromContext(ctx), changed)`: `platform:`
  permissions only, matched by `auth.Grants` (segment wildcards), `platform:*:*:*` holds all, a nil
  caller holds nothing, each permission named once in collection order. **403
  `PERMISSION_ABOVE_CALLER`**, "You may only add or remove permissions you hold yourself: `<p1>,
  <p2>`".
- **All-or-nothing**: the check precedes `usecaseop.Sync`, so a refusal writes no role, event or
  audit row.

## The mirror (`7f7cc90e`)

`SyncPlatformRoles.of` (`platform/role/operations`) collects `changed` exactly as above
(`RoleCeiling.changed(current.permissions(), def.permissions())` for a refreshed `CODE` role,
`def.permissions()` for a new one, `stale.permissions()` for a swept one; skipped rows add nothing)
and calls `RoleCeiling.requirePermissions(Auth.current(), changed)` after the sweep loop, before
`Plan.sync`. Confinement already ran per role inside `Role.syncedFromCatalogue` /
`Role.withPermissions`, so the order is Go's. Same code, status, message and change-only rule as
the role-edit routes and the SDK sync (F15 mirror).

**Boot sync**: Java's is the same shape as Go's: `Seeder.seedRoles` writes the catalogue by direct
jOOQ (skip-if-exists), never through the operation and never under an `AuthContext`, so the
ceiling cannot reach it. Nothing changed there; `SeederTest` (15, unchanged, runs with no auth
bound) still passes. No system principal was introduced.

**The only other caller** is `RolesBff.syncPlatform` (searched every module). The SPA's dashboard
"Sync" button already toasts a 403 with the server's message (`api/client.ts`), as in Go.

## Tests

Failing first: the three new ceiling tests below failed on the unchanged operation ("Expecting
code to raise a throwable" ×2; the BFF answered 200 `{"created":0,"updated":18,...}` where 403 was
expected).

- `RoleOperationsTest.syncPlatformRolesBoundsPlatformPermissionChangesByTheCaller` (Go
  `TestSyncPlatformRoles_BoundedByCallersPermissions`, extended): a non-wildcard anchor holding the
  three role permissions and one `platform:` permission: an unchanged catalogue passes; adding one
  it lacks to an existing `CODE` role → 403, exact message, nothing written; a new role holding one,
  beside a new role it could create → 403 and neither created; the wildcard holder adds it; removal
  by the catalogue's list and by sweeping the stale role → 403, nothing written; a held segment
  wildcard covers what it names; the wildcard holder sweeps.
- `…ChecksTheCollectedChangesOnceInCollectionOrder`: the message names `b, a, c, d` (existing role:
  added then removed; new role, `b` once; swept role), application permissions on a catalogue role
  are not counted; the wildcard holder then applies the same catalogue; a role writer syncs a role
  holding only application permissions.
- `…CeilingFollowsConfinementAndSkipsWhatItDoesNotTouch`: confinement's 400 wins for a role writer;
  a same-named `DATABASE` role is skipped and not counted (a role writer passes, the row untouched);
  a stale `CODE` role still assigned is kept and not counted.
- `RolesBffTest.syncPlatformIsBoundedByTheCallersPermissions`: `platform:messaging-admin` trimmed of
  `platform:messaging:connection:sync` by hand; an anchor role writer without it → 403 envelope
  `{"error":"PERMISSION_ABOVE_CALLER","message":"You may only add or remove permissions you hold
  yourself: platform:messaging:connection:sync"}`, still trimmed; the `platform:*:*:*` anchor restores it.
- Adjusted: `syncPlatformRolesFollowsTheCatalogueLifecycle` and
  `platformSyncGrantsTheNewConnectionPermissionsToPreExistingRoles` ran as an anchor holding **no**
  permissions (the class's `ANCHOR`); they now run as the wildcard holder, as Go's
  `testpg.AnchorCtx` does. `RolesBffTest.writesNeedTheRolePermissionEvenForAnAnchor`: the
  non-wildcard role writer's 200 on sync-platform is preceded by a wildcard sync, so it asserts the
  gate rather than depending on whether this class's database already holds the catalogue.

**Mutants** (each killed; `RoleOperationsTest` + `RolesBffTest`): no ceiling call; an existing role's
change not collected; a new role's permissions not collected; a swept role's not collected;
additions only (removals ignored); a skipped non-`CODE` role counted; an assigned stale role
counted; the caller ignored (`null`); the check moved before the sweep.

## Divergences from Go

- None in the rule. Collection order differs only in how a stored role's permissions are ordered
  inside one role's "removed" part (Java's `Role.permissions()` is sorted; Go's is as loaded), which
  can reorder names in the message, never the set.
- Pre-existing, not changed: Java's confinement on the platform sync checks a catalogue role's whole
  list (`Role.syncedFromCatalogue`), Go's only what the sync adds (`AddedPermissions`). Both refuse
  before the ceiling; they differ only for a legacy `CODE` row already holding another
  application's permission that the catalogue still lists.

## Behaviour changes for clients

- `POST /bff/roles/sync-platform` by an anchor holding the three role permissions but not
  `platform:*:*:*` is refused 403 `PERMISSION_ABOVE_CALLER` whenever the catalogue would add or
  remove a platform permission it lacks (restoring a hand-trimmed role, creating a missing catalogue
  role, sweeping a stale `CODE` role). With the catalogue already in place it still passes. A
  super-admin sees no change. No wire shape changed.
- Deploy note (as Go's): a rollout that needs sync-platform to add new catalogue permissions must be
  run by a `platform:*:*:*` holder.

## Verification

- Targeted: `RoleOperationsTest` 32 (+3), `RolesBffTest` 14 (+1), `SeederTest` 15, `RoleApiTest` 10,
  `RoleTest` 34: green.
- Server module (`mvn -pl server -am test`): 5,818 tests (5,814 + 4), 0 failures, 0 errors, 1 skipped.
- **Full reactor, `mvn clean test` at `7f7cc90e`'s tree: BUILD SUCCESS**, 0 failures, 0 errors
  (18:25 min): server 5,818 (1 skipped), function-host 485 (2), fcdev 274, sdk 93 (1), parity 56
  (1), usecase 46, function-api 145, eventloop-check 16.

## Not verified

- No browser run of the dashboard's Sync button for a non-wildcard anchor (the toast path is
  existing code). The e2e and parity suites not run (their sync-platform caller is the seeded
  super-admin, so they should be unaffected). The Rust port not checked.
