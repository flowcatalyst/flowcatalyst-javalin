# Service-account reach follows its client links (owner ruling 2026-09-13)

> "Service accounts should be linked to a client, or be an anchor, in which
> case they apply across tenants."

Second half of `docs/spec/permissions-from-roles.md` (§4). Java-first; Go
mirrors (`docs/go-mirror/2026-09-13-service-account-reach.md`).

## 0. Today

`ServiceAccount.clientIds` is documented as "reach: which clients this
account may act for" and is settable on create and update
(`POST/PUT /api/service-accounts`). But the linked `SERVICE` principal is
always created `ANCHOR` (`Principal.newService`, Go `principal.NewService`),
and reach claims are built from the **principal** (`DbClaimsResolver`:
scope, `clientId`, `assignedClients`). So `clientIds` never reaches a token:
every service account reaches every tenant, whatever it was linked to.

## 1. The rule

The service account's client links decide its principal's association, on
the existing tiers and the existing `Principal` transitions:

| `clientIds` | Principal | Token `tier` | `clients` claim |
|---|---|---|---|
| none | `ANCHOR`, no home client | `ANCHOR` | every client (as today) |
| exactly one | `CLIENT`, homed at it | `CLIENT` | `[that client]` |
| several | `PARTNER`, no home client, each as a grant | `PARTNER` | those clients |

Applied where the links are written:

- `CreateServiceAccountWithCredentials` (standalone accounts): derive and
  persist the association in the same transaction as the principal, using
  the same repository path `SetClientAssociation` uses for users
  (`repo.withClientGrants(grants, actor)`).
- `UpdateServiceAccount`: when `clientIds` changes, re-derive and persist the
  linked principal's association in the same transaction. A token minted
  before the change keeps its claims until it expires (tokens are
  self-contained; sessions are not involved) — documented, not fixed here.
- `ProvisionServiceAccount` (an application's account): no client links →
  `ANCHOR`, unchanged. fcdev's `fcdev-router`: unchanged.
- Every `clientIds` entry must name an existing client, refused as
  `resourceNotFound("Client", id)` exactly as `SetClientAssociation`
  refuses it for users. Today the aggregate accepts any string.

Add `Principal.withServiceReach(List<String> clientIds)` returning the
existing `ClientAssociationChanged` (principal + grant list) so the mapping
lives in one place and the `NOT_A_USER` rule in `SetClientAssociation`
stays as it is (that operation is for users; this one is for accounts).

## 2. Consequences (all intended)

- A `CLIENT`-scoped account is refused every anchor-only route
  (`ANCHOR_REQUIRED`) and sees only its client's rows on client-scoped
  lists (`canAccessClient`, `filterClientScoped`), exactly like a `CLIENT`
  user. Permissions still come from its roles (§permissions-from-roles).
- A `PARTNER`-scoped account reaches its assigned clients and nothing else.
- The `/api/service-accounts/{id}/token` admin mint and `/oauth/token`
  client-credentials both load the principal, so both carry the new tier.

## 3. Tests (each with a killed mutant)

1. Operations: create with one client → principal `CLIENT` homed there; with
   two → `PARTNER` with both grants; with none → `ANCHOR`; update from none
   to one → re-derived; an unknown client id → `NOT_FOUND`. Mutant: skip
   the derivation (principal stays `ANCHOR`).
2. API, through a real token: a standalone account created with one client
   mints a client-credentials token whose decoded `tier` is `CLIENT` and
   whose `clients` is that one id; `GET /api/clients` with it → 403
   `ANCHOR_REQUIRED`; a client-scoped list it is allowed to read returns
   only that client's rows (seed one row per client). Two clients →
   `PARTNER` and both ids.
3. Parity: the `service-accounts` scenario already creates an account with
   `clientIds`; any step that mints for it now DIFFs on `tier`/`clients`
   against Go (`ANCHOR`) — allow-listed as Java-first with this spec. Add a
   step that the linked account is refused `GET /api/clients`.
