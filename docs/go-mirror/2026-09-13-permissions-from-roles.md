# Go hand-off — permissions come from roles at every tier (2026-09-13)

For the Go agent. Authority: `docs/spec/permissions-from-roles.md` (owner
ruling 2026-09-13). Java has built it; Go mirrors.

## The ruling

Scope (`ANCHOR` / `PARTNER` / `CLIENT`) is **reach** — which tenants a
principal may act for. Authority is always the permissions its roles grant.
`internal/platform/shared/auth/auth.go:409` (`if a.IsAnchor() ||
a.HasPermission(perm)`) fused the two: every anchor-scoped principal passed
every permission gate. Every provisioned service account is anchor-scoped,
so its `platform:application-service` role was decorative and any
application's client credentials could call every admin route; anchor staff
holding read-only roles could write.

## What to change

| Go | After |
|---|---|
| `RequirePermission` / `RequireAnyPermission` (auth.go ~409) | permission match only; drop `IsAnchor() \|\|` |
| `RequireAnchor` | unchanged — reach |
| `IsAdmin` (auth.go ~349: anchor or wildcard) | its only real use is the dashboard stats BFF; that route becomes `RequireAnchor` + any of `platform:admin:client:view`, `platform:admin:application:view`; then remove `IsAdmin` |
| the user-admin check (anchor passes for any target) | anchor still passes the reach part for any target but must hold a user-write permission |
| `CanAccessScope` / `CheckScopeAccess` / `FilterClientScoped` and every other `IsAnchor()` used to decide client visibility or delegation limits | unchanged |

The bootstrap admin holds `platform:super-admin` → `platform:*:*:*`, which
`HasPermission` already honours as a wildcard, so nothing seeded loses
access. Token minting already bounds the `scope` claim by the role ceiling
at every tier (`304338a`), so the claim finally matches enforcement.

## Tests to carry over (each proven by a mutant in Java)

1. Unit: an anchor context without the permission is refused
   (`PERMISSION_REQUIRED`); with the wildcard or the specific permission it
   passes; a CLIENT context unchanged; the user-admin check refuses an anchor
   without a user-write permission.
2. API: a provisioned application service account's client-credentials
   token gets 403 on `GET /api/principals`; an anchor principal holding only
   `platform:viewer`'s permissions gets 403 on `POST /api/clients` and 200 on
   `GET /api/clients`.
3. Parity: `parity/scenarios/authz/permissions-from-roles.json` pins both;
   three Java-first allow-list entries retire when this lands.

## Test fixtures

Go's handler tests that authenticate as a bare anchor principal with no
permissions will start failing, exactly as Java's did: give the fixture the
wildcard (or the specific permissions the test is about). A fixture failure
is a fixture to fix, not a reason to relax the gate.

## Next (separate spec, not yet written)

Service-account reach: a service account linked to clients should reach only
those clients; one with no client is an anchor. Today every service
principal is created `ANCHOR` regardless of its client links. Java specifies
it after this unit lands; do not pre-empt it.
