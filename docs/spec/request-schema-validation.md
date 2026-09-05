# Spec — request schema validation in huma's shape

Found by the parity harness on 2026-09-05 (S1-A and S1-C, every aggregate):
Go validates a request against the lockfile's schema **before any handler
runs** and answers a missing required property, a wrong type, an unknown
enum value or a broken format with one envelope the SPA understands field
by field. Java only has the domain checks inside the operations, so the same
request gets a different code, a different message and no per-field
details. The SPA reads those details (`frontend/src/api/client.ts`,
`error.details.errors[]` → "body.redirectUris: …") and shows them to the
user. This is a drop-in gap on every write route.

## 1. The wire contract (Go: `httpcompat.newError`, huma `validate.go`)

Status **400**. Body:

```json
{
  "error": "VALIDATION",
  "message": "validation failed",
  "details": {
    "errors": [
      { "location": "body", "message": "expected required property name to be present", "value": { "code": "x" } },
      { "location": "body.retries", "message": "expected integer", "value": "three" }
    ]
  }
}
```

- `error` is always `VALIDATION`, `message` always `validation failed`.
- `details.errors` lists **every** failure found (huma does not stop at the
  first), in schema order: for an object, **one alphabetical pass over the
  property names** (`schema.go` sorts them once), emitting a required-miss or
  recursing at each name in turn — *not* "required first, then properties"
  as the first draft of this spec said (corrected 2026-09-06 against
  `huma/v2@v2.38.0`). The committed lockfile is written by Go's
  `encoding/json`, which sorts object keys, so walking it in document order
  reproduces this.
- `location` is the JSON path of the *validated value*: `body` for the
  whole body, `body.name` for a property, `body.items[2].code` for an array
  element's property; query parameters are `query.<name>`, path parameters
  `path.<name>`. A missing required property is reported at the **object's**
  location (`body`), with the object as `value`.
- `value` is the offending value verbatim (the object for a missing
  property, the string for a wrong type). Omitted only when Go's value is
  nil.

Messages, exactly (huma `validation/messages.go`, `schema.go`):

| Check | Message |
|---|---|
| missing required property (body) | `expected required property <name> to be present` |
| missing required **query** parameter | `required query parameter is missing` at `query.<name>`, value `""` — parameter binding is a different code path (`huma.go`) from body validation |
| unknown property (schema `additionalProperties: false`) | `unexpected property` (location = that property) |
| type | `expected string` / `expected integer` / `expected number` / `expected boolean` / `expected array` / `expected object` |
| `enum` | `expected value to be one of "a, b, c"` (values joined by `, `) |
| `minLength` / `maxLength` | `expected length >= N` / `expected length <= N` |
| `minimum` / `maximum` / exclusive | `expected number >= N` / `<= N` / `> N` / `< N` |
| `multipleOf` | `expected number to be a multiple of N` |
| `minItems` / `maxItems` | `expected array length >= N` / `<= N` |
| `uniqueItems` | `expected array items to be unique` |
| `pattern` | `expected string to match pattern <pattern>` |
| `format: date-time` | `expected string to be RFC 3339 date-time` |
| `format: date` / `time` | `expected string to be RFC 3339 date` / `time` |
| `format: email` | `expected string to be RFC 5322 email: <go error text>` — only the prefix is contractual; the detail text after `: ` is Go's `net/mail` wording, compared loosely |
| `format: uri` | `expected string to be RFC 3986 uri: <detail>` (same loose rule) |
| `format: uuid` | `expected string to be RFC 4122 uuid: <detail>` |
| `format: hostname` | `expected string to be RFC 5890 hostname` |
| `format: ipv4` / `ipv6` | `expected string to be RFC 2673 ipv4` / `RFC 2373 ipv6` |
| `minProperties` / `maxProperties` | `expected object with at least N properties` / `at most N properties` |
| `dependentRequired` | `expected property <a> to be present when <b> is present` |

Numbers in messages render the way Go's `%v` renders the schema value
(`3`, not `3.0`, for an integer schema; `0.5` for a float).

## 2. What is validated, and against what

The lockfile on the classpath (`server/src/main/resources/openapi/openapi.lock.json`)
is the schema source — the same document Go generated its validator from.
For an incoming request to a lockfile operation (method + path template):

1. `requestBody.content["application/json"].schema` (resolving `$ref`
   into `components.schemas`) against the parsed JSON body. A body that is
   not JSON at all stays `INVALID_JSON` (that is a parse error, not a
   schema error, on both sides).
2. Each `parameters[]` with `in: query` / `in: path` that carries a schema:
   type (`integer`, `number`, `boolean`, `enum`), `minimum`/`maximum`,
   `format`. Location `query.<name>` / `path.<name>`. A required query
   parameter that is absent is `expected required property <name> to be
   present` at location `query`.

Nullable: the lockfile marks optional members by omission from `required`,
and huma treats an explicit JSON `null` on a non-nullable typed property as
a type error (`expected string`). Match that.

`readOnly` members (`$schema`, ids, timestamps) present in a request are
ignored by huma (`write only property is non-zero` is the only
read/write check, and only for `writeOnly` — none in this lockfile).

`additionalProperties`: **read the keyword as the lockfile has it.** The
first draft of this spec assumed `false` everywhere; the real lockfile sets
`true` on every top-level create/update request (`httpcompat.RelaxRequestBodies`
— Go relaxed them on purpose, its doc comment calls unknown-field rejection
"the #1 recurring parity bug class") and `false` only on the nested sync
item schemas. So an unknown top-level field is *accepted* on both sides and
`unexpected property` fires only where the lockfile says so. Case: huma matches property
names case-insensitively when `ValidateStrictCasing` is false (the default)
— a body with `Name` satisfies `name`. Match that too, and read the value
under the lockfile's spelling.

Routes outside the lockfile (`/auth/*`, `/oauth/*`, `/bff/*`, `/portal/*`,
`/api/me*`, `/api/public/*`) are not schema-validated on either side.

## 3. Where it runs

A `before` filter on the platform API, after the authenticator and before
any handler: match the request to a lockfile operation, validate, and on
failure write the §1 envelope and skip the remaining handlers. Unmatched
routes pass through untouched. The filter reads the body once and leaves
it readable for the handler (Javalin buffers `ctx.body()`).

Ordering relative to authentication is Go's: huma validates **after** the
authenticator group's middleware, so an unauthenticated caller is refused
before its body is inspected. Ordering relative to the handler's own
domain checks: schema first, so a body missing `name` never reaches
`NAME_REQUIRED` — the domain codes stay reachable only for values that
pass the schema (an empty string, a wrong shape a regex would catch), which
is exactly what the parity runs showed on the Go side.

## 4. What it replaces, and what it does not

The domain validations (`X_REQUIRED` for a blank string, format rules,
business rules) stay: they answer the requests the schema lets through. The
DTO records' own `null` handling stays. Nothing about responses changes.

## 5. Tests

- The message catalogue: one test per row of §1's table over a hand-built
  schema, asserting the exact message text, the location and the value.
- Ordering: an object missing two required properties and carrying a wrong
  type reports three entries in schema order.
- The envelope through a real route: `POST /api/event-types` with `{}`
  answers 400 `VALIDATION` with `details.errors[0].location == "body"` and
  the message naming `code`; the same request with `{"code":""}` reaches
  the domain check and answers `CODE_REQUIRED` (proves ordering §3).
- `additionalProperties`: an unknown field is refused; a case-variant of a
  known field is accepted and read.
- A non-lockfile route is untouched: `POST /auth/login` with `{}` keeps its
  own `EMAIL_REQUIRED`.
- The parity corpus is the acceptance test: every `VALIDATION` diff in the
  S1 reports must close without an allow-list entry.

## 6. Owner note

Nothing to rule: this is Go's observable behaviour, the SPA depends on it,
and the lockfile is already the authority Java is written from. The one
judgment call — matching huma's case-insensitive property names — follows
"the SPA never sends a case variant, so the lenient rule cannot hurt and the
strict one could".
