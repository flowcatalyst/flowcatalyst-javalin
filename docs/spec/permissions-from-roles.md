# Permissions always come from roles; scope is reach (owner ruling 2026-09-13)

> "Anchor users still need their roles and permissions applied, but they are
> global across all clients. Service accounts should be linked to a client,
> or be an anchor, in which case they apply across tenants."

Two ideas the code had fused are separated:

- **Reach** — which tenants a principal may act for. `ANCHOR` reaches every
  client, `PARTNER` its assigned clients plus grants, `CLIENT` its home
  client. Unchanged.
- **Authority** — what a principal may do inside its reach. Always the
  permissions its roles grant, at every tier. The super-admin wildcard
  `platform:*:*:*` is a permission like any other and is what a real
  super-admin holds.

Until now `Checks.require`/`requireAny` (Go `auth.go:409`) short-circuited
on `isAnchor()`, so an anchor-scoped principal was never asked for a
permission. Every provisioned service account is anchor-scoped, so its
least-privilege role was decorative and any application's client credentials
could call every admin route. Anchor staff with read-only roles could write.
This spec removes the bypass. It is Java-first; Go mirrors from here
(`docs/go-mirror/2026-09-13-permissions-from-roles.md`).

## 1. The gates

| Helper | Before | After |
|---|---|---|
| `require(a, p)` / `requireAny(a, p…)` | anchor passes, else permission match | permission match only |
| `requireAnchor(a)` | anchor scope | unchanged — a reach check |
| `requireAdmin(a)` | anchor OR wildcard | holds `platform:*:*:*`; its one caller, dashboard stats, instead gates `requireAnchor` + `requireAny(CLIENT_VIEW, APPLICATION_VIEW)` (platform-wide counts: anchor reach, and a read permission on what is counted). `requireAdmin` is then unused and is removed. |
| `requireUserAdmin(a, targetClientId)` | anchor passes for any target | anchor: any target, but must hold a user-write permission; non-anchor: as before (client access + permission); `null` target still anchor-only |
| `canAccessScope` / `checkScopeAccess` / `filterClientScoped` / `requireClientAccess` | reach checks | unchanged |
| direct `isAnchor()` in handlers and operations | reach decisions (client filters, delegation limits, platform-level resources) | unchanged — none of them grants authority |

`RouterConfigApi` goes back to the ordinary `Checks.require` gate; its
"403 without the role" test keeps pinning the behaviour (mutant: drop the
gate).

## 2. Who is affected, and what already holds the right thing

- The bootstrap admin holds `platform:super-admin` → the wildcard. fcdev's
  `fcdev-router` holds `platform:router`. Application service accounts hold
  `platform:application-service`, now enforced.
- Token minting already bounds the `scope` claim by the role ceiling at every
  tier (auth-core.md, `304338a`); the claim finally matches what the API
  enforces.
- The SPA is already permission-driven.
- **Test principals** (`X-FC-Test-Scope: ANCHOR` with no permissions) now
  hold no authority. Every anchor test helper states its permissions:
  `X-FC-Test-Permissions: platform:*:*:*` for the ordinary "anchor admin"
  fixture, or the specific permissions where a test is about authorization.
  Scope stays `ANCHOR` — it is the reach the test needs.

## 3. Tests that must exist (each with a killed mutant)

1. `ChecksTest` (or wherever `Checks` is pinned): an anchor context without
   the permission is refused by `require`/`requireAny` with
   `PERMISSION_REQUIRED`; with the wildcard it passes; with the specific
   permission it passes; a CLIENT context is unchanged. `requireUserAdmin`:
   an anchor without a user-write permission is refused for a client target
   and for a platform target.
2. An API-level proof on a real route: a provisioned application service
   account's client-credentials token is refused (403 `PERMISSION_REQUIRED`)
   on an admin read such as `GET /api/principals`, and accepted on what its
   role allows (event-type view). An anchor test principal holding only
   `platform:viewer`'s permissions is refused a write it could previously
   perform.
3. The whole server suite stays green after the test-helper sweep — a test
   that now fails because its fixture had no permissions is a fixture to
   fix, not a behaviour to relax.
4. Parity: a new `authz/permissions-from-roles.json` scenario — create an
   application, provision its service account, mint, `GET /api/principals`
   (Java 403 / Go 200 → allow-listed as Java-first with this ruling); an
   anchor user holding only `platform:viewer` attempting `POST /api/clients`
   (Java 403 / Go 201 → allow-listed likewise). Any existing scenario that
   DIFFs for the same reason is allow-listed the same way, each entry naming
   this spec.

## 3a. Found while building: routes with no permission gate at all

Nine API classes gate on `requireAnchor` alone and never ask for a
permission: `ClientApi`, `OAuthClientApi`, `IdentityProviderApi`,
`AuthAdminConfigApi`, `EmailDomainMappingApi`, `PlatformConfigApi`,
`CorsOriginApi`, `LoginAttemptApi`, `DeveloperBff`. On those an anchor
viewer can still write, because there is no gate for the withdrawn bypass to
have been bypassing. Permission codes exist for most of them
(`platform:admin:client:*`, `platform:auth:oauth-client:*`,
`platform:admin:login-attempt:view`, …). Under this ruling each of those
routes needs its permission gate; that is a follow-up unit with a per-route
mapping (existing codes where they exist, new ones where they do not), its
own tests, and a Go mirror item. The parity scenario in §3.4 deliberately
uses `POST /api/event-types` for the anchor-viewer refusal for that reason.

**Done 2026-09-13**: `docs/spec/reach-only-routes.md` — all nine classes
gated, the seed roles that held none of these codes updated, and the
read-refused/write-refused tests added per class.

## 4. Service-account reach (second half of the ruling — next unit)

Today every service principal is created `ANCHOR` (`Principal.newService`),
whatever `ServiceAccount.clientIds` says. The ruling: a service account
linked to clients reaches only those clients; one with no client is an
anchor and reaches every tenant. Mapping onto the existing tiers: no client
→ `ANCHOR`; exactly one → `CLIENT` with that home client; several →
`PARTNER` with them as assigned clients. That touches provisioning, the
service-account update path, and the claims a token carries, and is
specified separately once this unit has landed — it must not be folded in
here.

**Done 2026-09-13** — see `docs/spec/service-account-reach.md`.

## 5. Docs to update in the same unit

`auth-core.md` rulings (a new row: the bypass is withdrawn, with the date),
`sdk-ingest.md` §"Anchor callers bypass…", `router-config-auth.md` (the gate
paragraph), `docs/backlog.md` (the 2026-09-13 entry becomes resolved with a
pointer here), and the Go hand-off.
