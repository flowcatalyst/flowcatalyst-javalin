# Java mirror of Go F15: SDK role sync ceiling, unknown roles in the application principal sync (2026-10-13)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` F15 (owner rulings 2026-10-13),
`../flowcatalyst-go/docs/wire-contract.md` ("SDK sync: roles"), `internal/platform/role/ceiling.go`,
`internal/platform/role/operations/sync.go`, `internal/platform/principal/operations/sync_principals.go`
and `assignable_roles.go`; Go commits `2ba9763` (role sync ceiling test), `3aeb08c` (unknown roles)
and `96a15cc` (docs). Predecessor: `2026-10-13-platform-bug-fixes-5.md`. Every commit is on `main`,
not pushed.

## Commits

| Commit | Change |
|---|---|
| `89d258f5` | 1. `POST /api/applications/{appCode}/roles/sync` (`SyncRoles`) applies the permission ceiling. |
| `64b2a617` | 2. `POST /api/applications/{appCode}/principals/sync` skips role names that are not roles and reports them in `skippedRoles`. Lockfile. |
| (this file) | Ledger. |

## 1. SDK role sync ceiling (`89d258f5`)

Go F15 ruling 1. Go has applied it since F10 (`c56a68c`); F15 only added the addition test. Java's
`SyncRoles` applied **no** ceiling (Go's F15 Java/Rust note, confirmed: `RoleCeiling` was used by
the role-edit operations, F10, but not by either sync). Confinement already existed in Java: S1.5,
`Role.confined`, reached from `Role.syncedFromSdk` and `Role.withPermissions`, 400
`PERMISSION_OUTSIDE_APPLICATION`, per role as the role is built (see Divergences for one difference).

**The rule** (reusing the F10 mirror: `RoleCeiling.changed`, `RoleCeiling.requirePermissions`):

- an existing `SDK` role with a **non-empty** `permissions`: `RoleCeiling.changed(stored, sent)`
  (sent minus stored, then stored minus sent; Go `ChangedPermissions`);
- an empty or omitted list: the stored permissions are kept, nothing recorded;
- a new role: all its permissions; a role `removeUnlisted` deletes: all its permissions;
- a same-named `CODE`/`DATABASE` role: skipped untouched (`continue` before any recording);
- order: confinement (per role, in the loop), then `ROLE_HAS_ASSIGNMENTS` (the `removeUnlisted`
  loop), then **one** `RoleCeiling.requirePermissions(Auth.current(), changed)` before the plan;
- only `platform:` permissions the caller does not hold count (`Permission.grants`, segment
  wildcards; `platform:*:*:*` holds all); each once, in collection order;
- refusal: 403 `PERMISSION_ABOVE_CALLER` "You may only add or remove permissions you hold yourself:
  `<p1>, <p2>`"; the whole sync is refused and nothing is written (the refusal is thrown in
  `execute`, before the plan is returned).

**Tests** (`RoleOperationsTest`, new; the first two failed before the fix, "Expecting code to raise
a throwable"):

- `syncRolesBoundsPlatformPermissionChangesByTheCaller` (Go
  `TestSyncRoles_PlatformApplicationAdditionsBoundedByCaller`, on the `platform` application): a
  held permission syncs; a new role with `platform:*:*:*` beside a valid one, refused, neither
  written; an existing role gaining an unheld one, refused, unchanged; a caller lacking the kept
  permission re-syncs the same list and an empty list; removal by a replacing list and by
  `removeUnlisted`, refused, nothing written; a holder adds it. Messages asserted exactly.
- `syncRolesChecksTheCollectedChangesOnceInCollectionOrder`: one sync with an updated role
  (added + removed), a new role repeating a permission, and a `removeUnlisted` deletion; the message
  is `user:view, role:view, group:view, client:view` (added, removed, new, deleted, each once); a
  caller holding `platform:iam:role:*` gets the same list without `role:view`; a `platform:*:*:*`
  caller syncs it.
- `syncRolesCeilingFollowsConfinementAndAssignmentsAndSkipsNonSdkRows`: on an ordinary application
  a role with `platform:*:*:*` is 400 `PERMISSION_OUTSIDE_APPLICATION` (not 403); with an assigned
  role to remove and an unheld addition, `ROLE_HAS_ASSIGNMENTS` (not 403); a same-named `DATABASE`
  role holding `platform:iam:user:delete`, synced with `platform:iam:client:delete` and
  `removeUnlisted` by a caller holding neither, passes with 0/0/0 and the row unchanged.
- `SdkSyncApiTest.rolesSyncIsBoundedByThePermissionCeiling` (HTTP): an application role a
  super-admin gave `platform:iam:user:view`, re-synced without it by the application's service
  account, is 403 `PERMISSION_ABOVE_CALLER` with the exact message, nothing written; an empty list
  passes.

**Mutants** (each failed at least one of the tests above): ceiling removed (the HTTP test too);
existing role counting only added permissions; counting every sent permission; the empty list
checked; deleted roles not counted; new roles not counted; the ceiling before confinement (on the
new role); the ceiling before `ROLE_HAS_ASSIGNMENTS`; a per-role check instead of one collected
check; a skipped `CODE`/`DATABASE` role counted.

## 2. Unknown role names in the application principal sync (`64b2a617`)

Go F15 ruling 2. **Java before**: every name that is not a role refused the whole sync, 400
`UNKNOWN_ROLE` "role not found: `<name>`", on both routes (`SyncPrincipals.requireSyncableRoles`).
Go before stored such a name as an assignment (or refused it, `ROLE_APP_FORBIDDEN`, when the prefix
named another application); both now converge on the ruling.

**The rule** (`SyncPrincipals.syncableRoles`, per entry, after the target-reach check
`SYNC_TARGET_FORBIDDEN`):

1. names are lower-cased (as before) and matched exactly by `RoleRepository.findByName`; no `app:`
   prefix is added;
2. a role of another application (`Role.owningApplicationCode()` differs): 403 `ROLE_APP_FORBIDDEN`
   "role '`<name>`' does not belong to application '`<appCode>`'", the whole sync refused (unchanged);
3. a name that is not a role, whatever its prefix: not assigned, nothing created, not refused; added
   to a request-wide `LinkedHashSet` (each once, payload order); the entry syncs with its other
   roles. An existing user whose every name is skipped goes through the unchanged merge
   (`Principal.syncSourcedRoles`) with an empty list, so it keeps none of this application's
   `SDK_SYNC` roles; a new one is created with none;
4. unchanged: the merge, the `removeUnlisted` sweep, the role ceiling (`requireCeiling` sees only
   the assigned names, so skipped names are on neither side), `passwordHashIgnored`;
5. the skipped names are logged once per request (warn, `applicationCode`, `roles`) and carried on
   `PrincipalsSynced.skippedRoles` (a new record component, not in `data()`, so the event payload
   and its catalogued schema are unchanged) to `SyncResultResponse.skippedRoles`
   (`@JsonInclude(NON_EMPTY)`: omitted when empty, on every sync route's response).

The platform route `POST /api/principals/sync` is **unchanged**: every name must exist (400
`UNKNOWN_ROLE`), a non-anchor's names must be assignable, and its `SyncUsersResponse` has no
`skippedRoles`.

**Lockfile**: `SyncResultResponse` gains `skippedRoles` (optional `string[]`, Go's description);
`SyncPrincipalInputRequest.roles` takes Go's corrected description (it claimed the platform
prefixes names). Go's F13 descriptions of `passwordHash` and `passwordHashIgnored` on the same two
schemas were taken with them, so both schemas now equal Go's `api/openapi.lock.json` (compared as
parsed JSON; `SyncPrincipalsRequest` and `SyncUsersRequest` equal too). `SyncUsersResponse` and
`SyncUserInput` (the platform route) still differ from Go in those F13 descriptions only; not
touched. The SDK/SPA copies of the spec (`clients/*/openapi/openapi.json`, `sdk/openapi/openapi.json`,
`frontend/openapi/openapi.json`) were not touched, as in the previous batch.

**Specs**: `docs/spec/principal.md` §2 ("Principal sync's per-resource rules"),
`docs/spec/sdksync.md` §2 (the two optional arrays); `docs/spec/role.md` §5 and §7.1 for rule 1.

**Tests** (each failed before the fix with `UNKNOWN_ROLE`):

- `SdkSyncApiTest.principalsSyncSkipsRoleNamesThatAreNotRoles` (HTTP; Go
  `TestSyncPrincipals_AppRouteSkipsUnknownRoles`): own-prefixed, bare, mixed-case and
  foreign-prefixed unknown names skipped, the known role assigned, an all-unknown entry still
  created with no role, `skippedRoles` exactly `[<app>:f15-not-yet-synced, unprefixed,
  ghostapp:admin]` (deduplicated across entries, lower-cased, payload order); after the role is
  synced the same request assigns it and the body has no `skippedRoles`; an existing user re-synced
  with only an unknown name (`Nope:Gone`) is updated, reported `["nope:gone"]`, and holds no role;
  a known foreign role beside an unknown name is 403 `ROLE_APP_FORBIDDEN` with the exact message and
  nothing written.
- `PrincipalOperationsTest.syncRefusesAnotherApplicationsRoleAndSkipsAnUnknownOne` (renamed from
  `syncRefusesAnUnknownRoleAndAnotherApplicationsRole`): app route unknown name skipped
  (`PrincipalsSynced.skippedRoles`, no assignment, no role created); foreign role
  `ROLE_APP_FORBIDDEN`; **platform route** unknown name still 400 `UNKNOWN_ROLE`, nothing written.

No other Java test synced a role it never created (Java refused those before), so unlike Go no
existing test needed roles added.

**Mutants** (each failed): an unknown name assigned; the set replaced by a list (no dedup); the set
reset per entry; names not lower-cased; a known foreign role admitted; an unknown foreign-prefixed
name refused (the old F12 rule); the platform route skipping too; an all-skipped existing user
keeping its roles; the response not wired to the event; `skippedRoles` not omitted when empty.
**Survived**: the lockfile without `skippedRoles` (Java does not validate responses against the
lock; no test reads that schema). The lock field is checked only by the parsed-JSON comparison with
Go's lock above.

## Divergences from Go

- **Platform principal sync, unknown names** (pre-existing, unchanged by the brief): Go stores an
  anchor's unknown name as an assignment and refuses a non-anchor's (400 `UNKNOWN_ROLE`); Java
  refuses every caller's, 400 `UNKNOWN_ROLE`.
- **SDK role sync confinement scope** (pre-existing): Go confines only the permissions a synced role
  **adds** (`AddedPermissions`), so a role a super-admin gave another application's permission keeps
  it through a re-sync that sends it; Java's `Role.syncedFromSdk` confines the whole sent list, so
  that re-sync is 400 `PERMISSION_OUTSIDE_APPLICATION` in Java. Not changed (not in F15).
- **Message order of removed and deleted permissions**: Java stores a role's permissions in text
  order (`Role.normalise`) and Go reads them in the database's order (no `ORDER BY` on
  `iam_role_permissions`), so when several are removed or deleted the names in the
  `PERMISSION_ABOVE_CALLER` message can be ordered differently. Added and new-role permissions follow
  the payload in both. The `removeUnlisted` iteration order over stale roles is likewise the
  repositories' (Java `findByApplicationId`, Go `ORDER BY name`).
- **Super-admin**: Go's `PermissionsAboveCaller` returns nothing for `IsSuperAdmin()`; Java's
  `RoleCeiling` relies on `Permission.grants` (`platform:*:*:*` grants every platform permission).
  Same outcome for a `platform:*:*:*` holder (pinned in the order test).
- **Within-entry order of a skip and a refusal**: both iterate an entry's names in order and refuse
  on the first foreign role; identical.

## Found, not changed

- **`SyncPlatformRoles` (`/bff/roles/sync-platform`) has no permission ceiling in Java**; Go's has
  had one since F10 (every platform permission the catalogue sync adds to or removes from a role,
  and a swept role's). The route is anchor-only. Not in F15's brief.

## Parity

No `parity/scenarios` step sends an unknown role name or a platform permission on these routes
(`applications/sdk-sync.json` syncs one own-permission role and principals with `roles: []`), and
`parity/expected-diffs.json` has no entry on them; nothing changed there. The harness was not run.

## Verification

- Targeted runs before each commit: `RoleOperationsTest` (29), `SdkSyncApiTest` (23),
  `PrincipalOperationsTest` (32), `PrincipalApiTest` (40), `RolesBffTest`, `AuthorityGatesEndToEndTest`,
  `LockfileTest`, `LockfileCoverageTest`, `SchemaValidationTest`, `SchemaValidationRouteTest`: all
  green. Mutants run with `-Dsurefire.timeout=600`.
- **Full reactor, `mvn clean test` at `64b2a617`: BUILD SUCCESS**, 0 failures, 0 errors
  (13:55 min):

  | Module | Tests | Skipped |
  |---|---|---|
  | server | 5,781 | 1 |
  | function-host | 485 | 2 |
  | fcdev | 274 | 0 |
  | parity | 56 | 1 (`ParityRunTest` skips without a Go source) |
  | sdk | 93 | 1 |
  | usecase | 46 | 0 |
  | function-api | 145 | 0 |
  | eventloop-check | 16 | 0 |

  The server has 5 more tests than at `5fc01261` (5,776): `RoleOperationsTest` 3,
  `SdkSyncApiTest` 2 (one `PrincipalOperationsTest` test renamed and rewritten).

The repo has no formatter or lint target (`Makefile`, no Spotless/Checkstyle plugin); none run.

## Not verified

- Embedded databases only; no run against a deployed platform, a real SDK, or a browser.
- The SDKs (Java, TypeScript, Laravel) do not model `skippedRoles` and were not changed. The Java
  SDK's mapper disables `FAIL_ON_UNKNOWN_PROPERTIES` (`sdk/.../http/Json.java`), so it ignores the
  field; the Laravel generated normalizer was not exercised with it. Their docs that say the
  platform prefixes bare role names (Go F15 "New findings") were not checked here.
- The Rust platform was not checked (Go F15 lists its mirror separately).
