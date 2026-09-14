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

**Transport (`docs/vertx-migration-brief.md` phase 3, 2026-09-1x):** streamable
HTTP is served by `io.flowcatalyst.mcp.VertxStreamableServerTransportProvider`,
this repo's own `io.modelcontextprotocol.spec.McpStreamableServerTransportProvider`
implementation over vertx-web — not the MCP SDK's servlet-based
`HttpServletStreamableServerTransportProvider`, which needed Javalin/Jetty on
the classpath for MCP alone (the reason the first Vert.x cutover was
reverted, `docs/vertx-plan.md` §9). Javalin and Jetty are gone from every
`pom.xml`. `McpServer` owns its own, single-event-loop `Vertx` instance and
`HttpServer` on `FC_MCP_BIND:FC_MCP_PORT` — its own listener, as above, never
the API/metrics listener's. The provider is modelled line by line on the
SDK's servlet transport (its mcp-core 2.0.1 sources): same `POST`
(JSON-RPC in; a single JSON response for `initialize`, an SSE response
stream for every other request), `GET` (the standalone SSE listening
stream, `Mcp-Session-Id` required, `Last-Event-ID` resume), `DELETE`
(session end), the same `Mcp-Session-Id` response header and error
statuses/bodies, and the SDK's own `ServerTransportSecurityValidator`
Origin/Host checks (reused directly — that type has no servlet dependency).
One difference: the provider's own default configures
`DefaultServerTransportSecurityValidator` with empty allow-lists, where the
servlet-based `McpServer` left it at `NOOP` (no check at all) — but `McpServer`
never runs the provider on that bare default. It builds the validator with
`deriveSecurityValidator`, **derived, never a separate knob**: the allowed
Origins are `http://`/`https://` for `localhost`, `127.0.0.1`, `[::1]` and
whatever `FC_MCP_BIND` resolved to, each on the listener's own actual bound
port — every address a same-machine client, including a browser-based MCP
client sending a real `Origin` header, could legitimately use to reach this
exact listener — and the same host set becomes the allowed `Host` values too.
A request with no `Origin` header (every non-browser MCP client) is
unaffected either way; one carrying an `Origin` that isn't in that derived
set is refused (`403`), closing a DNS-rebinding gap the old, servlet-based
transport left wide open (`NOOP`) without introducing a configuration
surface. h2c is off on this listener (`HttpServerOptions#setHttp2ClearTextEnabled(false)`,
unlike the API listener) for a related reason: HTTP/2 carries no literal
`Host` header (only the `:authority` pseudo-header, which Vert.x does not
synthesize a `Host` entry from), so an h2c-upgraded connection would look
like every request is missing its `Host` header and get refused `421` by
the derived validator's `Host` check — MCP was always documented as
HTTP/1.1-only (§1), this just makes the listener actually enforce it. Every
request's JSON-RPC handling (which may call the platform over HTTP through
`PlatformClient`) runs on a virtual thread, never the Vert.x event loop;
every response write is marshalled back onto the request's own `Context`
with `runOnContext`, the pattern `io.flowcatalyst.http.vertx.VertxListener`
uses for the API listener. The platform's Jackson 3 mapper
(`platform/shared/json/Json#MAPPER`, wrapped in `mcp-json-jackson3`'s
`JacksonMcpJsonMapper`) is what the SDK serialises with — never Vert.x's own
Jackson-2-based `JsonObject` on this wire path.

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
tools and 9 resources by name, the listener binds to `FC_MCP_BIND` only, an
`Origin` matching the listener's own address (`http://localhost:<port>`) is
accepted, and an unrelated `Origin` is refused (403);
`VertxStreamableServerTransportProviderTest` drives the wire protocol
directly with a raw `HttpClient` (no SDK client) against a provider wired
with the same `deriveSecurityValidator`: the initialize handshake
returns a `Mcp-Session-Id` header, `tools/list` over the session answers a
JSON-RPC response inside the SSE stream, a `GET` listening stream receives
a `notifyClients` broadcast as an SSE event, `DELETE` ends the session and a
subsequent `POST` with that id is refused (404), an `Origin` matching the
listener's own address is accepted (200), and an unrelated `Origin` is
refused (403).
