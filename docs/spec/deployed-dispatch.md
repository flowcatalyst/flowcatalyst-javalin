# Deployed dispatch — the scheduler has no real publisher outside dev (2026-09-11)

Status: **needs an owner ruling** on §3 before any code. Found by the
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

## 3. The decision — how the router learns about the dispatch queue

The router's queues come only from `FLOWCATALYST_CONFIG_URL`, and today that
is Integral's control plane, which knows nothing of FlowCatalyst's dispatch
queue.

| Option | How | For | Against |
|---|---|---|---|
| **A. The router reads `DISPATCH_QUEUE_URL` itself** (recommended) | When set, the router adds that queue to whatever its config service returns, with its own pool (like the dev default broker adds its Postgres queue). The router task gets the same `DISPATCH_QUEUE_*` variables. | Smallest change. One source of truth: the IaC variable that already exists. Mirrors how dev mode works. | Pool sizing for the dispatch queue lives in env, not in the dispatch pools stored in the DB. |
| B. The platform serves a router-config endpoint | A `/api/router/config` listing the dispatch queue and the dispatch pools from the DB. The router's `FLOWCATALYST_CONFIG_URL` becomes `<integral>,<platform>` (multi-URL merge already exists). | Dispatch pools from the DB drive the router's pools. | A new endpoint and contract on both sides. The router now depends on the platform being up. Auth on that endpoint. |
| C. Integral lists it | Integral's `/api/config` includes FlowCatalyst's dispatch queue. | No FlowCatalyst code. | Couples Integral to FlowCatalyst internals; wrong owner. |

Recommendation: **A** now, designed so B can replace it later. The added
queue is just a `QueueConfig`; its source is the only thing that would
change.

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
