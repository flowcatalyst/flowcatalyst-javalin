# The router-config document is an authenticated API route (R3′, 2026-09-13)

Supersedes R3 of `docs/go-mirror/2026-09-12-dispatch-rulings.md` and the
"Where it is served" bullet of `docs/spec/deployed-dispatch.md` §3. Owner
rulings 2026-09-13: (1) drop the internal-listener + Service Connect design,
(2) the router authenticates with OAuth client credentials, (3) a new
built-in role `platform:router` carries the one permission it needs.

## 0. Why

R3 kept the document unauthenticated by serving it on the platform's
internal listener and reaching it through an ECS Service Connect alias. That
was a workaround for one property of one deployment's IaC (the fc-router task
role has no Secrets Manager access), and it made every organisation that
runs FlowCatalyst reason about the network exposure of port 9090 in its own
infrastructure. A credential means the same thing everywhere and fails
loudly when missing. Client credentials, rather than a static token, because
the platform already issues, rotates, revokes and audits OAuth clients, and
the MCP server already mints tokens this way (`TokenManager`).

## 1. Platform

- `GET /api/dispatch/router-config` is served on the **API listener**, under
  the platform's ordinary bearer authentication, gated by
  `Checks.requireAnchor` and `Checks.require(ac, DISPATCH_POOL_VIEW)`
  (`platform:messaging:dispatch-pool:view`). The document lists every
  client's queues, so a client-scoped principal never sees it. Same body as
  before (`RouterConfigDocumentBuilder`); platform mode only.
- The internal listener (`Metrics`) no longer serves it. `Metrics` takes no
  document builder.
- New built-in role **`platform:router`** ("Router", "Fetches the dispatch
  router configuration") with exactly `ADMIN_DISPATCH_POOL_READ`. Seeded
  like the other `PlatformRoles`. Anchor-only, not client-delegable.
- 401 without a token, 403 with a token lacking the permission, 404 for
  anything else under the prefix as today.
- **503 `DISPATCH_QUEUE_UNCONFIGURED`** (2026-09-14) when the dispatch-queue
  settings cannot be resolved — an SQS deployment without
  `FC_DISPATCH_QUEUE_PREFIX`, or without an account id / region. The
  settings are resolved **per request** (`RouterConfigApi.State` takes a
  supplier), never at wiring: the API tier publishes nothing, so it must
  boot regardless (owner ruling; the Go platform exited at boot on exactly
  this, staging 2026-09-14). The scheduler role keeps its eager refusal
  (`Server#schedulerPublisher`), since it would publish to nonsense names.
  The boot logs one WARN naming the reason.

## 2. Router

- New settings: `FC_ROUTER_CLIENT_ID`, `FC_ROUTER_CLIENT_SECRET`, both
  default `""`. Set together or not at all; one without the other is
  refused when the router's config source is built (`Router.configSource`),
  the same place the SQS prefix refusal lives for the publisher.
- The credential belongs to **the router's platform**, which the router
  already names: `FC_ROUTER_PLATFORM_URL` (the A-01 base URL). Credentials
  set without it are refused at the same place. The deployed router's
  `FLOWCATALYST_CONFIG_URL` lists several third-party (Integral) config
  services beside the platform's own document (`docs/deployments.md` §3),
  so "which URL gets the token" cannot be positional.
- `HttpConfigSource` takes an optional `TokenManager` plus the platform
  origin. Every request to a config URL whose **origin equals the platform
  URL's origin** carries `Authorization: Bearer <token>`; other origins are
  fetched as before, and the credential is never sent to them. The token is
  minted at `{FC_ROUTER_PLATFORM_URL}/oauth/token` by `TokenManager` (moved
  from `io.flowcatalyst.mcp` to `io.flowcatalyst.http.oauth`; the MCP code
  imports it from there).
- Consequence to accept knowingly: setting `FC_ROUTER_PLATFORM_URL` also
  turns on A-01 (settled siblings reported to the platform instead of
  released). That is the intended deployed behaviour whenever the router
  consumes the platform's own dispatch queues, and fcdev now runs that
  path too.
- A `TokenException` while minting is an attempt failure like any transport
  failure: logged with its cause on the first failure of a streak, then by
  reason; R-B keeps retrying. A `401` from the document invalidates the
  cached token so the next attempt re-mints; it is otherwise an ordinary
  `>= 300` attempt failure.
- Without credentials the router fetches unauthenticated, exactly as today —
  a platform-served URL then answers 401 on every attempt, which R-B retries
  and the CONFIGURATION warning reports.

## 3. fcdev

`DevBootstrap.bootstrapRouterCredentials(pool, dev)` runs after `seed` and
before `devEnv` builds the `Env`:

- Idempotent by client id `fcdev-router`: a `SERVICE` principal (scope
  `ANCHOR`, name "fcdev router", no client, no service-account row — Go's
  `cmd/fcdev/mcp_bootstrap.go` does the same for MCP) with the
  `platform:router` role, and a `CONFIDENTIAL` OAuth client with
  `grantTypes = [client_credentials]` linked to that principal. Direct jOOQ
  upserts in the `Seeder` style, no domain events.
- Every boot generates a fresh plaintext secret (`Secrets.generatePlaintext`,
  stored through `Secrets.hashedRef` with the app-key `Encryption`),
  overwrites the client's `secret_ref`, and sets `FC_ROUTER_CLIENT_ID` /
  `FC_ROUTER_CLIENT_SECRET` and `FC_ROUTER_PLATFORM_URL =
  http://localhost:<apiPort>` on the mutable dev env (`setDefault`, so an
  operator's own values win). Nothing is written to disk.
- `FLOWCATALYST_CONFIG_URL` defaults to
  `http://localhost:<apiPort>/api/dispatch/router-config`. The metrics port
  no longer matters; `--api-port 0` (ephemeral, integration tests) is the
  one case the port is not knowable before the bind, and then fcdev
  synthesises no URL and bootstraps no credentials — the router runs with
  no queues in that mode, as it did before.

## 4. Deployment

The router task needs `FC_ROUTER_CLIENT_ID` / `FC_ROUTER_CLIENT_SECRET` from
whatever secret mechanism the deployment uses, and `FLOWCATALYST_CONFIG_URL`
pointing at the platform's public API URL. The operator's workflow, entirely
through the existing UI/API: create an application for the router, provision
its service account (`POST /api/applications/{id}/provision-service-account`,
which yields the client id and the secret once), look the service account up
by code (`GET /api/service-accounts/code/app:{applicationCode}`) and assign
`platform:router` to it (`PUT /api/service-accounts/{id}/roles`; the
principals route refuses non-`USER` principals with `NOT_A_USER`). No
Service Connect alias, no internal-listener exposure.

**Gate detail found while building (2026-09-13):** at the time this route was
built, `Checks.require` granted every anchor-scoped caller every permission
(Go `auth.go:409` does the same), and a provisioned service account is
anchor-scoped, so the usual gate would have passed any application's service
account with no `platform:router` role at all. `RouterConfigApi` checked
`AuthContext#hasPermission` directly after `requireAnchor` to work around it.
That anchor bypass is now withdrawn platform-wide (owner ruling 2026-09-13,
`docs/spec/permissions-from-roles.md`) — permissions always come from roles,
at every tier — so the workaround is gone too: `RouterConfigApi` is back to
the ordinary `Checks.requireAnchor` + `Checks.require(DISPATCH_POOL_VIEW)`
gate, same as any other anchor-only, permission-gated route.

## 5. Tests that must exist (each with a killed mutant)

1. `RouterConfigEndpointTest`: the route answers 200 on the API listener to a
   client-credentials token whose principal holds `platform:router` and the
   body round-trips through `RouterConfig`; 401 with no token; 403 with a
   token whose principal lacks the permission (a provisioned application
   service account); and the route is **absent from the internal listener**
   (inverting today's assertion).
2. `HttpConfigSourceTest` (or `RouterConfigSourceTest`): with a token manager,
   the request to the first URL's origin carries the bearer header and a
   request to another origin does not; a 401 invalidates the cached token
   (the second attempt mints again — count the token-endpoint hits); a
   minting failure is an attempt failure, not an exception out of `fetch`.
3. `PlatformRolesTest`/`SeederTest` (whichever pins the role catalogue): the
   role exists with exactly the one permission.
4. `EnvTest`: one credential without the other is refused.
5. fcdev: `StartIntegrationTest` (router off) still passes; the Java e2e is
   the integration proof that fcdev's router fetches its document through
   the token (fcdev's log: zero failed attempts, a consumer for
   `platform-DEFAULT`).
6. Parity: a `dispatch/router-config` scenario (create application →
   provision service account → assign `platform:router` → client-credentials
   token → 200; the same for a service account without the role → 403; no
   token → 401), listed in `parity/surface.json`. Against Go these DIFF
   until Go mirrors; allow-listed with this ruling as the reason, as will
   the role listings that now include `platform:router`.

## 5a. Startup order (consequence, 2026-09-13)

With the document and `/oauth/token` on the API listener, fcdev's router
would race its own API bind on every boot: the first fetch fails with
connection-refused and the queues arrive on the 5 s retry. So the router is
now **built** before the listeners (its HTTP surface is mounted on the API
app) and **contends for leadership only after both listeners are bound** —
Go's own order in `run.go`. `RouterStartupOrderTest` pins it with a 3 s
window on the platform's default consumer, which a failed first attempt
cannot meet.

## 6. Go mirror

Go has not started §3; it builds from this spec instead of R3. Its MCP
bootstrap already shows the direct-insert bootstrap shape.
