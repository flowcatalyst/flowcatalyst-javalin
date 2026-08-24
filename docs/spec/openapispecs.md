# Application OpenAPI specs — behavioural spec

The contract for `io.flowcatalyst.platform.openapispecs`: the per-application
OpenAPI document store (`app_application_openapi_specs`) and its one
operation, `SyncOpenApiSpec`. Derived from the lockfile
(`POST /api/applications/{appCode}/openapi/sync` → `SyncOpenApiSpecResponse`)
plus the behaviour the Go `internal/platform/openapispecs` package embodies.
The route itself belongs to the sdksync surface (`docs/spec/sdksync.md` §3);
only the aggregate, repository and operation live here. The Java is written
*from* this; tests assert it. Questions marked **load-bearing or accident?**
need an owner ruling — until ruled on, the behaviour is kept.

## 1. Aggregate — `OpenApiSpec`

One stored OpenAPI document for an application. An application has **at most
one `CURRENT` row** at a time (partial unique index
`(application_id) WHERE status = 'CURRENT'`); every earlier sync is kept as
an `ARCHIVED` row so the lineage is auditable.

| Field | Type | Notes |
|---|---|---|
| `id` | `oas_` + 13-char TSID | minted on create |
| `applicationId` | string, required | hard FK to `app_applications` |
| `version` | string ≤ 64, required | unique per application (`UNIQUE (application_id, version)`) — see §3 for how it is chosen |
| `status` | `CURRENT` \| `ARCHIVED` | lenient stored read: unknown → `CURRENT` (**accident?** kept — Go did the same) |
| `spec` | JSON object, required | the document, stored as JSONB verbatim |
| `specHash` | string ≤ 64 | canonical-JSON SHA-256 hex of `spec` (§2) |
| `changeNotes` | `ChangeNotes`, optional | set only when the row is archived: the structural diff to its successor |
| `changeNotesText` | string, optional | the rendered summary of `changeNotes`, set with it |
| `syncedAt` | timestamp | the sync's `now` |
| `syncedBy` | principal id, optional | the execution context's principal (`null` when unauthenticated — never reached, authorize rejects first) |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` re-stamped on archive |

`ChangeNotes(addedPaths[], removedPaths[], addedSchemas[], removedSchemas[],
removedOperations[], hasBreaking)` is persisted as JSONB alongside the
archived row (wire names verbatim, empty lists omitted on write). The stored
column is a **foreign shape** (Go wrote it): a read tolerates `NULL`, omitted
keys and a bare `{}` (→ empty lists, `hasBreaking=false`).

State machine: `CURRENT --archive(notes, summary)--> ARCHIVED`. Archiving an
already `ARCHIVED` row is a conflict `ALREADY_ARCHIVED` (defensive — the
operation only ever archives the `CURRENT` row). There is no un-archive.

## 2. The document — `OpenApiDocument` (parser record)

The incoming `spec` is parsed once (`OpenApiDocument.parse(JsonNode)`) and
the parsed value is what the operation carries:

| Rule | Accepted | Rejected (400 `INVALID_OPENAPI_SPEC`) |
|---|---|---|
| must be a JSON object | `{"openapi":"3.0.0"}` | `null`/absent, `["x"]`, `"str"`, `42` — "OpenAPI spec must be a JSON object" |
| must carry a top-level `openapi` **or** `swagger` key (any value) | `{"openapi":"3.1.0"}`, `{"swagger":"2.0"}` | `{"info":{}}` — "Spec is missing the top-level `openapi` (or `swagger`) field" |

Derived facts:

- `infoVersion()` — `info.version` when it is a non-blank string, else
  absent.
- `hash()` — SHA-256 hex (64 chars) of the **canonical JSON**: object keys
  sorted (code-point order), arrays in place, no whitespace, scalars as
  Jackson writes them. Two documents differing only in key order or
  whitespace hash identically. **Load-bearing or accident?** (1) The hash is
  an internal fingerprint, not a contract: Go's canonicaliser escaped
  `<>&` and formatted floats Go-style, so a document last synced by Go can
  hash differently under Java and will be archived+re-inserted **once** on
  the first Java re-sync of an unchanged document. Kept (no cross-runtime
  hash vector exists); flag if a stable fingerprint is wanted.

## 3. Operation — `SyncOpenApiSpec` (`SyncOpenApiSpecCommand`)

Command: `SyncOpenApiSpecCommand(applicationId, applicationCode, spec: JsonNode)`.

| Phase | Rule | Error |
|---|---|---|
| validate | `applicationId` non-blank | 400 `APPLICATION_ID_REQUIRED` "applicationId is required" |
| validate | `applicationCode` non-blank | 400 `APPLICATION_CODE_REQUIRED` "applicationCode is required" |
| validate | `OpenApiDocument.parse(spec)` (§2 table) | 400 `INVALID_OPENAPI_SPEC` |
| authorize | `Checks.checkApplicationAccess(current, applicationId, applicationCode)` | 403 `FORBIDDEN` "Not authorised for application '<code>'" / `UNAUTHENTICATED` |
| execute | the state machine below | — |

Execute (`now` = one `Instant` for the whole run):

| Case | Effect | Result / event |
|---|---|---|
| a `CURRENT` row exists **and** its `specHash` equals the document's hash | nothing written | `Emit` `ApplicationOpenApiSpecSynced{specId = prior.id, version = prior.version, specHash, hasBreaking=false, archivedPriorVersion=null, unchanged=true}` |
| a `CURRENT` row exists, hash differs | prior → `archive(ChangeNotes.diff(prior.spec, new), summary)`; new `CURRENT` row inserted; both in one `SaveAll` | event with the new row's id/version/hash, `archivedPriorVersion = prior.version`, `hasBreaking = notes.hasBreaking`, `unchanged=false` |
| no `CURRENT` row | new `CURRENT` row inserted | event with `archivedPriorVersion=null`, `hasBreaking=false`, `unchanged=false` |

Version choice for the new row: `info.version` when present, else
`now` formatted `yyyyMMddHHmmss` (UTC). If **any** row (CURRENT or
ARCHIVED) of the application already has that version, the stored version is
`<candidate>+<yyyyMMddHHmmss>` (**load-bearing**: `UNIQUE (application_id,
version)`; the suffix is what keeps a re-published `1.0.0` insertable).
`syncedBy` = `ec.principalId()`, `syncedAt = createdAt = updatedAt = now`.

**Deviation** (2): Go's operation was `Public` and the SDK handler did the
application-access check itself (the anchor-only BFF "platform spec sync"
also reached it). Java applies the promoted rule — the check sits in the
operation's `authorize` — so a future BFF entry point must pass a caller with
application access (anchors with `allApplications` do). (3) Go wrote the
rows directly in `Execute` and emitted the event in a tail transaction; Java
returns one `Plan.saveAll` (archive + insert + event + audit atomically) —
the partial unique index still serialises concurrent syncs (the loser fails
with a unique violation → 500; **accident?** kept, no retry in Go either).

### Change notes — `ChangeNotes.diff(prior, current)`

A shallow structural diff, every list **sorted**:

| Field | Rule |
|---|---|
| `addedPaths` / `removedPaths` | set difference of the `paths` object keys |
| `addedSchemas` / `removedSchemas` | set difference of the `components.schemas` object keys |
| `removedOperations` | for every path present in both: HTTP verbs (`get put post delete options head patch trace`) present before and absent now, rendered `VERB /path` (upper-case verb) |
| `hasBreaking` | any of `removedPaths`, `removedSchemas`, `removedOperations` non-empty |

A missing/non-object `paths` or `components.schemas` counts as no keys.
`isEmpty()` = all five lists empty.

Summary text (`changeNotesText`):

- empty diff → `No structural changes (descriptions or examples may differ).`
- else the non-zero parts in this order, joined by `; `: `Added N path(s)`,
  `Removed N path(s)`, `Removed N operation(s)`, `Added N schema(s)`,
  `Removed N schema(s)`; then `. Contains breaking changes (removals).` when
  breaking, else `.`; then, when any removals exist, ` — ` + the non-empty
  of `removed paths: …`, `removed ops: …`, `removed schemas: …` joined by
  `; `, each listing at most 5 items (`a, b, … (+N more)`).

## 4. Domain event

`ApplicationOpenApiSpecSynced` — emitted on every sync (changed or not):

| Attribute | Value |
|---|---|
| type | `platform:developer:application-openapi:synced` |
| source | `platform:developer` |
| subject | `platform.application-openapi.<specId>` |
| messageGroup | `platform:application-openapi:<applicationId>` |
| data | `{applicationId, applicationCode, specId, version, specHash, archivedPriorVersion?, hasBreaking, unchanged}` (`archivedPriorVersion` omitted when null) |

One `aud_logs` row per sync, `operation = SyncOpenApiSpecCommand`, entity =
the spec id the event names.

## 5. Repository

`OpenApiSpecRepository` over `app_application_openapi_specs`:
`findById`, `findCurrentByApplication` (the single `CURRENT` row),
`findAllByApplication` (CURRENT first, then `synced_at DESC, id DESC`),
`existsByApplicationAndVersion` (any status), `persist` (upsert on `id`,
every column listed once, `created_at` insert-only), `delete`.

## 6. Tests

- `OpenApiSpecTest` (no DB): the §2 accept/reject table, key-order/whitespace
  hash stability, `infoVersion` absent cases, the diff table incl. verb
  removal and summary rendering, the archive transition + `ALREADY_ARCHIVED`.
- `OpenApiSpecOperationsTest` (envelope): validation table, authorization
  (no application access → `FORBIDDEN`), lifecycle (first sync → CURRENT
  row with `syncedBy`; identical re-sync → `unchanged`, no new row; changed
  → prior archived with notes text, new CURRENT, `archivedPriorVersion`;
  version collision → `+timestamp`; `swagger` accepted), `msg_events` +
  `aud_logs` rows.
- `OpenApiSpecRepositoryTest`: raw rows in Go-written shapes (`change_notes`
  `NULL`, partial keys) read back.
