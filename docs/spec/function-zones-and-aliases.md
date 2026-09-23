# Spec — domain zones, named aliases, alias-prefixed hostnames (package J)

Owner rulings 2026-09-22 (`docs/backlog.md` §"Function domains: zone claims and alias prefixes"):
a claim covers a zone; alias prefixes are **opt-in per route**; aliases are **HTTP-only**. Amends
`function-public-routes.md` §1–§3, `function-api.md` §5 and §6.1, `function-registry.md` §4.1/§4.3,
`function-developer-surface.md` §2, `function-ui.md`. **Implemented** — J1 zones `bc1be581`, J2
named aliases `f8269fba`, J3 alias prefixes `f7c453a8`, J4 e2e + docs `6a16efe8`.

## 0. What changes, in one paragraph

Today a claim is one exact hostname, `PUT …/aliases/<anything but live>` is `ALIAS_UNSUPPORTED`, and
the public listener matches the `Host` header exactly. After this package: a claim of `acme.com`
covers `acme.com` and every hostname under it, verified once; any alias name is a pointer to a READY
version; a route may opt into alias prefixes, so `qa-myapp.acme.com` serves the `qa` alias of the
function that owns `myapp.acme.com`. Subscriptions, schedules and the pool still follow `live`.

## 1. Zones (platform)

**A claim is a zone.** One kind of claim, one table (`fn_domains` unchanged, `hostname` is the zone
apex). A claim of `d` covers `d` itself and every hostname whose labels end in `d`'s labels
(`acme.com` covers `myapp.acme.com` and `qa-myapp.acme.com`; `hello.localhost` covers itself and
`x.hello.localhost`). The exact-hostname claim needs no second kind: it is a zone nobody puts
children under.

- `POST /api/function-domains` — as §1 today, plus: `d` must have **at least two labels**
  (`DOMAIN_INVALID`: "claim a domain, not a top-level label"); **no two claims may nest** — if an
  existing claim (any owner, verified or not) equals `d`, covers `d`, or is covered by `d` ⇒
  409 `DOMAIN_TAKEN`, never naming the holder (unchanged wording). Verification is the protection
  against claiming a public suffix: nobody can place `_flowcatalyst.co.uk`, so it stays PENDING and
  routes nothing; no public-suffix list is shipped.
- TXT record: `_flowcatalyst.<zone>` — unchanged; verified once for the whole zone.
- **Dev mode** auto-verifies a claim under `.localhost` at claim time — unchanged.
- **Release**: 409 `DOMAIN_IN_USE` while any `fn_routes` row's hostname is covered by the zone,
  naming the functions — the covering check replaces the equality check.
- **Publish**: each `public[].hostname` must be **covered by** a `VERIFIED` zone of the function's
  owner ⇒ else `PUBLIC_HOSTNAME_NOT_VERIFIED` (same code, same no-oracle rule).
- `FunctionDomainRepository.covering(Hostname)` — the one claim whose labels suffix the hostname's
  (there is at most one, by the nesting rule): walk the hostname's parent names and `findByHostname`
  each, longest first, or one `WHERE hostname = ANY(?)` over the candidates. `Access.byHostname`
  and the reach checks resolve a hostname through `covering`, so `GET /api/function-domains/{hostname}`
  (backlog S3) answers the covering zone for any hostname under it.
- `fn_routes.hostname` stays the exact hostname; `(hostname, path_prefix)` stays unique.

## 2. Named aliases (platform)

`fn_aliases` already allows any `alias` matching `^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`.

- `PUT /api/functions/{address}/aliases/{alias}` — `{version}`; **any** alias name matching the
  check, `FUNCTION_PROMOTE`. `live` keeps its full semantics (R3 READY, wiring materialised,
  `Function.promote`). A **named** alias (anything else): the version must be **READY** (the same
  R3 rule — a host has verified the artifact), not RETIRED (`VERSION_RETIRED`), the function not
  DISABLED; pointing an alias at the version it already names ⇒ `ALIAS_UNCHANGED`. **No wiring
  change** — HTTP-only by ruling; the operation's Javadoc says so. `alias` is no longer a path
  literal; `ALIAS_UNSUPPORTED` goes. 200 `{alias, version, versionId, previousVersion?}`. Event
  `alias:changed` carries the alias name (already in its shape).
- `DELETE …/aliases/{alias}` — 204; `live` ⇒ 409 `ALIAS_PROTECTED` ("promote another version, live
  cannot be removed"); unknown ⇒ 404 `Alias_NOT_FOUND`. Event `alias:removed` (new:
  `functionId, address, alias, versionId, version`).
- `GET …/aliases` — unchanged shape, now lists every alias.
- **Retire** a version a named alias points at ⇒ 409 `VERSION_ALIASED` naming the aliases (the
  `VERSION_IS_LIVE` pattern: the operator moves or deletes the alias first).
- **Delete function** cascades (already).

## 3. Alias prefixes on a route (manifest, platform, host)

- Manifest `public[]` entries gain `aliasPrefixes: ["qa", "staging"]` — optional; each a DNS label
  (`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`, ≤ 63), no duplicates, never `live` (`ROUTE_INVALID` naming the
  offender). Absent/empty ⇒ **exact match only**, exactly today's behaviour.
- `fn_routes` gains `alias_prefixes TEXT[] NOT NULL DEFAULT '{}'` (migration V15); the sync copies it.
  `GET /api/function-routes` rows and the desired-state `publicRoutes[]` carry `aliasPrefixes`.
- **Derived hostnames are not stored** and are not checked for conflicts at publish: an exact route
  on `qa-myapp.acme.com` always wins over the prefix derivation (§4 rule), so there is nothing to
  refuse. `docs/functions.md` says so.

## 4. The public listener (host) — matching

`PublicRouteTable.match(hostname, path)` becomes:

1. **Exact**: hostname has routes ⇒ longest whole-segment prefix, as today ⇒ `Match(address,
   functionPath, alias = "live")`.
2. **Prefix**: else, if the first label contains `-`, split it at the **first** `-` into `p` and
   `rest` (`qa-myapp` ⇒ `qa`, `myapp`; `qa-my-app` ⇒ `qa`, `my-app`; a label starting or ending
   with `-` cannot occur — `Hostname` refuses it); base = `rest` + the remaining labels. If base has
   routes: longest prefix as in 1; if that route's `aliasPrefixes` contains `p` ⇒ `Match(address,
   functionPath, alias = p)`; else 404. One level only — `qa-staging-myapp` is `p = qa`, base
   `staging-myapp.…`, which is itself an exact lookup.
3. Nothing ⇒ 404, as today.

Then D3's pipeline, with the version resolved by **`(address, alias)`** instead of `address`: the
host's registry maps `(address, alias) → loaded version` from the desired document (§5). An alias the
document does not carry for that address (never promoted, or its version lives in another pool) ⇒
404 `NOT_FOUND` — the same answer as an unknown route; the platform's `status` route is where an
operator sees why. The endpoint match, body cap, `auth`, CORS and limits are the **aliased
version's manifest's**, which the host already holds per version.

The private listener is unchanged (`address` ⇒ live; `address:version` ⇒ that version). No
`address:alias` form — the versioned form covers the developer's need.

## 5. Desired state (`function-api.md` §6.1)

Each `functions[]` entry gains `aliases: ["qa", …]` — the **named** aliases pointing at that version,
sorted, `[]` when none. A version that is only aliased (not live, not the newest published) appears
with `role: "alias"`, `mode: "lazy"`, and the host **serves** it (unlike `candidate`, which it only
verifies). `unload` follows: an aliased version is in `functions`, so it is never unloaded while
pointed at. Bytes stay deterministic (aliases sorted; entries by (address, version)).

## 6. fcdev and the SPA

- `fn promote <address> --version <n> [--alias <name>]` (default `live`); `fn alias list|delete`.
  `fn status` shows the aliases; `fn domain claim` help says a claim covers the zone.
- SPA: Versions tab gains "Point alias…" (name + version, READY rows only) and an Aliases table
  with delete (live disabled); Public routes tab shows `aliasPrefixes` and, for each, the derived
  hostname (`qa-myapp.acme.com → alias qa`); the domain claim drawer says "covers every hostname
  under it"; the domain list shows a zone's routed hostnames (from `GET /api/function-routes`
  filtered by covering — a new `?zone=` filter, or client-side).

## 7. Deployment

`docs/deployments.md`: the load balancer forwards `*.<zone>` to the function hosts and holds a
wildcard certificate per zone (owner IaC). fcdev: `*.localhost` already resolves to loopback on
macOS/Linux, so `qa-hello.localhost:8091` works with no setup.

## 8. Load-bearing behaviours (one mutant per condition; absence as well as presence)

Test column: blank where no single named test was found pinning the row (see J4 hand-off) rather
than guessing.

| # | Behaviour | Mutant | Test |
|---|---|---|---|
| Z1 | claim `acme.com`; publish with `myapp.acme.com` succeeds; with `myapp.other.com` ⇒ `PUBLIC_HOSTNAME_NOT_VERIFIED` | equality instead of covering | `FunctionTriggerSyncTest#publishSucceedsWithAHostnameCoveredByAZoneClaimOfTheSameOwner` / `#publishRefusesAHostnameUnderAnUnrelatedApex` |
| Z2 | claim `api.acme.com` after `acme.com` (any owner) ⇒ `DOMAIN_TAKEN`; and the reverse order | drop either direction | `FunctionDomainApiTest#claimOfASubHostnameAfterTheApexIsAlreadyClaimedIsDomainTaken` / `#claimOfTheApexAfterASubHostnameIsAlreadyClaimedIsDomainTaken` (`#claimNestingIsRefusedEvenForTheSameOwner` too) |
| Z3 | a one-label claim ⇒ rejected | drop the label count | `HostnameTest#labelsRejected` (`"localhost"` case) — **note**: implemented as `HOSTNAME_INVALID` via `Hostname.parse`'s own ≥2-label floor, not a distinct `DOMAIN_INVALID` code as this spec originally drafted (`ClaimFunctionDomain`'s own doc comment records the decision); no claim-route-specific test was added since the floor is enforced before the operation runs |
| Z4 | release `acme.com` while `myapp.acme.com` is routed ⇒ `DOMAIN_IN_USE` | equality instead of covering | `FunctionDomainApiTest#releaseOfAZoneIsDomainInUseWhileADeeperHostnameIsRouted` |
| Z5 | `GET /api/function-domains/qa-myapp.acme.com` ⇒ the `acme.com` claim; another client's ⇒ 404 | — | `FunctionDomainApiTest#z5GetDomainByADeeperHostnameResolvesToTheZonesClaimReachableIs200OutOfReachIs404` |
| A1 | `PUT …/aliases/qa {version: 2}` with v2 READY ⇒ 200; v2 PUBLISHED ⇒ `VERSION_NOT_READY`; wiring (subscriptions, schedules, routes) unchanged after it — assert the trigger objects' count and the live pointer | apply live's wiring | `PublishPromoteRetireTest#promotingANamedAliasRequiresReadyJustLikeLive` / `#promotingANamedAliasRunsNoWiringAndLeavesLiveAndWiringUnchanged` |
| A2 | delete `live` ⇒ `ALIAS_PROTECTED`; delete `qa` ⇒ 204 and gone from the list | — | `FunctionApiTest#deleteAliasProtectsLiveRefusesUnknownAndRemovesANamedAlias` (`FunctionTest#removingLiveIsProtected` / `#removingANamedAliasDropsItAndKeepsOthers` at the domain-model level) |
| A3 | retire the version `qa` points at ⇒ `VERSION_ALIASED` naming `qa` | skip the check | `PublishPromoteRetireTest#retireRefusesAVersionANamedAliasPointsAtNamingItAndSucceedsOnceMoved` |
| P1 | manifest `aliasPrefixes: ["live"]` / duplicate / `"QA"` ⇒ `ROUTE_INVALID`; `[]` and absent accepted | — | `ManifestTest#aliasPrefixesRejectsLive` / `#aliasPrefixesRejectsDuplicate` / `#aliasPrefixesRejectsUppercase` / `#aliasPrefixesAbsentDefaultsEmpty` / `#aliasPrefixesEmptyArrayAccepted` |
| P2 | route table: exact `myapp.acme.com` ⇒ live; `qa-myapp.acme.com` with `qa` opted in ⇒ alias `qa`; without ⇒ 404; `qa-my-app.acme.com` splits at the first `-`; an exact route on `qa-myapp.acme.com` beats the derivation | each rule | `PublicRouteTableTest#aliasPrefixOptedInResolvesToTheBaseHostnamesRoute` / `#aliasPrefixNotOptedInIsNoMatch` / `#aliasPrefixSplitsAtTheFirstDash` / `#anExactRouteOnTheDerivedHostnameBeatsTheDerivation` / `#derivationAppliesOnlyOneLevel` / `#noDashInFirstLabelNeverDerives` |
| P3 | end to end on the host: v1 live, v2 aliased `qa` with a different response body; `Host: qa-hello.localhost` returns v2's body, `Host: hello.localhost` v1's; `Host: staging-hello.localhost` 404 | resolve by address only | `FnHttpServerPublicListenerTest#aliasPrefixedHostnameServesTheAliasedVersionExactServesLiveUnoptedInPrefixIs404` |
| D1 | desired state: the aliased-only version appears with `role: alias`, `aliases: ["qa"]`, and is not in `unload` | drop it from `functions` | `DesiredStateTest#anAliasOnlyVersionAppearsWithRoleAliasIsNeverUnloadedAndCarriesItsAliasesSorted` |
| D2 | the reconciler serves `role: alias` and still only verifies `candidate` | serve candidates | `ReconcilerTest#entryForAliasResolvesAnAliasEntryButNeverACandidateEvenIfItCarriesTheAliasName` |
| U1 | SPA vitest: alias promote drawer, aliases table, derived hostnames shown | — | `frontend/tests/function-versions-tab.test.ts` "promote dialog sends the typed alias name, not always live" / "disables the alias Delete button for live and enables it for a named alias" / "keeps Promote enabled for the live version, but disables the dialog's own submit only for an alias that already points there" (J4 fix: `canPromoteRow` originally gated the whole row on `!v.live`, which blocked pointing any OTHER alias at an already-live version — moved the ALIAS_UNCHANGED check into the dialog, per-alias); `frontend/tests/function-public-routes-tab.test.ts` "renders each opted-in alias prefix's derived hostname" |
| E1 | e2e: claim `hello.localhost`, deploy with `aliasPrefixes: ["qa"]`, point `qa` at v1, curl `qa-hello.localhost:8091/healthz` ⇒ 200 | — | `e2e/tests/functions.spec.ts` "functions › claim a domain, publish, configure, promote, and reach the function" (package J4) |

## 9. Slices

J1 zones (platform + fcdev help + SPA claim text) · J2 named aliases (platform + fcdev + SPA) ·
J3 prefixes (manifest, migration V15, sync, desired state, host route table + registry, SPA routes
tab) · J4 e2e + docs (`functions.md`, `function-service-overview.md`, `deployments.md`).
J1 and J2 are independent; J3 needs both.
