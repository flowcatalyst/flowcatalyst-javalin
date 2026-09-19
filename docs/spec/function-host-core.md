# Spec — the function API jar and the host's loading core (work package D, slice D1)

Design: `docs/function-runner-plan.md` §5 (JVM runtime), §4a (request/response are values), §10
items 14 and 16. Workplan §2 D items 3 and 7. This slice has **no open questions**: it is the API a
function is written against, and the class-loading machinery that keeps functions apart. It touches
no HTTP listener, no database and no platform route.

Later slices, each with its own spec: D2 the reconciler (desired state → fetch → verify → load),
D3 the invoke path (needs the trigger rulings and delivery signing — `function-triggers.md` T0),
D4 `FunctionContext` services (data sources, HTTP, events), D5 metrics and the process.

## 0. Departures from the workplan

| Workplan | Here | Why |
|---|---|---|
| `LoadedFunction` is a "verticle wrapper"; design §5 "wrapped in a verticle registered under the function's address" | a plain object; no verticle | invocations run on virtual threads and answer through `runOnContext` (design §4). A verticle per function adds an event-bus hop and a Vert.x type to the loading core for nothing: what needs a lifecycle is the class loader, and `LoadedFunction` owns that |
| the API jar "must build with no `--enable-preview`" | and with `release 21` | a function is compiled by its author's toolchain; 21 is the LTS floor that has records, sealed types and pattern `switch`. The host runs 25 and loads 21 class files happily; the reverse would make every function author match our JDK |

## 1. Module `function-api` — `flowcatalyst-function-api`

New reactor module (root `pom.xml`, before `server`). **Zero dependencies** (tests: JUnit + AssertJ).
Compiler: `release 21`, `failOnWarning`, `-Xlint:all`, **no** `--enable-preview` in compiler args or
surefire (override the parent's). A test asserts the built classes are major version 65 and that
none has the preview minor-version bit — the parent pom's flags are inherited by default, and an
API jar that silently carried the preview pin would refuse to load on every JDK but ours.

Package `io.flowcatalyst.function`. **Every type in a signature is a JDK type or one of these**
(design §5) — an ArchUnit-free reflection test walks every public signature and fails on anything
outside `java.*` / `javax.sql.*` / this package.

```java
public interface Function {
    default void init(FunctionContext ctx) throws Exception {}
    Result handle(Invocation in, FunctionContext ctx) throws Exception;
    default void stop() throws Exception {}
}
```

- `sealed interface Invocation permits EventInvocation, HttpInvocation, ScheduleInvocation` — each
  carries `FunctionAddress address()` and `String invocationId()`. A sealed type, not the design's
  `kind` enum with nullable halves: the compiler then tells a function author which cases exist.
  - `EventInvocation(address, invocationId, Event event)`; `Event(id, type, source, subject, Instant
    time, String dataContentType, byte[] data, correlationId, causationId, messageGroup, dedupId)`.
  - `HttpInvocation(address, invocationId, HttpRequest request)`; `HttpRequest(method, path, route,
    Map<String,String> pathParams, Map<String,List<String>> query, Map<String,List<String>> headers,
    byte[] body, remoteAddress, Principal principal /* null when the route is auth: none */)`;
    `Principal(id, type, clientId /* nullable */, Set<String> permissions)`.
  - `ScheduleInvocation(address, invocationId, String schedule, Instant scheduledFor)`.
- `sealed interface Result permits Ack, Retry, Fail, HttpResponse` with factories `Result.ack()`,
  `retry(Duration)`, `fail(String reason)`, `http(int status, Map<String,List<String>> headers,
  byte[] body)`. `Retry` rejects a negative or null duration; `Fail` a blank reason; `HttpResponse` a
  status outside 100–599.
- `FunctionAddress(application, service, name)` — a **second, deliberate copy** of the parser in
  `platform/function/FunctionAddress` (the API jar cannot depend on the server). Same rule, same
  table; a test in `function-host` runs one shared `@CsvSource` file through both and fails if they
  ever disagree.
- `FunctionContext`: `System.Logger logger()`, `Config config()` (`Optional<String> get(key)`,
  `String require(key)`), `Secrets secrets()` (same shape; values never in `toString`),
  `DataSource dataSource(String name)`, `HttpCaller http()`, `Events events()`, `Clock clock()`,
  `FunctionAddress address()`, `int version()`. `HttpCaller.send(HttpCall) → HttpReply` and
  `Events.emit(OutboundEvent)` are interfaces with value records here; their implementations are
  slice D4. In D1 the host supplies a `FunctionContext` whose unimplemented services throw
  `UnsupportedOperationException` naming the slice.
- **Values are values.** Every `byte[]` is cloned in the compact constructor and in the accessor;
  every map and list is deep-copied unmodifiable; header-name lookup is case-insensitive through
  `HttpRequest.header(name)` / `headers(name)` while the map keeps the original spelling. Records
  holding `byte[]` define `equals`/`hashCode`/`toString` over content (length only in `toString`).
  Pinned: mutating an array passed in, or one read out, never changes the record.

## 2. Module `function-host` — `flowcatalyst-function-host`, package `io.flowcatalyst.fnhost.load`

New reactor module after `server`; depends on `function-api` and `server` (for `Digest`,
`ArtifactStore`, `SignatureVerifier`, `Manifest` — used from D2 on); inherits the server's compiler
flags. D1 adds only the `load` package.

### 2.1 `ApiOnlyParentLoader`

`extends ClassLoader`, parent = the **platform** class loader (`ClassLoader.getPlatformClassLoader()`
— JDK modules, none of the application class path). `loadClass(name, resolve)`:

1. names starting `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `org.w3c.`, `org.xml.` ⇒ the platform
   loader (the JDK; this is wider than the design's "`java.*`, `javax.sql.*`" because a function using
   `java.net.http` or XML needs the JDK's own implementation classes, and none of these can come from
   the host's class path — the platform loader does not see it);
2. names in package `io.flowcatalyst.function` **exactly** (not subpackages, not
   `io.flowcatalyst.functionx`) ⇒ the host's loader — the one place a class crosses, so host and
   function agree on `Function`, `Invocation`, `Result`;
3. everything else ⇒ `ClassNotFoundException`.

`getResource`/`getResources`: rule 1 delegates to the platform loader; **everything else returns
nothing**, including `META-INF/services/*` — otherwise `ServiceLoader` inside a function discovers the
host's providers (Jackson modules, JDBC drivers, SLF4J bindings) by resource even though it could
not load their classes, and fails with `ServiceConfigurationError`.

### 2.2 `JvmFunctionLoader`

`LoadedFunction load(Path jar, String entrypoint, FunctionAddress address, int version)` →
`sealed LoadOutcome = Loaded(LoadedFunction) | Refused(Reason, String detail)`; reasons
`NATIVE_LIBRARY`, `SECURITY_PROVIDER`, `BUNDLES_API`, `ENTRYPOINT_NOT_FOUND`, `ENTRYPOINT_NOT_A_FUNCTION`,
`ENTRYPOINT_NOT_INSTANTIABLE`, `UNREADABLE_JAR`. A refusal is routine (it becomes a `FAILED` heartbeat
entry), so it is an outcome, not an exception.

Checks before any class is defined, by scanning the jar's entries:

- an entry ending `.so`, `.dll`, `.dylib`, `.jnilib` anywhere ⇒ `NATIVE_LIBRARY` naming it (design
  §5: one loader per JVM may load a given native library; the second function would get
  `UnsatisfiedLinkError`);
- `META-INF/services/java.security.Provider` ⇒ `SECURITY_PROVIDER`;
- any class in package `io.flowcatalyst.function` ⇒ `BUNDLES_API` — a shaded copy of the API would
  never be *loaded* (rule 2 wins, parent-first), but it signals a build that did not mark the API
  `provided`, and refusing is cheaper than explaining a `LinkageError` later.

Then: a `URLClassLoader` named `fn:<address>@<version>` over the jar with an `ApiOnlyParentLoader`
parent; load the entrypoint; it must implement `io.flowcatalyst.function.Function` **as the host
sees it** (`Function.class.isAssignableFrom`), have a public no-arg constructor, and instantiate
without throwing.

### 2.3 `LoadedFunction`

Holds the instance, its loader, address, version. `Result invoke(Invocation, FunctionContext)` sets
the calling thread's **context class loader** to the function's loader for the call and restores the
previous one in `finally` — also when the function throws, and also around `init` and `stop`.
`close()`: `stop()` (exceptions logged, not propagated), then `URLClassLoader.close()`. After
`close`, `invoke` throws `IllegalStateException`. It keeps no other reference to anything loaded by
the function's loader, and hands none out: `LoadedFunction` is what makes the loader collectable.

### 2.4 `FunctionRegistry`

`address → LoadedFunction` for the `live` version, plus at most one *previous* version draining.
`put(LoadedFunction)` swaps and returns the displaced one for the caller to close once its in-flight
invocations finish (in-flight counting is D3's; D1 exposes `retain()/release()` on `LoadedFunction`
and `close()` waits for zero with a bounded timeout, default 30 s, then closes anyway and logs).
Caps: `maxLoaded` (constructor), LRU over **lazy** entries only — a `warm` entry is never evicted;
exceeding `maxLoaded` with only warm entries is `IllegalStateException` (the platform's warm-capacity
check should have prevented it; say so in the message). Thread-safe; ownership comment on the lock.

## 3. Tests — fixtures are real jars

A test-support `FixtureJars` compiles small sources with `javax.tools.JavaCompiler` and packs jars at
test time (class path: the API jar only), so fixtures cannot rot and nothing binary is committed.
**Every isolation test is written to fail if `ApiOnlyParentLoader` is replaced by the host's
application loader** — that replacement is mutant L1 and must kill L2–L5 each.

| # | Behaviour | Mutant |
|---|---|---|
| L1 | (the mutant itself) parent = host application loader | — |
| L2 | a function bundling its own `com.example.lib.Version` (v2, with a method v1 lacks) while the **test class path holds v1 under the same name**: the function sees v2 — `Class.getClassLoader()` is the function loader and the v2-only method is callable | L1 |
| L3 | a function cannot load a host-only class: `io.vertx.core.Vertx`, `io.flowcatalyst.server.Platform`, `tools.jackson.databind.ObjectMapper` ⇒ `ClassNotFoundException` inside the function (it reports the outcome as a `Result.fail` reason the test reads) | L1; widen rule 2 to `io.flowcatalyst.` |
| L4 | two functions bundling one library with a static counter each see their own count | share one loader between them |
| L5 | `ServiceLoader.load(X.class)` **without** a loader argument inside a function finds the function's provider and not a host provider registered for the same interface on the test class path | drop the context-class-loader switch; let `getResources` delegate |
| L6 | the context class loader is restored after a normal return, after a throw, and after `init`/`stop` | restore only on the normal path |
| L7 | leak: load → invoke → `close()` → drop references → `System.gc()` loop (bounded, 50 × 100 ms) ⇒ a `WeakReference` to the loader clears. And the control: a fixture that parks its instance in a **JDK-owned** static (a `ThreadLocal` on a platform thread, or `java.util.logging` handler) does **not** clear — proving the test can fail | keep a strong reference in the registry after close |
| L8 | each refusal reason, from a fixture jar built for it; a refused jar defines **no** class (the scan precedes loading — assert via a static initialiser that would write a marker file) | scan after `loadClass` |
| L9 | API values: array and collection immutability (§1), `Retry`/`Fail`/`HttpResponse` validation, case-insensitive header lookup | drop each clone |
| L10 | the API jar's class files are major 65 without the preview bit; every public signature stays inside `java.*`, `javax.sql.*`, the API package | add `--enable-preview` back; add a Jackson type to a signature |
| L11 | registry: swap returns the displaced version; LRU evicts the least-recently-*invoked* lazy entry, never a warm one; `close()` waits for `release()` and gives up after its timeout | evict by insertion order; evict warm |
| L12 | the two `FunctionAddress` parsers agree on one shared table | loosen one |

One mutant per condition, not per step; assert absence as well as presence (`function-artifacts.md`
C5 records why).

## 4. Not in this slice

Thread-spawn detection, JVM-wide-default guards (time zone, locale, system properties,
`URL.setURLStreamHandlerFactory` — design §5's list) and the blocked-event-loop check need the
invoke path and land with D3. The reconciler, the listener, `FunctionContext` services, metrics, the
Dockerfile: D2–D5.
