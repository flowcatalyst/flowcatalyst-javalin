# Conventions

The contract for adding code to this repo. It is the Java reading of
`flowcatalyst-go/CONVENTIONS.md`; the intent is identical — one obvious way
to do each thing, so 37 aggregates stay uniform. Everything here is enforced
by review and by the tests named below.

The committed OpenAPI lockfile (`server/src/main/resources/openapi/openapi.lock.json`)
is the canonical wire spec. When in doubt about a field name, a status code
or an envelope shape, the lockfile wins — the Java code is written *from* it.

## 1. Stack — already chosen

| Concern | Choice | Notes |
|---|---|---|
| Language | Java 25, no preview features | records, sealed interfaces, exhaustive `switch`, pattern matching, `var`, `_`, text blocks, `///` Markdown doc comments |
| HTTP | Javalin 7 (Jetty 12, virtual threads) | routes registered on `JavalinDefaultRoutingApi` inside `Javalin.create(cfg -> …)`; one `XxxApi.register(routes, state)` per aggregate |
| JSON | Jackson 2.x via `platform.shared.json.Json.MAPPER` only | `NON_ABSENT`, unknown properties ignored, microsecond RFC 3339 timestamps. Never build a second `ObjectMapper`. |
| Database | jOOQ 3.21 (generated code committed under `io.flowcatalyst.db.generated`) + HikariCP + pgjdbc | typed DSL in repositories; plain-SQL text blocks only for `SKIP LOCKED` claims and partition DDL |
| Migrations | Flyway, `V1__baseline.sql` = the Go schema | additive-only while rollback to the Go binary must stay possible; never touch `goose_db_version` |
| Use cases | `io.flowcatalyst.sdk.usecase` (module `usecase`) | `Operation` → `Plan` → `UnitOfWork`; see `docs/usecase-envelope.md` |
| Errors | sealed `UseCaseError` in unchecked `UseCaseException`; HTTP envelope via `platform.shared.httperror.HttpError` | `{"error": CODE, "message": …, "details"?: …}`; 400/401/403/404/409/500 only |
| Auth | `platform.shared.auth` — `Auth.current()` (ScopedValue), `Checks.*` | coarse `Can*` in handlers, resource-level `checkScopeAccess` in use cases |
| IDs | `Tsid` (Go layout) + `EntityType` prefixes | `EntityType.EVENT_TYPE.generate()` → `evt_…` |
| Logging | SLF4J + Logback, JSON in prod | not a contract; MDC keys `correlation_id`, `causation_id`, `principal_id`, `execution_id` |
| Tests | JUnit 5 + AssertJ; embedded Postgres via `TestPg`; `TestHttp` | real DB, never mocked |
| DI | none — constructors and `io.flowcatalyst.server.Platform` | no container, no annotations, no reflection |

Do not introduce a competing library for any row. If you think one is
needed, raise it first.

## 2. Layout of an aggregate

```
io/flowcatalyst/platform/<aggregate>/
├── <Aggregate>.java            ← record(s) + invariants (no I/O); implements HasId
├── <Aggregate>Repository.java  ← jOOQ reads + Persist<A> (persist/delete) on a DbTx
├── operations/
│   ├── <Aggregate>Events.java  ← every domain event record + type/source/subject constants
│   ├── Create<Aggregate>.java  ← one operation per file: command record + static of(repo,…)
│   ├── Update<Aggregate>.java
│   └── …
└── api/
    ├── <Aggregate>Api.java     ← record State(repo, uow, …) + static register(routes, state) + handlers
    └── (DTO records)           ← request/response shapes from the lockfile
```

Hard rules:
- **One operation per file**, built with the staged builder
  `Operation.<C,E>named("…").validate(..).authorize(..).execute(..)`.
- **One events file per aggregate.**
- **Command records are named exactly like the Go commands** (`CreateCommand`,
  `UpdateCommand`, `SyncRolesCommand`…). The simple class name is the audit
  `operation` column.
- **No business logic in `api/`.** A handler does: coarse permission check →
  build command → `op.run(uow, cmd, Auth.executionContext(ctx))` → write
  response. Reads do not go through use cases.
- **No SQL outside the repository.** Operations call repository methods; the
  repository uses the generated jOOQ tables.
- **Wire shapes come from the lockfile**, not from the Go structs. Status
  codes per operation come from the lockfile too.

The `eventtype` aggregate is the canonical example — copy its shape. The
rules it established (from its audit):

- **Invariants live on the aggregate.** `<Aggregate>.java` exposes
  intent-named transitions (`archive()`, `finaliseSchema(v)`) that return a
  copy and throw `UseCaseException` (conflict/not-found) when violated.
  Operations never `switch` on entity state; an execute phase is
  *load → transition → event → Plan*. A transition with a side result
  returns a small nested record (`SchemaFinalised(eventType, deprecatedVersion)`).
- **A multi-field partial update is one `update(Changes)` transition.**
  When an update command's fields are all optional ("absent = untouched"),
  the aggregate exposes a nested `record Changes(...)` (null = untouched,
  collections `List.copyOf`'d, doc stating what an empty list means) and one
  `update(Changes)` transition that applies the non-null fields and
  re-stamps `updatedAt`; the operation builds `new <Aggregate>.Changes(...)`
  from the command and calls it. `withX` copies are for construction-time
  defaults, not admin updates — a chain of `withX` cannot express
  "absent = untouched" without `if`s in the operation. `Role.update` and
  `Process.update` are the models.
- **A command field that *selects* a transition is routed, not modelled.**
  When a command carries a value that chooses between existing intent-named
  transitions (an update's `status`), the execute phase switches
  exhaustively on the parsed enum and calls the named transition
  (`case PAUSED -> c.pause()`). The aggregate does not expose a
  `transitionTo(status)` / `withStatus(status)` dispatcher — its only public
  transitions are the ones the spec names.
- **One parser per formatted value.** A value with a format rule (the
  `a:b:c:d` code, semver, cron…) is a record with `static parse(String)`
  throwing the validation error once, with one message set; the entity
  factory and the command's validate phase both call it.
- **Platform-level aggregates use `Access.byId`.** An aggregate with no
  client dimension (application, client, role) names its load-or-404 helper
  `Access.byId(repo, id)` — not `loadScoped` — and its class doc states that
  there is no per-resource scope to check, which is why every by-id write
  declares `Authorize.publicAccess()`. Operations whose *resource* is
  another aggregate's instance (a client id on the command) call
  `Checks.checkScopeAccess(Auth.current(), cmd.clientId())` in `authorize`.
- **`operations/Access.java`: `loadScoped(repo, id)`** = load-or-404 +
  `Checks.checkScopeAccess`. Every by-id write operation uses it — that is
  *why* those operations declare `Authorize.publicAccess()` (say so in a
  one-line comment next to it). Not-found is
  `UseCaseException.resourceNotFound(resource, id)`; blank checks are
  `UseCaseException.requireNonBlank(value, code, message)`.
- **Events file:** `record X(...) { static X of(ExecutionContext ec, <Aggregate> a, …) }`
  plus a private `metadataFor(ec, type, a)`; subjects/message groups via
  `EventConventions`; the `data()` payload record is the wire shape, field
  names verbatim. Operations never assemble metadata.
- **Repository shape:** one `DSLContext` field; `findOne(Condition)` →
  `fetchOptional().map(toEntity)`; `findMany(Condition)`; child rows
  hydrated in one `IN` query and passed *into* `toEntity(row, children)`
  (never "bare then rehydrate"); list filters as a `record ListFilter(...)`
  (null = no filter); an upsert lists each column once
  (`LinkedHashMap<Field<?>,Object>` for insert + on-conflict, `created_*`
  insert-only); writes on `DSL.using(tx.connection())` only.
- **Enums:** the constant name is the stored/wire string; `parse(String)`
  is the lenient reader; never `valueOf` on external input; switch on them
  exhaustively (no `default`) inside the aggregate.
- **Two readers when the spec splits stored and wire.** When an enum's
  stored value reads leniently (unknown → default, or unknown entries dropped
  for a set-valued junction) but the wire rejects unknown values with a
  pinned code, the enum carries both readers and nothing else does:
  `parse(String)` / `readStored(List<String>)` — the lenient stored reader
  the repository alone calls — and `parseStrict(String)` /
  `parseAllStrict(List<String>)` — the wire reader that throws the
  validation error once (code and message live on the enum), called by the
  validate phase / DTO alone. `ScopeType` and `MfaMethod` are the models; a
  single lenient `parse` remains right only where the spec says an unknown
  wire value is not an error (`ApplicationType`, `DispatchMode`).
- **API:** DTO ↔ entity mapping lives in `api/` as `Request.toCommand()` /
  `Response.from(entity)`; handlers are four lines; read-side rules
  (`visible(ac, et)`, `listFilter(ctx)`) are private helpers in the Api
  class, not in operations.
- **Docs say why and reference the spec**, not the Go:
  `/// (spec §5, open question 3)` rather than `/// (Go operations.Foo)`.
- **Tests:** the entity test covers every transition/error code without a
  DB; the operations test exercises each operation once through the
  envelope and asserts `msg_events` + `aud_logs` rows with jOOQ;
  `@ParameterizedTest` for validation tables; helpers
  `created/reload/runAsAnchor`; API tests use `TestHttp` (`get/post/put/delete/send`).

## 3. Authorization placement (locked)

- Handler: coarse permission (`Checks.require(Auth.current(), Permission.EVENT_TYPE_UPDATE)`,
  `Checks.requireAny(…)` for an any-write / sync grouping, or `requireAnchor`).
  Reads are gated in the handler only.
- Use case: resource-level — may *this* principal act on *this* resource?
  `create` → in `authorize` against `cmd.clientId()`; `update/delete/status`
  → in `execute`, right after the load + not-found check.
  `Operation.Authorize.publicAccess()` when the resource has no per-instance
  dimension or the operation is reached from several differently-gated entry
  points (each entry point keeps its own gate).
- No coarse `Checks.require*` / `requireAnchor` inside `operations/` — ever.

## 4. Wire contract

- Every registered `/api/**` route must be in the lockfile
  (`LockfileCoverageTest` fails on drift and reports coverage).
- Timestamps on the wire: `Json.MAPPER` formats `Instant`/`OffsetDateTime`
  as `2026-05-24T08:30:00.123456Z`. Webhook signature timestamps are
  milliseconds — a different formatter, on purpose.
- Error bodies only through `HttpError`; 401 bearer failures through
  `HttpError.writeInvalidToken`.
- Pagination: use the `apicommon` records; the endpoint's lockfile schema
  decides which one (a single standard envelope is a planned, separate change).

## 5. Concurrency

- Virtual threads for per-request and per-message work; `ScopedValue` for
  request context; `AtomicReference`/`Semaphore`/`ReentrantLock` for shared
  state, each with a one-line ownership comment.
- Every background loop has an explicit stop signal and is stopped by
  `Server.Running.stop()`; no fire-and-forget threads.
- Leader-gated subsystems check `isLeader()` exactly where the Go code does.

## 6. Testing

- Test next to the code: `<Class>Test`. Operations tests run against
  `TestPg` with a real `UnitOfWork(TestPg.dataSource(), new PlatformSink(Json.MAPPER))`.
- No truncation between tests — scope data with fresh TSIDs; never assert
  table-wide counts.
- API tests use `TestHttp` and the `X-FC-Test-*` headers with
  `Authenticator.Config.of(true)`.
- Parity fixtures (HMAC vectors, schema fingerprint, lockfile) are committed
  and never regenerated silently.

## 7. What NOT to do

- Don't add Lombok, Spring, Guice, Bean Validation, MapStruct, or a second JSON mapper.
- Don't generate OpenAPI from code; don't edit the lockfile by hand.
- Don't construct domain events in handlers; don't commit outside `Operation.run` /
  `UnitOfWork.inTransaction` (bootstrap/seed code is the only exception and uses `DbTx.wrapForBootstrap`).
- Don't rename DTO fields or command records without coordinating across the SDKs.
- Don't use `Date`, `Calendar`, `System.out`, or `Thread.sleep` without a reason.

## 8. How a Go corner becomes Java — spec, implement, audit

The port is not a translation. Every unit (an aggregate, a subsystem, a
shared package) goes through three distinct steps, each a separate read:

1. **Semantic extraction — a spec, not code.** Read the Go corner and write
   `docs/spec/<unit>.md`: every state, transition, timing constant, ordering
   guarantee, wire shape and edge case the code embodies, in prose and
   tables. Every constant and odd branch is flagged *load-bearing or
   historical accident?* for the owner to rule on. The spec is reviewed
   before implementation starts, and it seeds the conformance suite.
   (For CRUD aggregates the spec is mostly the lockfile + the validation /
   authorization rules + error codes — still written down, just short.)
2. **Implement the spec as if Go never existed**, against the idiom
   checklist below. Tests assert the spec.
3. **Audit the Java as a Java program against the spec** — never as a diff
   against the Go. Line-level similarity to Go is a smell, not a goal.

Idiom checklist (steps 2 and 3):
- Message / outcome taxonomies are sealed interfaces + records, dispatched
  with exhaustive `switch` — no type switches, no channel-per-type wiring.
- Topology restructured, not translated: multiplex at producers into one
  queue per stage; never N queues with poll-loops simulating `select`.
- Cancellation is interruption. No ported `ctx` threaded through signatures.
  `InterruptedException` is restored-and-exited at every blocking point,
  never swallowed.
- Scatter-gather uses `StructuredTaskScope` with a completion policy — not
  `CompletableFuture` chains, not hand-rolled latch choreography. (Preview
  in Java 25: `--enable-preview` is enabled for the `server` module; keep
  its use localised.)
- Panic-recovery scaffolding is deleted — per-virtual-thread failure
  isolation makes it unnecessary. The retry policies it guarded are kept
  as explicit, named policy objects (records).
- Errors split by kind: expected outcomes (rejected, deferred, backpressure)
  are sealed result types returned and switched on; genuinely exceptional
  conditions are exceptions. Not Either-everywhere, not
  exceptions-for-control-flow.
- Java-shaped structure: packages by component; records for every data
  carrier; the hand-wired `Server`/`Platform` composition root; JFR events
  (`jdk.jfr.Event`) at the component's own semantic points (message
  accepted / routed / dead-lettered, lease acquired, partition created) so
  observability is native.
- Plus the hygiene list: Go-isms in names/shapes (`IDStr`, `FromContext`,
  empty-string-as-absent at API boundaries, `String[]`), defensive copies on
  record components holding collections/arrays, tightest visibility, `final`
  by default, no `Optional` record components — model absence with a sealed
  type (`Spa.None`, `KeyRotation.Single`) or a list; `Optional` is a return
  type only — `java.time`/`Duration`, `ScopedValue` not `ThreadLocal`,
  parameterised SLF4J logging, `///` docs that say *why*, AssertJ tests that
  read as sentences, `-Xlint:all` clean.

Step 3 is a reviewer's job, not the author's second look — use a fresh
reader (or agent) with the spec and the checklist.

Rules promoted from audits (recurring findings become rules here):
- **Outcomes, not exceptions, at verification boundaries.** A check whose
  negative result is routine (token verification, password verification,
  idempotency/dedup hits, optimistic-lock misses) returns a sealed outcome
  (`Verified | Rejected`, `Ok | Mismatch | InvalidHash`) the caller switches
  on. Exceptions are for infrastructure failure. `JwtVerifier.Verification`
  and `PasswordHash.Verification` are the models.
- **No empty-string sentinels inside the JVM.** The wire/DB `""` ↔ `NULL`
  mapping lives in sinks and DTOs; an absent optional string in a record is
  `null` (documented) or an `Optional` return — never `""`.
- **Import it.** No fully-qualified names inline; `[Type]` / `[#method]`
  Markdown links in `///` docs, never `{@link}`.
- **Shared row/payload builders live in `SinkSupport`.** Anything two sinks
  must write identically (`context_data`, `deduplication_id`, the audit
  `operation` name) is one helper, never copied.
- **Deterministic maps in wire-byte tests.** A test asserting serialised
  bytes builds its fixture with insertion-ordered maps (`LinkedHashMap`,
  `Map.entry` sequences) — `Map.of` iteration order is unspecified.
- **No stringly-typed dispatch in the composition root.** Toggle lists,
  route groups and the like are records, never `String[]` + `switch(String)`.
- **Application-scoped sync authorization is one helper.** A sync operation
  scoped to an application calls
  `Checks.checkApplicationAccess(Auth.current(), applicationId, applicationCode)`
  (null → `UNAUTHENTICATED`, no access → `FORBIDDEN`
  "Not authorised for application '<code>'") in its `authorize` phase —
  never a hand-written `if (ac == null) … if (!ac.canAccessApplication(…))`
  block in `operations/`.
- **Read-side not-found lookups are one private helper per key kind in
  the Api class** (`resolveRole(s, idOrName)`, `roleNamed(s, name)`), never an
  inline `findBy…().orElseThrow(() -> HttpError.notFound(…))` repeated across
  handlers; a route that is name-only by spec uses the name-only helper.
- **A JSON column is a foreign shape — pin the read, not just the write.**
  When an aggregate stores a collection as JSONB (`tnt_clients.notes`), the
  repository test inserts a raw row in the shapes another writer may have
  produced (other RFC 3339 offsets / fractions, omitted optional keys,
  `NULL`) and asserts it reads back; asserting only what we ourselves wrote
  does not protect the schema-compat boundary.
- **If a parser record exists, the factory takes the parsed type**
  (`Client.create(String name, ClientIdentifier id)`), never re-parsing a raw
  string inside the aggregate — the type carries the proof.
- **A matcher is a pinned table.** A predicate other subsystems dispatch on
  (a pattern `matches(code)`, a scope `canAccess…`) has one implementation on
  the aggregate, its rules stated exhaustively in the spec (what is literal,
  what is a wildcard, what empty/`null` input does), and a
  `@ParameterizedTest` `@CsvSource` table grouped by rule (match /
  count-differs / mismatch / edge) — `EventTypeBinding.matches` +
  `SubscriptionTest.bindingMatchesWholeSegmentsOnly` is the model. A `null`
  input returns `false`; it is never coalesced to `""`.
- **A parser another subsystem will match against is pinned like a
  matcher.** When a parser record's accepted forms are what a later consumer
  compares live input to (`Origin.parse` → the CORS filter, an event-type
  pattern → routing), the spec carries a per-rule Accepted / Rejected table
  and the entity test carries two grouped `@CsvSource` tables — accept and
  reject — each with a rule-label column, so a regex change must edit a named
  row, not a comment.
- **`HttpError` constructors are handler-layer only.** Code under
  `operations/` raises `UseCaseException.authorization / validation /
  resourceNotFound / conflict` directly; `HttpError.forbidden /
  unauthenticated / notFound / badRequest` are the Api's spellings of the same
  thing and are never imported into an operations package.
- **A DB-backed, per-resource access rule is one public helper in
  `operations/Access`** (`requireRead(repo, ac, key)` / `requireWrite(…)`),
  called by the read handlers and by the write operation's `authorize` phase;
  the handler never re-derives "anchor or grant".
- **A malformed-input parser rejects the empty component.** When a parser
  record splits a token into parts (`<time>|<id>`, `a:b:c:d`), an empty part
  is a malformation reported with the parser's one error, never accepted as
  `""` — the pinned malformed table carries an "empty part" row.
- **Carriers of key material, secrets or ciphertext mask `toString`** —
  including records whose components are `SecretKey`/`PrivateKey` (JDK key
  `toString`/`hashCode` leak key-derived bits), not just `String` secrets.
- **Parse once, carry the facts.** When a helper needs two facts about one
  input (bytes + "was prefixed"), return a small private record from a
  single parse rather than re-deriving the second fact from the raw string.
- **One spelling per encoding contract.** A package that wraps an encoding
  (`Base64Strict`) uses it everywhere, including `stored()`/canonical
  re-encoders.
- **A keyset cursor is one record; its policy is the route's.** Every
  cursor-paginated list uses `apicommon.KeysetCursor` `(at, id)` for its
  position and the `<RFC 3339 UTC>|<id>` base64url token; `parse` returns
  `Optional` and never throws. What a malformed token *means* (400 `CURSOR`,
  or "first page") is decided in the Api's `after(ctx)` helper with
  `orElseThrow` / `orElse(null)`, so two lists can differ in policy without a
  second parser.
- **Typed query parameters read through `apicommon.QueryParams`.** An
  integer query parameter is read with `QueryParams.intParam(ctx, name)`
  (throwing form) or `intParam(ctx, name, errors)` + `QueryParams.validation(errors)`
  (accumulating form, one `details.errors` entry per bad parameter in the
  route's documented order); a handler never hand-builds the
  `{message, location: "query.<name>", value}` detail or the 400 `VALIDATION`
  envelope. `PageQuery.from` and `EventApi.Page.from` are the models.
- **A read scope is a required component, never a defaulted filter.** When
  a repository read takes the caller's visibility (`Visibility.Everything |
  Tenants`) alongside `ListFilter` columns, the record `requireNonNull`s it —
  `null = no filter` applies to columns, not to whose view the query is; a
  `none()` factory names `Everything` explicitly.
- **Subsystem knobs reach the composition root through `Env`, never
  `EnvReader.system()`.** A `fromEnv(EnvReader)`-style factory is a
  convenience for tests and raw-env callers; `Platform`/`Server` read the
  value from the `Env` record (`env.appKey()`) and call a value-taking factory
  (`Encryption.fromKeys`). Reading the process environment inside the
  composition root silently ignores environments loaded from a map or `.env`
  (fcdev).
- **A classpath-enumerated corpus is tested from a jar, not just
  `target/classes`.** When code lists resources under a classpath directory
  (`getResource(dir)` → `file:`/`jar:`), its test packs a fixture jar (with
  directory entries) and loads through a `URLClassLoader`, once fresh and once
  with the jar already mounted as a zip filesystem — surefire only ever
  exercises the exploded-directory branch.
- **Defaults are domain, not transport.** A default for an absent optional
  value (`concurrency ⇒ 10`) is applied by the operation/aggregate from a
  named constant on the aggregate; the DTO and handler map the wire shape
  verbatim.

## 9. Adding a new aggregate — checklist

- [ ] Lockfile operations extracted for `/api/<aggregate>…`
- [ ] `<Aggregate>.java` + invariants + test
- [ ] `<Aggregate>Repository.java` (jOOQ) + `Persist`
- [ ] `operations/<Aggregate>Events.java`, one operation per file
- [ ] `api/<Aggregate>Api.java` + DTO records
- [ ] Registered in `io.flowcatalyst.server.Platform`
- [ ] Operations test (TestPg) + API test (TestHttp)
- [ ] `LockfileCoverageTest` shows the new routes, no drift
- [ ] Pass 2 idiom review done (§8)
