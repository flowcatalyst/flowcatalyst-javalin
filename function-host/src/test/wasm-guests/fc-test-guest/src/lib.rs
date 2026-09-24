//! Behaviour fixture for the function host's Wasm support
//! (docs/spec/function-wasm-runtime.md §5). One module, several exports; a test
//! picks one by naming it as the manifest's `entrypoint`. Every export receives
//! the host's request JSON (spec §3) and answers with the result JSON
//! `{"status", "headers", "body"}` — parameters come from the request's query.
//! Every reply carries `x-instance-calls`: how many calls this instance has
//! served, so a test can tell a fresh instance (`1`) from a reused one.
//!
//! Rebuild with `make wasm-fixtures` (never by hand): it records the module's
//! sha256, and a test fails when the committed module and that record disagree.

use extism_pdk::*;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicU64, Ordering};

// The two FlowCatalyst host functions (spec §4) — everything else a guest may
// reach is an Extism built-in or WASI.
#[host_fn("extism:host/user")]
extern "ExtismHost" {
    fn fc_secret_get(key: String) -> String;
    fn fc_emit_event(event: String) -> String;
}

// WASI, imported directly: this module targets wasm32-unknown-unknown, whose std
// has no clock or stdout of its own.
#[repr(C)]
struct Ciovec {
    buf: *const u8,
    buf_len: usize,
}

#[link(wasm_import_module = "wasi_snapshot_preview1")]
extern "C" {
    fn clock_time_get(id: u32, precision: u64, time: *mut u64) -> u16;
    fn fd_write(fd: u32, iovs: *const Ciovec, iovs_len: usize, nwritten: *mut usize) -> u16;
}

const CLOCK_REALTIME: u32 = 0;

/// Calls this instance has served, counted at every export's entry. Linear
/// memory is per instance, so a fresh instance's first call counts `1`.
static CALLS: AtomicU64 = AtomicU64::new(0);

fn enter() -> u64 {
    CALLS.fetch_add(1, Ordering::SeqCst) + 1
}

fn now_nanos() -> u64 {
    let mut t: u64 = 0;
    unsafe {
        clock_time_get(CLOCK_REALTIME, 1, &mut t);
    }
    t
}

fn write_fd(fd: u32, text: &str) {
    let iov = Ciovec { buf: text.as_ptr(), buf_len: text.len() };
    let mut written: usize = 0;
    unsafe {
        fd_write(fd, &iov, 1, &mut written);
    }
}

fn request(input: &str) -> Value {
    serde_json::from_str(input).unwrap_or(Value::Null)
}

/// The first value of query parameter `name`, if any.
fn query(req: &Value, name: &str) -> Option<String> {
    req.get("query")?.get(name)?.get(0)?.as_str().map(|s| s.to_string())
}

fn reply(calls: u64, status: u16, body: Value) -> FnResult<String> {
    Ok(json!({
        "status": status,
        "headers": {
            "content-type": ["application/json"],
            "x-instance-calls": [calls.to_string()],
        },
        "body": body.to_string(),
    })
    .to_string())
}

/// The request exactly as the host sent it, as the response body — the host's
/// tests read every field back out (spec §6 test 1).
#[plugin_fn]
pub fn echo(input: String) -> FnResult<String> {
    let calls = enter();
    Ok(json!({
        "status": 200,
        "headers": {
            "content-type": ["application/json"],
            "x-guest": ["echo", "twice"],
            "x-instance-calls": [calls.to_string()],
        },
        "body": input,
    })
    .to_string())
}

/// Busy-waits `ms` milliseconds on the WASI clock (default 300) — a call with a
/// known duration that holds its instance the whole time (spec §6 test 4).
#[plugin_fn]
pub fn busy(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    let ms: u64 = query(&req, "ms").and_then(|v| v.parse().ok()).unwrap_or(300);
    let start = now_nanos();
    let until = start + ms * 1_000_000;
    let mut spins: u64 = 0;
    while now_nanos() < until {
        spins = std::hint::black_box(spins + 1);
    }
    reply(calls, 200, json!({"ms": ms, "startNanos": start, "endNanos": now_nanos()}))
}

/// Never returns (spec §6 test 2) — unless the query says `spin=false`, so a
/// test can prove the SAME version answers again after a deadline.
#[plugin_fn]
pub fn spin(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    if query(&req, "spin").as_deref() == Some("false") {
        return reply(calls, 200, json!({"spun": false}));
    }
    let mut n: u64 = 0;
    loop {
        n = std::hint::black_box(n.wrapping_add(1));
    }
}

/// Allocates and touches `mb` MiB (default 1) in the guest's own linear memory
/// (spec §6 test 3).
#[plugin_fn]
pub fn alloc(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    let mb: usize = query(&req, "mb").and_then(|v| v.parse().ok()).unwrap_or(1);
    let v = vec![7u8; mb * 1024 * 1024];
    let sum: u64 = std::hint::black_box(&v).iter().step_by(4096).map(|b| *b as u64).sum();
    reply(calls, 200, json!({"allocatedMb": mb, "sum": sum}))
}

/// Allocates `mb` MiB through the Extism KERNEL (`Memory::new`), 1 MiB at a time, never freed —
/// the kernel's own memory, which the guest's linear-memory cap does not cover.
#[plugin_fn]
pub fn kalloc(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    let mb: usize = query(&req, "mb").and_then(|v| v.parse().ok()).unwrap_or(1);
    let block = vec![7u8; 1024 * 1024];
    // The kernel answers offset 0 when it cannot allocate (its memory is capped); the PDK does
    // not treat that as an error, so count how many MiB were really granted.
    let mut granted = 0usize;
    for _ in 0..mb {
        let m = Memory::new(&block)?;
        if m.offset() == 0 {
            break;
        }
        granted += 1;
        std::mem::forget(m);
    }
    reply(calls, 200, json!({"requestedMb": mb, "grantedMb": granted}))
}

/// Returns Extism's error code — unless the query says `fail=false`.
#[plugin_fn]
pub fn fail(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    if query(&req, "fail").as_deref() == Some("false") {
        return reply(calls, 200, json!({"failed": false}));
    }
    Err(WithReturnCode::new(Error::msg("the guest failed on purpose"), 1))
}

/// Returns a body that is not the result JSON shape.
#[plugin_fn]
pub fn malformed(_input: String) -> FnResult<String> {
    enter();
    Ok("this is not the result shape".to_string())
}

/// `config_get(query.key)`.
#[plugin_fn]
pub fn config(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    let key = query(&req, "key").unwrap_or_default();
    let value = config::get(&key)?;
    reply(calls, 200, json!({"key": key, "value": value}))
}

/// `fc_secret_get(query.key)` — the value comes back in the body so the test can
/// prove the lookup worked (and prove the host never logs it).
#[plugin_fn]
pub fn secret(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    let key = query(&req, "key").unwrap_or_default();
    let value = unsafe { fc_secret_get(key.clone())? };
    reply(calls, 200, json!({"key": key, "value": value}))
}

/// GET `query.url` through Extism's `http_request`; reports what the guest saw.
#[plugin_fn]
pub fn http(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    let url = query(&req, "url").unwrap_or_default();
    let outbound = HttpRequest::new(url).with_method("GET").with_header("x-from-guest", "yes");
    let res = http::request::<()>(&outbound, None)?;
    let body = String::from_utf8_lossy(&res.body()).to_string();
    let upstream = res.header("x-upstream").map(|s| s.to_string());
    reply(calls, 200, json!({"status": res.status_code(), "body": body, "xUpstream": upstream}))
}

/// `fc_emit_event` with an event built from the query; the host's JSON answer is
/// returned verbatim under `result`.
#[plugin_fn]
pub fn emit(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    let mut event = json!({
        "type": query(&req, "type").unwrap_or_else(|| "fixture:guest:thing:happened".to_string()),
        "subject": "thing-1",
        "data": {"n": 1, "from": "wasm"},
        "messageGroup": "group-1",
    });
    if let Some(dedup) = query(&req, "dedupId") {
        event["dedupId"] = Value::String(dedup);
    }
    let answer = unsafe { fc_emit_event(event.to_string())? };
    let parsed: Value = serde_json::from_str(&answer).unwrap_or(Value::String(answer));
    reply(calls, 200, json!({"result": parsed}))
}

/// Extism `log_info` / `log_warn`, and WASI stdout / stderr.
#[plugin_fn]
pub fn log(input: String) -> FnResult<String> {
    let calls = enter();
    let req = request(&input);
    let msg = query(&req, "msg").unwrap_or_else(|| "hello".to_string());
    info!("guest info: {}", msg);
    warn!("guest warn: {}", msg);
    write_fd(1, &format!("guest stdout: {}\n", msg));
    write_fd(2, &format!("guest stderr: {}\n", msg));
    reply(calls, 200, json!({"logged": msg}))
}
