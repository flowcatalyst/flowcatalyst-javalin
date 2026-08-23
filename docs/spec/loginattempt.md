# Login attempt — behavioural spec

The contract for `io.flowcatalyst.platform.loginattempt`. Derived from the
lockfile (`/api/login-attempts` — one read operation) plus the recording
rules and the backoff queries the login / token / passkey flows rely on.
The Java is written *from* this; tests assert it. Questions marked
**load-bearing or accident?** need an owner ruling — until ruled on, the
behaviour is kept.

## 1. Aggregate

A login attempt is one row of the authentication trail: the outcome of a
password login, a passkey login, a service-account token mint or a developer
token mint, keyed on the identifier the caller typed. It feeds two readers:
the admin list (§2) and the brute-force backoff (§5). This package is a
**store, not a use case**: it has no operations, no domain events and no
`Persist` — rows are written by the auth flows directly through the
repository, outside the unit-of-work envelope, and never emit `msg_events`
or `aud_logs` (§6).

| Field | Type | Column | Notes |
|---|---|---|---|
| `id` | `lat_` + 13-char TSID | `id` | generated on record |
| `attemptType` | `USER_LOGIN` \| `SERVICE_ACCOUNT_TOKEN` \| `DEVELOPER_TOKEN` | `attempt_type` | stored as the constant name; lenient read: unknown → `USER_LOGIN` |
| `outcome` | `SUCCESS` \| `FAILURE` | `outcome` | stored as the constant name; lenient read: unknown → `SUCCESS` |
| `failureReason` | string, optional | `failure_reason` (100) | human text (`Invalid credentials`, `SSO required`, `Invalid 2FA code`, `Invalid passkey`, token-grant reasons); absent on success |
| `identifier` | string, optional | `identifier` (255) | what was typed: the e-mail (lower-cased, trimmed by the login / passkey flows) or the OAuth `client_id` (as typed) |
| `principalId` | string, optional | `principal_id` | the matched principal when one was resolved before the outcome was decided |
| `ipAddress` | string, optional | `ip_address` (45) | best-effort client IP (rightmost `X-Forwarded-For` hop); absent when unknown |
| `userAgent` | string, optional | `user_agent` | only the passkey flow fills it today |
| `attemptedAt` | timestamp | `attempted_at` | `now()` UTC at record time |

`attemptType` distinguishes a human minting their own developer token
(`DEVELOPER_TOKEN`, `client_id` = the principal's own id) from genuine
service-account activity (`SERVICE_ACCOUNT_TOKEN`) so the audit trail does
not blur the two.

No state machine, no transitions: a row is written once and never updated.
The record refuses `null` in `id`, `attemptType`, `outcome`, `attemptedAt`;
every other component is `null` when absent (never `""`).

## 2. HTTP surface (lockfile)

| Method / path | Query inputs | Success | Envelope |
|---|---|---|---|
| `GET /api/login-attempts` | `attemptType`, `outcome`, `identifier`, `principalId`, `dateFrom`, `dateTo`, `after`, `pageSize` | 200 | cursor `LoginAttemptListResponse` `{items[], hasMore, nextCursor?}` |

Gate: **anchor only** — `Checks.requireAnchor` in the handler.
Unauthenticated → 403 `UNAUTHENTICATED`; non-anchor → 403 `ANCHOR_REQUIRED`
`anchor scope required`. No permission code exists for this surface
(**load-bearing or accident?** open question 1). Every error is the
`ErrorModel` envelope.

Wire shapes:

| Schema | Fields (in order) | Notes |
|---|---|---|
| `LoginAttemptResponse` | `id, attemptType, outcome, failureReason, identifier, principalId, ipAddress, userAgent, attemptedAt` | **all nine keys are always present**: the optional ones are emitted as JSON `null`, `identifier` is emitted as `""` when the row has none (the lockfile types it as a non-null string); `attemptedAt` RFC 3339, 6 fractional digits, `Z` |
| `LoginAttemptListResponse` | `items[]`, `hasMore`, `nextCursor?` | `items` never `null`; `nextCursor` omitted unless `hasMore` |

The `""`-for-absent `identifier` is a wire rule of the DTO only; inside the
JVM the field is `null` (open question 2).

## 3. The cursor list

| Input | Rule |
|---|---|
| `pageSize` | absent → 50; `< 1` or `> 200` → **50** (not clamped — same family as the audit list; open question 3); non-integer → 400 `VALIDATION` with `details.errors[{message: "invalid integer", location: "query.pageSize", value}]` |
| `after` | absent/empty → first page; otherwise an opaque cursor (§4); **malformed → ignored, first page** (open question 4 — the audit list answers 400 `CURSOR`) |
| `attemptType`, `outcome`, `identifier`, `principalId` | equality filters on the stored string; absent/empty → no filter; an unknown `attemptType` / `outcome` value matches no row (it is not parsed leniently — open question 5) |
| `dateFrom`, `dateTo` | RFC 3339 timestamps (offset allowed), inclusive bounds on `attempted_at`; absent/empty/**unparseable → no bound** (open question 4) |

Ordering: `attempted_at DESC, id DESC` — newest first, id as the tiebreak so
the order is total and the keyset is stable.

Pagination: the repository is asked for `pageSize + 1` rows; if it returns
more than `pageSize`, `hasMore = true`, the extra row is dropped and
`nextCursor` encodes the position of the last *returned* row. Otherwise
`hasMore = false` and `nextCursor` is omitted. An empty page has
`hasMore = false`.

## 4. Cursor format (`LoginAttemptCursor`)

A keyset position `(attemptedAt, id)`. Encoded as base64url **without
padding** of the text `<attemptedAt as RFC 3339 UTC>|<id>`; decoding splits
on the first `|`, parses the timestamp leniently (0–9 fractional digits, as
either writer may have produced) and reports any failure — bad base64, no
`|`, unparseable time — as "no cursor" to the caller, which the list route
treats as the first page (§3). The encoder writes `ISO_INSTANT` (0/3/6/9
fractional digits); the stored value has microsecond precision, so a round
trip is exact. The next page is every row with
`(attempted_at, id) < (cursor.attemptedAt, cursor.id)` in the same ordering.

A cursor is opaque to clients: its only consumer is this endpoint.

## 5. Repository contract (the store the auth flows use)

The admin list needs one read; the login / token / passkey flows and the
`loginbackoff` policy need the rest. All are exposed now so the auth port
does not come back to this package.

| Method | Behaviour |
|---|---|
| `recordAttempt(attempt)` | inserts the row as given (`NULL` for every absent optional); **direct write, autocommit, no unit of work, no event, no audit row** (§6) |
| `findPage(filter, after, limit)` | §3: every non-null filter ANDed, strictly before `after` when given, `attempted_at DESC, id DESC`, at most `limit` rows (`limit` must be `>= 1`; the API bounds it — no silent correction) |
| `findRecentByIdentifier(identifier, limit)` | the newest `limit` rows for the identifier, `attempted_at DESC` (the login session-history panel reads 20) |
| `lastSuccessAt(identifier)` | `MAX(attempted_at)` over `SUCCESS` rows for the identifier; empty when there has never been one |
| `failureStatsSince(identifier, ip, since)` | `(count, lastFailureAt)` over `FAILURE` rows for the `(identifier, ip)` pair with `attempted_at >= since`; `lastFailureAt` absent when the count is 0 |
| `countFailuresSince(identifier, since)` | `FAILURE` rows for the identifier across all IPs with `attempted_at >= since` |

Identifier matching is a plain string equality on the column: the callers
normalise (lower-case, trim) before both recording and querying. The
repository does not normalise (open question 6).

How `loginbackoff` composes these (for the auth port; not implemented here):
`lastSuccessAt` bounds the failure window (fallback: now − 30 days); with an
IP, `failureStatsSince(identifier, ip, bound)` drives the per-pair
exponential delay (free attempts, base delay, cap; the delay is measured
from `lastFailureAt`); then `countFailuresSince(identifier, max(bound,
now − global window))` is compared with the global ceiling.

Indexes the schema already carries for these reads: `(identifier,
attempted_at) WHERE outcome = 'FAILURE'`, `(identifier)`, `(attempted_at)`,
`(principal_id)`, `(outcome)`, `(attempt_type)`.

## 6. Persistence — outside the envelope, on purpose

Recording an attempt is infrastructure processing: the auth flows insert
the row directly through the repository in autocommit, **not** inside a
use case — there is no command, no `msg_events` row and no `aud_logs` row.
Recording is best-effort in every caller: a failed insert is swallowed and
never fails the login or token request (a logging miss must not deny a
user). The repository itself does not swallow — it throws on failure and
the *caller* decides; the "log and continue" lives in the auth flow.

## 7. Authorization placement

| Where | What |
|---|---|
| Handler | `Checks.requireAnchor(Auth.current())` |
| Use case | none — there are no use cases |
| Resource-level | none: an anchor sees every row |

## 8. Open questions for the owner (summary)

1. The list is anchor-only with no permission code (the audit list has
   `platform:admin:audit-log:view`). Keep anchor-only, or introduce a
   permission?
2. `identifier` is emitted as `""` on the wire when the row has none, while
   the other optionals are `null`. Keep (lockfile types it non-null) or
   make it nullable like its siblings?
3. `pageSize > 200` resets to 50 rather than clamping to 200. Keep (pinned)
   or clamp?
4. A malformed `after` cursor and unparseable `dateFrom`/`dateTo` are
   silently ignored (first page / no bound), unlike the audit list's 400
   `CURSOR`. Keep (pinned) or reject with 400?
5. `attemptType` / `outcome` filters are raw string equality: an unknown
   value lists nothing rather than being rejected or leniently mapped. Keep?
6. Identifier case-folding is the callers' job; the repository compares
   raw. Keep, or fold inside the repository (`LOWER(identifier)` would
   bypass the partial index)?
7. A `countRecentFailures(identifier, window)` read exists in the source
   but has no caller; it is `countFailuresSince(identifier, now − window)`
   and is not exposed. Confirm dropping it.
