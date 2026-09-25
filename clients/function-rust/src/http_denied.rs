use std::fmt;

/// Returned by [`crate::context::Http::request`] when the host refuses to
/// make a call — the target host is not on the version's `manifest.httpAllow`
/// list, the scheme is not `https` (and the host is not loopback), or the
/// call could not be reached. The Rust mirror of `function-api`'s
/// `HttpCallRefusedException` and the JS library's `HttpDenied` — the host
/// itself never traps a Wasm instance for this
/// (`docs/spec/function-wasm-runtime.md` §4: "a denied host is a
/// guest-visible error, not a trap"); this type turns the Extism `status: 0`
/// signal into something a handler matches on by type instead of remembering
/// to check a magic status code.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpDenied {
    pub reason: String,
}

impl HttpDenied {
    pub fn new(reason: impl Into<String>) -> Self {
        HttpDenied { reason: reason.into() }
    }
}

impl fmt::Display for HttpDenied {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "outbound call refused: {}", self.reason)
    }
}

impl std::error::Error for HttpDenied {}
