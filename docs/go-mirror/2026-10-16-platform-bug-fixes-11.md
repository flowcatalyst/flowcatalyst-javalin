# Java mirror of Go F18: malformed role names rejected; assignments name existing roles; roles carry their application id (2026-10-16)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` F18 (owner report 2026-10-15) and
`docs/token-claims-followups.md` section 4 (reject at the write boundary; a validated role-name
type; **no foreign keys**, owner ruling 2026-08-27); Go commit `52c51e6`, migrations
`076_normalise_role_assignment_names.sql` and `077_backfill_role_application_id.sql`.
Predecessor: `2026-10-15-platform-bug-fixes-10.md`. Commit is on `main`, not pushed.
**F18: mirrored**, with the deviations at the end.

## What was already in Java before this change

- User role assignment (`AssignRoles`, which also serves create-user's follow-up, the bulk import
  and the BFF) and the IdP role sync (`SyncIdpRoles`) already refused a name that is not a role
  (`ROLE_NOT_FOUND`).
- The SDK principal sync already refused an unknown name on the platform route (`UNKNOWN_ROLE`, for
  every caller) and skipped-and-reported it on the application route (F15).
- The SDK role sync already looked an application's roles up by `application_id`.
- **Not present**: any shape check anywhere; the service-account assignment path
  (`AssignRolesToServiceAccount`) stored any string it was sent (the root of F18: Java had the same
  hole); `RoleAssignment.role` was a `String`; `application_id` was written null by the admin role
  create (`POST /api/roles`, `/bff/roles`) and by the seeder / `sync-platform`; "platform role" for
  the client-administrator checks meant `applicationId == null`; no `V32`/`V33`.

## Changes

- **Validator and value type**: `role/RoleName` (final class, private constructor). Shape: two or
  more colon-separated, non-empty segments of ASCII letters, digits, `_`, `.`, `-`; every other
  character refused (Unicode spaces and zero-width included, as any non-ASCII char); case not
  constrained; multi-colon names valid. Permissions: the same plus `*`, one or more segments.
  Rejected, never trimmed: 400 `INVALID_ROLE_NAME` / `INVALID_PERMISSION`, the message quoting the
  value. Constructors: `of` (shape), `resolve` / `resolveAll` (shape, then existence: 400
  `ROLE_NOT_FOUND`; the result is the stored role's own name), `Role.canonicalName()`, `constant`
  (platform-owned literals), `stored` (persistence reads only, validates nothing).
  `principal.RoleAssignment.role` is now a `RoleName`; `roleName()` is the text. `Principal.assignRoles`
  and `syncSourcedRoles` take `List<RoleName>`. (`serviceaccount.RoleAssignment` is a read-side
  projection with the wire's `roleName` string and is unchanged.)
- **Write paths covered**: service-account assignment (shape, then existence, 400 `ROLE_NOT_FOUND`;
  `AssignRolesToServiceAccount.of` now takes the `RoleRepository`, and `ServiceAccountApi.State` gains
  `roles`); user assignment, create-user, bulk import and BFF (all through `AssignRoles`); IdP role
  sync; SDK principal sync, both routes (a malformed or unknown name is skipped and reported in
  `skippedRoles`, never stored; a malformed name is not a role even if a legacy definition bears
  it); role create, role update (permissions), grant-permission; SDK role sync (one bad name or
  permission refuses the whole sync before any write); IdP role-mapping create (`platformRoleName`
  shape). Not covered by design (as in Go): the platform seed / `sync-platform` catalogue (code
  constants), OAuth-client and IdP/email-domain allow-lists (they hold role ids), application codes.
- **Roles carry their application id**: admin create resolves and sets `application_id` and refuses
  an unknown application, 400 `APPLICATION_NOT_FOUND` (`RoleRepository.applicationIdByCode`); the
  `Seeder` and `SyncPlatformRoles` set the platform application's id, and `SyncPlatformRoles` heals an
  existing row (warn, write null, when the application row is missing); `SyncRoles` adopts an id-less
  role of its application instead of colliding on the unique name (only an `SDK`-sourced one is
  written, as in Go; the other sources are left to V33); `Role.isPlatform()` (application code
  `platform`) is what the two client-administrator checks use (`Access.assignableRolesProblem`,
  `PrincipalApi.protectedRoleNames`), so the backfill does not let a client admin assign platform roles.
- **Data migrations** (idempotent, Go's statements; both listed in `db/migration.index`):
  `V32__normalise_role_assignment_names.sql` (Go 076: rewrites a whitespace assignment under the
  normalised name only when that role exists, keeping source and time; drops the bad row if the
  principal already holds the real one; leaves other malformed rows) and
  `V33__backfill_role_application_id.sql` (Go 077: `application_id` from `app_applications.code =
  application_code` where NULL; unmatched rows left). Counts pinned by `MigratorTest` and
  `GoAdoptionTest` went 31 to 33 (and 30 to 32 executed on the goose-57 database); both are data-only,
  so `SchemaFingerprintTest` is unchanged. **Both need the owner's database run**: they ran on scratch
  databases only. List leftovers with `SELECT * FROM iam_principal_roles WHERE role_name ~ '\s'` and
  `SELECT id, name, application_code FROM iam_roles WHERE application_id IS NULL`.
- **Wire**: new 400 codes `INVALID_ROLE_NAME`, `INVALID_PERMISSION`, `APPLICATION_NOT_FOUND`
  (role create) and `ROLE_NOT_FOUND` on the service-account assignment; role responses now show
  `applicationId` for admin-created and platform roles. No schema or description change,
  `openapi.lock.json` untouched.

## Tests (mutation-checked)

`RoleNameTest` (shape, Unicode and zero-width spaces, offender quoted, permissions plus `*`,
`stored`/equality, `isPlatform`); `ServiceAccountOperationsTest` (malformed and unknown refused, the
stored roles unchanged); `PrincipalOperationsTest` (user assign and IdP sync refuse malformed and
unknown; SDK sync skips a malformed name on both routes and never stores it; a non-anchor's platform
route is still `UNKNOWN_ROLE`; a platform role carrying the platform application's id is still not
assignable by a client admin); `RoleOperationsTest` (create rejects malformed names and permissions;
create sets the application id and refuses an unknown application; grant/update permission; SDK
role sync rejects a malformed entry and adopts an id-less role; the catalogue sync sets and heals the
id); `AuthAdminConfigOperationsTest` (IdP mapping); `RoleDataFixMigrationsTest` (V32 and V33 run by
Flyway then twice more by hand, ambiguous rows untouched, an existing id never overwritten);
`SeederTest.rolesMatchGo` now expects the platform application's id on every seeded role.
Fixtures that created roles under a hand-picked code now call `TestPg.ensureApplication` (Go's
`testpg.EnsureApplication`).

## Deviations from Go

- **Platform principal sync, unknown names, anchor caller**: Java used to refuse every caller's
  unknown name with 400 `UNKNOWN_ROLE` (no F18 hole, stricter than Go's old store-unchecked). The
  brief says the anchor route follows the F15 rule, so it now **skips and reports** (as Go does);
  this loosens a previously stricter Java behaviour and is recorded in `docs/spec/principal.md`. A
  non-anchor's unknown or malformed name is still 400 `UNKNOWN_ROLE`, as in Go. Closes the
  "platform principal sync, unknown names" divergence in `2026-10-14-platform-bug-fixes-6.md` for the anchor.
- A `RoleName` is a final class (Java has no private-constructor record); persistence reads use
  `RoleName.stored`, the analogue of Go's `StoredName`.
- Go's `role.ValidateNames`-style Go `Scan`/`Value`/JSON methods have no Java counterpart (jOOQ reads
  go through `stored`).

## Not verified

No run against a deployed or dev database; the migrations ran on embedded scratch databases only.
The generated SDK clients were not touched. The `DbClaimsResolver` rule that drops a role without
an `application_id` from an application-narrowed token is unchanged (as in Go); the V33 backfill is
what makes existing admin-created roles visible to it.
