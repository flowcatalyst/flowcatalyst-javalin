# Database access for Wasm functions — `fc.db.*` (W4)

Plan: `docs/plan/wasm-and-js-functions.md` (D5: after the runtime). A JVM function gets a
`DataSource` per manifest `db[]` entry (`DbPools`, `FunctionDataSource`); a Wasm guest cannot
hold one, so it gets host functions over the **same** pools. Starts after W1+W2; runs in parallel
with W3.

## Host functions (`extism:host/user`, JSON in, JSON out)

| Function | Input | Output |
|---|---|---|
| `fc_db_query` | `{"db","sql","params":[…],"tx"?}` | `{"rows":[{col:value}], "truncated":bool}` or `{"error":{code,message}}` |
| `fc_db_execute` | `{"db","sql","params":[…],"tx"?}` | `{"updated":n}` or `{"error":…}` |
| `fc_db_begin` | `{"db"}` | `{"tx":"<opaque id>"}` or `{"error":…}` |
| `fc_db_commit` / `fc_db_rollback` | `{"tx"}` | `{"ok":true}` or `{"error":…}` |

- `db` must name a manifest `db[]` entry; anything else is `{"error":{"code":"DB_NOT_DECLARED"}}`.
- Values: `params` are JSON scalars bound positionally (`?`) — never interpolated; strings,
  numbers, booleans, null. Rows: SQL types to JSON — numbers as numbers (exact decimals as
  strings), timestamps ISO-8601, `bytea` base64, `json`/`jsonb` as parsed JSON.
- **Connections are scoped to the invocation.** A `tx` id is valid only within the call that
  opened it; at the end of the call every connection it borrowed is returned and any open
  transaction **rolled back** (force-release, even on trap, deadline or failure). Without `tx`,
  each statement runs in its own autocommit borrow.
- **Deadline:** each statement's timeout is the time left before the invocation deadline
  (`InvocationDeadline`), so a slow query cannot outlive the call.
- **Size:** at most 10 000 rows or 8 MiB of row JSON per query (whichever first) → `truncated`.
- Errors are values (`code` from the SQLSTATE class: `DB_CONSTRAINT`, `DB_SYNTAX`, `DB_TIMEOUT`,
  `DB_UNAVAILABLE`, `DB_ERROR`) — never a trap; the message is the driver's, the SQL text and
  parameter values are never logged.

The guest library (`@flowcatalyst/function`, W3) gains `ctx.db(name).query/execute/transaction(fn)`
wrappers once this lands; the Rust fixture guest gains matching calls.

## Tests (mutant each)

1. Query and execute against the per-class test database through the real pools; params bound, not
   interpolated (a value containing `'; DROP` round-trips as data).
2. A transaction committed is visible afterwards; one left open when the call ends is rolled back
   and its connection returned (pool's active count back to its prior value — mutant: skip the
   end-of-call release → the count assertion fails).
3. A `tx` id from one call is refused in the next (mutant: a global tx map without the call check).
4. An undeclared `db` is `DB_NOT_DECLARED`; a statement past the deadline is `DB_TIMEOUT` and the
   connection is still returned.
5. Truncation at the row cap.
