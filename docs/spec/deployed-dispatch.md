# Deployed dispatch — the scheduler has no real publisher outside dev (2026-09-11)

Status: **direction set 2026-09-12 (§3); five open questions there before code.** Found by the
verification plan's Phase 0 inventory (`docs/deployments.md`).

## 1. What is true today, on both sides

- The dispatch scheduler hands each claimed dispatch job to a queue, which
  the router consumes and POSTs to the platform's `/api/dispatch/process`.
- **The only real publisher is the dev one.** It is used only with
  `FC_DEFAULT_BROKER=postgres`, which is what `fcdev` sets: the scheduler and
  the router share one built-in Postgres queue in one process. Otherwise both
  sides fall back to a no-op publisher:
  - Go: `internal/server/subsystems.go` `schedulerPublisher`, which logs "dispatch
    jobs will be claimed but NOT delivered; do not enable FC_SCHEDULER_ENABLED in
    production until a real queue.Publisher is wired".
  - Java: `platform/scheduler/NoopPublisher`.
- **The callback defaults to localhost.** It comes from
  `FC_DISPATCH_PROCESSING_ENDPOINT`, defaulting to
  `http://localhost:<port>/api/dispatch/process`. That reaches the platform
  only when the platform is in the same process, as in `fcdev`.
- **The IaC already describes the deployed intent, and nothing reads it.**
  `DISPATCH_QUEUE_TYPE=SQS`, `DISPATCH_QUEUE_URL` (a dedicated SQS queue it
  creates) and `DISPATCH_QUEUE_REGION` on platform and worker, plus
  `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT=http://fc-platform:8080/api/dispatch/process`
  (the Service Connect alias). Neither Go nor Java reads any of them.
- Hence "it works locally" (`fcdev`, fulfil-go test apps) and would not in
  np/prod, where the worker has `DISPATCH_SCHEDULER_ENABLED=true` but a no-op
  publisher. Dispatch is not yet in use there (owner, 2026-09-11), which is
  why nothing has broken.

## 2. What "honour them" requires (owner ruling: honour, and patch Go to match)

1. **Callback:** `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT` as an alias of
   `FC_DISPATCH_PROCESSING_ENDPOINT`. Trivial.
2. **Publisher:** with `DISPATCH_QUEUE_TYPE=SQS` and a `DISPATCH_QUEUE_URL`,
   the scheduler publishes to that SQS queue instead of no-op. It must be FIFO
   (per-group ordering is load-bearing): message group = the dispatch job's
   group, dedup id = the job id. The IaC's queue is `.fifo`, to be checked.
3. **Router consumption:** the router must consume that queue. That is §3.

## 3. The owner's direction (2026-09-12): the platform serves router config

The router is a general message router, not a FlowCatalyst component: it
already merges several config URLs, and Integral is only one of them. So
**the platform serves its own router config**, and its dispatch queues and
pools come from there — option B of the earlier draft, with dev mode folded
in.

- **One code path, dev and prod.** Dev stops injecting a fixed single-queue
  config: `fcdev` points `FLOWCATALYST_CONFIG_URL` at its own platform, whose
  config lists Postgres-backed queues instead of SQS ones. The dev/prod
  difference becomes the queue *type* in one served document, not a different
  branch in `Router.configSource`.
- **Queues per client and priority.** A dispatch job goes to its client's
  queue for its priority — `…-{client}-DEFAULT` / `…-{client}-HIGH_PRIORITY`.
  Platform-wide (client-less) dispatch jobs get their own queue.
- **Priority already exists in the data**: `msg_subscriptions.queue` is
  read-only and set by nothing today (`subscription.md` §1 calls it a possible
  accident), and dispatch jobs carry `client_id`, `dispatch_pool_id` and
  `message_group`. Confirm before building that `queue` is the intended
  carrier rather than a new column.
- **Pools** come from `msg_dispatch_pools` (code, concurrency, rate limit,
  client), which is already the router's `PoolSpec` shape.
- **The router needs no new setting**: it learns everything from the config
  document, so `DISPATCH_QUEUE_*` stays a *platform* setting (how to name and
  address the queues it advertises), not a router one.

### Open questions before code

1. **Names.** Integral uses `FC-{env}-{tenant}-{queue}.fifo`. Does the
   platform's own dispatch queue follow the same convention, and what is the
   platform-wide one called (`FC-{env}-platform-DEFAULT.fifo`?).
2. **Priority values.** `DEFAULT` and `HIGH_PRIORITY` only, or an open set?
   Does it live on `msg_subscriptions.queue`, and where does a direct
   (non-subscription) dispatch job get its priority?
3. **Who creates the SQS queues?** Integral creates them lazily on first send.
   The same for FlowCatalyst (the publisher creates if missing), or created in
   the IaC per client? Lazy pairs well with the router change of 2026-09-11:
   a not-yet-created queue is polled by nobody and alerts no one.
4. **Endpoint.** Path and auth for the platform's config document, and whether
   the router's `FLOWCATALYST_CONFIG_URL` becomes `<integral>,<platform>`.
5. **Pool keys across sources.** Integral's pool keys are `{tenant}-{pool}`;
   the merge is first-definition-wins per key, so the platform's keys must not
   collide with Integral's.

## 4. The other Phase 1 rulings (2026-09-11)

- **`OIDC_SESSION_TTL` (8 h), `OIDC_ACCESS_TOKEN_TTL` (1 h),
  `OIDC_REFRESH_TOKEN_TTL` (30 d):** honour them in Java, and patch Go at the
  same time. Today both hardcode a 24 h session. **The cutover will change
  session length in prod**, so announce it.
- **`FLOWCATALYST_JWT_PUBLIC_KEY`, `FC_WEBAUTHN_RP_NAME`, `FC_STATIC_DIR`:**
  unused by both. Remove them from the IaC; the owner applies it, and they
  are listed in `docs/deployments.md`.
- **Every Go change** is developed and verified in a scratch copy (`go build`,
  `go vet`, the package tests), saved under `docs/go-mirror/`, and applied to
  the Go working tree without committing, as with the 2026-09-09
  debug-event patch.
