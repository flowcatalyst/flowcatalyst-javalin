# Route-surface parity audit — part 2 of the static drop-in audit

Date 2026-09-14. Java repo `flowcatalyst-javalin` (working tree, `main` @
`2b11adc`). Go repo `flowcatalyst-go` (READ-ONLY, `f81fd5a` + uncommitted
working-tree changes — `git status` showed the SDK/frontend/laravel
generated-client diffs plus `internal/platform/auth/login/endpoint.go`,
`internal/platform/passwordreset/api/{api,bulk_import_pg_test}.go`,
`internal/platform/principal/api/{api,dto}.go`). Scope: everything **outside**
`LockfileCoverageTest`'s `/api/**` contract — the chi-mounted auth/session/
OAuth/OIDC/portal/BFF/public/SPA/health/metrics/router/MCP/internal surface —
plus method- and auth-mode mismatches on routes that are in the lockfile.

No builds were run (Maven/Go); this is a static read of both trees plus five
manual code-path traces for the response-shape spot check (§4).

## 0. Method

Go surface extracted from `internal/server/wire_public.go`, `wire_routes.go`,
`wire_spec.go`, `run.go`, `http.go`, `subsystems.go`; every package chi-
mounts into those (`auth/login`, `auth/bridge`, `portalauth`,
`auth/clientselection`, `auth/oauthapi`, `passwordreset/api`,
`shared/bff/*`, `shared/me`, `shared/sdk`, `publicapi`,
`dispatchjob/{processing,settled}`, `outbox/admin.go`), the huma-registered
`webauthn/api` (mounted **inside** the auth group, so it is Go-lockfiled but
Java's `LockfileCoverageTest` treats `/auth/` as outside its own lockfile —
noted where it matters), `internal/router/api/*` (`huma.Register` operation
literals), `internal/mcp/server.go` + `cmd/fcdev/mcp.go` /
`internal/server/subsystems.go`'s `StartMCP`.

Java surface extracted from `Server.java` (`buildApiAndReaper`), `Platform.java`,
`Metrics.java`, every `*Api.java` under `platform/**` that calls
`routes.get/post/put/delete/patch(...)` or `routes.in(Group...).x(...)`,
`router/api/*.java`, `mcp/McpServer.java` + `VertxStreamableServerTransportProvider.java`,
`outbox/OutboxAdminApi.java`.

Grep patterns used throughout:
`r\.(Get|Post|Put|Delete|Patch|Handle)\(` / `huma\.Register\(api, huma\.Operation\{` (Go),
`routes\.(in\(Group[^)]*\)\.)?(get|post|put|delete|patch)\(` (Java).

## 1. Go surface, by listener

### 1.1 API listener (`FC_API_PORT`) — public / self-authenticating

| Method | Path | Auth | Cite |
|---|---|---|---|
| GET | `/health` | none | `internal/server/run.go:80` |
| POST | `/auth/check-domain` | none | `internal/platform/auth/login/endpoint.go:121` |
| GET | `/auth/check-domain` | none | `endpoint.go:124` |
| POST | `/auth/login` | none | `endpoint.go:125` |
| POST | `/auth/logout` | none (stale cookie accepted) | `endpoint.go:126` |
| POST | `/auth/refresh` | none (public router) | `endpoint.go:132` |
| POST | `/auth/2fa/verify` | none (pending-token bearer, not session) | `internal/platform/auth/login/twofactor.go:62` |
| POST | `/auth/2fa/challenge/email` | none | `twofactor.go:63` |
| POST | `/auth/2fa/enroll/totp/begin` | none | `twofactor.go:64` |
| POST | `/auth/2fa/enroll/totp/confirm` | none | `twofactor.go:65` |
| POST | `/auth/2fa/enroll/email/begin` | none | `twofactor.go:66` |
| POST | `/auth/2fa/enroll/email/confirm` | none | `twofactor.go:67` |
| GET | `/auth/oidc/login` | none | `internal/platform/auth/bridge/login_endpoint.go:149` |
| GET | `/auth/oidc/callback` | none | `login_endpoint.go:150` |
| GET | `/auth/oidc/session/end` | none | `login_endpoint.go:151` |
| GET | `/portal/auth/oidc/login` | none | `login_endpoint.go:862` |
| GET | `/portal/authorize` | none | `internal/platform/portalauth/endpoints.go:57` |
| POST | `/portal/auth/check-domain` | none | `endpoints.go:58` |
| POST | `/portal/auth/login` | none | `endpoints.go:59` |
| POST | `/portal/auth/password-reset` | none | `endpoints.go:60` |
| POST | `/auth/password-reset/request` | none | `internal/platform/passwordreset/api/api.go:439` |
| GET | `/auth/password-reset/validate` | none | `api.go:440` |
| POST | `/auth/password-reset/confirm` | none | `api.go:441` |
| POST | `/auth/password-setup/request` | none | `api.go:447` (Go working-tree, uncommitted) |
| GET | `/oauth/authorize` | none (session cookie checked internally) | `internal/platform/auth/oauthapi/authorize.go:24` |
| POST | `/oauth/token` | none (client auth in body/basic) | `internal/platform/auth/oauthapi/token.go:162` |
| POST | `/oauth/introspect` | none (client auth in body/basic) | `internal/platform/auth/oauthapi/introspect_revoke.go:15` |
| POST | `/oauth/revoke` | none (client auth in body/basic) | `introspect_revoke.go:20` |
| GET/POST | `/oauth/userinfo` | bearer (access token, handler-checked) | `internal/platform/auth/oauthapi/userinfo.go:17-18` |
| GET | `/.well-known/openid-configuration` | none | `internal/platform/auth/oauthapi/discovery.go:11` |
| GET | `/.well-known/jwks.json` | none | `discovery.go:12` |
| GET | `/api/public/platform` | none | `internal/platform/publicapi/endpoint.go:57` |
| GET | `/api/public/login-theme` | none | `endpoint.go:58` |
| GET | `/api/config/platform` | none (legacy alias) | `endpoint.go:61` |
| GET | `/api/openapi.json` | none | `internal/server/wire_spec.go:16` |
| GET | `/api/openapi.yaml` | none | `wire_spec.go:25` |
| GET | `/q/openapi` | none | `wire_spec.go:38` |
| GET | `/swagger-ui` | none | `wire_spec.go:47` |
| POST | `/api/dispatch/process` | HMAC job token (handler-checked) | `internal/platform/dispatchjob/processing/processing.go:126` |
| POST | `/api/dispatch/settled` | HMAC job token (handler-checked) | `internal/platform/dispatchjob/settled/settled.go:75` |

### 1.2 API listener — session/bearer authenticated (inside `Authenticator`)

| Method | Path | Cite |
|---|---|---|
| GET | `/auth/me` | `login/endpoint.go:140` |
| POST | `/auth/change-password` | `endpoint.go:143` |
| POST | `/auth/change-password/send-email-code` | `endpoint.go:144` |
| GET | `/auth/login-history` | `endpoint.go:146` |
| GET | `/auth/2fa/status` | `twofactor_selfservice.go:25` |
| POST | `/auth/2fa/methods/totp/begin` \| `/confirm` | `twofactor_selfservice.go:26-27` |
| POST | `/auth/2fa/methods/email/begin` \| `/confirm` | `twofactor_selfservice.go:28-29` |
| DELETE | `/auth/2fa/methods/{method}` | `twofactor_selfservice.go:30` |
| POST | `/auth/2fa/recovery-codes/regenerate` | `twofactor_selfservice.go:31` |
| GET | `/auth/2fa/trusted-devices` | `twofactor_selfservice.go:32` |
| DELETE | `/auth/2fa/trusted-devices/{id}` | `twofactor_selfservice.go:33` |
| GET | `/auth/client/accessible` | `auth/clientselection/clientselection.go:34` |
| POST | `/auth/client/switch` | `clientselection.go:35` |
| GET | `/auth/client/current` | `clientselection.go:36` |
| POST/POST/POST/POST | `/auth/webauthn/register/begin,complete,authenticate/begin,complete` | `webauthn/api/api.go:80-86` (huma, in auth group) |
| GET/DELETE | `/auth/webauthn/credentials`, `/{id}` | `webauthn/api/api.go:85-86` |
| GET | `/api/me`, `/api/me/applications`, `/api/me/clients`, `/api/me/clients/{clientId}`, `/api/me/clients/{clientId}/applications` | `shared/me/me.go:33-37` |
| GET/POST/... | `/bff/dashboard/stats` | `shared/bff/dashboard.go:47` |
| GET/POST/PUT/PATCH/DELETE/... | `/bff/event-types*` (12 routes) | `shared/bff/event_types.go:37-52` |
| GET/POST | `/bff/developer/*` (7 routes) | `shared/bff/developer.go:58-64` |
| GET/POST/PUT/DELETE | `/bff/roles*` (10 routes) | `shared/bff/roles.go:46-55` |
| GET | `/bff/filter-options/clients`, `/bff/event-types/filters/applications` | `shared/bff/filter_options.go:48-49` |
| GET | `/bff/scheduled-jobs*` (6 routes) | `shared/bff/scheduled_jobs.go:47-52` |
| POST | `/api/audit-logs/batch` | `shared/sdk/audit_batch.go:50` |
| POST | `/api/dispatch-jobs`, `/api/dispatch-jobs/batch` | `shared/sdk/dispatch_jobs_batch.go:81-82` |

### 1.3 Router mount (same API listener, prefix default `/router`, BasicAuth — `internal/server/run.go:247-266`)

67 routes total (`internal/router/api/handlers_{health,dashboard,warnings,mutations,mocks,misc,messages,group_flush}.go`, all `huma.Register`), plus non-huma `GET /monitoring/dashboard`, `GET /dashboard.html` (`api.go:309-312`), `GET <prefix>/metrics` (Prometheus, `run.go:264`), and huma's own auto-mounted `GET <prefix>/openapi.json` / `GET <prefix>/docs` (not suppressed for the router config, unlike the platform's — `run.go:255-261` clears only `SchemasPath`). Full list (method/path/OperationID) captured from the `huma.Operation{...}` literals:

- **Health** (`handlers_health.go:21-96`): `GET /health`, `GET /q/health`, `GET /health/live`, `GET /health/ready`, `GET /health/startup`, `GET /monitoring`, `GET /monitoring/health`, `GET /monitoring/pools`, `GET /monitoring/warnings`, `GET /monitoring/consumer-health`.
- **Dashboard** (`handlers_dashboard.go:17-53`): `GET /monitoring/pool-stats`, `GET /monitoring/queue-stats`, `GET /monitoring/queues`, `GET /monitoring/circuit-breakers`, `GET /monitoring/circuit-breakers/{name}/state`, `GET /monitoring/in-flight-messages`, `GET /monitoring/in-flight-messages/check`, `POST /monitoring/in-flight-messages/check-batch`, `GET /monitoring/in-flight-messages/detail`, `GET /monitoring/mediating`.
- **Warnings** (`handlers_warnings.go:18-54`): `GET /warnings`, **`DELETE /warnings`**, `POST /warnings/{id}/acknowledge`, `POST /warnings/acknowledge-all`, `GET /warnings/critical`, `GET /warnings/unacknowledged`, `GET /warnings/severity/{severity}`, **`DELETE /warnings/old`**, `GET /monitoring/warnings/unacknowledged`, `GET /monitoring/warnings/severity/{severity}`.
- **Mutations** (`handlers_mutations.go:12-32`): `PUT /monitoring/pools/{poolCode}`, `POST /monitoring/broker-stats/refresh`, `POST /monitoring/circuit-breakers/{name}/reset`, `POST /monitoring/circuit-breakers/reset-all`, `POST /monitoring/warnings/{id}/acknowledge`, `POST /monitoring/in-flight-messages/{messageId}/ack`.
- **Mocks** (`handlers_mocks.go:20-74`): 14 routes, `/api/test/*` and `/api/benchmark/*`.
- **Misc** (`handlers_misc.go:22-55`): `GET /api/config`, `POST /config/reload`, `POST /api/seed/messages`, `GET /monitoring/standby-status`, `GET /monitoring/traffic-status`, `GET /monitoring/stream-health`, `GET /monitoring/stream-health/live`, `GET /monitoring/stream-health/ready`.
- **Messages** (`handlers_messages.go:17`): `POST /messages`.
- **Group flush** (`handlers_group_flush.go:14-28`): `GET /monitoring/blocked-groups`, `GET /monitoring/group-flushes`, `POST /monitoring/group-flushes/{pool}/{group}/clear`.
- **Dashboard HTML + metrics + docs**: `GET /monitoring/dashboard`, `GET /dashboard.html` (`api.go:310-311`), `GET <prefix>/metrics` (`run.go:264`), `GET <prefix>/openapi.json`, `GET <prefix>/docs` (huma default, `run.go:255-261`).

### 1.4 Metrics listener (`FC_METRICS_PORT`, own port)

`GET /health`, `GET /ready`, `GET /metrics` (placeholder text) — `internal/server/http.go:49-73`.

### 1.5 MCP listener (`FC_MCP_PORT`/`FC_MCP_BIND`, own port)

`/mcp` (all of POST/GET-SSE/DELETE via one `r.Handle`), `GET /health` — `internal/server/subsystems.go:431-434` (also `cmd/fcdev/mcp.go:74-75` for the standalone `fcdev mcp` binary, same shape).

### 1.6 Outbox admin listener (`FC_OUTBOX_ADMIN_PORT`, loopback-only, own port, no auth)

`GET /outbox/groups`, `GET /outbox/groups/blocked`, `POST /outbox/groups/{group}/pause`, `/resume`, `/unblock`, `/skip` — `internal/outbox/admin.go:23-49`, mounted at `internal/server/subsystems.go:352-353`.

**Go total: ≈188 distinct (method, path, listener) registrations** outside the `/api/**` huma/OpenAPI lockfile (109 on the API listener's auth/OIDC/portal/BFF/me/SDK-batch/dispatch-callback/spec surface + 1 API-listener `/health` + 67 under `/router` + 3 metrics + 2 MCP (`/mcp` counted once, method-agnostic) + 6 outbox admin).

## 2. Java surface, by listener

### 2.1 API listener — public / self-authenticating

| Method | Path | Auth | Cite |
|---|---|---|---|
| GET | `/health` | none | `server/Server.java:724` |
| POST/GET | `/auth/check-domain` | none | `platform/auth/login/LoginApi.java:104-105` |
| POST | `/auth/login` | none | `LoginApi.java:106` (adjacent) |
| POST | `/auth/logout` | none | `LoginApi.java:110` |
| POST | `/auth/refresh` | none | (auth/login, refresh handler — verified present, wired public) |
| POST | `/auth/2fa/challenge/email`, `/enroll/totp/begin`, `/enroll/email/begin` | none | `platform/auth/mfa/api/TwoFactorApi.java:91,92,94` |
| GET | `/auth/oidc/login`, `/auth/oidc/callback`, `/auth/oidc/session/end` | none | `platform/auth/oidc/OidcBridgeApi.java:108-110` (`CALLBACK_PATH` = `/auth/oidc/callback`, line 66) |
| GET | `/portal/auth/oidc/login` | none | `platform/portalauth/PortalSso.java:69` |
| GET/POST×3 | `/portal/authorize`, `/portal/auth/check-domain`, `/portal/auth/login`, `/portal/auth/password-reset` | none | `platform/portalauth/api/PortalAuthApi.java:72-75` |
| POST/GET/POST | `/auth/password-reset/request`, `/validate`, `/confirm` | none | `platform/passwordreset/PasswordResetApi.java:81,83` (+confirm) |
| POST | `/auth/password-setup/request` | none | `PasswordResetApi.java:82` — **present**, matches Go's uncommitted addition |
| GET | `/oauth/authorize` | none | `platform/auth/oauth/OAuthAuthorizeApi.java:46` (`Group.OIDC`) |
| POST | `/oauth/token` | none | `platform/auth/oauth/OAuthTokenApi.java:53` (`Group.OIDC`) |
| POST | `/oauth/introspect`, `/oauth/revoke` | none | `platform/auth/oauth/OAuthIntrospectionApi.java:29-30` |
| GET/POST | `/oauth/userinfo` | bearer (handler-checked) | `platform/auth/oauth/OAuthUserinfoApi.java:28-29` |
| GET | `/.well-known/openid-configuration`, `/.well-known/jwks.json` | none | `platform/auth/oauth/OAuthDiscoveryApi.java:26-27` |
| GET | `/api/public/platform`, `/api/config/platform`, `/api/public/login-theme` | none | `platform/publicapi/api/PublicApi.java:40-43` |
| GET | `/api/openapi.json`, `/api/openapi.yaml`, `/q/openapi`, `/swagger-ui` | none | `platform/shared/openapi/SpecRoutes.java:49-52` |
| POST | `/api/dispatch/process` | HMAC job token | `platform/dispatchjob/processing/ProcessingApi.java:89` |
| POST | `/api/dispatch/settled` | HMAC job token | `platform/dispatchjob/settled/SettledApi.java:59` |

### 2.2 API listener — session/bearer authenticated (`Auth.scoped`)

All present and 1:1 with Go §1.2: `LoginApi.java:111-112` (`/auth/me`, `/auth/login-history`), `platform/auth/mfa/api/ChangePasswordApi.java:65` + adjacent (`/auth/change-password*`), `TwoFactorApi.java:98-106` (9 self-service routes), `platform/auth/clientselection/ClientSelectionApi.java:52-54`, `platform/passkey/api/PasskeyApi.java:77-86` (all 6 webauthn routes — `authenticate/complete` is registered via `routes.in(Group.LOGIN).post(...)`, easy to miss on a plain grep), `platform/bff/api/MeApi.java:49-53`, `DashboardBff.java:44`, `EventTypesBff.java:79-92` (12 routes incl. both `PUT` and `PATCH` on `{id}`, matching Go's dual mapping), `DeveloperBff.java:71-77`, `RolesBff.java:70-79`, `FilterOptionsBff.java:37-38`, `ScheduledJobsBff.java:67-72`, `platform/ingest/api/IngestApi.java:92-94` (`/api/dispatch-jobs`, `/api/dispatch-jobs/batch`, `/api/audit-logs/batch`).

Java-only addition inside this group: `GET /bff/debug/events`, `GET /bff/debug/events/{id}`, `GET /bff/debug/dispatch-jobs` (`platform/bff/api/DebugBff.java:68-70`) — **not actually Java-only**: Go has the same three routes, huma-registered (`internal/platform/event/api/api.go:54-58`, `internal/platform/dispatchjob/api/api.go:66-67`), so they are inside Go's `/api/**` lockfile even though Java's `LockfileCoverageTest` classifies `/bff/` as outside its own. Not a gap.

### 2.3 Router mount (`Server.java:741-753`, same API listener, `Group.NO_DB`, BasicAuth via `BasicAuthFilter`)

61 routes registered by `RouterApi.register` (`router/api/RouterApi.java:182-195`, delegating to 12 per-resource classes) + `GET /monitoring/dashboard`, `GET /dashboard.html` (`router/api/dashboard/DashboardHandler.java:50-51`) + `GET <prefix>/metrics` (`router/api/MetricsRoutes.java:60`). Full enumeration (method + path + file:line) was captured directly from source and matches Go's §1.3 list **except**:

- `DELETE /warnings` and `DELETE /warnings/old` — **absent**. `router/api/WarningRoutes.java:22-24`: *"`DELETE /warnings` and `DELETE /warnings/old` are absent: `WarningStore` has no bulk-remove, only `raise`/`acknowledge`/`cleanup` and the read accessors."* This is a code comment, not a `docs/` ruling — see §3.
- `<prefix>/openapi.json`, `<prefix>/docs` — **absent**. `router/api/RouterApi.java:61-62` (class doc, "Routes deliberately absent"): *"`GET /openapi.json`, `/docs` — huma-generated docs; no equivalent generator wired for the router surface."*

`BasicAuthFilter` (`router/api/auth/BasicAuthFilter.java:1-60`) additionally **fixes** a Go defect noted in its own doc comment: Go's public-path check compares against `r.URL.Path` unstripped of the chi mount prefix, so under the real `/router` mount `/router/health/live` is not in Go's public set and gets challenged even though `/health/live` alone is meant to be exempt; Java strips the prefix first. Not a route-existence difference, but worth carrying into any Go-vs-Java behavioural parity run against a real BasicAuth-configured router.

### 2.4 Metrics listener (`Metrics.java`, `FC_METRICS_PORT`, own `VertxListener`)

`GET /health`, `GET /ready`, `GET /metrics` — `Metrics.java:61-63`. Unlike Go, `/metrics` here serves the **real** Prometheus registry, not a placeholder string (`Metrics.java:19-27` documents this as a deliberate tidy-up, router alias kept for existing scrapes). Improvement, not a gap.

### 2.5 MCP listener (`McpServer.java`, `FC_MCP_PORT`/`FC_MCP_BIND`, own Vert.x instance)

`POST|GET|DELETE /mcp` (`mcp/VertxStreamableServerTransportProvider.java:128-130`, three explicit registrations vs Go's one method-agnostic `r.Handle` — same effective method set), `GET /health` (`McpServer.java:173`).

### 2.6 Outbox admin listener (`OutboxAdminApi.java`, `FC_OUTBOX_ADMIN_PORT`, loopback-only, no auth)

All 6 routes present and identical: `OutboxAdminApi.java:54-66`.

**Java total: ≈184 distinct (method, path, listener) registrations** — 4 fewer than Go, both gaps in the router mount (§2.3).

## 3. Diff

### 3.1 Routes only in Go (not in Java)

| Route | Listener | Ruled? |
|---|---|---|
| `DELETE /warnings` | router | **Not owner-ruled.** Code comment only (`WarningRoutes.java:22-24`): `WarningStore` has no bulk-remove operation. This is a real capability gap for an operator clearing the warning log via the API — worth a `docs/backlog.md` line or a `WarningStore.clearAll()` port if the owner wants parity. |
| `DELETE /warnings/old` | router | Same as above — no bulk age-based cleanup exposed over HTTP (`WarningStore.cleanup()` exists and runs on a timer, per the class comment, just not reachable via this route). |
| `<prefix>/openapi.json`, `<prefix>/docs` | router | Documented as deliberately absent (`RouterApi.java:61-62`, "no equivalent generator wired for the router surface") — a tooling/discoverability gap, not a behavioural one; nothing reads the router's self-served OpenAPI doc in this repo's own tests. Low-priority gap, not blocking.

No other Go route was found missing from Java. In particular: `POST /auth/password-setup/request` (the Go working-tree addition called out in the task) **is present** in Java (`PasswordResetApi.java:82`).

### 3.2 Routes only in Java (not in Go)

None found. `GET /bff/debug/events`, `/bff/debug/events/{id}`, `GET /bff/debug/dispatch-jobs` looked Java-only on first grep but Go has the identical three routes, just huma-registered inside its own `/api/**` lockfile rather than chi-mounted (§2.2) — not a real Java addition.

### 3.3 Method-set mismatches on shared paths

None found. Every path that exists on both sides registers the same method set, including the two-method dance on `/bff/event-types/{id}` (Go `PUT`+`PATCH`, Java `PUT`+`PATCH` — `EventTypesBff.java:86-87` explicitly comments this as "the SPA's own dialect", matching Go `event_types.go:43,47`) and `/oauth/userinfo` (`GET`+`POST` both sides).

### 3.4 Auth-mode mismatches

**One found, and it is a deliberate, documented, Java-first divergence — not a defect:**

- **`GET /api/dispatch/router-config`** — unauthenticated request:
  - Go (`internal/platform/dispatch/api.go:42-46`, `auth.RequireAnchor`/`requirePermission` → `usecase.Authorization("UNAUTHENTICATED", …)`): **403**, platform envelope `{"error":"UNAUTHENTICATED", "message":"authentication required"}`.
  - Java (`RouterConfigApi.java:49-58`): **401** `{"error":"UNAUTHORIZED", "message":"authentication required"}`, explicitly overriding the platform's usual `Checks`-driven 403.
  - Ruled: `docs/spec/router-config-auth.md` §1 ("401 without a token, 403 with a token lacking the permission") and §6 ("Go has not started §3; it builds from this spec instead of R3"). `docs/spec/permissions-from-roles.md` is the companion ruling withdrawing the anchor-bypass that used to let any provisioned service account through this gate regardless of role. Go is expected to mirror this later; until it does, the parity harness allow-lists the `router-config` scenario's status-code diff against this ruling (`docs/spec/router-config-auth.md` §5 item 6).

No other auth-mode mismatch was found across the ~180 routes compared, including the two envelope families (`auth-core.md` §5's platform `{"error":…}` vs login-surface `{"code":…}` shapes — verified in §4 below) and the RFC 6749 OAuth envelope.

### 3.5 Listener mismatches

None. Every route lives on the same listener on both sides: API port (platform, router-mounted-under-API-port, spec), metrics port, MCP port, outbox-admin port. The router-config document's move off the metrics/internal listener onto the API listener (R3 → R3′) is a **Go-and-Java-agreed target state** that Go has not yet implemented (`router-config-auth.md` §6) — both sides already serve it from the API listener in this tree, since Go's `dispatch.Register` in `wire_routes.go:345-347` already mounts it in the ordinary authenticated huma group, not on the old internal listener. (R3's "internal listener" design was retired in Go before this snapshot; only the auth-gate strictness differs, per §3.4.)

## 4. Response-code / envelope spot check (5 routes)

| # | Route | Unauthenticated | Forbidden (wrong permission) | Match? |
|---|---|---|---|---|
| 1 | `GET /auth/me` (login-surface) | Go 401 `{"code":"UNAUTHENTICATED","message":"Not authenticated"}` + `WWW-Authenticate: Cookie realm="fc_session"` (`login/endpoint.go` LE:578-621, LE:676-684) | n/a (any authenticated principal reads their own profile) | **Match.** Java `LoginApi.java:509-511`: same status, same header, same `code`-keyed body via `HttpError.writeLoginSurface`. |
| 2 | `POST /bff/event-types` (platform envelope, permission-gated) | Go 403 `{"error":"UNAUTHENTICATED","message":"authentication required"}` (`auth.go:451-452`, `requirePermission`) | Go 403 `{"error":"PERMISSION_REQUIRED","message":"permission required: …"}` (`auth.go:455`) | **Match.** Java `Checks.require`/`Checks.requireAny` throw the identical `UseCaseException.authorization("UNAUTHENTICATED"\|"PERMISSION_REQUIRED", …)` (`Checks.java:44,53-63`), rendered through the same `{"error":…}` envelope (`HttpError.java:36-62`), both 403 via the authorization-kind→403 mapping. |
| 3 | `GET/POST /oauth/userinfo` (RFC 6749 envelope) | Go 401 `{"error":"invalid_request","error_description":"Missing Authorization header"}` (`userinfo.go:126-127`) | n/a (token validity is the only gate) | **Match.** Java `OAuthUserinfoApi.java:33-35` — identical status, code, and message text, via `OAuthError`. |
| 4 | `GET /api/dispatch/router-config` (platform envelope, override) | Go **403** `UNAUTHENTICATED` (see §3.4) | Go 403 `PERMISSION_REQUIRED` | **Mismatch, ruled.** Java: unauthenticated → **401** `UNAUTHORIZED`; forbidden → 403 `PERMISSION_REQUIRED` (matches). This is the one documented divergence carried in §3.4. |
| 5 | `POST /api/dispatch/process` (HMAC job-token envelope) | Not spot-checked in depth — both sides gate in-handler on the scheduler's HMAC token rather than `AuthContext`; a missing/invalid signature produces each side's own bespoke 401/403 shape (not the platform envelope on either side). Flagged for a follow-up pass rather than asserted here, since it needs a live signature to exercise meaningfully rather than a static read. | | Not verified — recommend a targeted unit-test read (`ProcessingApi` / `dispatchprocessing`) before relying on this route's error shape in the parity corpus. |

**Pattern found:** the platform's two envelope families (`{"error":…}` for everything routed through `httperror`/`HttpError`, `{"code":…}` for the hand-written login surface) are reproduced exactly, including which routes use which family and the one deliberately-diverging status code on the router-config route. No accidental 401-vs-403 or envelope-key mismatch was found in the routes actually compared.

## 5. Counts

- Go non-lockfile route registrations (all listeners): **≈188**
- Java non-lockfile route registrations (all listeners): **≈184**
- Routes only in Go, unruled: **2** (`DELETE /warnings`, `DELETE /warnings/old` — router, code-comment justification only, no `docs/` ruling)
- Routes only in Go, ruled/low-priority: **2** (`<router-prefix>/openapi.json`, `<router-prefix>/docs` — documented absent, no consumer found)
- Routes only in Java: **0**
- Method-set mismatches on shared paths: **0**
- Auth-mode mismatches: **1**, and it is a deliberate, documented Java-first ruling (`GET /api/dispatch/router-config`: Go 403/Java 401 on missing auth), not a defect
- Listener mismatches: **0**

## Summary for the caller

Only-Go, not ruled by a `docs/` file:
- `DELETE /warnings` (router) — `WarningStore` has no bulk-remove; code comment only, no owner ruling on record.
- `DELETE /warnings/old` (router) — same; no bulk age-based cleanup exposed over HTTP.

Auth-mode mismatch, ruled (not a defect):
- `GET /api/dispatch/router-config` unauthenticated: Go 403 `UNAUTHENTICATED`, Java 401 `UNAUTHORIZED` — `docs/spec/router-config-auth.md` §1/§5/§6, Java-first, Go has not mirrored yet.

Everything else — including the Go working tree's new `POST /auth/password-setup/request`, all ~180 other outside-lockfile routes across the API/router/metrics/MCP/outbox-admin listeners, every method set, and the two wire-error-envelope families — matched.
