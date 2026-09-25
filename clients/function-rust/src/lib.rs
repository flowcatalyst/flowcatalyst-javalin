//! `flowcatalyst-function` — the Rust guest library for FlowCatalyst
//! functions (`docs/spec/function-rust-guest.md`), the Rust twin of
//! `@flowcatalyst/function` (`clients/function-js`). A function author
//! depends on this crate via a `path` dependency (fcdev ships it with the
//! `fn init --lang rust` scaffold, as it ships `@flowcatalyst/function`'s
//! source — this crate is not published to crates.io) and wraps their logic
//! with [`handler`]:
//!
//! ```ignore
//! use extism_pdk::plugin_fn;
//! use flowcatalyst_function::{handler, FunctionResult};
//!
//! #[plugin_fn]
//! pub fn handle(_input: String) -> extism_pdk::FnResult<String> {
//!     handler(|req, ctx| -> Result<FunctionResult, String> {
//!         if req.path == "/healthz" {
//!             return FunctionResult::json(200, &serde_json::json!({"status": "ok"}))
//!                 .map_err(|e| e.to_string());
//!         }
//!         Ok(FunctionResult::ok())
//!     })
//! }
//! ```
//!
//! ## What `cargo test` on the host target covers, and what it does not
//!
//! Every type in `caller`, `request` and `result` is pure — plain data and
//! `serde_json`, no Extism import anywhere in the call graph — so those
//! modules' tests run as ordinary `cargo test` on the host target
//! (`aarch64`/`x86_64`, whatever the machine is), exactly the spec's "cargo
//! test on the host target for the pure parts": request parsing from the ABI
//! JSON fixture the JS library also tests against, the result JSON shape,
//! the caller case table (read directly from
//! `clients/function-js/test/fixtures/`, never copied), and `handler`
//! turning an error into the fixed-reason 500.
//!
//! `context` and `db` are different: every one of their functions calls an
//! Extism host import (`extism:host/env` or `extism:host/user`) that is
//! resolved only by a real Wasm host — there is no native fallback, and
//! unlike the JS library (which can fake `globalThis.Config`/`Http`/`Host`
//! with plain object monkey-patching for `context.test.ts`), Rust cannot
//! fake a linked `extern "C"` symbol the same way. A native `cargo test`
//! binary still LINKS successfully as long as nothing in the test's call
//! graph actually calls one of those functions (verified empirically against
//! this toolchain: an unreferenced `extern "C"` import is fine, a called one
//! is a hard link error) — so these modules compile and their **types**
//! participate in the pure tests above, but their host-calling **behaviour**
//! (config/secrets/http/events/db) is proven by the real Endive/Extism
//! runtime instead, in the host integration test `WasmFunctionHelloRustTest`
//! (the twin of `WasmFunctionHelloJsTest`).

mod base64;
mod caller;
mod context;
mod db;
mod handler;
mod http_denied;
mod request;
mod result;

pub use caller::{Caller, PrincipalCaller};
pub use context::{
    Config, Context, EventEmitException, Events, Http, HttpReply, HttpRequestInit, Logger, OutboundEvent, Secrets,
};
pub use db::{Db, DbError, DbRows, DbValue, Tx};
pub use handler::{handler, run, UNCAUGHT_REASON};
pub use http_denied::HttpDenied;
pub use request::FunctionRequest;
pub use result::{FunctionResult, ResultError};
