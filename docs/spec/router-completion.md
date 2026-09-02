# Router completion plan — Java vs. the implementation-neutral specification

_Written 2026-09-02 by the orchestrator. Authority order: the ruling ledger
(`../flowcatalyst-rust/docs/owner-questions.md`), the conformance corpus
(`conformance/mediation-outcomes.json`), the specification
(`../flowcatalyst-rust/docs/router-specification.md`), then Go
(`../flowcatalyst-go`, which absorbed every ruling in `2e2e466..7ae5acd`).
`docs/spec/router.md` is the deep behavioural detail but is **pre-ruling** —
where it disagrees with the ledger, the ledger wins._

Baseline on `a2e266d`: server 2156 tests, **1 failure** — `process-error-500`
against the uncommitted corpus row (R-57). Everything below was found by a
four-slice read-only audit of the Java against the specification's MUSTs,
verified by reading code, not keywords.

## 1. Gap table

Status: DONE means implemented and pinned. Only PARTIAL / MISSING rows are
listed; everything else in the specification was found DONE.

| Ref | Gap | Unit |
|---|---|---|
| §4.4 R-57 | Only 501 is special-cased; 500/505+ fall into the 5xx-transient branch (breaker failure, 30s delay, no warning, retry burst). Corpus row `process-error-500` now demands ErrorConfig / delay 0 / warning ERROR / breaker success / metric failure / disposition REJECTED | 1 |
| §4.4 R-12 | Breaker keyed by full URL incl. query; `BreakerRegistryTest.queryStringSeparatesBreakers` pins the pre-ruling behaviour | 1 |
| §4.5 | Ungrouped `flushGroup` is silently ignored — MUST be logged | 1 |
| §11 | Java runner never asserts the corpus `metric` column | 1 |
| §7.3 | CONNECTION, RATE_LIMIT, CIRCUIT_BREAKER never constructed (Go: same) | 1 (breaker, rate limit), 4 (connection) |
| §0 / A-01 | `BLOCK_ON_ERROR` ACKs untried siblings **unconditionally** with no settled hook and no platform half — a MUST-NOT. Must release them until a platform URL is configured | 2 |
| §5.4 A-01 | No `FC_ROUTER_PLATFORM_URL`, no settled reporter | 2 |
| §2.3 R-13/R-16 | No `FC_ROUTER_STRICT_ROUTING` gate at all | 3 |
| §2.2 R-59 | Synthesised `{client}-DEFAULT-POOL` never evicted; no idle-TTL config | 3 |
| §2.1 layer 2 | `InFlightTracker.ensureTracked` built, tested, never called | 3 |
| §2.1 | A redelivery never kicks a dead drainer; `OrderedGroups.claimDrainer` unused | 3 |
| §2.4 / §6 | Consumer pauses on **any** pool full process-wide, not on the pools its own last batch fed | 3 |
| A-10 | Config fetched once per leadership gain; no 5-minute re-poll; `POST /config/reload` is a stub | 4 |
| R-30 | No per-source last-known-good; partial failure drops that source's pools; only a log line | 4 |
| R-33 | Reload not leadership-gated (moot while it is a stub) | 4 |
| X-11 | Removed pool: `pools.remove` + synchronous `close()` inside reconfigure (blocks up to ~12s, invisible to monitoring while draining) | 4 |
| X-11 / R-26 | Removed or changed queue: consumer closed at once; no lingering set; a buffered/in-flight message on that queue can no longer ack/nack (`QueueBroker` logs and drops) | 4 |
| R-26 | `ConsumerSupervisor` (stall restart + escalation) fully built, never wired | 4 |
| R-36 | Readiness driven by warnings only; consumer liveness never feeds it | 4 |
| §7.3 | QUEUE_HEALTH never constructed | 4 |
| R-04 | No blocked/held-groups route (needs per-group snapshot and pools visible while draining) | 4 |
| X-04 | Notifier floor hardcoded; no `FC_NOTIFY_MIN_SEVERITY` | 5 |
| X-04 | No INFO-specific TTL | 5 |
| A-08 | `WarningStore.cleanup()` never scheduled in production | 5 |
| R-52 | Group-flush suppressions not exposed; registry cannot enumerate per group | 5 |
| R-53 | Suppressed counter absent from Prometheus | 5 |
| R-56 | `standby-status.instance_id` still the lock key; `RouterApiTest.standbyStatus` pins the defect | 5 |
| §3.4 / §9 | No dispatch scheduler, no `/api/dispatch/process`, no `GroupHolding`, no `/api/dispatch/settled`, no reaper, no `Cancel`/`Complete` verbs; two `DispatchMode` enums with opposite defaults (X-01) | 6 (platform) |

## 2. Rulings taken by the orchestrator

These are Java design decisions inside the ledger's rulings, not new rulings.

1. **REJECTED is a required component, not a status test.** `ErrorConfig`
   gains an explicit `Disposition` component (`UNDELIVERABLE` or `REJECTED`,
   nothing else — compact constructor rejects the rest) with two factories.
   The classifier chooses at the one place the 5xx boundary lives. A 3xx/4xx/
   pre-flight rejection is `UNDELIVERABLE`; every 5xx except 501/502/503/504
   is `REJECTED`. `ErrorProcess` is then only ever 502/503/504/unexpected, so
   its disposition becomes the constant `RETURN_TO_BROKER`.
2. **REJECTED is terminal on the first attempt** (R-57 "single attempt";
   corpus delay 0). The pool's `rejectionBudget` disappears; `RetryHead` is
   produced only by `RETRY_IN_PLACE`. IMMEDIATE: ACK with reason `rejected`.
   Ordered: straight to the per-mode decision (`Continue` / `BlockGroup`).
3. **The A-01 gate is the presence of a platform URL.** With
   `FC_ROUTER_PLATFORM_URL` unset the router MUST NACK-release the untried
   siblings (spec §0). With it set, it ACKs them and reports fire-and-forget
   (5s timeout, chunks of 1000, own virtual thread, messages without an auth
   token skipped). `BlockGroup` therefore carries the siblings and the *pool*
   decides ack-or-release from its reporter presence.
4. **Warning emission points for the dark categories.** CIRCUIT_BREAKER:
   WARNING once per breaker on the CLOSED→OPEN transition, INFO on OPEN→CLOSED,
   never per attempt. RATE_LIMIT: INFO once per pool when its own limiter goes
   from unlimited to limiting (the 429 path stays silent — corpus). CONNECTION:
   WARNING per queue when a poll fails, once per failure streak, cleared by an
   INFO on recovery. QUEUE_HEALTH: from the broker-stats sampler when a queue's
   metrics cannot be read. All go through the store (X-04).
5. **Lingering consumers.** `Consumer` keeps its single teardown verb; "stop
   polling" is the loop thread's interrupt. `RouterManager` moves a removed or
   replaced consumer into a lingering map keyed by queue name + instance;
   `QueueBroker` resolves active first, then lingering; the housekeeping tick
   closes a lingering consumer once `InFlightTracker` holds nothing for its
   queue that was registered before it detached.
6. **Removed pools drain.** `Pool.drain()` is a new verb: stop admitting, keep
   the buffers and drainers, and `close()` once buffer and workers are both
   empty (checked on the housekeeping tick). `RouterManager.allPools()` returns
   routing pools plus draining ones for the monitoring surface.
7. **X-06 for dispatch-job status; X-01 for dispatch mode.** `DispatchJobStatus.parse`
   is strict (legacy `ERROR` kept as a holding status; an unknown stored value
   fails the row read with its id, and a list containing it fails). Dispatch
   mode stays lenient by ruling: null/blank/unknown ⇒ `NEXT_ON_ERROR`, unknown
   logged. The two enums (`router.wire` and `platform.subscription`) are still
   separate; merging them is the last small unit.
8. **DJ-5 by the standing convention.** The resend rollup subject is the
   constant `platform.dispatchjobs.resent`, as Go has it.
9. **The reaper is not leader-gated** (each sweep is one idempotent,
   status-guarded UPDATE) but it is a held, stoppable resource of the server.
10. **The scheduler's election key is the standby lock key suffixed
    `:scheduler`**, so a router leader and a scheduler leader may differ.
11. **Publishing is Postgres or nothing.** The scheduler publishes to the
    built-in Postgres broker queue the default-broker router consumes, or to a
    loud no-op publisher; SQS/NATS publishers are deferred, as in Go.
12. **Subscriber deliveries are unsigned until the service-account aggregate
    lands.** The credentials resolver ships with a "none" implementation and
    says so at the wiring site.

## 3. Units and ownership

Three agents in parallel at most; each in its own worktree so builds do not
share `target/`. A unit owns its files exclusively; anything outside its list
is a merge conflict the orchestrator has to resolve, so agents do not touch it.

| Unit | Scope | Owns |
|---|---|---|
| 1 mediator/pool contract | R-57, R-12, metric column, ungrouped-flush log, CIRCUIT_BREAKER + RATE_LIMIT warnings, corpus commit | `router/wire/MediationOutcome`, `router/pool/{HttpMediator,Pool,OrderedGroups,PoolMetrics}`, `router/policy/{BreakerRegistry,CircuitBreaker,RateLimiter}`, `conformance/mediation-outcomes.json`, their tests, `MediationConformanceTest`, `DeliverySeamTest` |
| 2 A-01 gate + settled reporter | §0 gate, §5.4 hook | `router/pool/Pool` + `OrderedGroups` (after unit 1), new `router/settled/**`, `Env` (platform URL), `server/Router` wiring |
| 3 routing | strict gate, R-59 eviction, layer-2 dedup, drainer kick, per-consumer capacity | `router/manager/{RouterManager,ConsumerLoop}`, `router/wire/{Message,DispatchMode}` if needed, `Env` (strict, idle secs), `router/lifecycle/LifecycleLoops` (eviction tick) |
| 4 lifecycle | config re-poll, R-30, R-33 reload, X-11 drain/linger, supervisor wiring, R-36, QUEUE_HEALTH, CONNECTION, R-04 | `router/manager/{RouterServer,RouterManager,QueueBroker,ConsumerSupervisor,ConsumerLoop}`, `router/config/**`, `router/inflight/InFlightTracker`, `router/lifecycle/**`, `router/api/{AdminRoutes,HealthRoutes,PoolRoutes}`, `server/Router` |
| 5 observability | X-04 floor + INFO TTL, cleanup scheduling, R-52 routes, R-53 series, R-56 | `router/observability/**`, `router/prometheus/**`, `router/policy/GroupFlushRegistry`, `router/api/{AdminRoutes,RouterApi,Wire}` + new `GroupFlushRoutes`, `router/standby` (read only), `Env` (min severity), `server/Router` wiring lines, `LifecycleLoops` (cleanup task) |
| 6 platform seam | spec first (`docs/spec/dispatch-seam.md`), then X-01, DJ-1, GroupHolding, scheduler/publisher, `/api/dispatch/process`, `/api/dispatch/settled`, reaper | `platform/dispatchjob/**`, `platform/subscription/DispatchMode`, new `platform/dispatch/**`, `server/Platform` |

Order: 1 ∥ 5 ∥ 6-spec → 2 → 3 ∥ 4 → 6-implement. Units 3 and 4 both edit
`RouterManager`; unit 3 lands first and unit 4 rebases.

## 4. Per-unit acceptance

Every unit: `mvn -q -B -pl server -am test` green in its worktree; each
load-bearing behaviour mutation-checked (break the code, watch the named test
fail — `Claude.md` testing policy); `NoOrphansTest` still green; no test that
pins pre-ruling behaviour survives. The orchestrator merges into `main`, runs
the full uncontended suite, and commits one unit at a time.
