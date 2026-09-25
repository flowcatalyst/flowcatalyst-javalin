# Router API authentication with platform tokens

Owner ruling 2026-09-25 (`docs/backlog.md` §"Overnight review" item 2). Supersedes `router.md` §9.7
(BasicAuth) outside dev mode.

## Why

The deployed router (`../inhance/iac/compute/fc-router.ts`) sets `AUTH_MODE=NONE`, and it is
reachable through the internet-facing ALB. Anyone who knows its domain can call:

- `POST /messages`: a caller-chosen mediation target, which the router POSTs to from inside the VPC;
- `/api/seed/messages`;
- breaker resets, in-flight force-ACKs and pool updates;
- the mock endpoints.

## Rules

1. **Credential.** Outside dev mode (`FLOWCATALYST_DEV_MODE` false) every non-public router route
   needs `Authorization: Bearer <platform JWT>`. The token is verified locally against the
   platform's JWKS, using the same verifier the function host uses (now shared from `server`,
   package `io.flowcatalyst.platform.shared.auth.jwks`):
   - the issuer is discovered from `<platform>/.well-known/openid-configuration`;
   - an unknown `kid` refetches at most once per 30 s;
   - RS256 signature and expiry are checked;
   - `token_use` must be `api`: identity tokens and session-kind tokens are refused.

   `<platform>` is `FC_ROUTER_PLATFORM_URL`. When the platform runs in the same process and that is
   unset, it is `http://127.0.0.1:<FC_API_PORT>`. With neither, every protected route answers 401
   and startup logs a WARN.
2. **Authority.** Two permissions:
   - `platform:messaging:router:view` for every `GET`/`HEAD`, plus the in-flight checks done over
     `POST` (`/monitoring/in-flight-messages/check-batch`). The SDKs' stuck-message recovery calls
     these.
   - `platform:messaging:router:operate` for every other method: publish, breaker resets,
     in-flight ACK, group-flush clear, pool update, config reload, warning acknowledge, broker-stats
     refresh.

   A missing or bad token is 401 with `WWW-Authenticate: Bearer realm="FlowCatalyst Router"` and
   `X-Auth-Mode: BEARER`. A valid token without the permission is 403 `PERMISSION_REQUIRED`.
3. **Roles.**
   - New seed role `platform:router-operator` holds both permissions.
   - `platform:viewer` gains `:view`.
   - `platform:application-service`, the role application service accounts get, gains `:view`, so
     an application's SDK can still run the in-flight check. The applications are the operator's
     (trust model), so seeing router monitoring is acceptable.
   - `platform:super-admin` holds both through its wildcard.
   - `platform:router` stays the router's own identity for fetching config and does not grant
     calling the router.
4. **Public routes** need no credential:
   - `/health`, `/health/{live,ready,startup}`, `/q/health*`, `/metrics`, `/q/metrics` and
     `/ready`, as §9.7 already lists;
   - the dashboard page itself (`/dashboard.html`, `/monitoring/dashboard`), which carries no data;
   - its two sign-in helpers (rule 6).
5. **Dev mode** keeps §9.7 exactly: Basic auth when `FC_ROUTER_AUTH_USER` is set, open when unset
   or `AUTH_MODE=NONE`. Outside dev mode `AUTH_MODE`, `FC_ROUTER_AUTH_USER` and
   `FC_ROUTER_AUTH_PASS` are ignored, with a startup WARN naming each one that is set. The router
   still starts, so a deploy that still carries `AUTH_MODE=NONE` keeps moving messages.
6. **Dashboard sign-in** uses authorization code with PKCE against the platform.
   - `FC_ROUTER_DASHBOARD_CLIENT_ID` names a **public** OAuth client registered on the platform. It
     must have API access, the `platform` application, and the dashboard page's URL as a redirect
     URI.
   - `GET {prefix}/dashboard/auth-config` answers `{enabled, authorizationEndpoint, clientId}`. The
     authorization endpoint comes from the platform's discovery document, which is the browser's
     external URL. `enabled` is false when the client id or the discovery is missing.
   - `POST {prefix}/dashboard/token` `{code, codeVerifier, redirectUri}` forwards to
     `<platform>/oauth/token` (`grant_type=authorization_code`, the public client id, no secret) and
     answers only `{accessToken, expiresIn}`. The refresh token and id token are dropped. The router
     proxies because the browser page is on the router's origin, and a direct call would need CORS
     on the platform's token endpoint.
   - The page keeps the access token in memory only; the PKCE verifier and `state` sit in
     `sessionStorage` across the redirect. When the token expires, or the page reloads, it signs in
     again, which is instant while the platform session lasts.
7. **Mock routes are dev-only.** `/api/test/*`, `/api/benchmark/*` and `/api/seed/messages` are
   mounted only in dev mode. Elsewhere they are absent (404), not merely protected.
8. **SDKs.** The Java, TS and Laravel router resources send the same bearer token the SDK uses for
   the platform. Against today's open router this is harmless, so the SDK release must reach apps
   before a router enforcing rule 1 is deployed.

## Out of scope

- A refresh flow on the dashboard.
- Moving the dashboard into the SPA.
- Rate limiting on the router's API.
