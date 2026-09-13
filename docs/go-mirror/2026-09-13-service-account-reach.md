# Go hand-off — service-account reach follows its client links (2026-09-13)

For the Go agent. Authority: `docs/spec/service-account-reach.md`. Context:
`2026-09-13-permissions-from-roles.md` (scope is reach, permissions come
from roles).

## What Go has today

`serviceaccount/operations/create.go` stores `cmd.ClientIDs` on the account
(`sa.ClientIDs`), but `principal.NewService(sa.ID, name)` always creates the
linked principal `ANCHOR`, and claims are built from the principal — so the
links never reach a token and every service account reaches every tenant.

## What to build

Derive the linked principal's association from the account's client links,
on the tiers Go already has, wherever the links are written (create, update):

| `ClientIDs` | Principal |
|---|---|
| none | `ANCHOR` (unchanged; also every application-provisioned account) |
| one | `CLIENT`, `ClientID` = it |
| several | `PARTNER`, each id an assigned-client grant |

Refuse an unknown client id the way the user client-association operation
does (`Client` not found). A token minted before an update keeps its claims
until expiry — documented, not fixed.

## Tests to carry over

Operations: one → `CLIENT`; two → `PARTNER` with both grants; none →
`ANCHOR`; update re-derives; unknown client refused. API: a client-linked
account's token decodes to `tier: CLIENT` with `clients: [id]`, is refused
`GET /api/clients` (`ANCHOR_REQUIRED`), and sees only its client's rows on a
client-scoped list. Parity's `service-accounts` scenario is allow-listed as
Java-first on `tier`/`clients` until this lands.
