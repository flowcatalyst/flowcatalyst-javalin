# Spec — function triggers: from a manifest to subscriptions, dispatch pools and scheduled jobs

Design: `docs/function-runner-plan.md` §3 ("Triggers → platform objects"), §4. Workplan §2 B
"TriggerSync". Builds on `function-registry.md` (A), `function-api.md` (B — it fills B's
`TriggerSync` seam, §5.1 step 8). `http` triggers are RouteSync, package F — not here.

**Status: proposed — four decisions below (D1–D4) depart from the design and want the owner's eye
before code.** Nothing here is built yet.

## 0. What the code says that the design did not know

1. **The router does not call the function host.** A subscription match creates a dispatch job; the
   router delivers only `{"messageId": …}` to the platform's own `/api/dispatch/process`; *that*
   endpoint (`SubscriberDelivery`) is the webhook client that POSTs to the subscription's `endpoint`
   and interprets the response (`dispatch-seam.md` §1). So "the router invokes `/invoke/{address}`"
   means: the subscription's endpoint is the host's `/invoke/{address}` URL, the platform's processing
   endpoint is the caller, and **retry, backoff and review are the dispatch-job rules**, not router
   mediation codes. Package D takes its status-code contract from `dispatchjob.md`, not from
   `conformance/`.
2. **Deliveries are unsigned today.** `Platform.java` wires `DeliveryCredentials.none()` — "every
   delivery goes out bare until [the service-account] aggregate lands". That aggregate has since
   landed (`serviceaccount/WebhookCredentials`), the resolver has not. Go signs. This is a gap in the
   port that matters at cutover **whether or not functions exist**, and the host's "verify the HMAC"
   (design §4) is impossible until it closes. It is prerequisite **T0** below.
3. **`SyncSubscriptions` with `removeUnlisted` hard-deletes every `API`/`CODE` subscription of the
   application that the caller did not list.** A function's subscription created as `API` would be
   deleted by the application's own next SDK sync. A new source value (`FUNCTION`) is not available:
   `chk_msg_subscriptions_source` is a constraint Go shares, and both readers are strict
   (`UnrecognisedSubscriptionSourceException`) — one such row would corrupt Go's list.
4. **Codes are `^[a-z][a-z0-9-]*$`** for subscriptions, dispatch pools and scheduled jobs: no dots, no
   colons. An address cannot be a code, and `.`→`-` is not injective (`a-b.c` / `a.b-c`).
5. **A subscription has no "message group key".** Ordering comes from the event's own `messageGroup`
   and the subscription's `DispatchMode` (`IMMEDIATE | NEXT_ON_ERROR | BLOCK_ON_ERROR`). The
   manifest's `messageGroupKey` names nothing that exists.

## 1. Decisions

- **D1 — Triggers are validated at publish and materialised at promote.** The design says "on each
   publish". But a published version is not serving: if v2 adds a trigger for event X and the
   subscription appears at publish, X is delivered to **v1**, which never asked for it — and on a
   first publish, to nothing. Platform objects follow the `live` version, exactly as the design's
   own RouteSync does. Promote and rollback therefore *do* touch subscriptions when the two versions'
   triggers differ; when they do not (the common case) nothing changes and nothing is written.
- **D2 — Function-owned subscriptions are `UI`-sourced, and ownership is a link table.** `UI` means
   "sync never touches it", which is the property needed. Ownership is recorded in
   `fn_trigger_objects` (Java-only), so cleanup is by id, never by a naming convention someone can
   collide with. An operator can still edit or delete such a subscription in the admin UI; the next
   promote re-asserts it, and `GET …/status` reports a linked object that has gone missing.
- **D3 — One dispatch pool per function, not per host pool.** Its concurrency is the manifest's
   `maxConcurrency`. The design's pool-wide dispatch pool needs a number the platform does not have
   (hosts × capacity) and lets one hot function starve the pool's others. Per function, the
   platform's own capacity gate holds that function's backlog at exactly the cap the function
   declared, before the host ever has to answer "busy".
- **D4 — `messageGroupKey` is replaced by `mode`** in the `event` trigger: a `DispatchMode` constant
   name, default `IMMEDIATE`. (Manifest change in package A: key set, reader, tests.)

## 2. Schema (V11 is unreleased — edit in place)

`fn_trigger_objects` — `function_id` references `fn_functions(id) ON DELETE CASCADE` · `kind
VARCHAR(20)` check in (`SUBSCRIPTION`,`DISPATCH_POOL`,`SCHEDULED_JOB`) · `object_id VARCHAR(17)` ·
`trigger_key VARCHAR(300)` (the event type code, the cron expression + timezone, or `pool`) ·
`created_at`. Primary key `(function_id, kind, trigger_key)`; unique `(kind, object_id)`. Named per
A §2's rule; joins `JAVA_ONLY_TABLES`.

The cascade removes the links, not the objects — `DeleteFunction` removes the objects first (§4).

## 3. Names

`<fid>` = the function id, lower-cased, without its `fnc_` prefix (a TSID is Crockford base32:
lower-cased it is `[0-9a-z]`). Unique by construction, legal in every code pattern.

| Object | Code | Name |
|---|---|---|
| dispatch pool | `fn-<fid>` | `Function <address>` |
| subscription | `fn-<fid>-<8 hex of sha256(eventTypeCode)>` | `Function <address> ← <eventTypeCode>` |
| scheduled job | `fn-<fid>-<8 hex of sha256(cron ‖ 0x00 ‖ timezone)>` | `Function <address> @ <cron>` |

The human finds a function's objects by name (the address is in it) or through Status; the code is
for the machine.

## 4. Behaviour

`TriggerSync` (B's seam) gains three entry points, all inside the caller's transaction:

- **`onPublish(function, version)` — validate only.** Every `event` trigger's `eventType` must be an
  existing, non-archived event type (`EVENT_TYPE_NOT_FOUND` naming it); every `schedule` cron must
  parse with the scheduled-job aggregate's own parser, timezone a valid zone id (`CRON_INVALID`,
  `TIMEZONE_INVALID`); the function's application must have a service account when there is any
  `event` or `schedule` trigger (`APPLICATION_SERVICE_ACCOUNT_REQUIRED` — it is what signs the
  delivery, T0). `warm: true` ⇒ the count of `live` warm versions in that pool plus this one must not
  exceed `maxWarmPerHost` (conflict `WARM_CAPACITY_EXCEEDED` naming the cap and the pool).
- **`onPromote(function, newLive, previousLive?)` — reconcile** the function's platform objects to
  `newLive`'s manifest. Desired set from the manifest; actual set from `fn_trigger_objects`; per key:
  create / update-if-different / delete. Order: pool first (subscriptions reference it), then
  subscriptions and jobs, deletions last. Objects are written through their own aggregates'
  repositories and transitions with their own events (`platform:admin:subscription:created` …), via
  the scoped commit — a function's subscription is a real subscription and its audit trail says so.
  A link whose object has vanished is recreated. **No difference ⇒ no write, no event.**
- **`onDelete(function)`** — delete every linked object, then the function.
- `UpdateFunction` to `DISABLED` pauses the linked subscriptions and jobs; `enable` resumes them.
  (A disabled function is absent from desired state — B §6.1 — so deliveries would only fail.)

Object fields:

- **Dispatch pool**: `concurrency = maxConcurrency`, `rateLimit = null`, platform-wide (`clientId`
  null — pools are matched by code), status active.
- **Subscription**: `source UI`, `applicationCode` = the function's, `clientId` = the owner's (null
  for a platform function), one binding = the event type, `endpoint` = `<pool URL>/invoke/<address>`,
  `dispatchPoolId/Code` = the function's pool, `mode` = the trigger's, `timeoutSeconds =
  ceil(maxDurationMs / 1000)`, `maxRetries` = the aggregate default, `dataOnly = false` (the function
  API's `Invocation` carries the envelope — design §5), `serviceAccountId` = the application's.
- **Scheduled job**: one cron, the timezone, `targetUrl` = the same endpoint, `concurrent = false`,
  scope = the owner's client, payload `{"address": …, "schedule": <cron>}`.
- **Pool URL**: `FC_FN_POOL_URL` in `Env`, a template with one `{pool}` placeholder, default
  `http://fn-{pool}:8080` — the ECS Service Connect alias convention (`deployments.md`), so an
  environment that follows the convention sets nothing. A template without `{pool}` is a startup
  error. The URL is resolved at promote and stored on the subscription; changing the template means
  re-promoting (say so in the env var's comment).

## 5. T0 — sign the deliveries (prerequisite, independent of functions)

`DeliveryCredentials` gets its real implementation: job → subscription → `serviceAccountId` → the
service account's `WebhookCredentials` (bearer token and/or signing secret, decrypted as that
aggregate already does); a scheduled job likewise through `OutboundCredentials` if that path is also
bare (check; say which). Resolution failure degrades to a bare delivery with a WARN (dispatch-seam
§5) — existing rule, keep it. Spec source: `dispatch-seam.md` §5 "Delivery request construction" and
the SDK's `WebhookSignature` test vectors, which are committed parity fixtures. **Pinned by**: a
delivery to a local HTTP server carries `X-FlowCatalyst-Signature` that the SDK's own
`WebhookSignature.verify` accepts with the service account's secret and rejects with another; mutant:
sign with the wrong secret / skip signing.

## 6. Load-bearing behaviours (one mutant per condition — see `function-artifacts.md` C5 for why)

| # | Behaviour | Mutant |
|---|---|---|
| T1 | publish with an unknown event type / bad cron / bad zone / no service account fails and persists nothing | skip each validation |
| T2 | publish creates **no** subscription, pool or job | materialise at publish |
| T3 | promote creates exactly the manifest's objects, linked; the subscription is `UI`, points at the function's pool and endpoint, carries the application's service account | wrong source; wrong pool; endpoint without the address |
| T4 | an application SDK sync with `removeUnlisted=true` after promote leaves the function's subscription in place | create it as `API` |
| T5 | promote v2 (adds X, drops Y, keeps Z): X created, Y deleted, Z **untouched — no event, `updated_at` unchanged** | rewrite everything on every promote |
| T6 | rollback to v1 restores v1's set | reconcile against the previous version instead of the new |
| T7 | `maxConcurrency` change between versions updates the pool's concurrency, and only that | — |
| T8 | warm capacity: the (cap+1)-th warm publish in a pool is 409 naming cap and pool; a different pool is unaffected; a non-warm publish is unaffected | count all pools; count lazy versions |
| T9 | delete removes the linked objects and only those (a sibling function's survive) | delete by name prefix |
| T10 | disable pauses, enable resumes, the linked subscriptions | — |
| T11 | a linked subscription deleted by hand is recreated on the next promote and reported missing by Status in between | trust the link row |

## 7. Open for the owner

- **D1–D4** above.
- **Q8 — the pool URL convention** `http://fn-{pool}:8080`: is that the alias shape you want for the
  host service in `../inhance/iac`? It becomes the default the moment this lands.
- **Q9 — T0 is a cutover item in its own right.** Java has been delivering webhooks unsigned since
  the dispatch seam landed. Should T0 go to `main` ahead of the function branch?
