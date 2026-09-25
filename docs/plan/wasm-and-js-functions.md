# Plan: Wasm functions, and JavaScript through them

Owner request 2026-09-24. Builds on the existing design, `docs/function-runner-plan.md` §6
(Endive/Chicory + the Extism ABI; JavaScript as a Wasm guest via a QuickJS-based PDK), and on the
trust model: every function is the operator's own code — Wasm is for language choice, memory
caps and containment of mistakes, **not** sandboxing hostile tenants.

## Where things stand (verified 2026-09-24)

- The platform already accepts `runtime: wasm` end to end: create, publish (a `.wasm` digest),
  sign, promote. `wasmMemoryMb` is parsed, defaulted (64 MB) and ceilinged; the Wasm entrypoint is
  an export name (`^[A-Za-z_]\w*$`); artifacts, digests, upload and Sigstore verification are
  format-agnostic.
- The host refuses at load: `Reconciler` (≈L498) reports `RUNTIME_UNSUPPORTED` for any wasm
  entry. There is **no loader abstraction** — `Reconciler.loader` is a concrete
  `JvmFunctionLoader`; `LoadedFunction` wraps a `Function` + `URLClassLoader`.
- The function API (`Function`, `FunctionContext`, `Request`, `Result`, `Caller`, `Config`,
  `Secrets`, `Events`, `HttpCaller`, `DataSource`, `Clock`) is already value-based, so a request
  and a result serialise to the same bytes for any runtime (plan §4a).
- No Wasm or JS dependency, fixture or toolchain exists in the repo.

## Rulings (owner, 2026-09-24)

- **D1: Endive.** W0 no longer compares runtimes; it proves Endive for our needs (below).
- **D2: Extism**, behind our own `fc.*` host-function names.
- **D3 + D4: JavaScript through Wasm** (QuickJS via the Extism JS PDK); a JS function declares
  `runtime: wasm`; `fn init --lang js` scaffolds it.
- **D5: `fc.db.*` later** (W4).

Per `Claude.md` routing, W0, W1 and W2 are the parent model's (new abstractions, deadline and
allowlist enforcement); W3's sample/types/scaffold, W4's volume and W5 are Sonnet-shaped with
parent-model review.

## Decisions for the owner (before W1) — as asked

| # | Question | Recommendation |
|---|---|---|
| D1 | Runtime: **Endive** (Bytecode Alliance; Cranelift native backend via FFI) or **Chicory** (pure Java, compiler mode = wasm → JVM bytecode) | Decide from W0's measurements. Foundation governance favours Endive; a native backend costs the "no native library" property and may complicate the native fcdev and the jlink image. |
| D2 | ABI: **Extism** (plan's choice: bytes in/out, host functions, PDKs for Rust/JS/Go/C#/Zig/…) or our own minimal ABI | Extism, behind our own `fc.*` host-function names. The ABI is small; if Extism's steward fades, the PDKs are thin and forkable. Dylibso is a single company — say so plainly; it is the same governance question as Deno, with a much smaller exit cost. |
| D3 | JavaScript: **through Wasm** (QuickJS via the Extism JS PDK) or a JS engine in the host (GraalJS/Truffle) | Through Wasm: one runtime, one ABI, one memory cap, no second engine or Oracle-steered Truffle stack. Cost: an interpreter (QuickJS), no Node built-ins, npm only when bundled to one file. |
| D4 | Does a JS function declare `runtime: wasm`? | Yes — JavaScript is a build concern; the host sees a module. `fn init --lang js` scaffolds it. |
| D5 | Database access for Wasm guests (`fc.db.*`) in the first release? | No — ship W1–W3 without it; W4 adds it. |

## W0 findings (2026-09-24, spike in a scratch project, not merged)

**Setup.** Endive **1.1.0** (Maven `run.endive`, released 2026-09-03; `runtime`, `compiler`,
`wasi`, `wabt`, `build-time-compiler` + Maven plugin, `annotations-processor`; the Cranelift
"Redline" backend is published only as `-experimental`). **Endive is pure Java — no native library,
no JNI** — so the plan's jlink/native-fcdev worry about an FFI backend does not apply. Extism's
Java host SDK (`org.extism.sdk:chicory-sdk` 0.3.0, Oct 2025) is still on `com.dylibso.chicory`;
**porting it to Endive is mechanical** (42 files / ~2.8k lines, BSD-3; a package rename compiled
first time, the Extism kernel `extism-runtime.wasm` included). Its HTTP host functions need an
`HttpConfig` (Extism's `http-json-jackson` + `http-client-javanet`, neither depends on Chicory) —
in the product they are replaced by ours over `AllowlistHttpCaller` (W2). Guests: Rust via
`extism-pdk` 1.4 (138 KB module); JavaScript via `extism-js` 1.6.1 (2.4 MB — QuickJS inside;
needs binaryen's `wasm-merge`/`wasm-opt`; imports 25 `extism:host/env` functions + 12 WASI).

| | Rust | JavaScript |
|---|---|---|
| load, compiled (wasm → JVM bytecode) | ~0.2 s | ~0.9 s |
| first call, compiled | ~0.3 ms | ~3 ms |
| steady call, compiled / interpreted | **~20 µs** / 160 µs | **~340 µs** / 930 µs |
| metaspace per compiled module | ~3.8 MB | ~5.6 MB (one oversized function stays interpreted — Endive falls back per function) |
| deadline: `Thread.interrupt()` on a guest in `while(true)` | stops at once (`WasmInterruptedException`) | stops at once |
| memory cap (max pages below the need) | clean trap; the same instance answers the next call | clean guest `out of memory`; instance survives |

**Consequences for W1/W2.**
- **Deadlines need no abandoned-thread pool:** interrupt the invocation's thread at the deadline,
  exactly as the JVM path does (`InvocationRunner`). The plan's biggest risk is gone.
- **Memory cap:** `MemoryLimits(initial = the module's declared minimum, max = wasmMemoryMb)` —
  capping the *initial* size below the module's minimum fails instantiation.
- **Compile once per version, instantiate per concurrent call:** an Extism plugin instance is not
  thread-safe; the compiled machine is cached per module (`CachedAotMachineFactory`), so instances
  share compiled code and each has its own linear memory. A per-version pool sized by
  `maxConcurrency`, created lazily.
- **Metaspace:** compile mode costs 4–6 MB per version — `MetaspaceGuard` must count Wasm loads
  exactly like JVM loads.
- **Native fcdev:** runtime compilation defines classes, which GraalVM native cannot do — but the
  native fcdev already runs functions in a child `java -jar fc-fnhost.jar`, so Wasm follows the same
  path. `fc-server` native never hosts functions.
- **Logging:** Endive's `SystemLogger` writes to stderr/stdout; wire an SLF4J `Logger` into the port.
- **JS performance:** ~0.3 ms per call is fine behind HTTP; heavy compute belongs in Rust (or the JVM).
- **Build-time compilation** (Endive's Maven plugin) could later move the 0.2–0.9 s compile to
  publish time; not needed for W1.

## Packages

**W0 — spike (parent model, time-boxed, no merge).** Answer with numbers, in a scratch module:
Endive (D1) running an Extism JS-PDK module and a Rust module — its Maven coordinates and
release, whether Extism's Java host SDK runs on it or we bind the Extism kernel ourselves; cold load time and
first-call latency in compile mode; per-call overhead vs the JVM hello function; whether an
invocation can be **interrupted** at a deadline (if not: a dedicated pool abandoned on timeout,
plan §6); linear-memory cap enforcement; metaspace cost of compile mode (it generates classes);
whether each works under the jlink image and under GraalVM native (fcdev). Output: the findings
section above. **Done.**

**W1 — the loader seam and the Wasm loader** (one worktree, parent model).
- A sealed `FunctionLoader` (JVM | Wasm) chosen by `manifest.runtime()`; `Reconciler` holds the
  set, the `RUNTIME_UNSUPPORTED` branch goes. `LoadedFunction` becomes runtime-neutral
  (a `Loaded` handle with invoke/init/close; the JVM one keeps its class loader).
- `WasmFunctionLoader`: validate the module (exports the entrypoint, imports only `fc.*` and the
  Extism kernel), instantiate with `wasmMemoryMb` as the hard cap, pre-compile once per version.
  Refusals are `LoadOutcome.Refused` values with new `Reason`s (not exceptions).
- Invocation: `Request` → JSON bytes (body base64) → guest → JSON `Result`; the deadline enforced
  per W0's finding; permits and `MetaspaceGuard` apply unchanged (compile mode is metaspace).
- Fixtures: per W0 — tiny modules from WAT text at test time if the runtime can assemble WAT,
  else a minimal committed set with their source beside them.
- Tests: load/refuse/invoke/timeout/memory-cap/unload, each mutant-checked.

*Status (2026-09-25): **W1–W5 all landed** — W1+W2 `3b44ecc7` (runtime), W3 `4de4a51c` (JS guest,
sample, `fn init --lang js`), W4 `d7cb3bef` (`fc_db_*`), W5 (SPA create/publish, `fn validate`
test, docs, an e2e step publishing and calling the JS sample — e2e 55/55 on Java). Remaining from
this plan: "Later" (Rust guest sample + template, TinyGo). The note below is from the W1+W2 review:*

*Status (2026-09-24): W1 + W2 implemented per `docs/spec/function-wasm-runtime.md`, reviewed and merged. One W0 finding did not hold for the SDK as ported: `CompiledPlugin.instantiate()`
re-parses the module and builds a fresh `CachedAotMachineFactory` per instance, so every instance
recompiled (new classes, new metaspace); the vendored module's `ManifestWasm.fromModule` + a
once-per-process parsed kernel (`extism-endive/NOTICE`) let the host compile once per version and
share it across the pool.*

**W2 — host functions** (same worktree as W1, after it; parent model).
`fc.log`, `fc.config.get`, `fc.secret.get` (manifest-declared only), `fc.http.request`
(**enforced** allowlist and deadline — the same `AllowlistHttpCaller`), `fc.events.emit`,
`fc.now`. One JSON convention for every call; every host function's failure is a value the guest
sees, never a trap that kills the instance. Tests: each function reachable, each denial observed.

**W3 — JavaScript guest** (own worktree, Sonnet; needs W1+W2 merged).
`examples/function-hello-js` mirroring function-hello (three endpoints, a subscription, config and
secret); a thin `@flowcatalyst/function` guest package (TypeScript types over the `fc.*` calls and
the Request/Result shapes, published with the other SDKs); `fcdev fn init --lang js` (package.json,
esbuild bundle, extism-js compile step, manifest with `runtime: wasm`); `fcdev fn build` for JS if
the toolchain can be driven from fcdev, else documented npm scripts. An integration test publishes
the JS sample to a real host and calls every endpoint.

**W4 — `fc.db.*`** (own worktree, Sonnet; after W2; parallel with W3).
`query`, `execute`, `tx.begin/commit/rollback` over the manifest's `db[]` pools, a connection
handle scoped to the invocation and force-released at its end; rows as JSON.

**W5 — platform and UI** (small, after W3).
Enable `wasm` in the SPA's create drawer (shown disabled today), the manifest editor's runtime
picker, `fn validate` and docs (`functions.md`, `function-service-overview.md` §12,
`function-host-reconciler.md` R11); an e2e step publishing and calling the JS sample.

**Later:** a Rust guest sample + template (Extism Rust PDK), TinyGo.

## Worktrees and order

```
W0 (me) ──► rulings D1–D5 ──► W1 ─► W2 ──┬─► W3 (JS guest) ──► W5
                                          └─► W4 (fc.db)
```

W1/W2 share the loader and context files, so one worktree, in sequence. W3 and W4 touch
disjoint code (guest tooling vs host db functions) and run as two worktrees in parallel. Each
package gets its own spec in `docs/spec/` before its coder starts; the orchestrator reviews,
mutation-checks and runs the full suite before each merge.

## Risks

- **Interrupting a running guest** — if neither runtime can stop a call mid-flight, a stuck JS
  loop holds a permit until its thread is abandoned; W0 decides the mechanism.
- **Native fcdev** — a Cranelift/FFI backend may not work in the GraalVM-native fcdev; the native
  binary already delegates JVM functions to a child `java -jar fc-fnhost.jar`, and Wasm would
  follow the same path, so this is a cost, not a blocker.
- **Performance of JS** — QuickJS is an interpreter; fine for glue and webhooks, not for heavy
  compute. State it in the docs rather than discover it in production.
