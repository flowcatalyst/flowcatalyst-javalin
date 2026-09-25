//! What a handler answers with — the Rust mirror of the JS library's
//! `result.ts` (`docs/js library's [`Result`] object, and Java's
//! `function-api` `Result` record's static factories). Built only through the
//! functions below.
//!
//! **Deviation from JS/Java, forced by the platform:** JS's builders `throw`
//! on invalid input, caught by its `handler()` and turned into a controlled
//! `fail(reason)` — the guest instance survives. Java's builders throw
//! `IllegalArgumentException`, caught per call by the JVM loader — the
//! instance survives there too. On `wasm32-unknown-unknown`, a Rust panic
//! does neither: it traps the whole instance regardless of the crate's
//! `panic` profile (`unwind` compiles but does not actually unwind on this
//! target — verified empirically against this toolchain; `catch_unwind`
//! cannot intercept it). Panicking here would be strictly worse than Java/JS,
//! discarding the instance for what should be a one-call mistake. So these
//! builders return `Result<FunctionResult, ResultError>` instead of
//! panicking/throwing — composable with `?` inside a
//! [`crate::handler`]-wrapped closure, which folds any `Err` into the same
//! fixed-reason failure response the JS/Java throw path reaches.

use serde::Serialize;
use std::collections::BTreeMap;
use std::fmt;

/// The wire shape (`docs/spec/function-wasm-runtime.md` §3's guest output
/// JSON).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FunctionResult {
    pub status: u16,
    pub headers: BTreeMap<String, Vec<String>>,
    pub body: Vec<u8>,
}

/// Why a [`FunctionResult`] builder refused its input — see the module doc
/// for why this is a `Result`, not a panic.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResultError {
    InvalidStatus(u16),
    BlankReason,
    InvalidRetrySeconds(String),
    Serialize(String),
}

impl fmt::Display for ResultError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ResultError::InvalidStatus(s) => write!(f, "status must be an integer 100-599, was {s}"),
            ResultError::BlankReason => write!(f, "reason must not be blank"),
            ResultError::InvalidRetrySeconds(s) => write!(f, "seconds must be a non-negative finite number, was {s}"),
            ResultError::Serialize(e) => write!(f, "could not serialise the value as JSON: {e}"),
        }
    }
}

impl std::error::Error for ResultError {}

fn check_status(status: u16) -> Result<u16, ResultError> {
    if !(100..=599).contains(&status) {
        return Err(ResultError::InvalidStatus(status));
    }
    Ok(status)
}

fn content_type(value: &str) -> BTreeMap<String, Vec<String>> {
    BTreeMap::from([("Content-Type".to_string(), vec![value.to_string()])])
}

impl FunctionResult {
    /// The invocation succeeded; nothing further to say. `200`, empty body.
    pub fn ok() -> Self {
        FunctionResult { status: 200, headers: BTreeMap::new(), body: Vec::new() }
    }

    /// A direct HTTP answer whose body is `value` serialised as JSON, with
    /// `Content-Type: application/json`.
    pub fn json<T: Serialize + ?Sized>(status: u16, value: &T) -> Result<Self, ResultError> {
        let status = check_status(status)?;
        let body = serde_json::to_vec(value).map_err(|e| ResultError::Serialize(e.to_string()))?;
        Ok(FunctionResult { status, headers: content_type("application/json"), body })
    }

    /// A direct HTTP answer whose body is `value` verbatim, with
    /// `Content-Type: text/plain; charset=utf-8`.
    pub fn text(status: u16, value: impl Into<String>) -> Result<Self, ResultError> {
        let status = check_status(status)?;
        Ok(FunctionResult { status, headers: content_type("text/plain; charset=utf-8"), body: value.into().into_bytes() })
    }

    /// A direct HTTP answer, verbatim.
    pub fn status(
        status: u16,
        headers: BTreeMap<String, Vec<String>>,
        body: impl Into<Vec<u8>>,
    ) -> Result<Self, ResultError> {
        let status = check_status(status)?;
        Ok(FunctionResult { status, headers, body: body.into() })
    }

    /// The invocation failed. `reason` becomes the audit/metrics detail,
    /// carried in a `{"error": "<reason>"}` body. `500`.
    pub fn fail(reason: impl Into<String>) -> Result<Self, ResultError> {
        let reason = reason.into();
        if reason.trim().is_empty() {
            return Err(ResultError::BlankReason);
        }
        let body = serde_json::to_vec(&serde_json::json!({ "error": reason }))
            .map_err(|e| ResultError::Serialize(e.to_string()))?;
        Ok(FunctionResult { status: 500, headers: content_type("application/json"), body })
    }

    /// The invocation should be redelivered after `seconds`, rounded up to a
    /// whole second — same delivery-path caveats as Java's `function-api`
    /// `Result#retry` doc: honoured by a subscription/direct dispatch job,
    /// advisory only for a scheduled job.
    pub fn retry(seconds: f64) -> Result<Self, ResultError> {
        if !seconds.is_finite() || seconds < 0.0 {
            return Err(ResultError::InvalidRetrySeconds(seconds.to_string()));
        }
        let ceil = seconds.ceil() as i64;
        Ok(FunctionResult {
            status: 429,
            headers: BTreeMap::from([("Retry-After".to_string(), vec![ceil.to_string()])]),
            body: Vec::new(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ok_is_200_with_an_empty_body() {
        let r = FunctionResult::ok();
        assert_eq!(r.status, 200);
        assert!(r.body.is_empty());
    }

    #[test]
    fn json_serialises_the_value_and_sets_content_type() {
        let r = FunctionResult::json(201, &serde_json::json!({"hello": "world"})).unwrap();
        assert_eq!(r.status, 201);
        assert_eq!(r.headers.get("Content-Type").unwrap(), &vec!["application/json".to_string()]);
        let parsed: serde_json::Value = serde_json::from_slice(&r.body).unwrap();
        assert_eq!(parsed, serde_json::json!({"hello": "world"}));
    }

    #[test]
    fn json_rejects_an_out_of_range_status() {
        assert!(FunctionResult::json(99, &serde_json::json!({})).is_err());
        assert!(FunctionResult::json(600, &serde_json::json!({})).is_err());
    }

    #[test]
    fn text_carries_the_text_verbatim() {
        let r = FunctionResult::text(200, "hello").unwrap();
        assert_eq!(r.body, b"hello");
        assert_eq!(r.headers.get("Content-Type").unwrap(), &vec!["text/plain; charset=utf-8".to_string()]);
    }

    #[test]
    fn status_is_a_direct_verbatim_answer() {
        let r = FunctionResult::status(
            418,
            BTreeMap::from([("X-Teapot".to_string(), vec!["yes".to_string()])]),
            "short and stout",
        )
        .unwrap();
        assert_eq!(r.status, 418);
        assert_eq!(r.headers.get("X-Teapot").unwrap(), &vec!["yes".to_string()]);
        assert_eq!(r.body, b"short and stout");
    }

    #[test]
    fn status_defaults_headers_and_body_when_omitted() {
        let r = FunctionResult::status(204, BTreeMap::new(), Vec::<u8>::new()).unwrap();
        assert!(r.headers.is_empty());
        assert!(r.body.is_empty());
    }

    #[test]
    fn fail_is_500_with_error_reason() {
        let r = FunctionResult::fail("boom").unwrap();
        assert_eq!(r.status, 500);
        let parsed: serde_json::Value = serde_json::from_slice(&r.body).unwrap();
        assert_eq!(parsed, serde_json::json!({"error": "boom"}));
    }

    // mutant: drop the blank check — Java's Result#fail throws IllegalArgumentException and the
    // JS builder throws for the same input; this must refuse it identically, not silently accept "".
    #[test]
    fn fail_rejects_a_blank_reason() {
        assert!(FunctionResult::fail("").is_err());
        assert!(FunctionResult::fail("   ").is_err());
    }

    #[test]
    fn retry_is_429_with_a_retry_after_header() {
        let r = FunctionResult::retry(5.0).unwrap();
        assert_eq!(r.status, 429);
        assert_eq!(r.headers.get("Retry-After").unwrap(), &vec!["5".to_string()]);
    }

    // mutant: floor instead of ceil — a sub-second remainder must round UP, never down, same as
    // Java's Result#retry doc (asking for less delay than requested is wrong).
    #[test]
    fn retry_rounds_a_sub_second_remainder_up_to_a_whole_second() {
        assert_eq!(FunctionResult::retry(0.1).unwrap().headers["Retry-After"], vec!["1".to_string()]);
        assert_eq!(FunctionResult::retry(5.0).unwrap().headers["Retry-After"], vec!["5".to_string()]);
    }

    #[test]
    fn retry_allows_a_zero_delay() {
        assert_eq!(FunctionResult::retry(0.0).unwrap().headers["Retry-After"], vec!["0".to_string()]);
    }

    #[test]
    fn retry_rejects_a_negative_delay() {
        assert!(FunctionResult::retry(-1.0).is_err());
    }

    #[test]
    fn retry_rejects_a_non_finite_delay() {
        assert!(FunctionResult::retry(f64::NAN).is_err());
        assert!(FunctionResult::retry(f64::INFINITY).is_err());
    }
}
