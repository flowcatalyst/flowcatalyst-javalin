# Spec — admission control and time (tiers 1–3, the deadline, the pool size)

Status: owner-ruled 2026-09-06 (`docs/vertx-plan.md` §3b). Evidence:
`../test-size/RESULTS.md` §"Where the switches come from", §"The fix, measured", §"Sizing".

## 0. The two rules everything else follows from

1. **On a request path a park is untimed or it does not happen.** `Semaphore.acquire()`,
   a lock, a socket read: free on a virtual thread. `parkNanos`, `tryAcquire(t)`,
   `poll(t)`, `Thread.sleep`, `soTimeout`, `Future.get(t)`: a kernel round trip through
   the ForkJoinPool's delay-scheduler thread that on a one-core pod also preempts the
   carrier. Measured: 3.4 of Vert.x B's 3.97 switches per request were HikariCP's
   timed borrow; a pool-sized untimed gate → 0.82 and Go-level throughput.
2. **The listener's event loop owns time.** A request's deadline is a timer on the loop
   (Netty's hashed wheel, on the loop thread, costs nothing per request). When it fires
   it cancels the request's in-flight query and interrupts its virtual thread; the
   parks above all wake on interrupt.

There is **one number** in this design: the pool size (§5). Everything else is derived.

## 1. Tier 1 — the pool gate (`io.flowcatalyst.platform.shared.database.GatedDataSource`)

A `DataSource` wrapping the `HikariDataSource` that `Database.newPool` builds; every
caller that today receives the Hikari pool receives the gate instead (`Main`,
`StartCommand`, `DbCommand`/`FreshCommand`, tests). `DbSecretRefresher` still needs the
Hikari instance for credential rotation: the gate exposes `hikari()`.

- `getConnection()` = `ordinary.acquire()` (untimed) → `hikari.getConnection()` →
  a `Connection` proxy whose `close()` returns the connection **then** releases the
  permit. Every other `Connection` method delegates. `getConnection(user, pw)` is
  unsupported (throws), as nothing calls it.
- Permits: `ordinary = poolSize − reserved`, `reserved = max(1, poolSize / 16)` (2 at 32).
  `forProbes()` returns a view whose `getConnection()` takes from a separate
  `Semaphore(reserved)`; `/health` and `/ready` DB checks use that view and nothing
  else does. Hikari's `connectionTimeout` is left at its default: a permit holder always
  finds a connection, so it never fires, and if it ever does it is a bug worth the
  exception.
- **Nested-acquire guard.** A request scope binds `ScopedValue<Admission> CURRENT`
  (the seam adapter does this around the whole before/handler/after sequence; §4).
  `Admission` is a small mutable holder: `int held`, the list of currently held
  `Connection`s (for the deadline's `cancelQuery`, §4). `getConnection()` with
  `CURRENT` bound and `held > 0` throws `IllegalStateException("nested connection
  checkout inside a request: <path>")` **before** touching the semaphore. Outside a
  request scope (background workers) the gate applies, the guard does not.
- Background workers (outbox processor, stream projector, scheduler, purger, reaper)
  keep checking out through the same gate. They wait untimed like everyone else.
  Audit item (report-only, Sonnet): list every background path that holds one
  connection while checking out another; each is a future deadlock under a full gate
  and gets a ruling.
- Metrics: `fc_db_gate_waiting` (gauge), `fc_db_gate_held` (gauge), on the existing
  Prometheus registry. No timing histogram — a wait is untimed by design and its length
  is the deadline's business.

## 2. Tier 2 — local group bulkheads (`io.flowcatalyst.http.Group` + `Budgets`)

`Budgets` (in `io.flowcatalyst.http`) maps each `Group` to a `Semaphore` built once at
bootstrap from a `Budgets.Derived` record the bootstrap fills in. The seam adapter
acquires the group's permit **untimed** before the route handler of a grouped
registration and releases it after it (or on exception), binding the [Admission]
scope for the same span. On the Javalin adapter (Phase 1) that span is the route
handler — Javalin's `before`/`after` run outside it, which is why the authenticator's
own checkout is not counted by the nested-acquire guard; on Vert.x (Phase 2) the
adapter owns the whole chain and the span is before → handler → after. Order inside a
request is fixed: deadline armed (Phase 2) → group permit → pool permit per checkout;
released in reverse.

Derivations — the number is the resource the group actually contends on:

| Group | Routes | Budget | Derived from |
|---|---|---|---|
| `LOGIN` | `/auth/login`, `/auth/2fa/**`, `/auth/change-password*`, `/auth/password-reset/*`, passkey login, portal login | `Runtime.availableProcessors()` | password4j's Argon2 executor is `newFixedThreadPool(AVAILABLE_PROCESSORS)`; more logins in flight than that only queue on its pool. `equalizeTiming` (the dummy hash) counts too, so the budget covers failed logins. |
| `OIDC` | `/auth/oidc/**`, portal SSO callback | `Runtime.availableProcessors()` (= `LOGIN`, owner ruling 2026-09-06) | A login path: one session created per callback, DB-bound, no hashing; the JDK `HttpClient` has no connection limit to derive from, so it shares `LOGIN`'s derivation. |
| `DISPATCH` | `POST /api/dispatch/process` | **none on the platform side** (owner ruling 2026-09-06) | The dispatch bulkhead already exists and is already derived: the router's per-pool `Pool.Config.concurrency` bounds calls into this endpoint, and an attempt holds no DB connection while it waits on the customer (verified). A platform-side budget would protect nothing measurable. |
| `INGEST` | `/api/ingest/**` (`IngestApi`) | **none** (owner ruling 2026-09-06) | Uses the pool gate like every other request. Isolation, when ingest is busy, is a deployment of its own, not a budget. Group tag kept for the registry only. |

`DISPATCH` and `INGEST` registrations carry their group (the seam records it) with no
budget. The mechanism, the `LOGIN` and `OIDC` budgets, and the metrics
`fc_bulkhead_waiting{group}` / `fc_bulkhead_held{group}` land now.

## 3. Tier 3 — cluster-wide group budget (`io.flowcatalyst.http.ClusterBudget`)

Opt-in; **not built now** beyond the interface and its default:

```
interface ClusterBudget { Optional<Permit> tryAcquire(Group group); }   // never waits
interface Permit extends AutoCloseable { void close(); }                 // release
```

`ClusterBudget.NOOP` always grants and is the default. Future implementations:
`postgres` (row leases claimed with `SKIP LOCKED`, lease expiry for crashed nodes,
released on close) and `redis`. Rules already fixed: a node never holds a pool permit
while asking for a cluster permit (the adapter asks *before* the local group permit);
an empty answer is `503` with `Retry-After` at once — there is no cross-node waiting.

## 4. The deadline (Phase 2, Vert.x adapter)

- One product default, **30 s**, for every request. A group may declare a longer one
  only by derivation: `DISPATCH` = `SubscriberDelivery.MAX_TIMEOUT` (2 min) + 10 s,
  because the endpoint legitimately waits that long on the customer.
- Armed on the loop's timer when the request is dispatched to its virtual thread;
  cancelled when the response ends.
- On fire: (1) for every `Connection` in `Admission.held`, `unwrap(PgConnection.class)
  .cancelQuery()` — measured 8–12 ms to wake, SQLSTATE `57014`, connection reusable,
  rollback works; (2) `Thread.interrupt()` on the request's virtual thread — wakes a
  semaphore park in ~6 ms and any non-JDBC socket read (which it closes, acceptable);
  (3) the adapter answers `503` with no body if nothing has been written yet, else
  closes the connection. The handler's exception unwinds through the normal mappers;
  the `UnitOfWork` rollback and the permit releases happen in their `finally`s.
- Never `Thread.interrupt()` **without** `cancelQuery()` first: interrupt alone closes
  the pgjdbc socket and Hikari evicts the connection (measured).

## 5. The pool size

`Database.newPool(url, size)` default **32**, fixed, not derived from cores, on the
server and on fcdev alike; the existing env var overrides it. `Main.java`'s
`max(4, availableProcessors)` goes. Deployment rule (in `docs/spec/cutover.md`): pods ×
32 ≤ Postgres `max_connections` − reserved; beyond ~3 pods raise it or front Postgres
with PgBouncer in transaction mode (pgjdbc `prepareThreshold=0` then).

## 6. Tests (break-it-on-purpose each; say which assertion caught which mutant)

| # | Behaviour | Test | Mutant that must fail it |
|---|---|---|---|
| 1 | `poolSize` requests can hold connections concurrently; the `poolSize+1`-th **waits** and proceeds when one closes; total time ≈ one release, not a timeout | `GatedDataSourceTest` with a fake `DataSource` handing out stub connections; count concurrent holders with a latch | gate off → `poolSize+1` concurrent holders observed |
| 2 | The waiting checkout is **untimed**: a thread parked in `getConnection()` is still parked after 2 × Hikari's default `connectionTimeout` and gets a connection when one is released | same; assert the parked thread's state is `WAITING`, not `TIMED_WAITING` | use `tryAcquire(t)` → state `TIMED_WAITING` |
| 3 | Reserved permits: with all ordinary permits held, `forProbes().getConnection()` returns at once | assert wall time < 100 ms | reserved = 0 → hangs (bounded by the test's own timeout) |
| 4 | Ordinary callers never take a reserved permit | hold `ordinary` permits, assert the next ordinary caller waits while a probe succeeds | single shared semaphore → probe waits |
| 5 | Nested checkout inside a request scope throws before waiting | bind `Admission`, check out once, check out again → `IllegalStateException`; assert `waiting` gauge did not move | drop the guard → second checkout blocks/succeeds |
| 6 | `close()` releases exactly once; double `close()` is a no-op | permits available after two closes == before | release on every close → permits exceed pool |
| 7 | A budget of one admits one login handler; the second request is parked on the bulkhead (waiting = 1, held = 1) and completes after the first releases | `BudgetsTest` through `TestHttp.routes(budgets, …)` with `Routes.in(Group.LOGIN)` and a latch-blocked handler | release the permit before the handler → waiting stays 0 while both handlers run |
| 8 | Deadline (Phase 2): a handler running `select pg_sleep(60)` answers `503` within 30 s + 1 s, its connection is back in the pool and reusable, its permits are released | `VertxDeadlineTest` against embedded Postgres | skip `cancelQuery` → the connection is evicted (pool total drops) |
| 9 | `Main` and `StartCommand` build a gate of size 32 by default | assert `GatedDataSource.poolSize()` | leave `max(4, cores)` → 4 on the test host, or 14 |

## 7. Owner rulings on the budgets (2026-09-06)

1. `OIDC` = `LOGIN`'s derivation (`availableProcessors`).
2. `DISPATCH`: the router's per-pool concurrency is the dispatch bulkhead; the platform
   endpoint carries none.
3. `INGEST`: no budget — the pool gate like other requests; a busy ingest gets its own
   deployment.

## 8. Landed 2026-09-06 (branch `vertx-listener`)

Tier 1 (`GatedDataSource`, generated `GatedConnection`, reserved probe lane, nested
guard, gauges), tier 2 (`Budgets`, `Group` permits in the Javalin adapter, gauges),
tier 3 interface + `NOOP`, pool default 32 (`Database.newPool(url)`), probes on the
reserved lane in `Server.health`. Mutants killed: gate off (rows 1+4), timed acquire
(rows 1+4 via `TIMED_WAITING`), probes on the ordinary lane (row 3, by fork timeout),
guard removed (row 5), release on every close (row 6), permit released before the
handler (row 7). Row 8 (deadline) is Phase 2. Not yet done: the background-worker
nested-checkout audit (§1), `docs/spec/cutover.md` deployment rule (§5).
