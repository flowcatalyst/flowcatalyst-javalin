# Auth identity — behavioural spec

Semantic extraction (CONVENTIONS §8 step 1) of the **auth identity**
subsystem of `../flowcatalyst-go`: the OIDC bridge (FlowCatalyst as an OIDC
*client* of external IdPs), the portal identity plane, two-factor
authentication, passkeys (WebAuthn), password reset / invite / approval, the
e-mail transport and the security-notification catalogue — plus the
principal-touching side effects those flows perform (JIT provisioning, IdP
role sync, password reset). It is a behavioural contract, **not a design**:
the Java is written *from* this, the owner rules on every
**load-bearing or accident?** line, and conformance tests assert it.

Markers: **[C]** = wire / storage contract a client, the SPA, an SDK or a
row written by the Go/TS server depends on; **[I]** = internal mechanics we
are free to reshape as long as [C] holds. Every fact cites Go `file:line`
with these path prefixes: `bridge/` = `internal/platform/auth/bridge/`,
`login/` = `internal/platform/auth/login/`, `twofa/` =
`internal/platform/auth/twofa/`, `mfatoken/` =
`internal/platform/auth/mfatoken/`, `mfa/` = `internal/platform/mfa/`,
`webauthn/` = `internal/platform/webauthn/`, `passwordreset/` =
`internal/platform/passwordreset/`, `resetapproval/` =
`internal/platform/resetapproval/`, `portalauth/` =
`internal/platform/portalauth/`, `portalidentity/` =
`internal/platform/portalidentity/`, `notify/` = `internal/platform/notify/`,
`email/` = `internal/platform/shared/email/`, `principal/` =
`internal/platform/principal/`, `server/` = `internal/server/`, `sql/` =
`internal/migrate/sql/`, `ratelimit/` = `internal/platform/shared/ratelimit/`.

Related specs this one leans on and does not restate: `loginattempt.md`
(attempt store + backoff), `emaildomainmapping.md` (the 2FA / tenant fields),
`identityprovider.md` (`syncRolesFromIdp`, `allowedRoleIds`,
`allowedEmailDomains`, multi-tenant fields), `password-hash.md`,
`encryption.md`. The principal aggregate itself is ported separately; §12
lists exactly what these flows do to it.

---

## 0.5 Owner rulings in force (2026-09-05) — override the sections below

Every question in §19 and every defect in §18 was ruled on 2026-09-05
(`docs/auth-rulings.md`, "Rulings — Batch A/B/C"). Where a ruling changes a
section below, **the ruling wins**; the text is left as extracted so the Go
behaviour stays visible.

| Ruling | Effect on this spec |
|---|---|
| Q1 | The cached OIDC client is refreshed when the identity-provider row changes (same-node invalidation + TTL). §4.1 |
| Q2 | Storage keeps the empty-string provider-direct marker; the domain models a sealed mode. §3.1 |
| Q3 | `OIDC_VERIFY` is a fixed message; the verifier's error goes to the log. §4.4 |
| Q4, Q7, Q8, Q18, Q19, Q20, Q23 | Kept as Go (documented product decisions). |
| Q5 / defect 6 | Session-mint failure → the `ErrorModel` 500 envelope, fixed message, cause logged. §4.6 |
| Q6 | A CLIENT/PARTNER mapping without `primaryClientId` is refused at create/update (authadmin); JIT keeps the check as defence. §4.7 |
| Q9 (with core Q29) | `check-domain`: same shape, `authorizationUrl` omitted unless real. §4.10 |
| Q10 | The portal login flow is consumed at the callback sink, like the password path. §5.6 |
| Q11 / defect 7 | Remember-device exists only for internally managed identities, structurally absent for external-IdP domains, **off by default**, on only by explicit domain policy; the policy change and each enrolment / revocation are audit-logged; a store error never enables it. Verify Go's stored default at cutover. §6.1, §6.7 |
| Q12 / defect 9 | `/auth/2fa/verify` enforces the domain's allowed-method list (403 `METHOD_NOT_ALLOWED`); recovery codes are never restricted. §6.4 |
| Q13 / defect 1 | Passkey sign counter + `last_used_at` persisted; a counter that goes backwards is rejected; `passkey:authenticated` emitted. §7.5 |
| Q14 / defect 8 | Admin-triggered resets keep bypassing the factor (lost-device recovery). The admin action gains an option — never on self-service — that also clears MFA enrolments + trusted devices, notifies the user and writes an audit row; the domain policy forces re-enrolment. §8.1, §8.7 |
| Q15 / defect 15 | `Date`, `Message-ID`, RFC 2047 subject on outgoing mail. §9 |
| Q16 | Fallback brand `FlowCatalyst`. §10 |
| Q17 / defect 5 | The purger sweeps expired e-mail PINs, trusted devices, reset tokens and approval requests. §14 |
| Q21 | Trusted-device items use the platform time shape; no `principalId`. §6.4 |
| Q22 / defect 12 | System actor spelled `"system"` for new rows. §2 |
| Q24 | `authenticate/begin` 429 in the platform `TOO_MANY_REQUESTS` envelope. §7 |
| Q25 / defect 13 | Passkey events under `platform:iam`. §7.5 |
| defect 10 | 2FA trusted-device / method DELETE → 404 when nothing was deleted. §6.4 |
| defect 11 | `EXPIRED` written by the purger; the reviewer `note` persisted. §3.7, §8.6 |
| defects 2, 3, 4 | Dead code; not ported. |

## 1. Purpose & boundaries

Five cooperating surfaces, one per section below:

| Surface | Routes | Plane | Session outcome |
|---|---|---|---|
| OIDC bridge (§4) | `GET /auth/oidc/login`, `GET /auth/oidc/callback`, `GET /auth/oidc/session/end`, `POST|GET /auth/check-domain` | employee (`iam_principals`) | `fc_session` cookie via SessionWriter |
| Portal plane (§5) | `GET /portal/authorize`, `POST /portal/auth/check-domain`, `POST /portal/auth/login`, `POST /portal/auth/password-reset`, `GET /portal/auth/oidc/login`, admin `/api/portal-users*` | portal (`portal_identities`) | an OAuth authorization code; **never** `fc_session` |
| Two-factor (§6) | `POST /auth/login` decision + 14 `/auth/2fa/*` routes + `/auth/change-password*` | employee | `fc_session` after the second factor |
| Passkeys (§7) | 6 `/auth/webauthn/*` routes (lockfile) | employee | `fc_session` via `Set-Cookie` |
| Password reset / invite / approval (§8) | `/auth/password-reset/{request,validate,confirm}`, `/api/reset-approvals*`, admin `send-password-reset` / `reset-2fa` | both (tokens key `prn_` or `ptu_` subjects) | none (the SPA signs in afterwards) |

Shared infrastructure: the e-mail service (§9), the notification catalogue
(§10), the `mfatoken` pending/enroll JWT (§6.3), the shared login-attempt /
backoff budget (`loginattempt.md` §5), the per-IP governor on the bridge.

Out of scope (other specs / later units): `/oauth/*` (the platform as an
OIDC *provider*; only its portal-subject boundary is noted in §5.8),
`/auth/login` password verification itself beyond the 2FA hand-off,
`/auth/me`, `/auth/refresh`, `/auth/login-history`, IdP role-mapping CRUD
(`/api/idp-role-mappings`, `internal/platform/auth/api/api.go:138-140`), the
principal aggregate's own operations.

---

## 2. Actors, planes and vocabulary

- **Employee plane** — `iam_principals` users; authenticated by password
  (+2FA), passkey, or the OIDC bridge; session = `fc_session` cookie (an
  RS256 JWT carrying only `sub`/`email`, authorization re-resolved per
  request — `internal/platform/auth/provider/provider.go:286-299`).
- **Portal plane** — `portal_identities`, one row per (client, email); never
  gets a cookie; the platform proves identity and hands the portal app an
  authorization code whose subject is the `ptu_` id
  (`docs/portal-identity-plan.md:154-221`).
- **Mapping-based (home-realm) login** — `/auth/oidc/login?domain=` resolved
  through `tnt_email_domain_mappings → oauth_identity_providers`
  (`bridge/oidc.go:68-99`).
- **Provider-direct login** — `?provider_id=` / portal SSO; no mapping; the
  login-state row carries an **empty** `email_domain_mapping_id`, which is
  the marker the callback branches on (`bridge/login_endpoint.go:277-281,
  396-400`). [I] (the empty-string sentinel is a Go/schema accident —
  `docs/portal-identity-plan.md:115-118`; the Java may model it as a sealed
  `LoginMode`, but the **stored** row keeps `''` for schema compatibility —
  `sql/007_oauth_tables.sql:120` is `NOT NULL`).
- **System actor** — JIT provisioning and role sync run with an execution
  context whose principal id is `""` (`bridge/login_endpoint.go:661, 687,
  803`); the unauthenticated reset confirm runs as `"system"`
  (`passwordreset/api/api.go:656`). **load-bearing or accident?** — two
  spellings of "nobody" end up in `aud_logs.principal_id` (open question 22).
- **Strong factor** — a confirmed TOTP method. Passkeys are *not* consulted
  by the reset flow (`passwordreset/api/api.go:511-527`;
  `docs/auth-hardening-plan.md:8-9`).

---

## 3. Data model

All ids are 17-char TSIDs (`<3-char prefix>_<13>`), timestamps `TIMESTAMPTZ`
written as UTC. JSON names below are the Go struct tags (what any Go-side
consumer or test fixture serialises).

### 3.1 `oauth_oidc_login_states` — one in-flight OIDC handshake [C-storage]

`sql/007_oauth_tables.sql:116-134` + `sql/041_portal_identities.sql:61-62`.

| Column | Type | Go field | Notes |
|---|---|---|---|
| `state` | `VARCHAR(200)` **PK** | `State` | the CSRF token **and** the lookup key; 32 random bytes base64url-no-pad = 43 chars (`bridge/login_endpoint.go:337, 838-844`) |
| `email_domain` | `VARCHAR(255) NOT NULL` | `EmailDomain` | lower-cased at construction (`bridge/login_state.go:54`); `""` for provider-direct / portal states (`bridge/login_endpoint.go:909`) |
| `identity_provider_id` | `VARCHAR(17) NOT NULL` | `IdentityProviderID` | |
| `email_domain_mapping_id` | `VARCHAR(17) NOT NULL` | `EmailDomainMappingID` | `""` ⇒ provider-direct (§2) |
| `nonce` | `VARCHAR(200) NOT NULL` | `Nonce` | 32 random bytes b64url (43 chars); must equal the id_token `nonce` claim |
| `code_verifier` | `VARCHAR(200) NOT NULL` | `CodeVerifier` | 64 random bytes b64url (86 chars); PKCE S256 |
| `return_url` | `VARCHAR(2000)` | `ReturnURL *string` | raw caller value; sanitised only at redirect time (§4.6) |
| `oauth_client_id`, `oauth_redirect_uri`, `oauth_scope`, `oauth_state`, `oauth_code_challenge`, `oauth_code_challenge_method`, `oauth_nonce` | nullable varchars | `OAuth*` | the chained `/oauth/authorize` request (SPA-forwarded) or the portal flow's chain (`bridge/login_endpoint.go:349-355, 911-917`) |
| `interaction_uid` | `VARCHAR(200)` | `InteractionUID` | written `NULL`, never read (schema carry-over) |
| `created_at`, `expires_at` | `NOT NULL` | | `expires_at = created_at + 10 min` (`bridge/login_state.go:49-62`) |
| `portal_client_id` | `VARCHAR(17)` | `PortalClientID *string` | non-empty ⇒ portal-plane handshake (`bridge/login_state.go:37-41`) |

Row lifecycle: `Insert` → **exactly one** `Consume` (`DELETE … WHERE state=$1
AND expires_at > NOW() RETURNING …`, `bridge/login_state.go:124-145`) →
gone. Expired rows are swept by the purger (§14). `FindByState`
(`:95-116`) and `Delete` (`:149-153`) are dead code; note `FindByState`
selects 17 columns into 18 scan targets and would fail if ever called
(`:97-109`) — do not port.

### 3.2 `portal_login_flows` — one parked `/portal/authorize` [C-storage]

`sql/041_portal_identities.sql:30-42`; `portalauth/flow.go:21-61`.

| Column | Go field | Notes |
|---|---|---|
| `id` `VARCHAR(64)` PK | `ID` | 32 random bytes b64url (43 chars) |
| `oauth_client_id` `VARCHAR(100) NOT NULL` | `OAuthClientID` | `oauth_clients.client_id` |
| `portal_client_id` `VARCHAR(17) NOT NULL` | `PortalClientID` | the OAuth client's `portal_client_id` at stash time |
| `redirect_uri` `VARCHAR(2000) NOT NULL` | `RedirectURI` | validated against the client's registered URIs before insert |
| `scope`, `nonce`, `code_challenge` `VARCHAR(200)`, `code_challenge_method` `VARCHAR(10)` | `*string` | `nil` when the query param was empty (`portalauth/endpoints.go:119-128`); method defaults to `S256` whenever a challenge is present (`:122-127`) |
| `state` `VARCHAR(500) NOT NULL` | `State` | required at `/portal/authorize` |
| `created_at`, `expires_at` | | `expires_at = now + 15 min` (`portalauth/flow.go:43`) |

`Find` reads a live row **without** consuming (`:98-102`, so a wrong
password does not burn the flow); `Consume` is `DELETE … RETURNING` with the
TTL predicate (`:106-112`).

### 3.3 `portal_identities` [C-storage, C-wire via `/api/portal-users`]

`sql/041_portal_identities.sql:8-23`; `portalidentity/entity.go:40-78`.

| Column | JSON | Notes |
|---|---|---|
| `id` `VARCHAR(17)` PK | `id` | prefix `ptu_` (`internal/tsid/tsid.go:169-170`) |
| `client_id` `VARCHAR(17) NOT NULL` | `clientId` | no FK |
| `email` `VARCHAR(255) NOT NULL` | `email` | stored lower-cased + trimmed (`entity.go:71`); `UNIQUE (client_id, email)` |
| `name` `VARCHAR(255)` | `name,omitempty` | `""` ⇔ `NULL` at the sink (`repository.go:110,121-126`) |
| `password_hash` `VARCHAR(255)` | `-` | `NULL` until an invite/reset completes or forever for SSO-only |
| `status` `VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'` | `status` | `ACTIVE` \| `DISABLED` |
| `source` `VARCHAR(20) NOT NULL` | `source` | `INVITE` \| `JIT` |
| `last_login_at` | `lastLoginAt,omitempty` | stamped best-effort on password and SSO login |
| `created_at`, `updated_at` | `createdAt`, `updatedAt` | |

`Persist` is an upsert `ON CONFLICT (client_id, email) DO UPDATE SET name,
status, updated_at` — a re-ensure keeps id / source / created_at / password
(`repository.go:100-113`). `CanSignInWithPassword` = `ACTIVE` **and**
non-empty hash (`entity.go:60-62`).

### 3.4 Two-factor tables [C-storage]

`sql/031_mfa_tables.sql`. All four have `principal_id … REFERENCES
iam_principals(id) ON DELETE CASCADE`.

`iam_user_mfa_methods` (`:48-61`, entity `mfa/entity.go:42-63`): `id` (`mfm_`),
`principal_id`, `method` `'TOTP'|'EMAIL_PIN'` (**unique** per principal),
`secret_encrypted TEXT` (AES-256-GCM envelope of the base32 TOTP secret via
`encryption.Service`; `NULL` for `EMAIL_PIN`), `confirmed_at` (NULL =
pending enrolment, never satisfies a challenge), `last_used_at` (TOTP replay
guard: stores `step × 30 s` as UTC, `mfa/crypto.go:100-105`), `created_at`.
JSON: `id, principalId, type, confirmedAt?, lastUsedAt?, createdAt`
(`secretEncrypted` never serialised).

`iam_user_mfa_recovery_codes` (`:68-79`, `entity.go:67-83`): `id` (`mrc_`),
`principal_id`, `code_hash VARCHAR(64)` (lower-hex SHA-256 of the
*normalised* code), `used_at` (single-use stamp), `created_at`.

`iam_mfa_email_pins` (`:87-100`, `entity.go:98-121`): `id` (`mep_`),
`principal_id`, `purpose` `'login'|'enroll'` (default `'login'`),
`pin_hash VARCHAR(64)` (SHA-256 hex of the digits), `attempts INT DEFAULT 0`,
`expires_at`, `created_at`. `IsExpired` = `now > expires_at` (`:121`).

`iam_mfa_trusted_devices` (`:108-121`, `entity.go:126-150`): `id` (`mtd_`),
`principal_id`, `token_hash VARCHAR(64) UNIQUE` (SHA-256 hex of the raw
cookie value — only the hash is stored so a DB read cannot mint a cookie),
`label VARCHAR(255)` (User-Agent, ≤250 chars, NULL when empty),
`expires_at`, `created_at`, `last_used_at`. JSON (listed on
`GET /auth/2fa/trusted-devices`): `id, principalId, label?, expiresAt,
createdAt, lastUsedAt?`. **[C]** the list route leaks `principalId` and uses
Go's default time JSON (RFC 3339 nanos) — see §6.7.

`tnt_email_domain_mappings.require_2fa / remember_device_enabled /
remember_device_days` + junction `tnt_email_domain_mapping_2fa_methods`
(`:13-31`) — owned by `emaildomainmapping.md`; read here through
`twofa.Policy`.

### 3.5 `webauthn_credentials` + ceremony rows [C-storage]

`sql/020_webauthn_credentials.sql:16-26`; `webauthn/entity.go:29-55`.

| Column | Notes |
|---|---|
| `id VARCHAR(17)` PK | prefix `pkc_` |
| `principal_id … ON DELETE CASCADE` | |
| `credential_id BYTEA NOT NULL UNIQUE` | the authenticator-issued id, denormalised from the blob |
| `passkey_data JSONB NOT NULL` | the go-webauthn `webauthn.Credential` JSON **verbatim** (`id`, `publicKey`, `attestationType`, `transport`, `flags`, `authenticator{AAGUID, signCount, cloneWarning, attachment}`, …) — a foreign shape; legacy (webauthn-rs) blobs `{"cred":{…}}` exist in old rows (§7.6) |
| `name VARCHAR(120)` | user label; required on register since `webauthn/api/api.go:161-167` |
| `created_at`, `last_used_at` | |

Ceremony state lives in the **shared** `oauth_oidc_payloads` table
(`sql/007_oauth_tables.sql:141-151`): `id = "WebauthnRegistration:"+stateID`
or `"WebauthnAuthentication:"+stateID`, `type` = the same discriminant,
`payload` JSONB `{principalId, session, displayName?}` / `{principalId,
session}` where `session` is go-webauthn `SessionData` (challenge, user id,
allowed credential ids, expiry, UV requirement …), `expires_at = now + 10
min` (`webauthn/ceremony_repository.go:31-56, 93-109`;
`internal/sqlc/queries/webauthn_ceremony.sql`). Consume = `DELETE … WHERE id
= $1 AND (expires_at IS NULL OR expires_at > NOW()) RETURNING payload`.
`stateId` = 16 random bytes b64url (22 chars, `webauthn/api/api.go:459-463`).

### 3.6 `iam_password_reset_tokens` [C-storage]

`sql/008_auth_tracking_tables.sql:29-35` + `031:36-39` + `032:8-9` +
`033:9-10` + `041:69-70`; entity `passwordreset/passwordreset.go:40-64`.

| Column | JSON | Notes |
|---|---|---|
| `id VARCHAR(17)` PK | `id` | prefix `prt_` |
| `principal_id VARCHAR(17) NOT NULL` (no FK) | `principalId` | a `prn_` **or** a `ptu_` id — the confirm flow branches on the prefix (`passwordreset/api/api.go:804-806`) |
| `token_hash VARCHAR(64) NOT NULL UNIQUE` | `tokenHash` | lower-hex SHA-256 of the raw link token |
| `purpose VARCHAR(20) NOT NULL DEFAULT 'reset'` | `purpose` | `reset` \| `invite`; unknown reads as `reset` (`:31-36`) |
| `reset_2fa BOOLEAN NOT NULL DEFAULT FALSE` | `reset2fa` | clear all factors on confirm |
| `requires_factor BOOLEAN NOT NULL DEFAULT FALSE` | `requiresFactor` | confirm additionally needs a TOTP code |
| `factor_attempts INT NOT NULL DEFAULT 0` | `factorAttempts` | wrong-TOTP counter on this token |
| `redirect_uri VARCHAR(2000)` | `redirectUri,omitempty` | post-confirm redirect (portal invites / resets); validated at mint, never at confirm |
| `expires_at`, `created_at` | `expiresAt`, `createdAt` | 15 min (reset) / 72 h (invite) |

The raw token: 32 random bytes b64url-no-pad = 43 chars of `[A-Za-z0-9_-]`
(`passwordreset/api/api.go:788-794`, pinned by `api_test.go:29-49`);
`hashToken("")` = `e3b0c442…b855` (`api_test.go:17`).

### 3.7 `iam_reset_approval_requests` [C-storage, C-wire]

`sql/032_reset_approval.sql:15-31`; `resetapproval/resetapproval.go:30-55`.
`id` (`rar_`), `principal_id` (FK cascade), `client_id` (nullable, no FK),
`status VARCHAR(20) DEFAULT 'PENDING'` (`PENDING|APPROVED|DENIED|EXPIRED` —
`EXPIRED` is declared but **never written**; expiry is the predicate
`expires_at > NOW()`, `:98-105,113-120,150-160`), `reset_2fa BOOLEAN
DEFAULT TRUE` (always `true` from `New`, `:43-55`), `note VARCHAR(255)`
(never written), `decided_by VARCHAR(17)`, `decided_at`, `expires_at`
(72 h default), `created_at`.

### 3.8 Reference tables read by role sync

`oauth_idp_role_mappings` (`sql/007:38-44` + `035:13`): `id`,
`idp_role_name`, `internal_role_name` (Go field `PlatformRoleName`),
`idp_type` (nullable, **not** used for filtering — `bridge/login_endpoint.go:739-740`),
timestamps. `oauth_identity_provider_allowed_roles` (`sql/040:23-30`):
`(identity_provider_id, role_id)` — role **TSIDs** (`identityprovider.md`).

---

## 4. OIDC bridge (employee plane)

### 4.1 Provider resolution & cache [I]

`Bridge` caches one resolved client per `issuerURL + "|" + clientID`
(`bridge/oidc.go:31-43, 140`). Construction does the discovery round-trip
(`/.well-known/openid-configuration`) **once per process per key** and is
never invalidated: an IdP whose issuer / client id / secret changes keeps
the old client until restart (`:143-183`) — **load-bearing or accident?**
(open question 1). The mutex is held across the discovery HTTP call
(`:141-160`) — a Go convenience; the invariant is only "at most one
discovery per key", not "serialise all resolutions".

**Ruled (Q1) and as built, 2026-09-23:** one cached client per identity
provider, reused only while every field that shapes it — issuer, client id,
secret ref, multi-tenant, issuer pattern — is unchanged and the 10-minute
TTL holds. An edit is seen on the next login on every node (Go `7ab071c`
widened its key to the same fields); the provider API's same-node
`invalidate` hook only drops a deleted provider's entry early. A
provider resolving with no secret logs an INFO, since a confidential
registration refuses the exchange with only `AADSTS7000218` to show for it.

| Step | Rule | Source |
|---|---|---|
| `ResolveForEmail(email)` | domain = text after the **last** `@` (`:279-286`); `""` → error `invalid email: no domain`; `FindByEmailDomain` nil → error `no email-domain mapping for <d>`; IdP missing → error; IdP `INTERNAL` → `(nil, idp, mapping, nil)` = "no bridge needed" | `:72-99` |
| `ResolveByProviderID(id)` | IdP missing → error; not `OIDC` → error `identity provider is not OIDC`; `OIDCMultiTenant && len(AllowedEmailDomains)==0` → error `multi-tenant IdP requires allowed_email_domains for provider-direct login` (fail closed) | `:111-130` |
| build | issuer URL or client id `nil` → error; multi-tenant ⇒ discovery with `InsecureIssuerURLContext` + `SkipIssuerCheck` + `SkipClientIDCheck` (the discovery doc reports a `{tenantid}` template issuer) | `:136-160` |
| client secret | ref `nil`/`""` → public client; ref set and no encryption service → error `OIDC client_secret_ref present but no encryption service configured (set FLOWCATALYST_APP_KEY)`; decrypt failure → error (no plaintext fallback — `docs/oidc-security-audit.md:75-76`) | `:191-203` |
| scopes | `openid profile email` | `:179` |

### 4.2 ID-token verification [C-security]

`VerifyIDToken` (`bridge/oidc.go:214-228`): library verifies signature
(JWKS), `exp`, `nbf`; single-tenant also `iss` + `aud`. Multi-tenant
re-applies after the fact:

| Check | Rule |
|---|---|
| issuer | exact `==` configured issuer URL, else (multi-tenant only) regex `oidc_issuer_pattern` must **match** (`regexp.MatchString`, unanchored unless the pattern anchors); `nil`/`""`/uncompilable pattern ⇒ reject (`:244-254`) |
| audience | token `aud` list must **contain** our client id (`:232-239`) |

`docs/oidc-security-audit.md:13-33` records the owner-deferred hardening:
operators should pin the pattern to one tenant GUID (config, not code).

### 4.3 `GET /auth/oidc/login` — start [C]

`bridge/login_endpoint.go:284-367`. Query: `provider_id`, `domain`, `email`
(legacy: domain derived from it, `:288-293`), `return_url` (or legacy
`returnUrl`, `:294-297`), and the chained-OAuth params `oauth_client_id`,
`oauth_redirect_uri`, `oauth_scope`, `oauth_state`, `oauth_code_challenge`,
`oauth_code_challenge_method`, `oauth_nonce` (each stored only when
non-empty, `:349-355, 612-618`).

| Condition | Status / code | Message |
|---|---|---|
| neither `domain` nor `provider_id` | 400 `DOMAIN_REQUIRED` | `domain or provider_id query param is required` |
| `provider_id` given and `ResolveByProviderID` fails | 500 `OIDC_RESOLVE_FAILED` | `OIDC could not be initialised for this provider` |
| `domain` path, `ResolveForEmail("x@"+domain)` errors | 500 `OIDC_RESOLVE_FAILED` | `OIDC could not be initialised for this domain` |
| **Java, owner ruling 2026-09-25:** the domain has no mapping | 404 `EMAIL_DOMAIN_NOT_MAPPED` (logged at INFO) | `this email domain does not sign in with SSO` |
| `domain` path, IdP is `INTERNAL` | 400 `OIDC_NOT_CONFIGURED` | `OIDC is not configured for this domain` |
| state insert fails | 500 `OIDC_STATE` | `persist state failed` |
| ok | **302** to `<authorize endpoint>?client_id&redirect_uri&response_type=code&scope=openid profile email&state=<s>&nonce=<n>&code_challenge=<S256(verifier)>&code_challenge_method=S256` (`:361-366`, `oidc.go:258-262`) |

Precedence: `provider_id` wins over `domain` when both are present
(`:308`). Provider-direct stores mapping id `""` and `email_domain` = the
raw `domain` param (possibly `""`). `redirect_uri` =
`absoluteCallbackURL` (§4.5). All error bodies are the `ErrorModel`
envelope `{code, message}` (`httperror.Write`).

### 4.4 `GET /auth/oidc/callback` — finish [C]

`bridge/login_endpoint.go:372-591`. The table is the **ordered** decision
list; every row after "consume" has already burned the state (the user must
restart — by design, `:380-383`).

| # | Condition | Status / code | Message |
|---|---|---|---|
| 1 | `state` or `code` missing | 400 `MISSING_PARAM` | `state and code are required` |
| 2 | consume DB error | 500 `OIDC_STATE` | `lookup state failed` |
| 3 | unknown / replayed / expired state | 400 `INVALID_STATE` | `unknown or expired login session` |
| 4 | provider-direct re-resolve fails | 500 `OIDC_RESOLVE_FAILED` | `…for this provider` |
| 5 | mapping re-resolve (`"x@"+email_domain`) errors | 500 `OIDC_RESOLVE_FAILED` | `…for this domain` |
| 6 | mapping path and IdP now `INTERNAL` / mapping gone | 400 `OIDC_NOT_CONFIGURED` | |
| 7 | token exchange (with `code_verifier`) fails | 500 `OIDC_EXCHANGE` | `code exchange failed` |
| 8 | no `id_token` in the token response | 400 `NO_ID_TOKEN` | `IDP did not return id_token` |
| 9 | `VerifyIDToken` fails | **403** `OIDC_VERIFY` | `id_token verification failed: <err>` (leaks the library error text — **accident?** open question 3) |
| 10 | claims not decodable | 400 `OIDC_CLAIMS` | `id_token claims malformed` |
| 11 | `nonce` claim ≠ stored nonce | 403 `NONCE_MISMATCH` | `nonce did not match` |
| 12 | `email` empty and `preferred_username` empty | 403 `NO_EMAIL` | `id_token has no email / preferred_username claim` |
| 13 | lower-cased email contains `#ext#` | 403 `EXTERNAL_GUEST` | `external guest accounts are not supported` |
| 14a | provider-direct: IdP `allowedEmailDomains` non-empty and does not contain the email's domain (case-insensitive) | 403 `EMAIL_DOMAIN_MISMATCH` | `the token's email domain is not allowed for this identity provider` |
| 14b | mapping: email domain ≠ login domain (`EqualFold`) | 403 `EMAIL_DOMAIN_MISMATCH` | `the token's email domain does not match the login domain` |
| 14c | mapping with `requiredOidcTenantId` set: `tid` claim empty | 403 `TENANT_MISMATCH` | `id_token has no tenant id (tid) claim` |
| 14d | … `tid` ≠ required | 403 `TENANT_MISMATCH` | `id_token tenant does not match the configured tenant` |
| 15 | `portal_client_id` set → portal sink (§5.6) | | |
| 16 | principal lookup by email errors | 500 `REPO` | `principal lookup failed` |
| 17 | no principal → JIT (§4.7); its errors propagate verbatim (`:547-550`) | | |
| 18 | existing principal → `LowercaseEmail` self-heal, failure logged only | | |
| 19 | mapping path → role sync (§4.8), failure logged only | | |
| 20 | SessionWriter (§4.6) | 302 | |

Claims read: `email`, `name`, `preferred_username`, `tid`, `nonce`, `roles`
(`[]string`) (`:446-453`). Provider-direct with a single-tenant IdP whose
derived `allowedEmailDomains` is empty accepts **any** account at that IdP
(`:489-493`) — **load-bearing or accident?** (open question 4).

### 4.5 Callback URL [C-config]

`absoluteCallbackURL` (`:819-834`): `ExternalBaseURL` (wired to
`cfg.JWTIssuer`, `server/wire_routes.go:223`) trimmed of `/` +
`/auth/oidc/callback`; when unset, derived from `X-Forwarded-Proto` /
`X-Forwarded-Host` / `Host` / TLS (dev only). Used identically at start and
exchange.

### 4.6 Where the user lands + the session cookie [C]

Target (`:574-590`): (1) `oauth_client_id` stored → relative
`/oauth/authorize?response_type=code&client_id=…` + each non-empty stored
`redirect_uri`, `scope`, `state`, `code_challenge`, `code_challenge_method`,
`nonce` (`buildAuthorizeRedirect`, `:596-610`); (2) else `return_url` **only
if** it starts with a single `/` and not `//` or `/\` (`safeRelativeReturnURL`,
`:623-628`); (3) else `/dashboard`.

SessionWriter (`server/wire_routes.go:225-246`): mint `fc_session` via
`Provider.MintSessionToken(principalID, login.SessionTTL = 24 h)` and set
cookie `Name=fc_session, Path=/, HttpOnly, Secure=!AuthAllowTestHeaders,
SameSite=Lax, MaxAge=86400` then **302** to the target. Mint failure →
`http.Error` **plain-text 500** `session mint failed: <err>` (not the
envelope — **accident?** open question 5). The default JSON `{principalId}`
writer (`bridge/login_endpoint.go:129-136`) is unreachable because the
callback always passes a non-empty target.

### 4.7 JIT provisioning (principal side effect) [C-behaviour]

Mapping path (`autoProvision`, `:640-675`): re-fetch the mapping **by the id
stored in the state** (not the re-resolved one) — DB error → 500 `REPO`
`email_domain_mapping lookup failed`; missing → 403 `MAPPING_GONE` `The
email-domain mapping that drove this login no longer exists; cannot
auto-provision`. Then `principalops.CreateUser{Email, Scope =
mapping.ScopeType, ClientID = mapping.PrimaryClientID, IDPType = "OIDC"}`
with system actor `""`: email lower/trim + regex
`^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$`; scope must be
`ANCHOR|PARTNER|CLIENT`; **`CLIENT`/`PARTNER` scope requires a non-nil
`clientId`** → a mapping with no `primaryClientId` makes the JIT fail with
400 `CLIENT_REQUIRED` (`principal/operations/create.go:39-65`) —
**load-bearing or accident?** (open question 6); `EMAIL_EXISTS` 409 on a
race; `IDPType=="OIDC"` ⇒ `password_hash = NULL`, `provider = "OIDC"`
(`create.go:90-96`); event `platform:iam:user:created` (source
`platform:iam`). No roles are assigned here (`:636-639`). Then the principal
is re-read (`post-create principal lookup failed` / `missing` → 500 `REPO`).

Provider-direct (`autoProvisionPortal`, `:681-700`): `CreatePortalUser{Email,
Provider:"OIDC"}` → an **inert** USER principal: scope `CLIENT`, `client_id
NULL`, `AllApplications=false`, no roles, no password, provider `OIDC`
(`principal/operations/create_portal.go:37-86`; pinned by
`create_portal_pg_test.go:24-59`). Note this path only runs when a portal
SSO state is **not** flagged `portal_client_id` — i.e. `GET
/auth/oidc/login?provider_id=` without a portal flow. **load-bearing or
accident?** (open question 7: should `?provider_id=` without a portal flow
still exist now that the portal plane is separate?)

### 4.8 IdP role sync (principal side effect) [C-behaviour]

`syncIdpRoles` (`:724-808`), mapping path only, **after** authentication,
best-effort (any error → warn log, login continues, `:562-570`):

1. Re-read the IdP; missing → apply the **empty** set (drop every
   `IDP_SYNC` role, `:729-734`); `syncRolesFromIdp == false` → **no-op**
   (roles left exactly as they are, `:735-737`).
2. Load **all** `oauth_idp_role_mappings` (not filtered by `idp_type`);
   build `idp_role_name → internal_role_name` (last duplicate wins).
3. Resolve `allowedRoleIds` → role **names** via `roles.FindByID`; a missing
   role id is warned and skipped. `hasAllowList = len(allowedRoleIds) > 0`
   — if every id is dangling, the allow-set is empty but the filter is still
   on, so **every** claim role is rejected (`:754-767, 780-786`) —
   **load-bearing or accident?** (open question 8).
4. For each claim role: unmapped → `slog.Warn("REJECTED unauthorized IDP
   role: not found in idp_role_mappings", principalId, idpRole)`; mapped but
   not allowed → debug skip; else keep (set semantics, order unspecified).
5. `SyncIdpRolesCommand{UserID, PlatformRoles}` as system actor:
   `USER_ID_REQUIRED`; `User_NOT_FOUND`; non-USER → `NOT_A_USER`
   (business-rule); **every** supplied name must exist → `ROLE_NOT_FOUND`
   (the whole sync fails); keep every assignment whose
   `assignment_source != 'IDP_SYNC'`, append the new set tagged
   `IDP_SYNC` (deduped against the kept names — an admin-assigned role of
   the same name is **not** re-tagged), `updated_at = now`; event
   `platform:iam:user:roles-assigned` `{userId, roles, added, removed}`;
   junction rewritten via `RolesPersister` (`principal/operations/sync_idp_roles.go:44-129`).

An empty `roles` claim is valid input: it strips all `IDP_SYNC` roles
(`:719-723`).

### 4.9 `GET /auth/oidc/session/end` — RP-initiated logout [C]

`bridge/login_endpoint.go:160-238`. Always clears `fc_session` first
(`Value="", Path=/, HttpOnly, Secure=CookieSecure, SameSite=Lax, MaxAge=-1`).
Then:

| Condition | Response |
|---|---|
| no `post_logout_redirect_uri` | 200 `{"message":"Session ended"}` |
| client id from `id_token_hint` `aud` (payload base64url-decoded **without** signature check; `aud` string or first array entry, `:245-269`), else `client_id` param; neither | 400 `{"error":"invalid_request","error_description":"Invalid post_logout_redirect_uri: id_token_hint or client_id is required to verify post_logout_redirect_uri"}` |
| client lookup error | 400 … `internal error verifying client` |
| unknown client | 400 … `id_token_hint audience does not match any registered client` |
| URI not in the client's `post_logout_redirect_uris` (`oauthapi.MatchRedirectURI`: exact, else wildcard-in-leftmost-label rules — `internal/platform/auth/oauthapi/redirecturi.go:28-64`) | 400 … `not in the client's registered post_logout_redirect_uris` |
| ok | **303** to the URI, `state` appended (`?`/`&` chosen by presence of `?`) |

The error body is the **OAuth** `{error, error_description}` shape, not
`ErrorModel` [C].

### 4.10 `/auth/check-domain` [C]

`POST` (`login/endpoint.go:161-206`), body `{email}`:

| Condition | Response |
|---|---|
| undecodable JSON | 400 `INVALID_JSON` (message = decoder text) |
| blank email | 400 `EMAIL_REQUIRED` `email is required` |
| no `@` or trailing `@` | 200 `{"authMethod":"internal"}` (deliberately not a 400: no malformed-domain leak, `:174-180`) |
| mapping or IdP missing / lookup error | 200 `{"authMethod":"internal"}` |
| IdP `OIDC` | 200 `{"authMethod":"external","loginUrl":"/auth/oidc/login?domain=<domain>","idpIssuer":"<issuer>"?}` — domain lower-cased and percent-encoded with a custom encoder that keeps `[A-Za-z0-9-_.~@]` (`:689-718`) |
| IdP `INTERNAL` | 200 `{"authMethod":"internal"}` |

`GET ?email=` legacy (`:222-245`): 200 `{domain, authMethod: "INTERNAL"|"OIDC",
providerId?, authorizationUrl?}` where `authorizationUrl = <issuer trimmed>/authorize`
(a naive guess, not the discovery endpoint — **accident?** open question 9)
and `providerId` is set for **any** mapped IdP type.

Login-side enforcement that belongs with this surface: `POST /auth/login`
refuses a password for any domain mapped to an `OIDC` IdP with 403
`SSO_REQUIRED` `This email domain signs in through its identity provider;
password login is disabled`, after recording a failed attempt `SSO
required` (`login/endpoint.go:415-432`); `ssoManaged` (external identity or
OIDC-mapped domain) closes `/auth/change-password` with 400 `SSO_MANAGED`
(`login/change_password.go:32-38`).

### 4.11 Rate limit [I→C]

`/auth/oidc/*` and `/portal/*` share a **per-instance, per-IP** token bucket
(`FC_OIDC_RATE_PER_MIN`=60, `FC_OIDC_BURST`=30; `server/wire_routes.go:247-277`,
`ratelimit/governor.go:69-74`). Rejection: 429, `Retry-After: <s>`, body
`{"error":"TOO_MANY_REQUESTS","message":"Too many authentication requests"}`
(`ratelimit/ratelimit.go:179-187`). IP = rightmost `X-Forwarded-For` hop,
else `RemoteAddr` (`:219-240`); no IP ⇒ pass.

---

## 5. Portal plane

### 5.1 `GET /portal/authorize` [C]

`portalauth/endpoints.go:63-139`. Direct (pre-redirect) failures use the
OAuth `{error, error_description}` JSON body; post-validation failures
**307**-redirect to `redirect_uri` with `error`, `error_description`,
`state` query params (`errRedirect`, `:420-428`).

| Condition | Outcome |
|---|---|
| `state` blank | 400 `invalid_request` ``\`state\` parameter is required for CSRF protection`` |
| client lookup error | 500 `server_error` `Internal error` |
| client unknown or inactive | 400 `unauthorized_client` `Unknown or inactive client` |
| client has no `portal_client_id` | 400 `unauthorized_client` `Client is not a portal client` |
| `redirect_uri` not matched (`MatchRedirectURI`) | 400 `invalid_request` `Invalid redirect_uri` |
| `response_type != code` | 307 `unsupported_response_type` `Only 'code' response type is supported` |
| `client.PKCERequired` and no `code_challenge` | 307 `invalid_request` `PKCE code_challenge is required` |
| `code_challenge_method` present and ≠ `S256` | 307 `invalid_request` `Only the S256 code_challenge_method is supported` |
| flow build / insert fails | 307 `server_error` `Could not start the login flow` |
| ok | **307** to `LoginPagePath` (default `/portal/login`) `?flow=<id>` |

### 5.2 `POST /portal/auth/check-domain` [C]

`:144-182`, body `{flowId, email}`: undecodable → 400 `INVALID_BODY`
`malformed request body`; flow lookup error → 500 `FLOW`; absent/expired
→ 400 `FLOW_EXPIRED` `The login flow has expired — return to the portal and
try again`; domain empty (`LastIndexByte('@')` must be > 0 and not last,
lower-cased, `:385-392`) → 400 `EMAIL_INVALID` `email is not valid`; IdP
scan error → 500 `IDP`; an `OIDC` IdP whose derived `allowedEmailDomains`
contains the domain (`identityprovider.OIDCProviderForDomain`, case-insensitive
scan of `FindAll`, `internal/platform/identityprovider/portal_domain.go:18-35`)
→ 200 `{"method":"SSO","redirectUrl":"/portal/auth/oidc/login?flow=<id>&provider_id=<idp>"}`;
else 200 `{"method":"PASSWORD"}`. Never reveals whether the identity exists.

### 5.3 `POST /portal/auth/login` [C]

`:197-270`, body `{flowId, email, password}`:

| # | Condition | Outcome |
|---|---|---|
| 1 | bad JSON / flow errors | as §5.2 (`INVALID_BODY`, `FLOW`, `FLOW_EXPIRED`) |
| 2 | cluster rate limit `portal_login` bucket, key `<portalClientId>:<lower(trim(email))>`, **10 attempts / 15 min** (`FC_RL_PORTAL_LOGIN_PER_15MIN`; `ratelimit/ratelimit.go:74`) — counts every attempt, success included (`CheckAndRecord`), backend error fails **open** (`:162-175`) | 429 `{"error":"TOO_MANY_REQUESTS","message":"too many attempts"}` + `Retry-After` |
| 3 | domain owned by an OIDC IdP | **401** `{"code":"SSO_REQUIRED","message":"Sign in with your organisation account"}` |
| 4 | identity lookup error | 500 `IDENTITY` |
| 5 | no identity, `DISABLED`, or no hash | `passwordhash.EqualizeTiming` then 401 `{"code":"INVALID_CREDENTIALS","message":"Invalid email or password"}` — flow **not** consumed |
| 6 | wrong password | 401 same |
| 7 | `Consume` flow nil/err (raced or expired meanwhile) | 400 `FLOW_EXPIRED` |
| 8 | `IssueCode` fails | 500 `CODE` `could not issue the authorization code` |
| 9 | ok | `TouchLastLogin` best-effort; 200 `{"redirectUrl":"<redirect_uri>?code=<c>&state=<s>"}` — **no cookie** |

Pinned by `portalauth/portal_flow_pg_test.go:40-128` (journey),
`:158-203` (suspended = uniform 401), `:290-371` (SSO domain refuses the
correct password).

### 5.4 `POST /portal/auth/password-reset` [C]

`:279-334`, body `{flowId, email}`: `INVALID_BODY` / `FLOW` / `FLOW_EXPIRED`;
email lower/trim; bad domain → 400 `EMAIL_INVALID`; rate limit key
`reset:<portalClientId>:<email>` (same bucket/policy); SSO-owned domain →
silent 200; otherwise, only an existing **ACTIVE** identity in the flow's
client is mailed (`SendPortalReset(identity.ID, identity.Email,
portalOriginOf(flow.RedirectURI))`, errors swallowed); always 200
`{"message":"If an account exists, a reset email has been sent."}`.
`portalOriginOf` = `scheme://host/` of the flow's validated redirect URI,
`nil` when unparsable or the host contains `*` (`:340-347`; pinned
`portal_flow_pg_test.go:232-284`).

### 5.5 `IssueCode` [C-behaviour]

`:352-374`: code = 48 random bytes b64url (64 chars);
`grantstore.NewAuthorizationCode(raw, flow.OAuthClientID, subjectID,
flow.RedirectURI)` + `scope`, `nonce`, `state`, `code_challenge(+method)`;
insert; redirect = `redirect_uri` + `?`/`&` + `code=…&state=…`. The
subject is the `ptu_` identity id (test `:117-122`).

### 5.6 Portal SSO: `GET /portal/auth/oidc/login` + callback sink [C]

Start (`bridge/login_endpoint.go:866-929`): `Portal` not wired → 500
`PORTAL_DISABLED` `portal login is not configured`; `flow` or `provider_id`
missing → 400 `MISSING_PARAM` `flow and provider_id are required`; the flow
is **consumed here** (single-use at SSO start — a failed IdP round-trip
means restarting from the portal; **load-bearing or accident?** open
question 10): error → 500 `FLOW`, nil → 400 `FLOW_EXPIRED`;
`ResolveByProviderID` error → 500 `OIDC_RESOLVE_FAILED`; (the 403
`IDP_NOT_EXTERNAL` branch at `:899-903` is unreachable — `ResolveByProviderID`
already rejects non-OIDC); state row with `email_domain=""`,
`email_domain_mapping_id=""`, `portal_client_id=flow.PortalClientID` and the
flow's OAuth chain copied into `oauth_*`; insert → 500 `OIDC_STATE`; **302**
to the IdP exactly as §4.3. Any OIDC IdP may serve any portal (`:894-898`;
`docs/portal-identity-plan.md:173-179`).

Sink (`:937-1000`, entered at §4.4 row 15 after rows 1-14a passed —
provider-direct binding = the IdP's derived `allowedEmailDomains`):

| Condition | Outcome |
|---|---|
| `Portal` nil | 500 `PORTAL_DISABLED` |
| `oauth_client_id` / `oauth_redirect_uri` / `oauth_state` missing on the state | 400 `PORTAL_STATE_INVALID` `portal login state is missing its OAuth chain` |
| identity lookup error | 500 `IDENTITY` `identity lookup failed` |
| no identity for (portalClientId, email) | JIT `portalidentity.Ensure{ClientID, Email, Name: trimmed id_token name or nil, Source: "JIT"}` as system actor; op errors propagate (`CLIENT_ID_REQUIRED`/`EMAIL_REQUIRED`/`EMAIL_INVALID` 400, `Client_NOT_FOUND` 404, `REPO` 500); re-read failure → 500 `IDENTITY` `post-create identity lookup failed` |
| identity `DISABLED` | **302** to `oauth_redirect_uri` with `error=access_denied&error_description=This account is suspended for this portal&state=<oauth_state>` (`portalErrorRedirect`, `:1004-1014`) — SSO never self-reactivates |
| `IssueCode` fails | 500 `CODE` |
| ok | `TouchLastLogin` best-effort; **302** to the code redirect; **no** `fc_session` |

### 5.7 Portal identity operations & admin API [C]

Operations (`portalidentity/operations.go`; all `Authorize: Public` — the
controller gates):

| Op | Validate | Execute | Event (`source platform:portal`, subject `platform.portal-identity.<id>`, group `platform:portal-identity:<id>`) |
|---|---|---|---|
| `Ensure{clientId, email, name?, source}` | `CLIENT_ID_REQUIRED`, `EMAIL_REQUIRED`, `EMAIL_INVALID` (`@` at index > 0 and not last) | client missing → `Client_NOT_FOUND` 404; source ≠ `JIT` ⇒ `INVITE`; existing row → `status=ACTIVE`, name overridden only when a non-blank name is supplied; new → `New` (lower/trim email, `ACTIVE`); upsert | `platform:portal:identity:ensured` `{identityId, clientId, email, created, source}` (`events.go:11,23-51`) |
| `SetStatus{id | clientId+email, status}` | `TARGET_REQUIRED`, `STATUS_INVALID` (`ACTIVE`/`DISABLED`) | not found → `PortalIdentity_NOT_FOUND`; `clientId` given and ≠ row's → same 404 (cross-client hidden) | `platform:portal:identity:status-set` `{identityId, clientId, status}` |
| `Delete{clientId, id}` | `ID_REQUIRED` | 404 rules as above; row deleted | `platform:portal:identity:deleted` `{identityId, clientId, email}` |

`/api/portal-users` (lockfile; `portalidentity/api/api.go:61-68`), all
inside the auth middleware; authorization `CanManagePortalUsers` /
`CanReadPortalUsers` (`internal/platform/shared/auth/auth.go:656-682`):
anchor passes; else `CanAccessClient(clientId)` (`SCOPE_FORBIDDEN` `no
access to this client`) **and** permission `platform:iam:portal-user:manage`
(manage) or `view|manage` (read); unauthenticated → `UNAUTHENTICATED`. All
three spell 403.

| Route | Request | Behaviour | Response |
|---|---|---|---|
| `POST /api/portal-users` | `PortalUserRequest{clientId*, email*, name?, returnInviteLink?, redirectUri?}` | `CLIENT_ID_REQUIRED`; `redirectUri` (trimmed) must **exactly** equal a registered redirect URI of one of the client's portal-flagged OAuth clients (`REDIRECT_URI_INVALID` 400; repo unwired → 500 `PORTAL_REDIRECT`), absent ⇒ `defaultPortalRedirect` = first parseable, non-wildcard registered URI's `scheme://host/` or `nil` (`:226-251`); `Ensure(INVITE)`; re-read (`REPO` 500); **SSO-owned domain** (IdPs wired): no set-password step — `returnInviteLink` ⇒ `inviteUrl = redirectUri or default origin`, else `SendPortalSSOInvite(email, target)` when a target exists (`invited=true` on success); else **invite only while no password**: `returnInviteLink` ⇒ `PortalInviteLink` (`INVITE_LINK` 500), else `SendPortalInvite` (`INVITE_EMAIL` 500 — "a retry re-sends") | `PortalUserResponse{identityId, created, invited, inviteUrl?, ssoManaged?, hasPassword}` |
| `GET /api/portal-users?clientId=` | | `CLIENT_ID_REQUIRED`; newest first | `{portalUsers:[{identityId, email, name, status, source, hasPassword, lastLoginAt?, createdAt, updatedAt}]}` |
| `POST …/{id}/activate` / `deactivate` | `{clientId*}` | `SetStatus` | `{message:"Portal user activated"|"Portal user deactivated"}` |
| `DELETE …/{id}?clientId=` | | `Delete` | `{message:"Portal user deleted"}` |

Pinned: `portalidentity/api/ensure_sso_pg_test.go:55-138` (SSO domain →
`ssoManaged`, "open the portal" invite, never a set-password invite;
`returnInviteLink` on an SSO domain returns the portal origin).

### 5.8 Boundary: redeeming a portal code

`/oauth/token` branches on the `ptu_` prefix
(`internal/platform/auth/oauthapi/portal_token.go:13-60`): identity must
exist and be `ACTIVE` (`invalid_grant` otherwise), id_token minted from the
identity with **empty** roles, access token authority-free, **no refresh
token**. Specced with the OAuth provider; listed here so the portal state
machine (§11.8) is complete.

---

## 6. Two-factor authentication

### 6.1 Domain policy [C-behaviour]

`twofa.Policy.Evaluate(email)` (`twofa/policy.go:33-50`): no `@` / trailing
`@` → `{Internal:true}`; domain lower-cased; no mapping → internal, no
policy; mapping found → `Internal = IdP.Type != OIDC` (IdP lookup failure ⇒
internal). Derived: `Requires2FA = mapping.require2fa && Internal`;
`AllowedMethods = mapping.allowed2faMethods` (nil when unmapped);
`RememberEnabled = mapping.rememberDeviceEnabled && Internal`;
`RememberDays = mapping.rememberDeviceDays`, `≤0 → 30` (`:54-77`).

`login/twofactor.go:86-88` computes `rememberAllowed` **without** the
`Internal` term (unlike `status`/`rememberDevice`) — only observable for an
OIDC-mapped user holding a password, whom `SSO_REQUIRED` already blocks;
**accident** (open question 11: align to `RememberEnabled()`).

### 6.2 The `/auth/login` decision [C]

After the password verifies (`login/endpoint.go:467-486`; decision
`login/twofactor.go:76-145`), when MFA **and** MFATokens are wired:

| Case | Response (HTTP 200, **no cookie**) |
|---|---|
| principal has an external identity | proceed to normal login |
| `usable = confirmed methods ∩ allowedMethods` (intersection only when the domain requires 2FA) is non-empty; remember-device allowed and the `fc_td` cookie verifies | proceed (`VerifyTrustedDevice` stamps `last_used_at`) |
| `usable` non-empty otherwise | `{"status":"mfa_required","mfaToken":<pending JWT 10 min>,"methods":[…usable],"rememberDeviceAllowed":bool}` |
| `usable` empty and domain does not require 2FA | proceed |
| `usable` empty and domain requires 2FA | `{"status":"enrollment_required","enrollToken":<enroll JWT 30 min>,"allowedMethods":[…]}` |
| evaluation error (`ConfirmedMethods` / `VerifyTrustedDevice` / mint) | **500** `{"code":"MFA_EVAL_FAILED","message":"could not evaluate two-factor requirement"}` — fail closed |

Completed login body (`loginResponse`, `login/endpoint.go:343-361`): `{status:"ok",
principalId, name, email, roles[], permissions[], clientId (nullable),
recoveryCodes?[] (enrol-and-complete only), ssoManaged}`.

### 6.3 `mfatoken` — pending / enroll JWT [C-security]

`mfatoken/mfatoken.go`: HS256, secret = `SHA-256("fc-mfa-token-v1|" ‖ RSA
private exponent D bytes)` (`:48-51`) — stable across instances sharing the
session key, and **structurally unusable as a session cookie** because the
session validator accepts RS256 only (`mfatoken_test.go:73-80`). Claims:
`iss` (provider issuer), `sub`, `prp` (`mfa_pending` | `mfa_enroll`), `iat`,
`nbf`, `exp` (`:60-74`). `Parse(tok, want)`: HS256-only, issuer must match,
`sub` non-empty, `prp == want` (`:78-104`); tests pin wrong-purpose,
expiry, tamper, cross-key rejection (`mfatoken_test.go:36-68`). Every
`/auth/2fa/*` token-gated handler maps any parse failure or an
inactive/missing principal to 401 `{"code":"UNAUTHENTICATED","message":"Invalid
or expired session"}` with `WWW-Authenticate: Cookie realm="fc_session"`
(`login/twofactor.go:391-403`, `login/endpoint.go:676-684`).

### 6.4 The 14 `/auth/2fa/*` routes [C]

Mounted only when wired (`login/twofactor.go:57-67` needs MFA **and**
MFATokens; `login/twofactor_selfservice.go:20-33` needs MFA only). The six
token-gated routes sit on the **public** router; the eight self-service
routes inside the auth middleware (`login/endpoint.go:119-148`). All bodies
are `{code, message}` JSON; `INVALID_JSON` = 400 `malformed request body`.

| Route | Gate | Request | Outcomes |
|---|---|---|---|
| `POST /auth/2fa/verify` | pending token | `{mfaToken, method, code, rememberDevice}` | backoff (`loginattempt.md` §5, same (email, IP) budget) → 429 `{"code":"TOO_MANY_REQUESTS","message":"too many failed login attempts; try again later"}` + `Retry-After`; `method` upper/trim ∉ {`TOTP`,`EMAIL_PIN`,`RECOVERY_CODE`} → 400 `INVALID_METHOD` `unknown 2FA method`; service error → 500 `VERIFY_FAILED` `could not verify code`; wrong → attempt `FAILURE` `Invalid 2FA code` recorded, 401 `Invalid or expired code`; ok → (`RECOVERY_CODE` ⇒ notify `RecoveryCodeUsed`), (`rememberDevice` ⇒ §6.7), `completeLogin` (cookie + attempt `SUCCESS` + `loginResponse`) |
| `POST /auth/2fa/challenge/email` | pending | `{mfaToken}` | no email → 400 `NO_EMAIL` `account has no email`; send fails → **502** `EMAIL_SEND_FAILED` `could not send code`; 200 `{"message":"A verification code has been sent to your email."}` |
| `POST /auth/2fa/enroll/totp/begin` | enroll | `{enrollToken}` | method not allowed for a 2FA-required internal domain → 403 `METHOD_NOT_ALLOWED` `authenticator app is not permitted for this domain`; service errors §6.5; 200 `{secret, uri, qr}` |
| `POST /auth/2fa/enroll/totp/confirm` | enroll | `{enrollToken, code}` | bad code → 400 `INVALID_CODE` `that code didn't match — try again`; ok → notify `TwoFactorEnrolled(TOTP)`, audit `2FA_TOTP_ENROLLED`, `completeLogin` with `recoveryCodes` (first set, §6.6) |
| `POST /auth/2fa/enroll/email/begin` | enroll | `{enrollToken}` | `METHOD_NOT_ALLOWED` `email codes are not permitted for this domain`; `NO_EMAIL`; 200 message as challenge |
| `POST /auth/2fa/enroll/email/confirm` | enroll | `{enrollToken, code}` | as TOTP confirm; audit `2FA_EMAIL_ENROLLED`; `recoveryCodes` omitted unless TOTP is also confirmed |
| `GET /auth/2fa/status` | session | | 200 `{methods[], required, allowedMethods[] (domain list when required, else ["TOTP","EMAIL_PIN"]), recoveryCodesLeft, rememberDeviceEnabled, trustedDeviceCount}`; `ConfirmedMethods` error → 500 `STATUS_FAILED` |
| `POST /auth/2fa/methods/totp/begin` | session | | as enroll begin |
| `POST /auth/2fa/methods/totp/confirm` | session | `{code}` | as enroll confirm but 200 `{"recoveryCodes":[…]}` (`[]` when none generated) |
| `POST /auth/2fa/methods/email/begin` / `confirm` | session | — / `{code}` | as above |
| `DELETE /auth/2fa/methods/{method}` | session | | unknown method → 400 `INVALID_METHOD`; load error → 500 `REMOVE_FAILED`; 2FA-required domain and this is the last confirmed factor → 409 `LAST_FACTOR` `your organisation requires 2FA — add another method before removing this one`; delete error → 500; ok → notify `TwoFactorMethodRemoved`, audit `2FA_METHOD_REMOVED`, 200 `{"message":"Two-factor method removed."}` (deleting a method the user never had is also 200) |
| `POST /auth/2fa/recovery-codes/regenerate` | session | | no confirmed TOTP → 400 `NO_TOTP` `recovery codes apply to authenticator-app 2FA`; error → 500 `REGEN_FAILED`; ok → notify `RecoveryCodesRegenerated`, audit `2FA_RECOVERY_REGENERATED`, 200 `{"recoveryCodes":[10 codes]}` |
| `GET /auth/2fa/trusted-devices` | session | | 200 `{"devices":[TrustedDevice JSON §3.4]}`; error → 500 `LIST_FAILED` |
| `DELETE /auth/2fa/trusted-devices/{id}` | session | | owner-scoped delete; 200 `{"message":"Device removed."}` even when nothing matched; error → 500 `REVOKE_FAILED` |

`/auth/2fa/verify` does **not** re-apply the domain's allowed-method list:
any confirmed factor (or a recovery code) is accepted even if the
`mfa_required` response offered fewer (`login/twofactor.go:180-191`) —
**load-bearing or accident?** (open question 12).

Audit rows (`login/twofactor.go:350-363`): direct `aud_logs` insert,
`entity_type=PRINCIPAL`, `entity_id = principal_id = the user`,
`operation` ∈ {`2FA_TOTP_ENROLLED`, `2FA_EMAIL_ENROLLED`,
`2FA_METHOD_REMOVED`, `2FA_RECOVERY_REGENERATED`}; admin reset writes
`2FA_RESET_BY_ADMIN` with the admin as principal
(`principal/api/api.go:1365-1375`). Challenge success/failure is in
`login_attempts`, not audit.

### 6.5 The MFA service [C-behaviour unless noted]

Defaults (`mfa/service.go:48-57`): issuer `FlowCatalyst` (overridden live by
the platform name when a resolver is wired, `:77-84`, `server/wire_services.go:181-184`),
PIN 6 digits, PIN TTL 10 min, PIN max attempts 5, 10 recovery codes,
trusted-device default TTL 30 d.

TOTP (`mfa/crypto.go:21-27, 47-105`): RFC 6238, **period 30 s, skew ±1
step, 6 digits, SHA-1, 20-byte secret**; `otpauth://` URI via pquerna/otp
(`totp/<issuer>:<account>?secret=<base32>&issuer=…&period=30&algorithm=SHA1&digits=6`);
QR = 240×240 PNG as `data:image/png;base64,…` (best-effort; absent on render
failure). Validation iterates steps `−1..+1` around `now/30`, constant-time
compares the candidate, returns the matched step; replay guard rejects
`step ≤ stepFromTime(last_used_at)` (`service.go:277-280`); the accepted
step's representative time (`step×30`, UTC) is stored. Pinned by
`mfa/crypto_test.go:24-66` and `service_pg_test.go:93-112` (the enrolment
code cannot be replayed at login).

| Service call | Behaviour | Errors |
|---|---|---|
| `BeginTOTPEnrollment(pid, account)` | confirmed TOTP exists → `ErrAlreadyEnrolled`; an unconfirmed row is deleted and replaced; secret encrypted with the app key; returns `{Secret base32, URI, QR?}` | no encryption service → `ErrEncryptionUnavailable` |
| `ConfirmTOTPEnrollment(pid, code)` | no row / no secret → `ErrNoPendingEnrollment`; confirmed → `ErrAlreadyEnrolled`; bad code → `false`; ok → `confirmed_at=now`, `last_used_at=step` | decrypt failure → error |
| `BeginEmailEnrollment(pid, email)` | confirmed → `ErrAlreadyEnrolled`; row inserted if absent; PIN issued with purpose `enroll` | mail send error propagates |
| `ConfirmEmailEnrollment(pid, code)` | verify `enroll` PIN; ok → confirm the row (row missing → `ErrNoPendingEnrollment`) | |
| `SendLoginEmailPin` / `VerifyLoginEmailPin` | purpose `login` | |
| `VerifyTOTP(pid, code)` | not enrolled / unconfirmed → `false`; bad → `false`; replay → `false`; ok → stamp | `ErrEncryptionUnavailable` (→ 500 at verify) |
| `VerifyRecoveryCode(pid, code)` | hash of the normalised code; unused row → guarded `UPDATE … SET used_at WHERE used_at IS NULL` (single-use, race-free, `repository.go:138-146`) | |
| `GenerateRecoveryCodes(pid)` | delete the whole set, insert 10 fresh | |
| `RemainingRecoveryCodes` | `COUNT(*) WHERE used_at IS NULL` | |
| `RemoveMethod(pid, type)` | delete by type (policy at the caller) | |
| `ResetAll(pid)` | delete methods, recovery codes, pins (both purposes), trusted devices (`:337-351`) | |
| `IssueTrustedDevice(pid, label, ttl)` | `ttl ≤ 0 → 30 d`; raw = 32 random bytes b64url; store SHA-256 hex; returns raw | |
| `VerifyTrustedDevice(pid, raw)` | `""` → false; `expires_at > NOW()` and hash match → touch `last_used_at`, true | |
| `ListTrustedDevices` / `RevokeTrustedDevice(pid, id)` / `RevokeAllTrustedDevices` | newest first / owner-scoped delete / delete all | |

E-mail PIN (`service.go:408-453`): issuing deletes every outstanding PIN of
that purpose, draws `n` digits uniformly (zero-padded, `crypto.go:109-120`,
`crypto_test.go:68-83`), stores the hash with `expires_at = now + 10 min`,
sends subject **`Your verification code`** with `renderEmailPin`
(`mfa/templates.go:11-19`: `<p>Your verification code is:</p><p style="font-size:24px;font-weight:bold;letter-spacing:3px">NNNNNN</p><p>This code expires in 10 minutes.</p><p>If you did not try to sign in, you can ignore this email.</p>`);
a send failure **propagates** (the user needs the PIN). Verification reads
the **latest** PIN of the purpose: none → false; expired **or** `attempts ≥
5` → delete, false; constant-time hex match of the trimmed code → delete,
true; else `attempts+1` (atomic `RETURNING`), delete when the new count `≥
5`, false. Pinned `service_pg_test.go:114-134` (wrong PIN, correct PIN once,
consumed PIN refused).

Recovery codes (`crypto.go:30-31, 122-158`): format `XXXXX-XXXXX`, alphabet
`ABCDEFGHJKMNPQRSTVWXYZ23456789` (30 symbols: Crockford base32 without `I L
O U 0 1`), rejection-sampled; normalisation = trim, upper-case, strip `-`
and spaces (`"  a7k2m 9pqrt "` ≡ `"A7K2M-9PQRT"`, `crypto_test.go:85-110`);
stored as SHA-256 hex of the normalised form. Recovery codes back **TOTP
only**: `ensureRecoveryCodes` generates the first set only when TOTP is
confirmed and zero remain (`login/twofactor.go:369-385`); regenerate
requires TOTP.

### 6.6 Enrol-and-complete, self-service confirm

Token-gated confirm ⇒ `completeLogin(recoveryCodes)` (cookie + success
attempt + `loginResponse` with `recoveryCodes` when a first set was
generated). Session-gated confirm ⇒ 200 `{"recoveryCodes":[…]|[]}`. Both
notify `TwoFactorEnrolled` and audit.

### 6.7 Trusted-device cookie [C]

`rememberDevice` (`login/twofactor.go:425-456`): only when the mapping has
`rememberDeviceEnabled` **and** the domain is internal; `ttl =
rememberDeviceDays × 24 h` (`≤ 0 → 30 d`); label = trimmed `User-Agent`,
truncated to 250, nil when empty; cookie **`__Host-fc_td`** when
`CookieSecure` else **`fc_td`** (`:34-37`), `Path=/`, `HttpOnly`,
`Secure=CookieSecure`, **`SameSite=Strict`**, `Expires`/`MaxAge=ttl`; then
notify `NewTrustedDevice(label)`. The login decision reads the same cookie
name (`:465-471`). Revoked on: password change (`login/change_password.go:93-97`),
password reset confirm (§8.4), admin/self 2FA reset, explicit revoke.

### 6.8 Change-password interplay [C]

`/auth/change-password` (`login/change_password.go:19-108`), session-gated,
body `{currentPassword, newPassword, code}`: `SSO_MANAGED` 400;
`NO_PASSWORD` 400; wrong current → 401 `INVALID_CURRENT_PASSWORD`; policy →
400 `<policy code>`; any confirmed factor and no `code` → 400
`{"code":"MFA_REQUIRED","message":"Enter a code from your second factor to
change your password.","methods":[…]}`; code accepted by **any** confirmed
factor (TOTP, email PIN) or a recovery code when TOTP is confirmed
(`:113-133`), else 400 `INVALID_CODE`; then hash, `UpdatePasswordHash`,
revoke trusted devices + refresh tokens (best-effort), notify
`PasswordChanged`, 200 `{"message":"Your password has been changed."}`.
`POST /auth/change-password/send-email-code`: `NO_MFA` / `NO_EMAIL_2FA` /
`NO_EMAIL` 400, `SEND_FAILED` 500, 200 `{"message":"A code has been sent to
your email."}` (`:138-166`).

### 6.9 Admin hooks [C]

`POST /api/principals/{id}/reset-2fa` (`principal/api/api.go:1338-1377`):
MFA unwired → 500 `MFA_NOT_CONFIGURED`; 404; `RequireUserAdmin(ac,
p.ClientID)` (anchor, or user-write permission + `CanAccessClient`; nil
client → `ANCHOR_REQUIRED`); non-anchor targeting a non-CLIENT-scope user →
403 `Client administrators can only manage client-scope users`
(`:803-808`); non-user → 400 `NOT_USER`; `ResetAll`; notify
`TwoFactorReset`; audit `2FA_RESET_BY_ADMIN`; 200 `{"message":"Two-factor
authentication reset"}`. `GET /api/principals/{id}` enriches
`twoFactorMethods[]` with confirmed factor names (`:316-327`).

---

## 7. Passkeys (WebAuthn)

### 7.1 Relying party configuration [C-config]

`webauthn.NewService(Config{RPDisplayName, RPID, RPOrigins})` wraps
go-webauthn (`webauthn/service.go:26-36`). `RPDisplayName` = platform name
read **once at startup** (`server/wire_services.go:160-167`), `RPID` =
`FC_WEBAUTHN_RP_ID` (default `localhost`), origins = comma-separated
`FC_WEBAUTHN_ORIGINS`, fallback `FC_WEBAUTHN_RP_ORIGIN`, default
`http://localhost:8080` (`server/envcfg.go:283-293`); every origin must be
listed verbatim (exact scheme+host match). User handle =
`[]byte(principalID)`, username = e-mail, display name = principal name or
the caller's `displayName` (`service.go:55-72`; `api/api.go:109-127`).

### 7.2 Routes (lockfile) [C]

All six are huma operations inside the auth middleware; errors are the
`ErrorModel` envelope, statuses via `usecase` kinds (validation 400,
authorization 403, not-found 404, internal 500).

| Route | Auth | Request | Behaviour | Response |
|---|---|---|---|---|
| `POST /auth/webauthn/register/begin` | session (else 403 `UNAUTHENTICATED`) | `{displayName?}` | principal missing → 404; existing credentials loaded (legacy skipped); **no `excludeCredentials`** (`api.go:128-133`); ceremony stored 10 min | `{stateId, options}` (`options` = go-webauthn `CredentialCreation` JSON) |
| `POST /auth/webauthn/register/complete` | session | `{stateId, name?, credential}` | blank name → 400 `NAME_REQUIRED` **before** consuming; consume nil/err → 400 `STATE_NOT_FOUND` `registration ceremony state not found or expired`; state's principal ≠ caller → 403 `FORBIDDEN` `registration ceremony belongs to a different principal`; principal missing → 404; parse → 400 `INVALID_CREDENTIAL` `<lib err>`; attestation → 400 `ATTESTATION_INVALID` `<lib err>`; `Register` op (`STATE_ID_REQUIRED`), event `platform:admin:passkey:registered` `{credentialId, userId, name?}`; notify `NewPasskey` | `{credentialId}` |
| `POST /auth/webauthn/authenticate/begin` | none | `{email*}` | blank → 400 `EMAIL_REQUIRED`; backoff on (email, rightmost XFF) → **429** huma `Too many failed attempts — try again later`; unknown/inactive principal **or** no usable credential → **decoy** `{stateId: random, options: decoy}` (nothing stored); else `BeginLogin`, ceremony stored | `{stateId, options}` |
| `POST /auth/webauthn/authenticate/complete` | none | `{stateId, credential}` | consume nil/err/no principal, principal missing/inactive, no creds → 403 `INVALID_CREDENTIALS` `Invalid credentials.`; parse failure or assertion failure → attempt `FAILURE` `Invalid passkey` (with UA) + same 403; counter persistence attempted (see §7.5 bug); `Provider` nil → 500 `WIRING`; mint → 500 `MINT_FAILED`; attempt `SUCCESS` | `Set-Cookie: fc_session=…; Path=/; HttpOnly; Secure?; SameSite=Lax; Expires; Max-Age=86400` + `{principalId, email (nullable), name, roles[]}` |
| `GET /auth/webauthn/credentials` | session | | legacy rows skipped with a warn | `[{id, name?, createdAt, lastUsedAt?}]` |
| `DELETE /auth/webauthn/credentials/{id}` | session | | id not among the **caller's** credentials → 404 `Credential_NOT_FOUND` (never 403 — no enumeration); `Revoke` op, event `platform:admin:passkey:revoked` | 204 |

Passkey login never consults 2FA (`docs/2fa-implementation-plan.md:28-33`)
and returns no `permissions` (the SPA loads `/auth/me`).

### 7.3 Decoy challenge [C-security]

`decoyChallenge(rpID)` (`api.go:472-491`): `{"publicKey":{"challenge":<32B
b64url>,"timeout":60000,"rpId":<real rp id>,"allowCredentials":[{"type":"public-key","id":<32B
b64url>}],"userVerification":"preferred"}}` — shape-identical to a real
non-discoverable challenge; completion then fails like a wrong passkey.

### 7.4 Shared attempt budget [C-behaviour]

Begin checks `loginbackoff.Check(email, ip)`; complete records `USER_LOGIN`
attempts keyed on the principal's **lower-cased** e-mail, with `ip` =
rightmost XFF and `user_agent` (`api.go:272-291`) — the same store the
password path uses (`loginattempt.md` §5). Failures are recorded **only**
for real principals after a real ceremony, so probing unknown e-mails never
trips the budget (`:223-228`).

### 7.5 Events, persistence — and a defect

Events (`webauthn/operations/events.go`): source `platform:admin`, subject
`platform.passkey.<id>`, group `platform:passkey:<id>`; types
`platform:admin:passkey:registered|authenticated|revoked`.

**Defect [I→C]:** `authenticateComplete` runs `AuthenticateCommand{StateID,
UpdatedCredential}` **without** `PersistedCredentialID`
(`api/api.go:339-342`); the op's `Validate` rejects with
`CREDENTIAL_ID_REQUIRED` (`operations/authenticate.go:33-36`) and the result
is discarded (`_, _ =`). Consequently the sign counter / `last_used_at` are
**never persisted** and `passkey:authenticated` is **never emitted**. Clone
detection across logins is therefore absent. **load-bearing or accident?**
(open question 13 — fix in the port, or keep parity?)

`Persist` is an upsert (`WebauthnCredentialUpsert`) of `id, principal_id,
credential_id, passkey_data, name, created_at, last_used_at`
(`repository.go:96-110`).

### 7.6 Legacy rows [C-storage]

`isLegacyPasskey` = JSON object with top-level `cred` and neither `id` nor
`publicKey` (`repository.go:153-162`; table in `legacy_passkey_test.go:5-44`).
A go-webauthn unmarshal of such a blob yields an **empty** credential id
without error (`:125-139`), so the repository turns it into
`ErrLegacyPasskey`: `FindByPrincipal` skips with
`slog.Warn("skipping legacy webauthn passkey")`, `FindByCredentialID` →
not-found, `FindByID` → error. Owner decision: no conversion; the user
re-registers (`:142-147`).

---

## 8. Password reset, invites, approvals

2026-09-14, `app-managed-invitations.md`: `POST /auth/password-setup/request`
(§3 there) mints the ordinary INVITE token for an account awaiting password
setup, and an INVITE confirm that finishes `ok` on a domain without 2FA also
sets the session cookie and answers `sessionEstablished:true` (§4 there — a
step 13 after §8.4's table).

### 8.1 Token minting (the `principalEmailer`) [C-behaviour]

`passwordreset/api/api.go:201-337`. Every mint first **deletes all tokens of
the subject** (`DeleteByPrincipalID`) — one live token per subject across
reset/invite/approval:

| Entry | Subject | Purpose / TTL | Flags | Link |
|---|---|---|---|---|
| `SendResetEmail(p, reset2FA)` (admin `send-password-reset`, approval) | `prn_` | reset / 15 min | `reset_2fa = reset2FA`, **`requires_factor = false` always** (`:231-232` — an admin reset never asks for TOTP; **load-bearing or accident?** open question 14) | `<base>/auth/reset-password?token=<raw>`; e-mail "Reset your password"; silently skipped when the principal has no e-mail (`:221-223`) |
| `SendInvite` / `SendInviteRedirect(p, redirectURI)` / `InviteLink` | `prn_` | invite / 72 h | `redirect_uri` | `<base>/auth/set-password?token=<raw>`; e-mail "Set your password" (or no e-mail for `InviteLink`) |
| `SendPortalReset(identityID, email, redirectURI)` | `ptu_` | reset / 15 min | `redirect_uri` | `/auth/reset-password?token=`; portal reset e-mail |
| `PortalInviteLink` / `SendPortalInvite(identityID, email, redirectURI)` | `ptu_` | invite / 72 h | `redirect_uri` | `/auth/set-password?token=`; "Join the portal" |
| `SendPortalSSOInvite(email, portalURL)` | — | no token | | "You've been invited" → the portal URL |

`<base>` = `cfg.JWTIssuer` (`server/wire_routes.go:81`). The SPA's
`/auth/reset-password` and `/auth/set-password` pages share one machinery
(`:257-260`).

### 8.2 `POST /auth/password-reset/request` [C]

`passwordreset/api/api.go:435-558`. Body `{email}`; undecodable → 400
`INVALID_BODY` `malformed request body`; **always** 200
`{"message":"If an account exists, a reset email has been sent."}` —
every internal error is logged as "suppressed". `tryIssueToken`:

1. blank e-mail → nothing.
2. `FindByEmail` (lower-cased) nil → warn with the **domain only**, nothing.
3. ineligible — not a USER, has an external identity, no e-mail → nothing.
4. `strong = confirmed TOTP`; `!strong && RequireStrongFactorForReset`
   (wired **false**, `server/wire_public.go:44-64`) → queue approval (§8.6)
   instead of a token.
5. delete the principal's tokens; mint reset (15 min) with
   `requires_factor = strong`; e-mail the link best-effort (Emailer nil ⇒
   token still created).

### 8.3 `GET /auth/password-reset/validate?token=` [C]

`:562-578`. Always 200 `{valid, reason, requiresFactor, portal?}`: lookup
error / no row → `{"valid":false,"reason":"not_found","requiresFactor":false}`;
expired → `reason:"expired"`; else `{"valid":true,"reason":null,"requiresFactor":<flag>,"portal":true?}`
(`reason` is emitted as JSON `null`, not omitted, `:419-428`). Does **not**
consume, does **not** reveal an exhausted `factor_attempts`.

### 8.4 `POST /auth/password-reset/confirm` [C]

`:582-679`, body `{token, password, factorCode}`:

| # | Condition | Outcome |
|---|---|---|
| 1 | bad JSON | 400 `INVALID_BODY` |
| 2 | lookup error | 500 `REPO` `token lookup failed` |
| 3 | no row | 400 `INVALID_TOKEN` `Invalid or expired reset token.` |
| 4 | expired | delete the subject's tokens; 400 `EXPIRED_TOKEN` `Reset token has expired.` |
| 5 | subject starts `ptu_` | portal confirm (§8.5) |
| 6 | `requires_factor`: MFA unwired | 400 `FACTOR_REQUIRED` `Two-factor verification is required.` |
| 7 | `factor_attempts ≥ 5` | burn the set; 400 `INVALID_TOKEN` |
| 8 | `VerifyTOTP` error | 500 `MFA` `factor verification failed` |
| 9 | wrong code | `factor_attempts+1` (atomic); reaches 5 → burn the set + 400 `INVALID_TOKEN`; else 400 `INVALID_FACTOR` `Invalid authenticator code.` — the token survives for a retry |
| 10 | `principalops.ResetPassword{ID, NewPassword}` as actor `"system"` | errors propagate: `ID_REQUIRED`, `PASSWORD_TOO_SHORT` (<8) 400, `Principal_NOT_FOUND` 404, `NOT_A_USER` 409, `PASSWORD_TOO_LONG`/`TOO_WEAK`/`TOO_COMMON`/`CONTAINS_IDENTITY` 400 (`principal/operations/reset_password.go:36-95`); event `platform:iam:user:password-reset-completed` |
| 11 | delete the subject's tokens (best-effort), log `password reset completed` | |
| 12 | `postResetTwoFactor` | 200 `{status, message, enrollToken?, allowedMethods?, redirectUri?}` |

`postResetTwoFactor` (`:701-765`): principal re-read failure → plain ok;
MFA wired: `reset_2fa` ⇒ `ResetAll` + notify `TwoFactorReset`; **always**
revoke trusted devices; revoke refresh tokens (`RevokeAllForPrincipal`);
notify `PasswordChanged`; then if MFA+MFATokens wired and the domain
`Requires2FA` and the user has **no** confirmed method → mint enroll token
(30 min) → `{"status":"enrollment_required","message":"Password set. Set up
two-factor authentication to finish.","enrollToken":…,"allowedMethods":[…]}`;
else `{"status":"ok","message":"Password reset successfully."}`.
`redirectUri` echoes the token's stored value (`:672-678`). Note a wrong
TOTP at step 9 also burns that TOTP step for the replay guard only on
success; a correct code consumes the step (§6.5).

### 8.5 Portal confirm [C]

`confirmPortalReset` (`:812-856`): `PortalIdentities` unwired → 400
`INVALID_TOKEN`; lookup error → 500 `REPO`; identity missing or not
`ACTIVE` → 400 `INVALID_TOKEN`; `passwordpolicy.Validate(password,
identity.Email, identity.Name)` → 400 `<policy code>` (**wrapped** as a
validation error — the bare violation once produced a 500, pinned by
`confirm_portal_pg_test.go:32-94`: `PASSWORD_CONTAINS_IDENTITY`,
`PASSWORD_TOO_SHORT`); hash → 500 `HASH`; `SetPasswordHash` → 500 `REPO`
(0 rows ⇒ error); delete the identity's tokens; notify
`PortalPasswordChanged`; 200 `{"status":"ok","message":"Password set
successfully.","redirectUri":…?,"portal":true}`. No 2FA gate, no refresh
revocation, no event.

### 8.6 Approval queue [C]

Queueing (`:532-558`): `Approvals` unwired or principal has no `client_id`
→ nothing (anchor users get no approval path,
`docs/auth-hardening-plan.md:25-27`); an unexpired `PENDING` request already
exists → nothing; else insert `New(principalID, clientID, 72 h)` (`reset_2fa
= true`), then notify every client-admin e-mail
(`FindClientAdminEmails`: active principals with role
`platform:client-admin` and that `client_id`, `principal/repository.go:95-117`)
with link `<base>/authentication/reset-approvals/<id>`.

Routes (lockfile; `resetapproval/api/api.go:40-45`):

| Route | Gate | Behaviour | Response |
|---|---|---|---|
| `GET /api/reset-approvals` | `CanWritePrincipals` (any of user create/update/delete) | anchors: all pending unexpired, oldest first; others: `client_id = ANY(ac.Clients)` (`nil` → `[]` → none) | `{requests:[{id, principalId, email, name, clientId?, expiresAt, createdAt}]}` (e-mail/name resolved per row; blank when the principal is gone) |
| `POST /api/reset-approvals/{id}/approve` | `RequireUserAdmin(ac, req.ClientID)` | 404 `ResetApprovalRequest_NOT_FOUND`; `Decide(APPROVED)` guarded `WHERE status='PENDING' AND expires_at > NOW()` → 0 rows ⇒ 400 `ALREADY_DECIDED` `request is no longer pending`; principal gone → 404; `SendResetEmail(p, req.Reset2FA)` best-effort | `{"message":"Reset approved — the user has been emailed a link"}` |
| `POST /api/reset-approvals/{id}/deny` | same | `Decide(DENIED)` | `{"message":"Reset request denied"}` |

### 8.7 Admin `send-password-reset` + invites [C]

`POST /api/principals/{id}/send-password-reset` (`principal/api/api.go:1301-1333`),
body optional `{reset2fa?}` (pointer so a body-less POST works): 404;
`RequireUserAdmin`; `blockNonClientTarget`; op `SendPasswordReset`
(`principal/operations/send_password_reset.go:38-75`): `ID_REQUIRED`;
emailer unwired → 500 `EMAILER_NOT_CONFIGURED`; 404; `NOT_USER` 400
`Password reset only applies to user accounts`; `OIDC_USER` 400 `Cannot send
password reset for OIDC-federated users — they manage credentials at their
IDP`; `NO_EMAIL` 400; send failure → 500 `EMAILER`; 200 `{"message":"Password
reset email sent"}`. Creating an internal user without a password sends the
invite; with a password sends `AccountCreated`; federated/OIDC users get
nothing (`principal/api/api.go:664-688`).

### 8.8 E-mail copy [C-ish]

Rendered through the branding theme (`branding.LoadTheme(...).RenderEmail`);
portal mails use brand `Portal`, platform colours, no logo, footer `This is
an automated message. Please do not reply to this email.`
(`passwordreset/api/api.go:90-199`):

| Sender | Subject | Heading / button / after-button |
|---|---|---|
| `SendResetLink` | `Reset your password` | "Reset your password" / `Reset password` / "This link expires in 15 minutes.", "If you didn't request this, you can safely ignore this email." |
| `SendInviteLink` | `Set your password` | "Welcome to <brand>" / `Set your password` / 2FA guidance line, "This link expires in 72 hours." |
| `SendPortalInviteLink` | `Join the portal` | "You've been invited to the portal" / `Join the portal` / "This link expires in 72 hours." |
| `SendPortalResetLink` | `Reset your password` | portal reset copy, 15 minutes |
| `SendPortalSSOInvite` | `You've been invited` | "…sign in with your organisation account — no password setup needed." / `Open the portal` |

---

## 9. E-mail transport [C-config]

`email/email.go`. `FromEnv()`: `FC_SMTP_HOST` else `SMTP_HOST` (the `FC_`
name always wins, `:143-150`, `email_test.go:37-47`); unset ⇒ `LogService`
that **logs the full body at WARN** (set-password links and PINs are
readable in dev, `:34-46`). Otherwise `SMTPService{port default 587,
username, password, from default noreply@flowcatalyst.local, secure}` where
`SECURE` ∈ {`true`,`1`,`yes`,`on`} (case-insensitive) ⇒ implicit TLS from
the first byte (`tls.Dial`, `ServerName = host`, then `AUTH` (PLAIN, only
when a username is set), `MAIL FROM`, `RCPT TO`, `DATA`, `QUIT`,
`:94-128`); else `smtp.SendMail` (opportunistic STARTTLS when advertised,
`:85-91`). Message = RFC 5322 with CRLF: `From`, `To`, `Subject`,
`MIME-Version: 1.0`, `Content-Type: text/html; charset=UTF-8`, blank line,
body (`:131-141`, `email_test.go:49-63`) — **no `Date`, `Message-ID`, no
RFC 2047 subject encoding** (**accident?** open question 15). One recipient
per message; `Send` ignores `ctx`.

---

## 10. Notification catalogue [C-copy]

`notify/notify.go`. Every send is best-effort (`nil` notifier / service /
blank recipient ⇒ no-op; failure ⇒ `slog.Warn("security notification not
delivered")`, `:48-55`). Brand name = live platform name, default
**`Flowcatalyst`** (`:37-44` — lower-case *c*, unlike `FlowCatalyst`
elsewhere; **accident?** open question 16). Footer
`<p style="color:#888;font-size:12px">If this wasn't you, contact your
administrator immediately.</p>` unless noted.

| Method | Subject | Trigger |
|---|---|---|
| `AccountCreated(to)` | `Your account has been created` (no footer) | internal user created **with** a password |
| `PasswordChanged(to)` | `Your password was changed` | change-password, reset confirm |
| `PortalPasswordChanged(to)` | `Your portal password was changed` (no brand) | portal confirm |
| `TwoFactorEnrolled(to, method)` | `Two-factor authentication enabled` (`authenticator app` / `email code`) | every enrol confirm |
| `TwoFactorMethodRemoved(to, method)` | `Two-factor method removed` | self-service delete |
| `TwoFactorReset(to)` | `Two-factor authentication was reset` | admin reset-2fa, `reset_2fa` token confirm |
| `RecoveryCodesRegenerated(to)` | `New recovery codes generated` | first set at enrolment, regenerate |
| `RecoveryCodeUsed(to)` | `A recovery code was used to sign in` | `/auth/2fa/verify` with `RECOVERY_CODE` |
| `NewPasskey(to)` | `A new passkey was registered` | register/complete |
| `NewTrustedDevice(to, label)` | `A new device was remembered` (label in grey when present) | remember-device |
| `ResetApprovalNeeded(to, link)` | `A password reset needs your approval` (no footer; `Review the request` link) | approval queued |

---

## 11. State machines

### 11.1 OIDC login state (`oauth_oidc_login_states`)

| From | Event | To | Notes |
|---|---|---|---|
| — | `/auth/oidc/login` or `/portal/auth/oidc/login` | LIVE (expires +10 min) | |
| LIVE | callback `Consume` (`expires_at > NOW()`) | CONSUMED (row gone) | before any verification; a later failure cannot be retried with the same state |
| LIVE | `expires_at` passes | EXPIRED (row present, unconsumable) | |
| EXPIRED | purger (1 min) | gone | |

### 11.2 Portal login flow (`portal_login_flows`)

| From | Event | To |
|---|---|---|
| — | `/portal/authorize` | LIVE (+15 min) |
| LIVE | check-domain / wrong password / password-less identity | LIVE (not consumed) |
| LIVE | correct password (`Consume`) or SSO start (`Consume`) | CONSUMED |
| LIVE | TTL | EXPIRED → purged |

### 11.3 WebAuthn ceremony (`oauth_oidc_payloads`, type `Webauthn*`)

`— → STORED (+10 min) → CONSUMED` on the first complete call (even one that
then fails attestation / assertion); `STORED → EXPIRED → purged`. A
registration complete with a blank `name` leaves the state STORED.

### 11.4 MFA method (`iam_user_mfa_methods`)

| From | Event | To |
|---|---|---|
| — | begin TOTP / begin e-mail | PENDING (`confirmed_at NULL`) |
| PENDING (TOTP) | begin TOTP again | PENDING (row replaced, new secret) |
| PENDING (e-mail) | begin e-mail again | PENDING (same row, new PIN) |
| PENDING | valid first code | CONFIRMED (`confirmed_at`; TOTP also `last_used_at`) |
| CONFIRMED | begin again | `ErrAlreadyEnrolled` |
| CONFIRMED (TOTP) | valid login code at a newer step | CONFIRMED (`last_used_at` advances) |
| any | `DELETE /auth/2fa/methods/{m}`, `ResetAll` | gone |

### 11.5 E-mail PIN (`iam_mfa_email_pins`)

`— → ISSUED (attempts 0, +10 min)`; wrong code → `attempts+1`; `attempts`
reaches 5 → deleted; correct → deleted; expired → deleted on next verify;
re-issue of the same purpose deletes the previous. (No purger — open question
17.)

### 11.6 Recovery code

`— → UNUSED → USED (used_at)` via guarded update; regenerate / reset deletes
the whole set.

### 11.7 Trusted device

`— → LIVE (+days) → (touch on verify) → gone` by revoke / revoke-all /
reset; expired rows stay until revoked (no purger).

### 11.8 Portal identity

| From | Event | To |
|---|---|---|
| — | `Ensure` (INVITE via API, JIT via SSO) | ACTIVE, no password |
| ACTIVE | invite/reset confirm | ACTIVE with password |
| ACTIVE | `deactivate` | DISABLED (password login 401, SSO `access_denied`, token redeem `invalid_grant`) |
| DISABLED | `activate` or re-`Ensure` | ACTIVE (password kept) |
| any | `DELETE` | gone |

### 11.9 Reset token (`iam_password_reset_tokens`)

| From | Event | To |
|---|---|---|
| — | any mint | LIVE (all other tokens of the subject deleted first) |
| LIVE (`requires_factor`) | wrong TOTP | LIVE, `factor_attempts+1`; 5th wrong → subject's set deleted |
| LIVE | confirm succeeds | subject's set deleted |
| LIVE | `expires_at` passes | EXPIRED (validate says `expired`; confirm deletes the set and says `EXPIRED_TOKEN`) |

### 11.10 Approval request (`iam_reset_approval_requests`)

`— → PENDING (+72 h)`; approve → `APPROVED` (+ reset e-mail with
`reset_2fa`); deny → `DENIED`; a second decision or a decision after
`expires_at` → `ALREADY_DECIDED`; `EXPIRED` is never written.

---

## 12. What these flows do to the principal aggregate

| Flow | Effect | Source |
|---|---|---|
| bridge callback, mapping path, unknown e-mail | `CreateUser` (scope + primary client from the mapping, provider `OIDC`, no password, no roles) — event `user:created` | `bridge/login_endpoint.go:640-675` |
| bridge callback, provider-direct, unknown e-mail | `CreatePortalUser` (inert principal) | `:681-700` |
| bridge callback, existing principal | `LowercaseEmail` self-heal (direct UPDATE of `email`, `email_domain`, `updated_at`; version bump) | `:551-556`; `principal/repository.go:718-739` |
| bridge callback, mapping path | `SyncIdpRoles` — rewrites `IDP_SYNC` assignments, event `roles-assigned` | `:562-570, 796-808` |
| `/auth/login` | `UpdatePasswordHash` on rehash, `LowercaseEmail` | `login/endpoint.go:453-465` |
| reset confirm | `ResetPassword` — hash set, event `password-reset-completed` | `passwordreset/api/api.go:656-661` |
| change-password | `UpdatePasswordHash` (direct, no event) | `login/change_password.go:78-86` |
| approval queue | `FindClientAdminEmails` read | `principal/repository.go:95-117` |

---

## 13. Timing / sizing constants

| # | Constant | Value | Where | Load-bearing or accident? — evidence |
|---|---|---|---|---|
| 1 | OIDC login-state TTL | 10 min | `bridge/login_state.go:60` | load-bearing (IdP round-trip budget; audited as adequate `docs/oidc-security-audit.md:58`) |
| 2 | state / nonce entropy | 32 bytes → 43 chars | `bridge/login_endpoint.go:337-338` | load-bearing (CSPRNG, ≥128 bit) |
| 3 | PKCE verifier | 64 bytes → 86 chars (RFC 7636 allows 43–128) | `:339` | load-bearing (length within spec) |
| 4 | PKCE method | `S256` only | `:365` | load-bearing |
| 5 | OIDC scopes | `openid profile email` | `bridge/oidc.go:179` | load-bearing (`name`/`email` claims) |
| 6 | bridge cache key | `issuer|clientId` | `oidc.go:140` | load-bearing (one discovery per IdP) — invalidation absent = accident? (Q1) |
| 7 | session cookie TTL | 24 h | `login/endpoint.go:49`; `webauthn/api/api.go:359-362` | load-bearing (SPA expectation) |
| 8 | fc_session attributes | `Path=/ HttpOnly SameSite=Lax Secure=!AuthAllowTestHeaders` | `server/wire_routes.go:231-239` | load-bearing |
| 9 | per-IP governor on `/auth/oidc/*`+`/portal/*` | 60/min, burst 30 | `ratelimit/governor.go:69-74` | tunable (env) |
| 10 | governor prune / idle | 5 min / 10 min | `governor.go:91-92` | [I] accident |
| 11 | default post-login target | `/dashboard` | `bridge/login_endpoint.go:581` | load-bearing (SPA route) |
| 12 | portal flow TTL | 15 min | `portalauth/flow.go:43` | accident? (why ≠ 10 min OIDC state — Q18) |
| 13 | portal flow id / auth code entropy | 32 B / 48 B | `flow.go:47`; `endpoints.go:353` | load-bearing (opaque) |
| 14 | portal login rate limit | 10 / 15 min per (client, email) | `ratelimit/ratelimit.go:74` | tunable |
| 15 | portal login page path | `/portal/login` | `endpoints.go:134-137` | load-bearing (SPA route) |
| 16 | pending (mfa) token TTL | 10 min | `login/twofactor.go:27` | load-bearing-ish (matches PIN TTL) |
| 17 | enroll token TTL | 30 min | `:28`; `passwordreset/api/api.go:404` | judgement call |
| 18 | mfatoken secret prefix | `fc-mfa-token-v1|` | `mfatoken/mfatoken.go:49` | load-bearing across instances/versions |
| 19 | TOTP period / skew / digits / algo / secret | 30 s / ±1 / 6 / SHA-1 / 20 B | `mfa/crypto.go:21-27` | load-bearing (authenticator-app compatibility; existing enrolments) |
| 20 | TOTP QR size | 240 px | `mfa/service.go:162` | accident |
| 21 | TOTP issuer default | `FlowCatalyst` | `service.go:50` | load-bearing for existing enrolments (label only) |
| 22 | e-mail PIN length / TTL / attempts | 6 / 10 min / 5 | `service.go:51-53` | load-bearing (plan `docs/2fa-implementation-plan.md:150`) |
| 23 | recovery-code count / format / alphabet | 10 / `XXXXX-XXXXX` / 30-symbol | `service.go:54`; `crypto.go:31,122-140` | load-bearing (printed codes must keep verifying) |
| 24 | trusted-device default TTL | 30 d | `service.go:55`; `twofa/policy.go:72-77` | load-bearing (EDM default) |
| 25 | trusted-device token | 32 B b64url, SHA-256 stored | `crypto.go:163-171` | load-bearing (existing cookies) |
| 26 | trusted-device cookie | `__Host-fc_td` / `fc_td`, `SameSite=Strict` | `login/twofactor.go:34-37, 441-450` | load-bearing |
| 27 | UA label cap | 250 chars | `:552-554` | accident (column is 255) |
| 28 | WebAuthn ceremony TTL | 10 min | `webauthn/ceremony_repository.go:34` | load-bearing (browser prompt budget) |
| 29 | ceremony id prefixes | `WebauthnRegistration:` / `WebauthnAuthentication:` | `:32-33` | load-bearing (shared table, TS parity) |
| 30 | stateId entropy | 16 B → 22 chars | `webauthn/api/api.go:459-463` | fine |
| 31 | decoy timeout / sizes | 60000 ms, 32 B challenge, 32 B fake id | `:472-491` | load-bearing (must look real) |
| 32 | RP defaults | `localhost` / `http://localhost:8080` | `server/envcfg.go:284`; `wire_services.go:165` | dev defaults |
| 33 | reset token TTL | 15 min | `passwordreset/api/api.go:41` | load-bearing (e-mail copy says 15 min) |
| 34 | invite token TTL | 72 h | `:45` | load-bearing (e-mail copy says 72 hours) |
| 35 | reset raw token | 32 B → 43 chars; SHA-256 hex | `:786-794`; `api_test.go` | load-bearing (stored hashes) |
| 36 | factor attempts ceiling | 5 | `:52` | load-bearing (matches PIN cap) |
| 37 | approval TTL | 72 h | `:553-558` | judgement (plan says "e.g. 72h") |
| 38 | `RequireStrongFactorForReset` | false | `:382`, wiring | product decision (Q19) |
| 39 | min password length (strict) | 8 (policy) | `passwordpolicy.go:28` | load-bearing |
| 40 | reset/invite link paths | `/auth/reset-password?token=`, `/auth/set-password?token=`, `/authentication/reset-approvals/<id>` | `:236,260,545` | load-bearing (SPA routes) |
| 41 | SMTP defaults | port 587, from `noreply@flowcatalyst.local` | `email/email.go:65-68` | dev defaults |
| 42 | purger cadence | 1 min | `server/subsystems.go:491` | accident |
| 43 | notify default brand | `Flowcatalyst` | `notify/notify.go:43` | accident (Q16) |
| 44 | login-backoff knobs | see `loginattempt.md` §5 | `loginbackoff.go:40-49` | tunable |
| 45 | `X-Forwarded-For` hop | rightmost | `ratelimit/ratelimit.go:219-240` | load-bearing (spoof resistance) |

---

## 14. Topology, concurrency, HA, shutdown

Invariants (as behaviour):

- Every short-lived secret row (login state, portal flow, ceremony, reset
  token set) is **single-use**; consumption is atomic (`DELETE … RETURNING`
  with the TTL predicate) and happens **before** the expensive/verifying
  work so a failed attempt cannot be replayed.
- Security side effects (notifications, audit rows, role sync, counters,
  refresh revocation, trusted-device revocation) never fail the primary
  action; they are logged.
- Anti-enumeration responses are byte-identical for present/absent subjects
  (reset request, portal reset, passkey begin decoy, portal login 401).
- Timing equalisation on the password paths (`EqualizeTiming`).
- Hashes at rest: SHA-256 hex for PINs / recovery codes / device tokens /
  reset tokens (high-entropy inputs), Argon2id for passwords, AES-GCM for
  TOTP secrets.

What Go does because of Go ([I], not contract): a process-wide mutex around
the bridge cache; per-instance in-memory governor (per-IP limits are
per-node, not cluster-wide — unlike the portal-login limiter which uses the
shared store); `nil`-service no-ops everywhere (Java: sealed `Disabled`
variants); error sentinels mapped in handlers; `ctx` threading.

HA: no leadership anywhere. All instances share the DB; `mfatoken` HMAC is
derived from the shared RSA key so tokens roam; the purger
(`server/subsystems.go:485-522`) runs on **every** instance every minute
and is idempotent — it sweeps `oauth_oidc_payloads`, `oauth_oidc_login_states`,
Webauthn ceremonies and `portal_login_flows` only; `iam_mfa_email_pins`,
`iam_mfa_trusted_devices`, `iam_password_reset_tokens` and
`iam_reset_approval_requests` have purge methods that **nobody calls**
(`mfa/repository.go:227-234, 312-319`, `passwordreset/passwordreset.go:166-174`)
— rows expire by predicate and accumulate (Q17).

Shutdown: the purger exits on context cancel; handlers are request-scoped;
nothing to drain.

---

## 15. Configuration

| Env | Used by | Default |
|---|---|---|
| `FLOWCATALYST_APP_KEY` | TOTP secret encryption; OIDC client-secret decryption (`server/wire_routes.go:214`, `wire_services.go:184`) | unset ⇒ TOTP `TOTP_UNAVAILABLE` 503, confidential OIDC IdPs fail to resolve |
| `JWTIssuer` (cfg) | bridge `ExternalBaseURL`; reset/invite/approval link base | |
| `AuthAllowTestHeaders` (cfg) | `CookieSecure = !value` for every cookie here | |
| `FC_OIDC_RATE_PER_MIN` / `FC_OIDC_BURST` | bridge + portal governor | 60 / 30 |
| `FC_RL_PORTAL_LOGIN_PER_15MIN` | portal login / reset limiter | 10 |
| `FC_WEBAUTHN_RP_ID`, `FC_WEBAUTHN_ORIGINS` (fallback `FC_WEBAUTHN_RP_ORIGIN`) | passkeys | `localhost`, `http://localhost:8080` |
| `FC_SMTP_HOST|SMTP_HOST`, `…_PORT`, `…_USERNAME`, `…_PASSWORD`, `…_FROM`, `…_SECURE` | e-mail | log-only, 587, —, —, `noreply@flowcatalyst.local`, false |
| `FC_LOGIN_BACKOFF_*`, `FC_LOGIN_GLOBAL_*` | shared backoff (`loginattempt.md`) | 3 / 2 s / 300 s / 3600 s / 100 / 900 s |
| platform name (platform config row) | TOTP issuer (live), notification brand (live), RP display name (startup) | |
| not env: `RequireStrongFactorForReset=false`, `ApprovalTTL=72h`, `PendingTokenTTL=10m`, `EnrollTokenTTL=30m`, `LoginPagePath=/portal/login` | | |

---

## 16. Observability

Routes (wire summary): `GET /auth/oidc/login`, `GET /auth/oidc/callback`,
`GET /auth/oidc/session/end`, `POST|GET /auth/check-domain`,
`GET /portal/authorize`, `POST /portal/auth/check-domain`,
`POST /portal/auth/login`, `POST /portal/auth/password-reset`,
`GET /portal/auth/oidc/login`, the 14 `/auth/2fa/*`, `POST
/auth/change-password`, `POST /auth/change-password/send-email-code`, 6
`/auth/webauthn/*`, `POST /auth/password-reset/request`, `GET
/auth/password-reset/validate`, `POST /auth/password-reset/confirm`, 3
`/api/reset-approvals*`, 5 `/api/portal-users*`, `POST
/api/principals/{id}/send-password-reset`, `POST
/api/principals/{id}/reset-2fa`. Only the lockfile ones (webauthn,
portal-users, reset-approvals) are OpenAPI-described; the chi routes are
contract-by-SPA.

Metrics: none. Logs (slog) at every best-effort failure: role-sync rejection
(`REJECTED unauthorized IDP role …` WARN with `principalId`, `idpRole`),
missing allow-list role, e-mail self-heal failure, 2FA eval failure (ERROR),
send failures, suppressed reset errors (domain only), `password reset
completed` INFO, `portal password set` INFO, legacy passkey skip,
notification non-delivery, rate-limit backend fail-open.

Records: `login_attempts` (`USER_LOGIN`, reasons `Invalid credentials`, `SSO
required`, `Invalid 2FA code`, `Invalid passkey`); `aud_logs` direct rows
(§6.4) and UoW rows for every operation; `msg_events` for the events in
§4.7/§4.8/§5.7/§7.5/§8.4.

---

## 17. Edge cases mined from tests

| Behaviour | Test |
|---|---|
| A just-used TOTP code (the enrolment confirm) is rejected at login | `mfa/service_pg_test.go:106-109` |
| Previous-step TOTP validates; 10 steps back does not; steps advance | `mfa/crypto_test.go:41-66` |
| Wrong PIN refused; correct PIN once; consumed PIN refused | `service_pg_test.go:125-134` |
| 10 recovery codes; single-use; remaining decrements | `:137-149` |
| Trusted device verifies, revoked device does not | `:152-164` |
| `ResetAll` leaves no method and no recovery code | `:167-175` |
| PIN is exactly `n` digits, zero-padded | `crypto_test.go:68-83` |
| Recovery format / alphabet / normalisation equivalence | `:85-110` |
| mfatoken round-trip; wrong purpose, expired, tampered, other key rejected; **never valid as a session token** | `mfatoken/mfatoken_test.go:21-80` |
| Raw reset token 43 chars b64url; hash 64 lower-hex; `hash("")` vector | `passwordreset/api/api_test.go:8-49` |
| `factor_attempts` atomic & persistent; burn deletes the set; consume round-trip with the widened columns; second consume nil | `passwordreset/passwordreset_pg_test.go:23-81` |
| Portal set-password: name / e-mail-local-part in password → 400 `PASSWORD_CONTAINS_IDENTITY`; `ab` → `PASSWORD_TOO_SHORT`; success body has `"portal":true` and the hash is stored | `passwordreset/api/confirm_portal_pg_test.go:32-94` |
| Portal journey: 307 to `/portal/login?flow=`; check-domain `PASSWORD`; wrong password 401 keeps the flow; right password → code whose subject is `ptu_`; replay → 400 | `portalauth/portal_flow_pg_test.go:40-128` |
| Non-portal OAuth client refused at `/portal/authorize` (`not a portal client`) | `:132-154` |
| DISABLED identity → uniform 401 | `:158-203` |
| Forgot-password: active identity mailed with redirect = portal origin; unknown e-mail same response, no send | `:232-284` |
| SSO-owned domain: check-domain `SSO`, password login `SSO_REQUIRED`, reset silently skipped | `:290-371` |
| Ensure on SSO domain → `ssoManaged`, "open the portal" invite, never set-password; `returnInviteLink` → portal origin; unowned domain → set-password invite | `portalidentity/api/ensure_sso_pg_test.go:55-138` |
| Legacy passkey blob detection table | `webauthn/legacy_passkey_test.go:5-44` |
| `CreatePortalUser` inert shape (CLIENT scope, nil client, no roles/apps, `AllApplications=false`, no hash, provider OIDC, e-mail lower/trim); duplicate → `EMAIL_EXISTS`; bad e-mails rejected | `principal/operations/create_portal_pg_test.go:24-88` |
| SMTP: log service when unset; `FC_` prefix wins; MIME headers | `email/email_test.go:9-63` |
| Backoff: delay curve, pair backoff, elapsed allows, global ceiling, no-IP skips pair check | `internal/platform/auth/loginbackoff/loginbackoff_test.go:20-119` |

No Go test covers the bridge callback, the 2FA HTTP handlers, or the
WebAuthn handlers end-to-end (`docs/2fa-implementation-plan.md:223-224`);
the conformance suite must.

---

## 18. Defects and oddities observed (for the owner, not silently "fixed")

1. Passkey sign counter / `last_used_at` never persisted; `passkey:authenticated`
   never emitted (§7.5).
2. `LoginStateRepo.FindByState` scans 18 targets from 17 columns (dead code,
   §3.1).
3. `IDP_NOT_EXTERNAL` branch unreachable (§5.6).
4. `FindByState`/`Delete`/`IsExpired` dead after the atomic consume
   (`docs/oidc-security-audit.md:90`).
5. Four expiring tables never purged (§14).
6. SessionWriter mint failure is a plain-text 500 (§4.6).
7. `rememberAllowed` ignores `Internal` (§6.1).
8. Admin-sent reset tokens never require TOTP (§8.1).
9. `/auth/2fa/verify` ignores the domain's allowed-method list (§6.4).
10. `DELETE /auth/2fa/trusted-devices/{id}` and `DELETE /auth/2fa/methods/{m}`
    report success for nothing deleted (§6.4).
11. `EXPIRED` approval status never written; `note` never written (§3.7).
12. Two spellings of the system actor (`""` vs `"system"`) (§2).
13. Passkey events live under source `platform:admin` while portal events
    use `platform:portal` and principal events `platform:iam` (§7.5).
14. GET `/auth/check-domain` fabricates `authorizationUrl = issuer + "/authorize"`
    (§4.10).
15. E-mail MIME lacks `Date`/`Message-ID`; subject unencoded (§9).

---

## 19. Open questions for the owner (yes / no)

1. May the Java invalidate / refresh the cached OIDC client when the IdP row
   changes (secret rotation, issuer change) instead of requiring a restart?
2. Keep the empty-string `email_domain_mapping_id` as the provider-direct
   marker in the stored row (schema-compat), modelling it as a sealed mode
   only in memory?
3. Keep leaking the library's verification error text in
   `OIDC_VERIFY` messages?
4. Keep "single-tenant provider-direct IdP with no mapped domains accepts
   any account at that IdP"?
5. Keep the plain-text 500 on session-mint failure, or switch to the
   `ErrorModel` envelope?
6. Keep failing JIT with `CLIENT_REQUIRED` when a CLIENT/PARTNER mapping has
   no `primaryClientId` (vs. refusing at mapping-creation time)?
7. Keep `GET /auth/oidc/login?provider_id=` **without** a portal flow (the
   Phase-1 inert-principal JIT path) now that the portal plane is separate?
8. When every `allowedRoleIds` entry is dangling, keep "reject all roles"
   (vs. treat as unrestricted)?
9. Keep the GET `/auth/check-domain` legacy shape (and its guessed
   `authorizationUrl`) at all?
10. Keep consuming the portal flow at SSO **start** (no retry after an IdP
    failure), or consume it at the callback sink like the password path?
11. Align `rememberAllowed` in the login decision with `RememberEnabled()`
    (require internal domain)?
12. Should `/auth/2fa/verify` enforce the domain's allowed-method list?
13. Fix the passkey counter persistence (and emit `passkey:authenticated`)
    in the port?
14. Should admin-triggered reset tokens set `requires_factor` when the user
    has TOTP?
15. Add `Date` / `Message-ID` headers and RFC 2047 subject encoding to
    outgoing mail?
16. Change the notification default brand to `FlowCatalyst`?
17. Purge expired PINs / trusted devices / reset tokens / approvals on the
    housekeeping loop?
18. Keep the 15-minute portal flow TTL (vs. 10 minutes like the OIDC state)?
19. Keep `RequireStrongFactorForReset = false` (no-TOTP users get an e-mailed
    link; the approval queue stays dormant)?
20. Keep `/auth/2fa/*` token routes on the public router and self-service
    routes behind the auth middleware exactly as mounted?
21. Keep the Go-default time serialisation (RFC 3339 nanos) and `principalId`
    on `GET /auth/2fa/trusted-devices` items, or align to the platform's
    `httpcompat.Time` shape?
22. Unify the system actor spelling (`""` vs `"system"`) in `aud_logs`?
23. Keep the portal-plane 2FA deferral (no second factor for portal password
    users)?
24. Keep `authenticate/begin` on huma's 429 error shape (vs. the
    `TOO_MANY_REQUESTS` envelope the password path uses)?
25. Keep passkey events under source `platform:admin`?
