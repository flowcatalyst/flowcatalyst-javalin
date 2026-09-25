# function-hello-rust

The Rust twin of [`examples/function-hello`](../function-hello) and
[`examples/function-hello-js`](../function-hello-js) — the reference every Rust function author
reads first (`docs/spec/function-rust-guest.md` §2, `docs/functions.md` "Rust functions"). Rust
functions run **through Wasm** (compiled straight to `wasm32-unknown-unknown` with the Extism
Rust PDK); the manifest declares `"runtime": "wasm"` and `"entrypoint": "handle"`.

## Toolchain

- Rust (`rustup`) with the `wasm32-unknown-unknown` target: `rustup target add wasm32-unknown-unknown`
- `cargo`

## Build

```bash
cargo build --release --locked --target wasm32-unknown-unknown
```

The built module lands at `target/wasm32-unknown-unknown/release/function_hello_rust.wasm`.

The committed fixture the host's tests load
(`function-host/src/test/resources/wasm/rust/function_hello_rust.wasm`) is rebuilt by
`make wasm-fixtures` at the repo root, which records its sha256 in the `rust/` directory's own
`SHA256SUMS`.

## What it does

- `GET /healthz` (`auth: none`) — a liveness probe.
- `POST /events/greeting-requested` (`auth: webhook`) — a subscription delivery: reads the
  `GREETING` config value, checks the `API_KEY` secret's presence (never its value), emits a
  `hello:greeting:greeting:sent` event, acks.
- `GET /api/hello/{name}` (`auth: platform`) — checks the caller's `hello:greeting:greet`
  permission before doing anything else, then greets them by name.
- `GET /api/proxy?url=` (`auth: none`) — not in the Java example: calls `ctx.http.request`
  against the given URL and reports whether the host allowed it, demonstrating `HttpDenied` for a
  host outside `manifest.httpAllow`.
