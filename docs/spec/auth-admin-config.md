# Auth admin configuration — anchor domains, client auth configs, IdP role mappings

Extracted 2026-09-05 against Go HEAD (`9f7be62`), from
`internal/platform/auth/{entity.go,repository.go,operations/{anchor_domain,auth_config,idp_role_mapping,events}.go,api/{api.go,dto.go}}`,
`internal/sqlc/queries/auth.sql`, and `operations/ops_pg_test.go`. Three
small platform-level aggregates that Go keeps inside the `auth` package but
that have no dependency on the (unported) OAuth/OIDC machinery — they are
configuration read by it. Ported as one Java unit, package
`io.flowcatalyst.platform.authadmin`, so the auth port later finds them in
place. Behaviour tables only; `[C]` = contract, `[I]` = implementation detail.

## 1. Purpose and boundaries

- **AnchorDomain** — an e-mail domain whose users are *anchor* (platform
  operator) users. Read by the login flow (`MatchesEmail`: `email` lower-cased
  ends with `@<domain>`). No client dimension.
- **ClientAuthConfig** — per e-mail-domain login configuration: which scope
  (`ANCHOR|PARTNER|CLIENT`) such users get, which clients they are bound to
  (`primaryClientId`, `additionalClientIds`, `grantedClientIds`), and the
  provider (`INTERNAL` password login or `OIDC` with issuer/client/secret-ref).
- **IdpRoleMapping** — maps an upstream IdP role name (per `idpType`, e.g.
  `keycloak`, `entra`) to a platform role name (`app:role`). Read by IdP role
  sync (`principal` / `SyncIdpRoles`).

All eleven routes are **anchor-only** at the handler (`authedAnchor` →
`RequireAnchor`, reads included); the operations themselves are
`Authorize: Public` because there is no per-client resource to scope. Java:
`Auth.scoped` + `Checks.requireAnchor` in the handler, operations authorize
`Access.anyAuthenticated` (or equivalent) — mirror the placement, do not
push the anchor gate into the operation.

## 2. Data model (baseline `V1`, all three unchanged by 046–052)

| Table | Columns | Uniqueness |
|---|---|---|
| `tnt_anchor_domains` | `id VARCHAR(17)`, `domain VARCHAR(255)`, `created_at`, `updated_at` (all NOT NULL) | `domain` UNIQUE |
| `tnt_client_auth_configs` | `id`, `email_domain VARCHAR(255)`, `config_type VARCHAR(50)`, `primary_client_id VARCHAR(17) NULL`, `additional_client_ids JSONB` (array of client ids), `granted_client_ids JSONB`, `auth_provider VARCHAR(50)`, `oidc_issuer_url VARCHAR(500) NULL`, `oidc_client_id VARCHAR(255) NULL`, `oidc_multi_tenant BOOLEAN`, `oidc_issuer_pattern NULL`, `oidc_client_secret_ref NULL`, `created_at`, `updated_at` | `email_domain` UNIQUE |
| `oauth_idp_role_mappings` | `id`, `idp_role_name VARCHAR(200)`, `internal_role_name VARCHAR(200)` (wire name `platformRoleName`), `idp_type VARCHAR(50) NULL`, `created_at`, `updated_at` | `idp_role_name` UNIQUE (index, **not** including `idp_type` — see §8 D1) |

Ids: TSID with the entity's own prefix (`tsid.AnchorDomain`, `tsid.ClientAuthConfig`,
`tsid.IdpRoleMapping` — use the matching `EntityType` constants; add them if absent).
Persist is an upsert `ON CONFLICT (id) DO UPDATE` with `updated_at = now()`;
delete is a hard delete. Lists are unpaginated (`FindAll`), ordered by
`domain` / `email_domain` / `idp_role_name` ascending.

**Stored-enum reads are strict (X-06, Go `6cbe708`):** `config_type` and
`auth_provider` outside their sets fail the row with the platform
`CorruptRowException` pattern (`Corrupt…Exception(rowId)`), and a list
containing such a row fails as a whole. Pinned in Go by
`TestClientAuthConfigFindByID_Corrupt*FailsLoudly` and
`…FindAll_CorruptConfigTypeFailsTheWholeList`.

## 3. HTTP surface (lockfile) — 11 operations [C]

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| GET | `/api/anchor-domains` | — | 200 `AnchorDomainListResponse{items:[AnchorDomainResponse]}` | 401, 403 |
| POST | `/api/anchor-domains` | `CreateAnchorDomainRequest{domain}` | **201** `AnchorDomainResponse` | 400 `DOMAIN_REQUIRED` \| `INVALID_DOMAIN`; 409 `DOMAIN_EXISTS` |
| PUT | `/api/anchor-domains/{id}` | `UpdateAnchorDomainRequest{domain}` | **204** | 400 `INVALID_DOMAIN`; 404 `AnchorDomain` |
| DELETE | `/api/anchor-domains/{id}` | — | **204** | 404 |
| GET | `/api/auth-configs` | — | 200 `AuthConfigListResponse{items}` | |
| POST | `/api/auth-configs` | `CreateAuthConfigRequest` | **201** `AuthConfigResponse` | 400 (§4.2); 409 `DOMAIN_ALREADY_CONFIGURED` |
| PUT | `/api/auth-configs/{id}` | `UpdateAuthConfigRequest` (all optional) | **204** | 400 `INVALID_AUTH_PROVIDER`; 404 `AuthConfig` |
| DELETE | `/api/auth-configs/{id}` | — | **204** | 404 |
| GET | `/api/idp-role-mappings` | — | 200 `IdpRoleMappingListResponse{items}` | |
| POST | `/api/idp-role-mappings` | `CreateIdpRoleMappingRequest{idpType,idpRoleName,platformRoleName}` | **201** `IdpRoleMappingResponse` | 400 `FIELD_REQUIRED`; 409 `MAPPING_EXISTS` (§8 D1 — deliberate deviation) |
| DELETE | `/api/idp-role-mappings/{id}` | — | **204** | 404 |

`AnchorDomainResponse{id, domain, createdAt, updatedAt}`.
`AuthConfigResponse{id, emailDomain, configType, primaryClientId?, additionalClientIds[], grantedClientIds[], authProvider, oidcIssuerUrl?, oidcClientId?, oidcMultiTenant, oidcIssuerPattern?, oidcClientSecretRef?, createdAt, updatedAt}` —
the arrays are always present (`[]`, never null); the `?` fields are omitted when null.
`IdpRoleMappingResponse{id, idpType, idpRoleName, platformRoleName, createdAt, updatedAt}`.
Timestamps: microsecond ISO instants like every other aggregate.
There is no GET-by-id on any of the three.

## 4. Operations [C]

Command records named as Go's: `CreateAnchorDomainCommand`, `UpdateAnchorDomainCommand`,
`DeleteAnchorDomainCommand`, `CreateAuthConfigCommand`, `UpdateAuthConfigCommand`,
`DeleteAuthConfigCommand`, `CreateIdpRoleMappingCommand`, `DeleteIdpRoleMappingCommand`
(the simple name is the audit `operation` column). Every write is one
unit of work: row + event + audit row.

### 4.1 Anchor domains
- Normalise `domain` = trim, lower-case. Validate: blank → `DOMAIN_REQUIRED`
  (create) — update folds blank into `INVALID_DOMAIN`; must contain `.` and
  none of space `/` `@` → else `INVALID_DOMAIN`.
- Create: existing row with the same normalised domain → 409 `DOMAIN_EXISTS`.
- Update: replaces `domain` (normalised); a collision with **another** row's
  domain surfaces as the UNIQUE violation — Go has no pre-check here (§8 D2);
  Java pre-checks and answers 409 `DOMAIN_EXISTS`.
- Delete: hard delete by id; 404 when missing.

### 4.2 Client auth configs
- Create validation, in order: `emailDomain` normalised (trim, lower) must be
  non-blank and contain `.` → else `INVALID_EMAIL_DOMAIN`; `configType` ∈
  `ANCHOR|PARTNER|CLIENT` → else `INVALID_CONFIG_TYPE`; `authProvider` ∈
  `INTERNAL|OIDC` → else `INVALID_AUTH_PROVIDER`; when `OIDC`:
  `oidcIssuerUrl` non-blank → else `OIDC_ISSUER_REQUIRED`, `oidcClientId`
  non-blank → else `OIDC_CLIENT_ID_REQUIRED`.
- Create: existing config for the domain → 409 `DOMAIN_ALREADY_CONFIGURED`.
  Defaults: `additionalClientIds`/`grantedClientIds` absent → `[]`;
  `authProvider` default `INTERNAL` in the entity constructor but the wire
  requires it. All optional strings stored as given (null when absent).
- Update: only supplied fields change (`null` in the JSON = leave alone —
  Go uses pointer-absent semantics; Java: `Optional`/nullable request fields,
  an absent field is untouched). `authProvider` when supplied must parse.
  **No OIDC-completeness check on update** (§8 D3 — kept as Go, question
  raised).
- Delete: hard delete; 404 when missing.

### 4.3 IdP role mappings
- Validate: each of `idpType`, `idpRoleName`, `platformRoleName` non-blank →
  else `FIELD_REQUIRED` with message `<field> is required` (first missing
  wins, in that order). Stored as given (no normalisation).
- Create: Go inserts directly; a duplicate `idpRoleName` hits the UNIQUE
  index and Go answers **500** (no handling — §8 D1). Java: pre-check
  `findByIdpRoleName` → 409 `MAPPING_EXISTS` — a deliberate deviation
  recorded in §9.
- Delete: hard delete; 404 when missing.

## 5. Events [C]

Source `platform:admin`. Types
`platform:admin:anchor-domain:{created|updated|deleted}`,
`platform:admin:auth-config:{created|updated|deleted}`,
`platform:admin:idp-role-mapping:{created|deleted}`.
Subjects `platform.anchordomain.<id>` / `platform.authconfig.<id>` /
`platform.idprolemapping.<id>`; message groups `platform:anchordomain:<id>` /
`platform:authconfig:<id>` / `platform:idprolemapping:<id>`.
Data: anchor domain events carry `{anchorDomainId, domain}`; auth-config
events `{authConfigId, emailDomain}`; mapping created
`{mappingId, idpType, idpRoleName, platformRoleName}`, deleted
`{mappingId}` plus the same three. Event records must not shadow a
`DomainEvent` accessor (`DomainEventContractTest`).

## 6. Persistence [I]

jOOQ over `TNT_ANCHOR_DOMAINS`, `TNT_CLIENT_AUTH_CONFIGS`,
`OAUTH_IDP_ROLE_MAPPINGS`. JSONB arrays via `org.jooq.JSONB` + Jackson
(`List<String>`), written as `[]` never `null`. `idp_type` is nullable in
the schema: a legacy row with `NULL` reads back as `""`… **no**: reads map
`NULL` `idp_type` to an absent value and the response omits nothing — the
lockfile requires `idpType`; Java returns `""` only if Go does. Go's
`rowToIdpRoleMapping` copies the column through `pgtype.Text` → `""` for
NULL. Keep: `""` on the wire for legacy rows (§8 D4).

## 7. Tests the port must have

`AuthAdminConfigTest` (no-DB: normalisation tables, validation tables per
§4 with `@ParameterizedTest`, `MatchesEmail`), `AuthAdminConfigOperationsTest`
(through the envelope: each create/update/delete writes the row, one
`msg_events` row with the §5 type/subject/group, one `aud_logs` row named
after the command; duplicates → 409; missing → 404; corrupt stored
`config_type` / `auth_provider` → `CorruptRowException` on the single read
**and** on the list), `AuthAdminConfigApiTest` (`TestHttp`: every route
with the §3 status codes; a non-anchor caller gets 403 on **every** route
including the lists; a CLIENT-scoped admin likewise). Load-bearing, so
mutation-checked: the anchor gate on reads; 409 on duplicate domain /
config / mapping; the strict stored read; update leaving unsupplied fields
untouched.

## 8. Defects and questions for the owner

- **D1** `POST /api/idp-role-mappings` with an existing `idpRoleName`
  answers 500 in Go (unhandled UNIQUE violation). And the index is on
  `idp_role_name` alone, so the same upstream role name cannot be mapped for
  two `idpType`s — is that intended, or should uniqueness be
  `(idp_type, idp_role_name)`? Java answers 409 `MAPPING_EXISTS` either way.
- **D2** `PUT /api/anchor-domains/{id}` to a domain another row owns → 500
  in Go (no pre-check). Java: 409 `DOMAIN_EXISTS`.
- **D3** `PUT /api/auth-configs/{id}` can set `authProvider=OIDC` with no
  issuer / client id (create validates them, update does not). Kept as Go;
  should update apply the same `OIDC_*_REQUIRED` checks?
- **D4** `idp_type` is nullable in the schema, required on the wire; legacy
  rows come back with `idpType: ""`. Backfill and make it NOT NULL?
- **Q1** `oidcClientSecretRef` is stored and echoed as given — it is a
  reference, never a plaintext secret. Confirm it is expected to be a
  `SecretRef` in the encryption service's format, and whether reads should
  redact it.

## 9. Deliberate deviations

- 409 `MAPPING_EXISTS` / `DOMAIN_EXISTS` where Go answers 500 (D1, D2).
