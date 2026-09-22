# Spec — the function API's OpenAPI document (owed since package B)

`function-api.md` §0 put every function route **outside the lockfile**: the lockfile is Go's
generated document, copied, never hand-edited (CONVENTIONS §7), and there is no Go for functions.
That left the function API with a wire contract in prose only. This is its document.

## 1. The document

`server/src/main/resources/openapi/functions.openapi.json` — **OpenAPI 3.1.0**, hand-authored, the
source of truth for the function surface (Java-first; nothing generates it, §3 keeps it honest).

- **Every** route registered by `FunctionApi`, `FunctionPolicyApi`, `FunctionDomainApi` and
  `FunctionControlApi` — `/api/functions…`, `/api/function-pools`, `/api/function-policies…`,
  `/api/function-domains…`, `/control/functions/…` — including the two of
  `function-artifact-upload.md` (`PUT …/artifacts/{digest}` with an `application/octet-stream`
  request body; `GET /control/functions/artifacts/{versionId}` with an octet-stream response).
- `operationId`s: lowerCamel verb-noun, unique (`listFunctions`, `publishFunctionVersion`,
  `promoteFunctionAlias`, `uploadFunctionArtifact`, `getDesiredState`, …). Tags: `functions`,
  `function-versions`, `function-config`, `function-policies`, `function-domains`,
  `function-control`.
- Schemas in `components.schemas`, named after the Java wire records (`FunctionResponse`,
  `PublishRequest`, `VersionResponse`, `Manifest`, `DesiredStateDocument`, `HeartbeatRequest`, …).
  **Every object schema sets `additionalProperties: false`** except where the wire genuinely is a
  free map (config values, manifest `config`/`secrets` declarations) — that is what makes §3's
  conformance test bite. `required` lists exactly the properties the server always writes; a
  property that is omitted-when-absent (`NON_ABSENT`) is optional, **not** nullable.
- One shared `ErrorResponse` (`{error, message, details?}`); every operation lists its non-2xx
  statuses with it, and its `description` names the `error` codes that status can carry, taken
  from the owning spec (`function-api.md`, `function-invocation.md`, `function-context.md`,
  `function-public-routes.md`, `function-artifact-upload.md`) and checked against the code.
- Security: `bearerAuth` (http bearer JWT) on everything; each operation's description states the
  permission (or, for `/control/functions`, the `platform:function-host` role) it requires.
- `info.description` says what is *not* here and where it is: the invocation surface
  (`/functions/{address}[:{version}]/{path}` on a function host — a passthrough to the function's
  own endpoints, `function-invocation.md`) and the platform's main API (`/api/openapi.json`).

## 2. Serving it

`GET /api/openapi-functions.json` — unauthenticated, `Group.NO_DB`, the resource's bytes verbatim,
registered beside `SpecRoutes` (same reasoning: tooling fetches it without a token). Not under
`/api/functions/…`: that prefix's next segment is an address. It joins
`LockfileCoverageTest`'s outside-lockfile list and `parity/surface.json` like every other
Java-only route. `docs/functions.md` links it.

No runtime request validation is added: `SchemaValidation` validates lockfile operations in
huma's shape for Go parity, and the function routes already answer malformed input with their own
use-case errors. Changing that is a behaviour change, not documentation.

## 3. Keeping it honest — the tests are the point

A hand-written OpenAPI document is wrong within a month unless something fails when it drifts.

| # | Behaviour | Mutant |
|---|---|---|
| O1 | **Coverage both ways**: the set of `METHOD path` registered under the function prefixes (from the real route registry, as `LockfileCoverageTest` obtains it) **equals** the document's operations — a route without an operation fails, and so does an operation without a route | delete one operation from the document; register an extra route in the test's own registry copy (or prove it by deleting a path and seeing the named route in the failure) |
| O2 | **Responses conform**: one scenario test drives the real API in-process (own `TestPg` database; `file://` blob store; signatures off) through **every operation at least once on its success path** — create → policy → upload → publish → list/get → heartbeat → ready → promote → status → config/secret → domains → desired state → events → download → retire → delete — and validates each actual response body against the document's schema for that operation and status. Octet-stream responses are checked for content type only. A test-side table maps each call to its `operationId`; the test **fails if any operation in the document was never exercised** | remove a property from a response schema (an undocumented field must fail, via `additionalProperties:false`); add a `required` property the server does not write; leave one operation out of the scenario |
| O3 | **Requests conform**: every JSON request body the scenario sends validates against the operation's request schema — so the documented request shape is one the server demonstrably accepts | make a sent field's schema type wrong |
| O4 | **Errors conform**: at least one refusal per route family (404 unknown function, 403 missing permission, 409/422 from publish/promote, 503 store-not-configured) validates against `ErrorResponse`, and the returned `error` code appears in that operation+status's description | remove the code from the description |
| O5 | the document is valid OpenAPI 3.1 structurally: unique `operationId`s, every `$ref` resolves, every path parameter in a template is declared and `required`, every operation has at least one 2xx response | break a `$ref` |
| O6 | `GET /api/openapi-functions.json` serves the resource's exact bytes, without a token | — |

**2026-09-22 (function backlog, unit S)**: three new operations join the scenario —
`listFunctionPolicies` (`GET /api/function-policies`, S2) and `getFunctionDomain` (`GET
/api/function-domains/{hostname}`, S3) are driven once each on their success path; `getFunctionConfig`
/ `setFunctionConfig` / `listFunctionSecrets` gain a `version` query parameter and a `declaredBy`
response field (S1, `function-context.md` §1) documented in place, no new operation. `O4`'s refusal
set gains two, both against `publishFunctionVersion`'s `400`: a manifest with an unknown key
(`MANIFEST_UNKNOWN_FIELD`) and one with `runtime: "cobol"` (`RUNTIME_INVALID`) — the mutant is the
same as any other O4 row, remove either code from the description. `publishFunctionVersion`'s `400`
description names every code the operation actually throws (S4), read from `PublishVersion` and
`Manifest.parseStrict`; `function-registry.md` §4.3's table was reconciled with the same reading.

The validator: reuse the in-house `shared/openapi/SchemaValidator` if it can be pointed at a second
document cheaply (it is written against `Lockfile`; a small generalisation — a `Lockfile`-like view
over any document — is acceptable, with `LockfileCoverageTest`/`SchemaValidationTest` still green).
If it cannot express something this document needs (`oneOf` for the sealed owner, `const`), either
author the schema within the keywords it implements or add the keyword **with its own test** — do
not add a validation dependency to any pom.
