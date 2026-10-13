# Java mirror of Go F16 and its follow-up: cookie-session CSRF, SPA serving, login CSRF, SSO binding, debug gate (2026-10-14)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` F16 and "F16 follow-up", decisions 23 and
24, `docs/wire-contract.md` ("Error envelope", "Authentication"); Go commits `94a4c79`
(SessionCSRF), `ff9e495` (SPA handler), `91bc61c` (docs), `6eb4b0f` (CredentialCSRF), `2cdff75`
(SSO login binding), `780e406` (debug gate), `91c355f` (docs). Predecessor:
`2026-10-14-platform-bug-fixes-6.md`. Every commit is on `main`, not pushed.

## Commits

| Commit | Change |
|---|---|
| `38b94bfb` | 1. Cookie-session writes: provenance and JSON only (`SessionCsrf`, `Exchange.host()`). |
| `2d7c4b63` | 2. SPA serving (`Frontend`): API 404s, 405s, asset 404s, ETag/304, cache and security headers; `Routes.head`. |
| `951db7b4` | 3. Login CSRF: the public sign-in routes (`CredentialCsrf`, shared `Provenance`) and the route-decision test. |
| `e31f0e62` | 4. SSO callback bound to the browser that started it (`LoginBinding`). |
| `c2709781` | 5. Diagnostics (this port's debug surface) refuse cross-site and same-site browser fetches. |
| (this file) | Ledger. |
| `092920cb` | Follow-up: CORS never grants credentials (section at the end). |

## Observed before the fixes (Java, empirically)

The composed platform (`Server` + `Platform`, embedded Postgres, the embedded SPA, the deployed
cookie `__Host-fc_session` from a real `/auth/login` of a seeded super-admin), with the cookie,
`Sec-Fetch-Site: same-site` and `Origin: https://evil.example.com` (a sibling subdomain, which Lax
does not block), unless noted:

| Request | Answer |
|---|---|
| `POST /bff/roles/permissions`, JSON body as `text/plain` | **201**, permission created |
| same, `application/x-www-form-urlencoded`; `multipart/form-data`; no `Content-Type`; JSON | **201** each |
| `POST /api/clients`, no `Content-Type` / `text/plain` / form | **201** each (Go refused `text/plain` and form here, huma; **Java decodes JSON from any body on every route**) |
| `POST /api/clients`, JSON, `Sec-Fetch-Site: cross-site` + `Origin: https://attacker.test` | **201** |
| same, foreign `Origin` without `Sec-Fetch-Site`; neither header | **201**, **201** |
| `POST /api/clients/{id}/activate`, no body | **200** "Client activated" |
| `PUT /api/clients/{id}`, `text/plain` | **204** |
| `DELETE /api/clients/{id}`, no body | **204**, client gone |
| `POST /auth/login`, the credentials as `text/plain`, cross-site | **200** `{"status":"ok",…}` (login CSRF) |
| `GET /api/clients/{id}/suspend?_method=POST` + `X-HTTP-Method-Override: POST` | the SPA shell, 200: **no method override is honoured** |
| SSO: `GET /auth/oidc/callback?state=…&code=…` from a client that never saw the start | completes (302, session cookie): every pre-existing `OidcBridgeTest`/`PortalSsoTest` callback ran that way |
| Diagnostics, dev-mode Basic credentials + `Sec-Fetch-Site: cross-site` | **200** |
| `GET /`, `/index.html`, client routes | 200 shell, `no-cache, no-store, must-revalidate`, no ETag |
| `GET /assets/index-OLDHASH.js`, `/favicon.ico`, `/robots.txt` | **200 shell** (`text/html`) |
| `GET /api/does-not-exist`, `/bff/x`, `/oauth/x`, `/.well-known/x`, `/control/x`, `/q/x`, `/apiary` | **200 shell** |
| `GET /api/clients/{id}/activate` (a POST-only route) | **200 shell** |
| `POST /api/does-not-exist`, `DELETE /bff/x`, `POST /clients/123` | 404 `{"error":"NOT_FOUND","message":"Not found"}` (the adapter's own 404) |
| `HEAD /` | 404 (no HEAD routes at all) |
| every fallback response | no `X-Content-Type-Options`, `X-Frame-Options`, CSP or `Referrer-Policy` |

**Not exploitable** (checked): no state-changing GET (232 GET registrations reviewed by handler
name; the non-obvious ones read only, except `GET /auth/oidc/session/end`, RP-initiated logout by
design, and `GET /oauth/authorize`, the OAuth flow); no method override; non-simple methods and
JSON bodies need a preflight — **but see "Found, not changed": Java, unlike Go, has a CORS filter
that grants credentialed preflights to allow-listed origins**. The router API and the diagnostics
never read the session cookie (bearer, or Basic in dev mode; pinned by
`SessionCsrfIntegrationTest.theDiagnosticsDoNotAcceptTheSessionCookie`, 401). There is no `/ui`
surface in this port.

## 1. Cookie-session writes (`38b94bfb`, Go `94a4c79`)

`SessionCsrf` (`platform/shared/auth`), a `before` filter right after the authenticator and before
the profile-only gate (`Platform.register`). For a POST, PUT, PATCH or DELETE whose context the
session cookie authenticated (`AuthContext.viaSessionCookie()`, the credential the authenticator
already stamped; Go needed a new context flag):

1. `Sec-Fetch-Site` `same-origin`/`none` passes; another value passes only with a trusted
   `Origin`; without it, `Origin`'s authority must equal the request's `Exchange.host()` (new:
   `Host`, or `:authority` over HTTP/2) or be trusted. Trusted = `FC_JWT_ISSUER`'s
   `scheme://authority` (`SessionCsrf.publicOrigins`, Go `publicOrigins`; a non-http(s) value is
   dropped with a warning). Neither header: refused. **403 `CROSS_ORIGIN_REQUEST`**, warn-logged.
2. A body (non-zero `Content-Length`, chunked, or bytes buffered without a length) must be
   `application/json` or `application/*+json` (a small `mime.ParseMediaType`-like parser: malformed
   parameters, duplicates, unclosed quotes are not JSON). **415 `UNSUPPORTED_MEDIA_TYPE`.**

Not applied: safe methods, bearer (the bearer wins when both are sent), test headers, a stale
cookie, every `isPublicPath` route (login, 2FA, reset, the dispatch callbacks), and `/oauth/*`,
`/.well-known/*` — which this port's authenticator *does* run for (Go mounts them outside its
authenticated group), so they are exempted by prefix.

**Java-only exception** (decision for the owner): `PUT /api/functions/{address}/artifacts/{digest}`
also accepts `application/octet-stream` and `application/wasm` from the cookie. This port's SPA
uploads function artifacts from the Publish drawer (`frontend/src/api/functions.ts`
`uploadArtifact`, `application/octet-stream`, in the embedded build); Go's ruling 2 ("the SPA does
not upload artifacts") does not hold here and the JSON rule would have broken the drawer.
Provenance still applies, and neither type is CORS-safelisted.

**Tests**: `SessionCsrfTest` (the matrix through the real listener: every value of
`Sec-Fetch-Site`, trusted origins with and without it, a different port, `Origin: null`, every
unsafe method with and without a body, 15 media types, chunked bodies, bearer/test-header/
in-process/no context, `/oauth`, `/.well-known`, the upload exception); `SessionCsrfIntegrationTest`
(composed platform, real `__Host-fc_session`: sibling, cross-site, Origin-only, no-header refusals
on BFF and API routes, body-less activate, PUT, DELETE, each asserting nothing was written; a
role-less user gets `CROSS_ORIGIN_REQUEST`, not `NO_PLATFORM_ROLE`; same-origin, `none`,
Origin-only, rewritten `Host` with the public origin (raw socket) allowed; 415 on both route kinds;
`+json`; the SPA upload passes; bearer with a foreign Origin, without headers, beside the cookie,
and with a non-JSON body allowed; `/oauth/token` cross-site with the cookie 200; an invalid cookie
answered `UNAUTHENTICATED`). Each failed before the fix (the guard unwired).

**Mutants** (each killed): neither-header allowed; applied to bearer; no JSON rule; the
authenticator stamping `BEARER_TOKEN` for the cookie; stamping `SESSION_COOKIE` for a bearer; no
`/oauth` exemption; no Origin-vs-Host; trusted origin ignored (with and without `Sec-Fetch-Site`);
unknown length treated as no body; `same-site` passing; the guard after the profile gate; the upload
exception removed; the upload exception on any route.

## 2. SPA serving (`2d7c4b63`, Go `ff9e495`)

`Frontend` is now registered for GET, HEAD, POST, PUT, PATCH and DELETE on `/` and `/<path>`
(`Group.NO_DB`, last), and mirrors Go's handler: API prefixes (`api`, `bff`, `oauth`,
`.well-known`, `control`, `q`, whole first segment) → 404 `{"error":"NOT_FOUND","message":"No route
for GET /api/x"}`, `no-store`, any method; other methods → 405 `Allow: GET, HEAD`; an existing file
→ its MIME type, a content-hash `ETag` (first 16 bytes of SHA-256, hex, quoted; cached per file),
304 on `If-None-Match` (`*`, lists, `W/`), `/assets/*` immutable, other files `no-cache`; a missing
file under `assets/` or with an asset extension (Go's allow-list) → 404 `text/plain` `no-store`;
anything else → `index.html`, `no-cache` + ETag. Every fallback response: `nosniff`,
`Referrer-Policy: same-origin`, `X-Frame-Options: DENY`, `Content-Security-Policy:
frame-ancestors 'none'; object-src 'none'; base-uri 'self'`.

`Routes.head(...)` is new (the seam had no HEAD); the Vert.x adapter answers a HEAD with the
handler's status, headers and `Content-Length`, no body (`VertxExchange.write`).

**Tests**: `FrontendTest`, rewritten (10 tests). **Mutants** (each killed): no API branch; prefix
by `startsWith` instead of segment; no asset 404; `no-store` index; no ETag; no 304; no
`X-Frame-Options`; no 405; no HEAD route; HEAD writing the body.

## 3. Login CSRF (`951db7b4`, Go `6eb4b0f`)

`CredentialCsrf`, a `before` filter over the exact paths of `CredentialCsrf.ROUTES` (the same 15 as
Go), every unsafe request, authenticated or not: provenance as §1 but a request with **neither**
header passes; then JSON only, for every caller. The provenance check moved to `Provenance`, shared
by both guards (`check(..., refuseWithoutHeaders)`).

**Route decisions** (pinned by `CredentialCsrfIntegrationTest.everyPublicUnsafeRouteIsDecided`,
which walks the composed server's registrations and fails on an unsafe route outside `/api/` and
`/bff/` in neither list, and on a listed route that is not mounted). The Java route set is
identical to Go's:

| Route | Decision |
|---|---|
| `POST /auth/login` | JSON-only + provenance (mints the session cookie) |
| `POST /auth/2fa/verify`, `/auth/2fa/enroll/{totp,email}/confirm` | JSON-only + provenance (finish a login) |
| `POST /auth/2fa/challenge/email`, `/auth/2fa/enroll/{totp,email}/begin` | JSON-only + provenance (steps of the same flow) |
| `POST /auth/webauthn/authenticate/{begin,complete}` | JSON-only + provenance (passkey sign-in mints the cookie) |
| `POST /auth/password-reset/confirm` | JSON-only + provenance (sets a password; an INVITE confirm mints the cookie) |
| `POST /auth/password-reset/request`, `/auth/password-setup/request` | JSON-only + provenance (mint tokens, send mail) |
| `POST /portal/auth/{check-domain,login,password-reset}` | JSON-only + provenance (the portal password plane) |
| `GET /auth/oidc/callback` (and both SSO starts) | browser binding cookie (§4) |
| `POST /auth/check-domain` | left: read-only |
| `POST /auth/logout` | left: issues nothing; `GET /auth/oidc/session/end` clears the cookie by design |
| `POST /auth/refresh` | left: refresh token in the body, tokens in the JSON body, no cookie |
| `POST /oauth/token`, `/oauth/introspect`, `/oauth/revoke` | left: form-encoded by the spec, client-authenticated (PKCE for codes) |
| `POST /oauth/userinfo` | left: bearer access token |
| authenticated `/auth/*` writes (change-password ×2, client/switch, `2fa/methods/*` ×4 + DELETE, recovery-codes, trusted-devices DELETE, webauthn register ×2, credentials DELETE) | left: `SessionCsrf` covers the cookie; bearer exempt |
| `POST /control/functions/{events,heartbeat}` | left: function runner bearer |
| `/api/dispatch/process`, `/settled`, `/api/public/*` | outside the walk (`/api/`), as in Go: HMAC job token / public reads |

**Observed before**: `POST /auth/login` cross-site `text/plain` with valid credentials, 200
`{"status":"ok"}` (the test's assertion on line 190 failed with exactly that body, guard unwired).

**Tests**: `CredentialCsrfIntegrationTest` (composed platform): the route pin; every guarded route
refuses cross-site `text/plain`, cross-site JSON, sibling `text/plain`, foreign Origin only,
`Origin: null` (403, no session cookie), and `text/plain`/form/multipart/no `Content-Type` both
same-origin and from a non-browser (415); same-origin, `none`, Origin = host, Origin = public base
URL and no headers pass the guard; a real login refused cross-site/sibling (403) and same-origin
`text/plain` (415) with no cookie, allowed for the SPA, curl and Origin-only (200 + cookie), and
through a `Host`-rewriting proxy with the public origin (raw socket); check-domain and logout not
guarded. **Mutants** (each killed): unwired; neither-header refused; no JSON rule; no provenance;
portal login and passkey complete dropped from `ROUTES` (the route pin); logout guarded; every path
guarded.

## 4. SSO callback binding (`e31f0e62`, Go `2cdff75`)

`LoginBinding` (`auth/oidc`): `GET /auth/oidc/login` and `GET /portal/auth/oidc/login` set
`fc_sso_<16 hex of sha256(state)>` = state (`__Host-` and `Secure` when the session cookie is secure
— this port's `cookiesSecure`, i.e. not test-headers mode, where Go keys on its `CookieSecure`;
`HttpOnly`, `SameSite=Lax`, `Path=/`, `Max-Age=600`). `GET /auth/oidc/callback`, after consuming
the state and before the portal branch, requires it (constant-time) or answers **400
`LOGIN_NOT_STARTED_HERE`** "This sign-in was not started in this browser. Start the sign-in
again.", the state spent, no login-attempt row, warn-logged; it always appends an expiring
`Set-Cookie` for the binding.

**Tests**: `OidcBridgeTest.aCallbackThisBrowserDidNotStartIsRefusedAndItsStateSpent` (cookie
attributes; no cookie → 400 and nothing provisioned; the right browser then gets `INVALID_STATE`;
another login's cookie and a forged value refused; the right browser signed in with the binding
cleared), `theBindingCookieIsHostPrefixedAndSecureWhenSecure`, and `PortalSsoTest` (the portal
start sets it; a portal callback sets no `fc_session` and only expires the binding). The existing
bridge and portal SSO tests now keep the start's cookie like a browser; their failure-path
assertions already name their own error codes. **Mutants** (each killed): no check; presence-only
check; checked before the consume; not cleared; no `__Host-`; TTL 30 min; `SameSite=Strict`; no
cookie at the bridge start; no cookie at the portal start.

## 5. Debug endpoints (`c2709781`, Go `780e406`)

This port has no `/debug/pprof`; its debug surface is the diagnostics under the router prefix
(thread dump, JFR snapshot and events), guarded by the router API's guard: a platform bearer, or
Basic in dev mode, **never the session cookie**. Basic credentials a browser has cached are
ambient, and a cross-site fetch with them was answered 200. Now `DiagnosticsRoutes.refused`: after
authentication, `Sec-Fetch-Site` present and neither `same-origin` nor `none` → **403
`CROSS_ORIGIN_REQUEST`**, every credential. Go's second clause (a cookie-authenticated request
without `Sec-Fetch-Site`) has no cookie to apply to. Tests: `DiagnosticsRoutesTest.crossSiteFetchesAreRefused`
(Basic and bearer, cross-site and same-site 403 on two routes; same-origin, none and absent 200;
unauthenticated still 401). Mutants killed: no check; same-site allowed; `none` refused; absent
refused. The router API itself and its dashboard (Basic in dev mode, ambient too) are not changed.

## Divergences from Go

- **Java decoded JSON from `text/plain`/form bodies on every route** (Go's huma routes refused
  them). The rule closes both alike.
- **Artifact upload exception** (§1): Go refuses a cookie-authenticated artifact upload; Java
  accepts `application/octet-stream`/`application/wasm` on that one route because its SPA uploads.
- **`/oauth/*` and `/.well-known/*` exempted by prefix** in `SessionCsrf` (Java's authenticator
  runs for them; Go's does not).
- **Passkey sign-in**: in Go it sits in the authenticated group, so a request carrying a valid
  cookie and neither header is also refused by `SessionCSRF`; in Java the route is public, so only
  `CredentialCsrf` applies and such a request passes the guard (it must still be JSON).
- **Method mismatch on an API path**: Go answers `GET` to a POST-only API route 405 (`mountFallback`);
  this adapter answers every method mismatch with the 404 envelope (its own `vertx-listener.md`
  rule), so `GET /api/clients/{id}/activate` is 404 `NOT_FOUND` "No route for GET …". Non-GET
  unknown client routes now get 405, as in Go (they were the adapter's 404 envelope before).
- **`OPTIONS`** on an unknown path stays the adapter's 404 (no `Routes.options`); Go's handler
  answers 405.
- **No Range support** on SPA files (Go's `http.ServeContent` honours `Range`/`If-Range`); `ETag`
  and `If-None-Match` only.
- **SSO binding "secure"** follows the session cookie's decision (`!FC_AUTH_ALLOW_TEST_HEADERS`),
  not the request scheme.
- **Diagnostics**: only the `Sec-Fetch-Site` clause applies (no cookie on that surface).
- **`$schema`**: not applicable (no huma); not changed, as ruled.

## Behaviour changes for clients

- A cookie-authenticated write from anything but the platform's own pages: 403
  `CROSS_ORIGIN_REQUEST`; a script replaying a browser cookie must send `Origin: <platform URL>`
  (or `Sec-Fetch-Site: same-origin`) or use a bearer token. Non-JSON cookie bodies: 415.
- **A page on a CORS-allow-listed origin** (`/api/platform/cors`, Java-only filter) that used the
  platform cookie for writes is now refused; bearer callers are unaffected.
- Sign-in routes: form/`text/plain`/no-`Content-Type` bodies 415 for every caller; a foreign
  `Origin` 403; JSON without browser headers still works.
- SSO: a sign-in must start and finish in the same browser; one in flight across the deploy fails
  once (`LOGIN_NOT_STARTED_HERE`); every start sets one short-lived cookie.
- Unknown API paths 404 JSON (were 200 HTML); missing assets 404 (were 200 HTML); unsafe methods on
  client routes 405 (were 404 envelope); `HEAD` on SPA paths now 200 (was 404); the shell is
  revalidated (`no-cache` + ETag) instead of `no-store`.
- Diagnostics from another site's link or fetch: 403.
- **Likely broken, not changed, not run**: the Playwright e2e fixtures call the API through
  `page.request`/`APIRequestContext` (e.g. `e2e/fixtures/clientScoped.ts`, `twoFactor.ts`,
  `tests/tenancy.spec.ts`) with the browser's cookie and no `Origin`/`Sec-Fetch-Site`: those POST/PUT
  calls will now be 403. Remedy: send `Origin: <baseURL>` on those calls (or
  `Sec-Fetch-Site: same-origin`). The parity harness (`parity/.../Runner.java`) replays its cookie jar
  without either header; scenarios that write with the session cookie will now be 403 on both Go
  and Java. Neither was edited (outside the brief; the owner may prefer another remedy).

## Found, not changed

- **CORS with credentials** (Java-only `CorsFilter`): an allow-listed origin gets
  `Access-Control-Allow-Credentials: true`, so a same-site allow-listed page can still **read**
  cookie-authenticated GET responses. Go has no CORS filter. Writes are now covered by `SessionCsrf`.
  **Fixed in the follow-up below** (`092920cb`).
- **Router API Basic auth in dev mode** is ambient too (state-changing router routes, the
  dashboard); only the diagnostics got the provenance rule, as Go scopes it to its debug gate.

## Verification

- Targeted runs before each commit (with `-Dsurefire.timeout=600` for every mutant):
  `SessionCsrfTest` 6, `SessionCsrfIntegrationTest` 8, `CredentialCsrfIntegrationTest` 4,
  `FrontendTest` 10, `OidcBridgeTest` 25, `PortalSsoTest` 7, `DiagnosticsRoutesTest` 11, plus
  `AuthenticatorTest`, `ProfileOnlyGateTest`, `CookieSecurityIntegrationTest`,
  `PlatformAuthenticatedHandlerTest`, `PlatformCorsScopeTest`, `LoginApiTest`, `TwoFactorApiTest`,
  `ChangePasswordApiTest`, `PasskeyApiTest`, `ClientSelectionApiTest`, `OAuthProviderTest`,
  `AuthorityGatesEndToEndTest`, `VertxSeamContractTest`, `VertxListenerTest`: all green.
- **Full reactor, `mvn clean test` at `c2709781`**: **BUILD SUCCESS**, 0 failures, 0 errors (12:34 min):

  | Module | Tests | Skipped |
  |---|---|---|
  | server | 5,807 | 1 |
  | function-host | 485 | 2 |
  | fcdev | 274 | 0 |
  | sdk | 93 | 1 |
  | parity | 56 | 1 (`ParityRunTest` skips without a Go source) |
  | usecase | 46 | 0 |
  | function-api | 145 | 0 |
  | eventloop-check | 16 | 0 |

  The server has 26 more tests than at `64b2a617` (5,781): `SessionCsrfTest` 6,
  `SessionCsrfIntegrationTest` 8, `CredentialCsrfIntegrationTest` 4, `FrontendTest` +5 (5 → 10),
  `OidcBridgeTest` +2, `DiagnosticsRoutesTest` +1.

## Not verified

- No browser run: Chrome's `Sec-Fetch-Site`/`Origin` on a sibling-subdomain request, a Lax cookie
  on a cross-site `text/plain` form post's response, the `__Host-` SSO cookie on the IdP's redirect
  back, the SPA under the new headers (PrimeVue, Swagger tab, mermaid), the Publish drawer's upload,
  and HTTP/2 (`Exchange.host()` from `:authority`; a body without `Content-Length` over h2) — the
  tests run HTTP/1.1.
- No SSO against a real IdP, no deployed environment behind a proxy, the e2e and parity suites not
  run (see above). The Rust port was not checked.

## Follow-up: CORS never grants credentials (`092920cb`, Java-only)

**The finding** (above, "Found, not changed"). `CorsFilter` (`platform/cors/filter`, spec
`docs/spec/cors.md` §9) answered every allow-listed `Origin` with the origin echoed **and**
`Access-Control-Allow-Credentials: true`, preflights included. A page on a listed origin that is a
same-site sibling of the platform (Lax cookies reach it) could `fetch(url, {credentials:
"include"})`, send the user's `__Host-fc_session`, and read the answer. `SessionCsrf` refuses its
writes since `38b94bfb`; reads stayed open.

**What relied on credentialed CORS: nothing found.** Searched the server, `fcdev`, the frontend,
`e2e/`, `parity/`, the SDKs, `docs/` and `docs/go-mirror/`:

- The SPA is served by the platform and calls relative paths (`frontend/src/api/client.ts`,
  `credentials: "include"` on a same-origin URL); no build-time API base URL exists.
- In development Vite (`frontend/vite.config.ts`, port 4200) **proxies** `/api`, `/bff`, `/auth`,
  `/oauth`, `/.well-known` to the backend, so the browser sees one origin and never asks for CORS.
- Playwright (`e2e/`) and the parity harness drive the platform's own origin; neither adds an
  allow-list entry beyond the CORS admin page's own add/remove scenario.
- The TypeScript, Laravel and Java SDKs call server-side with bearer tokens; none uses browser
  credentials. `fcdev` seeds no CORS origin.
- History: the filter landed 2026-09-05 (`8c3ae31f`, spec `0b3b6df0`) on the owner's ruling that the
  stored allowlist should drive real headers (backlog: "CORS filter driven by the allowlist");
  "credentials allowed" was the orchestrator's own default, justified in the spec as "the SPA
  authenticates with the `fc_session` cookie" — which is true but same-origin, so it never needed
  the grant. Go stores the same table and emits no CORS headers at all.

So no dev-mode switch was added: there is no legitimate cookie-credentialed cross-origin caller to
keep. Should one appear (an SPA served from another port without the proxy), the spec now says it
must be an explicit dev-only flag, off by default, refused when the public base URL is `https`.

**The change.** `CorsFilter`:

- never sends `Access-Control-Allow-Credentials` (simple requests, error responses, preflights);
- keeps the allow-list for bearer callers: a listed `Origin` is still echoed on
  `Access-Control-Allow-Origin` and its preflight still answered 204 with the requested headers
  (`authorization` included);
- never reflects an `Origin` that does not match, never answers `*`;
- sends `Vary: Origin` on **every** platform-surface response, with or without an `Origin` (was:
  only when one was sent), appended to an existing `Vary`. The router prefix is still untouched.

**Tests** (failing first: 10 tests across the three classes failed on the unchanged filter):
`CorsFilterTest` (credentials absent on simple, preflight and wildcard matches; not-listed
variants — `null`, suffix, prefix, scheme, port, case, trailing `/`, empty — never reflected;
`Vary` on disallowed simple, disallowed preflight and no-`Origin` responses),
`PlatformCorsScopeTest` (no credentials through `Platform.cors`; `Vary` on a platform path without
an `Origin`, still none on the router prefix), and the new `CorsCredentialsIntegrationTest`
(composed platform, real `__Host-fc_session`, real service-account bearer, a real allow-list entry
added through the API: a cookie read from the listed sibling is answered with no credentials grant;
its preflight too; a bearer caller on the listed origin keeps its preflight and echoed origin; a
not-listed origin is neither reflected nor granted, its preflight 403; the same-origin SPA
unaffected).

**Mutants** (each killed, `-Dsurefire.timeout=300`): credentials restored on every match;
credentials on preflights only; any `Origin` reflected; `*` instead of the echo; `Vary` only with
an `Origin`; `Vary` dropped; `Vary` replacing an existing value; the preflight not stopping the
chain.

**Behaviour changes for clients**

- A browser page on an allow-listed origin that calls with `credentials: "include"` (or
  XHR `withCredentials`) now fails in the browser: the preflight lacks the credentials grant, and a
  simple GET's response is not exposed to the page. The server still answers the simple GET (CORS is
  a browser control). Such a page must use a bearer token (omit credentials).
- A bearer-token page on an allow-listed origin: unchanged.
- The platform SPA (same-origin, and the Vite proxy in dev), curl, SDKs: unchanged, except that every
  platform-surface response now carries `Vary: Origin`.

**Verification**: `CorsFilterTest` 10, `PlatformCorsScopeTest` 4, `CorsCredentialsIntegrationTest` 5,
`CorsAllowlistTest`, `CorsOriginApiTest` green; server module 5,814 tests, 0 failures. **Full reactor,
`mvn clean test` at `092920cb`: BUILD SUCCESS**, 0 failures, 0 errors (12:32 min): server 5,814 (1
skipped), function-host 485 (2), fcdev 274, sdk 93 (1), parity 56 (1), usecase 46, function-api 145,
eventloop-check 16.

**Go**: nothing to mirror — Go has no CORS middleware and answers no preflight, which is stricter.
If Go ever adds one, it must not send `Access-Control-Allow-Credentials` for this allow-list.

**Not verified**: no real browser run (Chrome's handling of a credentialed preflight without the
grant and of a non-exposed credentialed GET is per the Fetch standard, not observed); the e2e and
parity suites not run; no deployed environment. Function-host endpoints declare their own
`cors.allowCredentials` (`docs/functions.md` §6a); that is the function author's choice and is not
changed here.
