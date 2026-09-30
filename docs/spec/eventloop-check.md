# Event-loop check (compile time)

A javac plugin, module `eventloop-check`, fails the build when code that runs on a Vert.x event
loop makes a blocking call. Vert.x's blocked-thread warning finds the same mistakes only at
runtime, and only on the paths a run happens to take. This check finds them at compile time.

Every listener here runs **one** event loop per `Vertx` instance. A blocking call there stalls
every connection that listener serves, not just one request.

**Where it runs:** the main compile of `server` and `function-host`, the two modules that run
Vert.x. Each has the module as a `provided` dependency and passes
`-Xplugin:EventLoopCheck -XDcompilePolicy=simple` on `default-compile`. Tests are not checked.
To add a module, copy both of those.

## 1. What runs on the loop

**Roots:**

- a lambda or method reference whose target type is a Vert.x type (`Handler`, …);
- a lambda or method reference passed to a Vert.x method, which covers the `java.util.function`
  arguments of `Future.map`, `compose` and friends. `executeBlocking` is excepted;
- a method that overrides a Vert.x method (a class implementing `Handler`, for example);
- a method, or a lambda passed for a parameter, marked `@OnEventLoop`.

**Reach:** from each root, every call whose target has source in the same compilation is followed,
transitively and across files. A lambda nested in loop code is loop code too, because `forEach`,
`Optional.map` and the like run it in place.

A lambda handed to another thread is not loop code. The check recognises:

- JDK executors (`Executor` and every subtype);
- `Thread.Builder`, `ThreadFactory`, `Thread` constructors and `Thread.startVirtualThread`;
- `CompletableFuture`'s `…Async` methods;
- `java.util.Timer.schedule…`;
- Vert.x `executeBlocking`;
- any parameter marked `@OffEventLoop`.

**Markers,** in package `io.flowcatalyst.eventloop`, are for what the check cannot see from
structure alone:

| Marker | Where it is used |
|---|---|
| `@OffEventLoop` | `RequestWorkers.submit(…, task)`: the task runs on a worker. `FnHttpServer.readBodyThenRun(…, continuation)`: it runs on a fresh virtual thread. |
| `@OnEventLoop` | `VertxListener.awaitOnLoop(…, task)`: a virtual thread hands the task to `runOnContext` through a variable. |

A marker must describe where the code really runs. Using one to silence a finding is a defect.

## 2. What counts as blocking

1. **Any method that declares `InterruptedException`.** That includes `Thread.sleep`/`join`,
   `Future.get`, latches, `Semaphore.acquire`, `BlockingQueue.take`/`put`, `Condition.await`,
   `Object.wait`, `HttpClient.send`, `Process.waitFor`, `ExecutorService.awaitTermination`, and
   our own methods.
2. **A fixed list of blockers that don't declare it:**
   - JDBC: any method on `Connection`, `Statement`, `ResultSet`, `DatabaseMetaData`,
     `DataSource` or `DriverManager`, judged by the receiver's static type;
   - jOOQ execution (`fetch…`, `execute`, `transaction…`, `stream`, …);
   - stream and reader reads, except on `ByteArrayInputStream`, `StringReader` and
     `CharArrayReader`;
   - `Files`, file and socket channels, `RandomAccessFile`, and constructing a file stream or a
     connecting `Socket`;
   - `Socket.connect`, `ServerSocket.accept`, and DNS (`InetAddress.getByName` and friends;
     `InetAddress.ofLiteral` never resolves);
   - `URL`/`URLConnection` I/O;
   - `CompletableFuture.join`, `ForkJoinTask.join`, `LockSupport.park…`,
     `Semaphore.acquireUninterruptibly`, `Condition.awaitUninterruptibly`,
     `ExecutorService.close`;
   - Vert.x `Future.await`.

## 3. What it does not check

- **Locks and monitors.** `synchronized`, `Lock.lock` and friends are allowed. The loop then
  waits only as long as the holder keeps the lock, and a call-site check can't see what the holder
  does. **Review rule:** never block while holding a lock the event loop also takes.
  `VertxBodyInputStream` and `Admission` follow it: under their monitors the holder only touches
  memory, and `wait()` releases the monitor.
- **Calls through an interface or abstract method** are not followed, because the implementation
  is unknown. **Code outside the compilation** is not followed either, for example `server`'s
  classes when compiling `function-host`. Both are still checked against §2 at the call site.
- **Callbacks from other libraries** (Reactor, the MCP SDK) are roots only when marked
  `@OnEventLoop`.
- **Tests.**

## 4. What it found on first run (2026-09-28)

- **`VertxListener.fireDeadline`** sent `PgConnection.cancelQuery()` from the event loop. The
  cancel opens a new connection to Postgres, so a slow or unreachable database stalled the whole
  API listener at every deadline. The cancel now runs on its own virtual thread. The interrupt
  fallback is still armed on the loop at the same moment, so it stays bounded.
  `VertxListenerTest.aStuckQueryAnswers503DeadlineAndItsConnectionIsBackInThePoolReusable` still
  sees SQLSTATE 57014, which proves the cancel still lands.
- **`FnObservability` `/ready`** read cgroup files on its loop for every request. The memory
  snapshot is now captured once when the listener starts; every value in it is fixed for the life
  of the process.
- **`TrustedProxies.isTrusted(String)`** used `InetAddress.getByName`. It never resolved, because a
  regex pre-check allowed only IP literals, but that depended on the regex being right. It now
  uses `InetAddress.ofLiteral`, which cannot resolve.

The other first-run findings were code that really runs off the loop, and the markers in §1 now
say so.

## 5. Handling a finding

Move the blocking work onto a virtual thread (the request's worker, or `Thread.ofVirtual()`) and
hop back to the loop with `Context.runOnContext` to touch Vert.x state. Or use the non-blocking
Vert.x API. Reach for a marker only when the code demonstrably runs where the marker says.
