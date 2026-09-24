# JavaScript functions (W3)

Plan: `docs/plan/wasm-and-js-functions.md` (D3/D4: JavaScript runs **through Wasm** — QuickJS via
the Extism JS PDK — and a JS function declares `runtime: wasm`). The host side and the invocation
ABI are `docs/spec/function-wasm-runtime.md` §3–§4; this unit is the author's side only. Starts
after W1+W2 are merged.

Toolchain (W0): `extism-js` (1.6.x) plus binaryen's `wasm-opt` and `wasm-merge` on the PATH; an
author's TypeScript is bundled to one file with `esbuild` first. No Node built-ins exist inside
the guest; npm packages work only when bundled.

## 1. `@flowcatalyst/function` — the guest library (new, `clients/function-js/`)

A small TypeScript package an author imports; no runtime dependencies.
- Types mirroring the ABI exactly: `FunctionRequest` (with `body(): Uint8Array`,
  `text(): string`, `json<T>(): T` helpers over `bodyBase64`), `Caller`
  (`platform | anonymous | principal` with the principal's fields and the same
  `hasPermission`/`hasRole`/`canAccessClient`/`canAccessApplication` helpers `Caller.Principal`
  has in Java — same semantics, tested against the same cases), `FunctionResult` and builders
  `ok/json/text/status/fail/retry(seconds)` matching Java's `Result` factories.
- `handler(fn: (req: FunctionRequest, ctx: Context) => FunctionResult)` — returns the export:
  reads `Host.inputString()`, parses, calls `fn`, writes the output JSON, returns 0; an uncaught
  exception becomes `fail(message)` (a 500), never a trapped instance.
- `Context`: `config.get(key)`/`require(key)` (Extism `Config.get`), `secrets.get`/`require`
  (the `fc_secret_get` user host function), `http.request({method,url,headers,body})` (Extism
  `Http.request`; a denied host surfaces as a thrown `HttpDenied`), `events.emit(event)`
  (`fc_emit_event`; `OutboundEvent`'s fields), `log.debug/info/warn/error`, `now()`.
- `interface.d.ts` template declaring the `handle` export and the two `extism:host/user` imports,
  shipped with the package so authors never write it by hand.
- Tests (vitest, no Wasm needed): request parsing from ABI JSON fixtures (the same JSON the Java
  host tests produce — copy one real input from W1's `echo` test), result JSON shape, the caller
  helpers against a shared case table, `handler` turning a throw into a 500.

## 2. `examples/function-hello-js`

Mirrors `examples/function-hello`: three endpoints (webhook, platform, none), one subscription,
`GREETING` config and `API_KEY` secret, a `/healthz`. `package.json` scripts: `build` =
`esbuild src/index.ts --bundle --format=cjs --target=es2020 --outfile=dist/index.js` then
`extism-js dist/index.js -i node_modules/@flowcatalyst/function/interface.d.ts -o
dist/function.wasm`. `manifest.json` with `runtime: wasm`, `entrypoint: handle`, `$schema`.
The built `function.wasm` is committed under `function-host/src/test/resources/wasm/js/` with a
recorded sha256 (CI has no JS→Wasm toolchain), rebuilt by `make wasm-fixtures`.

## 3. `fcdev fn init --lang js`

Writes the example's shape for a new function: `package.json` (depending on
`@flowcatalyst/function` via `file:lib/flowcatalyst-function` — fcdev **ships the library with
the scaffold**, as it ships the JVM function API; the package is not on npm), `tsconfig.json`,
`src/index.ts`, `manifest.json` (`runtime: wasm`), a README with the build steps and the toolchain
it needs. `--lang java` stays the default; `--runtime wasm` without `--lang` keeps today's
manifest-only behaviour. `fn build` is **not** added: the scaffold's `npm run build` is the build
(fcdev never drives npm).

## 4. Host integration test (function-host)

The committed `function-hello-js` module loads through the real reconciler and listener; every
endpoint answers as the Java hello function does for the same inputs (greeting from config, the
secret reachable, caller identity echoed on the platform endpoint, webhook POST accepted); an
`http.request` to a non-allowlisted host is a clean denial (mutant: bypass the allowlist).

## 5. Docs

`docs/functions.md` — "JavaScript functions": scaffold, build, the toolchain, what works and what
does not (no Node built-ins; bundle npm packages; QuickJS speed — ~0.3 ms per call, fine for
glue and webhooks, not heavy compute), the limits.
