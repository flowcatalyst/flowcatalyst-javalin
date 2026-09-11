# Portal apps, per-app grants, derived portal-user state, profile-only gate

Status: **being ported** (2026-09-11), branch `portal-apps`.

Source: Go `373fe93` (feature) + `2fe6bf0` (normative spec; the
profile-only gate answers in the platform envelope) + `d3eac0d` (SPA client
switch). Lockfile re-vendored at `2fe6bf0` (252 operations: +6 new,
`ensurePortalUser` / `listPortalUsers` / `deletePortalUser` changed).

**The contract below Part B is Go's `docs/portal-apps-reimplementation-spec.md`
at `2fe6bf0`, copied verbatim** — Go's own agent wrote it as the normative
spec for exactly this port, and it matches what our spec-extraction step
would have produced (behaviour, wire shapes, error codes, events,
acceptance scenarios). Part A records the Java-side decisions. Per
CONVENTIONS §8 the Java is audited against this file, never against the Go.

## Part A — Java decisions

| # | Decision |
|---|---|
| J1 | **`portalapp` is its own aggregate package** (`io.flowcatalyst.platform.portalapp`: `PortalApp`, `PortalAppCode` parser record, `PortalAppRepository`, `operations/`, `api/PortalAppApi`) — Go keeps it inside `portalidentity`; CONVENTIONS §2 is one aggregate per package. The **grant** belongs to the `PortalIdentity` aggregate (`PortalAppGrant(appId, source, grantedAt)`, `PortalAppGrantSource {INVITE, JIT, ADMIN}`), persisted by the identity's repository (§2.2 sync). |
| J2 | `EntityType.PORTAL_APP("pta")`. |
| J3 | Derived state (§2.3) is `PortalIdentity.state(Instant now)` returning `PortalUserState {INVITED, INVITE_EXPIRED, ACTIVE, SUSPENDED}` — computed, never stored. |
| J4 | `OAuthClient` gains `portalAppId` (nullable; `null` ⇔ blank). The invariant `portalAppId ⇒ portalClientId` is an entity invariant (`PORTAL_APP_REQUIRES_PORTAL_CLIENT`), alongside the existing `PORTAL_API_ACCESS_CONFLICT`. |
| J5 | §3.4 `CreatePortalAppWithOAuthClient` and §3.6 `DeletePortalApp` are `TxOperation`s (template: `application.operations.ProvisionServiceAccount`); the plaintext secret leaves only in the result, after commit. §3.3 `CreatePortalApp` is the single-aggregate operation. |
| J6 | §6's filter is a before-filter installed immediately after the `Authenticator`. Session-cookie contexts carry `PrincipalType.USER` (today `null`); bearer contexts keep the `type` claim. Test-header contexts get a type only from `X-FC-Test-Principal-Type` (absent ⇒ `null` ⇒ exempt). |
| J7 | §8 SDK: our `sdk` module has no authenticated-user / ID-token object, so the claim accessors have nowhere to live. **Not built**; revisit if the SDK grows an OIDC principal. |
| J8 | §7 SPA: the embedded SPA is re-synced from Go (`tools/sync-frontend.sh`), which brings the Portal pages and the client-switch fix; `docs/published/40-portal-users.md` is copied from Go. |
| J9 | Ensure §4.1 step 4: the pre-existing Java SSO branch sent the "open the portal" email whenever a target existed; the spec's *pending* rule (never signed in) now governs, and the mark-invited-with-no-expiry write is new. |
| J10 | Search §4.2 is built with jOOQ conditions added only when present (never `($x = '' OR …)`), `LIKE … ESCAPE '\'` on a bound, escaped `q%` so the `text_pattern_ops` indexes apply. |
| J11 | The portal identity **access token** keeps `azp` (the redeeming OAuth client): Java and Go both stamp it on every other client-bound identity token (`GenerateIdentityAccessTokenFor`); Go's portal branch alone uses the client-less minter. Go defect, allow-listed. Both tokens of a portal subject carry `tier: ""` — ruling `44e5633` already said so for the id_token; Java was stamping the `Principal.portalSubject` placeholder `CLIENT` on both until the parity run caught it on the access token. The portal id_token's `updated_at` is the identity's own `updated_at`, not the mint time (Go stamps the mint time — its synth principal has none). |
| J12 | Go `f0c3eae` (not part of the portal feature, same sync): an unsupported secret-manager scheme on the auth-config / identity-provider APIs answers 400 `UNSUPPORTED_SECRET_SCHEME`; Java already refused it as `INVALID_SECRET_REF` and now uses Go's code for that case only. The message matches Go's (owner, 2026-09-11): the scheme quoted whole (`"aws-smm://"`) and the supported list in its `aws-sm://` form, so a typo reads against the right spelling (ruling 2026-09-08). **Kept difference:** Java prefixes the field name (`oidcClientSecretRef: …`); Go does not. |

Owner questions: none blocking. Noted, not changed (pre-existing,
`auth-identity.md` §11.8): Ensure on an existing identity reactivates it
unconditionally, so an admin re-ensure of a suspended user un-suspends it.

## Part B — normative contract (Go `d6b215b`, verbatim; §11 errata = our fix list P1–P5)

### (Go title) Portal apps & platform access gate — reimplementation spec

A normative spec for reimplementing this feature set on another platform
implementation (e.g. Java) with **wire-identical** behaviour. Companion to
`docs/portal-apps-and-access-gate.md`, which explains *why*; this document
says exactly *what*. Where they disagree, the Go code on `main` is the
reference and `api/openapi.lock.json` is the wire authority for every
operationId named below.

Conventions used throughout:

- **Error envelope** (platform API, `/api/*`, `/bff/*`):
  `{"error": "<CODE>", "message": "<text>", "details"?: {…}}`.
  Kinds map to status: validation → 400, authorization → 403,
  not-found → 404, conflict → 409, internal → 500.
- **Not-found codes** are `<Resource>_NOT_FOUND` with the resource name
  verbatim: `PortalApp_NOT_FOUND`, `PortalIdentity_NOT_FOUND`,
  `Client_NOT_FOUND`, `OAuthClient_NOT_FOUND`; message
  `"<Resource> not found: <id>"`.
- **Portal plane endpoints** (`/portal/auth/*`) keep their existing body
  shape `{"code": "<CODE>", "message": "<text>"}` (the portal login page
  reads `code`).
- **OAuth endpoints** use RFC 6749 bodies
  `{"error": "<code>", "error_description": "<text>"}`.
- Timestamps are the platform's standard RFC 3339 wire form; all stored in
  UTC.
- Every aggregate change goes through the unit of work: row write + domain
  event + audit row in one transaction.

---

## 1. Data model (migration 053)

Apply verbatim (PostgreSQL):

```sql
CREATE TABLE IF NOT EXISTS portal_apps (
    id VARCHAR(17) PRIMARY KEY,                   -- TSID, prefix "pta"
    client_id VARCHAR(17) NOT NULL,
    code VARCHAR(100) NOT NULL,                   -- normalised lower-case
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_portal_apps_client_code UNIQUE (client_id, code)
);

CREATE TABLE IF NOT EXISTS portal_identity_apps (
    identity_id VARCHAR(17) NOT NULL REFERENCES portal_identities(id) ON DELETE CASCADE,
    portal_app_id VARCHAR(17) NOT NULL REFERENCES portal_apps(id) ON DELETE CASCADE,
    source VARCHAR(20) NOT NULL,                  -- INVITE | JIT | ADMIN
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (identity_id, portal_app_id)
);
CREATE INDEX IF NOT EXISTS idx_portal_identity_apps_app ON portal_identity_apps (portal_app_id);

ALTER TABLE oauth_clients ADD COLUMN IF NOT EXISTS portal_app_id VARCHAR(17);

ALTER TABLE portal_identities ADD COLUMN IF NOT EXISTS invited_at TIMESTAMPTZ;
ALTER TABLE portal_identities ADD COLUMN IF NOT EXISTS invite_expires_at TIMESTAMPTZ;

-- backfill 1: live invite tokens carry the real dates
UPDATE portal_identities pi
SET invited_at = t.created_at, invite_expires_at = t.expires_at
FROM iam_password_reset_tokens t
WHERE t.principal_id = pi.id AND t.purpose = 'invite' AND pi.invited_at IS NULL;

-- backfill 2: other never-completed INVITE identities were invited on create
UPDATE portal_identities
SET invited_at = created_at, invite_expires_at = created_at + INTERVAL '72 hours'
WHERE invited_at IS NULL AND source = 'INVITE'
  AND password_hash IS NULL AND last_login_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_portal_identities_client_email_prefix
    ON portal_identities (client_id, email text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_portal_identities_client_name_prefix
    ON portal_identities (client_id, lower(name) text_pattern_ops);
```

Down: drop the two indexes, the two identity columns,
`oauth_clients.portal_app_id`, the grant index, then `portal_identity_apps`
and `portal_apps`.

TSID entity type **`PortalApp`, prefix `pta`** — add to every TSID registry
(platform + SDKs that mirror it).

`oauth_clients.portal_app_id` has **no** FK (matches the rest of
`oauth_clients`); integrity is enforced by the operations below.

---

## 2. Domain rules

### 2.1 Portal app code

- Normalise: trim, then lower-case. Every lookup by code normalises first,
  so codes are case-insensitive on input.
- Valid: `^[a-z0-9][a-z0-9_-]{0,99}$` (after normalisation).
- Immutable after creation.

### 2.2 Portal identity additions

- `apps`: the identity's grants `[{appId, source, grantedAt}]`, loaded with
  the identity. Persisting an identity **syncs** the grant rows to exactly
  this set (delete rows not in the set; insert missing with
  `ON CONFLICT DO NOTHING`), keyed by the id that actually holds the row
  (the identity upsert is `ON CONFLICT (client_id, email) … RETURNING id`, so
  a racing ensure resolves to the winner's id).
- `grant(appId, source)` is idempotent (no-op if held); `revoke(appId)` is
  idempotent.
- `invited_at` / `invite_expires_at` are bookkeeping written outside the
  aggregate persist (a direct `UPDATE … SET invited_at, invite_expires_at,
  updated_at = NOW()`), never by the identity upsert.

### 2.3 Derived state (never stored)

Evaluated at request time `now`, first match wins:

| # | Condition | `state` |
|---|---|---|
| 1 | `status = DISABLED` | `SUSPENDED` |
| 2 | password hash non-empty **or** `last_login_at` set **or** `source = JIT` | `ACTIVE` |
| 3 | `invite_expires_at` set and `now >= invite_expires_at` | `INVITE_EXPIRED` |
| 4 | otherwise | `INVITED` |

`invite_expires_at` NULL with `invited_at` set = an SSO invite (never
expires). An identity never invited at all also reads `INVITED` (only
possible when no invite mailer is configured).

### 2.4 Portal OAuth client ↔ app

- An OAuth client is a **portal client** iff `portal_client_id` is non-empty.
- `portal_app_id` may only be set on a portal client, and the app's
  `client_id` must equal `portal_client_id`.
- Portal client **without** `portal_app_id` = *legacy client-wide*: no grant
  check, no app claims. Must keep working.
- "The app for OAuth client X": `SELECT pa.* FROM portal_apps pa JOIN
  oauth_clients oc ON oc.portal_app_id = pa.id WHERE oc.client_id = X`
  (X is the OAuth `client_id` string, not the row id). Null → legacy.

---

## 3. Operations and domain events

All events: `source = "platform:portal"`, spec version `1.0`, standard
metadata (event id, principal, correlation/causation/execution ids).

| Event type | Subject | Message group | `data` |
|---|---|---|---|
| `platform:portal:identity:ensured` *(extended)* | `platform.portal-identity.{identityId}` | `platform:portal-identity:{identityId}` | `{identityId, clientId, email, created, source, portalAppId?, portalAppCode?}` |
| `platform:portal:identity:app-granted` | same | same | `{identityId, clientId, portalAppId, portalAppCode, source}` |
| `platform:portal:identity:app-revoked` | same | same | `{identityId, clientId, portalAppId, portalAppCode}` |
| `platform:portal:app:created` | `platform.portal-app.{appId}` | `platform:portal-app:{appId}` | `{portalAppId, clientId, code, name}` |
| `platform:portal:app:updated` | same | same | same |
| `platform:portal:app:deleted` | same | same | same |

OAuth clients created/deleted by the orchestrations below emit the platform's
existing `platform:admin:oauth-client:created` /
`platform:admin:oauth-client:deleted` events, unchanged.

All operations are "authorize-public" at the operation layer; authorization
is the controller check in §4.

### 3.1 Ensure (extended)

Command: `{clientId, email, name?, source (INVITE|JIT), portalAppId?}`.

1. Validate: clientId non-blank (`CLIENT_ID_REQUIRED`); email non-blank
   (`EMAIL_REQUIRED`); email has a local part and a domain around `@`
   (`EMAIL_INVALID`).
2. Client must exist (`Client_NOT_FOUND`).
3. If `portalAppId`: the app must exist and belong to `clientId`
   (`PortalApp_NOT_FOUND`) and be active
   (400 `PORTAL_APP_INACTIVE`, message `portal app '<code>' is inactive`).
4. Find by (client, lower(email)). New → create (email lower-cased, name
   trimmed, status ACTIVE, source as given; anything but JIT is INVITE).
   Existing → status := ACTIVE; name replaced only if a non-blank name is
   given. Existing password and grants are kept.
5. If an app was given: `grant(app.id, source)`; the ensured event carries
   `portalAppId` / `portalAppCode`.
6. Persist + emit `identity:ensured`.

### 3.2 GrantApp / RevokeApp

Command: `{clientId, identityId, portalAppId}`, all required
(`TARGET_REQUIRED`). Identity must exist with the same client
(`PortalIdentity_NOT_FOUND`); app must exist with the same client
(`PortalApp_NOT_FOUND`). Grant uses source `ADMIN`. Both idempotent; both
always persist and emit their event.

### 3.3 CreateApp (single-aggregate; used internally/tests)

Validate: clientId (`CLIENT_ID_REQUIRED`), code (`CODE_INVALID`, message
`code must be 1-100 lower-case letters, digits, '-' or '_', starting with a
letter or digit`), name non-blank (`NAME_REQUIRED`). Client exists; code
unused for the client (409 `CODE_EXISTS`, `portal app code '<code>' already
exists for this client`). Emit `app:created`.

### 3.4 CreateAppWithOAuthClient (the API path) — ONE transaction

Command: CreateApp's fields + `redirectUris[]` + `clientType`.

Validate (before the transaction): CreateApp's rules; `clientType` empty or
`PUBLIC`/`CONFIDENTIAL` (`INVALID_CLIENT_TYPE`); every redirect URI parses
as an absolute URL with scheme `http`/`https`, a non-empty host containing
no `*`, and no fragment (`REDIRECT_URI_INVALID`, message
`redirectUris must be absolute http(s) URLs without wildcards: <uri>`).

Inside the transaction:

1. Client exists; code unused (as CreateApp).
2. Build the app.
3. Build the OAuth client:
   - row id: new TSID `oac`; `client_id`: a **new TSID of type OAuthClient**
     (same generator as backend-generated OAuth client ids)
   - `client_name` = `"<app name> (portal)"`
   - type: `CONFIDENTIAL` unless `PUBLIC` requested
   - `redirect_uris` = trimmed, non-empty entries
   - `grant_types` = `["authorization_code"]` (never refresh)
   - scopes = `["openid","profile","email"]`, `pkce_required = true`,
     `api_access = false`
   - `portal_client_id` = app.client_id, `portal_app_id` = app.id
   - CONFIDENTIAL: generate a secret with the platform's normal client-secret
     generator; store only the hash; keep the plaintext for the response.
4. Persist app + `app:created`; persist OAuth client +
   `oauth-client:created`. Any failure rolls back both.

Result: `{appId, oauthClientRowId, oauthClientId, clientType, clientSecret?}`.

### 3.5 UpdateApp

Command `{clientId, id, name?, description?, active?}`. id required
(`ID_REQUIRED`); a supplied name must be non-blank (`NAME_REQUIRED`). App
must exist and (when clientId given) belong to it (`PortalApp_NOT_FOUND`).
Description: trimmed, blank → null. Code never changes. Emit `app:updated`.

### 3.6 DeleteApp — ONE transaction

Command `{clientId, id}`. id required. App must exist/belong
(`PortalApp_NOT_FOUND`). Delete **every** OAuth client whose
`portal_app_id = app.id` (each emits `oauth-client:deleted`), then the app
(grants cascade; emit `app:deleted`). **Do not unlink instead** — an unlinked
portal client becomes a legacy client-wide portal (access widening).
Result lists the deleted OAuth `client_id`s.

---

## 4. HTTP API

Authorization helpers (existing platform semantics):

- **read** = anchor tier, or (caller can access `clientId` and holds
  `platform:iam:portal-user:view` or `:manage`)
- **manage** = anchor tier, or (caller can access `clientId` and holds
  `platform:iam:portal-user:manage`)

A missing/blank `clientId` is 400 `CLIENT_ID_REQUIRED` (checked before
authorization).

### 4.1 `POST /api/portal-users` — `ensurePortalUser` (extended) — 200, manage

Request (new field in bold):

```json
{ "clientId": "clt_…", "email": "pat@x.test", "name": "Pat",
  "portalAppCode": "customer-portal",
  "returnInviteLink": false, "redirectUri": "https://…" }
```

1. Resolve `portalAppCode` (if non-blank) to the client's app by normalised
   code → `PortalApp_NOT_FOUND` (id = normalised code).
2. `redirectUri` given → must exactly equal a registered redirect URI of one
   of the client's portal OAuth clients (`REDIRECT_URI_INVALID`). Otherwise
   default = the origin (`scheme://host/`) of the first usable registered
   redirect URI among the client's portal OAuth clients, **ordered with the
   resolved app's own OAuth clients first** (stable); skip unparsable URIs
   and hosts containing `*`.
3. Run Ensure (§3.1) with source INVITE and the app.
4. SSO-owned domain (an OIDC IdP owns the email domain):
   `ssoManaged = true`; *pending* = never signed in. If `returnInviteLink` →
   `inviteUrl` = the redirect target. Else if a mailer, a target, and
   pending → send the "open the portal" email, `invited = true`. If pending
   and (invited or an inviteUrl was returned) → mark invited with
   **no expiry**.
5. Otherwise, when a mailer is configured and the identity has no password:
   `returnInviteLink` → mint the set-password link (72h), return it as
   `inviteUrl`; else email it (`invited = true`; mail failure → 500
   `INVITE_EMAIL`). Mark invited with `expires = now + 72h`.
6. Respond (new fields in bold):

```json
{ "identityId": "ptu_…", "created": true, "invited": true,
  "inviteUrl": "…", "ssoManaged": false, "hasPassword": false,
  "portalAppCode": "customer-portal", "state": "INVITED" }
```

`portalAppCode` is the normalised code (omitted when none given); `state`
is §2.3 evaluated after step 4/5.

### 4.2 `GET /api/portal-users` — `listPortalUsers` (extended) — 200, read

Query: `clientId` (required), `q`, `portalAppCode`, `page` (0-based,
default 0, negative → 0), `size` (default **100**, capped at 1000; ≤0 →
100).

- `portalAppCode` non-blank → resolve (404 if unknown) and filter.
- Search SQL (build the WHERE dynamically so indexes apply):

```sql
-- base
WHERE pi.client_id = $1
-- when q (trimmed, lower-cased) is non-empty, $2 = escape(q) || '%'
  AND (pi.email LIKE $2 OR lower(pi.name) LIKE $2)
-- when app filter
  AND EXISTS (SELECT 1 FROM portal_identity_apps g
              WHERE g.identity_id = pi.id AND g.portal_app_id = $3)
ORDER BY pi.created_at DESC, pi.id DESC LIMIT $n OFFSET $m
```

  `escape` replaces `\` → `\\`, `%` → `\%`, `_` → `\_` (backslash is the
  LIKE escape). **Prefix only** — `jones` must not match "Pat Jones".
  `total` = `COUNT(*)` with the same WHERE.
- Response:

```json
{ "portalUsers": [ {
    "identityId": "ptu_…", "email": "pat@x.test", "name": "Pat",
    "status": "ACTIVE", "state": "INVITED", "source": "INVITE",
    "hasPassword": false,
    "apps": [ { "id": "pta_…", "code": "customer-portal", "name": "Customer Portal",
                "source": "INVITE", "grantedAt": "…" } ],
    "invitedAt": "…", "inviteExpiresAt": "…", "lastLoginAt": null,
    "createdAt": "…", "updatedAt": "…" } ],
  "total": 1, "page": 0, "size": 100 }
```

  `apps` is always an array; ordered by `granted_at`. If a grant's app can't
  be resolved, `code` and `name` fall back to the app id. Optional
  timestamps are omitted when null.

### 4.3 Grants — manage

- `POST /api/portal-users/{id}/apps` — `grantPortalUserApp`, 200. Body
  `{"clientId", "portalAppCode"}`. App resolved by code (404); inactive →
  400 `PORTAL_APP_INACTIVE`. Response `{"message": "Portal app access granted"}`.
- `DELETE /api/portal-users/{id}/apps/{portalAppCode}?clientId=` —
  `revokePortalUserApp`, 200. Response
  `{"message": "Portal app access revoked"}`.

### 4.4 Portal apps

`PortalAppResponse`:

```json
{ "id": "pta_…", "clientId": "clt_…", "code": "customer-portal",
  "name": "Customer Portal", "description": "…", "active": true,
  "oauthClients": [ { "id": "oac_…", "clientId": "oac_…", "clientName": "Customer Portal (portal)" } ],
  "userCount": 12, "createdAt": "…", "updatedAt": "…" }
```

`oauthClients` = OAuth clients with `portal_app_id = id`, ordered by name,
always an array; `userCount` = grant rows for the app.

- `GET /api/portal-apps?clientId=` — `listPortalApps`, 200, read.
  `clientId` omitted → anchors get every client's apps; others 400
  `CLIENT_ID_REQUIRED`. Ordered by name. Body `{"portalApps": [ … ]}`.
- `POST /api/portal-apps` — `createPortalApp`, **201**, manage. Body
  `{clientId, code, name, description?, redirectUris?, clientType?
  ("CONFIDENTIAL"|"PUBLIC")}`. Runs §3.4. Response:

```json
{ "portalApp": { …PortalAppResponse… },
  "oauthClientId": "oac_…", "oauthClientRowId": "oac_…",
  "clientType": "CONFIDENTIAL", "clientSecret": "…only for CONFIDENTIAL, once…" }
```

- `PUT /api/portal-apps/{id}` — `updatePortalApp`, 200, manage. Body
  `{clientId, name?, description?, active?}`. Returns `PortalAppResponse`.
- `DELETE /api/portal-apps/{id}?clientId=` — `deletePortalApp`, 200, manage.
  Runs §3.6. Response message `"Portal app deleted"`, suffixed
  `" with its OAuth client"` (1) or `" with its OAuth clients"` (>1).

### 4.5 OAuth clients (extended) — anchor only, as before

- `CreateOAuthClientRequest`, `UpdateOAuthClientRequest` and
  `OAuthClientResponse` gain `portalAppId?`.
- Controller, before running the operation, when `portalAppId` is
  non-blank: the app must exist (`PortalApp_NOT_FOUND`); if the request also
  carries a non-blank `portalClientId` different from the app's client → 400
  `PORTAL_APP_CLIENT_MISMATCH` (`portalAppId belongs to a different client
  than portalClientId`); then set `portalClientId := app.clientId`.
- Update semantics: `portalAppId: ""` unlinks, omitted keeps;
  `portalClientId: ""` clears the portal flag **and** the app link.
- Invariants after applying (400): `apiAccess` with a portal client →
  `PORTAL_API_ACCESS_CONFLICT` (existing); app without portal client →
  `PORTAL_APP_REQUIRES_PORTAL_CLIENT` (`a portal app can only be linked to a
  portal client (portalClientId)`).

---

## 5. Login gate

Resolve "the app for the flow's OAuth client" per §2.4. Null → no gate.

### 5.1 Password login — `POST /portal/auth/login`

Order (unchanged steps elided):

1. flow lookup → rate limit → SSO-domain refusal → identity lookup →
   uniform 401 `INVALID_CREDENTIALS` for unknown/suspended/password-less →
   password verify (401 on mismatch).
2. **New:** app resolved and (`!app.active` or identity lacks the grant) →
   **403** `{"code": "NO_PORTAL_ACCESS", "message": "You don't have access
   to this portal"}`. The flow is **not** consumed. This check runs only
   after a successful password verify.
3. consume flow → issue code → touch last login.

### 5.2 SSO callback (portal-flagged OIDC state)

1. Resolve the app; `!app.active` → redirect to the portal's
   `redirect_uri` with `error=access_denied`, `error_description=This portal
   is not currently available`, `state`.
2. Identity by (client, email):
   - absent → Ensure with source **JIT** and **the app** (first login grants
     it);
   - status ≠ ACTIVE → `access_denied` "This account is suspended for this
     portal" (unchanged);
   - app non-null and identity lacks the grant → `access_denied` "You don't
     have access to this portal" (**no** JIT grant for existing identities).
3. Issue code, touch last login, redirect (unchanged).

### 5.3 Code redemption — `POST /oauth/token` with a `ptu_` subject

After the existing "exists and ACTIVE" check: resolve the app for the
**redeeming OAuth client's** `client_id`; if non-null and (`!active` or no
grant) → 400 `{"error": "invalid_grant", "error_description": "Portal
identity has no access to this portal"}`.

### 5.4 id_token claims (portal subjects only)

Added to the existing portal id_token (roles still `[]`, no refresh token):

| Claim | When | Value |
|---|---|---|
| `portal_client_id` | always for portal logins | identity's tenant client id |
| `portal_app_code` | app-linked OAuth client | app code (normalised) |
| `portal_app_id` | app-linked OAuth client | app id (`pta_…`) |

Omitted (not null) when absent. Non-portal id_tokens never carry them.

Two further token rules for portal subjects (errata P3/P5, §11):

- The **access token** is client-bound like every other interactive
  identity token: it carries `azp` = the redeeming OAuth `client_id`.
- The id_token's **`updated_at`** is the portal identity's own
  `updated_at` — never the mint time.

---

## 6. Profile-only gate (users without a platform role)

A request filter placed **immediately after authentication**, covering every
authenticated `/api/*` and `/bff/*` route (and any other route in that
authenticated group).

- **Principal type** on the auth context: session-cookie authentication ⇒
  `USER`; bearer tokens ⇒ the access token's `type` claim (`USER` |
  `SERVICE`); unknown/absent ⇒ unknown.
- **Role-less** ⇔ type is `USER` **and** roles are empty **and**
  permissions are empty.
- If role-less and the path is not allowlisted → **403**
  `{"error": "NO_PLATFORM_ROLE", "message": "Your account has no platform
  access. Only your profile is available."}`.
- **Allowlist**: any path starting `/auth/` or `/portal/`; `GET /api/me`.
- **Pass through untouched**: unauthenticated requests (handlers apply their
  own checks); `SERVICE` principals (application service accounts authorize
  via their applications claim, not roles); unknown type.
- Dev/test header auth (if the implementation has it) is exempt unless the
  test explicitly marks the principal as `USER`.

Rationale to preserve: per-handler checks were insufficient — several
endpoints had no permission check, and anchor-tier short-circuits let a
zero-role ANCHOR user through almost everything. The filter closes both for
human users.

---

## 7. SPA behaviour (for UI parity)

- **Route access**: a user with no roles and no permissions may open only
  `/profile`; every other route (mapped or not) redirects there, and the
  sidebar is empty. Map `/identity/portal-users` and `/identity/portal-apps`
  to `platform:iam:portal-user:view`.
- **Navigation**: new group **Portal** → *Portal Apps*
  (`/identity/portal-apps`), *Portal Users* (`/identity/portal-users`);
  Portal Users removed from Client Administration.
- **Portal Users page**: client picker (anchors: all clients; client users:
  their accessible clients), portal-app filter, debounced (300 ms)
  server-side search (`q`), lazy server pagination (25/50/100), out-of-order
  responses discarded. Columns: email, name, **Status** (`state`: Invited
  (info), Invite expired (warn), Active (success), Suspended (danger);
  tooltip with invite dates), **Portal Apps** (removable chips → confirm →
  revoke), Source (`JIT` → "SSO sign-in", else "Invite"), last login,
  created, actions (suspend/reactivate, delete). **No invite button.**
- **Portal Apps page**: client picker; table of name/description, code,
  OAuth client ids (link to the OAuth client + copy), user count, status;
  create/edit dialog (create: name, code, description, callback URLs one
  per line, client type; edit: name, description, active; code read-only);
  delete confirm states the OAuth client(s) are deleted too. After create, a
  **credentials dialog**: app code, client id, client secret (once; "None —
  public client" for PUBLIC), endpoints (`{origin}/portal/authorize`,
  `/oauth/token`, `/.well-known/jwks.json`,
  `/.well-known/openid-configuration`), a Laravel env block
  (`FLOWCATALYST_BASE_URL`, `FLOWCATALYST_OIDC_ENABLED=true`,
  `FLOWCATALYST_OIDC_PORTAL=true`, `FLOWCATALYST_OIDC_CLIENT_ID`,
  `FLOWCATALYST_OIDC_CLIENT_SECRET` if any, a `portalAppCode` comment), and
  a warning when no callback URL was entered.
- **OAuth client drawers**: "Portal app" picker shown when a portal owner
  client is chosen, options = that client's apps; sends `portalAppId`
  (edit: `""` to unlink, and `""` whenever the portal owner is cleared).
- **Client switcher**: `POST /auth/client/switch` with `{clientId}`.
- **Permission checks in components** read the session user's own
  `permissions` (e.g. `userHasPermission(authStore.user, …)`), never a
  separately-populated permission store (errata P1, §11).
- **Cold loads**: the global route-permission guard runs before the route's
  auth guard; on routes that require authentication it must **await the
  initial session check** before applying the permission rules, so a
  role-less user who types `/dashboard` lands on `/profile` (errata P2, §11).

---

## 8. SDK surface

- Regenerate clients from the lockfile (new operationIds:
  `grantPortalUserApp`, `revokePortalUserApp`, `listPortalApps`,
  `createPortalApp`, `updatePortalApp`, `deletePortalApp`; extended
  `ensurePortalUser`, `listPortalUsers`, OAuth client models).
- Expose the portal login claims on the SDK's authenticated-user object:
  Laravel `getPortalAppCode()`, `getPortalAppId()`, `getPortalClientId()`
  (read from the ID-token claims; null when absent/empty); TypeScript
  `principal.portal = {clientId, appCode?, appId?}`, present only when
  `portal_client_id` is present, copied from the ID token onto the merged
  claims. A Java SDK should offer the equivalent.

---

## 9. Acceptance scenarios

A reimplementation is correct when all of these hold (each is a Go test
today):

1. **State**: live invite → INVITED; lapsed invite → INVITE_EXPIRED; password
   set (even after the link expired) → ACTIVE; SSO invite never expires; first
   SSO sign-in → ACTIVE; JIT → ACTIVE; DISABLED → SUSPENDED.
2. **Codes**: `"  Customer-Portal "` normalises to `customer-portal`;
   `a`, `supplier_portal2`, `9lives` valid; empty, `-lead`, `has space`,
   `dot.ted` invalid. The pattern itself is lower-case only, but operations
   validate the *normalised* code, so `UPPER` is accepted and stored as
   `upper`.
3. **Search**: 3 users; `PAT` finds `pat.jones@…` (email, case-insensitive);
   `jonas` finds name "Jonas Smith"; `jones` finds nothing; `under_` finds
   `under_score@…`; `u%` finds nothing; app filter returns only granted
   users; page 1 / size 2 has 1 row with total 3; unknown app code → 404.
4. **Ensure with `portalAppCode: "SUPPLIERS"`** → response
   `portalAppCode = "suppliers"`, `state = INVITED`, the user's `apps` lists
   it, `inviteExpiresAt` set.
5. **Gate** (two apps, user granted only A): password login via A → 200; via
   B with a wrong password → 401 (never 403 first); via B correct password →
   403 `NO_PORTAL_ACCESS`; after GrantApp(B) → 200; A deactivated → 403;
   RevokeApp(A) keeps B.
6. **Redemption**: code via an app-linked client → id_token has
   `portal_app_code`, `portal_app_id`, `portal_client_id`; revoke the grant,
   then a fresh code → 400 `invalid_grant`.
7. **Provisioning**: create with a callback → CONFIDENTIAL, secret returned
   once and not equal to the stored value, the OAuth client is portal-flagged,
   app-linked, `["authorization_code"]`, PKCE on, apiAccess off, callback
   registered, and listed under the app; PUBLIC → no secret; a duplicate code
   (any case) → 409 `CODE_EXISTS` and **no** extra OAuth client exists; a
   wildcard callback → 400 `REDIRECT_URI_INVALID`.
8. **Delete**: deleting app B removes B's OAuth client, leaves A's, and the
   user loses B's grant.
9. **Profile-only**: role-less CLIENT and role-less ANCHOR users get 403
   `NO_PLATFORM_ROLE` (exact envelope) on `/bff/roles`, `/api/clients`,
   `/api/me/clients`, `POST /api/audit-logs/batch`, and pass on `/auth/me`,
   `/auth/change-password`, `/auth/2fa/status`, `GET /api/me`; an admin, a
   SERVICE principal, an unknown-type context, and an anonymous request all
   pass through.

---

## 10. Implementation notes for another stack

- Keep the two orchestrations (§3.4, §3.6) in **one database transaction**
  each; secrets are generated inside but returned only after commit.
- Use a case-insensitive **prefix** search that can use the
  `text_pattern_ops` indexes: pass the pattern as a bound parameter and
  build the WHERE clause conditionally rather than `($x = '' OR …)`.
- Always escape user search input for LIKE.
- The gate in §5.1 must never run before the password check.
- Compute `state` at read time; do not add a stored status column.
- Deleting an identity cascades its grants; deleting an app cascades its
  grants — rely on the FKs.
- `GET /api/portal-users` default page size (100) is a behaviour change
  from the previous unpaginated list; match it so SDK callers see the same
  contract on both implementations.

---

## 11. Errata — fixed in Go after the first port (2026-09-11)

Found by the Java port; Go now behaves as below, and ports should match
(and drop any parity allow-list entries for them).

| # | Where | Defect | Now |
|---|---|---|---|
| P1 | SPA Portal Apps page | Write controls gated on the permission store's `hasPermission`, whose list nothing populates — no one could create/edit/delete apps | Gated on the session user's permissions (`userHasPermission`) |
| P2 | SPA global route guard | On a cold load it passed every navigation through before the session was hydrated, so the role-less → `/profile` redirect never happened | Awaits the session check on authenticated routes first |
| P3 | Portal code redemption | Access token minted without `azp` | Carries `azp` = the OAuth client_id |
| P4 | Unsupported secret-manager scheme (400 `UNSUPPORTED_SECRET_SCHEME`) | Message quoted the scheme before `://` (`"ref"://`) and listed `literal:` as a secret manager | `unsupported secret-manager scheme "ref://"; supported: aws-sm://, aws-ps://, gcp-sm://, vault://, env:// (prefix the value with "encrypt:" to store it as an encrypted plaintext secret instead)`. Note the list keeps the `aws-sm://` form — an owner ruling (2026-09-08) requires a typo like `aws-smm://` to see the correct spelling — so ports should match **this** list, not bare names. `literal:` is still *accepted*; it is just not advertised. |
| P5 | Portal id_token | `updated_at` was the mint time | The identity's own `updated_at` |
