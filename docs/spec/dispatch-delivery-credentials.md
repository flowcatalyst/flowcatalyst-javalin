# Spec — dispatch-job deliveries carry the application's webhook credentials

Owner ruling 2026-09-19: lands on `main` ahead of the function service. Closes `docs/backlog.md`
"Subscriber deliveries go out unsigned" and `dispatch-seam.md` §5 "Credentials", §15.

**Reversed 2026-09-22** (`docs/go-mirror/2026-09-22-delivery-credentials-handoff.md`,
`docs/spec/catch-up-2026-09-22.md` slice C2): §2 below — "credentials belong to the
application" — is no longer the resolution rule. The connection form REQUIRES a service
account and the subscription form asks for no application, so keying on the application alone
described a credential nothing configured; a Laravel subscriber holding the connection
account's secret rejected every delivery as unsigned-by-the-wrong-key. The new order is the
hand-off's: `subscription.serviceAccountId` → `subscription.connectionId → connection.serviceAccountId`
→ the application's oldest active account (§2 below, now step 3's fallback only, otherwise
unchanged) → bare with a reason. §2's numbered steps are kept below **as historical record of
what step 3 alone still does** — read "the resolution" throughout as "step 3, reached only when
neither the subscription nor its connection names an account." `DeliveryCredentials.forApplications`
is renamed `DeliveryCredentials.resolve` and gains the `connections`/`byServiceAccountId`
parameters; `Resolved` gains `reason`/`signedBy`. S2 and S4 below are re-read the same way (still
valid, now pinning step 3's inner behaviour specifically) — S1, S3, S5–S8 are unaffected in
substance. New load-bearing behaviours (the resolution order itself, the never-silent reason,
`request_info`, the 401/403 fail-fast) are T1–T9 in `docs/spec/catch-up-2026-09-22.md`, not
renumbered into this file's S-series.

## 1. The defect

`SubscriberDelivery` already stamps `Authorization: Bearer …` and `X-FlowCatalyst-Signature` /
`X-FlowCatalyst-Timestamp` when it is handed credentials (`dispatch-seam.md` §5). `Platform` hands
it `DeliveryCredentials.none()`, written when the `serviceaccount` aggregate did not exist. It does
now. So **every subscriber webhook Java delivers is unauthenticated and unsigned**; Go's are not.
A receiver that verifies signatures (every SDK's `WebhookSignature`) rejects Java's deliveries; one
that does not cannot tell a platform delivery from anyone's POST. Scheduled-job deliveries are
already signed (`scheduler/jobs/OutboundCredentials`) — this is dispatch jobs only.

## 2. Resolution (what Go does, `internal/server/wire_public.go` `dispatchDeliveryCredsResolver` — and it is right)

Credentials belong to the **application**, not to the subscription's or job's `serviceAccountId`:

1. the job has a `subscriptionId` ⇒ load the subscription; its `applicationCode`, when non-blank, is
   the application code;
2. otherwise (a direct dispatch job, or a subscription without an application) ⇒ the job's `code`
   up to its first `:` when it contains one and that segment is non-empty;
3. no application code ⇒ **no credentials** (bare delivery);
4. application by code; absent ⇒ no credentials;
5. the application's **oldest active service account**'s webhook credentials — exactly
   `OutboundCredentials.resolve`, behind the same one-minute-per-application cache the scheduled-job
   dispatcher uses. **One resolver, shared**: move/reuse, do not write a second.

Bearer token and signing secret are independent; either may be absent.

`DeliveryCredentials.forApplications(subscriptions, applications, byApplicationId)` is the
implementation; `none()` stays for tests. A subscription id that names no row is case 2, not an error.

## 3. Failure

`dispatch-seam.md` §5, unchanged: a resolver that **throws** (database down) degrades to a bare
delivery with a WARN (`message_id`/`job_id` field, cause attached) — `ProcessingApi` already
enforces this at the call site. "No credentials" (cases 3, 4, no active service account) is not a
failure and is logged at debug at most: it is the normal state of a platform-scoped job.

**No secret in any log field, message, exception or `toString`.**

## 4. Wiring

`Platform`: `ProcessingApi.State` gets the real resolver. Nothing else changes — no route, no
schema, no env var.

## 5. Load-bearing behaviours — named tests, one mutant per condition (assert absence too)

| # | Behaviour | Mutant |
|---|---|---|
| S1 | **End to end**: a job whose subscription belongs to an application with an active service account is delivered to a local HTTP server carrying a bearer token equal to the service account's and a signature that the SDK's own `WebhookSignature` verification accepts with that account's secret **and rejects with another secret** | wire `none()`; sign with a constant; sign a different body than the one sent |
| S2 | subscription → `applicationCode` wins over the job code's first segment when both exist and differ | prefer the job code |
| S3 | direct job, code `billing:invoice:created` ⇒ application `billing`'s credentials; code `legacy` (no colon) ⇒ bare; code `:x` ⇒ bare | take the whole code; accept an empty segment |
| S4 | unknown application code ⇒ bare; application with no active service account ⇒ bare; with two active accounts the **oldest** is chosen; a deactivated account older than both is ignored | pick newest; ignore status |
| S5 | token-only and secret-only accounts each send exactly their one header (assert the other header is **absent**) | send an empty header |
| S6 | the cache: two deliveries for one application inside a minute resolve once; after the TTL, again (drive the clock) | no cache; cache for ever |
| S7 | a throwing resolver ⇒ the delivery still goes out, bare, and a WARN is logged (Logback `ListAppender`); no secret appears in any captured log line during S1–S7 | let it abort the delivery; log the secret |
| S8 | `Platform` wiring: boot a real `Server` on `TestPg`, run one job through `/api/dispatch/process` (as `ProcessingApi`'s existing tests do), receive a signed request — the composition root, not a hand-built `State`, is what is under test | leave `none()` in `Platform` |

`docs/backlog.md` entry removed; `dispatch-seam.md` §15 item and `DeliveryCredentials`' class doc
updated; `docs/STATUS.md` gets a dated line.
