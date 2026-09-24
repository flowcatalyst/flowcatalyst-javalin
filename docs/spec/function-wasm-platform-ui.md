# Wasm functions in the platform UI and CLI (W5)

Plan: `docs/plan/wasm-and-js-functions.md` W5. W1–W4 landed: the host runs `runtime: wasm` modules
(Endive + Extism), JavaScript functions build to Wasm (`examples/function-hello-js`, committed module
`function-host/src/test/resources/wasm/js/function_hello_js.wasm`), and Wasm guests reach their
databases. The platform already accepts `runtime: wasm` on create and publish (nothing refuses it).
What is left is the operator's surface.

## 1. SPA

- `frontend/src/pages/functions/FunctionCreateDrawer.vue`: the runtime select offers **WASM** enabled
  (today "WASM (not yet supported)", disabled, with the hint "WASM functions are refused by the
  platform today"). Replace the hint with one line on what a Wasm function is (a module built with an
  Extism PDK — JavaScript via `fcdev fn init --lang js`), no "not supported" text anywhere.
- The publish flow already takes an artifact file: make sure it accepts a `.wasm` file for a `wasm`
  function (the file input's `accept`, any client-side check, and the label text), and a `.jar` for a
  `jvm` one. Read the drawer that publishes versions and change only what refuses or mislabels a
  `.wasm`.
- `ManifestEditorDrawer.vue` already offers `wasm` and `wasmMemoryMb`; verify nothing else in the
  editor hides or refuses Wasm-only fields, and change nothing if it does not.
- SPA unit tests (vitest) where the repo already tests these components: the create drawer offers an
  enabled `wasm` option, and the publish input accepts `.wasm` for a Wasm function.
- Rebuild the embedded SPA (`make frontend`) and commit it, as every SPA change here does.

## 2. `fcdev fn validate`

Read `fcdev/src/main/java/io/flowcatalyst/fcdev/fn/ValidateCommand.java`. If it refuses or mishandles a
`runtime: wasm` manifest or a `.wasm` artifact, fix that; if it already handles both, add a test that
pins it (a `wasm` manifest validates; a `wasm` manifest with a `wasmMemoryMb` over the policy cap is
reported).

## 3. Docs

`docs/functions.md` (its Wasm/JS sections exist — only remove any remaining "not supported" text),
`docs/function-service-overview.md` §12 (add Wasm/JS functions: runtime, ABI, host functions, how to
build, the limits), `docs/spec/function-host-reconciler.md` R11 only if it is out of date.

## 4. e2e (`e2e/tests/functions.spec.ts`)

A new test beside the JVM flow, same fixtures and style: create a `wasm` function through the UI,
publish `function_hello_js.wasm` with `examples/function-hello-js/manifest.json` (adapted exactly as
the JVM test adapts its manifest — pool, address), wait for READY, promote to `live`, wait for the
host's LOADED, and call it through the function host exactly as the JVM test calls its function —
assert the JS function's own answer (its `/healthz`, and the greeting endpoint's body from its
`GREETING` config if the JVM test sets config the same way). Run it with `pnpm e2e:java` (the Java
side only; the Go side has no Wasm).

## Tests / mutants

Each behaviour above has a test that fails on the old code (the disabled option; a `.wasm` refused;
the e2e call answering). Report which assertion pins which.
