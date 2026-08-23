# Email-domain mapping — behavioural spec

The contract for `io.flowcatalyst.platform.emaildomainmapping`. Derived from
the lockfile (`/api/email-domain-mappings*` — eight operations) plus the
validation rules, authorization placement, error codes, domain events and
persistence rules the aggregate embodies. The Java is written *from* this;
tests assert it. Questions marked **load-bearing or accident?** need an
owner ruling — until ruled on, the behaviour is kept.

## 1. Aggregate

An email-domain mapping routes every user whose email ends in `emailDomain`
to one **identity provider** at login / signup, and carries the client
grants and the second-factor policy applied to users who sign up through
that domain. Mappings are **anchor-only** platform configuration: there is
no per-resource dimension, so every gate on this surface is the handler's
`requireAnchor` and every use case is `publicAccess`.

Role-sync configuration (`sync_roles_from_idp`, the `allowed_roles`
junction) **moved to the identity provider** (Go migration 040). The
columns remain in the schema, dead: this aggregate never reads or writes
`tnt_email_domain_mappings.sync_roles_from_idp` (left at its DB default
`false`) and only clears `tnt_email_domain_mapping_allowed_roles` on
delete so migrated installs do not accumulate orphans.

| Field | Type | Notes |
|---|---|---|
| `id` | `edm_` + 13-char TSID | generated on create |
| `emailDomain` | string, unique | **trimmed and lower-cased** before validation and storage; must contain a `.` and none of ` `, `/`, `@`; immutable after create |
| `identityProviderId` | string, required | **not** validated against the IDP table on create (**accident?** — a dangling id is accepted); immutable via update, changed only by *move-provider* (§2) |
| `scopeType` | `ANCHOR` \| `PARTNER` \| `CLIENT` | immutable after create (**accident?** — no route changes it) |
| `primaryClientId` | string, optional | required at create when scope is `PARTNER`/`CLIENT`; replaced wholesale by update (absent ⇒ cleared, even on a `CLIENT` mapping — **accident?**) |
| `additionalClientIds` | list of client id | junction `…_additional_clients`; update: absent = unchanged, `[]` = clear |
| `grantedClientIds` | list of client id | junction `…_granted_clients`; same update rule |
| `requiredOidcTenantId` | string, optional | replaced wholesale by update (absent ⇒ cleared) |
| `require2fa` | boolean | default `false`; second-factor enforcement for internal-auth users (inert for OIDC domains) |
| `allowed2faMethods` | list of `TOTP` \| `EMAIL_PIN` | junction `…_2fa_methods`; **must be non-empty when `require2fa`**; update: absent = unchanged, `[]` = clear |
| `rememberDeviceEnabled` | boolean | default `false`; only meaningful with `require2fa` |
| `rememberDeviceDays` | int | default **30** (domain constant); create applies the command's value only when `> 0` (0 / negative / absent ⇒ 30); update applies any supplied value, including `0` and negatives (**accident?**) |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Client ids on the mapping are **not** validated against `tnt_clients`
(**accident?** — kept).

Lenient enum reads, as everywhere (**accident?** — masks corruption;
kept): unknown stored `scope_type` → `ANCHOR`; a stored `method` that is
not `TOTP`/`EMAIL_PIN` is **dropped** from `allowed2faMethods` on read
(never an error — the Go reader passed it through verbatim; such a row
cannot be produced through this API). Both enums keep the wire strict
(`INVALID_SCOPE_TYPE`, `INVALID_2FA_METHOD`, §4): the stored reader and
the wire reader are two methods on the enum, never one lenient reader
used for both.

The seeder creates one mapping for the bootstrap admin's domain
(`ANCHOR`, internal IDP, 2FA off, `rememberDeviceDays 30`) — the table is
never assumed empty.

## 2. State machine

There is no status. Transitions:

| Transition | Effect | Precondition / error |
|---|---|---|
| `create` | new `edm_` id, normalised domain, empty lists, `rememberDeviceDays = 30` | domain not already mapped → `DOMAIN_ALREADY_MAPPED` (409) |
| `update` | one `update(Changes)` transition: replaces the grant / 2FA fields as listed in §1; `updatedAt = now` | resulting (merged) 2FA policy consistent (§4) |
| `moveToProvider(target)` | `identityProviderId = target`; when the **target is `INTERNAL`**, every `USER` principal on the domain whose `idp_type = OIDC` is converted back to internal auth (`idp_type = INTERNAL`, `external_idp_id = NULL`, `IDP_SYNC`-sourced role rows removed); moving **toward an OIDC** provider touches no principal (the OIDC callback matches existing users by email) | target differs from current → `ALREADY_ON_PROVIDER` (409); target exists → `IdentityProvider_NOT_FOUND` (404) |
| `delete` | hard delete of the row and every junction row (incl. legacy `allowed_roles`) | — |

The principal reset is a persistence detail of the move: no per-user
event; the count is reported as `usersReset` on the result and the
`provider-changed` event's audit row covers the change. **load-bearing**
(without it OIDC-provisioned users have no password hash and the reset
flow refuses them — hard lock-out).

## 3. HTTP surface (lockfile)

Every error is the `ErrorModel` envelope `{"error": CODE, "message": …}`.
Unless stated, the gate is **anchor-only**: unauthenticated → 403
`UNAUTHENTICATED`, non-anchor → 403 `ANCHOR_REQUIRED`. The
`platform:iam:email-domain-mapping:*` permissions exist in the catalogue
but no route checks them (**load-bearing or accident?**).

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/email-domain-mappings` | anchor | — | 200 `MappingListResponse` `{mappings[], total}` | every mapping ordered by `emailDomain`; `total = mappings.length` |
| `POST /api/email-domain-mappings` | anchor | `CreateMappingRequest` → `CreateCommand` | 201 `CreatedResponse` `{id}` | |
| `GET /api/email-domain-mappings/lookup?domain=` | **none** — reachable by any request the bearer middleware lets through, including unauthenticated (**load-bearing or accident?** — the login page uses the *public* `/auth/check-domain`, so this looks like an accident) | — | 200 `MappingResponse`, or 200 `{"found": false}` when unmapped | `domain` absent/empty → 400 `DOMAIN_REQUIRED` `domain query param is required`; matched **as given** (not lower-cased — **accident?**) |
| `GET /api/email-domain-mappings/by-domain/{domain}` | anchor | — | 200 `MappingResponse` | 404 `EmailDomainMapping_NOT_FOUND` with the domain in the message; matched as given |
| `GET /api/email-domain-mappings/{id}` | anchor | — | 200 `MappingResponse` | 404 `EmailDomainMapping_NOT_FOUND` |
| `PUT /api/email-domain-mappings/{id}` | anchor | `UpdateMappingRequest` → `UpdateCommand(id from path)` | 204, empty body | |
| `POST /api/email-domain-mappings/{id}/move-provider` | anchor | `MoveProviderRequest{identityProviderId}` → `MoveProviderCommand` | 200 `MoveProviderResponse` | |
| `DELETE /api/email-domain-mappings/{id}` | anchor | `DeleteCommand` | 204 | |

Route precedence: `/lookup` and `/by-domain/…` are literal paths and must
win over `/{id}`.

Wire shapes (field order as listed; optional fields omitted when `null`;
lists always present):

| Schema | Fields |
|---|---|
| `CreateMappingRequest` | `emailDomain`*, `identityProviderId`*, `scopeType`*, `primaryClientId`, `additionalClientIds[]`, `grantedClientIds[]`, `requiredOidcTenantId`, `require2fa` (default false), `allowed2faMethods[]`, `rememberDeviceEnabled` (default false), `rememberDeviceDays` |
| `UpdateMappingRequest` | `primaryClientId`, `additionalClientIds[]`, `grantedClientIds[]`, `requiredOidcTenantId`, `require2fa`, `allowed2faMethods[]`, `rememberDeviceEnabled`, `rememberDeviceDays` — all optional; absent semantics per §1 |
| `MoveProviderRequest` | `identityProviderId`* |
| `MappingResponse` | `id, emailDomain, identityProviderId, identityProviderName?, scopeType, primaryClientId?, additionalClientIds[], grantedClientIds[], requiredOidcTenantId?, require2fa, allowed2faMethods[], rememberDeviceEnabled, rememberDeviceDays, createdAt, updatedAt` |
| `MappingListResponse` | `mappings[], total` |
| `MoveProviderResponse` | `mappingId, emailDomain, fromIdentityProviderId, toIdentityProviderId, usersReset` |
| lookup miss | `{"found": false}` |
| `CreatedResponse` | `id` |

`identityProviderName` is the display name of the mapping's IDP, resolved
on read (omitted when the IDP row does not exist). Timestamps RFC 3339
with 6 fractional digits, `Z`.

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create | `emailDomain` non-blank after trim | `EMAIL_DOMAIN_REQUIRED` | `Email domain is required` |
| Create | normalised domain contains `.` and none of ` `, `/`, `@` | `INVALID_EMAIL_DOMAIN` | `Email domain must be a valid DNS name (e.g. example.com)` |
| Create | `identityProviderId` non-blank | `IDP_REQUIRED` | `identityProviderId is required` |
| Create | `scopeType` ∈ {`ANCHOR`,`PARTNER`,`CLIENT`} (exact, case-sensitive) | `INVALID_SCOPE_TYPE` | `scopeType must be ANCHOR, PARTNER, or CLIENT` |
| Create | `primaryClientId` present when scope is `PARTNER`/`CLIENT` | `PRIMARY_CLIENT_REQUIRED` | `primaryClientId is required for PARTNER and CLIENT scope` |
| Create / Update | every `allowed2faMethods` entry ∈ {`TOTP`,`EMAIL_PIN`} | `INVALID_2FA_METHOD` | `allowed2faMethods entries must be TOTP or EMAIL_PIN` |
| Create | `require2fa` ⇒ `allowed2faMethods` non-empty | `2FA_METHOD_REQUIRED` | `at least one 2FA method must be allowed when require2fa is set` |
| Update | the **merged** policy satisfies `require2fa` ⇒ non-empty methods (checked in execute, after the load) | `2FA_METHOD_REQUIRED` | same |
| Update / Delete / Move | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Move | `identityProviderId` non-blank | `IDP_REQUIRED` | `identityProviderId is required` |

Rules are checked in the order listed. Malformed JSON body → 400
`INVALID_JSON` (transport).

The domain format is what login routing (`/lookup`, the future
`/auth/check-domain`) matches live input against, so it is pinned here
and by `EmailDomainMappingTest`'s accept / reject tables (one parser,
`EmailDomain.parse`, used by the create command and the entity factory):

| Rule | Accepted | Rejected |
|---|---|---|
| **Blank** — `null`, `""`, whitespace-only → `EMAIL_DOMAIN_REQUIRED`, not `INVALID_EMAIL_DOMAIN` | — | — |
| **Trim** — leading/trailing whitespace (anything `String.trim` removes) dropped before matching and storage | `'  example.com  '` → `example.com` | — |
| **Case** — lower-cased (`Locale.ROOT`) before matching and storage; uniqueness is on the lower-cased form | `Example.COM` → `example.com` | — |
| **Dot** — at least one `.` | `a.b`, `sub.domain.example.co.uk`, `xn--bcher-kva.example` | `nodot`, `localhost` |
| **Space** — no interior `' '` | — | `exam ple.com` |
| **Slash** — no `/` (no paths, no schemes) | — | `example.com/path`, `https://example.com` |
| **At** — no `@` (a domain, not an address) | — | `user@example.com`, `@example.com` |
| **Edge** — nothing else is checked: labels, leading/trailing dots, underscores, interior tabs all pass (**accident?**) | `example.`, `.com`, `exa_mple.com`, `.` | — |

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | `requireAnchor` on every route except `GET /lookup` (no gate, §3) |
| Use cases (all four) | `publicAccess` — no per-resource dimension; the handler's gate is the whole check |
| Reads | handler only |

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Create | another mapping has the same normalised domain | `DOMAIN_ALREADY_MAPPED` `Email domain '<d>' is already mapped` | 409 |
| Update / Delete / Move | no mapping with that id | `EmailDomainMapping_NOT_FOUND` `EmailDomainMapping not found: <id>` | 404 |
| Move | target equals the current provider (checked **before** the target lookup) | `ALREADY_ON_PROVIDER` `Email domain '<d>' is already mapped to that identity provider` | 409 |
| Move | no identity provider with the target id | `IdentityProvider_NOT_FOUND` `IdentityProvider not found: <id>` | 404 |

The DB also has `UNIQUE (email_domain)`; the operation checks first so the
error is the 409 envelope.

## 7. Domain events

Source is always `platform:admin`; spec version `1.0`; subject
`platform.emaildomainmapping.{id}`; **every** event carries message group
`platform:emaildomainmapping:{id}`. `data` omits nothing.

| Type | `data` fields |
|---|---|
| `platform:admin:email-domain-mapping:created` | `mappingId, emailDomain` |
| `platform:admin:email-domain-mapping:updated` | `mappingId, emailDomain` |
| `platform:admin:email-domain-mapping:deleted` | `mappingId, emailDomain` |
| `platform:admin:email-domain-mapping:provider-changed` | `mappingId, emailDomain, fromIdentityProviderId, toIdentityProviderId` |

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Emaildomainmapping`,
`entity_id = {id}`, `operation` = command record simple name —
`CreateCommand`, `UpdateCommand`, `DeleteCommand`, `MoveProviderCommand` —
`operation_json` = the command) in the same transaction as the row change.
The move's principal reset produces no event of its own (§2).

Note: the seeded event-type catalogue (`PlatformEventSchemas`) lists these
under `platform:admin:edm:*` while the emitted type is
`platform:admin:email-domain-mapping:*` — **load-bearing or accident?**
(outside this package; flagged only).

## 8. Persistence

`tnt_email_domain_mappings`: upsert `ON CONFLICT (id)` updating
`email_domain, identity_provider_id, scope_type, primary_client_id,
required_oidc_tenant_id, require_2fa, remember_device_enabled,
remember_device_days, updated_at`; `created_at` insert-only; `updated_at`
stamped `now()` at persist time; `sync_roles_from_idp` never written
(dead, §1). Junctions (`additional_clients`, `granted_clients`,
`2fa_methods`) are replaced wholesale on every persist (delete-then-insert
in the same transaction; no FK cascade exists). Delete clears the three
live junctions **and** the legacy `allowed_roles` junction, then the row.

Reads: by id; by domain (exact); by identity provider (by domain); all
(by domain). Junction rows are hydrated in one `IN` query per junction and
read in insertion (`id`) order.

Temporary cross-aggregate reads, owned here until the `identityprovider`
and `principal` aggregates land (flagged in the repository): identity
provider `id → (name, type)` for response enrichment and the move's
target check; the move's principal reset (`iam_principals` +
`iam_principal_roles`) — the one write outside this aggregate's tables,
kept because the move is incomplete without it (§2).

## 9. Open questions for the owner (summary)

1. `GET /lookup` has no authorization gate (any request, incl.
   unauthenticated, can read a domain's routing + client grants). Intended?
2. `identityProviderId` and the client ids are never validated against
   their tables on create/update. Intended?
3. Update replaces `primaryClientId` / `requiredOidcTenantId` wholesale
   (absent ⇒ cleared) and does not re-check `PRIMARY_CLIENT_REQUIRED` for
   `PARTNER`/`CLIENT` scopes. Intended?
4. `rememberDeviceDays`: create ignores `≤ 0` (→ 30) while update stores
   any value. Intended?
5. Lookups (`/lookup`, `/by-domain`) match the domain as given while
   storage is lower-cased. Intended?
6. The `email-domain-mapping:*` permissions exist but every route is
   anchor-only. Intended?
7. `scopeType` is immutable (no route changes it). Intended?
8. Lenient stored reads: unknown `scope_type` → `ANCHOR`; an unknown
   stored `method` is dropped on read (the Go passed it through verbatim;
   the alternative is to fail the read of every mapping on one bad junction
   row). Keep the lenient drop?
9. Seeded catalogue type `platform:admin:edm:*` vs emitted
   `platform:admin:email-domain-mapping:*`.
