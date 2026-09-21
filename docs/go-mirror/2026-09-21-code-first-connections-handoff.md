# Java hand-off: application-owned connections, code-first connection sync

Owner rulings (2026-09-21). Go implements them in the commits that add this
file; Java must mirror the wire contract and the schema so the two platforms
agree against the shared database.

The driver: a Laravel application could not define a subscription in code.
The sync required a `target` the SDK never sent, and named the connection by
an id that is different in every environment. Fixing that properly meant
giving connections an owner.

## Vocabulary

Two independent axes. Do not conflate them.

| Term | Means |
|---|---|
| **global** | no client (`client_id IS NULL`) |
| **client-scoped** | bound to one client |
| **shared** | no application (`application_code IS NULL`) |
| **application-owned** | bound to one application |

## Schema — Go migration 056

Both platforms share one database; whichever deploys first applies it.

- `msg_connections.application_code VARCHAR(100)` NULL.
- `msg_connections.source VARCHAR(20) NOT NULL DEFAULT 'UI'`, CHECK
  `source IN ('CODE','API','UI')` (`chk_msg_connections_source`). Existing rows
  become `UI`.
- `idx_msg_connections_code_client` and `idx_msg_subscriptions_code_client`
  (`UNIQUE (code, client_id)`) are DROPPED and replaced by
  `uq_msg_connections_app_client_code` / `uq_msg_subscriptions_app_client_code`:
  `UNIQUE (COALESCE(application_code,''), COALESCE(client_id,''), code)`.
  Codes are now unique **per application**, and "none" is a real key value on
  both nullable parts — the old index never rejected two rows with the same
  code and a NULL `client_id`.
- 056 pre-checks both tables and refuses to apply, naming the colliding groups,
  if existing rows collide under the new key. Run
  `scripts/ops/056-application-scope-prescan.sql` against production first.

Java action items:

- **Do not re-create the old indexes.** Java's `V1__baseline.sql` creates them
  with `IF NOT EXISTS`. On an existing database that is a no-op; a Flyway
  baseline run against a fresh database AFTER Go's 056 would put them back and
  silently reinstate per-client (not per-application) uniqueness. Add a Java
  migration that drops them and creates the two `uq_…` indexes, idempotently.
- Regenerate jOOQ (`Indexes.IDX_MSG_CONNECTIONS_CODE_CLIENT` /
  `IDX_MSG_SUBSCRIPTIONS_CODE_CLIENT` disappear; `MSG_CONNECTIONS` gains two
  columns).
- Java's connection/subscription upserts are `ON CONFLICT (id)` and do not
  depend on the old index — no change needed there. Inserts that omit `source`
  get `'UI'`, which is correct for the UI/API create path.
- Every "does this code already exist" lookup must use the three-part key
  (application, client, code) with NULL-as-a-value semantics
  (`IS NOT DISTINCT FROM`).

## Connections: create / update

`POST /api/connections`, `PUT /api/connections/{id}` gain optional
`applicationCode`. It must name an existing application (404
`Application_NOT_FOUND`) and the caller must be able to access that application
(403). On update it is set-if-provided (cannot be cleared); changing it re-runs
the duplicate check under the new key (409 `CODE_EXISTS`). Responses gain
`applicationCode` and `source`. Rows created here are `source = UI`.

## Connection sync (new)

`POST /api/applications/{appCode}/connections/sync?removeUnlisted=<bool>`

```
{
  "clientId": "<client id OR identifier slug>",   // optional; omitted = global
  "connections": [
    { "code": "...", "name": "...", "description": "...", "externalId": "..." }
  ]
}
```

Result shape is the same as the subscription sync
(`created / updated / deleted / syncedCodes`).

Rules:

- **Permission**: any of `platform:messaging:connection:sync`,
  `platform:messaging:connection:manage`,
  `platform:application-service:connection:{create,update,delete}` — the same
  pattern as subscriptions. The new permissions are granted to the roles that
  hold the subscription equivalents (`messaging-admin` gets `connection:sync`;
  `application-service` gets the three app-service ones).
- **Authorization**: the caller must be able to access the application; when
  `clientId` is given, the client too. A client-less sync needs ONLY application
  access — deliberately unlike the scheduled-jobs sync, which demands anchor.
  Ownership (next bullet) is what makes that safe. A caller that syncs several
  applications is simply anchor, or linked to several applications.
- **Ownership**: a sync creates, updates and removes only rows with
  `application_code = {appCode}` AND `client_id` = the request's client (NULL
  matching NULL only) AND `source IN ('API','CODE')`. A UI-authored row with the
  same key is left untouched; a shared row and another application's row are
  never seen. New rows are stamped `source = API`.
- **Service account**: `service_account_id` is ALWAYS the application's
  provisioned service account (`app_applications.service_account_id`) — the
  account whose signing secret the application validates deliveries against —
  never the caller's. No application service account → 400
  `APPLICATION_SERVICE_ACCOUNT_REQUIRED`. There is no per-connection override.
- **removeUnlisted** is scoped as under Ownership, and refuses AS A WHOLE with
  409 `CONNECTION_REFERENCED` (naming the connection and the subscription codes)
  if any row it would remove is still referenced by a subscription. Connection
  delete has no such guard of its own.
- **Client reference**: id or identifier slug; unknown → 404. Ids differ per
  environment, identifiers do not.
- Validation: code format as on create; name required; duplicate codes within
  one request rejected.
- Events: per-row `connection:created|updated|deleted` plus a rollup
  `platform:admin:connection:synced` (carries `applicationCode`, `clientId`,
  counts, `syncedCodes`). The event type + JSON schema are in the seeded
  catalogue.

## Subscription sync

`POST /api/applications/{appCode}/subscriptions/sync` — additive changes; a
request that uses none of them behaves exactly as before.

Request body gains `clientId` (id or identifier slug, optional; omitted =
global). Each subscription entry gains `connectionCode` (string) and
`sharedConnection` (bool); `connectionId` still works.

- **Client scoping**: rows are matched, created and removed within
  (application, client). A sync for client A never sees, updates or removes
  client B's subscriptions or the global ones, and vice versa. Previously the
  sync loaded every client's rows for the application and matched by code
  alone. Created rows get `client_id` = the request's client. `removeUnlisted`
  is scoped to application AND client (NULL matching NULL only) AND
  `source IN ('API','CODE')`.
- **Authorization**: application access always; client access when `clientId`
  is given; a client-less sync needs only application access (not anchor).
- **Connection by code — the namespace is explicit, with NO fallback**:
  - bare `connectionCode` → a connection owned by THIS application;
  - `sharedConnection: true` → a shared (application-less) connection.
  Within the chosen namespace a client-scoped sync looks up (code, namespace,
  its client) first, then (code, namespace, no client); a client-less sync
  resolves only a global connection. Not found → 404 `CONNECTION_NOT_FOUND`
  (naming the code). Why no fallback: with one, creating an application-owned
  connection later with the same code as a shared one would silently switch
  which credentials sign a subscription's deliveries.
  `sharedConnection: true` without a `connectionCode` → 400
  `SHARED_CONNECTION_REQUIRES_CODE`.
- `connectionId` and `connectionCode` both sent but naming different
  connections → 400 `CONNECTION_MISMATCH`.
- **Scope consistency on the `connectionId` path** (an id can name any row) →
  400 `CONNECTION_SCOPE_MISMATCH` when the connection is bound to a different
  client than the sync's (a global sync may use only a global connection), or
  belongs to a different application (shared connections are usable by
  anyone). The code path cannot violate either by construction — a would-be
  violation there is simply `CONNECTION_NOT_FOUND`.
- The `platform:admin:subscription:synced` rollup event gains `clientId`
  (seeded JSON schema updated — it is `additionalProperties: false`).

Left alone, as found: `msg_subscriptions.client_scoped` /
`client_identifier` and `msg_connections.client_identifier` are never written
by any create, update or sync path in Go. Synced rows leave them unset too.

## Rollout

1. Run `scripts/ops/056-application-scope-prescan.sql` against production;
   resolve any rows it returns.
2. Deploy.
3. As an anchor user, `POST /bff/roles/sync-platform`. The seeder never rewrites
   a role that already exists, so without this step existing `messaging-admin` /
   `application-service` roles do NOT hold the new connection permissions and
   every connection sync is 403.

## Already mirrored — nothing to do

- Seeded event-type schema version `v1` → `1.0` (Go migration 055): Java has
  it as `V11__platform_event_schema_version.sql` and the seeder writes `1.0`.

## Reference commits (Go, `main`)

| Commit | Contents |
|---|---|
| `56aea7c` | subscription sync accepts `connectionCode` |
| `291ae7a` | migration 056, connection `applicationCode` + `source`, per-application uniqueness |
| `f06ebc2` | connection sync endpoint, permissions, ownership rule |
| `774a18b` | subscription sync `clientId` + `sharedConnection`, scope checks |

`api/openapi.lock.json` at `774a18b` or later is the wire authority for the
request/response shapes above. The integration tests beside each use case
(`connection/operations/sync_pg_test.go`,
`subscription/operations/ops_pg_test.go`,
`sdksync/sync_{connections,subscriptions}_http_pg_test.go`,
`migrate/application_scope_pre{check,scan}_pg_test.go`) are the executable
statement of every rule here — the scoped-removal tests in particular were
mutation-checked and are the ones to port first.
