# Login attempts link to who they were; service accounts show their ids (owner request 2026-09-17)

Status: **requested by the owner, implementing in both repos** (Go and Java,
same contract — the lockfile stays byte-identical, the SPA stays drift-free).

## Problem
A login-attempt row records `identifier` and `principalId`, but nothing in
the UI connects them to anything:
- For `SERVICE_ACCOUNT_TOKEN` rows the identifier is the **OAuth client's
  public `client_id`** (what the caller sent to `/oauth/token`), which no
  service-account screen shows, and no API returns after creation.
- The service-account detail drawer shows no ids at all — not the account
  id, not its principal id, not its OAuth `client_id`.
- The login-attempt detail dialog shows both ids as plain text.

## Backend (both repos)

### B1 — `ServiceAccountResponse.oauthClientId`
- Type string, **absent when null**, handled exactly like the existing
  `principalId` field on the same response (Go `*string` + `omitempty`;
  Java: match however `principalId` serialises).
- Value: the **public `client_id`** (NOT the OAuth client row `id`) of the
  OAuth client whose `principal_id` is this service account's principal.
  If several, the earliest by `(created_at, id)`. None → absent.
- Populated **on the same reads that populate `principalId`** (single-account
  reads) and nowhere else — list responses stay without it (no per-row
  lookup), and create responses keep today's shape.
- Needs a repository query "OAuth clients by principal id" (Go: sqlc query +
  `make sqlc`; sqlc is at `~/go/bin/sqlc`, not on PATH).

### B2 — `PrincipalResponse.serviceAccountId`
- Type string, **absent when null**. Value: the principal's own
  `service_account_id` column — set for SERVICE principals, absent for users.
- Carried on **every** `PrincipalResponse` (it is a column on the row already
  loaded; no extra lookup).

### Lockfile
Go regenerates `api/openapi.lock.json` with `make api-bump`; the Java copy
`server/src/main/resources/openapi/openapi.lock.json` is replaced with that
file byte-for-byte. The SPA's generated types (`frontend/src/api/generated`)
are regenerated with `pnpm api:generate` from it.

## Frontend (written once, copied to the other repo; `tools/frontend-drift.sh` must stay clean)

### F1 — service-account detail drawer (`ServiceAccountDetailDrawer.vue`, view mode)
After **Code**, add two read-only `FcDetailField`s rendered as `<code>`:
**Principal ID** (`principalId`) and **OAuth Client ID** (`oauthClientId`).
Each hidden when absent.

### F2 — login-attempt detail dialog (`LoginAttemptListPage.vue`)
When the dialog opens, resolve the targets; render each id as a
`RouterLink` when resolved, else plain `<code>` exactly as today (a failed
lookup — 403, 404, network — is never an error toast; it just stays text).
Following a link closes the dialog.

| Row | Attempt type | Resolution | Link target |
|---|---|---|---|
| Principal ID | any, when `principalId` present | `GET /api/principals/{principalId}` | `type == USER` → route `user-detail` `{id: principalId}`; `type == SERVICE` with `serviceAccountId` → route `service-account-detail` `{id: serviceAccountId}` |
| Identifier | `SERVICE_ACCOUNT_TOKEN` | `GET /api/oauth-clients/by-client-id/{identifier}` | route `oauth-client-detail` `{id: <client row id>}` |
| Identifier | `DEVELOPER_TOKEN` | identifier is a USER principal id | same target as the Principal ID row (`user-detail`) |
| Identifier | `USER_LOGIN` | only when the Principal ID row resolved to a USER | `user-detail` `{id: principalId}` |

Use the existing API modules (`frontend/src/api/…`); add a function only if
none exists for these two reads.

## Tests
Backend, per repo — each must fail under its mutant (CLAUDE.md policy):

| # | Assert | Mutant |
|---|---|---|
| T1 | single-account read of a provisioned service account returns `oauthClientId` **equal to its OAuth client's public `client_id`**, and **not equal** to the client's row id | return the row id |
| T2 | the list endpoint's items carry no `oauthClientId` | populate it on list |
| T3 | a service account with no linked OAuth client → field absent | return "" instead of absent |
| T4 | `GET /api/principals/{id}` for a service account's principal returns `serviceAccountId` equal to the account's id; for a USER principal the field is absent | drop the field / wrong column |

Frontend: type-check and build clean in both repos (`vue-tsc` via the
repo's existing script); `tools/frontend-drift.sh` exit 0.
