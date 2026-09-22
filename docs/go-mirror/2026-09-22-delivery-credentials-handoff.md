# Java hand-off: the connection's service account signs dispatch deliveries

Owner ruling 2026-09-22, **reversing** `docs/spec/dispatch-delivery-credentials.md`
(2026-09-19) §2 "Credentials belong to the application". Go implements it in the
commit that adds this file (`internal/server/delivery_creds.go`,
`internal/platform/serviceaccount/secretresolver.go`,
`internal/platform/dispatchjob/processing/processing.go`). No schema or wire
change; `docs/wire-contract.md` "Dispatch-processing delivery request" carries
the new rule.

## Why

The 2026-09-19 rule keyed signing on the *application's oldest active service
account*, and never read `msg_connections.service_account_id` or
`msg_subscriptions.service_account_id`. But the connection create form
**requires** a service account, and the subscription form asks for **no
application** — so both UIs described a credential nothing used. A Laravel
subscriber configured with the connection account's signing secret rejected
every delivery with 401, the job went `FAILED: HTTP 401 Unauthorized` after
three retries, and nothing recorded why. Owner: "What is the point of having
the connection setup asking for a service account."

## Resolution order (replaces spec §2)

The first that **names** an account decides. **No fall-through past a named
account**: an account someone configured must be the one used, or declined
with a reason — silently signing with a different account is how a rotated or
deactivated credential keeps working by accident.

1. `subscription.serviceAccountId` — an explicit override.
2. `subscription.connectionId → connection.serviceAccountId` — the normal case.
3. The application's oldest active service account, found through
   `subscription.applicationCode` or, for a direct job with no subscription,
   the job code's first segment (`billing:invoice:created` → `billing`). This is
   the old rule, kept only as the fallback for a job with no connection to
   name an account.
4. Nothing → bare delivery, **with a reason** (below).

Steps 1–2 resolve the account **by id** and require `active = TRUE`; an
inactive account, a missing account, or one with no webhook credentials yields
*no credentials* and a reason naming what configured it —
`connection cnn-value: service account sa-conn is inactive`. Step 3 is
unchanged (`OutboundCredentials.resolve` by application, oldest active). Both
lookups stay behind the one-minute-per-key cache; add a by-id cache beside the
by-application one (Go: `NewCachedOutboundCredsByIDResolver`, sharing the memo
with `NewCachedOutboundCredsResolver`).

## Never unsigned silently

`OutboundCreds` (Java `DeliveryCredentials.Resolved`) gains a `reason` —
non-empty exactly when both credentials are empty. The processing handler:

- logs `dispatch process: delivering unsigned` at WARN with `job_id`,
  `subscription_id`, `reason` (a resolver that throws still degrades to bare
  delivery with its WARN, as spec §3 says — unchanged);
- on a **failed** attempt appends `" (delivered unsigned: <reason>)"` to the
  attempt's `errorMessage`, so the job page says why the 401 happened
  instead of just that it happened. Success and deferral are untouched.

Reasons, for parity of wording:

| Situation | Reason |
|---|---|
| named account inactive | `<who>: service account <code> is inactive` |
| named account missing | `<who>: service account <id> does not exist` |
| named account without credentials | `<who>: service account <code> has no webhook credentials` |
| application has no active account | `application <code> has no active service account` |
| application unknown | `application <code> does not exist` |
| nothing names anything | `no subscription, connection or application names a service account` |

`<who>` is `subscription <code>` or `connection <code>`.

**No secret in any log field, message, exception or `toString`** — unchanged.

## Tests to mirror (Go `internal/server/delivery_creds_test.go`, fakes, no DB)

Two accounts with **different** secrets — the connection's and the
application's — so which one signs is observable:

- connection account signs; the application lookup is **not consulted**
  (mutant: the old application-first rule);
- subscription account overrides the connection's;
- a named account that is inactive / has no credentials / does not exist →
  bare, reason names `connection <code>`, application **not consulted**
  (mutant: fall through to the application — Go's mutant of this was caught);
- no connection account, or no connection → application fallback;
- direct job: `value:invoice:created` → application `value`; `legacy` → bare
  with the "nothing names" reason; `nosuchapp:x` → "does not exist";
- a lookup error propagates (the caller degrades it, not the resolver).

Spec §5's S1–S8 stay valid with S2/S4 re-read under the new order.

## Still open

- The SPA connection form's "Service Account" field now does what it says; the
  subscription form still has no application field, which only matters for the
  step-3 fallback.
- Retry policy for a config-caused 401 (three attempts cannot fix a missing
  credential) — not ruled; deliveries still retry to `max_retries`.

## Addendum (same day): attempts record what was sent; 401/403 fail fast; the "sign" action

Follow-on rulings after the first deploy still 401'd and the platform's own
records could not say why (owner: "walking around Mount Everest blindfolded").

- **Failed attempts keep the response body.** `Attempt.CompleteFailure` now
  takes the body; before, only successes stored it, so every 401 recorded
  `HTTP 401 Unauthorized` and discarded the SDK's reason. Java's equivalent
  must store `response_body` on failure too.
- **`msg_dispatch_job_attempts.request_info JSONB`** (Go migration 057) — a
  `RequestSummary` of what was SENT: `signedBy` (service-account code),
  `signature`/`bearer` booleans, `timestamp`, sorted header `names`,
  `unsignedReason`, `target`. Never a secret. `OutboundCreds` gains
  `signedBy` (the SA code) to feed it. Exposed as `request` on the attempt DTO.
- **401/403 → FAILED on the first attempt** (`advance`): a retry sends the
  same credentials, so three attempts only delay the same answer. Logged as
  `dispatch failed (subscriber refused credentials; not retried)`. Note
  `MarkFailed` still does not bump `attempt_count` (pre-existing; requeue does
  not reset it, so bumping would make a requeued job fail immediately).
- **`POST /api/dispatch-jobs/{id}/sign`** (+ `/bff/...`), gated on
  `dispatch-job:view-raw`: builds the delivery exactly as `/api/dispatch/process`
  would — same resolvers, same `buildRequest` — and returns `{request, headers,
  body}` WITHOUT sending. The signature is real and verifies against the returned
  timestamp + body; `Authorization` is masked `Bearer ••••••`. Header names in the
  plan use the documented spelling (`X-FlowCatalyst-*`), not Go's canonical form.
- **Laravel SDK `php artisan flowcatalyst:verify-signature --timestamp --signature
  --body-file`** recomputes the HMAC with the app's configured secret and prints
  presented vs expected (plus a secret fingerprint), distinguishing "wrong
  secret" from "stale timestamp". Java/TS SDKs: a matching CLI is desirable but
  not built.
- Dispatch jobs gain `descriptor` (subscription name at fan-out; optional on
  direct create) and the read projection gains `descriptor` + `metadata`
  (fan-out copies the event's `context_data` onto the job's metadata). List
  filter `messageGroup` (exact). List rows carry `clientIdentifier`.
