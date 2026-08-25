# Auth core — behavioural spec

Semantic extraction (CONVENTIONS §8 step 1) of the **auth core** of
`/Users/andrewgraaff/Developer/flowcatalyst-go`: JWT mint/validate, the
session cookie, the password-login surface, the hand-rolled OAuth 2.0 / OIDC
provider, the grant store, the OAuth-client aggregate, rate limiting, the
service-account token mint, the authenticator middleware, the dev test-header
bypass and the purger. **Not code.** The 2FA / passkey / OIDC-bridge
mechanics are the second spec (`auth-identity`); this document covers them
only where the login path branches on them.

Every fact cites `file:line` in the Go repo. `[C]` = wire/storage contract
that must be reproduced byte-for-byte; `[I]` = internal mechanics the Java
may restructure as long as the stated behaviour holds. Open questions are
numbered **Q1…Qn** inline and collected in §19 as yes/no decisions. Until an
owner ruling lands, the Go behaviour is the spec.

### Path aliases used in citations

| Alias | File (under `flowcatalyst-go/`) |
|---|---|
| AS / AST / BCT | `internal/platform/auth/authservice/authservice.go` / `authservice_test.go` / `build_clients_test.go` |
| ST / STT | `internal/platform/auth/sessiontoken/sessiontoken.go` / `_test.go` |
| PR / FRT | `internal/platform/auth/provider/provider.go` / `filter_roles_test.go` |
| LE / CP / SH / TF / TSS | `internal/platform/auth/login/endpoint.go` / `change_password.go` / `session_history.go` / `twofactor.go` / `twofactor_selfservice.go` |
| LB / LBT | `internal/platform/auth/loginbackoff/loginbackoff.go` / `_test.go` |
| TK / AZ / DS / IR / PT / RU / TA / UI | `internal/platform/auth/oauthapi/token.go` / `authorize.go` / `discovery.go` / `introspect_revoke.go` / `portal_token.go` / `redirecturi.go` / `token_apiaccess.go` / `userinfo.go` |
| AZT / TKT / TST / TIT / DST / RUT / AAT / DGT / PTT | oauthapi tests: `authorize_test.go` / `token_test.go` / `token_scope_test.go` / `token_idtoken_scope_test.go` / `discovery_test.go` / `redirecturi_test.go` / `api_access_token_pg_test.go` / `developer_grant_pg_test.go` / `portal_token_pg_test.go` |
| GS / PA / RO / ROT | `internal/platform/auth/grantstore/grantstore.go` / `pending_auth.go` / `rotate.go` / `rotate_pg_test.go` |
| EN / RP / OC / SX / AA / AD / OPT | `internal/platform/auth/entity.go` / `repository.go` / `operations/oauth_client.go` / `operations/stash.go` / `api/api.go` / `api/dto.go` / `operations/ops_pg_test.go` |
| RL / GV / PG / RD / RLT / GVT | `internal/platform/shared/ratelimit/ratelimit.go` / `governor.go` / `postgres.go` / `redis.go` / `ratelimit_test.go` / `governor_test.go` |
| MW / MWT | `internal/platform/shared/middleware/middleware.go` / `_test.go` |
| SA | `internal/platform/shared/auth/auth.go` |
| TP | `internal/platform/auth/twofa/policy.go` |
| SAA / SAD | `internal/platform/serviceaccount/api/api.go` / `dto.go` |
| WS / WR / WP / SS / EC / SK / PL | `internal/server/wire_services.go` / `wire_routes.go` / `wire_public.go` / `subsystems.go` / `envcfg.go` / `signing_key.go` / `internal/platform/auth/payload/payload.go` |
| M7 / M29 / M30 / M41 / M42 | `internal/migrate/sql/007_oauth_tables.sql` / `029_…post_logout…` / `030_rate_limit_events.sql` / `041_portal_identities.sql` / `042_oauth_client_api_access.sql` |
| LOCK | `api/openapi.lock.json` |
| ADR / AUD | `docs/adr/0001-session-token-vs-oauth.md` / `docs/oidc-security-audit.md` |

---

## 1. Purpose & boundaries

The auth core is four cooperating things that share **one RSA key pair**
(WS:60-88, PR:226-229, ADR:60-61, ADR:100-113):

1. **Token minting + validation** (`authservice`, `sessiontoken`,
   `provider`): every JWT the platform issues — access tokens, OIDC ID
   tokens, session cookies — and the one validator the middleware uses.
2. **The SPA session surface** (`login`): `/auth/check-domain`, `/auth/login`,
   `/auth/logout`, `/auth/me`, `/auth/refresh`, `/auth/change-password`,
   `/auth/login-history` (LE:1-12, LE:119-148).
3. **The OAuth 2.0 / OIDC provider** (`oauthapi` + `grantstore`):
   `/oauth/authorize`, `/oauth/token`, `/oauth/introspect`, `/oauth/revoke`,
   `/oauth/userinfo`, `/.well-known/openid-configuration`,
   `/.well-known/jwks.json` (TK:1-10, ADR:100-107).
4. **Admin configuration + protection**: the OAuth-client aggregate
   (`/api/oauth-clients`), the service-account admin mint, the
   rate-limit store/governors, the login backoff, the authenticator
   middleware + dev bypass, the purger.

Out of scope here (second spec): `/auth/2fa/*`, `/auth/webauthn/*`,
`/auth/oidc/*` (the bridge, AUD:3-9), `/portal/*`, password reset, the
email-domain-mapping / identity-provider aggregates. The login path's
*branches* into 2FA and SSO are in scope (§7.1).

Already ported in Java (cross-reference, §17): `Authenticator`,
`JwtVerifier`, `TokenClaims`, `ClaimsResolver`, `SigningKeys`, `AuthContext`,
`Auth`, `Checks`, `Permission`, `Scope`, `PrincipalType`, `PasswordHash`
(`server/src/main/java/io/flowcatalyst/platform/shared/auth/*`).

Design rationale on record: ADR-0001 — session cookies are owned by a tiny
standalone package, not the OAuth stack; fosite was then removed entirely and
`/oauth/*` hand-rolled (ADR:47-61, ADR:100-113).

---

## 2. Token classes (the vocabulary everything else uses)

| Class | Minted by | `token_use` | Carries authority? | Lifetime | Validated by | Cite |
|---|---|---|---|---|---|---|
| **API access token** | `client_credentials` (service account or developer), `/auth/refresh`, admin SA mint, `apiAccess` interactive clients | `"api"` | yes: tier, scope, clients, roles, applications, all_applications | 3600 s | middleware (`sessiontoken.Validate` + aud) and `authservice.ValidateToken` | AS:34-53, AS:385-399, TA:27-57, LE:317, SAA:275-345 |
| **Identity access token** | `authorization_code` + its `refresh_token` grant (default), portal code redemption | `"identity"` | **no** — empty arrays, no scope, `all_applications=false`; middleware **rejects** it as a bearer | 3600 s | `authservice.ValidateToken` (userinfo/introspect) | AS:42-46, AS:401-411, AS:454-456, MW:19-24, MW:187-189, PT:49 |
| **OIDC ID token** | `authorization_code` w/ `openid`, refresh w/ `openid` | absent | identity + roles (possibly narrowed) + apps/clients | 300 s | relying party via JWKS; **never** by the platform (aud ≠ platform → middleware rejects) | AS:130-163, AS:476-517, ST:125-139, STT:116-160 |
| **Session cookie** (`fc_session`) | `/auth/login` (after 2FA), OIDC bridge SessionWriter, passkey login | absent | **no** — only `iss/sub/iat/nbf/exp/tier/email/all_applications`; authority resolved from DB per request | 24 h | middleware `sessiontoken.Validate`; no `aud` → passes | PR:272-300, ST:76-120, LE:49, LE:498-512, MW:157-177, WR:225-238 |
| **Legacy access token without `token_use`** | older builds | absent | treated permissively (accepted) | — | middleware | AS:124-126, MW:184-186 |

The string values `"api"`/`"identity"` are a wire contract shared between
`authservice` and `sessiontoken` (AS:48-49, ST:67-70) [C].

---

## 3. Message / data model

### 3.1 Access-token JWT payload (`AccessTokenClaims`) [C]

Header: `{"alg":"RS256"|"HS256","typ":"JWT","kid":<kid>}` — `kid` only for
RS256 (AS:521-531). Payload, in struct order (AS:79-128, AS:423-458):

| Claim | Type | Present | Value | Cite |
|---|---|---|---|---|
| `iss` | string | always | `Config.Issuer` (= `FC_JWT_ISSUER`, WS:82) | AS:429 |
| `sub` | string | always | principal id (`prn_…`) — or `ptu_…` for portal identity tokens | AS:430, PT:41-49 |
| `exp` | number | always | `iat + AccessTokenExpirySecs` (3600) | AS:424-431 |
| `iat` | number | always | `now` UTC | AS:432 |
| `nbf` | number | always | `= iat` | AS:433 |
| `jti` | string | always | untyped 13-char TSID | AS:434 |
| `aud` | **bare string** (never array) | always | `Config.Audience` (= issuer, WS:83) | AS:74-83, AS:436, AST:110-112 |
| `type` | string | always | `"USER"` / `"SERVICE"` | AS:86, AS:437 |
| `tier` | string | always | `"ANCHOR"`/`"PARTNER"`/`"CLIENT"` — renamed from historical `scope` (deliberate divergence) | AS:88-92, AS:438 |
| `scope` | string | **omitted when empty** | space-delimited granted permission codes | AS:94-98, AS:449, AST:121-123 |
| `email` | string | omitted for SERVICE or empty email | user email | AS:100-101, AS:593-599, AST:177-191 |
| `name` | string | always | `principal.Name` | AS:103-104, AS:440 |
| `clients` | string[] | always (possibly `[]`) | anchor → `["*"]`; partner → `"id:identifier"` per assigned client; client → home client `"id:identifier"` (bare `id` when identifier map lacks it) | AS:106-108, AS:609-636, BCT:15-68 |
| `roles` | string[] | always | role names | AS:110-111, AS:601-607 |
| `applications` | string[] | always | `AccessibleApplicationIDs` (explicit bindings, **not** derived from role prefixes) | AS:113-116, AS:638-649 |
| `all_applications` | bool | always | `principal.AllApplications` (false on identity tokens) | AS:118-121, AS:453 |
| `token_use` | string | omitted on legacy tokens; always on new mints | `"api"` / `"identity"` | AS:123-127, AS:448, AS:455 |
| `permissions` | — | **never emitted** | — | AST:124-126 |

Identity tokens: `scope` absent, `clients/roles/applications = []`,
`all_applications=false`, `token_use="identity"`, identity fields intact
(AS:441-456, AST:403-445).

### 3.2 OIDC ID-token JWT payload (`IDTokenClaims`) [C]

Header as above. Payload (AS:134-163, AS:476-517):

| Claim | Present | Value | Cite |
|---|---|---|---|
| `iss`, `sub`, `exp`, `iat` | always | exp = iat + 300 | AS:494-499, AST:315-337 |
| `nbf`, `jti` | **never** | — | AS:130-133, AST:278-283 |
| `aud` | always, bare string | the RP `client_id` | AS:137-138, AS:500 |
| `auth_time`, `updated_at` | always | both `= iat` (not the real auth time) — **Q1** | AS:501, AS:506 |
| `nonce` | when supplied | echo of authorize `nonce` | AS:502 |
| `name` | always | | AS:503 |
| `email`, `email_verified` | when email non-empty | `email_verified` is **always `true`** when present — **Q2** | AS:484-489, AS:504-505 |
| `acr`, `amr` | never emitted (fields exist, never set) | | AS:146-147, PR:70-72 |
| `azp` | always | `= aud` | AS:491, AS:507 |
| `type`, `tier` | always | | AS:508-509 |
| `client_id` | when principal has a home client | home client id | AS:155-156, AS:510 |
| `roles` | always (`[]` not `null`) | full role names, or narrowed app-local **short names** for an app-scoped client (§7.3.5) | AS:157-158, AS:477-479, AS:511, AST:289-313 |
| `applications`, `all_applications`, `clients` | always | as in 3.1 | AS:159-162, AS:512-514 |

### 3.3 Session-cookie JWT payload (`sessiontoken.Mint`) [C]

Always RS256, no `kid` header (ST:114-115 — `jwt.NewWithClaims` only; the
signing path does not stamp `kid`). Payload (ST:84-112, PR:296-299):

| Claim | Present | Value |
|---|---|---|
| `iss` | always | provider issuer |
| `sub` | always (Mint refuses empty) | principal id (ST:80-82, STT:162-168) |
| `iat`, `nbf` | always | now |
| `exp` | when ttl ≠ 0 | now + ttl (24 h from login) |
| `tier` | always | `c.Scope` — **empty string** on real cookies (provider passes only Subject+Email, PR:296-299) |
| `email` | when non-empty | |
| `clients`, `roles`, `applications` | only when non-empty — **never on real cookies** | |
| `all_applications` | always | `false` on real cookies |
| `scope` | only when permissions non-empty — never on real cookies | space-joined |
| `aud` | **never** | — this is what lets the middleware's audience guard pass cookies (ST:128-139) |

Reason the cookie is identity-only: staleness + the ~4 KB browser cookie cap
(PR:285-295). The token **cannot** be revoked; logout only clears the cookie
(LE:565-576, SH:11-12).

### 3.4 What the validator reads (`sessiontoken.Claims`) — ported

`Subject, Scope(=tier), Email, Clients, Roles, Applications, AllApplications,
Permissions(=Fields(scope)), TokenUse, IssuedAt` (ST:45-65, ST:183-199).
Rules: RS256 only (ST:153-160); `exp/nbf/iat` standard; `iss` must equal
(ST:172-176); if an `aud` claim is present (string or array) it must contain
the platform audience, **no `aud` passes** (ST:177-181, ST:216-238); `sub`
required (ST:196-198). Java `JwtVerifier` implements these (see §17).

### 3.5 `AuthContext` projection — ported

Fields `PrincipalID, Email, Scope, Clients, Roles, Applications,
AllApplications, Permissions` (SA:139-160). Cookie path: everything
re-resolved from DB per request via `provider.ResolveClaims` (MW:163-177);
deactivated/deleted principal → error → treated as logged-out (PR:83-92,
MW:80-93). Bearer path: claims as-is; `Permissions` = `scope` claim, or
flattened from `roles` when the token has no scope (MW:197-202). Java
`Authenticator`/`ClaimsResolver` implement this (§17).

### 3.6 OAuth client aggregate (`auth.OAuthClient`) [C]

Table `oauth_clients` (M7:51-63) + five junctions. Entity (EN:44-110):

| Field | JSON (response, AD:133-161) | Column | Type/limit | Default / rule | Cite |
|---|---|---|---|---|---|
| `ID` | `id` | `id` | VARCHAR(17) PK, `oac_`+13 | generated | EN:95, M7:52 |
| `ClientID` | `clientId` | `client_id` | VARCHAR(100) UNIQUE | backend-generated `oac_…` TSID when omitted; caller-supplied kept (internal command only — the HTTP request has **no** `clientId` field) | OC:56-59, AD:15-19, OPT:84-86 |
| `ClientName` | `clientName` | `client_name` | VARCHAR(255) | required, trimmed on update | OC:45-47, OC:148-150 |
| `ClientType` | `clientType` | `client_type` | VARCHAR(20) | `PUBLIC`/`CONFIDENTIAL`; lenient parse → `PUBLIC` | EN:28-42 |
| `SecretRef` | — (never on wire) | `client_secret_ref` | VARCHAR(500) | `"encrypted:"` + AES-GCM ciphertext of the plaintext secret; nil for PUBLIC | EN:51-55, OC:371-394, RP:47-50 |
| `RedirectURIs` | `redirectUris` | `oauth_client_redirect_uris.redirect_uri` VARCHAR(500) | junction | `[]` default | M7:71-77 |
| `PostLogoutRedirectURIs` | `postLogoutRedirectUris` | `oauth_client_post_logout_redirect_uris` TEXT | junction (raw pgx) | | M29:14-25, RP:150-162 |
| `GrantTypes` | `grantTypes` | `oauth_client_grant_types.grant_type` VARCHAR(50) | junction | **empty = unrestricted** (legacy) | M7:94-100, TK:767-784 |
| `Scopes` | `defaultScopes` | `default_scopes` VARCHAR(500) | comma-joined string; NULL when empty | | RP:109-119, RP:347-353 |
| `AllowedOrigins` | `allowedOrigins` | `oauth_client_allowed_origins.allowed_origin` VARCHAR(200) | junction (raw pgx) | CORS allowlist (consumed by the CORS filter, see `docs/spec/cors.md`) | EN:63-65, M7:82-89 |
| `ApplicationIDs` | `applicationIds` + `applications[{id,name}]` | `oauth_client_application_ids.application_id` VARCHAR(17) | junction | `applications` resolved at read time; deleted app → name = id | M7:105-111, AA:54-94 |
| `PKCERequired` | `pkceRequired` | `pkce_required` | BOOL DEFAULT TRUE | | EN:69-71, OPT:28 |
| `Active` | `active` | `active` | BOOL DEFAULT TRUE | | EN:72 |
| `PrincipalID` | `serviceAccountPrincipalId` (omitempty) | `service_account_principal_id` | VARCHAR(17) | the SERVICE principal client_credentials mints for | EN:73, RP:121, AD:153-154 |
| `PortalClientID` | `portalClientId` (omitempty) | `portal_client_id` | VARCHAR(17) | non-empty ⇒ portal plane; refused at `/oauth/authorize` | EN:74-77, M41:48-49, AZ:83-86 |
| `APIAccess` | `apiAccess` | `api_access` | BOOL DEFAULT FALSE | mutually exclusive with PortalClientID | EN:78-83, M42:9-10, OC:197-206 |
| `CreatedAt/UpdatedAt` | `createdAt/updatedAt` | TIMESTAMPTZ | `updated_at` stamped `now()` on every persist | RP:108, RP:124 |

Persist = upsert row + clear-and-reinsert all five junctions inside the UoW
tx (RP:106-188). Delete clears junctions explicitly then deletes the row
(RP:190-213). Read hydrates all junctions; nil slices normalised to `[]`
(RP:281-302).

### 3.7 Grant-store rows — `oauth_oidc_payloads` [C] (storage compat)

One table (M7:141-155): `id VARCHAR(128) PK, type VARCHAR(64), payload JSONB,
grant_id, user_code, uid VARCHAR(128), expires_at, consumed_at TIMESTAMPTZ,
created_at`. Rows are infrastructure (raw pgx, no UoW) (GS:9-11).

**AuthorizationCode** — `id = "AuthorizationCode:" + code`, `type =
"AuthorizationCode"` (GS:32, GS:110). Payload camelCase (GS:86-99):
`accountId, clientId, redirectUri, scope, codeChallenge, codeChallengeMethod,
nonce, state, contextClientId` (nullable strings emitted as `null`, never
omitted), `kind:"AuthorizationCode"`, `iat`, `exp` (unix). Columns:
`grant_id/user_code/uid = NULL`, `expires_at = created+10 min`, `consumed_at
= NULL` (GS:132-137). `Used ⇔ consumed_at IS NOT NULL` (GS:214). Insert is
an upsert on id (GS:136).

**RefreshToken** — `id = "RefreshToken:" + <untyped TSID>`, `type =
"RefreshToken"` (GS:33, GS:244, GS:313). Payload (GS:286-302): `accountId,
clientId (nullable), tokenHash, scope` (space-joined), `accessibleClients`
(**always an array, never null** — GS:338-341), `revoked, revokedAt
(RFC3339Nano string|null), tokenFamily, replacedBy, lastUsedAt, createdFromIp,
userAgent, iat, exp, kind:"RefreshToken"`. Columns: `grant_id = tokenFamily`
(so a family can be revoked by column), `expires_at`, `consumed_at` set on
revoke (GS:342-347, GS:402-416). Lookups are by `payload->>'tokenHash'`
(GS:354-379). Only the SHA-256 hash of the raw token is stored (GS:220-221).

**PendingAuth** — `id = "PendingAuth:" + state`, `type = "PendingAuth"`,
payload `clientId, redirectUri, scope, codeChallenge, codeChallengeMethod,
nonce, createdAt (RFC3339Nano)`, `expires_at = now+10 min`, upsert on id
(PA:14-80). Consumed by `DELETE … RETURNING` (PA:85-90) — but **nothing in
the codebase consumes it**; it is written for wire compatibility only
(AZ:201-204, AUD:84) — **Q3**.

The older `payload` package documents different type strings
(`access_token`, `refresh_token`, …, PL:33-39) — those rows are **not**
written by this code; only its `PurgeExpired` is used (PL:167-171, SS:486).

### 3.8 Rate-limit store rows [I]

Postgres: `iam_rate_limit_events(id BIGSERIAL, bucket VARCHAR(64), key TEXT,
occurred_at TIMESTAMPTZ)` + index `(bucket,key,occurred_at DESC)` (M30:23-37,
PG:32-39). Redis: key `fc:rl:<bucket>:<key>:<floor(now/window)>`, INCR +
EXPIRE(window) on first hit (RD:46-70).

---

## 4. Key material, signing, validation [C unless marked]

| Rule | Cite |
|---|---|
| Algorithm: RS256 when an RSA private key is configured; else HS256 with a non-empty `SecretKey`; **empty secret or bad RSA key = refuse to start** (fail closed; a deliberate hardening over older warn-and-fallback) | AS:296-334 |
| Public key derived from the private key when not supplied | AS:309-316, AS:698-710 |
| Private key PEM: PKCS#1 or PKCS#8; public: PKIX or PKCS#1 | AS:679-696, AS:712-727 |
| `kid = base64url_nopad(sha256(publicKeyPEM bytes)[:16])` → 22 chars; deterministic; computed over the **exact PEM text** | AS:663-666, AST:250-259 |
| HS256 service has no kid, no JWKS keys | AS:283-294, AS:355-356, AST:210-212 |
| Rotation: one **previous** RSA public key, validation-only, also listed in JWKS | AS:336-353, AS:370-383, AST:215-248 |
| Validation order: try current key, then previous keys; algorithm pinned to the service's alg; `iss` checked by library; `aud` checked manually (bare string ≠ array); expiry → `ErrTokenExpired` immediately (no further keys tried); any other failure → `ErrInvalidToken` with last error | AS:533-565 |
| Same RSA key signs cookies (`sessiontoken`), access/ID tokens (`authservice`) and the 2FA pending tokens (HMAC derived) | WS:60-88, WS:185, PR:226-229 |
| Key loading order [I]: `FC_JWT_SIGNING_KEY_PATH` file → `FLOWCATALYST_JWT_PRIVATE_KEY` → `FC_JWT_SIGNING_KEY_PEM` (both normalised: literal `\n`, quotes, whole-PEM base64) → **ephemeral 2048-bit key + warning** | SK:16-70 |
| Previous key: `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY`, normalised, dropped unless it contains `-----BEGIN` | EC:257-266 |
| Issuer = audience = `FC_JWT_ISSUER` (fallbacks `FC_EXTERNAL_BASE_URL`, `EXTERNAL_BASE_URL`, default `http://localhost:8080`) | EC:146, WS:62-66, WS:82-83 |
| JWKS: `{"keys":[{kty:"RSA",use:"sig",kid,alg:"RS256",n,e}]}` — current first, then previous; `n`/`e` base64url no-pad big-endian | DS:74-104, AS:668-677, DST:80-102 |

Java `SigningKeys` already implements loading/normalisation/kid (§17).

---

## 5. Wire contracts — the three error envelopes

| Shape | Where | Body | Headers | Cite |
|---|---|---|---|---|
| **Platform envelope** | `/auth/*`, `/api/*`, rate-limit middleware | `{"code":…, "message":…, "details"?:…}` — BUT the 429 helper writes `{"error":"TOO_MANY_REQUESTS","message":…}` (key `error`, not `code`) | `Retry-After` on 429 | httperror.go:55-78, RL:177-187, RLT:116-128 |
| **`/auth` 401** | login surface | `{"code":"UNAUTHENTICATED","message":…}` | `WWW-Authenticate: Cookie realm="fc_session"` | LE:676-684 |
| **`/auth` backoff 429** | `/auth/login` (+2FA verify) | `{"code":"TOO_MANY_REQUESTS","message":"too many failed login attempts; try again later"}` | `Retry-After: <secs>` | LE:662-670 |
| **RFC 6749 envelope** | `/oauth/*` | `{"error":…, "error_description"?:…}` | `Cache-Control: no-store`, `Pragma: no-cache` on **every** OAuth error and token success | TK:890-939 |
| **OAuth 429 (client bucket at /oauth/token)** | | `{"error":"rate_limit_exceeded","error_description":…}` | `Retry-After ≥ 1` | TK:909-919 |
| **Middleware 401** | bad explicit Bearer anywhere behind the Authenticator | `{"error":"invalid_token","error_description":<err text>}` | `WWW-Authenticate: Bearer error="invalid_token"` | MW:234-248 |

Status mapping for use-case errors: validation→400, authorization→403,
not-found→404, conflict/business-rule→409, else 500 (httperror.go:29-52).
Note the `/auth/login` SSO rejection is `usecase.Authorization` → **403**
(LE:427-429).

---

## 6. Wire contracts — endpoint tables

### 6.1 Session surface `/auth/*` [C] (not in LOCK — chi routes; `grep` of LOCK for `/auth/login|/oauth/` = 0 hits)

Mounting: check-domain, login, logout, 2fa/*, refresh are on the **public**
router outside the Authenticator (LE:119-133, WP:27-32); me, change-password,
change-password/send-email-code, login-history, 2fa self-service are inside
the Authenticator group (LE:138-148, WR:83-92).

| # | Method path | Auth | Request | Success | Errors | Cite |
|---|---|---|---|---|---|---|
| A1 | `POST /auth/check-domain` | none | `{"email"}` | 200 `{"authMethod":"internal"\|"external","loginUrl"?,"idpIssuer"?}`; `loginUrl = "/auth/oidc/login?domain=" + encodeURI(domain)` (custom encoder keeps `@-_.~`, `%XX` else) | 400 `INVALID_JSON`; 400 `EMAIL_REQUIRED`; malformed/unknown domain → 200 internal (deliberately no leak) | LE:152-206, LE:686-741 |
| A2 | `GET /auth/check-domain?email=` | none | query | 200 `{"domain","authMethod":"INTERNAL"\|"OIDC","providerId"?,"authorizationUrl"?}`; `providerId` set for **any** mapped IdP type, `authorizationUrl = issuer.trimRight('/')+"/authorize"` whenever issuer set — legacy shape | never errors | LE:208-245 |
| A3 | `POST /auth/login` | none | `{"email","password","rememberMe"}` (`rememberMe` parsed, **unused**) | 200 `loginResponse` (§6.1a) + `Set-Cookie fc_session`; or 200 2FA challenge (§7.1) | see §7.1 table | LE:333-487 |
| A4 | `POST /auth/logout` | none (accepts stale cookie) | — | 204, `Set-Cookie fc_session=; Max-Age=-1` same attrs | never | LE:565-576 |
| A5 | `GET /auth/me` | session/bearer | — | 200 `loginResponse` **with `"status":""`** (no omitempty) and no `recoveryCodes`; principal re-loaded from DB | 401 `UNAUTHENTICATED "Not authenticated"` (no ctx / missing / inactive); 500 on repo error | LE:578-621, LE:343-361 |
| A6 | `POST /auth/refresh` | none (public router) — only mounted when RefreshTokens+Auth wired | `{"refreshToken"}` | 200 `{"accessToken","tokenType":"Bearer","expiresIn":3600,"refreshToken"}` (camelCase; **always** an API token with full authority, no scope narrowing) | 400 `INVALID_JSON`; 401 `"Invalid or expired refresh token"` (empty, unknown, rotated-out, expired, revoked, principal missing); 401 `"Token was not issued to this client"` (token bound to an OAuth client → must use /oauth/token); 401 `"Account is not active"`; 500 | LE:247-329 |
| A7 | `POST /auth/change-password` | session | `{"currentPassword","newPassword","code"}` | 200 `{"message":"Your password has been changed."}` | 401 UNAUTHENTICATED; 400 `INVALID_JSON "malformed request body"`; 400 `SSO_MANAGED`; 400 `NO_PASSWORD`; 401 `INVALID_CURRENT_PASSWORD`; 400 `<passwordpolicy code>`; 500 `MFA_STATUS_FAILED`; 400 `MFA_REQUIRED {code,message,methods[]}`; 400 `INVALID_CODE`; 500 `HASH_FAILED`; 500 `UPDATE_FAILED` | CP:19-108, TF:558-566 |
| A8 | `POST /auth/change-password/send-email-code` | session | — | 200 `{"message":"A code has been sent to your email."}` | 401; 400 `NO_MFA`; 500 `MFA_STATUS_FAILED`; 400 `NO_EMAIL_2FA`; 400 `NO_EMAIL`; 500 `SEND_FAILED` | CP:135-166 |
| A9 | `GET /auth/login-history` | session | — | 200 `{"attempts":[{attemptType,outcome,failureReason?,ipAddress?,userAgent?,attemptedAt}]}` — last **20** rows by identifier (= lower-cased email), `[]` when no repo / no email | 401; 500 `HISTORY_FAILED` | SH:13-58 |

**6.1a `loginResponse`** (LE:343-361): `status` ("ok" on login),
`principalId, name, email, roles[] (names), permissions[], clientId (null
when none), recoveryCodes[] (omitempty; enroll-and-complete only), ssoManaged`.
`permissions` = flattened permission set **plus literal `"*"`** appended when
the set contains `platform:*:*:*` (LE:363-380). If claims resolution fails
after a successful login the user is still logged in with `permissions: []`
(LE:514-520). `ssoManaged` = principal has an external identity OR its email
domain maps to an OIDC IdP (LE:538-561).

**Cookie attributes** (LE:503-512, WR:230-238): name `fc_session`, Path `/`,
HttpOnly, `Secure = !FC_AUTH_ALLOW_TEST_HEADERS` (WS:199), SameSite=Lax,
`Expires = now+24h`, `Max-Age = 86400`. The bridge writer sets the same minus
`Expires` (WR:230-238).

### 6.2 OAuth / OIDC provider `/oauth/*`, `/.well-known/*` [C]

Mounting: `/oauth/authorize` public router wrapped in
`IPLimitMiddleware(oauth_authorize_ip)` (WP:66-69); `/oauth/token` **inside
the Authenticator group** wrapped in `GovernorMiddleware(oauthTokenIPGov)` →
`IPLimitMiddleware(oauth_token_ip)` (WR:190-196); introspect, revoke,
userinfo, discovery, jwks also inside the Authenticator group, unwrapped
(WR:197-200). Consequence: an explicit bad `Authorization: Bearer` on any of
these is 401'd by the middleware **before** the handler (MW:79-92) — **Q4**.

| # | Method path | Client auth | Request | Success | Errors (status `error` – description) | Cite |
|---|---|---|---|---|---|---|
| O1 | `GET /oauth/authorize` | none (session cookie or Bearer read by handler; cookie wins) | query: `response_type, client_id, redirect_uri, scope, state, nonce, code_challenge, code_challenge_method, provider, prompt, max_age` | 307 → `redirect_uri ? or & code=<86-char base64url>&state=<state>`; or 307 → `/auth/login?oauth=true&response_type=code&client_id&redirect_uri&state[&scope][&code_challenge][&code_challenge_method][&nonce]`; or 307 → `/auth/oidc/login?provider_id&oauth_client_id&oauth_redirect_uri&oauth_state[&oauth_scope][&oauth_code_challenge][&oauth_code_challenge_method][&oauth_nonce]` | **direct**: 400 `invalid_request` "`state` parameter is required for CSRF protection"; 429 platform envelope (client bucket); 500 `server_error` "Internal error"; 400 `unauthorized_client` "Unknown client" / "Client is not active" / "Portal clients must use /portal/authorize"; 400 `invalid_request` "Invalid redirect_uri". **307 to redirect_uri with `error&error_description&state`**: `unsupported_response_type` "Only 'code' response type is supported"; `unauthorized_client` "Client is not permitted to use the authorization_code grant"; `invalid_request` "PKCE code_challenge is required" / "Only the S256 code_challenge_method is supported"; `invalid_scope` "Invalid scope(s): a, b"; `login_required` "User is not authenticated" (prompt=none); `server_error` "Failed to create authorization code" / "Internal error" | AZ:29-238, AZ:266-274 |
| O2 | `POST /oauth/token` | Basic or body (`client_id`,`client_secret`) | form: `grant_type, code, redirect_uri, client_id, client_secret, code_verifier, refresh_token, scope` | 200 `{"access_token","token_type":"Bearer","expires_in":3600,"refresh_token"?,"id_token"?,"scope"?}` + no-store headers | see §6.2a | TK:120-229 |
| O3 | `POST /oauth/introspect` | Bearer (any valid platform access token) **or** client creds | form `token`, `client_id`, `client_secret` | 200 `{"active":true,"sub","tier","email"?,"name","type","iss","token_type":"Bearer","scope"?,"client_id"?:clients[0],"exp","iat"}` or 200 `{"active":false}` | 400 `invalid_request` "Malformed form body"; 401 `invalid_token` "Token is invalid or expired" (bad bearer); client-auth errors as O2 | IR:23-104 |
| O4 | `POST /oauth/revoke` | as O3 | form `token` (`token_type_hint` ignored) | 200 empty body; best-effort revoke of the refresh token whose hash matches | as O3 | IR:106-125 |
| O5 | `GET`/`POST /oauth/userinfo` | Bearer | — | 200 `{"sub","email"?,"name","tier","scope"(always, "" when none),"type","client_id"?,"clients":[],"roles":[],"applications":[]}`; `client_id` = first `clients` entry with `:identifier` stripped, omitted for `*` | 401 `invalid_request` "Missing Authorization header" / "Invalid Authorization header format"; 401 `invalid_token` "Token is invalid or expired" | UI:12-98 |
| O6 | `GET /.well-known/openid-configuration` | none | — | 200 document (§6.2b) | — | DS:41-72 |
| O7 | `GET /.well-known/jwks.json` | none | — | 200 `{"keys":[…]}` (§4) | — | DS:88-104 |

Note O3/O4 responses are plain `application/json` **without** no-store
(IR:129-136); O5 likewise.

**6.2a `/oauth/token` ordered checks and errors** (TK:161-229):

1. Form parse fail → 400 `invalid_request` "Malformed form body".
2. If body `client_id` non-empty: in-memory per-client governor → 429
   `rate_limit_exceeded` "this client_id has exceeded its token endpoint rate
   limit"; then distributed bucket `oauth_token_client` → same 429
   (TK:173-184). (Basic-auth-only requests skip both — **Q5**.)
3. For `authorization_code` / `refresh_token` (and any unknown grant):
   `authenticateClient` (TK:233-275): Basic header wins over body; no creds →
   401 `invalid_client` "Missing client credentials"; lookup error → 500
   `server_error`; unknown → 401 "Unknown client"; inactive → 401 "Client is
   not active"; PUBLIC client presenting a secret → 401 "Public clients must
   not provide a client_secret"; CONFIDENTIAL without secret → 401 "Client
   secret required for confidential clients"; wrong secret (decrypt +
   constant-time compare; no encryption service ⇒ always wrong) → 401
   "Invalid client credentials". Basic creds are URL-decoded per RFC 6749
   §2.3.1 (TK:292-322; scheme case-insensitive).
4. Grant allow-list: non-empty `GrantTypes` not containing the grant → 400
   `unauthorized_client` "Client is not permitted to use the '<g>' grant
   type" (TK:201-205).
5. Body `client_id` present and ≠ authenticated client → 400
   `invalid_request` "client_id does not match the authenticated client"
   (TK:212-216).
6. Dispatch; unknown grant → 400 `unsupported_grant_type` "Grant type '<g>'
   is not supported".

**client_credentials** (TK:326-402, TK:414-448, TK:457-488): missing
`client_id` → 400 `invalid_request` "Missing client_id"; missing
`client_secret` → 400 "Missing client_secret" (Basic auth is **not** read
here — body only — **Q6**); lookup error → 500; **no OAuth client and
`client_id` starts with `prn_`** → developer branch; else 401
`invalid_client` "Invalid client credentials"; inactive → same 401; PUBLIC →
401 `unauthorized_client` "Public clients cannot use client_credentials
grant"; grant not allowed → 401 `unauthorized_client` "Client is not
permitted to use the client_credentials grant type" + failed attempt
recorded; no secret ref → 401 "Invalid client credentials"; wrong secret →
401 + attempt `"Invalid client secret"`; no principal → 500 "Client not
properly configured"; principal missing → 500 same; inactive principal → 401
`invalid_client` "Service account is not active"; scope explicit but
intersection empty → 400 `invalid_scope` "Requested scope exceeds the service
account's granted permissions" + attempt `"requested scope exceeds granted
permissions"`; success → record SUCCESS attempt (identifier = client_id,
principal id), response with `scope` (space-joined granted; omitted when
empty), **no refresh token, no id_token**.

**Developer branch** (TK:404-448): principal must exist, be active, be USER,
hold role `platform:developer` (re-checked live), have a
`DevClientSecretRef`; secret verified like a client secret; failure →
401 `invalid_client` "Invalid client credentials" in every case (only the
wrong-secret case records a `DEVELOPER_TOKEN` failure `"Invalid developer
client secret"`); success → `invalid_scope` message tail "your granted
permissions"; attempt type `DEVELOPER_TOKEN`. Tests DGT:115-239.

**authorization_code** (TK:492-612): missing code → 400 `invalid_request`
"Missing 'code' parameter"; `FindAndConsume` (atomic single-use, GS:144-156)
nil → 400 `invalid_grant` "Invalid or expired authorization code"; expired
(belt-and-braces) → "Authorization code has expired"; code bound to a
different **authenticated** client → "Client ID mismatch"; `redirect_uri` ≠
stored → "Redirect URI mismatch"; PKCE when a challenge was stored
(TK:717-752): missing verifier → "Missing code_verifier"; length ∉ [43,128]
→ "code_verifier must be 43-128 characters"; non-unreserved char → "contains
invalid characters"; method nil/"" ⇒ S256, "S256" ⇒ base64url(sha256), else
plain; constant-time mismatch → "Invalid code_verifier"; `ptu_` subject →
portal branch (PT:23-76: no portal repo → 400 `invalid_grant` "Portal
subjects are not supported"; not found/suspended → "Portal identity not found
or suspended"; identity token + id_token with `roles:[]`, **never a refresh
token**); principal missing → 400 `invalid_grant` "Principal not found";
access token via `mintInteractiveAccessToken` (§7.3.4); `id_token` iff scope
has `openid`; `refresh_token` iff scope has `offline_access` (new family
rooted at its own id, scopes = Fields(scope), bound to the code's client);
response `scope` = the code's stored scope verbatim (nullable).

**refresh_token** (TK:616-711): missing → 400 `invalid_request` "Missing
refresh_token parameter"; `Rotate` with binding hook: stored token bound to a
client and requesting client ≠ → 401 `invalid_grant` "Token was not issued to
this client" (nothing rotated); store error → 500; nil → 401 `invalid_grant`
"Invalid or expired refresh token"; principal missing → 401 "Principal not
found"; inactive → 401 "Account is not active"; access token via
`mintInteractiveAccessToken` against the **current** principal and the bound
client (re-fetched if not authenticated this call); `id_token` iff stored
scopes contain `openid` **and** a client is bound (non-fatal on mint error);
response `refresh_token` = new raw, `scope` = stored scopes joined (omitted
when empty). Note: 401 for `invalid_grant` here vs RFC's 400 — **Q7**.

**6.2b Discovery document** (DS:20-71): `issuer = BaseURL (= FC_JWT_ISSUER)`,
`authorization_endpoint /oauth/authorize`, `token_endpoint /oauth/token`,
`userinfo_endpoint /oauth/userinfo`, `end_session_endpoint
/auth/oidc/session/end`, `introspection_endpoint`, `revocation_endpoint`,
`jwks_uri /.well-known/jwks.json`, `response_types_supported ["code"]`,
`subject_types_supported ["public"]`, `id_token_signing_alg_values_supported
["RS256"]` (advertised even on HS256 — **Q8**), `scopes_supported
["openid","profile","email","offline_access"]`,
`token_endpoint_auth_methods_supported ["client_secret_basic",
"client_secret_post"]`, `grant_types_supported ["authorization_code",
"refresh_token","client_credentials"]`, `claims_supported [sub iss aud exp iat
auth_time nonce name email email_verified acr amr azp type scope client_id
roles applications clients]` (advertises `acr`/`amr` which are never emitted,
and `scope` which is tier-renamed), `code_challenge_methods_supported
["S256"]`, `request_parameter_supported false`,
`request_uri_parameter_supported false`.

### 6.3 OAuth-client admin API `/api/oauth-clients` [C] (LOCK:14621-15010)

All **anchor-only** via `RequireAnchor` (AA:145-151, SA:303-311): no ctx →
403 `UNAUTHENTICATED` "authentication required"; non-anchor → 403
`ANCHOR_REQUIRED` "anchor scope required". Operations are `Authorize:
Public` — the gate lives in the handler (OC:36-40).

| operationId | Method path | Request body | Success | Errors | Cite |
|---|---|---|---|---|---|
| `listOAuthClients` | `GET /api/oauth-clients` | — | 200 `{"clients":[OAuthClientResponse]}` (no total) | 500 | AA:155-172, AD:210-215 |
| `createOAuthClient` | `POST /api/oauth-clients` | `CreateOAuthClientRequest` (required `clientName`,`clientType`; `redirectUris[] (format uri)`, `grantTypes[]`, `defaultScopes` **string** (space-split) or legacy `scopes[]`, `pkceRequired?`, `postLogoutRedirectUris[]`, `allowedOrigins[]`, `applicationIds[]`, `principalId?`, `portalClientId?`, `apiAccess?`; additionalProperties **true**) | 201 `{"client":OAuthClientResponse,"clientSecret"?}` — plaintext secret only for CONFIDENTIAL, only once | 400 `CLIENT_NAME_REQUIRED`; 409 `CLIENT_ID_EXISTS`; 400 `PORTAL_API_ACCESS_CONFLICT`; 500 `SECRET` (no `FLOWCATALYST_APP_KEY`) | AD:20-74, OC:20-107, LOCK:2475-2551 |
| `getOAuthClient` | `GET /api/oauth-clients/{id}` | — | 200 `OAuthClientResponse` | 404 | AA:174-190 |
| `getOAuthClientByClientID` | `GET /api/oauth-clients/by-client-id/{clientId}` | — | 200 | 404 | AA:192-214 |
| `updateOAuthClient` | `PUT /api/oauth-clients/{id}` | `UpdateOAuthClientRequest` (all optional: `clientName`, `redirectUris`, `postLogoutRedirectUris`, `grantTypes`, `defaultScopes` **array** here, `scopes`, `allowedOrigins`, `applicationIds`, `pkceRequired`, `portalClientId` (`""` clears), `apiAccess`; additionalProperties true) — nil slice = keep, empty slice = clear | 204 | 400 `ID_REQUIRED`; 400 `CLIENT_NAME_REQUIRED` "clientName cannot be empty"; 404; 400 `PORTAL_API_ACCESS_CONFLICT` | AD:76-119, OC:111-195 |
| `activateOAuthClient` | `POST …/{id}/activate` | — | 200 `{"success":true,"message":"OAuth client activated"}` | 404 | AA:264-274 |
| `deactivateOAuthClient` | `POST …/{id}/deactivate` | — | 200 `{"success":true,"message":"OAuth client deactivated"}` | 404 | AA:276-286 |
| `rotateOAuthClientSecret` | `POST …/{id}/rotate-secret` | — | 200 `{"clientId":<public client_id>,"clientSecret"}` | 404; 409 `NOT_CONFIDENTIAL` "Only CONFIDENTIAL clients have rotatable secrets"; 500 `SECRET` | AA:288-312, OC:327-362 |
| `regenerateOAuthClientSecret` | `POST …/{id}/regenerate-secret` | SDK alias of rotate | same | same | AA:118-121 |
| `deleteOAuthClient` | `DELETE /api/oauth-clients/{id}` | — | 204 | 404 | AA:314-324, OC:289-315 |

`OAuthClientResponse` required keys (LOCK:4549-4640): `id, clientId,
clientName, clientType, redirectUris, postLogoutRedirectUris, allowedOrigins,
grantTypes, defaultScopes, pkceRequired, applicationIds, applications,
active, apiAccess, createdAt, updatedAt`; optional
`serviceAccountPrincipalId`, `portalClientId`; additionalProperties false.
`defaultScopes` is a **string** on create and an **array** on update and
response (AD:28, AD:88, AD:143) — **Q9**. Secret plaintext travels through a
process-local stash popped once by the handler after commit; entries older
than 2 min are discarded (SX:8-66, OPT:477-510, OPT:666-705). Events
`OAuthClientCreated/Updated/Activated/Deactivated/Deleted/SecretRotated`
(OC:98-104 etc.; shapes in `operations/events.go`, not re-listed).

### 6.4 Service-account admin mint [C] (LOCK:19235-19300)

`POST /api/service-accounts/{id}/token` (`mintServiceAccountToken`,
SAA:78-79): anchor-only; mints exactly the client_credentials API token for
the account's linked principal with scope = full flattened ceiling (no
requested-scope narrowing); nothing persisted; audit row
`SERVICE_ACCOUNT / TOKEN_MINTED_BY_ADMIN` best-effort (SAA:275-345). Response
`{"accessToken","tokenType":"Bearer","expiresIn":3600,"scope"?}` (SAD:235-245,
LOCK:6437-6468 required `accessToken,tokenType,expiresIn`). Errors: 403
anchor; 500 `TOKEN` "token minting is not wired"; 404 `ServiceAccount`; 400
`SERVICE_ACCOUNT_INACTIVE` (account or its principal); 500 `PRINCIPAL` "no
linked principal"; 500 `SCOPE`; 500 `TOKEN`.

---

## 7. Behaviour (ordered decision procedures)

### 7.1 `POST /auth/login` (LE:382-487) — ordered

| Step | Rule | Outcome | Cite |
|---|---|---|---|
| 1 | JSON decode fails | 400 `INVALID_JSON` | LE:383-387 |
| 2 | `email = lower(trim(email))`; empty email **or** empty password | 401 "Invalid credentials" (constant shape; **no attempt recorded**) | LE:392-397 |
| 3 | `ip = ClientIP(r)` (rightmost XFF hop, else RemoteAddr host) | | LE:398, RL:208-228 |
| 4 | Backoff `Check(email, ip)` if attempts repo wired; backoff **errors are ignored** (fail open) | 429 + `Retry-After` | LE:400-407 |
| 5 | SSO enforcement: email has a domain AND mapping exists AND its IdP is OIDC | record FAILURE `"SSO required"`; **403** `SSO_REQUIRED` "This email domain signs in through its identity provider; password login is disabled" | LE:415-432 |
| 6 | `FindByEmail` (repo lower-cases); error / nil / `!Active` / no UserIdentity / no PasswordHash | `EqualizeTiming(password)` then record FAILURE `"Invalid credentials"`; 401 | LE:434-443 |
| 7 | `passwordhash.Verify` fails | record FAILURE; 401 | LE:444-447 |
| 8 | `NeedsRehash` → re-hash + persist, best-effort (warn on failure) | | LE:449-459 |
| 9 | `LowercaseEmail` self-heal, best-effort | | LE:461-465 |
| 10 | If MFA wired: `maybeChallenge2FA` (TF:76-145): external identity → skip; domain policy (TP:33-77: unmapped ⇒ internal, no policy; mapped to OIDC IdP ⇒ not internal); `domainRequires = mapping.Require2FA && internal`; `usable = confirmed methods ∩ allowed (when required)`; usable non-empty → (remember-device cookie valid → skip) else 200 `{"status":"mfa_required","mfaToken","methods","rememberDeviceAllowed"}`; none usable and domain requires → 200 `{"status":"enrollment_required","enrollToken","allowedMethods"}`; evaluation error → **500 `MFA_EVAL_FAILED`** (fail closed) | | LE:467-484 |
| 11 | `completeLogin`: mint cookie (failure → **400 `MINT_FAILED`** — **Q10**), `Set-Cookie`, record SUCCESS (identifier=email, principal id, ip), resolve claims (failure → empty permissions), 200 `loginResponse{status:"ok"}` | | LE:493-536 |

Login attempts (`iam_login_attempts`) rows: `USER_LOGIN`, identifier
lower-cased, `FailureReason` ∈ {"Invalid credentials","SSO required"} or 2FA
reasons; never user-agent on this path (LE:632-652; see
`docs/spec/loginattempt.md`).

### 7.2 Backoff policy (`loginbackoff.Check`, LB:94-158) [I — the *numbers* are C]

Two layers, evaluated in order, per lower-cased identifier:

1. **Per (identifier, IP) exponential delay** — only when `ip != ""`.
   `cutoff = last SUCCESS for identifier, else now−30 days`;
   `count, lastFailure = failures for (identifier, ip) since cutoff`;
   `required = ComputeDelaySecs(count)`; if `required > 0` and `now −
   lastFailure < required` → deny `pair_backoff`, `RetryAfter = required −
   elapsed` (LB:105-137).
   `ComputeDelaySecs(n) = 0 if n ≤ Free; else min(Base << (n−Free−1), Max)`
   with the shift exponent clamped to 31 (LB:51-67). Defaults give
   0,0,0,0,2,4,8,16,32,64,128,256,300,300… (LBT:20-35).
2. **Per-identifier global ceiling** — `cutoff2 = max(now−GlobalWindow,
   cutoff)`; `count2 = failures for identifier since cutoff2` (any IP); if
   `count2 ≥ Ceiling` → deny `global_ceiling`, `RetryAfter = LockSecs`
   (LB:139-155). **Q11 RULED (owner, 2026-08-24): make it a real lock.** In
   Go today this is not a lock timer — the window keeps sliding, so the caller
   is told "900" while the real gate is the hourly count, which usually keeps
   denying well *past* 900 s and occasionally clears well before it. The
   agreed behaviour is: deny while `over-ceiling AND now < max(lockEnds,
   countEnds)`, advertising the later of the two, so the lock is enforced,
   the hourly ceiling is retained, and `Retry-After` is honest. Design,
   repository contract and test table: `docs/spec/login-backoff-lock.md`.

The 2FA verify step reuses the same check (TF:166-168). Federated users are
screened out before the check only by the SSO gate (LB:14-16).

### 7.3 `/oauth/authorize` and token minting rules

**7.3.1 Authorize ordered procedure** (AZ:29-238): state required (direct
400) → client bucket (429) → client lookup (direct 4xx/5xx) → `redirect_uri`
must match registered set (direct 400) → portal client refused (direct 400)
→ *from here on errors bounce to redirect_uri* → `response_type == "code"` →
`authorization_code` allowed → PKCE required & challenge missing → method ∉
{"",S256} → scope validation (`invalidScopes`: every token must be one of
`openid profile email offline_access` or in the client's `Scopes`; AZ:287-306)
→ session resolution (cookie first, then Bearer; `ValidateSession` =
`provider.ValidateSessionToken`, WS:138-144) → `sessionStale = max_age
exceeded` (absent/invalid/negative max_age or zero iat ⇒ not exceeded;
`max_age=0` ⇒ always stale; AZ:249-262, AZT:64-85) → `prompt=none` with no
fresh session → `login_required`; `prompt=login` forces re-auth → fresh
session: mint code (64 random bytes → 86-char base64url, AZ:315-328),
persist method `"S256"` when a challenge arrived without a method (AZ:148-162),
insert, 307 to `redirect_uri` with `code&state` using `&` when the URI
already has a query (AZ:276-285) → else `provider=` present: 307 to the
bridge with `oauth_*` params, **no pending-auth stash** → else stash
PendingAuth keyed by state and 307 to `/auth/login?oauth=true…`.

**7.3.2 redirect_uri matcher** (`MatchRedirectURI`, RU:8-135) — a pinned
table; shared with post-logout validation:

| Rule | Accepted | Rejected | Cite |
|---|---|---|---|
| exact string | `https://app.x.com/cb` vs same | | RU:29-33 |
| parse: must have scheme + host, **no userinfo** | | `https://app.x.com@evil.com/x`, `https://*` | RU:34-37, RUT:35-36 |
| non-wildcard patterns match only exactly | | `https://app.x.com/foo` vs `https://app.x.com/cb` | RU:38-41 |
| scheme equal (case-insens.), port equal | | `http://` vs `https://`, `:8443` vs none | RU:54-59, RUT:38-39 |
| host: wildcard only in leftmost label, full (`*.`) or partial (`qa-*.`, `*-qa.`); base ≥ 2 labels, exact; each `*` consumes ≥ 1 char, never a dot | `app.x.com` / `qa-acme.x.com` / `acme-qa.x.com` | `x.com`, `a.b.x.com`, `x.com.evil.com`, `qa-.x.com`, `*.com`, `evilx.com` | RU:66-124, RUT:17-40 |
| path: pattern path `""`/`/` ⇒ any path; trailing `/*` ⇒ prefix; else exact; query/fragment of the incoming URI ignored | `/auth/done` vs `/auth/*` | `/admin` vs `/auth/*`, `/other` vs `/cb` | RU:126-135, RUT:42-45 |

**7.3.3 Scope narrowing** (`grantedScope`, TK:786-838; TST:30-143):
`FlattenPermissions` unwired ⇒ `(nil,false)` — token gets **no scope claim**.
Requested tokens minus the reserved set `{openid, profile, email, address,
phone, offline_access}`; none left ⇒ full ceiling, `explicit=false`; else
keep each requested permission the ceiling `Grants` (4-segment wildcard match,
SA:243-270) — anchor passes everything verbatim; `explicit=true`.
client_credentials rejects `explicit && empty` with `invalid_scope`;
interactive minting ignores `explicit` (TA:52).

**7.3.4 Interactive access token shape** (`mintInteractiveAccessToken`,
TA:27-57; AAT:33-146): client nil or `!APIAccess` ⇒ identity token. Else: if
the client has `ApplicationIDs` and the role filter is wired: roles ← those
`FilterRolesForApplications` keeps, `AllApplications=false`,
`AccessibleApplicationIDs ← user's ∩ client's` (all of the client's when the
user holds AllApplications, TA:62-77); then scope from the narrowed roles;
`GenerateAccessTokenWithScope` (`token_use=api`). An unscoped apiAccess client
keeps the full role set.

**7.3.5 ID-token role narrowing** (`mintIDToken`, TK:840-858;
`filterRolesForApplications`, PR:163-218; FRT:37-137; TIT:46-131): client
nil / no `ApplicationIDs` / filter unwired ⇒ full role names. Else for each
assigned name: `FindByName`, falling back to `FindByShortNameInApps(name,
appIDs)` (SDK-synced bare short names); drop unknown, drop platform roles
(`ApplicationID == nil`), drop roles of other apps; emit the app-local
**short name** (`ShortName()`, first `:` after the app code — inner colons
kept). `[]` when nothing survives.

### 7.4 Refresh-token rotation core (`grantstore.Rotate`, RO:37-91; ROT:40-131)

1. `hash = base64url(sha256(raw))`; `FindValidByHash` (type=RefreshToken,
   tokenHash, `expires_at > now`, `consumed_at IS NULL`, `revoked=false`).
2. Not valid → `FindByHash`; if that row exists with `replacedBy` **and**
   `tokenFamily` set ⇒ **reuse detected**: `RevokeAllInFamily(grant_id =
   family)`; return `Stored=nil` (caller says "invalid or expired"). A
   replayed legacy token without a family just fails.
3. `authorize(stored)` hook (client binding) — failure returns the stored
   token and the error, **nothing rotated** (ROT:90-105).
4. `RevokeByHash(presented)` (sets payload revoked/revokedAt + `consumed_at`).
5. New pair: fresh raw/hash/id; copies `Scopes, AccessibleClients,
   OAuthClientID`; **`ExpiresAt = stored.ExpiresAt`** (absolute 7-day family
   cap, never extended, GS:36-41, RO:69-75); family = stored family, or
   rooted at the **new** token's id for legacy tokens (ROT:118-131).
6. Insert; `MarkAsReplaced(presented → new hash)` best-effort.
7. `lastUsedAt` exists but is never stamped by the flows (GS:459-470).

### 7.5 Authenticator decision tree — ported (MW:68-108, MW:117-213)

Bearer header wins; a non-Bearer `Authorization` suppresses the cookie
fallback; cookie otherwise (MWT:19-67). Invalid token from **cookie** ⇒
proceed anonymous (graceful logout, AUD:53); from **Bearer** ⇒ 401
`invalid_token` unless `IgnoreInvalidTokens`. Identity bearer ⇒ 401
(MWT:88-147). Test headers only when `FC_AUTH_ALLOW_TEST_HEADERS` and no
token present: `X-FC-Test-Principal` (required), `-Scope` (default CLIENT),
`-Clients`, `-Permissions`, `-Roles`, `-Email`, `-Applications`,
`-All-Applications` (default true; false when `-Applications` given)
(MW:250-301; fcdev sets it true, `cmd/fcdev/envcfg.go:23`).

### 7.6 Purger [I]

`StartPurger`: every **1 min** (`time.Ticker`), always on, runs four
best-effort `DELETE … WHERE expires_at < NOW()` sweeps: `oauth_oidc_payloads`
(all types — codes, refresh tokens, pending auth), `oauth_oidc_login_states`,
`webauthn_ceremonies`, `portal_login_flows`; failures logged at WARN, counts
at DEBUG; stops on ctx cancel (SS:475-522, PL:167-171). Rate-limit
`Prune(MaxWindow)` exists but **no caller was found** in this sweep — **Q12**.

---

## 8. State machines

### 8.1 Authorization code (GS:46-82, GS:144-156)

| State | Predicate | → | Trigger | Cite |
|---|---|---|---|---|
| Issued | row exists, `consumed_at NULL`, `expires_at > now` | Consumed | `FindAndConsume` (atomic UPDATE…RETURNING; concurrent redeemers lose) | GS:148-156 |
| Issued | | Expired | `expires_at ≤ now` (10 min after mint) | GS:35, GS:79 |
| Consumed / Expired | | Deleted | purger (only when expired; consumed-but-unexpired rows linger ≤ 10 min) | SS:500 |
| any | re-insert of same code id | overwritten (upsert) | `Insert` | GS:136 |

A consumed code redeemed again → `invalid_grant`; there is **no**
family/refresh revocation on code replay (RFC 6749 §4.1.2 recommends it) —
**Q13**.

### 8.2 Refresh token / family (§7.4)

| State | Predicate | → | Trigger |
|---|---|---|---|
| Active | not revoked, not consumed, `exp > now` | Rotated-out | successful `Rotate` (revoked + `replacedBy` set) |
| Active | | Revoked | `/oauth/revoke` (by hash), family revocation, `RevokeAllForPrincipal` (password change CP:98-102, password reset WP:51-53), binding failure never |
| Active | | Expired | `exp ≤ now` (≤ 7 d after **first** issuance in the family) |
| Rotated-out | presented again | **Family revoked** (every non-revoked row with `grant_id = family`) | reuse detection |
| Revoked / Expired | presented | rejected (no side effect unless rotated-out + family) | |
| Expired | | Deleted | purger |

Family id = root token id (`grant_id`); legacy rows (`tokenFamily NULL`) get
a family rooted at their first replacement.

### 8.3 Session (cookie) — stateless

Established by `Set-Cookie` (login, 2FA completion, bridge, passkey); valid
until `exp` (24 h) or signature/issuer failure; **cannot be revoked
server-side**; deactivation bites on the next request because the cookie path
re-resolves the principal (MW:157-177, PR:88-92); `/auth/logout` clears the
browser copy only; `max_age`/`prompt=login` on `/oauth/authorize` can force
re-auth without invalidating the cookie (AZ:119-140).

### 8.4 Backoff (per identifier) — derived, not stored

`Clean → PairDelayed (count>Free at this IP, within delay) → Clean (delay
elapsed)`; `any → GlobalLocked (≥100 failures/h any IP) → Clean (count falls
below 100)`; a SUCCESS resets both windows (`cutoff = lastSuccess`).

### 8.5 OAuth client

`Active ⇄ Inactive` via activate/deactivate (EN:250-260); inactive ⇒
`/oauth/authorize` 400 "Client is not active", token auth 401 "Client is not
active" / "Invalid client credentials" (client_credentials). `Deleted` is
terminal. Secret rotation replaces `SecretRef`; old secret invalid
immediately (no grace) — **Q14**.

---

## 9. Topology & concurrency as behaviour

| Invariant (behaviour) | What Go does because of Go (not contract) | Cite |
|---|---|---|
| Token minting/validation is pure and stateless; no per-request state. | `AuthService` is an immutable struct built once; `previousKeys` slice. | AS:239-253 |
| Auth-code consumption is atomic (exactly one redeemer wins). | Single `UPDATE … RETURNING` on the pool, no tx. | GS:148-156 |
| Refresh rotation steps are **not** transactional: revoke → insert → mark-replaced are three statements; a crash between revoke and insert strands the user (must re-login); `MarkAsReplaced` is best-effort so a crash there loses reuse detection for that hop. | Raw pgx, no tx. | RO:57-90 |
| Rate-limit check+record is atomic per store call (Postgres CTE insert+count; Redis INCR). | | PG:27-39, RD:52-70 |
| Rate-limit backend failure or nil store ⇒ **fail open** (never block auth). | `Enforce` logs WARN and returns nil. | RL:159-175, RLT:53-71 |
| Governors are per-process (not cluster-wide) token buckets; keys idle > 10 min pruned opportunistically every 5 min on a `Check`. | `sync.Mutex` + map + `x/time/rate`. | GV:13-32, GV:87-95, GV:135-153 |
| Purger is a single always-on loop per process; safe to run on every replica (idempotent deletes). | goroutine + ticker. | SS:485-522 |
| Login-attempt recording and audit inserts never fail the request. | errors discarded. | LE:632-652, TK:101-113, SAA:319-330 |
| The plaintext client secret must survive only from commit to response; process-local, TTL 2 min. | `sync.Map` stash swept on store. | SX:8-66 |
| Secret comparisons are constant-time (client secret, PKCE). | `subtle.ConstantTimeCompare`. | TK:277-290, TK:745-751 |
| Password verification for unknown/inactive accounts burns the same cost (`EqualizeTiming`). | | LE:434-443 |
| `/oauth/authorize` sits outside the Authenticator; `/oauth/token` & friends inside it (so the middleware sees Bearer headers first). | chi groups. | WP:66-69, WR:190-200 |

---

## 10. Constants — load-bearing or accident?

| # | Constant | Value | Where | [C]/[I] | Load-bearing or accident? — evidence |
|---|---|---|---|---|---|
| 1 | `TokenUseAPI` / `TokenUseIdentity` | `"api"` / `"identity"` | AS:51-52, ST:70 | C | **Load-bearing** — wire marker read by the middleware; two packages pinned to match (AS:48-49). |
| 2 | Access-token lifetime | 3600 s | AS:204, WS:86, responses `expires_in` at TK:500/622/724, PT:72, LE:326, SAA:347 | C | Load-bearing for SDKs. **Q15 RULED: derive `expires_in` from `AccessTokenExpirySecs`** — six sites hard-code the literal, so the first change to the TTL makes all six lie while the JWT `exp` tells the truth. Latent only because `WS:86` is itself a literal 3600. `docs/spec/oauthapi-fixes.md` Fix 6. |
| 3 | ID-token lifetime | 300 s | AS:207, WS:87, AST:315-337 | C | Load-bearing by design note (AS:190-196): ID token is one-shot. |
| 4 | Session cookie TTL | 24 h | LE:49, `SessionTokenExpirySecs 86400` AS:205 (unused by login) | C | Load-bearing (SPA session length); `SessionTokenExpirySecs` is dead config — accident. |
| 5 | Refresh-token absolute cap | 7 d | GS:41, AS:206 | C | Load-bearing (family cap). `applyDefaults` says **30 d** (AS:224-226) but nothing reads `RefreshTokenExpirySecs` — dead/inconsistent config. **Q16** |
| 6 | Auth-code TTL | 10 min | GS:35 | C | Load-bearing (RFC recommends ≤ 10 min). |
| 7 | Pending-auth TTL | 10 min | PA:21 | I | Accident-ish: rows are never consumed (Q3). |
| 8 | Auth-code entropy | 64 random bytes → 86-char base64url | AZ:144, AZ:322-328 | I | Load-bearing size-wise only as "≥ 128 bits"; exact length is not a contract (column is the id prefix + code ≤ 128 chars: `"AuthorizationCode:"`(18)+86 = 104 ✓). |
| 9 | Refresh raw token | 32 bytes → 43-char base64url; hash = base64url(sha256) | GS:252-265 | C (hash form is the storage contract) | Load-bearing: hash lookup by `payload->>'tokenHash'`. |
| 10 | `kid` | base64url(sha256(PEM)[:16]) = 22 chars | AS:663-666 | C | Load-bearing (JWKS consumers cache by kid; AST:250-259 pins it). |
| 11 | Default issuer/audience | `"flowcatalyst"` | AS:202-203 | I | Accident — overridden by wiring with `FC_JWT_ISSUER` (WS:82-83); test-only. |
| 12 | `FC_JWT_ISSUER` default | `http://localhost:8080` | EC:146 | I | Dev default. |
| 13 | PKCE verifier length | 43–128, unreserved charset | TK:721-728 | C | RFC 7636 §4.1 — load-bearing. |
| 14 | PKCE default method | S256 (absent ⇒ S256 at mint and verify) | AZ:156-159, TK:729-737 | C | Deliberate deviation from RFC's "plain" default; load-bearing (security). |
| 15 | Developer role name | `"platform:developer"` | TK:406 | C | Load-bearing (seeded role gates the self-mint). |
| 16 | Principal id prefix for developer branch | `"prn_"` | TK:348 | C | Load-bearing: the disambiguator between OAuth-client and principal client_ids (`oac_` vs `prn_`). |
| 17 | Portal subject prefix | `"ptu_"` | PT:15 | C | Load-bearing (plane separation). |
| 18 | OIDC reserved scopes (narrowing) | openid profile email address phone offline_access | TK:791-793 | C | Load-bearing. |
| 19 | Authorize standard scopes | openid profile email offline_access (no address/phone) | AZ:288 | C | Inconsistent with #18 — **Q17**. |
| 20 | Backoff `FreeAttempts` | 3 (`FC_LOGIN_BACKOFF_FREE_ATTEMPTS`) | LB:42 | C | Load-bearing UX/security; env-overridable. |
| 21 | Backoff `BaseDelaySecs` | 2 | LB:43 | C | Load-bearing. |
| 22 | Backoff `MaxDelaySecs` | 300 | LB:44 | C | Load-bearing. |
| 23 | Backoff `GlobalWindowSecs` | 3600 | LB:45 | C | Load-bearing. |
| 24 | Backoff `GlobalCeiling` | 100 | LB:46 | C | Load-bearing ("never trips on normal usage", LB:10-12). |
| 25 | Backoff `GlobalLockSecs` | 900 | LB:47 | C | Load-bearing **after the Q11 fix**: the enforced minimum denial after a ceiling trip, and the advertised `Retry-After`. In Go as extracted it was the header value only, with nothing enforcing it — see `docs/spec/login-backoff-lock.md`. |
| 26 | Backoff last-success fallback | now − 30 days | LB:110 | I | Accident (bounds the query); not observable. |
| 27 | Backoff shift exponent cap | 31 | LB:59 | I | Overflow guard; not observable (LBT:32-34). |
| 28 | Rate-limit buckets | `oauth_token_ip, oauth_token_client, oauth_authorize_ip, oauth_authorize_client, oauth_introspect_ip, oauth_revoke_ip, password_reset_ip, password_reset_email, check_domain_ip, portal_login` | RL:33-42 | C (storage keys) | Names appear in Redis keys/DB rows. `oauth_introspect_ip`, `oauth_revoke_ip`, `check_domain_ip` have **no policy and no caller** — accident/dead. **Q18** |
| 29 | `FC_RL_OAUTH_TOKEN_IP_PER_MIN` | 600 / 1 min | RL:68 | I | Generous ceiling ("long-tail"), env. |
| 30 | `FC_RL_OAUTH_TOKEN_CLIENT_PER_MIN` | 300 / 1 min | RL:69 | I | env. |
| 31 | `FC_RL_OAUTH_AUTHORIZE_IP_PER_MIN` | 600 | RL:70 | I | env. |
| 32 | `FC_RL_OAUTH_AUTHORIZE_CLIENT_PER_MIN` | 300 | RL:71 | I | env. |
| 33 | `FC_RL_PASSWORD_RESET_IP_PER_HOUR` / `_EMAIL_PER_HOUR` / `FC_RL_PORTAL_LOGIN_PER_15MIN` | 20 / 5 / 10 | RL:72-74 | I | other specs' buckets; listed for completeness of `Policies`. |
| 34 | Token governor per-IP | 120/min, burst 60 (`FC_OAUTH_TOKEN_IP_RATE_PER_MIN/_BURST`) | GV:46-53 | I | env. |
| 35 | Token governor per-client | 60/min, burst 30 | GV:55-63 | I | env. |
| 36 | OIDC bridge governor | 60/min, burst 30 | GV:65-74 | I | bridge spec; listed because it lives here. |
| 37 | Governor prune interval / idle TTL | 5 min / 10 min | GV:91-92 | I | Accident (memory hygiene). |
| 38 | Governor clamp | PerMinute,Burst < 1 ⇒ 1 | GV:78-86 | I | Accident (misconfig guard). |
| 39 | Redis connect timeout | 2 s | RD:13 | I | Accident. |
| 40 | Redis key prefix | `fc:rl:` | RD:46-48 | C (shared Redis) | Load-bearing if Java and Go share a Redis. |
| 41 | Retry-After floor | 1 s | RL:179-182, TK:914-916, RD:75-77, PG:61-63 | C | Load-bearing (clients parse it). |
| 42 | Purger tick | 1 min | SS:491 | I | Accident (any cadence ≥ TTLs works). |
| 43 | Secret stash TTL | 2 min | SX:17 | I | Accident (safety bound). |
| 44 | Client-secret entropy | 32 bytes base64url; stored `"encrypted:"`+ct | OC:372-392 | C (storage prefix) | Prefix is load-bearing (decrypt strips it; rows uniform). |
| 45 | Login-history page | 20 rows | SH:27 | C | Accident (UI choice). |
| 46 | 2FA pending / enroll token TTL | 10 min / 30 min | TF:27-28 | C | second spec; listed because login returns them. |
| 47 | Cookie name | `fc_session` | MW:115 | C | Load-bearing (SPA, bridge, authorize all read it). |
| 48 | Cookie `SameSite` | Lax | LE:509 | C | Load-bearing (OAuth redirects back into the SPA need Lax). |
| 49 | `WWW-Authenticate` values | `Cookie realm="fc_session"` / `Bearer error="invalid_token"` | LE:678, MW:238 | C | First is non-standard; SDKs may branch on the second. |
| 50 | Identity-reject error text | "this access token was issued for interactive login and cannot authorize API requests; obtain an API token via the client_credentials grant" | MW:24 | C | Surfaces as `error_description`. |
| 51 | HS256 minimum secret | non-empty (no length check) | AS:330-333 | I | Accident — **Q19**. |
| 52 | `ServiceAccountTokenResponse.expiresIn` | 3600 | SAA:342 | C | mirrors #2. |
| 53 | OAuth grant list empty ⇒ unrestricted | — | TK:774-784 | C | Legacy compat; load-bearing for pre-existing clients. **Q20** |
| 54 | `/auth/check-domain` GET auth methods | `INTERNAL`/`OIDC` | LE:228-236 | C | legacy shape, load-bearing for old clients. |
| 55 | `email_verified` | always true | AS:485-489 | C | **Q2** |
| 56 | `auth_time` | = iat of the ID token | AS:501 | C | **Q1** |
| 57 | Lockfile `clientType` lenient parse | unknown ⇒ PUBLIC | EN:36-42 | C | Accident — **Q21**. |
| 58 | `oauth_clients.client_id` length | VARCHAR(100); `client_secret_ref` 500; `default_scopes` 500; redirect 500; origin 200 | M7:52-58, M7:73, M7:84 | C | schema (existing DB). |
| 59 | `oauth_oidc_payloads.id` | VARCHAR(128) | M7:142 | C | bounds `"PendingAuth:" + state` — a state > 116 chars fails the insert → `server_error` redirect. **Q22** |
| 60 | `iam_rate_limit_events.bucket` | VARCHAR(64) | M30:25 | C | schema. |

---

## 11. Backend / store contracts

| Contract | Statement | Cite |
|---|---|---|
| Auth code insert | upsert on id; columns per §3.7 | GS:114-142 |
| Auth code consume | `UPDATE … SET consumed_at=NOW() WHERE id=$1 AND consumed_at IS NULL AND expires_at > NOW() RETURNING …`; `(nil,nil)` on no row | GS:148-156 |
| Auth code scan | `exp` falls back to `created_at + 10 min` when `expires_at` NULL | GS:197-200 |
| Refresh insert | upsert; `grant_id = tokenFamily`; `accessibleClients` forced `[]` | GS:317-352 |
| Refresh find valid | by hash, `expires_at > NOW() AND consumed_at IS NULL`, then `revoked=false` in memory | GS:365-379 |
| Refresh revoke (by hash / family / principal) | `jsonb_set(revoked=true, revokedAt=<RFC3339>)` + `consumed_at = now`; family: `grant_id = $2 AND (revoked IS NULL OR 'false')`; principal: `payload->>'accountId'`, unconsumed, unexpired, unrevoked | GS:402-457 |
| Mark replaced | `jsonb_set(replacedBy = <new hash>)` by hash | GS:383-393 |
| Pending auth | insert upsert; consume = `DELETE … RETURNING payload` | PA:57-115 |
| Delete expired (codes/refresh/pending) | `DELETE WHERE type=$1 AND expires_at < NOW()` | GS:167-177, GS:474-482, PA:119-127 |
| Payload purge (used by purger) | sqlc `OAuthPayloadPurgeExpired` (all types) | PL:167-171 |
| Rate-limit PG | CTE insert + count in window; over ⇒ second query for `MIN(occurred_at)` to compute Retry-After = window − age(oldest) (≥1) | PG:27-67 |
| Rate-limit Redis | fixed window `floor(now/window)`; INCR; EXPIRE on 1; Retry-After = window − (now mod window) | RD:50-81 |
| Rate-limit `Build` | `FC_RATE_LIMIT_DISABLE=1` ⇒ Noop; `FC_REDIS_URL` reachable (PING) ⇒ Redis; else Postgres; choice logged, URL credentials redacted | RL:122-152 |
| Login-attempt stats | `LastSuccessAt`, `FailureStatsByIdentifierIPSince`, `FailureCountByIdentifierSince` (see `docs/spec/loginattempt.md` §5) | LB:85-92 |
| OAuth client repo | §3.6; `FindByClientID` is the runtime hot path (`OAuthClientFinder` interface) | TK:39-45, RP:68-77 |
| Principal repo | `FindByEmail` lower-cases+trims; `FindByID`; `UpdatePasswordHash`; `LowercaseEmail` (no write when already lower) | principal/repository.go:152-162, :718-730 |
| Role repo (via provider) | `FindByName` (missing roles skipped), `FindByShortNameInApps` | PR:138-218 |
| Encryption | `Decrypt(secretRef)` (strips `"encrypted:"`); `Encrypt`; nil service ⇒ secret verification always fails | TK:281-290, OC:377-393, `docs/spec/encryption.md` |

---

## 12. Configuration (env)

| Var | Default | Used for | Cite |
|---|---|---|---|
| `FC_JWT_ISSUER` (← `FC_EXTERNAL_BASE_URL` ← `EXTERNAL_BASE_URL`) | `http://localhost:8080` | `iss`, `aud`, discovery base, bridge callback base | EC:146, WS:62-66, WS:82-83, WS:130 |
| `FC_JWT_SIGNING_KEY_PATH` / `FLOWCATALYST_JWT_PRIVATE_KEY` / `FC_JWT_SIGNING_KEY_PEM` | ephemeral | RSA private key | SK:16-49 |
| `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY` | — | rotation | EC:257-266 |
| `FC_AUTH_ALLOW_TEST_HEADERS` | false (fcdev: true) | test bypass **and** `Secure=false` on cookies | EC:213, WS:199, WR:87 |
| `FC_LOGIN_BACKOFF_FREE_ATTEMPTS / _BASE_SECS / _MAX_SECS`, `FC_LOGIN_GLOBAL_WINDOW_SECS / _CEILING / _LOCK_SECS` | 3/2/300/3600/100/900 | backoff | LB:40-49 |
| `FC_RL_*` (§10 #29-33) | | distributed policies | RL:66-76 |
| `FC_OAUTH_TOKEN_IP_RATE_PER_MIN/_BURST`, `FC_OAUTH_TOKEN_CLIENT_RATE_PER_MIN/_BURST`, `FC_OIDC_RATE_PER_MIN/_BURST` | 120/60, 60/30, 60/30 | governors | GV:46-74 |
| `FC_REDIS_URL`, `FC_RATE_LIMIT_DISABLE` | — | store selection | RL:125-141 |
| `FLOWCATALYST_APP_KEY` | — | client-secret / developer-secret encryption; absent ⇒ CONFIDENTIAL create/rotate fail 500, secret verification fails closed | OC:377-383, TK:281-284 |
| `envutil.Int/Uint32` semantics | unparseable ⇒ default | | envutil.go:23-39 |

No env for TTLs of access/ID/session/refresh tokens — they are compile-time
(WS:86-87, LE:49, GS:35-41).

---

## 13. Observability

Routes: §6. Logs (slog): password rehash/lowercase failures WARN (LE:456,
LE:464); 2FA eval failure ERROR (LE:474); rate-limit backend WARN + fail-open
(RL:168); store choice INFO (RL:127-138); purger INFO/WARN/DEBUG (SS:493-518);
ephemeral key WARN (SK:48); internal-error responses ERROR (httperror.go:72-74).
Correlation id: `X-Correlation-ID` echoed/generated (MW:29-39); principal id
added to log context after auth (MW:96, MW:102). **No metrics** are emitted
by any package in scope. Audit rows: only the admin SA mint
(`TOKEN_MINTED_BY_ADMIN`, SAA:319-330) and 2FA state changes (out of scope);
login attempts are the auth trail (§7.1).

---

## 14. HA / leadership & shutdown

No leader election anywhere in scope. Everything is safe on N replicas given
one shared signing key (SK:23-26 warns ephemeral keys break multi-replica) and
one DB/Redis; governors are intentionally per-replica (GV:13-17); the purger
runs on every replica (idempotent). Shutdown: purger exits on context cancel
(SS:496-498); nothing else holds state. A crash mid-rotation (§9) is the only
user-visible hazard.

---

## 15. Edge cases mined from tests

| Case | Expected | Test |
|---|---|---|
| `aud` is a bare string; `type,jti,nbf,iat,exp,iss,sub,tier,name,clients,roles,applications` always present; `scope` absent without grant; `permissions` never | | AST:102-127 |
| Scope claim round-trips as space-joined string | | AST:132-152 |
| CLIENT principal → `clients:["clt_123:acme"]`; SERVICE omits `email`, `type:"SERVICE"` | | AST:154-191 |
| HS256 service validates its own tokens and exposes 0 JWKS keys | | AST:193-213 |
| Token signed by previous key validates; JWKS lists 2 keys | | AST:215-248 |
| kid deterministic, 22 chars | | AST:250-259 |
| ID token: aud=azp=client, nonce echoed, no nbf/jti, email_verified true; roles override; empty roles `[]` not null; exp−iat = 300 vs 3600 | | AST:261-337 |
| `token_use` = api on GenerateAccessToken/WithScope; identity token carries no authority at all even for anchor | | AST:372-445 |
| `clients` pairs: anchor `*`, client/partner `id:identifier`, bare id when identifier missing (documented bug shape) | | BCT:15-68 |
| Session: round-trip; bad key; expired (`jwt.ErrTokenExpired`); wrong issuer; foreign aud rejected, platform aud passes, no aud passes; empty subject / nil key refused | | STT:24-175 |
| Backoff: delay curve; clean allowed; pair reject (0,2]; elapsed allows; global 100 → 900; empty IP skips pair gate | | LBT:20-119 |
| Authorize: unsupported response_type bounces to vetted redirect_uri with state; `?provider=` chains to bridge with all `oauth_*` params and no stash; unknown client / unregistered redirect_uri are **direct 400, never 3xx**; missing state 400 `invalid_request`; portal client refused with body mentioning `/portal/authorize` | | AZT:105-227 |
| `maxAgeExceeded` table (absent, zero iat, within, exceeded, `0` ⇒ always, invalid, negative) | | AZT:64-85 |
| `pctEncode` keeps `-_.~`, space → `%20`, `:` `/` `?` `=` encoded | | AZT:37-49 |
| `invalidScopes`: standard set valid; client scope valid; `bogus` reported | | AZT:51-62 |
| PKCE: S256 ok, nil method ⇒ S256, plain ok, rejections table all `invalid_grant` | | TKT:11-61 |
| Basic creds: `clt_1:s3cr3t`; empty secret ok; missing header / Bearer / no colon ⇒ not ok | | TKT:63-98 |
| `scopeHas` on empty scope false | | TKT:100-113 |
| `grantedScope` table: unwired ⇒ nil/false; omitted ⇒ ceiling; subset; unheld dropped; only-unheld ⇒ empty+explicit; OIDC-only ⇒ ceiling/not explicit; wildcard ceiling; anchor verbatim | | TST:30-143 |
| `mintIDToken`: unscoped client full roles; app-scoped narrowed; filter unwired full; nil client full | | TIT:46-131 |
| Discovery values; no `plain`, no implicit/hybrid response types; JWKS one key with kty/use/alg/kid/n/e | | DST:39-102 |
| redirect_uri matcher table (26 rows) | | RUT:5-54, AZT:16-35 |
| apiAccess client: token_use api, roles filtered, scope from narrowed roles, applications intersected, all_applications false; refresh re-mints same; unflagged client identity-only; portal+apiAccess conflict code | | AAT:33-164 |
| Developer grant: success; wrong secret 401 invalid_client; role revoked 401; inactive 401; no credential 401; unknown `prn_` 401 | | DGT:115-239 |
| Portal code: id_token sub = ptu id, email, roles empty; access token identity; **no refresh token even with offline_access**; suspended identity ⇒ 400 invalid_grant | | PTT:29-101 |
| Rotate: lineage preserved, replaced-by recorded, chain within family, replay revokes whole family incl. newest; authorize failure rotates nothing; never-issued ⇒ nil Stored; legacy roots a family at the replacement id | | ROT:40-131 |
| Middleware: header forms (case-insensitive Bearer, trim, non-Bearer ⇒ empty, `Bearer` alone ⇒ empty); cookie fallback only without Authorization; identity bearer 401 + no ctx, api bearer 200 | | MWT:19-147 |
| Rate limit: defaults; env override; Noop allows; Enforce allow/reject/fail-open/nil; redact URL; ClientIP rightmost XFF, RemoteAddr host, trailing comma ⇒ fallback; 429 body/headers exact; IP middleware rejects / passes with no IP | | RLT:12-153 |
| Governor: burst then throttle (retry 1), keys independent, prune, clamp, middleware 429 with Retry-After, no IP passes | | GVT:22-149 |
| OAuth client ops: public create (defaults, junctions, no secret stashed); confidential create stashes once, ref `encrypted:` and decrypts; omitted clientId ⇒ `oac_` TSID; rotate mints new secret, pop once, ref changes; public rotate conflict | | OPT:436-730 |
| Role filter table (bare sync names, prefixed names, multi-colon, other app dropped, platform dropped, unknown dropped, mixed) | | FRT:37-137 |

No tests exist for `login/*` handlers, `introspect/revoke`, `userinfo`,
`change-password`, `login-history`, Postgres/Redis stores, or the purger — the
conformance suite must add them.

---

## 16. Security properties worth stating as invariants

1. Identity tokens never authorise API calls (MW:180-189; AST:397-445).
2. ID tokens never validate as platform bearers (aud guard, ST:125-139).
3. `/oauth/authorize` never redirects to an unvetted URI (AZ:56-76, AUD H1).
4. Auth codes are single-use and client-bound to the **authenticated** client
   (TK:498-521).
5. A rotated-out refresh token replayed kills its family (RO:43-49).
6. `/auth/refresh` refuses OAuth-client-bound tokens (no client auth there)
   (LE:261-291).
7. Refresh never extends the 7-day family window (RO:69-75).
8. A scope request can never escalate (TK:796-807).
9. Password login is closed for OIDC-mapped domains even if a hash exists
   (LE:415-432), and password self-service for SSO-managed users (CP:32-38).
10. RSA misconfiguration / empty HS256 secret refuses to boot (AS:304-333).
11. Secrets compared constant-time; unknown users cost a real hash verify.
12. Rate limiting fails open; backoff errors are ignored (availability over
    strictness) — **Q23**.

---

## 17. Already ported in Java — what remains

Present under `server/src/main/java/io/flowcatalyst/platform/shared/auth/`
(signatures inspected, bodies not audited here): `Authenticator` (decision
tree, `Extracted`, `Authenticated|Anonymous|Rejected`, test headers, CSV
split), `JwtVerifier` (`RsaKeys(current, previous)` / `HmacKey`; exp, nbf,
iss, aud-if-present, sub; `Verified|Rejected`), `TokenClaims`,
`ClaimsResolver` (seam: resolve + `flattenPermissions`, `none()`),
`SigningKeys` (load order, `normalizePem`, PKCS#1/#8, derive public, `kid`,
`Single|Rotating`, ephemeral, `ensureSigningKeyFile`), `AuthContext`, `Auth`,
`Checks`, `Permission`, `Scope`, `PrincipalType`, `PasswordHash`
(`docs/spec/password-hash.md`), `CorrelationId`. `LoginAttemptRepository`
exists with the three backoff queries (`docs/spec/loginattempt.md` §5).

**Remaining for this subsystem** (nothing found in Java for any of these):
§3.1-3.3 minting (`authservice` generate*, `sessiontoken.Mint`,
`provider.MintSessionToken`/`ResolveClaims` DB implementation of
`ClaimsResolver`), §6.1 login surface (all nine routes, cookie minting,
`/auth/refresh`), §7.2 backoff policy, §6.2 the whole OAuth provider
(`authorize`, `token` ×3 grants + developer branch + portal branch,
introspect, revoke, userinfo, discovery, JWKS, redirect matcher, scope
narrowing, role filtering), §3.7/§7.4 grant store + rotation, §3.6/§6.3
OAuth-client aggregate (entity, repo with five junctions, six operations,
events, stash, ten routes), §6.4 SA admin mint, §3.8/§7 rate limiting
(store ×3, governors, middlewares, `ClientIP`), §7.6 purger. `docs/spec/cors.md`
already consumes `AllowedOrigins` — the Java CORS filter's origin source must
be this aggregate's junction.

---

## 18. Things the spec deliberately does **not** carry over

- `ratelimit.Policies.MaxWindow` / `Prune` — no caller (Q12).
- `payload.Repository` CRUD beyond `PurgeExpired` and its `TypeAccessToken…`
  constants (PL:33-39, PL:100-165) — unused by the flows.
- `RefreshToken.LastUsedAt/CreatedFromIP/UserAgent`, `AuthorizationCode.
  ContextClientID/State` — persisted but never populated by the flows
  (GS:49-63, GS:231-237).
- `loginRequest.rememberMe` (LE:336) — parsed, unused.
- `provider.Claims.Nonce/AuthorizedParty/AuthTime/EmailVerified` (PR:68-76) —
  never populated.
- `authservice.HasClientAccess/HasRole/IsAnchor` helpers (AS:567-589) — no
  caller found in scope.
- `middleware.stringSlice` (MW:215-232) — dead helper.

---

## 19. Open questions for the owner (yes/no)

| # | Question | Default if unanswered |
|---|---|---|
| Q1 | Keep `auth_time` = ID-token `iat` (not the cookie's `iat` / real login time)? | yes (as Go) |
| Q2 | Keep `email_verified: true` unconditionally whenever an email exists? | yes |
| Q3 | Keep writing `PendingAuth:{state}` rows that nothing reads (wire/storage compat only)? | yes |
| Q4 | **CORRECTED + RULED (owner, 2026-08-24).** The original framing was wrong: the Authenticator is **not a gate**. It attaches an `AuthContext` when credentials are present and otherwise calls `next` (`middleware.go:59-108`), so "inside the group" does not mean "requires authentication" — an anonymous `GET /.well-known/jwks.json` or a `client_id`/`client_secret` POST to `/oauth/token` works today. There is exactly **one** hard-fail path (`middleware.go:89-92`): an explicit `Authorization: Bearer` carrying a token the middleware will not accept is 401'd before the handler; a cookie is not, and a non-Bearer scheme is deliberately declined by `extractToken` (`middleware.go:129-132`), so Basic-authenticated introspect/revoke are safe. For five of the six routes the placement is untidy but harmless. **`/oauth/userinfo` is genuinely broken**: `middleware.go:187-189` refuses any `token_use=identity` token, and an ordinary (non-`APIAccess`) OIDC client's `authorization_code` grant mints exactly an identity token (`token_apiaccess.go:28-29`), so the canonical RP sequence *authorization_code → access_token → `GET /oauth/userinfo`* is 401'd before reaching the handler's own `validateBearer` (`userinfo.go:60-74`), which would have accepted it. That contradicts the stated design intent at `authservice.go:405-407`. Latent because `APIAccess` clients get `token_use=api`, and no FlowCatalyst SDK calls userinfo. **Fix (being made in Go first):** move `RegisterUserinfoRoutes` into `registerPublicRoutes`; moving discovery out as well is defensible hardening. The Java port follows the corrected Go, with a regression test covering an identity token against userinfo. |
| Q5 | Keep skipping the per-client rate buckets when the client authenticates with Basic only (no body `client_id`)? | yes | **Owner leaning (2026-08-24): no — rate-limit it.** A client authenticating with Basic and no body `client_id` is only IP-limited, so per-client brute-force of client secrets over Basic evades the client bucket. Fix: resolve the client id from `basicAuthCreds` *before* the rate-limit decision, so Basic and body clients are limited identically. |
| Q6 | Keep `client_credentials` reading `client_id`/`client_secret` **only from the body** (Basic auth refused with "Missing client_id") although discovery advertises `client_secret_basic`? | yes | **Verified 2026-08-24** (`token.go:132-145`, `token.go:159-160`, `token.go:326-334`): `parseTokenRequest` reads form values only and never merges Basic; `authorization_code` and `refresh_token` *do* accept Basic via `authenticateClient` (`token.go:233-236`, Basic wins). So the inconsistency is confined to the `client_credentials` grant, and discovery advertising `client_secret_basic` is a promise that grant breaks. Recommended fix is to route `client_credentials` through `authenticateClient` like the other grants — **not** to drop Basic, which RFC 6749 §2.3.1 requires an authorization server to support and which third-party OIDC libraries default to. |
| Q7 | **RULED (owner, 2026-08-24): 400, per RFC 6749 §5.2.** Verified 2026-08-24: **already done in the Go working tree** — `token.go:656-668` returns `StatusBadRequest` for `invalid_grant` on both the client-binding failure and the invalid/expired refresh token, with a comment citing §5.2 (every token-endpoint error is 400 except `invalid_client`, which MAY be 401). No further Go change; the spec row was stale. The Java port implements 400. |
| Q8 | **RULED (owner, 2026-08-24): fix — advertise the algorithm actually in use.** Verified 2026-08-24: **already fixed in the Go working tree** — `discovery.go` now sets `IDTokenSigningAlgValuesSupported: []string{s.Auth.Algorithm()}`, so an HS256 (no-RSA-key) deployment stops telling relying parties to verify RS256 against an empty JWKS. The Java port reads the active signer the same way. |
| Q9 | **RULED (owner, 2026-08-24): fix — arrays everywhere. DONE in Go, verified 2026-08-24.** `defaultScopes` is now one wire name, `array<string>`, byte-identical across create, update and the response; the legacy `scopes` alias is gone (`b5b471d`, `f908c3b`). Two corrections to the recommended path are recorded in `docs/spec/oauthapi-fixes.md` Fix 5 — the lenient decoder cannot work under huma, and the deprecation window was collapsed to zero because no first-party caller sent the old form. Nothing to port: the Java side implements the strict array only. |
| Q10 | **RULED (owner, 2026-08-24): 500.** The password was correct and the request was well-formed; a session cookie that cannot be minted is a server-side failure, and answering 400 tells the caller to fix a request that has nothing wrong with it (and invites a client to retry differently, which cannot help). **Still outstanding in Go** as of 2026-08-24: `internal/platform/auth/login/endpoint.go:500` calls `httperror.BadRequest("MINT_FAILED", err.Error())`. Note `httperror` has no `Internal` constructor (only `Forbidden`, `BadRequest`, `NotFound`) — add one wrapping `usecase.Internal`, or construct the `usecase.Error` directly, so `Status` maps it to 500. Do not put `err.Error()` in the body of a 500: log the cause, return a fixed message. |
| Q11 | **RULED (owner, 2026-08-24): no — make it a real lock.** `GlobalLockSecs` was assigned to nothing but the `Retry-After` header, so the constant named a control that did not exist and the env var changed only a number the client is told. Fix: deny while `over-ceiling AND now < max(lockEnds, countEnds)`; `Retry-After` = the later of the two. Strictly stronger than today, and the header becomes true. Owner is making the same change in Go. Spec: `docs/spec/login-backoff-lock.md`. |
| Q12 | **RULED (owner, 2026-08-24): fix — add it.** `PostgresStore.Prune` and the `Policies.MaxWindow()` that exists to feed it both have zero callers, so `iam_rate_limit_events` grows without bound and every limiter decision scans it. Go already has the janitor (`StartPurger`, 1-minute tick, four tables); the fix is a fifth sweep with retention `MaxWindow()`. Owner is making the same change in Go. Java has no periodic-task infrastructure yet, so this is a requirement on the auth port. Spec: `docs/spec/auth-retention.md`. |
| Q13 | **RULED (owner, 2026-08-24): keep current behaviour.** A replayed authorization code is rejected (`400 invalid_grant`) but the tokens the first redemption minted are not revoked, so an attacker who wins the race keeps a live, rotating refresh token. Deferred **out of the port** — Java reproduces the Go behaviour exactly and this is not a port deviation. Evidence, cost and blast-radius analysis: `docs/improvements.md`. |
| Q14 | **RULED 2026-08-24, then SHIPPED IN GO 2026-08-25 (`77ede1d`) — the port follows Go, not the original ruling.** Rotation now keeps the outgoing secret usable for a grace window: optional `{"graceSeconds"}` body on rotate, 24h default, `0` for an immediate cutover, `previousSecretExpiresAt` in the response (omitted when there is no overlap), plus `POST /api/oauth-clients/{id}/revoke-previous-secret` (idempotent). Exactly one previous secret is honoured. Two details the port must copy verbatim: `acceptClientSecret` runs **both** compares rather than short-circuiting on a current-secret match (early return leaks where a client sits in its rotation), and expiry is enforced **on read** because the row keeps a lapsed secret until the purger clears it. Full behaviour table: `docs/improvements.md`. |
| Q15 | **RULED (owner, 2026-08-24): fix — derive it from the configured access TTL.** Six response sites hard-code `3600` while the lifetime lives in `AuthService.Config.AccessTokenExpirySecs`. Latent today (config is itself a literal 3600 at `wire_services.go:86`, so the two agree), but the coupling is invisible: the first change to the TTL silently makes all six responses lie. Java derives it; the port must not reproduce the literal. Spec: `docs/spec/oauthapi-fixes.md` Fix 6. |
| Q16 | Treat `RefreshTokenExpirySecs`/`SessionTokenExpirySecs` config as dead (7 d / 24 h compile-time)? | yes |
| Q17 | Keep two different "standard scope" sets (authorize validation excludes `address`/`phone`; narrowing reserves them)? | yes |
| Q18 | Drop the three caller-less buckets (`oauth_introspect_ip`, `oauth_revoke_ip`, `check_domain_ip`) from Java? | yes (drop) |
| Q19 | Keep "any non-empty HS256 secret" (no minimum length)? | yes |
| Q20 | Keep "empty grant-type list ⇒ every grant allowed"? | yes |
| Q21 | Keep lenient `clientType` parsing (unknown ⇒ PUBLIC) on create? | yes |
| Q22 | Keep the implicit `state` length cap (≤ 116 chars) imposed by `oauth_oidc_payloads.id VARCHAR(128)`? | yes |
| Q23 | Keep fail-open for rate-limit backend errors and ignored backoff errors? | yes |
| Q24 | Keep `/auth/me` emitting `"status":""`? | yes |
| Q25 | Keep `/oauth/authorize` preferring the cookie over a Bearer header while the middleware prefers the header? | yes |
| Q26 | Keep introspect's `client_id` = first `clients` entry (an `id:identifier` pair or `*`), not the OAuth client? | yes |
| Q27 | Keep `/oauth/authorize` per-client 429 in the **platform** envelope while `/oauth/token` per-client 429 uses the **RFC** envelope? | yes |
| Q28 | Keep `/oauth/revoke` ignoring `token_type_hint` and only ever revoking refresh tokens (access tokens unrevocable)? | yes |
| Q29 | Keep `GET /auth/check-domain` legacy shape (`providerId` for any IdP type, `authorizationUrl = issuer + "/authorize"`)? | yes |
