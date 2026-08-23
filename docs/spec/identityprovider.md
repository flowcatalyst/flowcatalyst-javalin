# Identity provider — behavioural spec

The contract for `io.flowcatalyst.platform.identityprovider`. Derived from the
lockfile (`/api/identity-providers*` — five operations) plus the validation
rules, authorization placement, orchestration rules, error codes and domain
events the aggregate embodies. The Java is written *from* this; tests assert
it. Questions marked **load-bearing or accident?** need an owner ruling —
until ruled on, the behaviour is kept.

## 1. Aggregate

An identity provider (IdP) is how a person proves who they are: the seeded
**internal** provider (password auth) or an external **OIDC** provider
(Entra, Keycloak, Google…). An IdP is deliberately **not** bound to any
client or plane — its job is authentication only; which domains route to it
is the email-domain mapping table's business, and which client's portal
identity a portal login yields is the OAuth client's linkage, never an IdP
property (owner decision 2026-08-20). IdPs are **anchor-only** resources:
there is no per-resource dimension, so every gate on this surface is the
handler's `requireAnchor` and every use case is `publicAccess`.

| Field | Type | Notes |
|---|---|---|
| `id` | `idp_` + 13-char TSID | generated on create |
| `code` | string, unique, immutable | stored **verbatim** (not trimmed) — **accident?** (update trims the name; create trims nothing) |
| `name` | string, required | verbatim on create, **trimmed** on update — **accident?** |
| `type` | `INTERNAL` \| `OIDC` | lenient read: anything but `OIDC` → `INTERNAL` (wire and stored) |
| `oidcIssuerUrl` | string, optional | required (non-blank) when `type=OIDC` **at create only** |
| `oidcClientId` | string, optional | required (non-blank) when `type=OIDC` **at create only** |
| `oidcClientSecretRef` | string, optional | the **at-rest** secret ref (§5): `encrypted:…`, an external `scheme://ref`, or `literal:…`; never plaintext; never on the wire — only `hasClientSecret` |
| `oidcMultiTenant` | boolean | default `false` |
| `oidcIssuerPattern` | string, optional | regex the multi-tenant issuer must match |
| `allowedEmailDomains` | list of domain, **derived** | the domains whose `tnt_email_domain_mappings` row routes to this IdP, hydrated on read in `email_domain` order; **never persisted by this aggregate** (the mapping table is the single source of truth for domain → IdP routing, §4) |
| `syncRolesFromIdp` | boolean | default `false`; whether OIDC logins through this IdP reconcile the user's `IDP_SYNC` roles from the token's `roles` claim (Go migration 040 moved it here from the mapping) |
| `allowedRoleIds` | list of role id | which platform roles this IdP may confer via role sync; **empty = no restriction**; junction `oauth_identity_provider_allowed_roles`, replaced wholesale on persist, read in `role_id` order |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Optional strings are `null` inside the JVM; a blank incoming value
(`""`) **clears** the field (stored `NULL`). **Deviation:** Go stores a
client-sent `""` verbatim, and `hasClientSecret` is then `true` for an
empty `""` secret (Go tests `!= nil`) — Java reports `false`. **accident?**
(kept as a deliberate deviation: `hasClientSecret` must mean a secret exists).

The seeded internal provider has `code=internal`, `type=INTERNAL`
(`seeder.md`): it is the fallback target when a domain is released from an
OIDC provider and cannot be deleted. "The internal provider" is identified
by its **code**; "an INTERNAL-type provider" (the direction of a move, §4)
by its **type** — an admin may create further `INTERNAL`-typed providers.

## 2. State machine

None. The only rules are on delete:

| Transition | Precondition | Error | Status |
|---|---|---|---|
| delete | `code ≠ internal` | `INTERNAL_IDP_PROTECTED` "The internal identity provider cannot be deleted" (business rule) | 400 |
| delete | no mapping routes to the IdP (`allowedEmailDomains` empty) | `DOMAINS_STILL_MAPPED` "Identity provider still routes email domains (a, b); move or delete those mappings first" (conflict) | 409 |

Checked in that order. A dangling mapping would silently flip its domain's
users to the internal password prompt — move or delete the mappings first.

## 3. HTTP surface (lockfile)

All routes require a bearer and an **anchor** principal (403 `ANCHOR_REQUIRED`
otherwise; unauthenticated → 403 `UNAUTHENTICATED`). Every error is the
`ErrorModel` envelope `{"error": CODE, "message": …, "details"?: …}`.

| Method / path | Gate | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/identity-providers` | anchor | — | 200 `IdentityProviderListResponse` `{identityProviders: [IdentityProviderResponse], total}` | ordered by `code`; `total` = list size |
| `POST /api/identity-providers` | anchor | `CreateIdentityProviderRequest` → `CreateCommand` | **201 `IdentityProviderResponse`** (the full provider, re-read after commit — the SPA's create toast reads `name`) | secret ref converted to its at-rest form **in the handler, before the command is built** (§5) |
| `GET /api/identity-providers/{id}` | anchor | — | 200 `IdentityProviderResponse` | 404 `IdentityProvider_NOT_FOUND` |
| `PUT /api/identity-providers/{id}` | anchor | `UpdateIdentityProviderRequest` → `UpdateCommand(id, …)` | **200 `IdentityProviderResponse`** (re-read; the SPA collapses on a 204) | same secret handling |
| `DELETE /api/identity-providers/{id}` | anchor | `DeleteCommand(id)` | 204 | |

`IdentityProviderResponse`: `id, code, name, type, oidcIssuerUrl?,
oidcClientId?, hasClientSecret, oidcMultiTenant, oidcIssuerPattern?,
allowedEmailDomains[], syncRolesFromIdp, allowedRoleIds[], createdAt,
updatedAt` — the secret ref itself is **never serialised**; optional strings
are omitted when `null`; the two lists are always present.

`CreateIdentityProviderRequest`: `code, name, type, oidcIssuerUrl?,
oidcClientId?, oidcClientSecretRef?, oidcMultiTenant, oidcIssuerPattern?,
allowedEmailDomains?, primaryClientId?, syncRolesFromIdp?, allowedRoleIds?`.
`UpdateIdentityProviderRequest`: the same minus `code`/`type`, every field
optional (`null` = untouched; an absent `oidcMultiTenant` / `syncRolesFromIdp`
is untouched, not `false`).

## 4. Operations

### Validation (validate phase, pure)

| Operation | Rule | Code |
|---|---|---|
| Create | `code` blank | `CODE_REQUIRED` |
| Create | `name` blank | `NAME_REQUIRED` |
| Create | `type` parses to `OIDC` and `oidcIssuerUrl` blank | `OIDC_ISSUER_REQUIRED` |
| Create | `type` parses to `OIDC` and `oidcClientId` blank | `OIDC_CLIENT_ID_REQUIRED` |
| Create / Update | any listed email domain (after normalisation, blanks skipped) is not DNS-like: no `.`, or contains ` `, `/`, `@` | `INVALID_EMAIL_DOMAIN` |
| Update | `id` blank | `ID_REQUIRED` |
| Update | `name` supplied but blank | `NAME_REQUIRED` |
| Delete | `id` blank | `ID_REQUIRED` |

Checked in that order. Update does **not** re-check the OIDC fields — an
OIDC provider's issuer / client id can be blanked — **accident?**

Domain normalisation (create and update): each listed domain is trimmed and
lower-cased; blank entries are skipped; duplicates (after normalisation)
are dropped keeping first position; the rule is the email-domain mapping
aggregate's one parser (`EmailDomain.parse`), so both aggregates accept and
store the same form.

### Authorization

| Where | Rule |
|---|---|
| Handler | `requireAnchor` on every route |
| Use cases | `publicAccess` — an IdP has no per-resource dimension |

### Create (one transaction, `TxOperation` → `CreateResult`)

1. Another IdP with the same `code` → 409 `CODE_EXISTS` "Identity provider with code '<code>' already exists".
2. Persist the IdP (`allowedRoleIds` from the command, `[]` when absent) and emit `created`; audit `CreateCommand`.
3. For each normalised domain, **map it** (below); collect `domainsCreated` / `domainsClaimed`.

`CreateResult`: `identityProviderId, code, domainsCreated[], domainsClaimed[]`.

### Update (one transaction, `TxOperation` → `UpdateResult`)

1. Load by id → 404 `IdentityProvider_NOT_FOUND`.
2. Apply the non-null fields (`name` trimmed; `allowedRoleIds` replaced wholesale when supplied — `[]` clears; `code` and `type` immutable); persist; emit `updated`; audit `UpdateCommand`.
3. `allowedEmailDomains` **absent** → done (mappings untouched).
4. Otherwise it is the **desired set** of domains routed to this IdP:
   - the current set is read **before** any change (every mapping whose `identity_provider_id` is this IdP);
   - each desired domain is **mapped** (below) → `domainsCreated` / `domainsClaimed`;
   - unless this IdP **is** the internal provider (`code=internal`), every current domain not in the desired set is **released**: its mapping is moved to the internal provider (looked up by code; missing → 500 `SEED` "internal identity provider missing; cannot release domain '<d>'") — the mapping and its client / 2FA config survive, only the routing changes; `domainsReleased` += domain; `usersReset` += users converted (below). Deleting a mapping outright stays an explicit act on the email-domain page.

`UpdateResult`: `identityProviderId, code, domainsCreated[], domainsClaimed[], domainsReleased[], usersReset`.

### Mapping one domain to an IdP (`mapDomain`) — shared by create and update

| Existing mapping for the domain | Effect | Reported as |
|---|---|---|
| none | new mapping: `scopeType` = `CLIENT` when `primaryClientId` given else `ANCHOR`, `primaryClientId` from the command, no grants, 2FA off; emits the mapping aggregate's `created` | `created` |
| routes to this IdP already | nothing | — |
| routes elsewhere | `primaryClientId` filled **only when** the command gives one **and** the mapping has none (an existing client link is never overwritten); then **moved** to this IdP (below) | `claimed` |

### Moving a mapping to a target IdP — the email-domain mapping aggregate's rule, applied here

Re-point the mapping and emit the mapping aggregate's `provider-changed`
event (`from` → `to`), audited under this aggregate's command. Then,
**only when the target is `INTERNAL`-typed**, convert the domain's
OIDC-provisioned users back to internal auth (`idp_type=OIDC` USER
principals on the domain: `idp_type → INTERNAL`, `external_idp_id → NULL`,
their `IDP_SYNC`-sourced role rows removed; internal / hybrid users and
admin-assigned roles untouched; no per-user event). Moving toward an OIDC
provider touches no principal (the OIDC callback matches users by email).

The converted-user count feeds `usersReset` only for **releases**; a
**claim** toward an `INTERNAL`-typed IdP still converts the users but the
count is discarded — **accident?** (kept).

### Delete (`Operation`, `Plan.delete`)

Load → 404; `requireDeletable` (§2); hard-delete the row, its
`allowed_roles` junction and any legacy `oauth_identity_provider_allowed_domains`
rows; emit `deleted`; audit `DeleteCommand`.

## 5. The OIDC client secret

The column `oidc_client_secret_ref` holds a **secret ref** in the shared
grammar (`encryption.md` §3). The wire field `oidcClientSecretRef` is
converted to its at-rest form **in the handler, before the command is
built**, so the audit row (`operation_json` = the command) never carries a
plaintext secret:

| `FLOWCATALYST_APP_KEY` | Incoming | Stored |
|---|---|---|
| configured | plaintext / `encrypt:<pt>` | `encrypted:<v1 envelope>` |
| configured | `encrypted:…`, external `scheme://…`, `literal:…` | unchanged |
| configured | `encrypted:` + non-base64 | 400 `INVALID_SECRET_REF` (never stored) |
| not configured | `encrypted:…`, external, `literal:…` | unchanged |
| not configured | plaintext / `encrypt:<pt>` | 400 `ENCRYPTION_NOT_CONFIGURED` "cannot store OIDC client secret: FLOWCATALYST_APP_KEY is not configured" — never stored raw |
| either | omitted | create: no secret; update: untouched |
| either | `""` | create: no secret; update: **clears** the secret (`NULL`) |

A malformed configured key is fatal at boot (`encryption.md` §1). Reads
never decrypt here — login (not this unit) does.

## 6. Conflicts, not-found and internal errors (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another IdP has the same code | `CODE_EXISTS` | 409 |
| Update / Delete | no IdP with that id | `IdentityProvider_NOT_FOUND` | 404 |
| Update (release) | no IdP with `code=internal` | `SEED` (internal) | 500 |
| Delete | `code=internal` | `INTERNAL_IDP_PROTECTED` | 400 |
| Delete | mappings still route to it | `DOMAINS_STILL_MAPPED` | 409 |
| Any | handler: secret not storable | `ENCRYPTION_NOT_CONFIGURED` / `INVALID_SECRET_REF` | 400 |

## 7. Domain events

Source `platform:admin`; spec version `1.0`; subject
`platform.identityprovider.{id}`; message group
`platform:identityprovider:{id}` on every event. `data` = `{identityProviderId, code}` for all three.

| Type | When |
|---|---|
| `platform:admin:identity-provider:created` | create |
| `platform:admin:identity-provider:updated` | update (always, even when nothing changed) |
| `platform:admin:identity-provider:deleted` | delete |

The orchestration additionally emits the **mapping aggregate's** events on
its subjects (`platform.emaildomainmapping.{mappingId}`):
`…:email-domain-mapping:created` for each new mapping and
`…:email-domain-mapping:provider-changed` for each claim / release — each
with its own `aud_logs` row whose `operation` is this aggregate's command
(`CreateCommand` / `UpdateCommand`). Every event writes one `msg_events` row
and one `aud_logs` row (`entity_type = Identityprovider` for the IdP's own
events) in the one transaction.

## 8. Persistence

Table `oauth_identity_providers`, upsert `ON CONFLICT (id)`; `created_at`
insert-only; `updated_at` stamped `now()` at persist time. Junction
`oauth_identity_provider_allowed_roles` (`identity_provider_id, role_id`,
serial id) is cleared and re-inserted on every persist. `allowedEmailDomains`
is **read-only** here: hydrated in one `IN` query over
`tnt_email_domain_mappings` (`email_domain` order) — a cross-aggregate read
by design (the mapping table owns routing); the mapping writes go through the
mapping aggregate's repository and events. Delete clears the allowed-roles
junction and the legacy `oauth_identity_provider_allowed_domains` junction
(dead since mappings took over routing; cleared so migrated installs do not
accumulate orphans), then the row. Reads: by id, by code (exact), all (by
code).

## 9. Cross-aggregate dependencies (flagged)

- The mapping orchestration (§4) uses the **Java email-domain mapping
  package** directly (`EmailDomainMappingRepository`, `EmailDomainMapping`,
  `EmailDomainMappingEvents`, `EmailDomain.parse`) — the same direction of
  dependency as Go (`identityprovider/operations → emaildomainmapping/operations`).
  It is isolated in one package-private helper (`operations/DomainRouting`)
  so a change in that package touches one file. The "move a mapping to a
  provider" rule (re-point + `provider-changed` + reset OIDC users when the
  target is internal) is restated there; it should become one shared helper
  in the mapping aggregate's operations package when that package is next
  audited (Go exports it as `MoveMappingTx`).
- The OIDC-user conversion uses the mapping repository's **temporary**
  `resetOidcUsersToInternal` (a raw write into `iam_principals` /
  `iam_principal_roles`) until the principal aggregate lands.
- The encryption service is wired from `EnvReader.system()` in `Platform`
  (`Env` does not carry the app key yet); `Optional<Encryption>` is modelled
  as the sealed `ClientSecretEncryption` (`Enabled | Disabled`).

## 10. Open questions for the owner (summary)

1. `code` / `name` stored verbatim on create but `name` trimmed on update — trim both on create?
2. Update never re-validates OIDC required fields — intended?
3. A client-sent `""` clears an optional field (Java) vs stored `""` (Go); `hasClientSecret` for an empty secret is `false` (Java) vs `true` (Go) — confirm the deviation.
4. A claim toward an `INTERNAL`-typed IdP converts OIDC users but does not count them in `usersReset`.
5. Legacy `oauth_identity_provider_allowed_domains` rows are only ever cleared on delete — drop the table in a later migration?
6. `update` emits `updated` even when no field changed — keep?
