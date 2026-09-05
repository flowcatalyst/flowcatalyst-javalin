# MCP server — the agent-facing read-only surface

Extracted 2026-09-05 against Go HEAD (`9f7be62`) from `internal/mcp/`
(`server.go`, `tools.go`, `resources.go`, `config.go`, `auth.go`),
`internal/server/subsystems.go` (`StartMCP`), `envcfg.go`, and the Go tests
(`TestTokenManagerCachesAndRefreshesBeforeExpiry`, `…ErrorsOnNon2xx`,
`TestLoadConfigEnvPrecedenceAndDefault`, `TestCredentialsFileRoundTrip`,
`TestRequireCredentials`, `TestToolReturnsPrettyJSONNotGoMap`,
`TestGetSchemaFallsBackCurrentToFinalising`). Go uses the official
`modelcontextprotocol/go-sdk`; Java uses the official
`io.modelcontextprotocol.sdk:mcp` (already managed in the parent POM,
`mcp.version`). Behaviour tables, never code. `[C]` contract.

## 1. Purpose and topology [C]

A **read-only** Model Context Protocol server that proxies a fixed set of
platform reads over HTTP to the platform API, authenticating as an OAuth2
client (`client_credentials`). It holds no database connection — `Main`
already skips the pool for an MCP-only instance. Subsystem
`FC_MCP_ENABLED` (default false); listener `FC_MCP_BIND` (default
`127.0.0.1`, an agent-facing surface, `0.0.0.0` to expose) `:FC_MCP_PORT`
(default 8090), `ReadHeaderTimeout 5 s`; routes `/mcp` (streamable HTTP:
POST + GET as SSE + DELETE — one server instance shared across requests)
and `GET /health` → 200. Shutdown: graceful with a 5 s bound. Both
transports exist in Go (stdio for `fcdev mcp`, streamable HTTP in
`fc-server`); the server unit ships streamable HTTP, `fcdev mcp` adds stdio
later (`backlog.md` fcdev stubs).

Platform URL: `FLOWCATALYST_URL` → `FC_MCP_PLATFORM_URL` →
`http://localhost:<FC_API_PORT>`. Credentials: `FLOWCATALYST_CLIENT_ID` /
`FLOWCATALYST_CLIENT_SECRET`; when **both** are unset, the credentials file
`<user cache dir>/flowcatalyst-dev/mcp-credentials.json`
(`{"client_id","client_secret","base_url"}`, the file `fcdev start`
bootstraps; env wins per field). Missing credentials → the server still
starts and every tool answers the error "no MCP credentials: set
FLOWCATALYST_CLIENT_ID/FLOWCATALYST_CLIENT_SECRET, or run `fcdev start` to
bootstrap the mcp-credentials.json …" (`RequireCredentials`).

## 2. Token manager [C]

`POST {baseUrl}/oauth/token`, form `grant_type=client_credentials`,
`client_id`, `client_secret`; response `{access_token, expires_in}`.
Cached; **refreshed before expiry** with a buffer (`tokenRefreshBuffer`;
a token is reused only while `now + buffer < expiresAt`); non-2xx → error
surfaced to the tool call ("token endpoint returned <status>"). Sent as
`Authorization: Bearer <token>` on every platform call. Until the auth
aggregate lands, the Java platform has no `/oauth/token` — the Java MCP
therefore also accepts a **static bearer** (`FC_MCP_PLATFORM_AUTH_TOKEN`,
new, Java-only until then) and uses the token manager when a client id
and secret are configured; document this as a deliberate interim.

## 3. Tools [C] — every result is the platform's JSON, pretty-printed (`TestToolReturnsPrettyJSONNotGoMap`)

| Tool | Args (all optional unless marked) | Platform call |
|---|---|---|
| `list_event_types` | `status`, `application`, `subdomain`, `aggregate`, `clientId` | `GET /api/event-types?…` |
| `get_event_type` | `id` (required) | `GET /api/event-types/{id}` |
| `get_schema` | `id` (required), `version` (a spec-version **status**, default `CURRENT`) | `GET /api/event-types/{id}` then the spec version whose status is `version`; when `CURRENT` is absent **falls back to `FINALISING`** (`TestGetSchemaFallsBackCurrentToFinalising`); none → error "no <status> spec version"; returns the `schema` JSON only |
| `list_subscriptions` | `clientId` (admin-scoped) | `GET /api/subscriptions?clientId=` |
| `get_subscription` | `id` (required) | `GET /api/subscriptions/{id}` |
| `list_applications` | `active` (default true) | `GET /api/applications?active=` |
| `list_roles` | `source` | `GET /api/roles?source=` |
| `get_role` | `id` (required) | `GET /api/roles/{id}` |
| `get_openapi` | `applicationCode` (default `platform`) | `GET /api/applications/by-code/{code}` → `GET /bff/developer/applications/{appId}/openapi/current` (raw spec) |
| `whoami` | — | `GET /api/me` |
| `list_my_applications` | — | `GET /api/me/applications` |
| `get_application_capabilities` | `applicationCode` (required) | bundle `{application: GET /api/applications/by-code/{code}, openapi: the current spec (404 tolerated → null), assignableRoles: GET /api/roles/by-application/{appId} (404 → null), eventTypes: GET /api/event-types?application=<code>&status=CURRENT}` |

Descriptions are part of the contract (agents read them); copy Go's text
verbatim. Platform errors (non-2xx) become tool errors carrying the
platform's error envelope text.

## 4. Resources [C]

Static: `flowcatalyst://openapi/platform` (the platform's current OpenAPI,
`application/json`), `flowcatalyst://applications` (`/api/applications?active=true`),
`flowcatalyst://roles` (`/api/roles`), `flowcatalyst://event-types`
(`/api/event-types`), `flowcatalyst://subscriptions` (`/api/subscriptions`).
Templates: `flowcatalyst://event-types/{id}`, `flowcatalyst://subscriptions/{id}`,
`flowcatalyst://roles/{id}` (each `→ /api/<plural>/{id}`),
`flowcatalyst://applications/{code}` (`→ /api/applications/by-code/{code}`).
Every read returns one `application/json` text content with the platform's
body verbatim.

## 5. Tests the port must have

`TokenManagerTest` (caches; refreshes before expiry with a fake clock;
non-2xx surfaces the status), `McpConfigTest` (env precedence over the
file, per field; file round trip; the missing-credentials message),
`McpToolsTest` against a stub platform (`HttpServer`): every tool's path
and query, `get_schema`'s fallback and its "none" error, the capabilities
bundle with a 404 on roles tolerated, pretty-printed JSON, a platform 500
becoming a tool error; `McpServerTest`: `/health` 200, `/mcp` initialises
a session over streamable HTTP with the SDK's own client and lists the 12
tools and 9 resources by name, the listener binds to `FC_MCP_BIND` only.
