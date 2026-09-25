//! Everything a function may reach outside its own invocation — the Rust
//! mirror of `function-api`'s `FunctionContext` and the JS library's
//! `context.ts`. Built once per call by [`crate::handler::handler`].
//!
//! **Not covered by `cargo test`** (see `src/lib.rs`'s module doc): every
//! method here calls into an Extism host import that exists only inside a
//! real Wasm guest. The JS library can fake those (`globalThis.Config` etc.,
//! ordinary JS object monkey-patching); Rust cannot fake a linked
//! `extern "C"` symbol the same way, so this module's behaviour is proven by
//! the host integration test instead (`WasmFunctionHelloRustTest`, the twin
//! of `WasmFunctionHelloJsTest`), which runs the committed module through the
//! real Endive/Extism runtime.

use crate::db::Db;
use crate::http_denied::HttpDenied;
use extism_pdk::{host_fn, HttpRequest as ExtismHttpRequest};
use serde_json::Value;
use std::collections::BTreeMap;
use std::time::Duration;

#[host_fn("extism:host/user")]
extern "ExtismHost" {
    fn fc_secret_get(key: String) -> String;
    fn fc_emit_event(event: String) -> String;
}

/// Manifest-declared, non-secret config keys.
pub struct Config;

impl Config {
    /// The manifest-declared value, resolved by the host (Extism's
    /// `config_get`, built in — `docs/spec/function-wasm-runtime.md` §4).
    pub fn get(&self, key: &str) -> Option<String> {
        extism_pdk::config::get(key).ok().flatten()
    }

    /// @errors `Err` naming `key` if it was not declared.
    pub fn require(&self, key: &str) -> Result<String, String> {
        self.get(key).ok_or_else(|| format!("config key not declared: {key}"))
    }
}

/// Manifest-declared secret references. Never logged by an implementation of
/// this type.
pub struct Secrets;

impl Secrets {
    /// The manifest-declared secret's value, or `None` for an undeclared or
    /// unset key (the host answers offset `0` either way — `fc_secret_get`,
    /// `docs/spec/function-wasm-runtime.md` §4).
    pub fn get(&self, key: &str) -> Option<String> {
        let value = unsafe { fc_secret_get(key.to_string()) }.unwrap_or_default();
        if value.is_empty() {
            None
        } else {
            Some(value)
        }
    }

    /// @errors `Err` naming `key` if it was not declared.
    pub fn require(&self, key: &str) -> Result<String, String> {
        self.get(key).ok_or_else(|| format!("secret key not declared: {key}"))
    }
}

#[derive(Debug, Clone, Default)]
pub struct HttpRequestInit {
    pub method: Option<String>,
    pub url: String,
    pub headers: BTreeMap<String, String>,
    pub body: Option<Vec<u8>>,
}

impl HttpRequestInit {
    pub fn get(url: impl Into<String>) -> Self {
        HttpRequestInit { url: url.into(), ..Default::default() }
    }
}

/// The response to an [`HttpRequestInit`].
#[derive(Debug, Clone)]
pub struct HttpReply {
    pub status: u16,
    pub headers: BTreeMap<String, String>,
    pub body: String,
}

/// The host-mediated outbound HTTP client — never a direct network call, so
/// the host can apply the manifest's `httpAllow` list
/// (`docs/spec/function-wasm-runtime.md` §4). A denied or unreachable host
/// answers with `status: 0` from the Extism runtime itself; this wrapper
/// turns that into [`HttpDenied`] rather than a reply a caller must remember
/// to check.
pub struct Http;

impl Http {
    pub fn request(&self, init: HttpRequestInit) -> Result<HttpReply, HttpDenied> {
        let mut outbound = ExtismHttpRequest::new(init.url);
        if let Some(method) = init.method {
            outbound = outbound.with_method(method);
        }
        for (name, value) in init.headers {
            outbound = outbound.with_header(name, value);
        }
        let response = extism_pdk::http::request(&outbound, init.body).map_err(|e| HttpDenied::new(e.to_string()))?;
        let status = response.status_code();
        let body = String::from_utf8_lossy(&response.body()).into_owned();
        if status == 0 {
            let reason = extract_error(&body).unwrap_or(body);
            return Err(HttpDenied::new(reason));
        }
        let headers = response.headers().iter().map(|(k, v)| (k.clone(), v.clone())).collect();
        Ok(HttpReply { status, headers, body })
    }
}

fn extract_error(body: &str) -> Option<String> {
    let parsed: Value = serde_json::from_str(body).ok()?;
    parsed.get("error")?.as_str().map(str::to_string)
}

/// An event a function emits through [`Events::emit`]. Field-for-field the
/// Rust mirror of `function-api`'s `OutboundEvent` and the JS library's
/// `OutboundEvent` — `data` rides as an embedded JSON value, the
/// `fc_emit_event` host function's own contract
/// (`docs/spec/function-wasm-runtime.md` §4).
#[derive(Debug, Clone, Default)]
pub struct OutboundEvent {
    pub event_type: String,
    pub source: Option<String>,
    pub subject: Option<String>,
    pub data_content_type: Option<String>,
    pub data: Option<Value>,
    pub correlation_id: Option<String>,
    pub causation_id: Option<String>,
    pub message_group: Option<String>,
    pub dedup_id: String,
}

/// Raised by [`Events::emit`] when the platform refuses the event.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EventEmitException {
    pub code: String,
}

impl std::fmt::Display for EventEmitException {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "emit refused: {}", self.code)
    }
}

impl std::error::Error for EventEmitException {}

/// Emits platform events on a function's behalf, through the host — no
/// application credential is ever handed to the function.
pub struct Events;

impl Events {
    pub fn emit(&self, event: OutboundEvent) -> Result<(), EventEmitException> {
        let wire = serde_json::json!({
            "type": event.event_type,
            "source": event.source,
            "subject": event.subject,
            "dataContentType": event.data_content_type,
            "data": event.data,
            "correlationId": event.correlation_id,
            "causationId": event.causation_id,
            "messageGroup": event.message_group,
            "dedupId": event.dedup_id,
        });
        let answer = unsafe { fc_emit_event(wire.to_string()) }
            .map_err(|e| EventEmitException { code: format!("EMIT_FAILED: {e}") })?;
        let parsed: Value = serde_json::from_str(&answer)
            .map_err(|_| EventEmitException { code: "EMIT_FAILED".to_string() })?;
        if parsed.get("ok").and_then(Value::as_bool) == Some(true) {
            Ok(())
        } else {
            let code = parsed.get("error").and_then(Value::as_str).unwrap_or("EMIT_FAILED").to_string();
            Err(EventEmitException { code })
        }
    }
}

pub struct Logger;

impl Logger {
    pub fn debug(&self, message: &str) {
        extism_pdk::debug!("{}", message);
    }

    pub fn info(&self, message: &str) {
        extism_pdk::info!("{}", message);
    }

    pub fn warn(&self, message: &str) {
        extism_pdk::warn!("{}", message);
    }

    pub fn error(&self, message: &str) {
        extism_pdk::error!("{}", message);
    }
}

/// Everything a function may reach outside its own invocation.
pub struct Context {
    pub logger: Logger,
    pub config: Config,
    pub secrets: Secrets,
    pub http: Http,
    pub events: Events,
}

impl Context {
    pub(crate) fn build() -> Self {
        Context { logger: Logger, config: Config, secrets: Secrets, http: Http, events: Events }
    }

    /// The manifest-declared database named `name` (`docs/spec/function-wasm-db.md`).
    pub fn db(&self, name: impl Into<String>) -> Db {
        Db::new(name.into())
    }

    /// The host's clock — never read directly from a native clock in library
    /// code, so a test can fix time. WASI `clock_time_get`
    /// (`docs/spec/function-wasm-runtime.md` §4) on the guest target; on any
    /// other target (this crate's own `cargo test` host build) the native
    /// system clock, so callers outside a Wasm guest still get a real value.
    pub fn now(&self) -> Duration {
        now_impl()
    }
}

#[cfg(target_arch = "wasm32")]
fn now_impl() -> Duration {
    #[link(wasm_import_module = "wasi_snapshot_preview1")]
    extern "C" {
        fn clock_time_get(id: u32, precision: u64, time: *mut u64) -> u16;
    }
    const CLOCK_REALTIME: u32 = 0;
    let mut nanos: u64 = 0;
    unsafe {
        clock_time_get(CLOCK_REALTIME, 1, &mut nanos);
    }
    Duration::from_nanos(nanos)
}

#[cfg(not(target_arch = "wasm32"))]
fn now_impl() -> Duration {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or(Duration::ZERO)
}

#[cfg(test)]
mod tests {
    use super::*;

    // The one part of this module that's pure enough to pin here: now_impl's
    // host fallback actually advances (mutant: a fixed constant would pass
    // every OTHER test in this crate silently). The wasm32 branch is proven
    // by the host integration test, which runs the real committed module
    // through the real WASI clock and reports elapsed time (§ handler test).
    #[test]
    fn now_impl_reads_a_real_advancing_clock_on_the_host_target() {
        let a = now_impl();
        std::thread::sleep(Duration::from_millis(5));
        let b = now_impl();
        assert!(b > a, "clock did not advance: a={a:?} b={b:?}");
        // sane bounds: no one is running this test before 2020 or after 3000
        assert!(a.as_secs() > 1_577_836_800, "clock looks unset: {a:?}");
    }
}
