# Java mirror of the passkey namespace ruling and Go F13's ingest unwrap (2026-10-12)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` (F11 for migration 073's pattern, F13
"Encoded event `data` and audit `operationData`") and `../flowcatalyst-go/docs/wire-contract.md`
("Encoded `data` and `operationData` at batch ingest"); Go commits `1aa67fe` / migration 073 (the
rename-and-re-point pattern) and `8bf67d1` (the unwrap). Predecessor:
`2026-10-12-platform-bug-fixes-3.md`. Every commit is on `main`, not pushed.

Not in this batch (a Go agent is still defining them; a later mirror follows): dispatch-job
`clientCode`, the events-batch client guard, and a default `source`.

## Commits

| Commit | Change |
|---|---|
| `020d7a76` | A. Passkey events emitted and catalogued as `platform:admin:passkey:*`; V28 renames the catalogue rows and re-points bindings. |
| `14ea9b5a` | B. `POST /api/events/batch` and `POST /api/audit-logs/batch` unwrap an encoded `data` / `operationData`. |
| (this file) | Ledger. |

## A. Passkey event namespace (`020d7a76`)

**Ruling** (owner, 2026-10-12): Java emitted `platform:iam:passkey:{registered,authenticated,revoked}`
under source `platform:iam` (ruling I-Q25, 2026-09-05). Go
(`internal/platform/webauthn/operations/events.go`) and Rust
(`fc-platform-auth/src/webauthn/operations/events.rs`) emit `platform:admin:passkey:*` under source
`platform:admin`, with the same data (`{credentialId, userId, name?}`, `{credentialId, userId}`
twice), subject `platform.passkey.<id>` and message group `platform:passkey:<id>`. Java moves to
theirs.

**Changes:**

- `PasskeyEvents`: the three codes, and `SOURCE` `platform:admin`. The source was not named in the
  brief. It moves too because every Java events file's source is its codes' first two segments, and
  Go and Rust both say `platform:admin`. I-Q25 was about the source, so `docs/auth-rulings.md` and
  `docs/spec/auth-identity.md` now mark it superseded. Subject and message group are unchanged.
- Catalogue: `PlatformEventTypes` lists `platform:admin:passkey` after `platform:admin:roles:synced`
  (Go's position). `PlatformEventSchemas` has the same three schemas under the new codes. These
  equal Go's `event_types.go` / `event_schemas.go` rows (same DSL calls; name "Passkey …",
  subdomain `admin`, aggregate `passkey`).
- The go-seed fixture's six rows now carry Go's codes and subdomain, with a header note. Java-only
  codes go from 23 to 20; Go codes Java does not emit go from 21 to 18. The catalogue stays at 140.
- Tests updated: `PasskeyApiTest` (each event counted under its new code) and
  `EmittedEventSchemaTest` (sample map and `MAY_BE_ABSENT`).

**V28** (`V28__passkey_event_type_codes.sql`) is Go 073's five statements, with the three code
pairs inline in each (`VALUES`, no temp table):

1. A binding to an old code (by code, or by row) is dropped when its subscription already names the
   new code.
2. The remaining bindings get the new code. `event_type_id` points at the new row when one exists;
   otherwise it keeps the old row's id, which is about to be renamed.
3. and 4. Where the new code is already a row (a Go or Rust seeder on the same database ran
   first), the old row and its schema versions are deleted, so there is no unique-constraint
   failure.
5. Otherwise the old row is renamed (`code`, subdomain `admin`, aggregate `passkey`). Its id,
   status and schema versions are kept.

Re-pointing is right here, unlike V26: it is the same event under a new code.

- Wildcard bindings (`platform:iam:passkey:*`) are patterns and are left alone. Such a subscription
  stops receiving passkey events, as Go 073 accepts for `platform:admin:function:*`.
- Stored events and dispatch jobs keep the code they were written with.
- Idempotent. Flyway has no Down.
- On every boot the seeder runs after Flyway. It finds the renamed rows by code and adds nothing.

**Tests:**

- `PasskeyEventCodesMigrationTest` (scratch embedded databases; the SQL is re-executed over old-seed
  rows inserted after migration, as for V25 to V27):
  - Rename: ids, status and schemas kept, subdomain and aggregate set. The by-row-and-code and
    code-only bindings follow. The wildcard and a `platform:iam:reset-approval:queued` binding are
    kept. A re-run is a no-op, and the seeder adds no second row or schema.
  - Merge: the new rows exist already. Bindings move to the new rows' ids, including one by row id
    with a stale code. The duplicate is dropped, the old rows and schemas are gone, the new rows'
    schemas are kept, and a re-run is a no-op.
  - The SQL maps exactly the three pairs, in all five statements. The emitted constants equal the
    new codes and are catalogued, and the SQL names neither `msg_events` nor `msg_dispatch_jobs`.
- `EmittedCatalogueTest.thePasskeyEventsUseGoAndRustsCodes`: the codes are emitted and catalogued
  under `platform:admin:passkey`, and nothing under `platform:iam:passkey`.
- Migration counts: `MigratorTest` 28 and `GoAdoptionTest` 27 executed / 28 applied.
- Mutants, each run against the merge test, which failed both times:
  - the duplicate-binding delete dropped;
  - the `event_type_id` re-point dropped (the binding kept the deleted row's id).

**Wire impact:**

- `GET /api/event-types` and Sync Platform Events list the new codes.
- Passkey registrations, sign-ins and revocations are stored and dispatched under them, with source
  `platform:admin`.
- No SPA, SDK, fcdev or `/ui` reference to any passkey code. A whole-repo grep found only the
  seed, the tests, the fixture and the ledgers.

## B. Ingest unwrap of encoded `data` / `operationData` (`14ea9b5a`)

**Rule** (`io.flowcatalyst.platform.ingest.EncodedJson.unwrap`; Go `sdk.UnwrapEncodedJSON`):

- A member that is not a JSON string is returned unchanged: an object, array, number, boolean, JSON
  `null`, or an absent member.
- A string whose text, less JSON whitespace (space, tab, LF, CR), starts with `{` or `[` and is
  exactly one valid JSON value is replaced by that value.
- Every other string is kept.
- At most one level is removed, and the result is never a string.

Where it applies:

- `IngestApi.batchIngestEvents` applies it to `data`. That handler also serves the SPA's
  `/bff/events/batch` (Java mounts the same handler body there). Go has no BFF events batch, so in
  Java that route unwraps too. The SPA sends objects, so nothing changes for it.
- `batchIngestAuditLogs` applies it to `operationData`, before `AuditRedaction.redact`.

Unchanged:

- The singular `POST /api/events` (`EventIngestMapper` is shared, so the unwrap sits in the batch
  handler and not in the mapper).
- Dispatch-job `payload` (a `String` on the wire).
- Function events and the outbox processor.

**Accepted cost (Go's, the same).** A caller that means a string whose text is a JSON object or
array gets the object. Nothing tells it apart from an SDK's encoding, and none of the platform's
writers sends one. Such text has to be wrapped in an object. This is recorded in
`docs/spec/sdk-ingest.md` §3.3, which is the Java wire contract for these routes (Go's equivalent
is the `docs/wire-contract.md` section). §4.3 points to it.

**Java edge cases against Go.** Each is pinned in `EncodedJsonTest`.

- **Whitespace.** Only JSON whitespace is trimmed. `String#strip` would also remove vertical tab,
  form feed and the Unicode spaces, which Go keeps. A `"\u000b{}"` or `"{}\f"` stays a string in
  both. Mutant: `strip()` in place of the JSON trim; two cases fail.
- **Strictness.** The text is parsed by `Json.MAPPER` with `FAIL_ON_TRAILING_TOKENS` set
  explicitly. Mutant: `.without(...)`; `"{} trailing"`, `"{}{}"` and a `"{}\f"` case unwrap and
  fail. Comments, single quotes, unquoted keys, `NaN`, leading zeros, trailing commas and raw control
  characters are refused, as by Go's `json.Valid`.
- **Parse, not bytes.** Go returns the inner bytes verbatim, and JSONB normalises them. Java
  returns the mapper's tree, which is what the same value sent as itself becomes. A fraction is read
  as a `double`, so digits beyond double precision are lost where Go/JSONB keep them. The test pins
  "equals `Json.MAPPER.readTree` of the text", and that `a` is a double.
- **Limits.** Jackson's `StreamReadConstraints` apply. Text nested more than 500 levels is not
  parsed and stays a string, while Go unwraps it (Go's limit is 10,000). The same value sent as
  itself is refused by Java's body parse. Pinned at 501 (kept) and 500 (unwrapped).
- **Not compared.**
  - Duplicate keys inside the encoded text. Go's `json.Valid` accepts them and JSONB keeps the last
    value. Jackson's behaviour was not tested.
  - Lone UTF-16 surrogate escapes. Go's `json.Unmarshal` turns them into U+FFFD; what Jackson does
    was not tested.

**Tests:**

- `EncodedJsonTest`: Go's 23 table cases that apply to a parsed `JsonNode` (Go's two
  invalid-raw-input cases cannot arise once Jackson has parsed the body). The Java edges above, the
  null/absent cases, and the same node returned when a string is kept. Every case is also checked
  idempotent.
- `EncodedDataIngestTest`, through HTTP on the per-class embedded database, with the Rust SDK's
  golden fixtures. They are copied verbatim from `flowcatalyst-go/clients/rust-sdk/tests/fixtures/wire`
  (Go `544158f`) into `server/src/test/resources/ingest/rust-sdk-wire/`, so the tests do not read
  the sibling repo.
  - `event-full.json` is stored as an object, and its `clientCode` `acme` resolves to a client
    created for the test.
  - `event-minimal.json` without a source is refused in its slot ("source is required"); with one
    added it is stored as `{}`.
  - Go's case table: the value itself, an encoded array, `"[]"`, `"hello"`, `"null"`, `"42"` and
    `"{not json"`, plus a value encoded twice.
  - `/bff/events/batch` unwraps, and the singular create keeps a string `data`.
  - `audit-log-full.json` is kept whole: the test creates the `orders` application and the `acme`
    client, where Go removes the two codes. Its operation JSON is stored as the object
    `{"apiKey":"***","total":1500}`.
  - An encoded `{"password":"hunter2"}` is stored redacted. An object, `"plain text"` and `"null"`
    are kept.
- Mutants (each failed its test):
  - the events unwrap removed;
  - redaction moved before the unwrap (the password stored in clear);
  - trailing tokens allowed;
  - `String#strip` in place of the JSON trim.

**Stored rows.** Not changed, as in Go: no migration. To size them on a database:

```sql
SELECT count(*) FROM msg_events WHERE jsonb_typeof(data) = 'string'
  AND left(ltrim(data #>> '{}'), 1) IN ('{', '[');
```

Run the same query against `aud_logs.operation_json`. The owner decides, as for Go.

## Verification

- **Targeted runs before each commit.**
  - A: `PasskeyEventCodesMigrationTest`, `EmittedCatalogueTest`, `EmittedEventSchemaTest`,
    `PlatformCatalogueTest`, `SeederTest`, `MigratorTest`, `GoAdoptionTest`,
    `SchemaFingerprintTest` (1 skipped, as before) and `PasskeyApiTest`. All green.
  - B: `EncodedJsonTest`, `EncodedDataIngestTest`, `IngestApiTest` and `IngestMappingTest`. All
    green.
- **Full reactor, `mvn clean test` at `14ea9b5a`: BUILD SUCCESS**, 0 failures, 0 errors (12:19 min):

  | Module | Tests | Skipped |
  |---|---|---|
  | server | 5,750 | 1 |
  | function-host | 485 | 2 |
  | fcdev | 274 | 0 |
  | parity | 56 | 1 (`ParityRunTest` skips without a Go source) |
  | sdk | 93 | 1 |
  | usecase | 46 | 0 |
  | function-api | 145 | 0 |
  | eventloop-check | 16 | 0 |

  The server has 42 more tests than at `37993383` (5,708). They are this batch's 3 + 1 (A) and
  35 + 3 (B).

## Not verified

- V28 ran on embedded scratch databases only (through Flyway on every test database, and its SQL
  re-executed over old-seed rows), never on a deployed database.
- The Go/Java parity harness was not run.
- No browser run. No SPA change was made.
- The Rust platform was not checked for the ingest unwrap. Go's F13 lists it as still to mirror
  (`batch_create_events`, `sdk_audit_batch_api.rs`).
