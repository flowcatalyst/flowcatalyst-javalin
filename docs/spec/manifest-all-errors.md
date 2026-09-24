# Manifest parsing reports every problem, not the first

Backlog item from `function-manifest-authoring.md` M2.2. Today `Manifest.parseStrict` throws at the
first of 72 `UseCaseException.validation` sites, so an author fixing a manifest in the SPA editor
or with `fn validate` sees one error per round trip. This unit makes the parser collect every
independent problem, with a JSON Pointer to where it is, and keeps publish's contract unchanged.

Rules: `CONVENTIONS.md` §8 — expected outcomes are `Result<T, E>` with a sealed, context-carrying
`E`; exceptions only at envelope boundaries. `CLAUDE.md` testing policy.

## 1. Shape

```java
record ManifestProblem(String code, String message, String pointer) {}   // pointer: RFC 6901, "" = root
record ManifestRejected(List<ManifestProblem> problems) {}               // never empty
static Result<Manifest, ManifestRejected> check(JsonNode root, Runtime runtime,
                                                FunctionLimits defaults, ClientCeilings ceilings)
```

- `check` is the parser. It walks the whole document, recording a `ManifestProblem` wherever
  today's code throws, and returns `Ok(manifest)` only when nothing was recorded.
- `parseStrict` becomes `check(...)` then, on `Err`, throws the **first** problem as today's
  `UseCaseException.validation(code, message)`. Publish is therefore byte-for-byte unchanged:
  same first code, same message (messages keep the dotted path they name today). Every existing
  `ManifestTest`/`PublishVersion` assertion must pass unedited.
- The old `Manifest.check` → `Result<Manifest, UseCaseError>` bridge (M2.2) is replaced by this
  one; its callers move to the new type.
- Internally: a mutable problem collector threaded through the parse functions. A parse function
  that fails records its problem(s) and yields nothing (an empty `Optional` or equivalent — never a
  `null` or a placeholder value that later code could mistake for real). No exception is thrown
  and caught to implement collection; value-type constructors that throw on bad input
  (`DnsLabel`, `RoutePattern`, `Hostname`, `SettingKey` …) are called only after the same
  validity check they perform, or through a non-throwing `parse`/`isValid` companion — add one
  where missing, do not wrap a throwing constructor in try/catch.

## 2. What counts as independent (no cascades)

The collector must report what the author wrote wrong, not the consequences of it. A check runs
only when every input it reads parsed cleanly:

- Invalid or missing `runtime`: skip the runtime-dependent checks (`entrypoint` pattern,
  `wasmMemoryMb` applicability).
- Invalid `limits`: skip checks that compare against the resolved limits (endpoint `timeoutMs`
  vs `maxDurationMs`).
- An endpoint that failed to parse is left out of `ROUTE_AMBIGUOUS` and of the webhook-path
  lookups; a subscription or schedule whose path names an **invalid** endpoint is not also
  reported as `*_PATH_NOT_WEBHOOK`.
- Duplicate checks (`*_DUPLICATE`) compare only entries that parsed.
- `MANIFEST_REQUIRED` (root not an object) is the only problem when it occurs.
- Unknown keys (`MANIFEST_UNKNOWN_FIELD`) are reported for every unknown key, and the known keys
  beside them are still parsed.

Order: document order (top-level keys in `TOP_KEYS` order, array items by index, the same order
today's code visits them), so the first problem is exactly what `parseStrict` throws today.

## 3. Where the list surfaces

- `POST /api/functions/{address}/manifest/check`: `errors[]` carries every problem;
  each entry's `details.pointer` is the problem's pointer. Cross-checks (`checkPublish`) still run
  only when the manifest parsed. `functions.openapi.json` documents `details.pointer`.
- `fcdev fn validate`: prints every problem, `code pointer: message`, one per line.
- The SPA editor (`ManifestEditorDrawer.vue`): attaches each error to the form field its pointer
  names (endpoint *i*'s field, subscription *i*'s field, …), falling back to the section, then to
  the error list — replacing today's code-prefix mapping (`ENDPOINT_*` → Endpoints section).
- Publish (`POST …/versions`): unchanged — first problem, 400, as today.

## 4. Tests (mutant each)

1. **No cascades, exactly as today.** For every invalid-manifest case already in `ManifestTest`
   (each has one mistake), `check` returns exactly **one** problem, whose code and message equal
   what `parseStrict` throws. Mutant: drop the "skip when an input failed" guard on
   `*_PATH_NOT_WEBHOOK` → a case with an invalid endpoint reports two problems.
2. **All independent problems.** A manifest with at least six independent mistakes in different
   sections (unknown top-level key, bad pool, endpoint without `auth`, bad CORS origin, duplicate
   subscription, invalid `db[].name`) returns all six, in document order, with the right pointers
   (`/x`, `/pool`, `/endpoints/1/auth`, `/endpoints/2/cors/origins/0`, `/subscriptions/1`,
   `/db/0/name`). Mutant: return after the first problem → count is 1.
3. **Publish unchanged.** `PublishVersion` with the six-mistake manifest answers the first code,
   and the existing publish tests pass unedited.
4. **Route and CLI.** The check route returns all six with `details.pointer`; `fn validate`
   prints six lines and exits 1.
5. **SPA.** A check response with a pointer to `/endpoints/1/auth` renders under endpoint 1's
   auth field; a pointer with no matching field falls back to the error list.

## 5. Docs

`function-registry.md` §4.3 (the parser reports every problem; publish the first),
`function-manifest-authoring.md` M2.2 (the backlog note is resolved), `function-ui.md` §2.6
(pointer-based attachment), backlog entry closed.
