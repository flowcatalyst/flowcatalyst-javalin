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

## Packages

**W0 — spike (parent model, time-boxed, no merge).** Answer with numbers, in a scratch module:
Endive (D1) running an Extism JS-PDK module and a Rust module — its Maven coordinates and
release, whether Extism's Java host SDK runs on it or we bind the Extism kernel ourselves; cold load time and
first-call latency in compile mode; per-call overhead vs the JVM hello function; whether an
invocation can be **interrupted** at a deadline (if not: a dedicated pool abandoned on timeout,
plan §6); linear-memory cap enforcement; metaspace cost of compile mode (it generates classes);
whether each works under the jlink image and under GraalVM native (fcdev). Output: a findings
section appended here, and the D1/D2 rulings asked for.

**W1 — the loader seam and the Wasm loader** (one worktree, Sonnet).
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

**W2 — host functions** (same worktree as W1, after it).
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
