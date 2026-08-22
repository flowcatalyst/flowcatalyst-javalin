# Spec — platform seeder (`io.flowcatalyst.platform.seed`)

Behavioural contract for the startup seeder. Written from the contract
(the committed fixture `server/src/test/resources/seed/go-seed-expected.tsv`
and `SeederTest` / `PlatformCatalogueTest`), not from the Go source. The
fixture is a `pg_dump --data-only` of a fresh Go `fcdev start` with ids and
timestamps stripped — byte-equal rows are the acceptance criterion.

Items tagged **[owner?]** are "load-bearing or accident?" questions the
owner rules on; until ruled on, the behaviour is kept as-is.

## 1. When it runs, what it is

- `Main`: connect → Flyway migrate → `new Seeder(pool).run()` → start HTTP.
  Only when a database-backed subsystem is enabled.
- Infrastructure processing, outside the use-case envelope: no executing
  principal, no domain events, no audit rows, no `msg_events`. Direct jOOQ
  inserts. The only sanctioned user of `DbTx.wrapForBootstrap` in `server`.
- Inputs: a `DataSource` and an environment (`EnvReader`; production =
  `EnvReader.system()`). Only three variables are read:
  `FLOWCATALYST_BOOTSTRAP_ADMIN_EMAIL`, `FLOWCATALYST_BOOTSTRAP_ADMIN_PASSWORD`,
  `FLOWCATALYST_BOOTSTRAP_ADMIN_NAME`. Unset and empty are the same thing.

## 2. Steps, in order

| # | Step | Tables written | Transaction |
|---|------|----------------|-------------|
| 1 | platform application | `app_applications` | autocommit, per statement |
| 2 | built-in roles | `iam_roles`, `iam_role_permissions` | autocommit, per statement |
| 3 | event-type catalogue (+ schemas) | `msg_event_types`, `msg_event_type_spec_versions` | autocommit, per statement |
| 4 | default process | `msg_processes` | autocommit, per statement |
| 5 | bootstrap admin | `oauth_identity_providers`, `tnt_email_domain_mappings`, `iam_principals`, `iam_principal_roles` | **one** transaction (commit/rollback) via `DbTx.wrapForBootstrap` |

The order is fixed: step 5 depends on step 2 (the `platform:super-admin`
role name it grants — by name, no FK, so it would not fail without it).
Steps 1–4 are mutually independent. **[owner?]** Steps 1–4 run statement by
statement, not in one transaction; a crash mid-step leaves a partially
seeded catalogue that the next boot completes. Intended (cheap, self-healing)
or should the whole pass be one transaction?

### Failure semantics

- Any failure in step *N* aborts the pass: `run()` throws
  `IllegalStateException("<step name>: <cause message>")` with the cause
  attached. Step names: `seed platform application`, `seed roles`,
  `seed event types`, `seed default processes`, `seed bootstrap admin`.
  Later steps are not attempted; earlier steps' rows stay committed.
- Step 5 rolls back all four of its writes on any failure (SQL or runtime).
- A malformed catalogue entry (event-type code without exactly four non-empty
  `:` segments) is a programming error and fails the boot.

## 3. Row set on an empty database

With the three bootstrap variables set (fcdev defaults
`admin@flowcatalyst.local` / `DevPassword123!` / `Local Admin`):

| Table | Rows | Key facts |
|---|---|---|
| `app_applications` | 1 | `type=APPLICATION`, `code=platform`, `name=FlowCatalyst Platform`, `description=Core platform — its own OpenAPI document is published here as one of the applications`, `active=true`; `icon_url`, `website`, `logo`, `logo_mime_type`, `default_base_url`, `service_account_id` all NULL; id `app_…` |
| `iam_roles` | 14 | see §4; `application_id=NULL`, `application_code=platform`, `source=CODE`, `client_managed=false`; id `rol_…` |
| `iam_role_permissions` | 151 | see §4 |
| `msg_event_types` | 72 | see §5; `status=CURRENT`, `source=UI`, `client_scoped=false`, `description=NULL`, `created_by=NULL`; `application`/`subdomain`/`aggregate` = code segments 1–3; id `evt_…` |
| `msg_event_type_spec_versions` | 72 | one per event type: `version=v1`, `mime_type=application/schema+json`, `schema_type=JSON`, `status=CURRENT`, `schema_content` = the catalogue's draft-07 schema (jsonb, key order irrelevant); id `sch_…` |
| `msg_processes` | 1 | see §6; id `prc_…` |
| `oauth_identity_providers` | 1 | `code=internal`, `name=Internal Authentication`, `type=INTERNAL`, `oidc_multi_tenant=false`, `sync_roles_from_idp=false`, all OIDC columns NULL; id `idp_…` |
| `tnt_email_domain_mappings` | 1 | `email_domain` = part after `@` of the configured email **as configured (case preserved, whitespace stripped)**, `identity_provider_id` = the internal IDP's id, `scope_type=ANCHOR`, `primary_client_id=NULL`, `required_oidc_tenant_id=NULL`, `require_2fa=false`, `remember_device_enabled=false`, `remember_device_days=30`; id `edm_…` |
| `iam_principals` | 1 | `type=USER`, `scope=ANCHOR`, `name` = configured name or `Bootstrap Admin`, `active=true`, `email` = configured email **stripped + lower-cased**, `email_domain` = part after `@` of the stored email, `idp_type=INTERNAL`, `all_applications=true`, `password_hash` = argon2id PHC of the configured password (see `password-hash.md`); `client_id`, `application_id`, `external_idp_id`, `service_account_id`, `last_login_at`, `dev_client_secret_*` NULL; id `prn_…` |
| `iam_principal_roles` | 1 | `(principal_id, role_name=platform:super-admin, assignment_source=BOOTSTRAP, assigned_at=now())` |

Without the bootstrap variables the last four tables stay empty; everything
else is identical. Not seeded here (fcdev's MCP bootstrap does it):
`iam_service_accounts`, the SERVICE principal, `oauth_clients`.

**[owner?]** Case of the email-domain mapping: `  Ops@Example.COM ` yields
principal `ops@example.com` / `example.com` but mapping domain `Example.COM`.
Login lookups that compare `email_domain` case-sensitively would miss. Intended
(mapping is operator-visible as typed) or accident? The test pins it today.

**[owner?]** `iam_principal_roles.assigned_at` uses the database clock
(`now()`) while every other timestamp uses the JVM clock. Harmless; intended?

## 4. The 14 roles (insertion order) and their permission counts

All `platform:<short>`; `source=CODE`; displayed below as
`name — display name (permission count)`.

| # | name | display name | perms |
|---|---|---|---|
| 1 | `platform:super-admin` | Platform Super Admin | 1 (`platform:*:*:*`) |
| 2 | `platform:admin` | Platform Admin | 21 |
| 3 | `platform:admin-readonly` | Platform Admin Read-Only | 7 |
| 4 | `platform:iam-admin` | Platform IAM Admin | 14 |
| 5 | `platform:iam-readonly` | Platform IAM Read-Only | 3 |
| 6 | `platform:client-admin` | Client Administrator | 8 |
| 7 | `platform:auth-admin` | Platform Auth Admin | 9 |
| 8 | `platform:auth-readonly` | Platform Auth Read-Only | 2 |
| 9 | `platform:ai-agent-readonly` | AI Agent Read-Only | 2 |
| 10 | `platform:messaging-admin` | Messaging Administrator | 39 |
| 11 | `platform:viewer` | Platform Viewer | 15 |
| 12 | `platform:portal-administrator` | Portal Administrator | 2 |
| 13 | `platform:developer` | Developer | 8 |
| 14 | `platform:application-service` | Application Service Account | 20 |
| | | **total** | **151** |

Descriptions and the exact permission strings are pinned by the fixture
(`role` / `perm` lines) and by `Permissions` / `PlatformRoles`. Permission
strings are `platform:<context>:<resource>:<action>`; they are referenced by
existing `iam_role_permissions` rows and by SDK permission checks, so they
never change. No role has duplicate permissions. Permission rows are
inserted in declaration order (no semantic meaning; the PK is
`(role_id, permission)`).

**[owner?]** The `Permissions` class declares ~40 constants no built-in role
uses (e.g. `*_MANAGE`, `ADMIN_CLIENT_DELETE`, `IAM_PERMISSION_READ`,
`DEVELOPER_APPLICATION_OPENAPI_SYNC`). Part of the contract for the
permission-check layer, or dead vocabulary?

## 5. The 72 event types

Code = `platform:<subdomain>:<aggregate>:<event>`; name = `Title(aggregate)
Title(event)` where `Title` splits on `-` and upper-cases each part's first
character, except the five `…:synced` rows whose names are given explicitly
(`Principals Synced`, `Roles Synced`, `Event Types Synced`,
`Dispatch Pools Synced`, and `platform:admin:subscription:synced` →
`Subscription Synced` derived). Every code has a schema.

| group (`application:subdomain:aggregate`) | events | n |
|---|---|---|
| `platform:iam:user` | created, updated, activated, deactivated, deleted, roles-assigned, application-access-assigned, client-access-granted, client-access-revoked, logged-in, password-reset-requested, password-reset-completed | 12 |
| `platform:iam:principals` | synced | 1 |
| `platform:iam:serviceaccount` | created, updated, deleted, roles-assigned, token-regenerated, secret-regenerated | 6 |
| `platform:iam:client` | created, updated, activated, suspended, deleted, note-added | 6 |
| `platform:iam:role` | created, updated, deleted | 3 |
| `platform:iam:roles` | synced | 1 |
| `platform:iam:application` | created, updated, activated, deactivated, deleted, service-account-provisioned, enabled-for-client, disabled-for-client | 8 |
| `platform:iam:anchor-domain` | created, deleted | 2 |
| `platform:iam:auth-config` | created, updated, deleted | 3 |
| `platform:admin:cors` | origin-added, origin-deleted | 2 |
| `platform:admin:idp` | created, updated, deleted | 3 |
| `platform:admin:edm` | created, updated, deleted | 3 |
| `platform:admin:eventtype` | created, updated, archived, deleted, schema-added, schema-finalised, schema-deprecated | 7 |
| `platform:admin:eventtypes` | synced | 1 |
| `platform:admin:connection` | created, updated, deleted | 3 |
| `platform:admin:dispatch-pool` | created, updated, archived, deleted | 4 |
| `platform:admin:dispatch-pools` | synced | 1 |
| `platform:admin:subscription` | created, updated, paused, resumed, deleted, synced | 6 |
| | **total** | **72** |

No `webhook` / `delivery` codes. Every code matches `^platform:(iam|admin):`.

Schemas: draft-07 (`$schema` = `http://json-schema.org/draft-07/schema#`),
`type: object`, `properties`, `required`, `additionalProperties: false` —
except `platform:iam:user:logged-in`, a hand-written schema with
`additionalProperties: true`, a nested `flowcatalystClaims` object and a
`federatedClaims` `oneOf [object, null]`. Optional properties are
`{"type": ["<t>", "null"]}`; integers carry `minimum: 0`. The exact trees
are pinned by the fixture's `schema` lines (compared as JSON, not as text).

**[owner?]** `msg_event_types.source = UI` for catalogue rows (not `CODE`
as the roles and the process use). Load-bearing (the event-type sync
endpoint treats `CODE` rows differently?) or accident?

## 6. The example process

`msg_processes`: `code=platform:fulfilment:on-demand-flow`,
`name=On-Demand Fulfilment Flow`, `status=CURRENT`, `source=CODE`,
`application=platform`, `subdomain=fulfilment`, `process_name=on-demand-flow`,
`diagram_type=mermaid`, `tags={example,fulfilment,platform}`, `description` =
`DefaultProcesses.EXAMPLE_DESCRIPTION`, `body` = the Mermaid flowchart in
`DefaultProcesses.EXAMPLE_BODY` byte-for-byte (starts `flowchart TD\n    Start(`,
ends `terminal;\n`).

## 7. Bootstrap admin — decision table

Evaluated in this order; the first matching row wins.

| Condition | Outcome |
|---|---|
| any `iam_principals` row with `type=USER AND scope=ANCHOR` exists | skip silently (nothing written, not even the IDP / mapping) |
| email (stripped) empty **or** password empty | WARN `no bootstrap admin configured …`, skip |
| email has no `@`, or `@` is the last character | WARN `invalid bootstrap email format`, skip |
| a principal with `email = <stripped, original case>` exists (checked inside the tx) | INFO `bootstrap admin already present`, skip (nothing written — IDP and mapping are **not** created either, because the check precedes them) |
| otherwise | create, in one transaction: internal IDP (if `code=internal` absent), ANCHOR email-domain mapping (if that `email_domain` absent), the principal, the `platform:super-admin` grant (`ON CONFLICT DO NOTHING`) |

Derived values: name = `FLOWCATALYST_BOOTSTRAP_ADMIN_NAME` or
`Bootstrap Admin` when unset/empty; password hashed with
`PasswordHash.hash` *before* the transaction opens; the password is never
logged.

**[owner?]** "Only when NO anchor user exists" — the bootstrap env is ignored
forever once any anchor user exists (even one created by a different path or
a different email). Intended (bootstrap = first-boot only) — or should a
configured email that does not exist yet still be created?

**[owner?]** The by-email idempotency check compares the *stripped but
not lower-cased* email against `iam_principals.email` (which is stored
lower-cased). `Ops@Example.COM` configured twice on a DB where the anchor
user was then deleted would pass the anchor check, miss the by-email check
and fail on the unique index. Edge of an edge; intended or accident?

**[owner?]** Password strength is not validated for the bootstrap admin
(`pw` is accepted). Intended for a bootstrap path?

## 8. Idempotency — what a second run may and may not change

Run `run()` N times against the same database:

| Table | Second run… |
|---|---|
| `app_applications` | no change (skip-if-code-exists) |
| `iam_roles` | no change; **operator edits to display name / description survive** (skip-if-name-exists) |
| `iam_role_permissions` | no change; **operator additions and removals survive** — a role whose permissions were deleted stays empty |
| `msg_event_types` | ids, `created_at`, `source`, `status`, `client_scoped`, `description` unchanged; **`name` is overwritten with the catalogue name and `updated_at` is bumped on every run**, even if nothing changed |
| `msg_event_type_spec_versions` | no change once a `v1` row exists for the event type (schema edits to `v1` survive; a catalogue schema change is **not** rolled out to existing rows) |
| `msg_processes` | no change; operator edits survive |
| `oauth_identity_providers`, `tnt_email_domain_mappings`, `iam_principals`, `iam_principal_roles` | no change (anchor-user check short-circuits the whole step) |

Guarantees: never a duplicate row, never a new id for an existing code /
name, never a changed password hash. `SeederTest.secondRunChangesNothing`
pins this by snapshotting every seeder-owned table (excluding
`msg_event_types.updated_at`) after run 1 and run 2.

Concurrency: two seeders racing on an empty database both converge — every
insert that a uniqueness constraint guards uses `ON CONFLICT DO NOTHING`
(`app_applications.code`, `iam_roles.name`, `iam_role_permissions` PK,
`msg_event_types.code`, `msg_processes.code`, `iam_principal_roles` PK) and
ids are re-read after the insert. **[owner?]** The spec-version insert, the
IDP insert, the mapping insert and the principal insert are *not*
conflict-guarded: a genuinely concurrent second seeder can still fail on
`uq_msg_spec_versions_event_type_version`, `idx_oauth_identity_providers_code`,
`idx_tnt_email_domain_mappings_domain` or `idx_iam_principals_email`.
Acceptable (one server boots at a time) or should these be guarded too?

**[owner?]** Event-type `name` / `updated_at` refreshed on every boot —
intended sync of the catalogue (so renames ship with the binary) or an
accident that also clobbers operator renames and makes `updated_at`
meaningless for catalogue rows? Roles and the process deliberately do the
opposite. `SeederTest.rerunRefreshesEventTypeNamesButKeepsIds` pins it.

## 9. Observability

INFO on every insert (`seeded built-in role role=…`, `seeded platform
event types inserted=… total=…`, `bootstrap admin created email=… role=…
scope=ANCHOR`); nothing at INFO when a step is a no-op except the
`bootstrap admin already present` skip. WARN only for the two configuration
skip cases. No metrics, no JFR events.
