# Cookie hardening (owner, 2026-09-24)

Today (verified): two cookies. `fc_session` — `Path=/`, `HttpOnly`, `SameSite=Lax`, `Max-Age` =
session TTL, `Secure` when `FC_AUTH_ALLOW_TEST_HEADERS` is false (`SessionCookie`,
`Platform`: `cookiesSecure = !env.authAllowTestHeaders()`). The remember-device cookie —
`__Host-fc_td` when secure, `fc_td` otherwise, `SameSite=Strict` (`TrustedDeviceCookie`).
Readers of the session cookie: `Authenticator.extractToken` (static, `SESSION_COOKIE`),
`OAuthAuthorizeApi` (`SessionCookie.NAME`), and three `WWW-Authenticate: Cookie realm="…"`
strings (`LoginApi`, `TwoFactorApi`, `ChangePasswordApi`). No client reads it (the SPA cannot — it
is `HttpOnly`; the TS SDK's `fc_session` default is a consuming app's own cookie, not ours).

Three changes, one unit.

## 1. A deployed server refuses test headers

`FC_AUTH_ALLOW_TEST_HEADERS=true` lets a caller act as any principal through the test-principal
header — an authentication bypass, and it also turns `Secure` off. The **fc-server entry point**
refuses to start when it is true and `FLOWCATALYST_DEV_MODE` is not true: exit non-zero with one
log line naming both variables, before binding anything. fcdev is unaffected (it is dev by
definition; find where fcdev boots the server and make sure the check is on the fc-server path
only, not in the shared `Server`/`Platform` composition). A test that boots the fc-server path
with test headers sets dev mode.

## 2. The secure decision is pinned end to end

A test boots the composed platform with the default environment (test headers off) and logs in
through the real login route: the session `Set-Cookie` carries `__Host-fc_session=`, `Secure`,
`HttpOnly`, `SameSite=Lax`, `Path=/`, and no `Domain`. The same test with test headers on (and dev
mode): `fc_session=`, no `Secure`. Mutant: invert `cookiesSecure` in `Platform` — the test fails.
(The login route may not be the shortest path; any real route that mints the session cookie
through the composed server will do — say which in the report.)

## 3. `__Host-fc_session` when secure

- `SessionCookie` owns the name: `__Host-fc_session` when secure, `fc_session` otherwise (constants
  beside `TrustedDeviceCookie`'s). `SessionCookie.NAME` goes; callers ask the instance.
- **Cookie security is its own setting**, no longer implied inside `Authenticator` by the
  test-headers flag: `Authenticator.Config` carries the session cookie name (or a
  `SessionCookie`), set by `Platform` from the same `cookiesSecure` value as `SessionCookie` —
  one decision, passed to both. `extractToken` reads exactly that one name. **In secure mode the
  plain `fc_session` is never accepted** (accepting it would let a subdomain plant a session and
  defeat the prefix).
- `OAuthAuthorizeApi` reads the name from its `SessionCookie`; the `WWW-Authenticate` realms name
  the instance's cookie.
- `clear()` in secure mode expires `__Host-fc_session` **and** a leftover `fc_session` (`Path=/`,
  `Max-Age=0`), so the old cookie does not linger after the switch. Existing sessions end once:
  users sign in again after the deploy (owner accepted).
- Tests: every test that sends the session cookie uses the name for its mode (no literal
  `"fc_session"` in a test that runs secure). e2e and parity run fcdev (insecure) and keep
  `fc_session`.

Tests (mutant each): an `Authenticator` in secure mode authenticates `__Host-fc_session=<valid>`
and **rejects** `fc_session=<the same valid token>` (mutant: read both names); insecure mode the
reverse; logout in secure mode emits both expiries (mutant: drop the legacy one); the §2 test.

## 4. Cookie-session writes: provenance and JSON only (Go F16, 2026-10-14)

`SameSite=Lax` keeps the cookie off cross-site POSTs, not off a sibling subdomain's (same-site).
`SessionCsrf` (`platform.shared.auth`, Go `middleware.SessionCSRF`), a `before` filter right
after the authenticator and before the profile-only gate, applies to a POST, PUT, PATCH or
DELETE whose context the session cookie authenticated (`AuthContext.viaSessionCookie()`), except
under `/oauth/` and `/.well-known/` (outside Go's authenticated group):

1. `Sec-Fetch-Site` `same-origin` or `none` passes; another value passes only with a trusted
   `Origin`. Without it, `Origin`'s host must equal the request's `Exchange.host()` (`Host` or
   `:authority`) or `Origin` must be trusted. The trusted origin is the public base URL
   (`FC_JWT_ISSUER`'s scheme and authority), for a proxy that rewrites `Host`. Neither header:
   refused. Refusal: **403 `CROSS_ORIGIN_REQUEST`**, logged (warn).
2. Then a request with a body (non-zero or unknown length) must be `application/json` or
   `application/*+json` (a malformed or absent `Content-Type` is not): **415
   `UNSUPPORTED_MEDIA_TYPE`**. Java only: `PUT /api/functions/{address}/artifacts/{digest}`
   also takes `application/octet-stream` and `application/wasm`, which this port's SPA (the
   Publish drawer) sends with the cookie.

Not affected: bearer requests (the bearer wins when both are sent), test headers, a stale cookie,
every `isPublicPath` route (login, 2FA, password reset, the dispatch callbacks), safe methods.
Tests: `SessionCsrfTest` (the matrix), `SessionCsrfIntegrationTest` (a real `__Host-fc_session`
through the composed platform). Ledger: `docs/go-mirror/2026-10-14-platform-bug-fixes-7.md`.

## Docs

`docs/spec/auth-core.md` §6.1 cookie attributes; `docs/deployments.md` (the refusal, the one-time
sign-out on deploy); backlog/STATUS.
