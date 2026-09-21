# Spec — function registry: schema, values, entities, repositories (work package A)

Design: `docs/function-runner-plan.md` (decisions in its §10). Build order:
`docs/function-runner-workplan.md` §2 A. This spec is the contract for package A only — no routes,
no operations, no events, no host. Those are B–F and get their own specs.

There is no Go for this. The function service is Java-first; nothing here is checked against
`../flowcatalyst-go`, and the tables are Java-only (§2.1).

> **Amended by `function-invocation.md`.** §2's `fn_routes` shape, all of §4
> (`Manifest`'s `triggers`), and §6.6 (`FunctionRoute`) are superseded by that
> spec's §3-§5: a function is always invoked over HTTP, the manifest carries
> `endpoints`/`subscriptions`/`schedules`/`public` instead of `triggers`, and
> `fn_routes` is public-only (`hostname` NOT NULL, no `method`). This document
> is left as the historical record of package A's original design;
> `function-invocation.md` is the contract the code implements. `fn_trigger_objects`
> (invocation spec §4) is a genuinely new table, not a reshape of anything here.

## 0. Where this departs from the workplan, and why

| Workplan says | This spec says | Why |
|---|---|---|
| §0: do not touch `server/.../server/*`, the Vert.x conversion owns it | `Env.java` is edited normally | the conversion merged into `main` at `da06195`; §0 is history |
| §1 layout `entity/ repository/ service/ api/ events/` | `CONVENTIONS.md` §2 layout: records and repositories flat in `platform/function/`, later `operations/` and `api/` | one layout for every aggregate; the convention tests assume it |
| states `published`, `ready`, `retired`; runtimes `jvm`, `wasm` | stored as the enum constant name: `PUBLISHED`, `READY`, `RETIRED`, `JVM`, `WASM` | CONVENTIONS §2 "the constant name is the stored/wire string", and V6/V7's check constraints are upper-case. The manifest reader accepts either case (§4.4) |
| `fn_functions` carries "default limits" | it does not | limits live in the version's manifest, frozen at publish (§4.6). A second, mutable place to set the same limit is a knob with no owner |
| `fn_signer_policies` "(rename if cleaner)" | `fn_client_policies`, one row per client: signers and ceilings | the workplan's own suggestion |
| `fn_aliases … optional weight` | no weight column | weighted aliases are P4; adding a nullable column then is additive |
| `fn_routes` | hostname, method, path pattern, function — nothing else | auth mode, CORS, body cap and timeout stay in the manifest, which desired state already carries; two copies would drift |

## 1. Package and files

`server/src/main/java/io/flowcatalyst/platform/function/` (tests mirror it under `src/test`):

| File | Kind |
|---|---|
| `DnsLabel`, `FunctionAddress`, `FunctionAddressPattern` | parser records (§3) |
| `Hostname`, `RoutePattern`, `HttpMethod` | parser records / enum (§5) |
| `Runtime`, `FunctionStatus`, `AuthMode` | enums with `parse` / `parseStrict` |
| `FunctionLimits`, `ClientCeilings` | limit policy (§4.6) |
| `Manifest` (+ nested `Trigger`, `HttpRoute`, `Cors`, `DbRef`, `Limits`) | parsed manifest (§4) |
| `Function` (+ nested `FunctionAlias`), `FunctionRepository` | aggregate: function + its aliases (§6.1) |
| `FunctionVersion` (+ sealed `VersionState`), `Digest`, `SignerIdentity`, `FunctionVersionRepository` | aggregate (§6.2) |
| `FunctionHost` (+ `LoadedVersion`, sealed `LoadState`), `FunctionHostRepository` | §6.3 |
| `ClientPolicy` (+ `SignerIdentity`), `ClientPolicyRepository` | §6.4 |
| `FunctionDomain` (+ sealed `Verification`), `FunctionDomainRepository` | §6.5 |
| `FunctionRoute`, `FunctionRouteRepository` | §6.6 |

`EntityType` gains `FUNCTION("fnc")`, `FUNCTION_VERSION("fnv")`, `FUNCTION_DOMAIN("fnd")`,
`FUNCTION_ROUTE("fnr")`. Aliases, hosts and client policies have natural keys and no TSID.

## 2. Schema — `V13__functions.sql`

All ids `VARCHAR(17)`. All timestamps `TIMESTAMPTZ NOT NULL DEFAULT NOW()` unless marked nullable.
`CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` throughout, like V9. **Every constraint
and index is named explicitly and its name starts with the table's name or `idx_<table>_`** — the
fingerprint filter (§2.1) depends on it.

`LABEL` below is the check `col ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'` (§3.1, the same rule as
`DnsLabel`; the two must agree and a test pins that they do — §8 M1).

**`fn_functions`** — `id` pk · `application_id` not null · `application_code VARCHAR(63)` not null
LABEL · `service_name VARCHAR(63)` not null LABEL · `name VARCHAR(63)` not null LABEL ·
`client_id VARCHAR(17)` **null** (null = a platform-owned function, ruling R2) · `runtime VARCHAR(10)` not null check in (`JVM`,`WASM`) ·
`description VARCHAR(1000)` null · `status VARCHAR(20)` not null default `ACTIVE` check in
(`ACTIVE`,`DISABLED`) · `created_at` · `updated_at`.
Unique `(application_id, service_name, name)`; unique `(application_code, service_name, name)` —
the second is the address lookup's index.
`application_code` is a copy of the application's code. It is safe to copy because an application's
code is immutable (`application.md`), and it is what lets the database check all three segments of
the address and lets a read by address avoid a join. No foreign key to `app_applications` or
`tnt_clients` — no `fn_` table references a table outside `fn_`, as `portal_apps.client_id` does not.

**`fn_versions`** — `id` pk · `function_id` not null references `fn_functions(id) ON DELETE CASCADE` (ruling R4) · `version INT`
not null check `> 0` · `artifact_ref VARCHAR(1000)` not null · `digest VARCHAR(71)` not null check
`~ '^sha256:[0-9a-f]{64}$'` · `signature_bundle TEXT` null · `signature_bundle_ref VARCHAR(1000)`
null · `signer_issuer VARCHAR(500)` null · `signer_subject VARCHAR(1000)` null · `manifest JSONB`
not null · `state VARCHAR(20)` not null check in (`PUBLISHED`,`READY`,`RETIRED`) · `published_by
VARCHAR(17)` not null · `published_at` · `ready_at` nullable · `retired_at` nullable.
Checks: `state <> 'READY' OR ready_at IS NOT NULL`; `state <> 'RETIRED' OR retired_at IS NOT NULL`.
Unique `(function_id, version)`; unique `(function_id, digest)`. Index `(function_id, state)`.
The signer and bundle columns are nullable because fcdev runs with signatures off (design §8);
whether production may ever leave them null is package C's rule, not the table's.

**`fn_aliases`** — pk `(function_id, alias)` · `function_id` references `fn_functions(id) ON DELETE
CASCADE` · `alias VARCHAR(63)` LABEL · `version_id` not null references `fn_versions(id) ON DELETE CASCADE` ·
`updated_by VARCHAR(17)` not null · `updated_at`.

**`fn_hosts`** — `id VARCHAR(100)` pk (the host names itself) · `pool VARCHAR(63)` not null LABEL ·
`state VARCHAR(20)` not null check in (`ACTIVE`,`DRAINING`) · `loaded JSONB` not null default
`'[]'` · `started_at` · `last_heartbeat`. Index `(pool, last_heartbeat)`.

**`fn_client_policies`** — `client_id VARCHAR(17)` pk (a client id, or the reserved value `PLATFORM` for platform-owned functions — a primary key cannot be null, and no TSID is ever `PLATFORM`; the repository is the only place that spells it, §6.4) · `signers JSONB` not null default `'[]'` ·
`max_duration_ms INT` null · `max_concurrency INT` null · `max_wasm_memory_mb INT` null ·
`max_db_pool_size INT` null · each ceiling check `IS NULL OR > 0` · `created_at` · `updated_at`.

**`fn_domains`** — `id` pk · `client_id` **null** (null = the platform's) · `hostname VARCHAR(253)` not null check
`hostname = lower(hostname)` · `verification_token VARCHAR(64)` not null · `verified_at` nullable ·
`created_at`. Unique `(hostname)`. Index `(client_id)`.

**`fn_routes`** — superseded by `function-invocation.md` §3, §6.6: `hostname` is now NOT NULL (every
row is public — a private call needs no route row at all) and there is no `method` column;
`path_pattern` is renamed `path_prefix`, unique on `(hostname, path_prefix)` alone. The shape below
is the original design, kept for history only:
~~`id` pk · `function_id` not null references `fn_functions(id) ON DELETE CASCADE` ·
`hostname VARCHAR(253)` **null** (null = private-only route, design §4a) · `method VARCHAR(10)` not
null check in (`GET`,`HEAD`,`POST`,`PUT`,`PATCH`,`DELETE`,`OPTIONS`) · `path_pattern VARCHAR(1024)`
not null · `created_at`.
Unique `(hostname, method, path_pattern)` — Postgres treats nulls as distinct, so this constrains
public routes only, across all functions, which is the conflict the design wants rejected. Private
routes are scoped to their function: unique index `(function_id, method, path_pattern) WHERE
hostname IS NULL`. Index `(function_id)`.~~

### 2.1 Java-only tables

`function-invocation.md` §4 adds an eighth Java-only table, `fn_trigger_objects` (not a reshape of
anything here — a genuinely new table), and widens one Go-shared constraint
(`chk_msg_subscriptions_source`) on a table that is NOT Java-only; see that spec §4.1.

`SchemaFingerprintTest.JAVA_ONLY_TABLES` gains the seven tables below, each naming this spec. Its
`isJavaOnly` matcher is substring-based and was written for one table; with the naming rule above it
is enough that a line's table column equals a Java-only table **or** its object name starts with
`<table>_` or `idx_<table>_`. Tighten it to that — and keep `mail_outbox` passing. `GoAdoptionTest`
is updated the same way it was for V8: V13 is a genuine addition on a Go-adopted database, created
once.

## 3. Addresses

### 3.1 `DnsLabel`

`record DnsLabel(String value)`, `static parse(String field, String raw)`. **No normalisation**: the
input is not trimmed and not lower-cased; anything that is not already a label is rejected. (An
address is an identity used in router targets, permissions and metrics; two spellings of one
identity is how `Billing.Invoices.Create` and `billing.invoices.create` become two grants.)

Rule: 1–63 characters of `[a-z0-9-]`, first and last not `-`.
Error: validation `LABEL_INVALID`, message `"<field> must be a DNS label: 1-63 characters of a-z,
0-9 and '-', not starting or ending with '-'"`. `null` is rejected the same way.

| Rule | Accepted | Rejected |
|---|---|---|
| charset | `billing`, `a`, `0`, `inv-2`, `9lives` | `Billing`, `in_voices`, `a.b`, `a b`, `é` |
| length | 63 × `a` | empty, 64 × `a` |
| hyphen edges | `a-b`, `a--b` | `-a`, `a-`, `-` |
| whitespace | — | ` a`, `a ` (not trimmed) |
| absent | — | `null` |

### 3.2 `FunctionAddress`

`record FunctionAddress(DnsLabel application, DnsLabel service, DnsLabel name)`;
`static parse(String raw)`; `render()` and `toString()` give `app.service.name`;
`static of(DnsLabel, DnsLabel, DnsLabel)`.

`parse` splits on `.` and requires **exactly three** segments, each a `DnsLabel`. Every failure —
wrong count, bad segment, empty segment, null — is validation `ADDRESS_INVALID` with one message:
`"address must be app.service.function: three DNS labels separated by '.'"`. The parser never
supplies the `default` service; that is a tooling default (design §3.1).

| Rule | Accepted | Rejected |
|---|---|---|
| three parts | `billing.invoices.create` | `billing.create` (two), `a.b.c.d` (four), `billing` |
| empty part | — | `billing..create`, `.b.c`, `a.b.`, `` |
| segment rule | `b-1.inv-2.c-3` | `Billing.invoices.create`, `billing.in_voices.create`, `a.-b.c` |
| wildcards are not addresses | — | `billing.invoices.*`, `*.b.c` |
| whitespace / absent | — | ` a.b.c`, `a.b.c `, `null` |

### 3.3 `FunctionAddressPattern`

What a permission grant, a list filter or a status view names. Sealed:
`Exact(FunctionAddress)` · `Service(DnsLabel application, DnsLabel service)` (`app.service.*`) ·
`Application(DnsLabel application)` (`app.*`). `static parse(String raw)`; `render()`;
`boolean matches(FunctionAddress)`; error `ADDRESS_PATTERN_INVALID`, one message.

A bare `*`, a wildcard anywhere but last (`a.*.c`, `*.b.c`), a partial-segment wildcard
(`billing.inv*`), and `a.b.c.*` are rejected. "Everything" is the absence of a filter, not a pattern.

`matches` compares **whole segments** and never prefixes of strings; `matches(null)` is `false`.

| Rule | Pattern | Address | Result |
|---|---|---|---|
| exact | `billing.invoices.create` | `billing.invoices.create` | match |
| exact | `billing.invoices.create` | `billing.invoices.created` | no |
| service | `billing.invoices.*` | `billing.invoices.create` | match |
| service, whole segment | `billing.invoices.*` | `billing.invoices-v2.create` | no |
| service, other app | `billing.invoices.*` | `billing2.invoices.create` | no |
| application | `billing.*` | `billing.invoices.create` | match |
| application | `billing.*` | `billing.payments.refund` | match |
| application, whole segment | `billing.*` | `billing-eu.invoices.create` | no |
| null | any | `null` | no |

The repository turns a pattern into column equalities (§6.1), never a `LIKE`.

## 4. Manifest

> **Superseded by `function-invocation.md` §3.** `Trigger` (`event`/`schedule`/`http`) is gone;
> the manifest carries `endpoints` (the HTTP surface + auth), `subscriptions`, `schedules` and
> `public` instead. §4.2 (the two readers), §4.4-§4.6 (enums, defaults, limit resolution) still
> apply to the amended shape; §4.1 and §4.3's `Trigger`/`HttpRoute` rows below are historical.

### 4.1 Shape (the wire and stored JSON) — superseded, see above

```json
{
  "runtime": "jvm",
  "entrypoint": "com.acme.billing.CreateInvoice",
  "pool": "default",
  "warm": false,
  "limits": { "maxDurationMs": 30000, "maxConcurrency": 32 },
  "triggers": [
    { "type": "event", "eventType": "billing:invoices:invoice:created", "messageGroupKey": "invoiceId" },
    { "type": "schedule", "cron": "0 * * * *", "timezone": "UTC" },
    { "type": "http", "routes": [
      { "hostnames": ["api.acme.com"], "methods": ["GET", "POST"], "path": "/invoices/{id}",
        "auth": "bearer", "cors": { "origins": ["https://app.acme.com"] },
        "maxBodyBytes": 1048576, "timeoutMs": 10000 } ] }
  ],
  "config": ["INVOICE_PREFIX"],
  "secrets": ["billing/stripe-key"],
  "db": [ { "name": "main", "secretRef": "billing/dsn", "poolSize": 4 } ],
  "httpAllow": ["api.stripe.com"]
}
```

(`limits.wasmMemoryMb` exists for a `wasm` function only — §4.3 `LIMIT_NOT_APPLICABLE`.)

`Manifest` is a record; `Trigger` is sealed (`Event | Schedule | Http`); every list is
`List.copyOf`'d; absent optional strings are `null`, never `""`.

### 4.2 Two readers

- `Manifest.parseStrict(JsonNode, Runtime functionRuntime, FunctionLimits defaults, ClientCeilings
  ceilings)` — the publish reader. Validates everything in §4.3–4.6, **rejects unknown keys at every
  level**, and returns a manifest with every limit filled in (§4.6).
  An unknown key is rejected because the manifest is part of an immutable version: `maxConcurency`
  silently ignored is a function running at a limit nobody chose, for ever.
- `Manifest.readStored(JsonNode)` — the repository's reader. Ignores unknown keys, applies no
  ceilings, never throws on a value a newer writer might add that it does not understand *in an
  optional position*; it throws `IllegalStateException` naming the version only when `runtime` or
  `entrypoint` is unreadable. `fn_versions.manifest` is a foreign shape (CONVENTIONS §8).

Both read a Jackson tree from `Json.MAPPER`; no second mapper, no annotations-driven binding for the
strict path (unknown-key rejection is per level and must name the key). `toJson()` writes the shape
of §4.1 with `Json.MAPPER`; `readStored(parseStrict(x).toJson())` round-trips to an equal record.

### 4.3 Rules (each is one validation code; the first failure wins)

| Code | Rule |
|---|---|
| `MANIFEST_REQUIRED` | the node is null / not an object |
| `MANIFEST_UNKNOWN_FIELD` | any key not in §4.1, at any level; message names the JSON path (`limits.maxConcurency`). A trigger's allowed keys are **those of its own type** — `type` is read first, then `cron` inside an `event` trigger is an unknown field, not an ignored one |
| `MANIFEST_INVALID` | `warm` present and not a boolean |
| `RUNTIME_INVALID` | `runtime` absent or not `jvm`/`wasm` (case-insensitive) |
| `RUNTIME_MISMATCH` | `runtime` differs from the function's runtime |
| `ENTRYPOINT_REQUIRED` | absent/blank. JVM: must be a binary class name (`^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$`), else `ENTRYPOINT_INVALID`. Wasm: an export name `^[A-Za-z_][\w]*$` |
| `POOL_INVALID` | `pool` present and not a `DnsLabel`. Absent ⇒ `default` (`Manifest.DEFAULT_POOL`) |
| `LIMIT_INVALID` | a limit present and not a positive integer that fits an `int` (zero, negative, fractional, string, `5000000000`); message names the limit. The same "fits an `int`" rule holds for every integer in the manifest |
| `LIMIT_OVER_CEILING` | a limit above the client's ceiling; message names the limit, the value and the ceiling |
| `LIMIT_NOT_APPLICABLE` | `wasmMemoryMb` on a JVM function |
| `TRIGGER_INVALID` | `type` absent/unknown; `event` without `eventType`; `schedule` without `cron`; `http` with no routes |
| `TRIGGER_DUPLICATE` | two `event` triggers with the same `eventType`; more than one `http` trigger |
| `ROUTE_INVALID` | no methods; an unknown method; a method or a hostname listed twice in one route (each pair becomes an `fn_routes` row, and a duplicate would be a unique violation at publish, not a validation error); `path` not a `RoutePattern` (§5.2); a hostname not a `Hostname` (§5.1); `auth` not `bearer`/`none`; `maxBodyBytes`/`timeoutMs` not positive |
| `ROUTE_AMBIGUOUS` | two routes in this manifest that share a method and whose patterns are ambiguous (§5.3); message names both patterns. **Hostnames do not separate routes within one function**: the private entry (`/fn/{address}/…`, design §4a) reaches a function by address with no hostname, so every route of the function is a candidate there. A path served on two hostnames is one route listing both |
| `DB_INVALID` | `name` not a `DnsLabel`, blank `secretRef`, duplicate `name` |
| `CONFIG_INVALID` | blank or duplicate entries in `config`, `secrets` or `httpAllow` |

`eventType` and `cron` are carried as strings here. Whether the event type exists and whether the
cron parses are TriggerSync's checks (package B), against the aggregates that own those formats.

`timeoutMs` on a route is also held to the `maxDurationMs` ceiling (`LIMIT_OVER_CEILING`).
`db[].poolSize` is held to the `dbPoolSize` ceiling; absent ⇒ the default.

### 4.4 Enums

`Runtime { JVM, WASM }`, `AuthMode { BEARER, NONE }`, `HttpMethod { GET, HEAD, POST, PUT, PATCH,
DELETE, OPTIONS }`: `parseStrict(String)` is case-insensitive and throws the code above;
`parse(String)` is the stored reader (constant name, exact). JSON is written lower-case for
`runtime` and `auth`, upper-case for methods — the spellings of §4.1.

### 4.5 Defaults

`warm` absent ⇒ `false`. `auth` absent ⇒ `bearer` — a route is never public by omission.
`hostnames` absent or empty ⇒ a private-only route. `maxBodyBytes` absent ⇒ 1 MiB
(`HttpRoute.DEFAULT_MAX_BODY_BYTES`). `timeoutMs` absent ⇒ the function's `maxDurationMs`.

### 4.6 Limits: default, override, ceiling — frozen at publish

`record FunctionLimits(int maxDurationMs, int maxConcurrency, int wasmMemoryMb, int dbPoolSize,
int maxWarmPerHost)` — the platform defaults, from `Env`:

| Env var | Default |
|---|---|
| `FC_FN_DEFAULT_MAX_DURATION_MS` | 30000 |
| `FC_FN_DEFAULT_MAX_CONCURRENCY` | 32 |
| `FC_FN_DEFAULT_WASM_MEMORY_MB` | 64 |
| `FC_FN_DEFAULT_DB_POOL_SIZE` | 4 |
| `FC_FN_MAX_WARM_PER_HOST` | 200 |

One `Env` component `FunctionLimits functionLimits`, documented in `Env`'s comment style; a value
`<= 0` is a startup error like other invalid integers. (`FC_FN_MAX_CONCURRENCY`, the host-global
512, is the host's variable and belongs to package D.) `maxWarmPerHost` is carried for B's
warm-capacity check; A does not use it.

`record ClientCeilings(int maxDurationMs, int maxConcurrency, int wasmMemoryMb, int dbPoolSize)`;
`ClientPolicy.ceilings(FunctionLimits defaults)` resolves each: the policy's column when set, else
**the platform default**. A client with no policy row gets `ClientCeilings.of(defaults)`. So out of
the box a function may lower a limit but not raise one; raising takes an operator raising the
ceiling (design §4, §10.7). A ceiling set *below* the default lowers the applied default with it:
the effective value of an absent limit is `min(default, ceiling)`.

`parseStrict` returns the manifest with every applicable limit present. The stored manifest is
therefore complete, and a later change to a platform default never changes an already-published
version — a version is reproducible from its own row.

## 5. Hostnames and routes

### 5.1 `Hostname`

`record Hostname(String value)`, `parse(String raw)`: lower-cases (DNS is case-insensitive, and the
table checks `hostname = lower(hostname)`), then requires ≤ 253 characters, at least two labels,
each a `DnsLabel`, no trailing dot, no wildcard, no port, not an IP literal (all-numeric last
label). Error `HOSTNAME_INVALID`.

| Rule | Accepted | Rejected |
|---|---|---|
| case | `API.Acme.com` → `api.acme.com` | — |
| labels | `a.io`, `x-1.acme.co.za` | `localhost` (one label), `a..com`, `-a.com`, `a_b.com` |
| form | — | `acme.com.`, `*.acme.com`, `acme.com:443`, `https://acme.com`, `10.0.0.1` |
| length | 253 chars | 254 chars, a 64-char label |
| absent | — | `null`, `` |

(fcdev's `localhost` routes — design §8 — bypass domain verification in package B; whether they
also need a `Hostname` exemption is decided there, not by loosening this parser now.)

### 5.2 `RoutePattern`

`record RoutePattern(String value, List<Segment> segments)`; sealed `Segment = Literal(String) |
Param(String name) | Rest`. `parse(String raw)`, error `ROUTE_PATTERN_INVALID`.

| Rule | Accepted | Rejected |
|---|---|---|
| leading slash | `/`, `/invoices` | `invoices`, `` , `null` |
| literal segment `[A-Za-z0-9._~-]+` | `/v1/invoices.json` | `/in voices`, `/a%20b`, `/a?b`, `/a#b` |
| param `{name}`, name `[A-Za-z][A-Za-z0-9_]*` | `/invoices/{id}`, `/a/{x}/b/{y}` | `/{}`, `/{1d}`, `/a{id}`, `/{id}x`, `/{a}/{a}` (duplicate name) |
| rest `*` only as the last segment | `/files/*`, `/*` | `/*/a`, `/a*`, `/**` |
| empty segment | — | `//a`, `/a//b`, `/a/` (trailing slash) |
| length | 1024 chars | 1025 chars |

`Optional<Map<String,String>> match(String path)` — the params on a match (insertion-ordered),
empty `Optional` otherwise. `path` is the request's raw path, no query. Rules:

- split on `/`; literals compare byte-for-byte against the **raw** segment (no decoding, case
  sensitive); a param matches exactly one non-empty segment and its value is percent-decoded
  (a malformed escape ⇒ no match);
- `Rest` matches **zero or more** remaining segments: `/files/*` matches `/files`, `/files/` and
  `/files/a/b`;
- without `Rest`, the segment counts must be equal; a request's trailing slash is an empty last
  segment and matches nothing but `Rest` — `/a/` does not match `/a`;
- `match(null)` is empty.

### 5.3 Precedence and ambiguity

`RoutePattern implements Comparable` — *more specific first*: compare segment by segment from the
left, `Literal` before `Param` before `Rest`; if one pattern runs out first, the one without `Rest`
wins. Among patterns that match a path, the first in this order is the route. (Design §4a: "exact
segments first, then `{param}`, then a trailing `*`".)

`boolean ambiguousWith(RoutePattern other)`: true when the two have the same number of segments and
at every position are both the same literal, both params (names irrelevant), or both `Rest`. Those
are exactly the pairs the order cannot separate.

| Rule | A | B | Path | Result |
|---|---|---|---|---|
| literal beats param | `/a/b` | `/a/{x}` | `/a/b` | A |
| param beats rest | `/a/{x}` | `/a/*` | `/a/b` | A |
| leftmost decides | `/a/b/{y}` | `/a/{x}/c` | `/a/b/c` | A |
| no-rest beats rest at a tie | `/files` | `/files/*` | `/files` | A |
| ambiguous: param names | `/a/{x}` | `/a/{y}` | — | ambiguous |
| ambiguous: identical | `/a/*` | `/a/*` | — | ambiguous |
| not ambiguous | `/a/{x}` | `/a/b` | — | distinct |
| not ambiguous | `/a/{x}` | `/a/{x}/b` | — | distinct |

## 6. Entities and repositories

Records, `implements HasId` where there is an id, compact constructors `requireNonNull` the required
components, transitions return copies and throw `UseCaseException`. Repositories follow
`PortalAppRepository` / `ClientRepository`: one `DSLContext`, one private `findOne(Condition)` /
`findMany(Condition)`, children hydrated in one `IN` query and passed into `toEntity`, writes on
`DSL.using(tx.connection())` only, each column listed once in an upsert, `created_*` insert-only.

### 6.1 `Function` (+ aliases)

**Owner (ruling R2).** `sealed FunctionOwner = Platform | Client(String clientId)` — who a function,
a domain or a policy belongs to. `FunctionOwner.ofClientId(String)`: null ⇒ `Platform`; blank is an
`IllegalArgumentException`, never coerced. `String clientIdOrNull()` for the two nullable columns.
Nothing in the JVM carries a null or a `"PLATFORM"` string to mean the platform.

Components: `id`, `applicationId`, `address` (`FunctionAddress`), `owner` (`FunctionOwner`), `runtime`,
`description` (nullable), `status`, `aliases` (`List<FunctionAlias>`), `createdAt`, `updatedAt`.
`FunctionAlias(String alias, String versionId, String updatedBy, Instant updatedAt)`;
`Function.LIVE = "live"`.

- `static create(applicationId, FunctionAddress, FunctionOwner, Runtime, description, Instant now)` —
  `ACTIVE`, no aliases. It takes the parsed address (CONVENTIONS: the type carries the proof).
- `describe(String description, Instant now)` — the only editable field.
- `disable(now)` / `enable(now)` — conflict `FUNCTION_ALREADY_DISABLED` / `FUNCTION_ALREADY_ACTIVE`.
- `promote(String alias, FunctionVersion version, String principalId, Instant now)`:
  alias other than `live` ⇒ validation `ALIAS_UNSUPPORTED` (design §10.6); `version.functionId()` not
  this function ⇒ validation `VERSION_NOT_OF_FUNCTION`; version `RETIRED` ⇒ conflict
  `VERSION_RETIRED`; function `DISABLED` ⇒ conflict `FUNCTION_DISABLED`; already pointing at that
  version ⇒ conflict `ALIAS_UNCHANGED`. Returns `Promoted(Function function, String
  previousVersionId)` — `previousVersionId` null on first promotion. **It does not require `READY`**
  — see Q3.
- `Optional<String> liveVersionId()`; `boolean isLive(String versionId)`.
- **There is no transition that changes `applicationId`, `address`, `owner` or `runtime`**
  (design §10.17), and the repository's upsert `SET` list omits those columns, so even a hand-built
  copy cannot move a function.

`FunctionRepository implements Persist<Function>`: `findById`, `findByAddress(FunctionAddress)`,
`list(ListFilter)` with `record ListFilter(FunctionAddressPattern pattern, FunctionOwner owner,
FunctionStatus status)` (null = no filter; `Platform` filters to `client_id IS NULL`) ordered by address. `persist` upserts the row and
replaces the alias rows to match `aliases` (delete those absent, upsert the rest) in the same
transaction. `delete` removes the function and, by cascade, its versions, aliases and routes (ruling R4). A test
seeds all three, deletes, and asserts each table has no row for the function — and that a sibling
function's rows are untouched.

### 6.2 `FunctionVersion`

Components: `id`, `functionId`, `version` (int), `artifactRef`, `digest` (`Digest` record:
`parse` enforces `sha256:` + 64 lower-case hex, error `DIGEST_INVALID`, no normalisation),
`signatureBundle` (nullable), `signatureBundleRef` (nullable), `signer` (`SignerIdentity(issuer,
subject)`, nullable as a whole), `manifest`, `state`, `publishedBy`, `publishedAt`.

`sealed VersionState = Published | Ready(Instant at) | Retired(Instant at)`; the stored string is
`PUBLISHED|READY|RETIRED` plus the two timestamp columns. A `Retired` version that was once ready
keeps `ready_at` in the row; the record does not model it (nothing reads it).

- `static publish(functionId, int version, artifactRef, Digest, bundle, bundleRef, signer, Manifest,
  publishedBy, now)` — `Published`.
- `markReady(now)`: `Published` ⇒ `Ready(now)`; `Ready` ⇒ unchanged (same instance — the first
  `ready_at` is kept); `Retired` ⇒ unchanged. Never throws: it is driven by heartbeats, and a host
  still reporting a retired version for one more reconcile is routine, not an error.
- `retire(now)`: `Published|Ready` ⇒ `Retired(now)`; `Retired` ⇒ conflict `VERSION_ALREADY_RETIRED`.
  ("Is it the live version?" needs the function; package B's Retire asks `Function.isLive`.)
- `boolean loadable()` — not retired.

`FunctionVersionRepository implements Persist<FunctionVersion>`: `findById`,
`findByFunctionAndVersion`, `findByFunctionAndDigest`, `listByFunction(functionId)` newest first,
`findByIds(Collection)` → map (the desired-state batch read).

- `int nextVersion(String functionId, DbTx tx)` — takes `SELECT … FOR UPDATE` on the function's
  `fn_functions` row, then returns `max(version) + 1` (1 for the first). The lock is the point:
  two concurrent publishes must get 1 and 2, not 1 and a unique-violation 500.
- `persist`: insert; on conflict by id the `SET` list is **`state`, `ready_at`, `retired_at` only**.
  A version's content is immutable and the upsert is where that is enforced.
- `delete` throws `UnsupportedOperationException` — a version alone is retired, never deleted; it
  goes only when its whole function does (§6.1).

### 6.3 `FunctionHost`

`FunctionHost(id, DnsLabel pool, HostState state, List<LoadedVersion> loaded, startedAt,
lastHeartbeat)`; `HostState { ACTIVE, DRAINING }`;
`LoadedVersion(FunctionAddress address, int version, LoadState state)`;
`sealed LoadState = Registered | Loaded | Failed(String error)` — `Registered` is a lazy function
whose artifact the host has fetched and verified but not loaded (design §4), `Loaded` is in memory,
`Failed` carries the last error for Status. `boolean ok()` is true for the first two.

`loaded` JSON: `[{"address":"a.b.c","version":3,"state":"LOADED"},{"address":…,"state":"FAILED",
"error":"…"}]`. The stored reader drops an entry it cannot read (bad address, unknown state) rather
than failing the whole host row — one bad entry must not hide a host from Status.

`heartbeat(HostState, List<LoadedVersion>, now)` returns a copy; `static register(id, pool, now)`.
`FunctionHostRepository implements Persist<FunctionHost>` (upsert by id; `started_at` insert-only), plus `findById`,
`listByPool(DnsLabel pool)`, `listLive(DnsLabel pool, Instant seenSince)`,
`pools()` → `List<PoolSummary(DnsLabel pool, int hosts)>` counting hosts seen since an instant.

### 6.4 `ClientPolicy`

`ClientPolicy(FunctionOwner owner, List<SignerRule> signers, Integer maxDurationMs, Integer maxConcurrency,
Integer maxWasmMemoryMb, Integer maxDbPoolSize, createdAt, updatedAt)`;
`SignerRule(String issuer, String subject, Set<Runtime> runtimes)`.

- `boolean permits(SignerIdentity identity, Runtime runtime)` — some rule has an **equal** issuer,
  an **equal** subject and contains the runtime. No patterns, no case folding, no trimming; `null`
  identity ⇒ false; an empty signer list permits nothing. (Subject patterns, if package C finds the
  GitHub workflow identity needs them, are a ruling to ask for — not something to slip in here.)
- `ceilings(FunctionLimits defaults)` — §4.6.
- `signers` JSON `[{"issuer":…,"subject":…,"runtimes":["JVM"]}]`; the stored reader drops unknown
  runtimes from a rule and drops a rule with a blank issuer or subject.

`ClientPolicyRepository implements Persist<ClientPolicy>`: `findByOwner(FunctionOwner)` → `Optional`.
It maps `Platform` ⇄ the reserved `PLATFORM` key and is the only code that spells it; `HasId.id()`
is that stored key.

### 6.5 `FunctionDomain`

`FunctionDomain(id, FunctionOwner owner, Hostname hostname, String verificationToken, Verification
verification, createdAt)`; `sealed Verification = Pending | Verified(Instant at)`.
`static claim(FunctionOwner, Hostname, String token, now)`; `verified(now)`: `Pending` ⇒ `Verified`,
`Verified` ⇒ conflict `DOMAIN_ALREADY_VERIFIED`. `boolean usableBy(FunctionOwner owner)` — verified and
owned by that owner (`Platform` matches only `Platform`). `toString` masks the token.

Repository: `findById`, `findByHostname(Hostname)`, `listByOwner(FunctionOwner)`, `persist` (SET:
`verified_at` only), `delete`.

### 6.6 `FunctionRoute` — superseded by `function-invocation.md` §3, §6.6

`FunctionRoute(id, functionId, Hostname hostname, RoutePattern pathPrefix, createdAt)` — `hostname`
is never `null` (every row is public; a private call needs no route row, invocation spec §2). Not an
aggregate with transitions — a materialisation.

`FunctionRouteRepository`: `listByFunction`, `listByHostname`, `listByFunctions(Collection)` (the
desired-state batch read), `Optional<FunctionRoute> findPublic(Hostname, RoutePattern)` (the conflict
lookup package F's RouteSync names the other function from), and
`replaceForFunction(String functionId, List<FunctionRoute> routes, DbTx tx)` — delete-then-insert
in the caller's transaction.

The original shape (`HttpMethod method`, `RoutePattern pattern`, nullable `hostname`) is historical;
see the superseding spec.

## 7. Out of scope for A

Operations, events, audit, HTTP routes, OpenAPI, permissions, `Platform` registration, signature
verification, the artifact store, the API jar, the host. Nothing in `Platform.java` changes.

## 8. Load-bearing behaviours — each pinned by a named assertion and mutation-checked

Per `CLAUDE.md`: after the test passes, break the code, confirm *that* test fails, restore. The
report lists mutant → test. Use `-Dsurefire.timeout=<s>`; there is no `timeout` on macOS.

| # | Behaviour | The mutant that must die |
|---|---|---|
| M1 | The SQL label check and `DnsLabel` agree: every row of §3.1's table, inserted raw into `fn_functions.service_name`, is accepted/rejected by the database exactly as by the parser | drop the trailing-hyphen clause from either side |
| M2 | Address: exactly three segments | accept `>= 3`; accept 2 |
| M3 | Pattern matching is whole-segment (`billing.invoices.*` ✗ `billing.invoices-v2.create`) — in `matches` **and** in the repository's `ListFilter` (seed both functions, list by pattern, only one returns) | implement either with `startsWith` / `LIKE` |
| M4 | Unknown manifest key rejected, at top level and nested | skip the check for nested objects |
| M5 | Over-ceiling rejected; absent limit frozen to `min(default, ceiling)` in the returned manifest **and** in the row read back | compare against the default instead of the ceiling; leave the limit absent |
| M6 | `auth` absent ⇒ `BEARER` | default to `NONE` |
| M7 | Route precedence and ambiguity: every row of §5.3 | swap Literal/Param order; make `ambiguousWith` compare param names |
| M8 | `Rest` matches zero segments; `/a/` does not match `/a`; param values are decoded, literals are not | each, separately |
| M9 | A version's content is immutable through `persist`: persist a copy with another digest, artifact ref and manifest; the row read back has the originals and the new state | add `digest` to the `SET` list |
| M10 | `nextVersion` serialises: tx1 calls it and stays open; tx2's call, on another thread, has **not returned** after 300 ms; tx1 persists version 1 and commits; tx2 then returns 2 | remove `FOR UPDATE` (tx2 returns 1 at once) |
| M11 | A function cannot move: persist a copy with a different address, client and runtime; the row is unchanged | add `service_name` to the `SET` list |
| M12 | `markReady` keeps the first `ready_at` and never resurrects a retired version | overwrite on `Ready`; transition from `Retired` |
| M13 | Public-route uniqueness is across functions; private routes with equal method+pattern on two functions both insert; the same private route twice on one function does not | drop the partial unique index; make the main unique index include `function_id` |
| M14 | Foreign JSON shapes read: `fn_hosts.loaded` with a bad entry keeps the good ones; `fn_versions.manifest` with unknown keys reads; `fn_client_policies.signers` with an unknown runtime reads | make any stored reader strict |
| M15 | `permits` is exact: a subject differing by case, by a trailing `/`, or a rule for the other runtime does not permit | `equalsIgnoreCase`; ignore the runtime set |
| M16 | Alias replacement: promote to v2 after v1 leaves one `live` row pointing at v2 | insert without on-conflict update (would throw) / skip the update |
| M17 | The fingerprint filter hides exactly the `fn_` objects and `mail_outbox`: the Go fingerprint still matches, and a line for a Go table whose name merely *contains* a Java-only table's name is not hidden | revert to `contains` |

Also required, not mutation-gated: repository round-trips for all seven tables; every row of every
Accepted/Rejected table as a `@ParameterizedTest` `@CsvSource` with a rule-label column; `Env`
defaults and the `<= 0` rejection; `tools/jooq-verify.sh` green; `SchemaFingerprintTest`,
`GoAdoptionTest` and the convention tests green.

## 9. Rulings (owner, 2026-09-19) and what is still open

- **R1 (was Q1).** An application whose code is not a DNS label cannot own functions. Package B's
  create rejects it with `APPLICATION_CODE_NOT_ADDRESSABLE`, and the message says why.
- **R2 (was Q2).** A function may belong to the platform rather than a client. `client_id` is
  nullable on `fn_functions` and `fn_domains`; the platform's signer policy and ceilings are the
  `PLATFORM` row of `fn_client_policies`; in Java it is `FunctionOwner.Platform` (§6.1).
- **R3 (was Q3).** Desired state also carries each function's newest `PUBLISHED` version. A host
  fetches and verifies it and reports `Registered`; the heartbeat marks it `READY`; only a `READY`
  version can be promoted. Package B's Promote enforces `READY`; A's transition still does not,
  so it stays a pure function of what it is handed.
- **R4 (was Q4).** Deleting a function deletes all its versions, aliases and routes (cascade).
  Still open for B: `DeleteApplication` while the application has functions — there is no FK.
- **Open — Q5, package C.** Signer subject matching is exact here. GitHub's keyless subject embeds
  the workflow ref (`…/publish.yml@refs/heads/main`); a tag-triggered release has a different
  subject per tag. Exact list, or a constrained pattern.

| # | Behaviour added by the rulings | The mutant that must die |
|---|---|---|
| M18 | Delete cascades to versions, aliases and routes, and only that function's | drop `ON DELETE CASCADE` from `fn_versions` (delete throws) ; delete by `application_id` instead of `id` |
| M19 | `Platform` round-trips: a platform function reads back `Platform`, lists under the `Platform` filter and not under a client's; the platform policy is found by `findByOwner(Platform)` and by no client id | map `Platform` to a client filter of `IS NOT NULL`; spell the key differently on read and write |
| M20 | `usableBy`: a client's verified domain is not usable by the platform, nor the reverse | compare `clientIdOrNull()` with `Objects.equals` but skip the verified check / ignore owner |
