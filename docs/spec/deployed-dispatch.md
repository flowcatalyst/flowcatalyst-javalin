# Deployed dispatch — the scheduler has no real publisher outside dev (2026-09-11)

Status: **§4 built in Java 2026-09-12 (`ecfff18`); §3 fully ruled, no open
design question — buildable.** Found by the verification plan's Phase 0
inventory (`docs/deployments.md`).

The 2026-09-12 rulings that closed §3's remaining questions, and the evidence
behind each, are in `docs/go-mirror/2026-09-12-dispatch-rulings.md`. Where that
file and §3's older prose disagree, **the rulings file wins** — three of §3's
statements below are now superseded and are marked inline.

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
   ~~*Assumed, not stated:*~~ **Ruled (R5, 2026-09-12):** platform-wide
   (client-less) jobs use `platform` in the tenant position —
   `FC-{env}-platform-DEFAULT.fifo`. A client identifier of literally
   `platform` must be refused at creation so it cannot collide.
2. **Priorities: `DEFAULT` and `HIGH_PRIORITY` only**, carried by
   `msg_subscriptions.queue`.
   **Superseded in part (R1, 2026-09-12):** "read-only and set by nothing" was
   understated — *nothing on either side can write it*, and the SPA's `queue`
   field is silently discarded by both servers. `queue` is therefore being
   added to subscription create/update, validated **case-insensitively** to the
   two names (R1a) so the SPA's existing `"default"` keeps working.
   ~~*Assumed:*~~ **Ruled (R6):** a job with no subscription, a blank value, or
   an unrecognised one all publish as `DEFAULT`, never an error.
3. **Queues are created lazily**, on first publish, as Integral does. Pairs
   with the router's 2026-09-11 behaviour: a queue that does not exist yet is
   consumed by nobody and alerts no one.
4. **One `FLOWCATALYST_CONFIG_URL`**, comma-separated: Integral's endpoint and
   the platform's. The merge already exists (first definition per key wins).
5. **Pool keys follow Integral's** `{tenant}-{pool}`, so the platform's are
   `{clientIdentifier}-{poolCode}` and `platform-{poolCode}`.
   **Confirmed against the code (R7, 2026-09-12):** this contradicted
   `PoolCodeResolver`, which published platform-level pools *unprefixed*
   (dispatch-seam §2 / ledger R-16). The owner kept item 5, so R-16 is
   superseded and `PoolCodeResolver` changes — a wire change for
   platform-wide jobs, so a Go divergence until mirrored. The document needs
   no `*-DEFAULT-POOL` rows: `RouterManager.poolFor` synthesises any such code
   on demand.

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
- **Where it is served (R3, 2026-09-12):** on the platform's **internal
  listener** (`FC_METRICS_PORT`, 9090), which is not ALB-facing, reached
  through a new Service Connect alias. No authentication, and no secret on
  either side. An earlier same-day answer chose a shared-secret header and was
  reversed once it emerged that the fc-router task role deliberately has no
  Secrets Manager access. **This gates the feature on an IaC change** (owner:
  the alias varies deployment by deployment and will be added separately).
  `/api/config/*` is already taken on the platform, so the path is
  `/api/dispatch/router-config`.
- The scheduler publishes each claimed job to its client's queue for its
  priority, creating the queue if missing. FIFO: message group = the job's
  message group. **Superseded (R2, 2026-09-12):** the dedup id is *not* the job
  id — that collides with ledger rule R-18 and would let SQS FIFO silently drop
  a `StaleQueuedJobPoller` re-publish inside its 5-minute window. It is the job
  id **plus the publish attempt**, so it is never reused.
- **Batching (O2):** `PendingJobPoller` claims 100 and SQS caps a batch at 10,
  so `publish` chunks and, on failure, reverts **only the unpublished
  remainder** to `PENDING`. `DispatchPublisher`'s documented all-or-nothing
  contract cannot hold against SQS and must be updated.

**Router:** nothing. It learns the queues and pools from the merged config,
and already tolerates a queue that does not exist yet. (R3 keeps this true —
the config URL it already reads is the only thing that changes.)

**Dev mode:** `fcdev` points `FLOWCATALYST_CONFIG_URL` at its own platform
and drops `Router.configSource`'s fixed single-queue branch, so dev and prod
differ only in the queue *type* the same document names.

**Scope of that removal (R4, 2026-09-12):** the branch goes **entirely**, not
just for dev — one code path, as intended. Accepted consequence: a bare server
run with `FC_DEFAULT_BROKER=postgres` and no config URL stops consuming
anything. In Java that touches `Router.configSource`,
`Router.usesDefaultPostgresBroker` (which `Main` uses to decide whether a
router-only process needs a database pool), and five behaviours pinned by
`RouterConfigSourceTest`. Check the equivalent Go surface before removing it
there and report what breaks.

### Risks to pin with tests

- ~~A client whose identifier changes would rename its queues.~~ **Closed
  (O1, 2026-09-12):** `tnt_clients.identifier` is already immutable in code on
  both sides — no `withIdentifier`, `UpdateClient` carries only `{id, name}`,
  and `docs/spec/client.md:23` says so. Queue names keep the readable
  identifier. Pin the immutability with a test so it cannot be relaxed
  unnoticed; the residual risk of a direct SQL update orphaning that client's
  queues is accepted, not defended against.
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

### What was actually built (Java, 2026-09-12)

The callback alias and the three OIDC TTLs, `docs/go-mirror/2026-09-11-deployment-env-handoff.md`
§1–§2 — Java built first, per that hand-off's item 3 note.

- **`DISPATCH_SCHEDULER_PROCESSING_ENDPOINT`**, alias of the existing
  `FC_DISPATCH_PROCESSING_ENDPOINT` (`EnvReader.firstSet`, canonical first).
  Default unchanged: the computed `http://localhost:<apiPort>/api/dispatch/process`.
- **`FC_JWT_ACCESS_TOKEN_TTL_SECS`** (existing canonical) gains the alias
  `OIDC_ACCESS_TOKEN_TTL`, default 3600 — via the new `EnvReader.longAlias`
  (`integerAlias`'s semantics widened to `long`: an unparseable canonical
  value falls through to the alias, never straight to the default).
- **`FC_SESSION_TTL_SECS`** (new canonical), alias `OIDC_SESSION_TTL`,
  default 86400 (24h, unchanged) — `Env.sessionTtlSeconds`. Threaded into
  `TokenIssuer.Config.sessionTtlSeconds` (the session JWT's `exp`) and into
  `SessionCookie`'s `Max-Age` (now constructor state, not the old
  `TokenIssuer.SESSION_TTL_SECONDS` compile-time static); the two are kept
  equal by construction from this one value, pinned by a dedicated test
  (`LoginApiTest#configuredSessionTtlKeepsTheCookieMaxAgeEqualToTheJwtLifetime`).
  This ruling supersedes C-Q16 ("session TTL is compile-time") for these two
  classes; the superseded ruling is still named in both classes' docs, not
  deleted.
- **`FC_REFRESH_TOKEN_TTL_SECS`** (new canonical), alias
  `OIDC_REFRESH_TOKEN_TTL`, default 604800 (7d, unchanged) —
  `Env.refreshTokenTtlSeconds`. Threaded from `Platform` into `OAuthState`
  and `RefreshRotation` as constructor state (never a static or singleton),
  reaching `RefreshToken.issue`'s TTL at `/oauth/token` issuance and at
  rotation. This ruling supersedes C-Q16 ("7 days is the refresh family's
  absolute cap") for freshly-issued tokens; the superseded ruling is still
  named in `RefreshToken`'s docs, not deleted.
  - **Carve-out 1 — `GrantStore` hydration.** The fallback that reconstructs
    a legacy row's expiry when `expires_at` is `NULL` (`GrantStore.java`,
    `toRefreshToken`) deliberately keeps the historical `RefreshToken.TTL_SECONDS`
    constant, never `Env.refreshTokenTtlSeconds()`: it is reconstructing what
    the row's expiry *was* when it was written, not assigning a fresh TTL. A
    deploy-time TTL change must not retroactively extend already-issued
    legacy tokens.
  - **Carve-out 2 — rotation never extends.** `RefreshRotation.rotate` still
    calls `RefreshToken.issue` with the configured TTL (so the value has to
    be threaded through), then immediately overwrites the result with the
    presented token's own `expiresAt` (`withExpiresAt(stored.expiresAt())`).
    A family's absolute cap is set once, at first issuance, and rotation
    never moves it — even when the configured TTL is later raised.
- Both TTL changes and the callback alias are mirrored to Go per
  `docs/go-mirror/2026-09-11-deployment-env-handoff.md` §1–§2, per this
  section's own "Java builds first" note.
