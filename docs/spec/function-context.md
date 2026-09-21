# Spec — what a function is given: config, secrets, logger, database, HTTP, events (package D, slice D4)

Design: `docs/function-runner-plan.md` §5 (`FunctionContext`), §7 (data access), §10 items 2, 10.
API: `function-api` `FunctionContext` (D1/I3). Builds on B (`function-api.md`), I2 (`function-invocation.md`),
D2/D3 (host). Owner rulings 2026-09-20:

- **R12** config values and secrets are **stored on the platform, per function**, and reach hosts in
  desired state.
- **R13** a function emits events **through the host, on its behalf** — no application credential is
  distributed — and **may emit only event types its own application owns**.

## 1. Platform: config and secrets (slice D4a)

Per **function**, not per version: a value outlives a deploy; what a *version* brings is the list of
keys it needs (`manifest.config`, `manifest.secrets`, `manifest.db[].secretRef`).

Schema (V12, unreleased, edit in place; Java-only; named per the registry spec's rule):
`fn_config(function_id → fn_functions ON DELETE CASCADE, key VARCHAR(100), value TEXT NOT NULL,
updated_by, updated_at, pk (function_id, key))`; `fn_secrets(… same …, value_ref TEXT NOT NULL)` —
`value_ref` is `Encryption`'s `encrypted:` form under `FLOWCATALYST_APP_KEY`, exactly as service-account
secrets are stored. Keys: `^[A-Za-z][A-Za-z0-9_./-]{0,99}$` (`SETTING_KEY_INVALID`) — the manifest's
`config`/`secrets`/`secretRef` entries are held to the same rule (package A `Manifest`: add it).

Routes (`FunctionApi`; reach as every by-address route; outside the lockfile; `parity/surface.json`):

| Route | Permission | Behaviour |
|---|---|---|
| `GET /api/functions/{address}/config` | `FUNCTION_VIEW` | `{values: {k: v}, declared: [keys the live manifest declares], missing: [declared and unset]}` |
| `PUT /api/functions/{address}/config` | `FUNCTION_MANAGE` | full replacement `{values: {k: v}}`; ≤ 100 keys, value ≤ 8 KiB (`SETTING_TOO_LARGE`); 200 with the GET shape. `SetFunctionConfig`; event `platform:function:config:updated` with **keys only** |
| `GET /api/functions/{address}/secrets` | `FUNCTION_VIEW` | `{keys: [{key, updatedAt, updatedBy}], declared, missing}` — **never a value, in any response, log, event, audit row or `toString`** |
| `PUT /api/functions/{address}/secrets/{key}` | `FUNCTION_SECRET_MANAGE` (`platform:function:secret:manage`, new; to `messaging-admin` and `function-publisher`) | `{value}`; value ≤ 8 KiB, non-empty; 204. `SetFunctionSecret`; event `…:secret:set` (key only). The audit command's `toString`/JSON masks `value` — check how `aud_logs` serialises the command and prove the value is not in the row |
| `DELETE …/secrets/{key}` | same | 204; `…:secret:deleted`. 404 when absent |

`FLOWCATALYST_APP_KEY` unconfigured ⇒ the secret routes are `503 ENCRYPTION_UNCONFIGURED` and
desired state carries no secrets (and says so: see `missingSecrets` below) — never plaintext at rest.

**Promote** gains a check: every key the version's manifest declares (`config`, `secrets`, each
`db[].secretRef`) must have a value ⇒ else conflict `SETTINGS_MISSING` naming the keys. Failing at
promote is kinder than a function that loads and throws on its first `require`. Publish does not
check (values are often set between publish and promote).

**Desired state**: an entry carries `config: {k: v}` and `secrets: {k: v}` **restricted to the keys
that entry's own manifest declares** — a host never receives a secret the version did not ask for —
plus `missingSettings: [keys]` when any declared key is unset (a candidate may be missing some). Same
masking discipline as `webhookSigningSecret`; they are in the hashed bytes, so a change moves the ETag.

## 2. Host: the context (slice D4b)

`HostFunctionContext` is built when a version is loaded and passed to `init` and every `handle`.

- **`config()` / `secrets()`**: the entry's maps. `get` ⇒ `Optional`; `require` on an undeclared or
  unset key ⇒ `IllegalStateException` naming the key (never the value). `Secrets.toString` prints keys.
- **A settings change reloads the function**: the reconciler compares each loaded version's settings
  fingerprint (sha256 over the sorted config+secret maps) with the new document's; different ⇒ load a
  fresh `LoadedFunction` with a new context (`init` runs again), swap, close the old after it drains —
  the same "new before old" path as a promote. A function never observes a half-updated context.
- **`logger()`**: a `System.Logger` backed by SLF4J logger `fn.<address>`. **The MDC keys are set on the
  worker thread that runs the function** (D3 set them on the request thread; Logback's MDC is not
  inherited by child threads, so a function's own log lines carried none) and cleared in its `finally`.
  Parameterised messages are formatted with `MessageFormat` as `System.Logger` specifies; a throwable
  goes to `setCause`. Level mapping: `TRACE/DEBUG/INFO/WARNING/ERROR` ⇒ the SLF4J level; `ALL`/`OFF` as
  the JDK defines.
- **`dataSource(name)`**: `name` must be a `manifest.db[].name` ⇒ else `IllegalArgumentException`.
  The DSN is the secret named by that entry's `secretRef` (a JDBC URL with credentials, or
  `postgres://user:pass@host/db` — accept both, normalise to JDBC; **PostgreSQL only**, the one driver
  on the host's class path; anything else is a load failure `DB_UNSUPPORTED`). `DbPools`: one
  HikariCP pool per distinct (normalised DSN), shared by every function declaring it, `maximumPoolSize`
  = the **largest** `poolSize` among its current users (raised on demand, never above the client
  ceiling the platform already enforced), wrapped in the server's `GatedDataSource`; reference-counted
  by loaded version; closed when the last user unloads; at most `FC_FN_MAX_DB_POOLS` (default 16)
  distinct pools — one more is a load failure `DB_POOL_LIMIT`, not an eviction of a pool in use.
  The function receives a `DataSource` whose `close`/`unwrap` do nothing/refuse — it cannot close the
  shared pool or reach Hikari. The DSN appears in no log line or exception message (mask the URL's
  userinfo and password parameters in anything that prints it).
- **`http()`**: `HttpCaller.send(HttpCall)` over one shared `java.net.http.HttpClient`. The target
  host must match `manifest.httpAllow` — an entry is an exact host name or `*.suffix` (matches
  subdomains, not the apex); schemes `https` only unless the host is `localhost`/`127.0.0.1`;
  redirects are **not** followed (the function sees the 3xx — following one would step outside the
  allowlist); timeout = `min(HttpCall.timeout, time left before the invocation deadline)`; refusal ⇒
  `HttpCallRefusedException` (API-jar type) naming the host. Design §5 is explicit that for JVM
  functions this is a convention, not containment — say so in the API doc.
- **`events()`**: §3.
- **`clock()`**, **`address()`**, **`version()`**: as D1.

## 3. Emitting events (slice D4c)

`POST /control/functions/events` (`requireAnchor` + `FUNCTION_HOST_CONTROL`; 401 without a credential):

```json
{ "hostId": "…", "address": "billing.invoices.create", "version": 12,
  "events": [ { "type": "billing:invoices:invoice:created", "subject": "…", "dedupId": "…",
                "data": { … }, "correlationId": "…", "causationId": "…", "messageGroup": "…" } ] }
```

Checks, in order — each its own code; **nothing is written unless every event passes**:

1. `hostId` is a host whose last heartbeat is inside `LIVE_WINDOW` (`HOST_UNKNOWN`, 409);
2. the function exists and is `ACTIVE`, and `version` is its live version or its newest published
   candidate **in that host's pool** (`FUNCTION_NOT_SERVED_BY_HOST`, 409) — a host can speak only for
   what it runs;
3. 1–100 events; each `dedupId` non-blank and unique in the batch (`DEDUP_ID_REQUIRED`,
   `DEDUP_ID_DUPLICATE`); `data` an object ≤ 256 KiB;
4. **ownership (R13)**: each `type` is an existing, non-archived event type whose `application`
   equals the function's application code ⇒ else `403 EVENT_TYPE_NOT_OWNED` naming the type and both
   applications. An unknown type is the same 403 (not a 404 that maps the catalogue).

Then the events are written through **the same mapper and repository `POST /api/events/batch` uses**
(`EventIngestMapper`; same dedup behaviour — a repeated `dedupId` is the ingest path's idempotent
no-op, reported per event as the ingest routes report it), with `source = function:<address>`,
`clientId` = the function's owner (absent for a platform function), the correlation fields as given.
Response `{results: [...]}` in the ingest routes' shape.

Host: `Events.emit(OutboundEvent)` ⇒ one POST (batch of one) with the host's own token;
`dedupId` is required by the API type; `correlationId` defaults to the inbound event's own `correlationId` when the request was a
webhook delivery that carries one (the emitted event belongs to that flow), else `X-Correlation-Id`,
else the invocation id; `causationId` defaults to the inbound event's id; non-2xx ⇒ `EventEmitException` (API-jar type) carrying the platform's error code, so a
function can `Result.retry` on a 5xx and fail loudly on `EVENT_TYPE_NOT_OWNED`.

### 3.1 The same gap on the ordinary ingest path — not fixed here, written down

`POST /api/events` and `/api/events/batch` check one coarse permission (`BATCH_EVENTS_WRITE`) and
nothing about **whose** event type is being emitted: any principal with that permission can emit any
application's events. The owner's ruling (R13's second sentence) is that this should hold on the
outbox/ingest path too. It is a change to a Go-parity route that every SDK and outbox producer uses —
its own unit on `main`, with its own spec (what "owns" means for an application-scoped service
account vs a user; rollout: warn-then-enforce?). `docs/backlog.md` gets the entry; this slice does
not touch the ingest routes.

## 4. Load-bearing behaviours (one mutant per condition; absence as well as presence)

| # | Behaviour | Mutant |
|---|---|---|
| X1 | a secret value is in **no** HTTP response, `msg_events` row, `aud_logs` row, log line or `toString` (search every captured artefact for the literal) — set, replace and delete; the stored `value_ref` is not the plaintext and decrypts to it | store plaintext; echo the value in the event; unmask the command |
| X2 | desired state carries only declared keys: an undeclared stored secret is absent; `missingSettings` lists declared-unset keys; a settings change moves the ETag | send everything; omit `missingSettings` |
| X3 | promote refuses with `SETTINGS_MISSING` naming exactly the missing keys — config, secret, and `db` secretRef each in isolation — and succeeds once set | skip each of the three sources |
| X4 | no app key ⇒ secret routes 503, nothing stored | fall back to plaintext |
| X5 | host: `require` of an unset key throws naming the key; a settings change reloads — `init` runs again with the new values, the old instance is closed after draining, and an in-flight call on the old instance sees the **old** values throughout | mutate the context in place |
| X6 | function log lines carry `function`, `version`, `invocation_id`, `correlation_id` (capture the SLF4J output of a fixture function that logs); a line logged by the host after the invocation does not | set MDC on the request thread only |
| X7 | `dataSource`: undeclared name throws; two functions with one DSN share one pool (assert one Hikari pool, size = the larger `poolSize`); the pool closes when the last user unloads and not before; limit ⇒ `DB_POOL_LIMIT`; `close()` on the handed-out `DataSource` does not close the pool; the DSN's password is in no log line or exception (real queries against `TestPg`) | one pool per function; close on first unload; pass Hikari through |
| X8 | `http`: exact host allowed; `*.suffix` allows `a.suffix` and not `suffix` nor `evilsuffix`; `http://` refused except loopback; a 302 is returned, not followed; the timeout is cut to the remaining deadline | `endsWith` without the dot; follow redirects |
| X9 | emit: each of the four checks in §3 in isolation, **and nothing is written when one event of a batch fails ownership** (assert no `msg_events` row for the good ones); a repeated `dedupId` is idempotent; `source`/`clientId` as specified | skip ownership; write the passing ones; unknown type ⇒ 404 |
| X10 | emit end to end in process (extends D3's H15): the function handles a webhook, emits an owned event ⇒ the event row exists with correlation = the inbound delivery's; emits a not-owned type ⇒ `EventEmitException` with `EVENT_TYPE_NOT_OWNED` and no row | — (integration pin) |
