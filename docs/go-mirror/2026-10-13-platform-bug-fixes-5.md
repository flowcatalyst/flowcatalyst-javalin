# Java mirror of Go F14: ingest client rules, dispatch-job `clientCode`, default event `source` (2026-10-12)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` F14, `../flowcatalyst-go/docs/wire-contract.md`
("SDK ingest: client and source") and `internal/platform/shared/sdk/client_code.go`; Go commits
`fe25501` (dispatch-job `clientCode`), `abd2ae9` (events client guard), `4c3654a` (default
`source`) and `4bc3cc6` (docs). Predecessor: `2026-10-13-platform-bug-fixes-4.md`, which deferred
these three. Every commit is on `main`, not pushed.

## Commits

| Commit | Change |
|---|---|
| `2928d4a0` | A. Events: a `clientCode` refusal names the code; a blank `clientId`/`clientCode` is absent on every ingest route. New `IngestClients`. |
| `c1572b44` | B. Dispatch jobs: `clientCode` on the batch item and the singular body. |
| `5fc01261` | C. Events: a missing `source` defaults to the type's `{application}:{subdomain}`; lockfile `CreateEventRequest.source` optional. |
| (this file) | Ledger. |

The Java wire contract for these routes is `docs/spec/sdk-ingest.md` (Go's is the
`wire-contract.md` section): §3.4 (client), §3.5 (source), §4.1/§4.2 (dispatch-job client rule).

## `IngestClients` (Go `client_code.go`)

`io.flowcatalyst.platform.ingest.api.IngestClients`, package-private, one per request:

- `resolve(code)`: the per-request memoised `clientCode` → client id (one lookup per distinct
  code; replaces `IngestApi.resolveClientId`, the audit batch uses it too, unchanged behaviour).
- `nonBlank(s)`: `null` for absent or whitespace-only (Go `NonBlank`).
- `requireWritableClient(ac, clientId)`: moved from `IngestApi`, now blank-means-absent (Go
  `RequireWritableClient`).
- `resolveEventClient` (Go `ResolveEventClient`), `resolveJobClient` (Go `ResolveJobClient`).

## A. Events client guard (`2928d4a0`)

Ruling 3 of the brief. Java already had the guard Go copied (S3.2); two differences remained:

1. **A `clientCode` that resolves to a client the caller cannot reach** answered "No access to
   client: `<resolved id>`": it leaked the other tenant's client id and told an existing code apart
   from an unknown one ("No access to client: `<code>`"). Now both are 403 `FORBIDDEN` "No access
   to client: `<clientCode>`".
2. **Blank `clientId`**: Java treated only `null` as absent. A blank `clientId` was refused for a
   non-anchor ("No access to client: " / "No access to client:   "), stored as sent (`""`, `" "`)
   for an anchor, and blocked the `clientCode` beside it; the singular route's first-client default
   (§5 D3) fired only on `null`. Now blank counts as absent everywhere (events batch and singular,
   both dispatch-job routes, via `requireWritableClient`). A blank `clientCode` was already ignored.

Unchanged and already identical to Go: statuses, codes and the other messages, the single-client
default, the whole-batch refusal, an anchor's unknown code leaving the event platform-scoped.

**Tests** (`IngestClientRulesTest`, new; two clients whose identifiers differ from their ids, so a
message naming the id is told apart from one naming the code; each failed before the fix except
the own-code case):

- another client's code and an unknown code: the same 403 naming the code, the body never contains
  the other client's id, a valid item before it not written;
- own code resolves, an explicit id wins over another client's code;
- blank `clientId` (`""`, `"  "`, `" "` + own code) for a single-client caller: its client / the
  code; blank `clientId` + another client's code: 403 naming the code;
- an anchor's blank `clientId` stored `null` (batch and singular), blank id + code resolves;
- singular blank `clientId`: the single-client caller's client, and a two-client PARTNER's first
  client (the only case the singular default decides);
- dispatch jobs: a blank `clientId` is absent (batch and singular, single-client caller and anchor).

**Mutants** (each failed its test): the refusal naming the resolved id; `requireWritableClient`
without blank handling (killed only by the dispatch-job case, which is why that test exists); the
events rule checking `clientId != null` instead of non-blank; the singular default on `null` only
(killed only by the PARTNER case).

## B. Dispatch-job `clientCode` (`c1572b44`)

Ruling 1 of the brief. `DispatchJobBatchItem` and `CreateDispatchJobRequest` gained `clientCode`
(never stored). `resolveJobClient`, per item, before anything is written:

1. a non-blank `clientId` wins (the code is ignored, even one naming another client):
   `requireWritableClient`, 403 "No access to client: `<clientId>`";
2. else a code naming a client the caller reaches resolves to it;
3. a code naming another client, or none, from a non-anchor: 403 `FORBIDDEN` "No access to client:
   `<clientCode>`";
4. a code naming no client from an anchor: 400 `CLIENT_NOT_FOUND` "Client not found:
   `<clientCode>`";
5. neither: `requireWritableClient(null)`, unchanged (see Divergences).

Any refusal rejects the whole batch (no `items[i]` prefix, as the client guard had none). Order
in the Java batch loop is unchanged: client, then mapping (`INVALID_KIND`), confinement, signing
identity, duplicate ids; Go maps first, then the client. Only the first refusal's code can differ,
and only for a batch with two faults.

**Tests** (`IngestClientRulesTest`, each failed before the fix):

- anchor: a code resolves (batch, two items sharing it: one lookup; singular), an explicit id wins
  over a code, a blank code is ignored (`null` stored);
- anchor: an unknown code is 400 `CLIENT_NOT_FOUND` naming the code, a valid item before it not
  written; the singular route the same;
- client-scoped caller: own code resolves, explicit own id wins over another client's code; another
  client's code and an unknown code are the same 403 naming the code (body never names the other
  id), batch and singular, nothing written; a two-client PARTNER names one client by its code
  (before: 403 "clientId is required").

**Mutants** (each failed): an anchor's unknown code 403 instead of 400; a resolved id not
reach-checked; the code winning over `clientId`; the batch ignoring `clientCode`; the singular
ignoring `clientCode`; a blank code not ignored; no memoisation.

## C. Default event `source` (`5fc01261`)

Ruling 2 of the brief. `EventIngestMapper.eventSource(source, type)` (Go `eventSource`): a
non-blank source is returned as sent; else the trimmed type's first `:` segment, plus `:` and the
second when that is non-blank; `null` when the first segment is blank (or the type is absent).
`EventIngestMapper.toEvent` stores it and throws `VALIDATION` "source is required" on `null`, so the
singular route answers 400 `VALIDATION` "source is required" and no longer stores `""`. The batch
handler's per-item check calls the same function (item `BAD_REQUEST` "source is required" only for
a blank first segment). `/bff/events/batch` is the same handler. Function-emitted events always
pass `function:<address>` and are unaffected.

**Lockfile**: `CreateEventRequest.required` drops `source` (the request-schema validator had
refused an absent one with "expected required property source to be present"); `source` on
`CreateEventRequest` and `BatchEventItem` carries Go's description. Both schemas now equal Go's
`api/openapi.lock.json` (compared as parsed JSON). The SDK and SPA copies of the spec
(`clients/*/openapi/openapi.json`, `sdk/openapi/openapi.json`, `frontend/openapi/openapi.json`)
and the generated SPA types were already out of step with the lock and were not touched; Go did
not change its `clients/` copies either, so `tools/sdk-drift.sh` is unaffected. Go regenerated
its SPA's `types.gen.ts`; `tools/frontend-drift.sh` will show that file as different, as it already
did.

**Tests:**

- `IngestMappingTest`: Go's table (absent, `null`, blank, given kept, given with spaces kept,
  type trimmed, two segments, one segment, `orders: :x`, `orders::x`, blank first segment, blank
  and absent type) on `eventSource`; `toEvent` stores the default and refuses when there is none.
- `IngestApiTest.theEventsBatchDefaultsAMissingSourceFromTheType`: absent, `null`, blank, given,
  one segment, and a blank-first-segment item refused in its slot.
- `IngestApiTest.theSingularCreateDefaultsAMissingSourceFromTheType`: absent (response and stored
  row), blank, given, and a blank first segment 400 `VALIDATION` with nothing written.
- `EncodedDataIngestTest`: the Rust SDK's `event-minimal.json` now succeeds on `/api/events/batch`
  and `/bff/events/batch`, stored source `orders:sales` (before: refused in its slot).
- `IngestApiTest.aValidationFailureOnOneItem…`: its "source is required" case now uses a type with
  a blank first segment.

**Mutants** (each failed): the batch still refusing a missing source; the first segment only; the
type not trimmed; a blank given source kept; the mapper storing `""` instead of refusing; the lock
still requiring `source`.

## Divergences from Go

- **Dispatch jobs with neither `clientId` nor `clientCode`** (owner: left as is): Java applies
  `requireWritableClient` (anchor: platform-scoped; a caller with exactly one client: that client;
  anyone else 403 "clientId is required: …"); Go writes a non-anchor's job platform-scoped. Go
  records this under "New findings, not fixed".
- **`"*"` in `clients`**: Go's single-client default skips a `clients` of exactly `["*"]`; Java's
  does not check, as before. In Java `"*"` is emitted only for anchors, which return before the
  check. Not changed.
- **Whitespace**: "blank" and "trimmed" use Java's `String.isBlank`/`strip`
  (`Character.isWhitespace`); Go uses `unicode.IsSpace`. They differ on U+00A0, U+0085, U+2007,
  U+202F (whitespace to Go only) and U+001C–U+001F (to Java only). Not pinned.
- **Singular event order**: Go checks data and source before application confinement and the
  client guard; Java checks confinement, client and signing identity first, then data and source
  in the mapper (as before for data). A request that is both refused and has no source gets a 403
  in Java where Go answers 400.
- **Dispatch-job batch order**: see B.

## Parity

`parity/expected-diffs.json` has no entry on these behaviours, and every `events` scenario step
sends a source and no dispatch-job step sends a `clientCode`; nothing changed there. The harness
was not run.

## Verification

- Targeted runs before each commit: `IngestClientRulesTest`, `IngestApiTest`,
  `EncodedDataIngestTest`, `IngestMappingTest` (and, for C, `LockfileCoverageTest`,
  `FunctionControlApiTest`). All green. Mutants run with `-Dsurefire.timeout=300`.
- **Full reactor, `mvn clean test` at `5fc01261`: BUILD SUCCESS**, 0 failures, 0 errors
  (12:16 min):

  | Module | Tests | Skipped |
  |---|---|---|
  | server | 5,776 | 1 |
  | function-host | 485 | 2 |
  | fcdev | 274 | 0 |
  | parity | 56 | 1 (`ParityRunTest` skips without a Go source) |
  | sdk | 93 | 1 |
  | usecase | 46 | 0 |
  | function-api | 145 | 0 |
  | eventloop-check | 16 | 0 |

  The server has 26 more tests than at `14ea9b5a` (5,750): `IngestClientRulesTest` 9,
  `IngestApiTest` 2, `IngestMappingTest` 15 (14 table rows and 1).

## Not verified

- Embedded databases only; no run through a running outbox processor, a deployed platform, or a
  browser.
- The Rust platform was not checked (Go F14 lists its mirror separately).
