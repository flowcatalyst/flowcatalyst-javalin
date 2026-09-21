# Spec — how a function is invoked: always HTTP

Supersedes the proposal `function-triggers.md` (deleted). Owner rulings 2026-09-19/20, recorded in
§8. Design it amends: `docs/function-runner-plan.md` §3 ("Triggers"), §4, §4a, §5 (`Invocation`).
Builds on `function-registry.md` (A), `function-api.md` (B), `function-host-core.md` (D1),
`function-host-reconciler.md` (D2). Prerequisite on `main`: `dispatch-delivery-credentials.md`.

## 1. The model

**A function is an HTTP application.** Every invocation is an HTTP request; the function tells what
it is receiving from the **path**. There is no separate "event invocation": an event reaches a
function the way it reaches any subscriber — a subscription whose target is the function's URL,
delivered by the platform's dispatch-job processor as a signed webhook. A scheduled job likewise. A
browser or an API client likewise, minus the signature.

What the platform adds over "a webhook receiver you host yourself": the manifest **declares** the
function's subscriptions and schedules, and the platform creates them at promote and removes what a
later manifest no longer lists — so a function's wiring is versioned with its code and cleaned up
with it.

## 2. Addresses on the host

**Private entry** (VPC / Service Connect; fcdev):

```
/functions/{address}/{function-path…}              the live version
/functions/{address}:{version}/{function-path…}    that version, explicitly
```

- `{function-path}` may be empty (`/functions/a.b.c` ⇒ path `/`). The function receives
  `path = "/" + function-path` — never the `/functions/{address}` prefix.
- **The versioned form is for people, never for the platform.** The platform never writes a version
  into a subscription, a scheduled job or a route: a dispatch job stores its target URL when it is
  created, so queued and retrying deliveries would keep calling v12 after v13 is promoted and v12 is
  unloaded, and every promote or rollback would have to rewrite every subscription — the two things
  the `live` alias exists to prevent. The versioned form smoke-tests a published candidate before
  promoting it (it replaces the workplan's separate "test invoke" route). It requires a platform
  bearer token holding `platform:function:version:invoke` plus reach to the function, whatever the
  endpoint's own `auth` says; it serves only versions present in desired state (live, candidate);
  it is the one deliberate exception to "a candidate is never served" (`function-host-reconciler.md`
  R1b), and the host loads the candidate lazily for it.
- `:` is a legal path-segment character; an address cannot contain one, so the split is unambiguous.

**Public entry**: only registered `(hostname, path prefix)` pairs (§5). **`/functions/…` is refused
on the public listener (404)** — otherwise anyone on the internet reaches any function by address,
routed or not. The function sees the same `path` from either entry: `https://api.acme.com/invoices/7`
(prefix `/`) and `/functions/billing.invoices.api/invoices/7` both arrive as `/invoices/7`, so one
router inside the function serves both. The request value also carries the original `host` and
`originalPath`.

## 3. Manifest (replaces package A's `triggers`)

```json
{
  "runtime": "jvm", "entrypoint": "…", "pool": "default", "warm": false, "limits": { … },
  "endpoints": [
    { "path": "/events/*",  "auth": "webhook" },
    { "path": "/jobs/*",    "auth": "webhook" },
    { "path": "/api/*",     "auth": "platform", "methods": ["GET","POST"], "cors": { … },
      "maxBodyBytes": 1048576, "timeoutMs": 10000 },
    { "path": "/hooks/stripe", "auth": "none" }
  ],
  "subscriptions": [
    { "eventType": "billing:invoices:invoice:created", "path": "/events/invoice-created",
      "mode": "BLOCK_ON_ERROR", "maxRetries": 3, "timeoutSeconds": 30, "dataOnly": false }
  ],
  "schedules": [ { "cron": "0 * * * *", "timezone": "UTC", "path": "/jobs/hourly", "payload": { } } ],
  "public":    [ { "hostname": "api.acme.com", "pathPrefix": "/" } ],
  "config": [ … ], "secrets": [ … ], "db": [ … ], "httpAllow": [ … ]
}
```

- **`endpoints`** — `path` is a `RoutePattern`; `auth` is how **the host** authenticates a call before
  the function sees it:
  - `webhook` — the platform's signature (`X-FlowCatalyst-Signature` / `-Timestamp`, the SDK's
    `WebhookSignature` rule including its timestamp tolerance) under the **function's application's**
    signing secret (§6). What subscriptions, direct dispatch jobs and scheduled jobs use.
  - `platform` — a platform bearer token, verified locally against the platform's JWKS; the
    principal is passed in the request value. The function still decides what the principal may do.
  - `none` — the host checks nothing; the function does its own authentication (inbound third-party
    webhooks, its own sessions).
  - **`auth` is required on every endpoint** — no default. "The function manages its own auth" is a
    statement someone must make (`"none"`), never the result of leaving a key out.
  - A request matching no endpoint is 404 and never reaches the function. `methods` absent ⇒ all;
    `webhook` endpoints accept `POST` only. Patterns within one manifest must not be ambiguous
    (`ROUTE_AMBIGUOUS`, as A §5.3 — methods still separate them).
- **`subscriptions`** — the ordinary subscription fields (`mode` a `DispatchMode`, default
  `IMMEDIATE`; `maxRetries`, `timeoutSeconds`, `dataOnly` default to the subscription aggregate's own
  defaults, except `dataOnly` ⇒ `false`: a function should see the envelope). `path` **must match an
  endpoint whose `auth` is `webhook`** (`SUBSCRIPTION_PATH_NOT_WEBHOOK`) and be a literal path, not a
  pattern. One entry per `eventType` (`SUBSCRIPTION_DUPLICATE`). There is **no `filter`**: a
  subscription binding's filter has no column anywhere in the platform — it is accepted on the wire
  and dropped, for every subscription (a port gap, `docs/backlog.md`). A manifest that could carry
  one would promise filtering that never happens, so the key is an unknown field until the platform
  has real filters. `messageGroupKey` is gone: a
  subscription has no such thing — ordering is the event's own `messageGroup` plus `mode`.
- **`schedules`** — same `path` rule (`SCHEDULE_PATH_NOT_WEBHOOK`); one entry per (cron, timezone).
- **`public`** — `Hostname` + `pathPrefix` (a literal path, default `/`, no trailing slash except the
  root). The prefix is stripped before endpoint matching.

Package A's `Manifest` is reshaped accordingly (`Trigger` sealed type removed; `Endpoint`,
`SubscriptionSpec`, `ScheduleSpec`, `PublicRoute` added; both readers; every rule a code and a table
row). `fn_routes` becomes `(function_id, hostname, path_prefix)`, unique on `(hostname, path_prefix)`
across functions; the private partial index goes — private calls need no route row.

## 4. Platform-managed wiring

**Validated at publish, materialised at promote** (ruling R5). At publish: every `eventType` exists
and is not archived (`EVENT_TYPE_NOT_FOUND`); cron and zone parse (`CRON_INVALID`,
`TIMEZONE_INVALID`); any `subscriptions`/`schedules` ⇒ the application has an active service account
with a signing secret (`APPLICATION_SIGNING_SECRET_REQUIRED` — without it every delivery would be
refused by the host); `public` hostnames are verified domains of the owner (package F);
warm capacity (`WARM_CAPACITY_EXCEEDED`: the live warm versions of **other** functions in that pool,
plus this one, against `maxWarmPerHost` — a function's own live warm version is about to be
replaced, and counting it would stop a function at the cap from ever republishing). Nothing is
created.

At promote, inside the promote transaction, **reconcile to the new live manifest**: desired set from
the manifest, actual set from `fn_trigger_objects`; create / update-if-different / **delete what the
manifest no longer lists**; no difference ⇒ no write, no event. Rollback is a promote. `DeleteFunction`
deletes the linked objects, then the function. `DISABLED` pauses the linked subscriptions and jobs that are
`ACTIVE`; `enable` resumes those that are `PAUSED` — an object already in the target state (an
operator paused it by hand) is left alone, not a conflict that fails the function update.

| Object | Code | Notes |
|---|---|---|
| dispatch pool — **one per function** (R7) | `fn-<fid>` | `concurrency = maxConcurrency`; platform-wide like every pool |
| subscription, one per entry | `fn-<fid>-<8 hex sha256(eventType)>` | **`source = FUNCTION`** (R6); `applicationCode` the function's (that is what selects the signing credentials); `clientId` the owner's; `endpoint = <pool URL>/functions/<address><path>`; the function's pool; the entry's fields |
| scheduled job, one per entry | `fn-<fid>-<8 hex sha256(cron ‖ NUL byte ‖ zone)>` — `zone` as written in the manifest, `""` when absent | `applicationId` the function's; **`clientId` the owner's** (null for a platform function); `targetUrl` as above; `concurrent = false` |

Two entries of one function whose keys collide (32 bits of hash) are an internal error at promote,
never a silent overwrite of one link by the other. `<fid>` = the function id lower-cased without `fnc_` (legal in every code pattern, unique by
construction). Names carry the address for humans. **Pool URL** (R8): `FC_FN_POOL_URL`, one template,
`{pool}` **optional** — an environment with one pool, or fcdev, names the host directly and never
mentions `{pool}` at all; when present it may appear at most once. Default `http://fn-{pool}:8080`.
The template must be an absolute `http`/`https` URL with no userinfo, no path beyond an optional
trailing slash (stripped at resolve time — `endpointFor` string-concatenates `resolve(pool) +
"/functions/…"` directly), no query and no fragment; any other shape is a startup error naming
`FC_FN_POOL_URL`. Resolved at promote.

`fn_trigger_objects(function_id → fn_functions ON DELETE CASCADE, kind, object_id, trigger_key,
created_at)`, pk `(function_id, kind, trigger_key)`, unique `(kind, object_id)` — cleanup by id.

### 4.1 `FUNCTION` source — a deliberate divergence from Go (R6)

`SubscriptionSource` gains `FUNCTION`; `isSyncManaged()` is false for it, so an application's SDK
sync with `removeUnlisted` never touches a function's subscription. `chk_msg_subscriptions_source`
is widened in `V13` (unreleased). Consequences, accepted by the owner and written down:

- The constraint is on a table Go shares. `SchemaFingerprintTest` gains an explicit, named
  **divergent-constraint** allowance for exactly this definition (not a blanket filter), and
  `GoAdoptionTest` likewise.
- **While Go runs against the same database, a `FUNCTION` row breaks Go's subscription reads** — its
  reader is as strict as Java's (`UnrecognisedSubscriptionSourceException`'s twin). Functions are
  therefore a **post-cutover** feature, or Go gains the constant first. `docs/cutover.md` gets the line.
- Rollback to Go after the first function subscription exists needs those rows deleted first.

### 4.2 The two syncs that would still destroy a function's objects

Neither has a source column, and both are wrong for functions as they stand:

- `SyncDispatchPools` with `removeUnlisted` archives **every** unlisted pool platform-wide.
- `SyncScheduledJobs` with `archiveUnlisted` archives the application's unlisted `ACTIVE` jobs — and a
  function's jobs belong to that application.

Both skip any object linked in `fn_trigger_objects` (one repository read of the linked ids per sync,
passed in — the operations stay free of `fn_` knowledge beyond a `Set<String> protectedIds`).

## 5. Public routes — package F

Domain verification, `fn_domains`, RouteSync into `fn_routes`, the public listener's hostname match,
the header-overwrite rule (design §4a) — unchanged in intent, simpler in shape (§3). Not built now.

## 6. The signing secret reaches the host

A `webhook` endpoint can only be verified with the secret the platform signs with: the webhook
signing secret of the **oldest active service account of the function's application** — the same
chain the dispatch processor uses (`dispatch-delivery-credentials.md` §2), so signer and verifier
cannot disagree about whose secret it is. Desired-state entries for functions with a `webhook`
endpoint carry `webhookSigningSecret`. It changes the document's bytes, so a rotation changes the
ETag and hosts pick it up within one reconcile; the host accepts the previous secret for one
reconcile interval after a change so in-flight deliveries signed before the rotation still verify.

The desired-state document is now secret-bearing: the host never logs it, `DesiredDocument`'s
`toString` masks the field, and the route stays `requireAnchor` + `FUNCTION_HOST_CONTROL`.

A direct dispatch job reaches a function only if it is signed with that application's secret — i.e.
its `code`'s first segment is the function's application code (`dispatch-delivery-credentials.md`
§2 case 2). Say so in the developer docs (package E).

## 7. The function API (amends `function-host-core.md` §1)

- `Invocation` stops being sealed over three kinds: it **is** the HTTP request value —
  `Request(address, version, invocationId, method, path, originalHost, originalPath, pathParams,
  query, headers, body, remoteAddress, Caller caller)`; `sealed Caller = Platform /* verified webhook:
  subscription, dispatch job or schedule */ | Principal(id, type, clientId, permissions) | Anonymous`.
- `Result` is a single HTTP-response record (`status`, `headers`, `body`), not a sealed taxonomy, with
  helpers that spell the dispatch contract so authors do not memorise status codes: `Result.ack()`,
  `Result.retry(Duration)`, `Result.fail(String reason)`, `Result.http(status, headers, body)`, plus
  `Result.json(status, String json)` for a direct answer whose body is already JSON text (added in I3,
  beyond this section's original list — `function-api` has zero JSON-library dependency, so a caller
  who wants a JSON body still has to hand it over pre-built). The status/header each helper produces
  is taken from reading `dispatch-seam.md` §5 and the actual code
  (`platform/dispatchjob/processing/{ProcessingApi,SubscriberDelivery}.java`,
  `platform/scheduler/jobs/JobDispatcher.java`), pinned in `Result`'s own class doc as a table with
  file:line evidence — **not from memory, and not uniform**: `SubscriberDelivery.classify` honours a
  `429`'s `Retry-After` (integer seconds, no budget spent) and a `2xx` `{"ack":false}` body the same
  way, but `JobDispatcher.deliver` (the scheduled-job path) only branches on `2xx` vs. not — a `429`
  from `Result.retry` is treated exactly like `Result.fail` there, and the requested delay is silently
  discarded. Neither path lets a function force an *immediate* non-retryable failure: every non-2xx,
  non-429 status is retried identically until the platform's own attempt budget is spent
  (`dispatch-seam.md` §5's open question 1 — this D3-adjacent finding answers it descriptively, not
  normatively: today's behaviour, uniform, is what both processors do).
- `Webhook.event(Request)` parses the delivery body into the `Event` envelope value; `Webhook.schedule(Request)`
  likewise. Helpers, not invocation kinds. The wire shapes are `platform/dispatchjob/processing/
  DeliveryPayload.build`'s `Envelope` (event) and `platform/scheduler/jobs/JobDispatcher.WebhookEnvelope`
  (schedule) — read directly from those payload builders, not guessed; `Event.dataJson`/
  `Schedule.payloadJson` carry the `data`/`payload` member's raw JSON text rather than a parsed tree,
  since the API jar has no JSON library to hand a richer type back through. Malformed or
  wrongly-shaped JSON throws `WebhookFormatException`, via a small internal recursive-descent JSON
  reader (`WebhookJson`/`JsonValue`, package-private) built for exactly these two envelopes.

## 8. Rulings (owner, 2026-09-19/20)

- **R5** wiring is created at promote, from the manifest; anything the manifest no longer lists is deleted.
- **R6** a new subscription source, `FUNCTION`; Java diverges from Go here (§4.1).
- **R7** one dispatch pool per function.
- **R8** pool URL by convention: one env template, default `http://fn-{pool}:8080`.
- **R9** delivery signing lands on `main` first (`dispatch-delivery-credentials.md`).
- **R10** a function is always invoked over HTTP; the manifest declares intent (webhook receiver,
  scheduled-job target, standard HTTP); standard HTTP endpoints may manage their own auth.
- **R11** functions are addressable at `/functions/{address}[:{version}]/{function-path}`, the
  function-path telling the function what it is receiving; public hostname/path routes deliver the
  same path. Orchestrator's two amendments, accepted: the version is optional and never generated by
  the platform; `/functions/…` is private-entry only.
- Orchestrator's reading, not separately ruled — say if wrong: `auth` has no default (§3); manifest
  subscription entries carry the ordinary subscription fields including `mode`.

## 9. Slices

| Slice | Contents | Needs |
|---|---|---|
| I1 | manifest reshape (A), `fn_routes` reshape, `fn_trigger_objects`, `FUNCTION` source + fingerprint allowance, sync protection (§4.2) | — |
| I2 | publish validation + promote/delete/disable reconciliation (`TriggerSync` grows `onPromote`, `onDelete`, `onStatusChange`), `FC_FN_POOL_URL`, desired state carries `webhookSigningSecret` | I1; signing on `main` merged in |
| I3 | API jar reshape (§7) | — (parallel) |
| D3 | the host listener: `/functions/…`, endpoint matching, the three auth modes, permits, timeouts, status mapping, versioned invoke | I2, I3 |

## 10. Load-bearing behaviours (one mutant per condition; absence as well as presence)

| # | Behaviour | Mutant |
|---|---|---|
| V1 | `auth` missing on an endpoint is rejected; a subscription/schedule path that matches no `webhook` endpoint (none at all; only a `platform` one; only a `none` one) is rejected | default `auth`; accept any matching endpoint |
| V2 | publish creates **nothing**; promote creates exactly the manifest's objects with `source = FUNCTION`, the function's pool, `endpoint = <pool URL>/functions/<address><path>` — **no version in it** | materialise at publish; put the version in the URL |
| V3 | promote v2 (adds X, drops Y, keeps Z): X created, Y deleted, Z untouched — no event, `updated_at` unchanged; rollback restores v1's set | rewrite all; reconcile against the old manifest |
| V4 | an application SDK subscription sync with `removeUnlisted` leaves the function's subscription; a pool sync with `removeUnlisted` by **any** application leaves the function's pool un-archived; a scheduled-job sync with `archiveUnlisted` leaves the function's job active — and each still removes an ordinary unlisted object in the same call | drop each protection; protect everything |
| V5 | delete removes the linked objects and only those; disable pauses and enable resumes them | delete by code prefix |
| V6 | `maxConcurrency` change updates the pool's concurrency and only that | — |
| V7 | desired state carries the application's signing secret only for functions with a `webhook` endpoint; rotation changes the ETag; the secret is in no log line and no `toString` | always include; log the document |
| V8 | fingerprint: exactly the widened source constraint is allowed to differ — any other change to that table still fails | blanket-ignore the table |
