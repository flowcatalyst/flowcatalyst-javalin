# Rust functions (W6 — the plan's "Later: a Rust guest sample + template")

Plan: `docs/plan/wasm-and-js-functions.md`. Rust compiles straight to Wasm with the Extism Rust PDK
(`extism-pdk`, the version the host's own test guest pins: `=1.4.1`,
`function-host/src/test/wasm-guests/fc-test-guest/Cargo.toml`) and declares `runtime: wasm`. This
unit is the Rust twin of W3 (`docs/spec/function-js-guest.md`) — read that spec and its
implementation (`clients/function-js/`, `examples/function-hello-js/`, `fcdev fn init --lang js`,
`WasmFunctionHelloJsTest`) and mirror each part. The ABI and host functions are
`docs/spec/function-wasm-runtime.md` §3–§4 and `docs/spec/function-wasm-db.md`; the test guest
`function-host/src/test/wasm-guests/fc-test-guest/src/lib.rs` already calls every host function.

## 1. `flowcatalyst-function` — the guest crate (new, `clients/function-rust/`)

A small library crate, no dependencies beyond `extism-pdk`, `serde`, `serde_json`:
- Types mirroring the ABI exactly (as the JS library's `types.ts` does): `FunctionRequest` (`body()`
  bytes, `text()`, `json::<T>()` over `bodyBase64`), `Caller` (`Platform | Anonymous | Principal`
  with `has_permission` / `has_role` / `can_access_client` / `can_access_application` — the same
  semantics and the same shared case table the JS library tests against,
  `clients/function-js/test/fixtures/caller-cases.json`), `FunctionResult` with builders
  `ok / json / text / status / fail / retry(seconds)` matching Java's and the JS library's.
- A `handler` entry point (a function the author's `#[plugin_fn]` export calls, or a small macro if
  that is idiomatic): reads the input, calls the author's function, writes the output JSON; an
  author error (`Err` or a panic caught where the PDK allows) becomes `fail` with a **fixed**
  reason, logged — never the error text in the response body (the JS library's rule, `handler.ts`).
- `Context`: config (`extism_pdk::config::get`), secrets (`fc_secret_get`), http (`extism_pdk::http`,
  a denied host surfaces as an error value), events (`fc_emit_event`), database (`fc_db_query /
  execute / begin / commit / rollback` with `db(name).query(sql, params)` style helpers returning
  `Result` values that carry the host's `{code, message}`), log, now.
- Tests: `cargo test` on the host target for the pure parts (request parsing from the same ABI JSON
  fixture the JS library uses, result JSON shape, the caller case table, handler turning an error
  into the fixed-reason 500).

## 2. `examples/function-hello-rust`

Mirrors `examples/function-hello-js`: the same endpoints (webhook, platform, none, `/healthz`), the
same subscription, `GREETING` config, `API_KEY` secret, `manifest.json` with `runtime: wasm`,
`entrypoint: handle`. Built with `cargo build --release --target wasm32-unknown-unknown`; the built
module is committed at `function-host/src/test/resources/wasm/rust/function_hello_rust.wasm` with its
own `SHA256SUMS` in that directory, and `make wasm-fixtures` gains the recipe lines (append; do not
restructure the existing ones).

## 3. `fcdev fn init --lang rust`

Writes a new Rust function project: `Cargo.toml` (depending on `flowcatalyst-function` via
`path = "lib/flowcatalyst-function"` — fcdev ships the crate with the scaffold, as it ships the JS
library; the crate is not on crates.io), `src/lib.rs`, `manifest.json` (`runtime: wasm`), a README
with the build steps (`rustup target add wasm32-unknown-unknown`, `cargo build --release …`, `fn
publish`). The crate is packaged into fcdev exactly as the JS library is (`fcdev/pom.xml`'s antrun
zip; the native IncludeResources pattern already covers `fn-init/.*`). `--lang` accepts `java | js |
rust`; `--lang rust` forces `runtime: wasm` and refuses an explicit `--runtime jvm`, as `--lang js`.

## 4. Host integration test (function-host)

`WasmFunctionHelloRustTest`, the twin of `WasmFunctionHelloJsTest`: the committed module loads
through the real reconciler and listener; every endpoint answers as the JS one does for the same
inputs; an `http` call to a non-allowlisted host is a clean denial (mutant: bypass the allowlist).
Plus a `WasmRustFixturesTest` pinning the sha256, as `WasmJsFixturesTest` does.

## 5. Docs

`docs/functions.md` — a "Rust functions" section beside §8b (scaffold, toolchain, build, what the
crate offers, the limits); `docs/function-service-overview.md` §12 gains a Rust line.
