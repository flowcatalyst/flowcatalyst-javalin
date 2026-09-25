//! Wraps a handler closure as the module's `#[plugin_fn]` body
//! (`docs/spec/function-rust-guest.md` §1): reads the host's input, parses a
//! [`FunctionRequest`], calls the author's function, writes the
//! [`FunctionResult`] as the ABI's output JSON. See `src/result.rs`'s module
//! doc for why the author's function returns `Result<FunctionResult, E>`
//! rather than the JS library's throwing convention — an `Err` here plays
//! the same role a caught exception plays in `handler.ts`: it becomes
//! [`UNCAUGHT_REASON`] (a `500`), logged with the real detail, never leaking
//! that detail into the response body.
//!
//! Usage — `src/lib.rs` of a function project:
//! ```ignore
//! use extism_pdk::plugin_fn;
//! use flowcatalyst_function::{handler, FunctionResult};
//!
//! #[plugin_fn]
//! pub fn handle(_input: String) -> extism_pdk::FnResult<String> {
//!     handler(|req, ctx| -> Result<FunctionResult, String> {
//!         Ok(FunctionResult::json(200, &serde_json::json!({"hello": req.path_params.get("name")}))?)
//!     })
//! }
//! ```
//!
//! [`run`] (below `handler`) is the pure part: it takes the request JSON as a
//! plain `&str` rather than reading it from the host, so it is fully
//! testable with `cargo test` on the host target — the exact same funnel
//! `handler` uses, just fed a fixture string instead of `Host::input_string`.

use crate::base64;
use crate::context::Context;
use crate::request::{parse_request, FunctionRequest};
use crate::result::FunctionResult;
use std::collections::BTreeMap;
use std::fmt::Display;

/// The body reason for an author error — fixed, so nothing the error carries
/// reaches the caller (and never blank, which `FunctionResult::fail` would
/// otherwise refuse).
pub const UNCAUGHT_REASON: &str = "the function failed";

/// The `#[plugin_fn]` entry point a function's own `handle` export calls.
pub fn handler<F, E>(f: F) -> extism_pdk::FnResult<String>
where
    F: FnOnce(&FunctionRequest, &Context) -> Result<FunctionResult, E>,
    E: Display,
{
    let raw = extism_pdk::input::<String>()?;
    Ok(run(&raw, f))
}

/// The pure funnel [`handler`] wraps: parses `raw` (the ABI's request JSON),
/// builds a [`Context`], runs `f`, and encodes the outcome as the ABI's
/// output JSON — never panics, never propagates `f`'s error text into the
/// returned body.
pub fn run<F, E>(raw: &str, f: F) -> String
where
    F: FnOnce(&FunctionRequest, &Context) -> Result<FunctionResult, E>,
    E: Display,
{
    let result = match try_run(raw, f) {
        Ok(result) => result,
        Err(message) => {
            log_uncaught(&message);
            FunctionResult::fail(UNCAUGHT_REASON).expect("UNCAUGHT_REASON is a fixed, non-blank reason")
        }
    };
    encode_result(&result)
}

fn try_run<F, E>(raw: &str, f: F) -> Result<FunctionResult, String>
where
    F: FnOnce(&FunctionRequest, &Context) -> Result<FunctionResult, E>,
    E: Display,
{
    let value: serde_json::Value = serde_json::from_str(raw).map_err(|e| e.to_string())?;
    let request = parse_request(&value)?;
    let ctx = Context::build();
    f(&request, &ctx).map_err(|e| e.to_string())
}

#[cfg(target_arch = "wasm32")]
fn log_uncaught(message: &str) {
    extism_pdk::error!("uncaught error in the handler: {}", message);
}

/// Off the Wasm target (this crate's own `cargo test`), `extism_pdk::error!`
/// links to nothing real — mirrored to stderr instead so a test can still
/// observe that the detail WAS logged somewhere, just never in the body.
#[cfg(not(target_arch = "wasm32"))]
fn log_uncaught(message: &str) {
    eprintln!("uncaught error in the handler: {message}");
}

/// Encodes a [`FunctionResult`] as the ABI's guest-output JSON
/// (`docs/spec/function-wasm-runtime.md` §3): `{"status","headers","bodyBase64"}`.
fn encode_result(result: &FunctionResult) -> String {
    let headers: BTreeMap<&String, &Vec<String>> = result.headers.iter().collect();
    serde_json::json!({
        "status": result.status,
        "headers": headers,
        "bodyBase64": base64::encode(&result.body),
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn echo_fixture_raw() -> String {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../function-js/test/fixtures/echo-request.json");
        fs::read_to_string(path).unwrap_or_else(|e| panic!("reading {path}: {e}"))
    }

    fn decode_body(written: &serde_json::Value) -> Vec<u8> {
        let b64 = written["bodyBase64"].as_str().expect("bodyBase64 present");
        base64::decode(b64).unwrap()
    }

    #[test]
    fn parses_the_request_calls_f_and_writes_the_encoded_result() {
        let raw = echo_fixture_raw();
        let written_str = run(&raw, |req, _ctx| -> Result<FunctionResult, String> {
            FunctionResult::json(200, &serde_json::json!({"sawPath": req.path})).map_err(|e| e.to_string())
        });
        let written: serde_json::Value = serde_json::from_str(&written_str).unwrap();
        assert_eq!(written["status"], 200);
        let body: serde_json::Value = serde_json::from_slice(&decode_body(&written)).unwrap();
        assert_eq!(body, serde_json::json!({"sawPath": "/echo/42"}));
    }

    // mutant: let the error propagate (panic) or swallow it silently instead of converting it —
    // this is the one thing standing between a bug in a function author's code and an
    // uncontrolled instance trap (see result.rs's module doc: unlike JS, a Rust panic on
    // wasm32-unknown-unknown cannot be caught here at all, so the Result-returning contract IS
    // the whole safety net).
    #[test]
    fn turns_an_err_into_the_fixed_reason_500_never_letting_it_propagate() {
        let raw = echo_fixture_raw();
        let written_str = run(&raw, |_req, _ctx| -> Result<FunctionResult, String> {
            Err("boom from the handler".to_string())
        });
        let written: serde_json::Value = serde_json::from_str(&written_str).unwrap();
        assert_eq!(written["status"], 500);
        let body: serde_json::Value = serde_json::from_slice(&decode_body(&written)).unwrap();
        assert_eq!(body["error"], UNCAUGHT_REASON);
    }

    // mutant: answer the error's own message — a webhook or public caller would read internals.
    #[test]
    fn never_answers_the_errors_own_message() {
        let raw = echo_fixture_raw();
        let written_str = run(&raw, |_req, _ctx| -> Result<FunctionResult, String> {
            Err("db password is hunter2".to_string())
        });
        let written: serde_json::Value = serde_json::from_str(&written_str).unwrap();
        let body = decode_body(&written);
        assert!(!String::from_utf8_lossy(&body).contains("hunter2"));
    }

    // mutant: FunctionResult::fail(e.to_string()) instead of the fixed reason — a blank message
    // (fail() refuses blank input) would then make the internal expect() panic, escaping the
    // funnel entirely instead of answering a controlled 500.
    #[test]
    fn an_error_with_a_blank_display_is_still_a_500_not_a_panic() {
        let raw = echo_fixture_raw();
        let written_str = run(&raw, |_req, _ctx| -> Result<FunctionResult, String> { Err(String::new()) });
        let written: serde_json::Value = serde_json::from_str(&written_str).unwrap();
        assert_eq!(written["status"], 500);
    }

    #[test]
    fn malformed_input_json_is_also_a_controlled_500_not_a_panic() {
        let written_str = run("not json at all", |_req, _ctx| -> Result<FunctionResult, String> {
            Ok(FunctionResult::ok())
        });
        let written: serde_json::Value = serde_json::from_str(&written_str).unwrap();
        assert_eq!(written["status"], 500);
    }

    #[test]
    fn encodes_multi_valued_headers_as_arrays_in_the_wire_output() {
        let raw = echo_fixture_raw();
        let mut headers = BTreeMap::new();
        headers.insert("X-Multi".to_string(), vec!["a".to_string(), "b".to_string()]);
        let written_str = run(&raw, move |_req, _ctx| -> Result<FunctionResult, String> {
            FunctionResult::status(200, headers.clone(), Vec::<u8>::new()).map_err(|e| e.to_string())
        });
        let written: serde_json::Value = serde_json::from_str(&written_str).unwrap();
        assert_eq!(written["headers"]["X-Multi"], serde_json::json!(["a", "b"]));
    }
}
