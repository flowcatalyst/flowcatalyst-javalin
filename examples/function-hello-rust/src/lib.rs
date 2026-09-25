//! The Rust twin of `examples/function-hello` (Java) and `examples/function-hello-js` — the
//! reference every Rust function author reads first (`docs/spec/function-rust-guest.md` §2,
//! `docs/functions.md`). Same shape as both:
//!
//! - a **`webhook`** endpoint (`/events/greeting-requested`) — parses the platform's own delivery
//!   envelope, reads a config value and checks a secret's PRESENCE without ever logging its
//!   VALUE, emits an event this function's own application owns, and acks.
//! - a **`platform`** endpoint (`GET /api/hello/{name}`) — checks the caller's own
//!   `hasPermission` for the `hello` application's `hello:greeting:greet` permission BEFORE doing
//!   anything else.
//! - a **`none`** endpoint (`GET /healthz`) — the host checks nothing.
//! - a **`none`** endpoint (`GET /api/proxy?url=`) — beyond the Java example: demonstrates
//!   `ctx.http.request` and its [`HttpDenied`] on a host outside `manifest.httpAllow` (the host
//!   integration test's allowlist-denial mutant, `docs/spec/function-rust-guest.md` §4).

use extism_pdk::plugin_fn;
use flowcatalyst_function::{
    handler, Caller, Context, FunctionRequest, FunctionResult, HttpDenied, HttpRequestInit, OutboundEvent,
};
use serde::Deserialize;
use serde_json::json;

const EVENT_TYPE: &str = "hello:greeting:greeting:sent";
/// The `hello` application's own permission — not one of the platform's
/// `platform:*:*:*` codes, an application-defined one a caller's token
/// carries the same way.
const GREET_PERMISSION: &str = "hello:greeting:greet";

#[derive(Deserialize)]
struct DeliveryEnvelope {
    id: String,
    subject: Option<String>,
    #[serde(rename = "correlationId")]
    correlation_id: Option<String>,
    #[serde(rename = "messageGroup")]
    message_group: Option<String>,
    data: Option<DeliveryData>,
}

#[derive(Deserialize)]
struct DeliveryData {
    name: Option<String>,
}

#[plugin_fn]
pub fn handle(_input: String) -> extism_pdk::FnResult<String> {
    handler(|req, ctx| -> Result<FunctionResult, String> {
        if req.path == "/healthz" {
            return handle_health();
        }
        if req.path == "/events/greeting-requested" {
            return handle_greeting_requested(req, ctx);
        }
        if req.path.starts_with("/api/hello/") {
            return handle_hello(req, ctx);
        }
        if req.path == "/api/proxy" {
            return handle_proxy(req, ctx);
        }
        // Unreachable in production: the host never delivers a path the manifest does not
        // declare an endpoint for.
        Err(format!("no route for path {}", req.path))
    })
}

fn handle_health() -> Result<FunctionResult, String> {
    FunctionResult::json(200, &json!({"status": "ok"})).map_err(|e| e.to_string())
}

/// `auth: platform` guarantees `req.caller` is [`Caller::Principal`] once this arrives through
/// the real host; the check below still fails closed for anything else rather than assuming it.
/// `has_permission` is checked BEFORE anything else runs, so a caller without it never reaches
/// the response-building code below.
fn handle_hello(req: &FunctionRequest, ctx: &Context) -> Result<FunctionResult, String> {
    let principal = match &req.caller {
        Caller::Principal(p) if p.has_permission(Some(GREET_PERMISSION)) => p,
        _ => return FunctionResult::json(403, &json!({"error": "PERMISSION_REQUIRED"})).map_err(|e| e.to_string()),
    };
    // Observable-from-outside marker that the handler body itself ran, for a test to assert
    // absence of on the permission-denied path.
    ctx.logger.info(&format!("hello handled (principal={})", principal.id));
    let name = req.path_params.get("name").map(String::as_str).unwrap_or("world");
    FunctionResult::json(200, &json!({"message": format!("hello, {name}!"), "principalId": principal.id}))
        .map_err(|e| e.to_string())
}

fn handle_greeting_requested(req: &FunctionRequest, ctx: &Context) -> Result<FunctionResult, String> {
    let event: DeliveryEnvelope = req.json().map_err(|e| e.to_string())?;

    let greeting = ctx.config.get("GREETING").unwrap_or_else(|| "Hello".to_string());
    // Presence only — the value itself is never read into a log line, a response body or the
    // emitted event.
    let api_key_present = ctx.secrets.get("API_KEY").is_some();
    ctx.logger
        .info(&format!("greeting requested (subject={:?}, apiKeyPresent={})", event.subject, api_key_present));

    let name = event.data.as_ref().and_then(|d| d.name.clone()).unwrap_or_else(|| "world".to_string());

    // `req.invocation_id` is already unique per call (the host's own guarantee), so it doubles
    // as a dedup id here rather than reaching for a randomness source this crate does not depend
    // on (the JS twin uses `Date.now()` + `Math.random()` for the same purpose).
    let outcome = ctx.events.emit(OutboundEvent {
        event_type: EVENT_TYPE.to_string(),
        source: Some(format!("function:{}", req.address)),
        subject: event.subject.clone(),
        data_content_type: Some("application/json".to_string()),
        data: Some(json!({"name": name, "greeting": format!("{greeting}, {name}!")})),
        correlation_id: event.correlation_id.clone(),
        causation_id: Some(event.id.clone()),
        message_group: event.message_group.clone(),
        dedup_id: format!("greeting-{}", req.invocation_id),
    });
    match outcome {
        Ok(()) => Ok(FunctionResult::ok()),
        // A genuine rejection (e.g. EVENT_TYPE_NOT_OWNED) — the host's own `fc_emit_event`
        // answer carries no HTTP-style status to distinguish "worth retrying" from "never will
        // be" (`docs/spec/function-wasm-runtime.md` §4: `{"ok":false,"error":"<code>"}`, nothing
        // else), so unlike an `http.request` failure this is always reported, never retried.
        Err(e) => FunctionResult::fail(format!("event emit refused: {}", e.code)).map_err(|err| err.to_string()),
    }
}

/// Not in the Java example — exercises `ctx.http.request` for the guest library's own sake, and
/// gives the host integration test's allowlist denial something to call against the committed
/// module itself (`docs/spec/function-rust-guest.md` §4).
fn handle_proxy(req: &FunctionRequest, ctx: &Context) -> Result<FunctionResult, String> {
    let url = match req.query.get("url").and_then(|values| values.first()) {
        Some(url) => url.clone(),
        None => {
            return FunctionResult::json(400, &json!({"error": "missing 'url' query parameter"}))
                .map_err(|e| e.to_string())
        }
    };
    match ctx.http.request(HttpRequestInit { method: Some("GET".to_string()), url, ..Default::default() }) {
        Ok(reply) => FunctionResult::json(200, &json!({"denied": false, "status": reply.status, "body": reply.body}))
            .map_err(|e| e.to_string()),
        Err(HttpDenied { reason }) => {
            FunctionResult::json(200, &json!({"denied": true, "reason": reason})).map_err(|e| e.to_string())
        }
    }
}
