# SSO logins write login-attempt rows (owner ruling 2026-09-17)

Status: **ruled, implementing in both repos.**

## Why
Password, 2FA and passkey logins write `iam_login_attempts` rows; OIDC (SSO)
logins write none, so an SSO user's sign-ins are invisible on the
login-attempts page. The platform cannot see failures that happen **at the
IdP** (wrong password, MFA, abandoned) — the browser never returns — but it
does see, with certainty, the callback it accepts and the callbacks it
refuses. Record exactly those. "Initiated" is **not** recorded (owner
agreed: noise, and it would need a new outcome value).

## Rows

All rows: `attempt_type = USER_LOGIN`, `ip_address` = the same client-IP
helper password-login rows use (Java `ClientIp.of(ctx)`, Go
`ratelimit.ClientIP(r)`), `user_agent` = trimmed `User-Agent` header, blank →
null. Best-effort: a failed write is logged at WARN and never changes the
response. **Employee plane only** — a login state that routes to the portal
completion (`state.portal()` in Java; the Go equivalent) writes no row at
all, success or failure.

### Success
After the session token is minted, next to the `logged-in` emit
(`docs/spec/oidc-logged-in-event.md`): outcome `SUCCESS`, identifier = the
normalised email the callback uses, `principal_id` = the principal.

### Failures recorded
Only refusals of an identity the IdP sent us. Identifier is the **verified**
id token's normalised identifier where one exists — never a claim from an
unverified token.

| Callback refusal (Java code) | `failure_reason` | identifier |
|---|---|---|
| `OIDC_VERIFY` (signature / audience / issuer) | `SSO: id_token verification failed` | null |
| `NONCE_MISMATCH` | `SSO: nonce mismatch` | verified identifier |
| `NO_EMAIL` | `SSO: no email claim` | null |
| `EXTERNAL_GUEST` | `SSO: external guest account` | verified identifier |
| `EMAIL_DOMAIN_MISMATCH` (both branches) | `SSO: email domain not allowed` | verified identifier |
| `TENANT_MISMATCH` (both branches) | `SSO: tenant mismatch` | verified identifier |
| account provisioning refused (a 4xx `ProvisioningException`, e.g. `MAPPING_GONE`) | `SSO: account provisioning refused` | verified identifier |

Principal id is null on every failure.

### Not recorded
Missing `state`/`code`, unknown or expired state, IdP/config resolution
errors, code-exchange failure, IdP returned no id_token, repository errors,
session-mint failure — infrastructure or unauthenticated noise, not an
identity being refused.

Note (no action): backoff reads failures by identifier regardless of type,
so SSO failure rows count toward that email's password-login backoff. A row
with an identifier requires a token signed by the configured IdP, so this
cannot be used to lock out an arbitrary email; SSO-only users are already
refused at the password endpoint with "SSO required".

## Frontend (Java writes it; copied verbatim to Go)
`frontend/src/pages/platform/LoginAttemptListPage.vue`: under the existing
page subtitle, one short muted line: *"SSO sign-ins appear when the platform
accepts or refuses the identity provider's response. Failures at the identity
provider itself (wrong password, MFA) are only in that provider's logs."*
Match existing page styles; no new component.

## Tests — each must fail under its mutant
Use the existing fake-IdP callback harness (Java `OidcBridgeTest`, Go
`internal/platform/auth/bridge/oidc_login_event_pg_test.go`). Send
`X-Forwarded-For: 203.0.113.9, 198.51.100.7` and `User-Agent: fc-test/1.0`
on the callback. Assert on stored rows.

| # | Assert | Mutant |
|---|---|---|
| T1 | successful SSO login → exactly one `USER_LOGIN` `SUCCESS` row: identifier = email, principal id, ip `198.51.100.7`, UA `fc-test/1.0` | remove the success write |
| T2 | tenant mismatch (or email-domain mismatch — whichever the harness drives cheaply) → one `FAILURE` row, the reason from the table, identifier = verified email, principal null | remove that failure write |
| T3 | id token with a bad signature → one `FAILURE` row, reason `SSO: id_token verification failed`, identifier **null** | record the unverified token's email as identifier |
| T4 | unknown/expired state → **no** row | record on that branch |
| T5 | portal-flow state → no row (only if the harness can drive a portal state cheaply; otherwise say so) | drop the plane check |
