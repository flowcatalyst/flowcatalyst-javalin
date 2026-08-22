# CORS allowed origins — behavioural spec

The contract for `io.flowcatalyst.platform.cors`. Derived from the lockfile
(`/api/platform/cors*` — five operations) plus the validation rules,
authorization placement, error codes and domain events the aggregate
embodies. The Java is written *from* this; tests assert it. Questions marked
**load-bearing or accident?** need an owner ruling — until ruled on, the
behaviour is kept.

## 1. Aggregate

A CORS allowed origin is one entry of the platform-wide allowlist of browser
origins that may call the platform API cross-origin. The allowlist is
**platform-owned, anchor-only** configuration: there is no per-client
dimension, so every gate on this surface is the handler's `requireAnchor`
and every use case is `publicAccess`. It has no state machine — an origin is
added or deleted, never updated.

| Field | Type | Notes |
|---|---|---|
| `id` | `cor_` + 13-char TSID | generated on add |
| `origin` | string, unique (`tnt_cors_allowed_origins_origin_key`) | the browser origin, **trimmed** before validation and storage; format §4 |
| `description` | string, optional | free text, not trimmed |
| `createdBy` | principal id, optional | the acting principal on add; absent only for rows another writer produced |
| `createdAt`, `updatedAt` | timestamps | `updatedAt` is stamped `now()` on every persist |

Table `tnt_cors_allowed_origins` (`id`, `origin`, `description`, `created_by`,
`created_at`, `updated_at`); unique index on `origin`. No lenient enum reads —
the row has no enums.

## 2. State machine

None. The only transitions are *add* (a fresh row) and *delete* (hard delete).

## 3. HTTP surface (lockfile)

Every error is the `ErrorModel` envelope `{"error": CODE, "message": …,
"details"?: …}`. The literal `/allowed` segment is registered before the
`{id}` routes so it wins.

| Method / path | Gate (handler) | Body → command | Success | Notes |
|---|---|---|---|---|
| `GET /api/platform/cors/allowed` | **none — public** (no bearer needed; the authenticator lets unauthenticated requests through and this handler never consults the principal) | — | 200 `PublicAllowedResponse` `{origins: [string]}` | the origin strings only, ordered by origin; the browser-facing contract (the SPA reads it before it knows who is logged in) |
| `GET /api/platform/cors` | `requireAnchor` | — | 200 `CorsOriginListResponse` `{corsOrigins: [AllowedOriginResponse], total}` | ordered by origin; `total` = list size, no pagination |
| `POST /api/platform/cors` | `requireAnchor` | `AddOriginRequest` → `AddCommand` | 201 `CreatedResponse` `{id}` | |
| `GET /api/platform/cors/{id}` | `requireAnchor` | — | 200 `AllowedOriginResponse` | 404 `CorsOrigin_NOT_FOUND` |
| `DELETE /api/platform/cors/{id}` | `requireAnchor` | `DeleteCommand(originId from path)` | 204, empty body | 404 `CorsOrigin_NOT_FOUND` |

Unauthenticated on a gated route → 403 `UNAUTHENTICATED`; authenticated but
not anchor-scoped → 403 `ANCHOR_REQUIRED` (`anchor scope required`), whatever
permissions the principal holds.

Wire shapes:

| Schema | Fields | Notes |
|---|---|---|
| `AddOriginRequest` | `origin`*, `description` | |
| `AllowedOriginResponse` | `id, origin, description?, createdBy?, createdAt, updatedAt` | optional fields omitted when null; timestamps RFC 3339 with 6 fractional digits, `Z` |
| `CorsOriginListResponse` | `corsOrigins[]`, `total` | |
| `PublicAllowedResponse` | `origins[]` | strings |
| `CreatedResponse` | `id` | |

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Add | `origin` non-blank after trim | `ORIGIN_REQUIRED` | `Origin is required` |
| Add | trimmed `origin` matches `^https?://[a-zA-Z0-9*]([a-zA-Z0-9*.-]*[a-zA-Z0-9*])?(:\d+)?$` | `INVALID_ORIGIN_FORMAT` | `Origin must be a valid URL (e.g. https://example.com or http://localhost:3000)` |
| Delete | `originId` non-blank | `ID_REQUIRED` | `Origin id is required` — see open question 2 |

The format rule lives in one parser (`Origin.parse`) that rejects
null/blank, trims, then matches the pattern; the add command's validate
phase and the aggregate factory both use it, so the stored value is always
the trimmed form. Because the future CORS filter (§9) will compare request
`Origin` headers against these stored strings, the pattern's rules are
pinned here and by `CorsOriginTest`'s accept / reject tables:

| Rule | Accepted | Rejected |
|---|---|---|
| **Scheme** — `http` or `https`, lower-case only, followed by `://` | `https://example.com`, `http://localhost` | `example.com`, `ftp://…`, `HTTPS://…`, `Https://…`, `https:/…`, `https://` (no host) |
| **Host** — one or more of ASCII letters, digits, `.`, `-`, `*`; may not start or end with `.` or `-`; letter case is **preserved** (see open question 5); consecutive dots are admitted | `https://Example.COM`, `https://a`, `https://1`, `https://10.0.0.1`, `https://a-b.c-d.example.com`, `https://example..com` | `https://exa mple.com`, `https://-example.com`, `https://example.com-`, `https://.example.com`, `https://example.com.`, `https://a_b.example.com`, `https://[::1]` (no IPv6 literals), `https://user@example.com` (no userinfo) |
| **Wildcard** — `*` is an ordinary host character, allowed anywhere, including alone (open question 1; the parser does not expand it) | `https://*.example.com`, `https://example.*`, `https://ex*ample.com`, `https://*` | `https://*.` (ends with `.`) |
| **Port** — optional `:` + one or more ASCII digits; no range or leading-zero check | `http://localhost:3000`, `https://*.example.com:8443`, `https://example.com:0`, `https://example.com:65536` | `https://example.com:` , `https://example.com:abc`, `https://example.com:3000:4000`, non-ASCII digits |
| **Nothing after `host[:port]`** — no path (not even a trailing `/`), query or fragment | — | `https://example.com/`, `https://example.com:3000/`, `https://example.com/path`, `https://example.com?x=1`, `https://example.com#frag` |
| **Trim** — leading/trailing whitespace is dropped before matching and storage; interior whitespace is a host error | `'  https://app.example.com  '` → `https://app.example.com` | — |
| **Blank** — `null`, `""`, whitespace-only → `ORIGIN_REQUIRED`, not `INVALID_ORIGIN_FORMAT` | — | — |

Malformed JSON body → 400 `INVALID_JSON` (transport).

## 5. Authorization placement

| Where | What |
|---|---|
| Handler | `requireAnchor` on list / get / add / delete; nothing on `/allowed` |
| Add / Delete — use case | `publicAccess` — CORS origins are platform-owned with no per-resource dimension; the handler's anchor gate is the whole check |
| Reads | handler only |

## 6. Conflicts and not-found (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| Add | another row has the same (trimmed) origin | `ORIGIN_ALREADY_EXISTS` `CORS origin '<origin>' already exists` | 409 |
| Delete | no row with that id | `CorsOrigin_NOT_FOUND` `CorsOrigin not found: <id>` | 404 |

Uniqueness is checked by a read before the write; the unique index is the
backstop for a race (it surfaces as 500 `PERSIST`, not 409 — **accident**, the
same as every other aggregate today).

## 7. Domain events

Source `platform:admin`; spec version `1.0`; subject `platform.cors.{id}`;
message group `platform:cors:{id}` on every event so one origin's events are
delivered in order. `data` omits null fields (none are nullable here).

| Type | Subject | Message group | `data` fields |
|---|---|---|---|
| `platform:admin:cors:origin-added` | `platform.cors.{id}` | `platform:cors:{id}` | `originId, origin` |
| `platform:admin:cors:origin-deleted` | same | same | `originId, origin` |

Every event writes one `msg_events` row (`deduplication_id = type-eventId`)
and one `aud_logs` row (`entity_type = Cors`, `entity_id = {id}`,
`operation` = command record simple name — `AddCommand` / `DeleteCommand`,
`operation_json` = the command) in the same transaction as the row change.

## 8. Persistence

Upsert `ON CONFLICT (id)` (there is only ever one write per row, but the
shape is the shared one); `created_by` and `created_at` are never
overwritten; `updated_at` is stamped `now()` at persist time. Delete removes
the row. `description` is stored as given (`null` when absent on the wire —
an empty string on the wire is stored as an empty string: **accident?**,
harmless, the same as other aggregates).

## 9. Consumer: the CORS filter (owner decision — Java WILL emit CORS headers)

The Go platform stored this allowlist but never emitted CORS headers from it
(the browser-facing surface was fronted by something else). The owner has
ruled that the Java platform **will** drive its CORS response headers from
this allowlist. The filter is **not** part of this unit; this section fixes
what the filter needs from the aggregate so the repository exposes it now.

What the filter will need:

- A read of the current allowlist as origin strings:
  `CorsOriginRepository.allowedOrigins()` → `List<String>` ordered by origin —
  exactly the list `GET /api/platform/cors/allowed` serves, so the SPA and
  the filter can never disagree. Exposed in this unit.
- A **cache** in front of that read: the filter runs on every request and
  must not hit Postgres per request. The cache is the filter's (an
  `AtomicReference<Set<String>>` or similar with a bounded TTL as the
  fallback refresh).
- **Invalidation on change**: the `origin-added` / `origin-deleted` events
  (§7) are the signal. The filter subscribes to them (in-process after
  commit, or via the platform's own event delivery) and reloads
  `allowedOrigins()`; the TTL covers the multi-node case until a cross-node
  invalidation exists. Until the filter exists nothing consumes these
  events beyond the event log.
- Matching semantics are the filter's decision and **open question 3**
  below: exact string match on the `Origin` request header (the safe
  default), and whether the `*` the format admits becomes a wildcard host
  match.

## 10. Open questions for the owner (summary)

1. The origin format admits `*` inside the host (`https://*.example.com`).
   Load-bearing (intended wildcard support for the future filter) or an
   accident of the regex? If accident, tighten the pattern (a wire change:
   existing rows with `*` would become unreadable only in the sense that
   re-adding them fails; reads stay lenient).
2. Delete with a blank id: Go had no validation and surfaced it as 404
   `CorsOrigin_NOT_FOUND` (pinned by its test); Java follows the template
   and returns 400 `ID_REQUIRED`. Only reachable with a whitespace path
   segment. Keep the template rule?
3. Filter matching semantics (exact vs wildcard host; whether
   `Access-Control-Allow-Credentials` is emitted) — decided when the filter
   is specified, not here.
4. `description` `""` vs `null` on the wire are stored as given — normalise
   to `null`?
5. The host keeps its letter case and uniqueness is case-sensitive:
   `https://Example.com` and `https://example.com` are two rows. Browsers
   serialise the `Origin` header with a lower-case host, so the upper-case
   row would never match an exact-string filter (§9). Normalise the host to
   lower-case in the parser (a wire-visible change: the stored/returned
   origin would differ from what was posted), or leave it to the filter's
   matching rule?
