//! Database access for Wasm functions — `fc.db.*`
//! (`docs/spec/function-wasm-db.md`). `Context::db(name)` returns a [`Db`]
//! handle over the manifest's declared `db[]` entry named `name`;
//! [`Db::transaction`] borrows one connection for its whole closure, exactly
//! `docs/spec/function-rust-guest.md` §1's `db(name).query(sql, params)`
//! style. Like [`crate::context`], not covered by `cargo test` — every
//! function below calls an Extism host import that exists only inside a real
//! Wasm guest; proven by the host integration test instead.

use extism_pdk::host_fn;
use serde_json::Value;
use std::fmt;

#[host_fn("extism:host/user")]
extern "ExtismHost" {
    fn fc_db_query(input: String) -> String;
    fn fc_db_execute(input: String) -> String;
    fn fc_db_begin(input: String) -> String;
    fn fc_db_commit(input: String) -> String;
    fn fc_db_rollback(input: String) -> String;
}

/// A `params` value — JSON scalars bound positionally (`?`), never
/// interpolated: strings, numbers, booleans, null
/// (`docs/spec/function-wasm-db.md`).
pub type DbValue = Value;

/// `{"error": {"code","message"}}` from any `fc_db_*` call — the host's own
/// [DbFailure](../../../function-host/src/main/java/io/flowcatalyst/fnhost/wasm/DbFailure.java)
/// shape, carried through verbatim.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DbError {
    pub code: String,
    pub message: String,
}

impl fmt::Display for DbError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

impl std::error::Error for DbError {}

/// `fc_db_query`'s answer.
#[derive(Debug, Clone, PartialEq)]
pub struct DbRows {
    pub rows: Vec<serde_json::Map<String, Value>>,
    pub truncated: bool,
}

/// One manifest-declared database, scoped to this invocation.
pub struct Db {
    name: String,
}

impl Db {
    pub(crate) fn new(name: String) -> Self {
        Db { name }
    }

    /// `fc_db_query`, autocommit (no open transaction).
    pub fn query(&self, sql: &str, params: &[DbValue]) -> Result<DbRows, DbError> {
        call_query(&self.name, sql, params, None)
    }

    /// `fc_db_execute`, autocommit.
    pub fn execute(&self, sql: &str, params: &[DbValue]) -> Result<u64, DbError> {
        call_execute(&self.name, sql, params, None)
    }

    /// Opens a transaction (`fc_db_begin`), runs `f` with a [`Tx`] bound to
    /// it, and commits on `Ok` or rolls back on `Err` — the transaction is
    /// still force-released at the end of the call either way
    /// (`DbSession::close`, host-side), this is just the well-behaved path.
    pub fn transaction<T>(&self, f: impl FnOnce(&Tx) -> Result<T, DbError>) -> Result<T, DbError> {
        let id = call_begin(&self.name)?;
        let tx = Tx { db: self.name.clone(), id };
        match f(&tx) {
            Ok(value) => {
                call_commit(&tx.id)?;
                Ok(value)
            }
            Err(err) => {
                // Best-effort: the invocation's own end-of-call cleanup rolls back any
                // transaction still open regardless, so a failed rollback here is not
                // itself reported — `err` (the caller's own failure) is what matters.
                let _ = call_rollback(&tx.id);
                Err(err)
            }
        }
    }
}

/// An open transaction, bound to the [`Db`] it was opened on.
pub struct Tx {
    db: String,
    id: String,
}

impl Tx {
    pub fn query(&self, sql: &str, params: &[DbValue]) -> Result<DbRows, DbError> {
        call_query(&self.db, sql, params, Some(&self.id))
    }

    pub fn execute(&self, sql: &str, params: &[DbValue]) -> Result<u64, DbError> {
        call_execute(&self.db, sql, params, Some(&self.id))
    }
}

fn raw_call(answer: Result<String, extism_pdk::Error>) -> Result<Value, DbError> {
    let text = answer.map_err(|e| DbError { code: "DB_ERROR".to_string(), message: e.to_string() })?;
    serde_json::from_str::<Value>(&text)
        .map_err(|e| DbError { code: "DB_ERROR".to_string(), message: format!("malformed host answer: {e}") })
}

fn ok_or_error(value: Value) -> Result<Value, DbError> {
    match value.get("error") {
        Some(err) => {
            let code = err.get("code").and_then(Value::as_str).unwrap_or("DB_ERROR").to_string();
            let message = err.get("message").and_then(Value::as_str).unwrap_or("").to_string();
            Err(DbError { code, message })
        }
        None => Ok(value),
    }
}

fn call_query(db: &str, sql: &str, params: &[DbValue], tx: Option<&str>) -> Result<DbRows, DbError> {
    let input = serde_json::json!({"db": db, "sql": sql, "params": params, "tx": tx});
    let value = ok_or_error(raw_call(unsafe { fc_db_query(input.to_string()) })?)?;
    let rows = value
        .get("rows")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|r| r.as_object().cloned()).collect())
        .unwrap_or_default();
    let truncated = value.get("truncated").and_then(Value::as_bool).unwrap_or(false);
    Ok(DbRows { rows, truncated })
}

fn call_execute(db: &str, sql: &str, params: &[DbValue], tx: Option<&str>) -> Result<u64, DbError> {
    let input = serde_json::json!({"db": db, "sql": sql, "params": params, "tx": tx});
    let value = ok_or_error(raw_call(unsafe { fc_db_execute(input.to_string()) })?)?;
    Ok(value.get("updated").and_then(Value::as_u64).unwrap_or(0))
}

fn call_begin(db: &str) -> Result<String, DbError> {
    let input = serde_json::json!({"db": db});
    let value = ok_or_error(raw_call(unsafe { fc_db_begin(input.to_string()) })?)?;
    value
        .get("tx")
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| DbError { code: "DB_ERROR".to_string(), message: "host did not answer a tx id".to_string() })
}

fn call_commit(tx: &str) -> Result<(), DbError> {
    let input = serde_json::json!({"tx": tx});
    ok_or_error(raw_call(unsafe { fc_db_commit(input.to_string()) })?)?;
    Ok(())
}

fn call_rollback(tx: &str) -> Result<(), DbError> {
    let input = serde_json::json!({"tx": tx});
    ok_or_error(raw_call(unsafe { fc_db_rollback(input.to_string()) })?)?;
    Ok(())
}
