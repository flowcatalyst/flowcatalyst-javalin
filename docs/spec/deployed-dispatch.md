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

### Settled (owner, 2026-09-12)

1. **Naming: Integral's convention**, `FC-{env}-{tenant}-{queue}.fifo`, with
   the client's identifier as `{tenant}` and the priority as `{queue}`:
   `FC-staging-acme-DEFAULT.fifo`, `FC-staging-acme-HIGH_PRIORITY.fifo`.
   Sanitised as Integral sanitises (`_`, `.`, space → `-`), refusing a blank
   tenant rather than producing a shared lane.
   *Assumed, not stated:* platform-wide (client-less) jobs use `platform` in
   the tenant position — `FC-{env}-platform-DEFAULT.fifo`.
2. **Priorities: `DEFAULT` and `HIGH_PRIORITY` only**, carried by
   `msg_subscriptions.queue` (today read-only and set by nothing).
   *Assumed:* a dispatch job with no subscription gets `DEFAULT`; a blank or
   unrecognised `queue` value is `DEFAULT`, not an error.
3. **Queues are created lazily**, on first publish, as Integral does. Pairs
   with the router's 2026-09-11 behaviour: a queue that does not exist yet is
   consumed by nobody and alerts no one.
4. **One `FLOWCATALYST_CONFIG_URL`**, comma-separated: Integral's endpoint and
   the platform's. The merge already exists (first definition per key wins).
5. **Pool keys follow Integral's** `{tenant}-{pool}`, so the platform's are
   `{clientIdentifier}-{poolCode}` and `platform-{poolCode}`.

### What each side builds

**Platform (Java now, Go mirrored):**
- Serve the router config document — the existing `{processingPools, queues}`
  shape — listing, per client with dispatch work: its queues (one per
  priority in use) and its pools from `msg_dispatch_pools` (code, concurrency,
  rate limit). Queue type and address come from the platform's own settings:
  `DISPATCH_QUEUE_TYPE` (`SQS` deployed, `postgres` in dev) and the account
  and region from `DISPATCH_QUEUE_URL` / `DISPATCH_QUEUE_REGION`, with names
  built as above. **One new setting is unavoidable**: the `FC-{env}` prefix,
  since neither side has an app-environment name today — propose
  `FC_DISPATCH_QUEUE_PREFIX` (IaC: `FC-staging`), no default outside dev.
- The scheduler publishes each claimed job to its client's queue for its
  priority, creating the queue if missing. FIFO: message group = the job's
  message group, dedup id = the job id.

**Router:** nothing. It learns the queues and pools from the merged config,
and already tolerates a queue that does not exist yet.

**Dev mode:** `fcdev` points `FLOWCATALYST_CONFIG_URL` at its own platform
and drops `Router.configSource`'s fixed single-queue branch, so dev and prod
differ only in the queue *type* the same document names.

### Risks to pin with tests

- A client whose identifier changes would rename its queues; messages in the
  old queue are then orphaned. Decide before this ships: forbid the rename,
  or keep the queue name from the id rather than the identifier.
- Pool-key collisions with Integral's tenants (first definition wins, so a
  collision silently takes Integral's pool).
- The prefix being unset in a deployed environment must be a startup error,
  not a queue literally named `FC-{env}`.

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
