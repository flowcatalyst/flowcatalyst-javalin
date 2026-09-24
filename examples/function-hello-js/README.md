# function-hello-js

The JavaScript twin of [`examples/function-hello`](../function-hello) — the reference every
JS function author reads first (`docs/spec/function-js-guest.md` §2, `docs/functions.md`
"JavaScript functions"). JavaScript functions run **through Wasm** (QuickJS via the Extism JS
PDK); the manifest declares `"runtime": "wasm"` and `"entrypoint": "handle"`.

## Toolchain

- Node 24 / npm
- [`extism-js`](https://github.com/extism/js-pdk) 1.6.x on `PATH`
- [Binaryen](https://github.com/WebAssembly/binaryen)'s `wasm-opt` and `wasm-merge` on `PATH`
  (`brew install binaryen`)

## Build

```bash
npm install
npm run build
```

This bundles `src/index.ts` to CJS with `esbuild` (`dist/index.js`), then compiles it to a
Wasm module with `extism-js` against `@flowcatalyst/function`'s shipped interface file
(`dist/function.wasm`).

The committed fixture the host's tests load
(`function-host/src/test/resources/wasm/js/function_hello_js.wasm`) is rebuilt by
`make wasm-fixtures` at the repo root, which records its sha256 in the `js/` directory's own
`SHA256SUMS` — CI has no JS→Wasm toolchain, so the built artifact is committed.

## What it does

- `GET /healthz` (`auth: none`) — a liveness probe.
- `POST /events/greeting-requested` (`auth: webhook`) — a subscription delivery: reads the
  `GREETING` config value, checks the `API_KEY` secret's presence (never its value), emits a
  `hello:greeting:greeting:sent` event, acks or asks for a retry on a platform 5xx.
- `GET /api/hello/{name}` (`auth: platform`) — checks the caller's `hello:greeting:greet`
  permission before doing anything else, then greets them by name.
- `GET /api/proxy?url=` (`auth: none`) — not in the Java example: calls `ctx.http.request`
  against the given URL and reports whether the host allowed it, demonstrating
  `HttpDenied` for a host outside `manifest.httpAllow`.
