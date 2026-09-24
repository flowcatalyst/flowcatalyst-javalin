# Function manifest authoring aids

Queued by the owner behind package J (backlog §"Function domains: zone claims and alias
prefixes"): a JSON Schema for `manifest.json`, a validate/dry-run route that returns the promote
plan, an SPA editor, and `fcdev fn init`. **The manifest file stays the source of truth; every aid
here is an editor or a check, never a second definition.**

Background (verified 2026-09-24): the manifest is `Manifest.java` — `parseStrict` (publish reader,
rejects unknown keys, first error wins, throws `UseCaseException`), `readStored`, `toJson`. Current
field shape: `function-invocation.md` §3; readers/defaults/limits and the error table:
`function-registry.md` §4. Publish = `PublishVersion` (parse, then `FunctionTriggerSync.onPublish`
validate-only). Promote = `PromoteVersion` (`requireSettingsPresent` → alias flip → for `live`
only, `FunctionTriggerSync.onPromote`, which diffs and writes interleaved — **there is no plan
today**). No JSON Schema exists; `functions.openapi.json` carries `PublishManifestRequest`, an
OpenAPI rendering of the same shape.

Error handling throughout: `CONVENTIONS.md` §8 — expected outcomes are `Result<T, E>` with a
sealed, context-carrying `E`; `UseCaseException` only at the envelope's boundaries.

Packages, in build order: **M1** schema · **M2** plan + validate · **M3** `fn init` · **M4** SPA.
M1 and M2 touch disjoint files and may be built in parallel; M3 needs M1's schema URL; M4 needs M2.

---

## M1. The manifest's JSON Schema

1. **The document.** `server/src/main/resources/schemas/function-manifest.schema.json`, JSON
   Schema draft 2020-12, `$id` `urn:flowcatalyst:function-manifest`. Every object
   `additionalProperties: false`. Field names, types, `required`, enums and simple bounds exactly as
   `parseStrict` enforces them (the fact sheet's table in `function-registry.md` §4 / §4.3 is the
   checklist). Conditional rules JSON Schema can express are expressed (`if`/`then`):
   `limits.wasmMemoryMb` only when `runtime` is `wasm`; an endpoint with `auth: webhook` and
   `methods` has `methods` exactly `["POST"]`; `cors` origins `*` forbids `allowCredentials`.
   Rules it cannot express (`ROUTE_AMBIGUOUS`, `SUBSCRIPTION_PATH_NOT_WEBHOOK`,
   `SCHEDULE_PATH_NOT_WEBHOOK`, the `*_DUPLICATE` rules, limit ceilings, cron/zone validity,
   anything needing the database) are stated in the relevant `description`, naming the error code,
   so an editor tooltip tells the author what the platform will say. Every property has a
   one-line `description`; defaults are stated with `default` where the parser has one.
2. **`$schema` in a manifest is allowed.** `parseStrict` accepts an optional top-level `$schema`
   whose value is a string (any other type: `MANIFEST_INVALID`) and ignores it; `toJson` never
   writes it (the stored manifest is unchanged); `readStored` tolerates it. This is what lets VS
   Code / IntelliJ validate `manifest.json` as the author types.
3. **Served.** `GET /api/schemas/function-manifest.json` answers the file's bytes,
   `application/schema+json`, unauthenticated, `Group.NO_DB` — the `FunctionOpenApiRoutes`
   pattern, mounted beside it in `Platform`. Added to `LockfileCoverageTest`'s
   `OUTSIDE_LOCKFILE_EXACT_ROUTES` with a comment naming this spec (it is a document, like
   `/api/openapi-functions.json`).
4. **The sample uses it.** `examples/function-hello/manifest.json` (and any other committed
   manifest under `examples/`) gains `"$schema"` pointing at the committed file by relative path.
5. **It cannot drift.** A test walks the schema and compares it with the parser, level by level:
   the property names of every object equal the parser's key set for that object (`TOP_KEYS` and
   its siblings — expose them package-private if needed; add `$schema` at top level only);
   `required` equals the parser's required fields; every enum equals the wire values of the Java
   enum it mirrors (`Runtime`, `EndpointAuth`, `HttpMethod`, `DispatchMode`); `additionalProperties`
   is `false` everywhere. A second assertion compares the schema's property names with
   `functions.openapi.json`'s `PublishManifestRequest` at every level (the two renderings of one
   shape). A third parses every committed manifest (`examples/**/manifest.json`, test fixtures)
   with `parseStrict`. No JSON Schema validator library is added.

Tests (mutant each): removing a key from `TOP_KEYS` (or adding one to the schema) fails the walk;
adding an enum constant fails it; `parseStrict` rejecting `$schema` fails the sample test;
`toJson` writing `$schema` fails a round-trip assertion; the route answers 200 with the file's
exact bytes and the content type, with no credentials.

## M2. The promote plan, and a route that returns it without promoting

### M2.1 Plan, then apply

`FunctionTriggerSync.onPromote`'s reconciliation is split into **compute** and **apply**:

- `PromotePlan plan(...)` — reads current state (trigger objects, subscriptions, scheduled jobs,
  the pool, `fn_routes`) and returns what promoting the manifest to `live` would do. No writes.
- `apply(scoped, PromotePlan, ...)` — performs exactly the plan, in today's order (creates and
  updates first, deletions last), with today's `PUBLIC_ROUTE_TAKEN` and `TRIGGER_KEY_COLLISION`
  behaviour.
- `onPromote` becomes `apply(plan(...))`. **Promote and the dry run run the same `plan` code** —
  that is the whole point; a dry run that re-derives the diff separately is refused in review.

`PromotePlan` (a record; sealed actions, no nullable-as-state):

| Part | Shape |
|---|---|
| `alias`, `fromVersion` (the alias's current version, absent if none), `toVersion` | |
| `settingsMissing` | keys, first-seen order — `PromoteVersion.requireSettingsPresent`'s computation, returned instead of thrown |
| `pool` | `Create(key)` · `Update(key, changedFields)` · `Unchanged(key)` |
| `subscriptions[]` | per event type: `Create` · `Update(changedFields)` · `Delete` · `Unchanged`, each with the trigger key |
| `schedules[]` | per `(cron, timezone)`: same four actions |
| `publicRoutes` | `Replace(added[], removed[])` · `Unchanged` |
| `conflicts[]` | what would make the promote fail (`PUBLIC_ROUTE_TAKEN`, `TRIGGER_KEY_COLLISION`), each with its code and context |

For a **named alias** the plan carries only `alias`/versions/`settingsMissing` and marks the rest
`HttpOnly` (named aliases never reach trigger sync — `function-zones-and-aliases.md`).

Promote's behaviour is unchanged: `SETTINGS_MISSING` still 409s naming every key, conflicts still
fail the transaction. `PromoteResponse` is unchanged.

### M2.2 The validate route

`POST /api/functions/{address}/manifest/check`, body `{ "manifest": {…}, "alias": "live" }`
(`alias` optional, default `live`). Same permission and reach as the publish route. **Writes
nothing** — no version number is reserved, no row is locked for update.

Answer, always 200 when the function exists and the caller may publish (auth/404 as publish):

```json
{ "valid": false,
  "errors": [ { "code": "ENDPOINT_AUTH_REQUIRED", "message": "…", "details": {…} } ],
  "plan": null }
```
```json
{ "valid": true, "errors": [], "plan": { …PromotePlan as JSON… } }
```

- `errors` holds what publishing this manifest would reject: the `parseStrict` error (one — the
  parser stops at the first; collecting all is a backlog item, not this unit) and the
  `onPublish` cross-checks (event types, cron/zone, signing secret, warm capacity, hostname
  ownership). `valid` is `errors` empty.
- `plan` is computed only when `valid`: the plan for promoting this manifest, as the next version,
  to `alias`. `settingsMissing` non-empty does **not** make `valid` false — it is a promote
  precondition, reported in the plan so the author can set the keys first.
- **Result, not exceptions.** Add `Manifest.check(root, runtime, defaults, ceilings)` returning
  `Result<Manifest, UseCaseError>`: the one place that converts `parseStrict`'s exception into a
  value (documented as the bridge until the parser itself returns `Result`). `onPublish`'s checks
  get a non-throwing sibling returning a list of the same errors, used by both the throwing publish
  path and this route — one implementation of each check.
- Documented in `functions.openapi.json` (operation `checkManifest`, request/response schemas,
  `PromotePlan` components), exercised in `FunctionOpenApiCoverageTest`, present in
  `LockfileCoverageTest`'s function-route equality.

### M2.3 `fcdev fn validate`

`fn validate <address> --manifest <file> [--alias <name>]` calls the route and prints the errors,
or the plan as a readable list (`+ subscription order.created (create)`, `~ pool (update:
maxConcurrency)`, `- schedule "0 * * * *" (delete)`, `! settings missing: API_KEY`). `--output
json` prints the response body. Exit 0 when `valid`, 1 when not (settings missing alone is exit 0
with a warning line). Help text and `function-developer-surface.md` §2's table updated.

### M2 tests (mutant each; these are the load-bearing ones)

1. **The plan is what promote does.** For each scenario — first promote (everything `Create`), an
   identical re-promote (everything `Unchanged`), a manifest adding one subscription and removing
   another, a changed schedule, a changed pool limit, a changed public route — compute the plan,
   promote, and assert the rows actually created/updated/deleted match the plan's actions one for
   one (count by kind, and the keys). Then plan again: **every action `Unchanged`**. Mutant: make
   `apply` skip deletions → the post-promote plan shows `Delete` and the row count disagrees.
2. **The route writes nothing.** Row counts of `fn_versions`, trigger objects, subscriptions,
   scheduled jobs, pools, `fn_routes` before and after a check of a valid manifest are equal, and
   the next real publish still gets the next version number. Mutant: call `nextVersion` in the
   route → the version assertion fails.
3. `errors` for an invalid manifest carries the parser's code; for an unknown event type
   `EVENT_TYPE_NOT_FOUND`; `valid:false` and `plan:null` in both. A valid manifest missing a
   declared secret: `valid:true`, `plan.settingsMissing` names it, and a real promote still 409s
   `SETTINGS_MISSING`.
4. Named alias: plan has `HttpOnly` sections, `settingsMissing` still computed.
5. `fn validate` exit codes (0 valid, 1 invalid, 0 + warning for settings missing) against a real
   fcdev platform, `FnCliEndToEndTest` style.

## M3. `fcdev fn init`

`fn init <dir> [--runtime jvm|wasm] [--package <java.package>] [--name <artifactId>]
[--manifest-only]`. Local only — never contacts the platform.

- Writes a starter project derived from `examples/function-hello`: `pom.xml` depending on
  `flowcatalyst-function-api` at **fcdev's own version**, one handler class in `--package`
  (default `com.example.fn`), `manifest.json` with `"$schema"` set to
  `<platform-url>/api/schemas/function-manifest.json` (the `fn` global `--platform-url`, its
  default when absent), `runtime`, `entrypoint` matching the generated class, and one `platform`
  endpoint. Templates are fcdev resources, not copied from `examples/` at runtime.
- `--runtime wasm` or `--manifest-only` writes `manifest.json` only (there is no Wasm project
  template; say so in the help).
- Refuses when any file it would write already exists: exit 1, listing them; writes nothing.
- Prints next steps (`mvn package`, `fn publish target/<name>.jar --manifest manifest.json`).

Tests: the generated `manifest.json` passes `Manifest.parseStrict` for its runtime; the generated
handler **compiles** against the function-api classes on the test classpath (`javax.tools`
in-process, no Maven) and its class name equals the manifest's `entrypoint`; a pre-existing
`manifest.json` makes init exit 1 and leaves every file untouched (content hash); `--manifest-only`
writes exactly one file. Mutant each.

## M4. The SPA's manifest editor

A **Manifest editor** drawer on the function detail, opened three ways: "New manifest" (starts
from the same template as `fn init --manifest-only`), "Edit as new version" on a row of the
Versions tab (starts from that version's stored manifest), and "Import file".

- Two views of one model, switchable: a **form** covering every field of the schema (lists of
  endpoints, subscriptions, schedules, public routes, config, secrets, db, httpAllow; `cors` as a
  sub-form; a schedule's `payload` and nothing else as a JSON text field), and **JSON** (a
  textarea). Editing either updates the other; JSON that does not parse keeps the form read-only
  until fixed.
- **Validate** calls `checkManifest` and shows the errors (code + message, attached to the field
  when the code maps to one) or the plan in the same readable form as `fn validate`. The server is
  the only validator — no JSON Schema library in the SPA.
- **Export** downloads `manifest.json` (pretty-printed, `$schema` included, key order as the
  schema's). **Publish with this manifest** opens the existing publish drawer with the manifest
  pre-filled (the jar is still chosen there).
- Types from the regenerated `generated-functions` (never hand-written — the U8 convention test).
- No new dependency (PrimeVue + the existing vee-validate are enough).

Tests (vitest, mutant each): import the sample → export → deep-equal to the import (plus
`$schema`); a form edit changes the JSON view and vice versa; Validate renders a server
`ENDPOINT_AUTH_REQUIRED` against the endpoint's auth field and a plan's `Delete` rows; Publish
hands the exact edited manifest to the publish drawer. E2E (`e2e/tests/functions.spec.ts`):
open the editor from a version, add a subscription, Validate shows `Create`, publish, promote.

## Documentation

`function-developer-surface.md` §2 (`fn init`, `fn validate`), `function-ui.md` (the editor, its
tests), `function-invocation.md` §4 (plan/apply), `function-registry.md` §4.3 (`$schema`
accepted), `docs/function-service-overview.md` (one paragraph: how to author a manifest).
Backlog: `parseStrict` collecting every error instead of the first.
