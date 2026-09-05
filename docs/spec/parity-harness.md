# Spec — platform parity harness (`parity/`)

The platform half of `docs/spec/dropin-verification.md`. That plan covers the
router (broker-action sequences, Phase A–C). This one covers everything the
lockfile and the auth surface serve: **the same requests, in the same order,
against Go and Java on the same seed, must produce the same responses**, and
every place they do not must be named, explained and ruled on.

Status 2026-09-05: design (orchestrator). Harness code is a Sonnet unit
(brief `docs/process/briefs/2026-09-05-p5-parity-harness.md`); the scenario
corpus is written in phases (§8) and reviewed like any port.

## 0. The question, and why tests on both sides do not answer it

Both suites are green. Each proves its implementation agrees with the prose
it was written from. Neither proves the two agree with each other: the Java
suite was written from the spec, the spec from the Go, and every hop is a
place for a quiet difference to hide — a default that parses back to the
wrong enum, a list ordered by a different column, a 403 where Go says 404,
a field Java never emits because no test asked for it.

The frontend and the three SDKs are compiled against Go's behaviour, not the
lockfile's schemas. A wire difference is a client bug on cutover day. So the
oracle is **Go's actual responses**, byte for byte after a small, explicit
normalisation — not a schema, not a test, not a reading of the source.

What this harness does not do: it does not decide who is right. A diff is a
finding. §9 says how each finding is closed.

## 1. Topology

```
                 ┌────────────── one embedded PostgreSQL (zonky, PG 18) ──────────────┐
                 │  seed  ──template──▶  parity_go       parity_java  ◀──template──   │
                 └──────────────────────────▲──────────────────▲─────────────────────┘
   Go fc-server (subprocess, port P_go) ────┘                  └──── Java Server (in-process, port P_java)
                          ▲                                                   ▲
                          └──────────── ParityRunner: scenario → both sides ──┘
                                                 │
                                        normalise, diff, report
```

- **One Postgres**, started by the harness with zonky (the same fixture
  `TestPg` and fcdev's `EmbeddedPg` already use). No Docker.
- **`seed` is Go-created.** The harness builds Go's `fcdev` and `fc-server`
  from `PARITY_GO_SRC` (default `../flowcatalyst-go`, ~6 s), runs
  `fcdev init --yes --database-url … --admin-email … --admin-password …
  --code parity --name Parity --root <scratch>` (goose migrations + anchor
  admin + default client + application; the `.env` it writes lands in the
  scratch dir and is ignored), then starts `fc-server` against `seed` with
  only the platform enabled, waits for `GET /health` = 200 (Go migrates and
  seeds *before* it listens, `cmd/fc-server/main.go`), and stops it. `seed`
  now holds exactly what a Go deployment holds on day one.

  *Amendment 2026-09-05 (found by the first run):* Go HEAD `cb83fd5` cannot
  bootstrap a fresh database at all — its seeder writes `schema_type =
  'JSON'` against the CHECK its own migration 051 added (`docs/backlog.md`,
  Go defect on record). `fcdev init` therefore fails at the first
  event-type spec version, after migrations, the platform application and
  the roles. The harness recognises exactly that failure (SQLSTATE 23514 on
  `chk_msg_event_type_spec_versions_schema_type`), fills the catalogue with
  the **Java** seeder (idempotent over Go's rows, `JSON_SCHEMA` per the
  seeder spec's ruling; no Flyway — the schema stays goose's), and runs
  `fcdev init` again, which now skips every event type and creates the
  admin. Any other init failure is still a hard stop. `seed` is Go-created
  in every row but the spec versions, and the run log says which path was
  taken. When Go fixes its seeder the first init succeeds and the
  workaround is never entered.
- **Two clones** by `CREATE DATABASE parity_go TEMPLATE seed` (and
  `parity_java`). Postgres template copies are byte-identical, so every
  seeded id and timestamp is the same on both sides — which is what lets the
  comparison treat uncaptured ids as significant (§5).
- **Go runs as a subprocess** on `parity_go`: the built `fc-server`, env from
  §2, stdout/stderr captured to the report directory. Readiness = `/health`.
- **Java runs in-process** on `parity_java`, the way `Main` does it and
  every API test does: `Migrator.migrate(ds)` (this is the *adoption* path —
  non-empty schema, no Flyway history — so the harness exercises the real
  Go-to-Java database handover for free), `new Seeder(ds).run()` (must be a
  no-op on Go-seeded rows: the seeder spec's byte-equality claim, tested here
  against a genuinely Go-made database), then
  `new Server(env, new Server.Mode.Platform(ds), Server.Spa.none(), registry).start()`
  with `FC_API_PORT=0`. In-process rather than the jar because the HTTP layer
  is the same Javalin either way, the start path is `Main`'s, and it keeps
  the harness a plain Maven module. The jlink/native artefacts get their own
  smoke in Phase 5's CI item, not here.
- Both sides are stopped at the end; the report survives.

## 2. Environment given to both sides

One map, passed to both. A knob one side does not know is harmless.

| Variable | Value | Why both |
|---|---|---|
| `FC_DATABASE_URL` | per side | — |
| `FC_API_PORT` | Go: a free port picked by the harness; Java: `0` | — |
| `FC_PLATFORM_ENABLED` | `true`; every other `*_ENABLED` `false` | platform only |
| `FC_JWT_SIGNING_KEY_PATH` | one RSA-2048 PKCS#8 PEM generated per run into the scratch dir | same `kid`, same JWKS, tokens comparable (auth-core §4) |
| `FLOWCATALYST_APP_KEY` | one 32-byte key, padded standard base64, per run | seeded encrypted columns readable on both sides (encryption.md) |
| `FC_JWT_ISSUER` / `FC_EXTERNAL_BASE_URL` | each side's own `http://127.0.0.1:<port>` | must differ; normalised to `«base»` (§5) |
| `FC_AUTH_ALLOW_TEST_HEADERS` | unset | the harness logs in for real |
| `FC_SMTP_HOST` | unset | both sides log mail instead of sending (Go `email.FromEnv`, Java `Notifications`); a secret that only travels by mail is unreachable in v1 (§8) |
| `FC_WEBAUTHN_RP_ID` / `FC_WEBAUTHN_ORIGINS` | `localhost` / each side's base URL | passkey ceremonies; origin differs per side and the software authenticator signs whichever it is given |
| rate limits, backoff | defaults | a limit that trips on one side and not the other **is a finding** |
| `FC_BOOTSTRAP_ADMIN_*` | `parity-admin@example.com` / a fixed passphrase (no identity words: `PASSWORD_CONTAINS_IDENTITY`) | the anchor login every scenario starts from |

## 3. Scenario format

JSON files under `parity/scenarios/<group>/<name>.json`, one scenario = one
ordered list of steps, run to completion on Go first and then on Java (each
side against its own clone, its own cookie jar, its own captured values).
No YAML, no new dependency; Jackson 3 is already there.

```json
{
  "name": "event-types crud",
  "covers": ["createEventType", "getEventType", "listEventTypes", "updateEventType", "deleteEventType"],
  "steps": [
    { "id": "login",
      "request": { "method": "POST", "path": "/auth/login",
                   "body": { "email": "${admin.email}", "password": "${admin.password}" } },
      "capture": { "token": "/accessToken" } },
    { "id": "create",
      "request": { "method": "POST", "path": "/api/event-types", "auth": "${token}",
                   "body": { "code": "parity-${run}", "name": "Parity", "applicationId": "${app.id}" } },
      "expect": { "status": 201 },
      "capture": { "etId": "/id" } },
    { "id": "list",
      "request": { "method": "GET", "path": "/api/event-types", "query": { "clientId": "${client.id}" }, "auth": "${token}" },
      "unordered": ["/items"] },
    { "id": "delete-twice",
      "request": { "method": "DELETE", "path": "/api/event-types/${etId}", "auth": "${token}" } }
  ]
}
```

- **`request`**: `method`, `path`, optional `query` (map), `headers` (map),
  `auth` (a bearer token; sugar for the `Authorization` header), and exactly
  one of `body` (JSON, sent as `application/json`) or `form` (map, sent as
  `application/x-www-form-urlencoded` — the OAuth token endpoint).
- **Substitution** `${name}` anywhere in strings: path, query, headers, body
  values. Undefined name = scenario error, never an empty string.
- **Captured values** are per side: `capture` maps a name to a JSON Pointer
  (RFC 6901, Jackson `at()`) into the response body. A missing pointer is a
  scenario error on that side. `capture` may also name a header:
  `{"loc": "header:Location"}`, one query parameter of the `Location` URL:
  `{"code": "location-param:code"}` (the authorization code), and a
  cookie: `{"sess": "cookie:fc_session"}`.
- **Built-ins**: `${admin.email}`, `${admin.password}`; `${run}` (one short
  random token per harness run, the same on both sides, so codes and names
  are unique across runs but identical across sides); `${client.id}`,
  `${app.id}`, `${admin.id}` (read from the `seed` database once, before the
  clones are made, so they are the same on both sides);
  `${totp:secretVar}` (an RFC 6238 code from a captured base32 secret, for
  the MFA scenarios; `${totp:secretVar:-1}` / `:+1` the adjacent step, so
  a confirm and a verify in one scenario present different codes inside
  the ±1 window and the replay guard can be pinned without waiting); `${pkce.verifier}` / `${pkce.challenge}` (one pair per
  run); `${b64url:var}` where a value needs re-encoding.
- **`expect.status`** is a sanity check, not the oracle: the step is marked
  `ERROR` on the side that disagrees, and that is reported separately from a
  parity `DIFF` (§6). Use it on the steps a later step depends on (a login
  that must succeed), not everywhere.
- **`unordered`**: pointers to arrays compared as multisets of their
  normalised elements. Default is ordered, because order is part of the
  contract (a list the frontend renders as-is).
- **`ignore`**: `[{"pointer": "/items/*/createdBy", "reason": "…"}]` —
  dropped from both sides before comparison. Each entry needs a reason; the
  reviewer treats an `ignore` the way a `@Disabled` is treated.
- **`covers`**: the lockfile `operationId`s the scenario claims. The runner
  checks the claim against what was actually requested (method + path
  template match) and fails the scenario on a false claim; §7 uses it.
- Cookies: a jar per side. Not the JDK `CookieManager`: `fc_session` is
  `Secure` and both sides serve plain loopback HTTP, so the JDK jar drops
  it on every request after login (RFC 6265, correctly). The harness's own
  jar carries whatever `Set-Cookie` sends and forgets a cookie set to an
  empty value — the trusted local client the harness is. Redirects are
  never followed; `Location` is compared after normalisation.
- Passkeys: the scenario step `"authenticator": "register" | "assert"` tells
  the runner to run the software authenticator (a copy of the server test's
  `SoftAuthenticator`, ES256 + CBOR) over the previous step's options and
  send its output as the body. One authenticator instance per scenario per
  side.

## 4. What is compared, per step

The **step record** on each side: `status`, the named headers below, the
body (JSON when the content type says so, else the raw text), and, for
bodies that are not JSON but are known (the QR PNG, the OpenAPI document),
their SHA-256.

On a **3xx** response only the status and the headers below are compared;
the body and `Content-Type` are not (Go's `net/http` writes an HTML stub,
Javalin a text one — neither is a contract).

Headers compared, by name, nothing else: `Content-Type`, `Location`,
`WWW-Authenticate`, `Retry-After` (presence only), `Cache-Control`,
`Set-Cookie` (cookie name + attributes; the value becomes `«cookie»`).
`Date`, `Server`, `Content-Length`, `Transfer-Encoding` and anything else
are never compared. The list lives in one constant with a comment per entry.

## 5. Normalisation, in this order

Applied to each side's record before the diff; the raw record is kept for
the report.

1. **Own captured values → `«name»`.** Every string equal to a value this
   side captured is replaced by the capture name, in bodies, headers and
   `Location`; a captured value of eight characters or more is also
   replaced where it is *embedded* in a longer string (an id inside an
   error message, a derived id like `<principalId>-role-0`) — the first
   run showed both shapes. Ids, tokens, codes and flow ids therefore compare by *role*,
   not by value. A value that *should* have been captured and was not shows
   up as a diff — that is the scenario author's cue, not a normalisation gap.
2. **Own base URL → `«base»`** (issuer, discovery document, redirect
   targets, JWKS `jku`).
3. **RFC 3339 timestamps → `«time»`**, whole-string match only, and a
   *numeric* member named `iat`, `exp`, `nbf` or `auth_time` (epoch
   seconds — introspection echoes them) likewise. `null`, absent and
   `«time»` stay three different things.
4. **JWS strings** (three base64url segments with a JSON header; on any
   one string this rule is tried *before* rule 1, otherwise a token that
   was also captured — the session cookie — would collapse to its capture
   name and never be compared as a structure) →
   `{"«jwt»": {"header": …, "claims": …}}` with `iat`, `exp`, `nbf`,
   `auth_time` → `«time»`, `jti` → `«id»`, `iss` through rule 2, and any
   claim equal to a captured value through rule 1. Token *structure* is
   compared on every step that carries a token; this is where the C-Q1 /
   userinfo class of defect lives, and an opaque `«token»` would hide it.
5. **Cookie values → `«cookie»`** (rule 4 has already compared the JWT
   inside, because `fc_session` is captured as a cookie in the login step);
   the attributes are compared as a set (RFC 6265 gives their order no
   meaning and the two HTTP stacks differ), and an `Expires=` value is
   `«time»`.
6. **`unordered`** arrays sorted by their normalised JSON text.
7. **`ignore`** pointers removed.

Not normalised, deliberately: uncaptured id-shaped strings (seeded ids are
identical on both sides by construction, §1); numbers; enum strings; the
`$schema` member huma emits on every model (whether Java emits it is an
owner question, §10 — until ruled, its absence is an accepted diff, not a
silent one); error envelopes (auth-core §5 names three; they must match
exactly).

## 6. Report and exit status

`parity/target/parity-report/`:

- `report.json`: per scenario, per step: `OK` | `ACCEPTED` | `DIFF` |
  `ERROR`, with the diff as a list of `{pointer, go, java}` and the raw
  records on anything that is not `OK`.
- `report.md`: the same, readable; one line per `OK`, the full diff
  otherwise; the coverage table from §7 at the end.
- `go.log` / `java.log`: the servers' output, for the reader chasing an
  `ERROR`.

**`parity/expected-diffs.json`** is the allow-list:
`{"scenario": "…", "step": "…", "pointer": "…", "reason": "…", "ruling": "A-22 | backlog#… | owner 2026-…"}`.
`scenario` and `step` may be `"*"`, `scenario` may end in `*` to match a
name prefix (`"audit-logs*"`), `pointer` may start with `**/` to match a
trailing segment at any depth or end in `/**` to match everything below a
prefix (`**/$schema` matches `/$schema` and
`/items/3/$schema`) — for a difference that is systemic, one entry with one
reason, not one per step. A diff matching an entry is `ACCEPTED`. An entry
that matched nothing in the run (wildcard entries included) is **stale and
fails the run** — on a full run; under a `PARITY_ONLY` filter the stale
check is skipped, since entries for scenarios not run cannot match — the file cannot rot, and a Java fix
that removes a difference has to remove its excuse too. No `ruling` field, no
entry (the reviewer rejects it).

Exit status non-zero on any `DIFF`, any `ERROR`, any stale entry, any false
`covers` claim, or coverage under the threshold. That is what makes it a
gate rather than a dashboard.

## 7. Coverage

Mirrors `LockfileCoverageTest`: the runner loads
`server/src/main/resources/openapi/openapi.lock.json`, takes the 245
`operationId`s, and marks each one hit when a step's method + path matches
its template. Plus the **outside-lockfile surface**, listed by hand in
`parity/surface.json` from auth-core §6.1/6.2/6.5, auth-identity §11, the BFF
and public routes: every `METHOD /path` there must also be hit. The report
prints both percentages; `REQUIRED_COVERAGE` starts at `0.0` and is raised
to `1.0` when §8 phase S3 lands, exactly as the lockfile test did.

## 8. The corpus, in phases

Each phase is a reviewed unit. A scenario is a specification argument
first: it says which behaviour it pins and which step would show a
regression. Every group scenario includes its **tenant-confinement** steps —
a second, non-anchor principal created through the API, logged in, trying
the same routes — because that is the part a wire diff would make
dangerous rather than merely wrong.

- **S0 — smoke** (lands with the harness): `/health`, login, `/api/me`,
  event-types CRUD including the second delete (404 shape), one validation
  error (400 shape), one unauthenticated call (401 shape), one
  cross-tenant read (403/404 shape).
- **S1 — one scenario per lockfile tag** (28 groups; three Sonnet agents,
  ~9 groups each, scenarios only, no harness changes): create → get → list
  → update → get → delete → get, each list with its filters, each documented
  4xx, confinement steps. Batch/bulk routes included.
- **S2 — auth surface**: login (right, wrong, backoff sequence and the 429
  envelope), refresh rotation and family revocation, logout, session
  history, client selection, change-password; OAuth client credentials,
  authorization code + PKCE end to end (authorize → code in `Location` →
  token → introspect → userinfo → revoke → introspect again), discovery and
  JWKS; MFA: TOTP enrol (secret captured from the enrolment response) →
  confirm → login gate → `${totp}` verify → recovery code → trusted device
  cookie → admin reset; passkeys: register → list → assert → revoke →
  assert. Service-account mint and use.
- **S3 — the remaining outside-lockfile routes** (BFF, public, portal
  admin routes, docs/openapi, monitoring) and the coverage threshold to
  `1.0`.
- **Later, needing infrastructure**: anything whose secret only travels by
  mail (password reset completion, e-mail PIN, invites) needs an SMTP sink
  both sides can be pointed at — a ~60-line listener in the harness that
  records the last message per recipient and exposes it as `${mail:addr}`;
  the OIDC bridge and portal SSO need the fake IdP from `OidcBridgeTest`
  hosted where Go can reach it too. Both are v2, listed so they are not
  forgotten, not scheduled.

## 9. What a diff means — triage rule

Every `DIFF` closes exactly one way, in the report's own words:

1. **Java defect** → fix Java, add or sharpen the unit test that should have
   caught it (CLAUDE.md: assert the behaviour, break it, watch it fail), rerun.
2. **Go defect** → `docs/backlog.md` entry with the scenario and step as the
   reproduction, an `expected-diffs.json` entry citing it, and the Java
   behaviour kept (the correctness-over-conformance rule).
3. **Deliberate ruling** already on record (`auth-core.md` §0.5,
   `auth-identity.md` §0.5, `CONVENTIONS.md`, the `dispatch-seam`
   deviations) → `expected-diffs.json` entry citing the ruling id.

Nothing is normalised away to make a run green. Normalisation (§5) is
fixed; only the allow-list grows, and every entry in it has a name.

## 10. Owner questions

- **`$schema` on responses.** huma's schema-link transformer puts a
  `$schema` URL on every JSON response Go sends; Java never emits it
  (checked 2026-09-05: no emitter in `server`; the frontend's generated
  types declare it optional; no SDK reads it). Is its absence a wire break
  for any client the owner knows of? Until ruled: one wildcard allow-list
  entry `**/$schema`, cited here, and a `docs/backlog.md` line.
- **Where the harness runs.** Locally on demand is enough for Phase 5. In CI
  it needs a Go toolchain on the runner; the module's JUnit entry is skipped
  unless `PARITY_GO_SRC` or `PARITY_GO_BIN` is set, so `mvn test` stays
  green without one.

## 11. Out of scope

The router (dropin-verification Phase A–C), performance, the frontend end to
end (its own plan: Playwright against Java serving the built SPA, through
every BFF and auth route), the jlink/native artefacts (a start-and-`/health`
smoke in CI, Phase 5's CI item).
