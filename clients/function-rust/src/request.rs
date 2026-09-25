//! One invocation (`docs/spec/function-wasm-runtime.md` §3's request JSON,
//! decoded) — the Rust mirror of the JS library's `request.ts`.
//! `path_params`/`query`/`headers` are read-only; [`FunctionRequest::body`]/
//! [`FunctionRequest::text`]/[`FunctionRequest::json`] read the base64-encoded
//! wire body.

use crate::base64;
use crate::caller::{parse_caller, Caller};
use serde::de::DeserializeOwned;
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct FunctionRequest {
    pub address: String,
    pub version: i64,
    pub invocation_id: String,
    pub method: String,
    pub path: String,
    pub original_host: Option<String>,
    pub original_path: Option<String>,
    pub path_params: HashMap<String, String>,
    pub query: HashMap<String, Vec<String>>,
    pub headers: HashMap<String, Vec<String>>,
    pub remote_address: Option<String>,
    pub caller: Caller,
    body: Vec<u8>,
}

impl FunctionRequest {
    /// The raw request body.
    pub fn body(&self) -> &[u8] {
        &self.body
    }

    /// The body decoded as UTF-8 text (lossy — invalid sequences become the
    /// Unicode replacement character, the same non-fatal decoding
    /// `TextDecoder("utf-8")` gives the JS library by default).
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    /// The body parsed as JSON.
    pub fn json<T: DeserializeOwned>(&self) -> Result<T, serde_json::Error> {
        serde_json::from_slice(&self.body)
    }

    /// The first value of the header named `name`, matched
    /// case-insensitively.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.header_values(name).first().map(String::as_str)
    }

    /// Every value of the header named `name` from the first
    /// case-insensitively matching key — `&[]` when there is none.
    pub fn header_values(&self, name: &str) -> &[String] {
        let lower = name.to_ascii_lowercase();
        for (key, values) in &self.headers {
            if key.to_ascii_lowercase() == lower {
                return values;
            }
        }
        &[]
    }
}

fn required_str(obj: &serde_json::Map<String, Value>, key: &str) -> Result<String, String> {
    obj.get(key)
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| format!("request field '{key}' must be a string"))
}

fn required_i64(obj: &serde_json::Map<String, Value>, key: &str) -> Result<i64, String> {
    obj.get(key).and_then(Value::as_i64).ok_or_else(|| format!("request field '{key}' must be a number"))
}

fn optional_str(obj: &serde_json::Map<String, Value>, key: &str) -> Option<String> {
    obj.get(key).and_then(Value::as_str).map(str::to_string)
}

fn string_map(obj: &serde_json::Map<String, Value>, key: &str) -> HashMap<String, String> {
    obj.get(key)
        .and_then(Value::as_object)
        .map(|m| {
            m.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect()
        })
        .unwrap_or_default()
}

fn multi_map(obj: &serde_json::Map<String, Value>, key: &str) -> HashMap<String, Vec<String>> {
    obj.get(key)
        .and_then(Value::as_object)
        .map(|m| {
            m.iter()
                .map(|(k, v)| {
                    let values = v
                        .as_array()
                        .map(|a| a.iter().map(value_to_string).collect())
                        .unwrap_or_default();
                    (k.clone(), values)
                })
                .collect()
        })
        .unwrap_or_default()
}

fn value_to_string(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// Parses the ABI's request JSON (`docs/spec/function-wasm-runtime.md` §3)
/// into a [`FunctionRequest`].
pub fn parse_request(value: &Value) -> Result<FunctionRequest, String> {
    let obj = value.as_object().ok_or_else(|| "request body must be a JSON object".to_string())?;
    let body_base64 = obj.get("bodyBase64").and_then(Value::as_str).unwrap_or("");
    let body = base64::decode(body_base64)?;
    let caller = parse_caller(obj.get("caller").unwrap_or(&Value::Null))?;
    Ok(FunctionRequest {
        address: required_str(obj, "address")?,
        version: required_i64(obj, "version")?,
        invocation_id: required_str(obj, "invocationId")?,
        method: required_str(obj, "method")?,
        path: required_str(obj, "path")?,
        original_host: optional_str(obj, "originalHost"),
        original_path: optional_str(obj, "originalPath"),
        path_params: string_map(obj, "pathParams"),
        query: multi_map(obj, "query"),
        headers: multi_map(obj, "headers"),
        remote_address: optional_str(obj, "remoteAddress"),
        caller,
        body,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn echo_fixture() -> Value {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../function-js/test/fixtures/echo-request.json");
        let raw = fs::read_to_string(path).unwrap_or_else(|e| panic!("reading {path}: {e}"));
        serde_json::from_str(&raw).unwrap()
    }

    #[test]
    fn carries_every_scalar_field_intact() {
        let req = parse_request(&echo_fixture()).unwrap();
        assert_eq!(req.address, "fnc_a");
        assert_eq!(req.version, 1);
        assert_eq!(req.invocation_id, "inv-1");
        assert_eq!(req.method, "POST");
        assert_eq!(req.path, "/echo/42");
        assert_eq!(req.original_host.as_deref(), Some("127.0.0.1:8080"));
        assert_eq!(req.original_path.as_deref(), Some("/functions/fnc_a/echo/42"));
        assert_eq!(req.remote_address.as_deref(), Some("127.0.0.1"));
    }

    // mutant: drop pathParams from parsing — the exact regression named in
    // function-wasm-runtime.md §6 test 1's own mutant note.
    #[test]
    fn carries_path_params() {
        let req = parse_request(&echo_fixture()).unwrap();
        assert_eq!(req.path_params.get("id").map(String::as_str), Some("42"));
    }

    #[test]
    fn carries_multi_valued_query_params() {
        let req = parse_request(&echo_fixture()).unwrap();
        assert_eq!(req.query.get("y").unwrap(), &vec!["hello world".to_string(), "again".to_string()]);
    }

    #[test]
    fn decodes_the_utf8_body_from_body_base64() {
        let req = parse_request(&echo_fixture()).unwrap();
        assert_eq!(req.text(), "héllo body");
        assert_eq!(req.body(), "héllo body".as_bytes());
    }

    #[test]
    fn parses_an_anonymous_caller() {
        let req = parse_request(&echo_fixture()).unwrap();
        assert_eq!(req.caller, Caller::Anonymous);
    }

    #[test]
    fn looks_headers_up_case_insensitively() {
        let req = parse_request(&echo_fixture()).unwrap();
        assert_eq!(req.header("x-test-custom"), Some("hi"));
        assert_eq!(req.header("X-TEST-CUSTOM"), Some("hi"));
        assert_eq!(req.header_values("X-Test-Custom"), &["hi".to_string()]);
        assert_eq!(req.header("missing"), None);
        assert!(req.header_values("missing").is_empty());
    }

    #[test]
    fn parses_a_json_body() {
        let mut fixture = echo_fixture();
        fixture["bodyBase64"] = Value::String(crate::base64::encode(br#"{"a":1}"#));
        let req = parse_request(&fixture).unwrap();
        #[derive(serde::Deserialize)]
        struct Body {
            a: i64,
        }
        let body: Body = req.json().unwrap();
        assert_eq!(body.a, 1);
    }

    #[test]
    fn defaults_an_absent_body_to_empty() {
        let mut fixture = echo_fixture();
        fixture.as_object_mut().unwrap().remove("bodyBase64");
        let req = parse_request(&fixture).unwrap();
        assert!(req.body().is_empty());
        assert_eq!(req.text(), "");
    }

    #[test]
    fn rejects_a_non_object_request() {
        assert!(parse_request(&Value::String("not an object".to_string())).is_err());
        assert!(parse_request(&Value::Null).is_err());
    }

    #[test]
    fn rejects_a_request_missing_a_required_field() {
        let mut fixture = echo_fixture();
        fixture.as_object_mut().unwrap().remove("method");
        let err = parse_request(&fixture).unwrap_err();
        assert!(err.contains("method"), "{err}");
    }
}
