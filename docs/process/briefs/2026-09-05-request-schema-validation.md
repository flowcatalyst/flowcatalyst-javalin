# Brief — request schema validation in huma's shape

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. Spec:
**`docs/spec/request-schema-validation.md`** — read all of it first; it is
the contract, down to the message strings. This closes the largest class of
parity diffs the S1 corpus found (every `create-missing-*` /
`validation-missing-*` step in `parity/scenarios/**`).

## Files you own

```
server/src/main/java/io/flowcatalyst/platform/shared/openapi/SchemaValidation.java   (the filter + the validator; split into two classes if it passes ~400 lines)
server/src/main/java/io/flowcatalyst/platform/shared/openapi/Lockfile.java           (extend: request-body schema and parameters per operation, $ref resolution)
server/src/test/java/io/flowcatalyst/platform/shared/openapi/SchemaValidationTest.java
server/src/test/java/io/flowcatalyst/platform/shared/openapi/SchemaValidationRouteTest.java
```

Plus **one line** in `server/src/main/java/io/flowcatalyst/server/Platform.java`:
register the filter right after `routes.before(authenticated(...))` (spec
§3 — after the authenticator, before every handler). And one line in
`server/src/test/java/io/flowcatalyst/platform/shared/TestHttp.java` next
to `ResponseDefaults.register(cfg)` so every API test sees the production
wire. Expect a handful of existing `*ApiTest`s to change: a test that posted
`{}` and asserted a domain code (`NAME_REQUIRED`) now gets `VALIDATION` —
update those assertions to the new envelope **and keep the domain-code
assertion by sending the value as an empty string**, which is what the spec
§3 ordering means. List every test you changed in the report.

## Design constraints

- No new dependency. The lockfile's schemas use: `type`, `required`,
  `properties`, `items`, `enum`, `format`, `minLength`, `maxLength`,
  `minimum`, `maximum`, `pattern`, `additionalProperties`, `$ref`,
  `nullable` (check with a one-off script over the lockfile before you
  start and put the list in the class doc). Implement exactly those; throw
  `IllegalStateException` at startup on any keyword you did not implement,
  so a lockfile bump cannot silently skip a rule.
- Resolve the lockfile once at startup into an operation table:
  `(METHOD, path template) → {bodySchema?, parameters[]}`; matching a
  request path to a template is the same rule `LockfileCoverageTest` /
  `Coverage.matchesTemplate` use (`{id}` matches one segment).
- The filter reads `ctx.body()` (Javalin caches it — verify with a test
  that the handler still receives the body), parses with `Json.MAPPER`,
  and on a parse failure does nothing (the handler's `INVALID_JSON` path
  stays as it is — spec §2).
- Messages: one static method per rule returning the exact string from spec
  §1's table; the test file has one test per row.
- Number rendering in messages follows Go's `%v` (spec §1 last paragraph).
- Locations: `body`, `body.<prop>`, `body.<prop>[<i>]`, `query.<name>`,
  `path.<name>`, exactly as spec §1.
- Case-insensitive property matching (spec §2), and the value the handler
  later reads must be the lockfile spelling — normalise the parsed body's
  keys? **No**: leave the body bytes alone; only the *validation* is
  case-insensitive (huma reads case-insensitively too, but the Java DTOs
  are strict — record that as `// SPEC?` if you find a route where it
  matters).

## Tests you owe (CLAUDE.md: works, not exists — break each, watch it fail)

- `SchemaValidationTest`: every message row; ordering (required first, then
  properties in schema order, recursing); `additionalProperties`; nullable
  vs `null`; an unknown keyword refused at startup.
- `SchemaValidationRouteTest` (TestHttp with the filter): `POST
  /api/event-types` `{}` → 400 `VALIDATION`, `details.errors[0]` =
  `{location: "body", message: "expected required property code to be
  present", value: {}}`; the same route with `{"code": "", "name": "x", …}`
  → the domain `CODE_REQUIRED` (ordering); a body with an unknown property
  → `unexpected property` at `body.<prop>`; `POST /auth/login` `{}` →
  untouched (`EMAIL_REQUIRED`); a `GET` with a bad `limit` query → 400
  `VALIDATION` at `query.limit`.
- The parity corpus is the acceptance test: with your worktree merged to
  main, `PARITY_GO_SRC=/Users/andrewgraaff/Developer/flowcatalyst-go mvn -q
  -pl parity -am test -Dtest=ParityRunTest -Dsurefire.failIfNoSpecifiedTests=false`
  must show **zero** `DIFF`s whose `/error` is `VALIDATION` on the Go side.
  Run it (it takes ~10 minutes; never two Maven runs at once) and put the
  remaining VALIDATION diffs, if any, verbatim in your report.

## Message-text alignment (same unit, mechanical)

While the corpus is in front of you, align these Java message strings to
Go's exactly (the codes already match; only the text differs). Change the
string, run the aggregate's `*ApiTest`, fix its assertion:

| Route / code | Go text |
|---|---|
| anchor-domains `INVALID_DOMAIN` (create) | `domain must be a valid DNS name (e.g. example.com)` |
| anchor-domains `INVALID_DOMAIN` (update) | `domain must be a valid DNS name` |
| auth-configs `INVALID_EMAIL_DOMAIN` | `emailDomain must be a valid DNS name` |
| auth-configs OIDC missing issuer | `OIDC provider requires oidcIssuerUrl` |
| auth-configs OIDC missing client id | `OIDC provider requires oidcClientId` |
| auth-configs duplicate | `Auth config for '<domain>' already exists` |
| service-accounts unknown webhook auth type | `unknown webhook auth type "<type>"` (double quotes) |

If a Go text is a *worse* message than Java's, still adopt it — the parity
oracle is Go — and say so in the report.

## Build

`export JAVA_HOME=$(mise where java)`; `mvn -q -pl server test -Dtest=…
-Dsurefire.timeout=600`; never `mvn install`; `-Werror`; Jackson 3
(`tools.jackson`, `asString()`). Full `mvn -q -pl server clean test` green
before you report. Commit on your branch; do not merge.

## Report

Files; each test and the rule it pins; the mutants you ran; every
`*ApiTest` assertion you changed and why; the corpus result (VALIDATION
diffs remaining, verbatim); `// SPEC?` lines; honest verdict.
