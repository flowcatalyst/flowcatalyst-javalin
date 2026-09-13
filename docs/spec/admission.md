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

## 9. Request-level admission (owner design 2026-09-06, landed; replaces §2's mechanism on Vert.x)

Measured in `bench/real/RESULTS.md` rounds 4–12: the pool gate's *waiting* is the tail, and
every semaphore shape trades throughput for ordering. The owner's shape gets both: **per group,
a FIFO queue and N long-lived virtual-thread workers** (`io.flowcatalyst.http.RequestWorkers`).
The Vert.x loop pushes each parsed request; a worker pops it, runs the whole seam chain, hands
the buffered response back to the loop, and pops the next. Sizes are derived: the main pool is
the gate's ordinary permits (each worker holds at most one connection, so the gate — kept, unfair,
per checkout — never waits on the request path); `LOGIN` and `OIDC` are the processor count;
`DISPATCH` has its own pool so its customer waits never occupy a main-group slot. **Routes that
never touch the database run unbounded** (`Group.NO_DB`, declared at registration: the SPA, the
OpenAPI documents, the router's in-memory API, the 404 and failure paths) — nothing to queue
for. Result at two CPUs: 98% of Go's throughput with Go's spread (round 12). On the Javalin
adapter nothing changes (its own thread model); `Budgets` still applies there.

Rules that follow: a bounded pool plus a request that calls back into the same listener is a
deadlock — the in-process router→platform call in fcdev must not go through the main pool;
queue depth is memory (a parked request holds its parsed body), and a bound on it is where
tier 3's immediate `503` arrives. Gauges: `fc_request_workers_busy{pool}`,
`fc_request_queue_depth{pool}`.

## 10. The nested checkout, resolved: a request has one connection (2026-09-06)

The nested-acquire guard (§1) fired 34 times across five sites in the parity corpus: every one a
transaction-scoped operation (`execute((scoped, cmd, ec) -> …)`) whose body called a repository
read (`findByCode`, `findById`, `findByApplication`) that opened its own connection while the
transaction held one — a deadlock under a full gate, exactly the class the guard exists for. The
template's Plan-returning `execute` reads *before* its transaction, which is why it never tripped.

Resolution, in the gate rather than at each site: **a nested checkout inside a request that
already holds a connection returns a re-entrant handle on that same pooled connection.** It joins
the outer transaction (so it sees the transaction's own uncommitted writes, as a read inside the
operation must), its `close()` releases nothing, and it refuses `commit`, `rollback`, `abort` and
`setAutoCommit` (`SQLSTATE 25000`): the outer checkout owns the transaction. No second permit, no
wait, no deadlock. Pinned by `GatedDataSourceTest` (an uncommitted write on the outer handle is
visible through the inner one; the mutant that takes a fresh connection fails it).

## 11. Endpoint groups and request-level worker pools on Javalin (plan, 2026-09-08)

The Vert.x cutover was reverted on the owner's ruling, so the listener is Javalin/Jetty again and
each request runs on its own virtual thread from Jetty's pool. `io.flowcatalyst.http.RequestWorkers`
— the per-group queue with N long-lived virtual-thread workers, §9 — was only ever wired to the
Vert.x adapter and is therefore unused code after the revert. It is kept deliberately, with its
tests, because it is the mechanism this section plans to plug back in. Nothing here is built yet.

### 11.1 Where it plugs in

`JavalinRoutes.admitted(Handler)` is the single seam: every route is wrapped by it, it takes the
group's permit, opens the `Admission` scope and calls the handler. Two mechanisms can live behind
that one method:

| | mechanism | what it costs | what it gives |
|---|---|---|---|
| **(a) today** | `Budgets.acquire(group)`, a semaphore per group, untimed, **unfair** | nothing beyond the acquire | a concurrency bound; a releasing thread can barge ahead of waiters, which is what produced the bad tail in round 12 |
| **(a′) recommended** | the same, constructed **fair** | FIFO ordering, a little throughput under heavy contention | a bound *and* predictable ordering, with one park per request |
| **(b)** | `RequestWorkers.submit(group, task)`, the Jetty thread parks until a worker finishes | one hand-off and a second thread per request | nothing (a′) does not already give, on this listener |

Neither mechanism has anything to do with the kernel-switch problem: both are **untimed** parks, so
the continuation unmounts, no timer is armed and the delay scheduler is never involved. That problem
came only from *timed* waits (`tryAcquire(timeout)`, `poll(timeout)`), which is why HikariCP's borrow
was the defect and why §1's gate fixed it.

**(b) is the wrong default on Javalin.** Its advantage on the Vert.x adapter was that a queued
request was a *task*, not a thread: the event loop had not yet created a virtual thread for it, so a
thousand queued requests cost a thousand small objects. Under Javalin the thread already exists
before the request reaches `admitted()`, because Jetty made one, so submitting to a worker parks that
thread and starts a second one — a hand-off added rather than a thread saved. The only property (b)
has left is FIFO ordering, and a fair semaphore has that by construction.

So the plan is (a′): one fair semaphore per group, acquired once per request, permits equal to the
group's pool size. `RequestWorkers` stays in the tree with its tests in case a future listener is
event-loop shaped again, but it is not the Javalin design. The fairness cost measured in round 12
(2,373 unfair against 1,959 fair) was on the *per-checkout* gate, three or four acquisitions per
request; with one acquisition per request it should be small, and that is the one thing worth
measuring before committing to it.

### 11.2 Bucketing: group by what a request holds while it waits

Not by URL shape. A request can occupy three things — a worker slot, a gate permit and a database
connection — and the groups exist so that a request holding one of them for a long time cannot
starve requests that need a different one.

| group | holds a connection | worker bound | note |
|---|---|---|---|
| ingest / dispatch (from the message router) | for its transaction | own pool size | must not be starved by user traffic |
| API / BFF **write** | whole request: the transaction pins one connection | own pool size | cannot release mid-request; a transaction lives on one connection |
| API / BFF **read** | per statement, if §11.4 is adopted | own pool size, or higher | replica-capable once identified |
| long external wait (slow downstream, minutes) | **never across the wait** | large or unbounded | bounded by memory; a parked virtual thread costs almost nothing but does occupy a worker |
| SSE | never | unbounded | see §11.5 |
| no database (health, SPA, docs, 404) | never | unbounded | as today's `Group.NO_DB` |

### 11.3 Connection pools

**Ruled 2026-09-13 (owner): one pool per group**, sized per deployment, rather than one pool
with per-group shares. Isolation is the point — a lane over a shared pool still lets one group's
slow queries occupy connections another group needs, and a separate pool can later point at a
read replica. The invariants stay derived, so the pool size remains the only number a deployment
sets:

- permits(group) = poolSize(group) — nobody ever waits inside HikariCP, where the wait is a timed
  park (§1, and the measurement in `../test-size/RESULTS.md`).
- workers(group) = permits(group) — a worker can always get a connection immediately.
- probes keep their own reservation, or their own small pool, so a saturated instance is not killed
  by its own health check.

Consequence worth stating: with background work (purger, outbox, scheduler, stream processor, mail)
moved onto its own pool, the request path's gate becomes redundant — workers = pool size is then the
whole limit and nothing can contend. The gate exists today only because those subsystems share the
request path's pool. It should be deleted at the same time as the split, not carried forward.

Sizing today is broken in both languages and must be fixed as part of this: Java's
`Database.DEFAULT_POOL_SIZE = 32` is a constant with no environment override, and Go never sets
`MaxConnections` so pgxpool defaults to `max(4, NumCPU)`, which reads the host and ignores the CPU
quota. Measured: 32 against 14 for the same deployment (`docs/backlog.md`).

### 11.3a Sizing (proposal 2026-09-13, for the owner's confirmation)

The difficulty dissolves once only one number is chosen per instance and everything else is
derived from it or observed:

1. **One number: the instance's connection budget `B`** — what this pod may hold against
   Postgres. It comes from the database, not from demand: a Postgres connection is a backend
   process, and throughput saturates at a few connections per database core, so
   `pods × B ≤ max_connections − reserved` (§5's rule) and `B` is small. Today's 32 stays the
   default `B`.
2. **The split is a product default, expressed as shares of `B`**, not per-group absolutes:
   API ½, BFF ¼, ingest/dispatch ¼; background subsystems (purger, outbox, scheduler, stream,
   mail) a fixed small pool of 4 outside `B`'s request share; probes their own reservation
   (§1); SSE none. A share is by *connection-hold time*, not request count — a group that holds
   a connection across a whole transaction needs more than one that borrows per statement.
   Every group's pool is at least 2. The per-group pool size is the accepted knob for a
   deployment that knows better; nothing else is configurable.
3. **Workers per group follow how the group holds a connection** (owner, 2026-09-13: the
   numbers above size the *DB pools*; only transaction-bound work maps one worker to one
   connection):
   - **Transaction-bound** (use-case writes; the connection is held for the whole request):
     workers = poolSize. A worker always finds a connection; the gate never waits.
   - **Reads** (borrow per statement, release between — §11.4, now required by this rule):
     workers = 2 × poolSize. The session profile put roughly half of a read's time off the
     connection (jOOQ rendering ~20%, JWT verification ~30%), so two workers per connection
     keeps the pool busy without deep queues at the gate; the untimed gate (§1) is what makes
     the extra workers cheap to park.
   - **Background** (outbox, stream, scheduler, purger, mail; mostly waiting on HTTP or
     sleeping): each subsystem keeps its own concurrency setting over the shared background
     pool of 4; connections are borrowed per statement, and many virtual threads per
     connection is the normal shape.
   - **NO_DB**: unbounded, as today. **SSE**: one virtual thread per subscriber, no pool.
   Nothing here is a knob: the multipliers are product defaults tied to the measured profile;
   the pool size per group is still the only number a deployment sets.
4. **Orderly means bounded, not merely queued.** Each group's queue holds at most
   `8 × workers` waiting requests (owner, 2026-09-13); beyond that the request is refused at
   once with 503 and `Retry-After`, and every queued request carries a deadline on the event
   loop's timer wheel (§4) so a flood fails fast on the saturated group and never touches the
   others. The deadline bounds how long a request waits; the depth bounds how large a burst is
   absorbed before refusing. A wrong split therefore shows up as 503s and queue wait on one
   group, not as latency collapse everywhere — which is the property the owner asked for.
5. **The split is corrected by observation, not guessed better.** The metrics ruled in
   `docs/backlog.md` (queue depth, in-flight, queue wait per group; pool utilisation per pool)
   give the one signal that matters: sustained queue wait on one group while another pool sits
   idle means budget should move. That is an operator's monthly glance, not a design-time
   calculation.

### 11.4 The read/write question (open)

A write holds one connection from `begin` to `commit` and cannot do otherwise. A read has no
transaction, so each statement could take a different connection and give it back, which would
roughly halve a read's hold time: the round-15 profile put ~30% of a request in the RS256 verify and
~20% in jOOQ rendering, all of it CPU spent while holding a connection that is doing nothing.
The cost is eight gate acquisitions per request instead of one. They are untimed, so no timers are
involved, but each blocked acquire is still a park and an unpark. Decide it by measurement, at ONE
core, watching switches per request rather than throughput — `../test-size/RESULTS.md` shows the
cost of a wake is invisible above one core and material on it.

### 11.5 SSE (owner ruling 2026-09-08)

No pool and no database connection. One virtual thread per connection parked **untimed** on a
bounded per-subscriber queue (`take()`, never `poll(timeout)`); the publisher `offer()`s and closes
the stream of any subscriber whose queue is full, because that client cannot keep up. Keepalive is
ONE tick on a shared timer for every subscriber, never a timer per connection. Over HTTP/2 a
subscriber is a stream on a shared connection rather than a socket, so the bound is subscriber count
and memory, not file descriptors; note that the server's max-concurrent-streams (100 by default)
becomes the per-client ceiling, and that in development over cleartext browsers fall back to
HTTP/1.1 and its six-connections-per-origin limit. A snapshot needed at subscribe time is one query
before streaming starts, borrowed from the API read group.

### 11.6 Metrics needed to run any of this

`fc_request_queue_depth` (per pool) and `fc_db_gate_waiting` / `fc_db_gate_held` (per lane) already
exist and are registered. Missing, and needed before the sizing questions above can be answered from
data rather than argument: **connection-hold time per request, per group**. Its ratio to request
duration is the fraction that decides how much admission a pool can support, and it is the reading
that says whether §11.4 was worth doing.

### 11.7 Build plan (2026-09-13, all rulings taken; the Vert.x second attempt's phase P2a)

Rulings in force: one pool per group (§11.3); sizing per §11.3a (one budget, shares by hold
time, workers by kind, queue bound 8 × workers with 503 + `Retry-After`, deadlines); reads
release between statements (§11.4, required by the 2-per-connection worker rule); SSE per §11.5
(not built here — no SSE exists yet); one port; group declared per route.

**Groups.** `io.flowcatalyst.http.Group` becomes: `DISPATCH` (what the message router calls:
processing, settled, ingest — today's `DISPATCH` and `INGEST` merge), `BFF` (what the SPA calls
under `/bff/`), `API_WRITE` (a route that runs a use case inside a transaction), `API_READ`
(every other `/api/` route), `LOGIN`, `OIDC` (unchanged, CPU-bound), `NO_DB` (unchanged). A
route declares its group at registration as today; the split of the API is by transaction
span, so an API class that mixes reads and writes declares per route, not per class. Ungrouped
`/api/` registrations default to `API_READ` and fail `LockfileCoverageTest`'s sibling check if
they run a transaction — add a test that every route whose handler runs an `Operation`/`TxOperation`
is declared `API_WRITE` (grep-level, by the adapter recording which routes opened a transaction
during the suite is better if cheap).

**Pools.** Four `GatedDataSource`s from one budget `B` (default 32, `FC_DB_POOL_SIZE` keeps
overriding it): `API` (½ B, serves `API_READ`, `API_WRITE`, `LOGIN`, `OIDC`), `BFF` (¼ B),
`DISPATCH` (¼ B), `BACKGROUND` (4, outside B; outbox, stream, scheduler, purger, mail, the
router's own Postgres queues stay on their own URI-opened pools). Per-group override:
`FC_DB_POOL_SIZE_<GROUP>`; that is the only knob. Probes keep their reservation on the API pool
(§1). `Main`/`StartCommand` open the four and hand `Server` a `Pools` record instead of one
`DataSource`; every subsystem receives the pool it belongs to, and the `Platform` registration
receives the request-path pools by group. Postgres's `max_connections` guidance in
`docs/spec/cutover.md` becomes `pods × (B + 4) ≤ max_connections − reserved`.

**Workers.** `RequestWorkers` is the Vert.x adapter's dispatch for every grouped route:
`API_WRITE` = API pool size; `API_READ` = 2 × API pool size; `BFF` = 2 × BFF pool size (BFF is
reads; a BFF write, if any, declares `API_WRITE`); `DISPATCH` = DISPATCH pool size (each call
runs a transaction); `LOGIN`/`OIDC` = processor count; `NO_DB` unbounded. Each group's queue is
bounded at 8 × its workers; a request arriving at a full queue is answered 503 with
`Retry-After: 1` from the event loop without a worker; every queued request carries a deadline
(§4) on the loop's timer wheel. `fc_request_workers_busy`, `fc_request_queue_depth` and a new
`fc_request_queue_wait_seconds` histogram, all labelled by group; `fc_db_gate_*` labelled by pool.

**Reads release between statements.** `Admission`'s re-entrant handle (§10) pins one connection
for the request; for `API_READ` and `BFF` the scope is opened in *per-statement* mode: a checkout
is returned to the pool when the statement's connection closes, and the nested-acquire guard is
not armed (there is nothing held to deadlock against). `API_WRITE` and `DISPATCH` keep the
pinned mode. The gate acquisition per statement is untimed (§1). Measure at one core per §11.4's
instruction and put the switches-per-request number in the report.

**What is deleted.** `Budgets` (the per-group semaphore lanes) — superseded by the workers; the
Javalin-only §11.1 text stays as history. The gate itself stays: reads contend on it by design.

**Tests (each with a killed mutant).** Group declaration coverage as above; a full `DISPATCH`
queue answers 503 with `Retry-After` while `API_READ` on the same server still answers (the
isolation claim — mutant: one shared queue); `API_READ` workers = 2 × pool and `API_WRITE` = pool
(mutant: swap); a read of N statements holds a connection for none of the CPU between them
(assert the gate's held gauge returns to zero between statements — mutant: pinned mode); the
write path still pins (a nested checkout is refused, as today); every subsystem runs on the
`BACKGROUND` pool (assert by pool identity on a captured connection); a deadline fires on a
queued request. `bench/real` round comparing the two-CPU throughput and one-core switches per
request against the 2026-09-08 baseline, per the brief's §7.
