# Wasm functions in the host: the loader seam, the Wasm loader, the host functions (W1 + W2)

Plan: `docs/plan/wasm-and-js-functions.md` (rulings D1–D5 and the W0 findings — read them; every
number and API fact below comes from the spike). Runtime **Endive 1.1.0** (`run.endive:runtime`,
`compiler`, `wasi`); ABI **Extism**; JavaScript arrives later (W3) as an ordinary Wasm module.
Rules: `CONVENTIONS.md` §8 (Result/sealed outcomes; no exceptions for expected outcomes);
`Claude.md` testing policy (break each load-bearing behaviour on purpose and watch its test fail).

## 0. What must not change

Every existing function-host test passes **unedited**. A JVM function loads, isolates, invokes,
times out and unloads exactly as today. The platform side is untouched in this unit (it already
accepts `runtime: wasm`); the SPA keeps `wasm` disabled until W5.

## 1. The ported Extism host SDK — new reactor module `extism-endive`

- Source: `github.com/extism/chicory-sdk` at `8bceda1` (2025-10-31), `core/` only, BSD-3.
  Keep its package `org.extism.sdk.chicory` (so a future upstream Endive release can replace the
  module wholesale); rewrite `com.dylibso.chicory.*` → `run.endive.*`; drop
  `JacksonJsonCodec`/`JakartaJsonCodec` (Jackson 2 / Jakarta) and the `HttpUrlConnection`/`Jdk`
  client adapters. Keep `extism-runtime.wasm` (the Extism kernel) as a resource.
- `LICENSE` (the upstream BSD-3 text) and a `NOTICE`/README naming the upstream commit and listing
  every change made. Compiled at the reactor's release; its own tests are not ported (upstream's
  need fixtures we do not have) — ours in function-host cover what we use.
- Dependencies: `run.endive:runtime/compiler/wasi:1.1.0`, `org.extism.sdk:http-api:0.3.0` (the
  `HttpConfig`/`HttpJsonCodec`/`HttpClientAdapter` interfaces; no Chicory dependency). Pin versions
  in the parent pom's dependencyManagement.
- Endive logs through `run.endive.log.Logger`: function-host supplies an adapter to SLF4J — no
  runtime output goes to stdout/stderr.

## 2. The loader seam

- `LoadedFunction` holds a `Function` plus an `AutoCloseable` runtime resource instead of a
  `URLClassLoader`; the JVM path passes its class loader (still set as the thread context loader
  during a JVM call — Wasm calls leave the context loader alone). Its in-flight counting, drain,
  `init`/`stop`/`close` order are unchanged.
- A sealed `FunctionLoader` (`JvmFunctionLoader` | `WasmFunctionLoader`) with one method,
  `LoadOutcome load(Path artifact, Manifest manifest, FunctionAddress address, int version)` —
  or keep `JvmFunctionLoader.load`'s signature and add a small dispatcher; either way the
  `Reconciler` chooses by `manifest.runtime()`, and the `RUNTIME_UNSUPPORTED` branch goes.
  `MetaspaceGuard.check()` runs before **both** (Wasm compile mode generates classes: ~4–6 MB per
  version, W0).
- New `Reason`s for Wasm refusals (values, never exceptions): `WASM_INVALID` (does not parse),
  `WASM_ENTRYPOINT_NOT_EXPORTED`, `WASM_IMPORT_NOT_ALLOWED` (any import outside
  `extism:host/env`, `extism:host/user` and `wasi_snapshot_preview1`; the detail names it),
  `WASM_MEMORY_OVER_CAP` (the module's declared minimum exceeds `wasmMemoryMb`). A metaspace OOM
  while compiling is `OUT_OF_METASPACE`, as for the JVM.

## 3. The Wasm loader and the invocation ABI

- Parse once, **compile once per version** (Endive compiler mode, the SDK's cached machine
  factory), and serve calls from a **pool of plugin instances** — an instance is not thread-safe
  (W0) — created lazily up to `limits.maxConcurrency` (the listener's permits already bound
  concurrency, so a borrow never waits in practice; if it would, that is a bug, not a queue).
  Every instance: `MemoryLimits(initial = module's declared minimum, max = wasmMemoryMb in pages)`
  (capping the initial size below the minimum fails instantiation — W0); WASI with **no**
  preopened directories, no environment, no args; the guest's stdout/stderr go to the function's
  logger at INFO/WARN.
- The adapter is a `Function` (`WasmFunction`), so the registry, listener and invocation path are
  unchanged. `init(ctx)` binds the version's `FunctionContext` for the host functions (§4) and
  instantiates nothing yet; `stop()` closes the pool.
- **Request → guest input:** UTF-8 JSON:
  `{"address","version","invocationId","method","path","originalHost","originalPath",
  "pathParams":{},"query":{"k":["v"]},"headers":{"k":["v"]},"bodyBase64","remoteAddress",
  "caller":{...}}` — `caller` is `{"kind":"platform"}`, `{"kind":"anonymous"}` or
  `{"kind":"principal", "id","type","tier","clients","roles","applications","allApplications",
  "permissions"}` (the `Caller.Principal` record's components).
- **Guest output → Result:** UTF-8 JSON `{"status":int,"headers":{"k":["v"]}, and either
  "bodyBase64" or "body"}` (`body` is text, sent as UTF-8). An export returning Extism's error
  code, a trap, or output that is not this shape is `Result.fail(...)` with a 500 and one WARN —
  never a host exception.
- A guest that fails leaves its instance **discarded** (not returned to the pool); the next call
  gets a fresh one.
- **Deadline:** the listener already interrupts the invocation's thread at the deadline; Endive
  stops the guest at once (`WasmInterruptedException`, W0). The interrupted instance is discarded.
- **Memory cap:** an allocation past the cap is a clean guest failure (W0) → 500; the instance
  is discarded.

## 4. Host functions (W2)

What a Wasm function may reach — everything else is denied by having no import for it:

| Guest call | Namespace / name | Backed by |
|---|---|---|
| log | Extism `log_*` (built in) | the version's `HostLogger` |
| config | Extism `config_get` (built in) | the version's `MapConfig` via an Extism `ConfigProvider` — only manifest-declared keys |
| secret | `extism:host/user` `fc_secret_get(key) → value \| empty` | the version's `MapSecrets`; undeclared key → empty; never logged |
| HTTP | Extism `http_request` / `http_status_code` / `http_headers` (built in — the PDKs' `Http.request`) | an `HttpClientAdapter` over the version's `AllowlistHttpCaller` (allowlist, https, no redirects, deadline cap — **enforced**, unlike JVM functions) and our own `HttpJsonCodec` on Jackson 3. A denied host is a guest-visible error, not a trap. Extism's own `allowedHosts` is left empty — the allowlist is ours alone. |
| events | `extism:host/user` `fc_emit_event(json) → json` | the version's `ControlPlaneEvents`; the event JSON is `OutboundEvent`'s shape; the result is `{"ok":true}` or `{"ok":false,"error":"…"}` |
| time | WASI `clock_time_get` | the host clock |

Database access (`fc.db.*`) is W4, not here.

## 5. Fixtures

No Wasm toolchain in CI. So:
- **Refusal fixtures** are built at test time from WAT text with Endive's `wabt` (test scope):
  a module with a forbidden import, one without the entrypoint export, one whose declared memory
  minimum is over the cap, garbage bytes.
- **Behaviour fixtures** are small **Rust** guests (`extism-pdk`) whose `.wasm` is committed under
  `function-host/src/test/resources/wasm/` with their source beside it
  (`function-host/src/test/wasm-guests/`) and a `make wasm-fixtures` target that rebuilds them
  (`cargo build --release --target wasm32-unknown-unknown`, size-optimised). One guest exporting
  several functions is fine: `echo` (returns the request's fields and caller), `spin`
  (`loop {}`), `alloc` (allocates N MiB), `fail` (returns an error), and ones that call each host
  function (`config`, `secret`, `http`, `emit`, `log`). A test asserts each committed `.wasm`'s
  sha256 matches a recorded list, so a fixture cannot change without its source being rebuilt on
  purpose. JavaScript is W3's.

## 6. Tests (mutant each; these are the load-bearing ones)

1. A wasm version becomes READY and an HTTP call through the real listener reaches the guest and
   returns its result; request fields and the caller arrive intact (mutant: drop `pathParams` from
   the input JSON → assertion fails).
2. **Deadline:** `spin` with a 200 ms endpoint timeout answers the listener's timeout status and
   the call's thread is free within ~1 s; the next call succeeds (mutant: catch and ignore the
   interrupt in the adapter → the test fails or times out under `-Dsurefire.timeout`).
3. **Memory cap:** `alloc` past `wasmMemoryMb` → 500, and the next call succeeds on a fresh
   instance (mutant: return a failed instance to the pool → the next call fails).
4. **Concurrency:** N concurrent calls (N = `maxConcurrency`) all succeed and run in parallel
   (elapsed closer to one call than to N) — the pool, not one shared instance (mutant: a single
   instance behind a lock → the timing assertion fails).
5. Each refusal `Reason` from its WAT fixture (mutant per check).
6. `MetaspaceGuard` refuses a wasm load when headroom is gone (mutant: skip the check for wasm).
7. Host functions: config returns a declared key and nothing for an undeclared one; secret
   likewise and never appears in a log line; HTTP to an allowlisted loopback server works, to a
   non-allowlisted host is a guest-visible denial, and respects the invocation deadline; emit
   reaches the control plane; log lines land in the function's logger (mutant per function —
   e.g. bypass the allowlist → the denial test fails).
8. Unload closes the pool (instances released; a later call after unload is not served).
9. All existing function-host tests pass unedited; a JVM function in the same host keeps working
   beside a wasm one.

## 7. Docs

`function-host-core.md` (the seam), `function-host-reconciler.md` (R11 now: wasm loads),
`function-context.md` (the Wasm host functions table), `functions.md` (a short "Wasm functions"
section: the ABI JSON, the host functions, the limits), plan status.
