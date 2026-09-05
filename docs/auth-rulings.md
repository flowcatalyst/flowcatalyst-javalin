# Auth rulings — the three batches that gate Phase 3

Written 2026-09-05 by the orchestrator. Phase 3 (`docs/port-plan.md`) is
gated on the owner's yes/no answers to the open questions in
`docs/spec/auth-core.md` §19 and `docs/spec/auth-identity.md` §18–§19.
The standing rule is **no ruling → keep Go's behaviour**, so a batch can be
answered with just the numbers that differ from the recommendation.

Each row: my recommendation, the reason in one line, and what happens if
you say nothing. "Fix" recommendations follow the rulings already given on
Q5, Q10, Q11, Q12 and Q15 (correctness over conformance, and a defect Go
still carries is recorded in `docs/backlog.md`, never silently reproduced).

Answer format that unblocks a batch fastest: `A: all as recommended except
Q18 keep, I-5 keep`.

---

## Batch A — token issuance and sessions (unblocks the first auth cut: JWT/RS256, PKCE, refresh rotation, session cookie, `/auth/login`, MFA §6)

| # | Question | Recommend | Why | Unanswered ⇒ |
|---|---|---|---|---|
| C-Q1 | `auth_time` = ID-token `iat` rather than the real login time | keep | RPs only use it for `max_age`; Go behaviour is deterministic | keep |
| C-Q2 | `email_verified: true` whenever an email exists | keep | changing it needs a verified flag the schema lacks | keep |
| C-Q3 | Write `PendingAuth:{state}` rows nothing reads | keep | storage compat with a running Go instance | keep |
| C-Q5 | Skip per-client rate buckets for Basic-only clients | **fix** (rate-limit) | your 2026-08-24 leaning; Basic evades the client bucket otherwise | fix (leaning recorded) |
| C-Q6 | `client_credentials` reads credentials from the body only | **fix** (route through `authenticateClient`, Basic accepted) | discovery advertises `client_secret_basic`; RFC 6749 §2.3.1 | keep (Go) |
| C-Q16 | Treat `RefreshTokenExpirySecs`/`SessionTokenExpirySecs` config as dead (7 d / 24 h fixed) | keep | nothing reads them in Go; Java will not add a knob Go ignores | keep |
| C-Q17 | Two different "standard scope" sets (authorize excludes `address`/`phone`) | keep | wire-visible; unify in Go first if wanted | keep |
| C-Q18 | Drop the three caller-less rate buckets | **drop** | dead storage keys | drop |
| C-Q19 | Any non-empty HS256 secret accepted | **fix** (≥ 32 bytes) | HS256 under a short secret is a real weakness; only affects no-RSA deployments | keep |
| C-Q20 | Empty grant list ⇒ every grant allowed | keep | legacy clients depend on it | keep |
| C-Q21 | Lenient `clientType` parse (unknown ⇒ PUBLIC) on create | **fix** (400) | X-06 spirit: unknown enum values fail loudly; PUBLIC is the *less* safe default | keep |
| C-Q22 | Implicit `state` cap ≤ 116 chars from `VARCHAR(128)` | keep, but reject with `invalid_request` before the insert | a 500-redirect for a long state is a defect either way | keep |
| C-Q23 | Fail-open on rate-limit backend errors and ignored backoff errors | keep fail-open for rate limits; **fail-closed for backoff** | a backoff store error during a brute force must not disable the lock | keep |
| C-Q24 | `/auth/me` emits `"status":""` | keep | SPA tolerates it; cosmetic | keep |
| C-Q25 | `/oauth/authorize` prefers cookie over Bearer while middleware prefers Bearer | keep | the SPA path relies on the cookie winning | keep |
| C-Q26 | Introspect `client_id` = first `clients` entry, not the OAuth client | keep | consumers exist | keep |
| C-Q27 | Per-client 429 envelope differs between authorize (platform) and token (RFC) | keep | each matches its endpoint's family | keep |
| C-Q28 | `/oauth/revoke` only revokes refresh tokens | keep | access tokens are short-lived and stateless | keep |
| I-Q5 | Session-mint failure as plain-text 500 | **fix** (envelope 500) | consistent with your Q10 ruling | keep |
| I-Q21 | `GET /auth/2fa/trusted-devices` items on Go-default time + `principalId` | **align** to `httpcompat.Time` shape | one time format on the wire | keep |
| I-Q23 | Portal password users get no second factor | keep (record) | design choice, not a defect | keep |

MFA is in this batch's cut by ruling (`auth-identity.md` §6). Its own
questions are I-Q11, I-Q12 and the defects 7, 9, 10 — listed under Batch C
but I will take them in Batch A's order if you answer them here.


## Rulings — Batch A (owner, 2026-09-05, asked one by one)

Already ruled before this session and therefore not re-asked: C-Q5 (A-14), C-Q6 (A-13), C-Q21 (X-06 — reject unknown `clientType`).

- **C-Q1** — RULED 2026-09-05: real login time (the cookie's issue time), following Go's later fix.
- **C-Q2** — RULED: keep, always `true`.
- **C-Q3** — RULED: keep writing it (storage compat).
- **C-Q16** — RULED: dead — fixed 7 d / 24 h.
- **C-Q17** — RULED: keep both sets as Go.
- **C-Q18** — RULED: keep the names; **backlog**: wire policies and callers so introspect, revoke and check-domain are actually rate-limited.
- **C-Q19** — RULED: require ≥ 32 bytes; startup fails with a clear message.
- **C-Q20** — RULED: **empty grant list ⇒ no grant allowed** (fail closed). Cutover: existing rows with an empty list stop minting until their grants are set — data migration / seeder + `fcdev init` set grants explicitly; Go's behaviour recorded as a defect in `docs/backlog.md`.
- **C-Q22** — RULED: reject a `state` > 116 chars up front with `invalid_request`; the cap stays.
- **C-Q23** — RULED: rate-limit store fails open; backoff store fails **closed** (deny the login, 503). *Scope note (owner, later the same day):* the backoff store is the platform database, so this adds no new hard dependency; the residual failure is a missing `iam_login_attempts` partition. Phase 3 therefore also ships a readiness check that the current and next quarterly partitions exist, and a counter for backoff-store errors that alarms on the first one.
- **C-Q24** — RULED: populate `status` with the principal's real status (the field already exists, so no reader breaks); verify the SPA/SDK types treat it as optional.
- **C-Q25** — RULED: keep both orders as Go.
- **C-Q26** — RULED: **RFC 7662** — `client_id` is the OAuth client that minted the token. Verified 2026-09-05: no caller of introspection in InhanceMono (`apps`, `packages_root/packages`) or any SDK; the Go SDK's `IntrospectToken` has no callers; the tenant pair stays in the token's `clients` claim.
- **C-Q27** — RULED: RFC 6749 error shape for the 429 on **both** `/oauth/authorize` and `/oauth/token`; **backlog**: update the SDKs/SPA error parsing.
- **C-Q28** — RULED: keep refresh-token-only revocation for now. **Backlog (designed option)**: an access-token denylist, cache-first with the table as the fallback store; the one-hour access TTL bounds its value.
- **I-Q5** — RULED: envelope 500 as Q10 (fixed message, cause logged).
- **I-Q21** — RULED: align to the platform `Time` shape; drop `principalId`.
- **I-Q23** — RULED: keep — no portal-plane MFA in this cut (product decision, recorded).
- **I-Q11** — RULED: **trusted-device "remember" exists only for internally managed identities (password/passcode), never for external-IdP domains (structural, not a default); off by default; on only by explicit domain policy; the policy change, each enrolment and each revocation are audit-logged.** Check Go's stored `RememberEnabled` default before cutover so existing rows keep their value and only new mappings start off.
- **I-Q12** — RULED: enforce the domain's allowed-method list at verify too.

## Batch B — OIDC bridge and the portal plane (`/auth/oidc/*`, `/portal/*`, check-domain, JIT + role sync)

| # | Question | Recommend | Why | Unanswered ⇒ |
|---|---|---|---|---|
| I-Q1 | Refresh the cached OIDC client when the IdP row changes | **yes** | secret rotation without a restart | no (restart) |
| I-Q2 | Empty-string `email_domain_mapping_id` as the provider-direct marker in storage | keep | schema compat; sealed mode in memory only | keep |
| I-Q3 | Leak the library's verification error text in `OIDC_VERIFY` | **fix** (fixed message, log the cause) | error text from the verifier is an oracle | keep |
| I-Q4 | Provider-direct IdP with no mapped domains accepts any account at that IdP | keep, documented | single-tenant deployments rely on it | keep |
| I-Q6 | JIT fails `CLIENT_REQUIRED` when a CLIENT/PARTNER mapping has no `primaryClientId` | **fix** (refuse at mapping creation) | `authadmin` is already ported; the check belongs there | keep |
| I-Q7 | Keep the portal-less `GET /auth/oidc/login?provider_id=` path | keep | employee-plane SSO uses it | keep |
| I-Q8 | Every `allowedRoleIds` dangling ⇒ reject all roles | keep | fail-closed | keep |
| I-Q9 / C-Q29 | Keep GET `/auth/check-domain` legacy shape with the guessed `authorizationUrl` | **drop the guess** (omit `authorizationUrl` when unknown), keep the rest | defect 14 | keep |
| I-Q10 | Consume the portal flow at SSO start (no retry after IdP failure) | **fix** (consume at the callback sink) | password path already does this | keep |
| I-Q16 | Notification default brand → `FlowCatalyst` | yes | cosmetic | keep |
| I-Q18 | 15-minute portal flow TTL vs 10 for OIDC state | keep | harmless | keep |
| I-Q22 / defect 12 | Unify the system actor spelling in `aud_logs` | **`"system"`** | two spellings break audit queries | keep |
| defects 2, 3, 4 | Dead code in `LoginStateRepo`, unreachable `IDP_NOT_EXTERNAL`, dead finders after atomic consume | not ported | dead code is not behaviour | — |


## Rulings — Batch B (owner, 2026-09-05, asked one by one)

C-Q29 is ruled with I-Q9. Defects 2, 3 and 4 are dead code and are simply not ported (no question).

- **I-Q1** — RULED 2026-09-05: yes — refresh the cached OIDC client when the identity-provider row changes (same-node invalidation + bounded TTL, the CORS allowlist pattern).
- **I-Q2** — RULED: keep the empty-string storage marker; sealed mode in memory only.
- **I-Q3** — RULED: fixed `OIDC_VERIFY` message; cause logged with the correlation id.
- **I-Q4** — RULED: keep — a provider-direct IdP is an external directory whose admins already decide who exists; documented.
- **I-Q6** — RULED: refuse a CLIENT/PARTNER mapping without `primaryClientId` at creation (authadmin); JIT keeps the check as a defence.
- **I-Q7** — RULED: keep `/auth/oidc/login?provider_id=` for employee-plane (dashboard) users — the entry the I-Q4 case depends on.
- **I-Q8** — RULED: keep — all-dangling `allowedRoleIds` rejects all roles (fail closed).
- **I-Q9** — RULED (with C-Q29): keep the `check-domain` shape, **omit `authorizationUrl`** when it is not actually known (never fabricate `issuer + "/authorize"`).
- **I-Q10** — RULED: consume the portal flow at the callback sink, like the password path (an IdP failure leaves it retryable within its TTL).
- **I-Q16** — RULED: fallback brand `FlowCatalyst` (Go: `Flowcatalyst`, `notify/notify.go:43`).
- **I-Q18** — RULED: keep 15 min portal flow / 10 min OIDC state.
- **I-Q22** — RULED: unify the system actor on `"system"` for new rows; existing rows untouched.

## Batch C — passkeys, MFA details, password reset and approvals

| # | Question | Recommend | Why | Unanswered ⇒ |
|---|---|---|---|---|
| I-Q11 / defect 7 | Align `rememberAllowed` with `RememberEnabled()` (require internal domain) | **fix** | trusted-device cookie for an external domain user is a policy leak | keep |
| I-Q12 / defect 9 | `/auth/2fa/verify` enforces the domain's allowed-method list | **fix** | otherwise the admin policy is decorative | keep |
| defect 10 | `DELETE /auth/2fa/trusted-devices/{id}` and `/methods/{m}` report success for nothing deleted | **fix** (404) | PR-3 oracle: 404 byte-identical to not-found | keep |
| I-Q13 / defect 1 | Persist the passkey sign counter and `last_used_at`; emit `passkey:authenticated` | **fix** | the counter is the clone-detection control WebAuthn specifies | keep |
| I-Q14 / defect 8 | Admin-sent reset tokens set `requires_factor` for TOTP users | **fix** | an admin link must not bypass the user's second factor | keep |
| I-Q15 / defect 15 | `Date` / `Message-ID` headers and RFC 2047 subject encoding | **fix** | deliverability; mail without them is spam-scored | keep |
| I-Q17 / defect 5 | Purge expired PINs / trusted devices / reset tokens / approvals | **yes** | your Q12 ruling (`auth-retention.md`, `iam_rate_limit_events`) extended to the four tables `auth-identity.md` §14 names; the Java purger now exists (`7391dbe`) and the auth sweeps hang off it | keep (unbounded growth) |
| I-Q19 | `RequireStrongFactorForReset = false` | keep | product decision; the approval queue stays dormant | keep |
| I-Q20 | `/auth/2fa/*` mounting as in Go | keep | lockfile-visible | keep |
| I-Q24 | `authenticate/begin` on huma's 429 shape | **align** to the `TOO_MANY_REQUESTS` envelope | one 429 shape per plane | keep |
| I-Q25 / defect 13 | Passkey events under source `platform:admin` | **fix** (`platform:iam`) | principal events live there | keep |
| defect 11 | `EXPIRED` approval status and `note` never written | **fix** | the column exists and the SPA shows it | keep |
| defect 6 | see I-Q5 (Batch A) | | | |

---

## What I will do without a ruling

Batch A can start on the "keep" rows immediately; the eight "fix" rows are
each a small, isolated deviation and are written so that flipping one back
to Go behaviour is a one-line change with a test. Nothing in Batch B or C
starts before its rulings, except the pure-storage and crypto scaffolding
(`webauthn_credentials`, MFA secret encryption, reset-token hashing) whose
behaviour no question touches.
