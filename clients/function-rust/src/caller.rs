//! Who a [`FunctionRequest`](crate::FunctionRequest) came from — the Rust mirror of
//! `function-api`'s `Caller` sealed interface and the JS library's `caller.ts`
//! (`docs/spec/function-rust-guest.md` §1). One enum a `match` narrows on,
//! exactly the three cases the host can ever hand a function
//! (`docs/spec/function-invocation.md` §7).

use serde_json::Value;

/// `auth: "platform"` — an authenticated platform principal, verified against
/// the platform's JWKS before this call ever reached the handler. Every field
/// comes straight off the verified token; `email`/`name` are deliberately NOT
/// carried, same reasoning as Java's `Caller.Principal` and the JS library's
/// `PrincipalCaller`.
///
/// The methods below restate the platform's own authorisation rules exactly,
/// same semantics as `Caller.Principal` in `function-api` (Java) and
/// `PrincipalCaller` in `@flowcatalyst/function` (JS) — tested against the
/// same case table, read directly from
/// `clients/function-js/test/fixtures/caller-cases.json` (never copied), so
/// the JS and Rust implementations of the wildcard-match rule can never
/// quietly drift apart.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrincipalCaller {
    pub id: String,
    /// The principal's kind (e.g. `user`, `service-account`). Named `kind_`
    /// rather than `type` — a Rust keyword — the wire field is still `type`
    /// (see [`parse_caller`]).
    pub principal_type: String,
    pub tier: Option<String>,
    pub clients: Vec<String>,
    pub roles: Vec<String>,
    pub applications: Vec<String>,
    pub all_applications: bool,
    pub permissions: Vec<String>,
}

impl PrincipalCaller {
    /// Whether a held permission satisfies `required` — exact match, or a
    /// held code with the same segment count whose non-wildcard (`*`)
    /// segments equal `required`'s. `None` is never satisfied.
    pub fn has_permission(&self, required: Option<&str>) -> bool {
        match required {
            None => false,
            Some(required) => self.permissions.iter().any(|held| segments_match(held, required)),
        }
    }

    /// Any of `required` is held.
    pub fn has_any_permission(&self, required: &[&str]) -> bool {
        required.iter().any(|r| self.has_permission(Some(r)))
    }

    /// Every one of `required` is held.
    pub fn has_all_permissions(&self, required: &[&str]) -> bool {
        required.iter().all(|r| self.has_permission(Some(r)))
    }

    /// `roles` carries role codes verbatim (no matching rule needed).
    pub fn has_role(&self, code: &str) -> bool {
        self.roles.iter().any(|r| r == code)
    }

    /// `tier == Some("ANCHOR")`.
    pub fn is_anchor(&self) -> bool {
        self.tier.as_deref() == Some("ANCHOR")
    }

    /// An anchor always; otherwise `client_id` must be in `clients`.
    pub fn can_access_client(&self, client_id: &str) -> bool {
        self.is_anchor() || self.clients.iter().any(|c| c == client_id)
    }

    /// [`Self::all_applications`], or `application_id` is in `applications`.
    pub fn can_access_application(&self, application_id: &str) -> bool {
        self.all_applications || self.applications.iter().any(|a| a == application_id)
    }

    /// The one client this principal is scoped to, when unambiguous: exactly
    /// one entry in `clients` that is not the anchor wildcard `*`. `None` for
    /// zero, two-or-more, or a lone `*` entry.
    pub fn client_id(&self) -> Option<&str> {
        match self.clients.as_slice() {
            [one] if one != "*" => Some(one.as_str()),
            _ => None,
        }
    }
}

/// `io.flowcatalyst.function.Caller.Principal#matches` /
/// `@flowcatalyst/function`'s `segmentsMatch`, copied verbatim — equal
/// strings, or same segment count with every held segment either `*` or
/// equal to the required one.
fn segments_match(held: &str, required: &str) -> bool {
    if held == required {
        return true;
    }
    let h: Vec<&str> = held.split(':').collect();
    let r: Vec<&str> = required.split(':').collect();
    if h.len() != r.len() {
        return false;
    }
    h.iter().zip(r.iter()).all(|(hs, rs)| *hs == "*" || hs == rs)
}

/// Who a [`FunctionRequest`](crate::FunctionRequest) came from
/// (`docs/spec/function-invocation.md` §7).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Caller {
    /// `auth: "webhook"` — a verified delivery: subscription, direct dispatch
    /// job or scheduled job.
    Platform,
    /// `auth: "none"` — the host checked nothing; the function authenticates
    /// itself, if at all.
    Anonymous,
    /// `auth: "platform"`.
    Principal(PrincipalCaller),
}

/// Parses the ABI's `caller` object (`docs/spec/function-wasm-runtime.md` §3)
/// into a [`Caller`].
pub fn parse_caller(value: &Value) -> Result<Caller, String> {
    let obj = value.as_object().ok_or_else(|| format!("caller must be an object, got {value}"))?;
    match obj.get("kind").and_then(Value::as_str) {
        Some("platform") => Ok(Caller::Platform),
        Some("anonymous") => Ok(Caller::Anonymous),
        Some("principal") => {
            let id = required_string(obj, "id")?;
            let principal_type = required_string(obj, "type")?;
            let tier = obj.get("tier").and_then(Value::as_str).map(str::to_string);
            let clients = string_array(obj, "clients");
            let roles = string_array(obj, "roles");
            let applications = string_array(obj, "applications");
            let all_applications = obj.get("allApplications").and_then(Value::as_bool).unwrap_or(false);
            let permissions = string_array(obj, "permissions");
            Ok(Caller::Principal(PrincipalCaller {
                id,
                principal_type,
                tier,
                clients,
                roles,
                applications,
                all_applications,
                permissions,
            }))
        }
        other => Err(format!("unknown caller kind: {:?}", other.map(str::to_string))),
    }
}

fn required_string(obj: &serde_json::Map<String, Value>, key: &str) -> Result<String, String> {
    obj.get(key)
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| format!("a principal caller requires '{key}'"))
}

fn string_array(obj: &serde_json::Map<String, Value>, key: &str) -> Vec<String> {
    obj.get(key)
        .and_then(Value::as_array)
        .map(|values| values.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::fs;

    fn principal(overrides: Value) -> PrincipalCaller {
        let mut base = json!({
            "kind": "principal",
            "id": "id",
            "type": "user",
            "tier": null,
            "clients": [],
            "roles": [],
            "applications": [],
            "allApplications": false,
            "permissions": [],
        });
        merge(&mut base, &overrides);
        match parse_caller(&base).unwrap() {
            Caller::Principal(p) => p,
            other => panic!("expected a principal caller, got {other:?}"),
        }
    }

    fn merge(base: &mut Value, overrides: &Value) {
        if let (Some(base_obj), Some(over_obj)) = (base.as_object_mut(), overrides.as_object()) {
            for (k, v) in over_obj {
                base_obj.insert(k.clone(), v.clone());
            }
        }
    }

    #[test]
    fn parses_the_platform_and_anonymous_callers() {
        assert_eq!(parse_caller(&json!({"kind": "platform"})).unwrap(), Caller::Platform);
        assert_eq!(parse_caller(&json!({"kind": "anonymous"})).unwrap(), Caller::Anonymous);
    }

    #[test]
    fn rejects_an_unknown_kind() {
        let err = parse_caller(&json!({"kind": "bogus"})).unwrap_err();
        assert!(err.contains("unknown caller kind"), "{err}");
    }

    #[test]
    fn parses_every_field_of_a_principal_caller() {
        let p = principal(json!({
            "id": "user-1", "type": "user", "tier": "CLIENT", "clients": ["clt_1"],
            "roles": ["admin"], "applications": ["app_1"], "allApplications": false,
            "permissions": ["a:b:c:read"],
        }));
        assert_eq!(p.id, "user-1");
        assert_eq!(p.principal_type, "user");
        assert_eq!(p.tier.as_deref(), Some("CLIENT"));
        assert_eq!(p.clients, vec!["clt_1"]);
        assert_eq!(p.roles, vec!["admin"]);
        assert_eq!(p.applications, vec!["app_1"]);
        assert_eq!(p.permissions, vec!["a:b:c:read"]);
    }

    // The exact case table function-api's CallerTest (Java) and the JS library's caller.test.ts
    // use for Principal#hasPermission, read from the JS package's own fixture file so the three
    // implementations of the wildcard-match rule are pinned against the same cases and can never
    // quietly drift apart.
    #[test]
    fn has_permission_matches_the_shared_case_table() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../function-js/test/fixtures/caller-cases.json");
        let raw = fs::read_to_string(path).unwrap_or_else(|e| panic!("reading {path}: {e}"));
        let doc: Value = serde_json::from_str(&raw).unwrap();
        let cases = doc["hasPermission"].as_array().expect("hasPermission array");
        assert!(!cases.is_empty(), "the shared case table must not be empty");
        for case in cases {
            let held = case["held"].as_str().unwrap();
            let required = case["required"].as_str().unwrap();
            let expected = case["expected"].as_bool().unwrap();
            let p = principal(json!({"permissions": [held]}));
            assert_eq!(
                p.has_permission(Some(required)),
                expected,
                "held={held} required={required} expected={expected}"
            );
        }
    }

    // mutant: drop the None guard — a permission check must fail closed on a missing requirement.
    #[test]
    fn has_permission_is_false_for_a_missing_requirement_even_with_a_wide_open_permission() {
        let p = principal(json!({"permissions": ["*:*:*:*"]}));
        assert!(!p.has_permission(None));
    }

    #[test]
    fn has_any_and_all_permissions() {
        let p = principal(json!({"permissions": ["a:b:c:read", "a:b:c:write"]}));
        assert!(p.has_any_permission(&["a:b:c:write", "a:b:c:delete"]));
        assert!(!p.has_any_permission(&["a:b:c:other", "a:b:c:delete"]));
        assert!(p.has_all_permissions(&["a:b:c:read", "a:b:c:write"]));
        assert!(!p.has_all_permissions(&["a:b:c:read", "a:b:c:delete"]));
    }

    // mutant: drop the is_anchor() short-circuit — an anchor with an empty clients list would
    // then be refused access to every client, which is wrong.
    #[test]
    fn can_access_client_is_true_for_an_anchor_regardless_of_its_own_clients_list() {
        let anchor = principal(json!({"tier": "ANCHOR", "clients": []}));
        assert!(anchor.can_access_client("anything"));
    }

    #[test]
    fn can_access_client_checks_the_list_for_non_anchors() {
        let p = principal(json!({"tier": "CLIENT", "clients": ["clt_1"]}));
        assert!(p.can_access_client("clt_1"));
        assert!(!p.can_access_client("clt_2"));
    }

    #[test]
    fn can_access_application_is_true_when_all_applications() {
        let p = principal(json!({"allApplications": true, "applications": []}));
        assert!(p.can_access_application("anything"));
    }

    #[test]
    fn can_access_application_checks_the_list_otherwise() {
        let p = principal(json!({"allApplications": false, "applications": ["app_1"]}));
        assert!(p.can_access_application("app_1"));
        assert!(!p.can_access_application("app_2"));
    }

    #[test]
    fn has_role_checks_the_role_list_verbatim() {
        let p = principal(json!({"roles": ["admin"]}));
        assert!(p.has_role("admin"));
        assert!(!p.has_role("viewer"));
    }

    #[test]
    fn client_id_is_the_one_non_wildcard_client_else_none() {
        assert_eq!(principal(json!({"clients": []})).client_id(), None);
        assert_eq!(principal(json!({"clients": ["clt_1"]})).client_id(), Some("clt_1"));
        assert_eq!(principal(json!({"clients": ["clt_1", "clt_2"]})).client_id(), None);
        // mutant: skip the "*" check — an anchor's wildcard entry would wrongly report as a
        // client id.
        assert_eq!(principal(json!({"clients": ["*"]})).client_id(), None);
    }
}
