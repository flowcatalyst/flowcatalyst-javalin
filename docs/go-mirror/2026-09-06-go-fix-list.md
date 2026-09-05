# Go-side fixes found by the port (2026-09-06)

Everything the parity corpus (`parity/`, 1,145 steps) and the frontend e2e
(`e2e/`, 49 flows) found that is Go's to fix — as opposed to the rulings
already mirrored in `2026-09-05-auth-rulings.patch`. Go tree at `cb83fd5`.
Ordered by what each unblocks. Each item names the Java behaviour so the
two sides land on the same answer; where a parity allow-list entry
(`parity/expected-diffs.json`) exists, fixing Go makes it stale and the
next full corpus run says so — delete the entry then.

## A. Blocking — one-line fixes

| # | Defect | Where in Go | Fix | Unblocks |
|---|---|---|---|---|
| A1 | The seeder writes `schema_type = 'JSON'`; migration 051's CHECK (`chk_msg_event_type_spec_versions_schema_type`) allows only `JSON_SCHEMA \| XSD \| XML_SCHEMA \| PROTO \| PROTOBUF`, so `fcdev start` on an empty database fails its own seed | `internal/platform/seed/event_types.go:159` | `'JSON'` → `'JSON_SCHEMA'` (Java's `Seeder` writes `JSON_SCHEMA`; `go-seed-expected.tsv` already expects it) | The Go column of the frontend e2e (`pnpm e2e:both`); any fresh Go install; the parity harness's double-init workaround can go |
| A2 | Dispatch-job ingest checks `WRITE_DISPATCH_JOBS`, a permission no seeded role grants (the catalogue has `platform:messaging:batch:dispatch-jobs-write`), so every non-anchor SDK service account gets 403 | `internal/platform/shared/sdk/dispatch_job_create.go:63`, `dispatch_jobs_batch.go:144` (and the test fixture at `dispatch_job_create_pg_test.go:214`) | check `platform:messaging:batch:dispatch-jobs-write` (Java: `docs/spec/sdk-ingest.md` §5 D1) | SDK dispatch-job ingest for real tenants |

## B. Contract mismatches the SPA hits today

| # | Defect | Where | Fix |
|---|---|---|---|
| B1 | INTERNAL identity provider cannot be created from the SPA: the drawer sends `oidcMultiTenant` only inside its `type === "OIDC"` spread, but the create DTO has it as a non-pointer `bool`, so huma lists it in `required` and answers 400 VALIDATION | DTO `internal/platform/identityprovider/operations/create.go:28`; drawer `frontend/src/pages/authentication/identity-providers/IdentityProviderCreateDrawer.vue:167-178` | Either: make the DTO member `*bool` with `omitempty` (like `update.go:30`) and re-dump the lockfile, **or** move `oidcMultiTenant: form.value.oidcMultiTenant` out of the OIDC branch so it is always sent. The DTO change is the better one (an OIDC-only flag should not be required of an INTERNAL provider). Java's schema filter follows the lockfile, so re-dump it either way. e2e pin: `authentication-admin.spec.ts` "create, edit, then delete" flips to an unexpected pass |
| B2 | Event-type `clientScoped` is dropped on create: the drawer sends `clientScoped: true`, the constructor hard-codes `false` | `internal/platform/eventtype/entity.go:208` | carry the request value through construction (Java mirrors Go today; fix Java the same day). e2e pin: `catalogue.spec.ts` "a client-scoped event type is tagged Yes" |
| B3 | `hasLoginClient` is never computed (always `false`) on application responses | `internal/platform/application/api/dto.go:89` is declared, nothing assigns it | set it to "an `authorization_code` client is linked to this application" (Java: `application.md` §11 q3). Allow-list entry `**/hasLoginClient` |
| B4 | Dispatch-pool "Delete" dialog says the pool will be *archived*; both servers delete the row | frontend, the dispatch-pool delete confirm | copy fix |

## C. Wrong answers on the wire (Java kept the correct one; allow-listed)

| # | Defect | Where | Java's answer to converge on |
|---|---|---|---|
| C1 | BFF scheduled-job list leaks another client's job to a client-scoped caller when no `clientIds` filter is given | `internal/platform/shared/bff/scheduled_jobs.go` | confine to the caller's client (`docs/spec/bff.md` §7). Open question for both sides: are platform-scoped (`client_id IS NULL`) jobs visible to client users? Java lists them, Go hides them — rule it and align both |
| C2 | Batch event ingest with one invalid item writes the valid items and reports `SUCCESS` for the invalid one | `internal/platform/event/api/api.go` (batch path) | reject the whole batch (Java `IngestApiTest`; `sdk-ingest.md`) |
| C3 | Audit-log ingest stores NULL when `principalId` is absent, so the by-principal filter misses those rows | `internal/platform/shared/sdk/audit_batch.go:34,153` | default an absent `principalId` to the caller |
| C4 | Event deduplication never fires across requests: the unique index is `(deduplication_id, created_at)` and `created_at` is stamped per insert | `internal/migrate/sql/019_partition_messaging_tables.sql:106` | a partition-compatible dedup — e.g. a `(deduplication_id, date_trunc('day', created_at))` window or an ingest-time lookup — `sdk-ingest.md` §5 D6 says what Java does |
| C5 | `client_credentials` for a client with no principal answers **500** `server_error` "Client not properly configured"; RFC 6749 §5.2 wants 400 `unauthorized_client` | `internal/platform/auth/oauthapi/token.go:476,485` | 400 `unauthorized_client`. Java mirrors the 500 today for parity — fix both together |
| C6 | `/api/me` `name` is the email for a principal that has a stored name, and `""` for a service principal | `internal/platform/shared/me/me.go:100-106` | the stored name; the service account's name for a `client_credentials` token |
| C7 | Send-email-code for a principal with no factor at all answers `NO_EMAIL_2FA` | `internal/platform/auth/login/change_password.go:154` | `NO_MFA` first, then `NO_EMAIL_2FA` (`auth-identity.md` §11.7) |

## D. Java additions worth mirroring (deliberate, allow-listed, not defects)

| # | Behaviour | Where in Go | Java |
|---|---|---|---|
| D1 | Password change expires the `__Host-fc_td` trusted-device cookie in the browser as well as revoking the rows | `login/change_password.go` | clears the cookie (`auth-identity.md` §11) |
| D2 | Gated login response carries `rememberDeviceAllowed` so the SPA can hide the checkbox when the domain forbids it (I-Q11) | `auth/twofactor.go` | emits it |
| D3 | Introspection `client_id` = the token's `azp` (RFC 7662); Go answers the first tenant of `clients` and omits it for the anchor | `oauthapi/introspect_revoke.go` | already in `2026-09-05-auth-rulings.patch` (C-Q26) — listed here because the allow-list still carries it until the patch is applied |

## Owner rulings still open (both sides wait)

- `$schema` on every JSON response (huma adds it; Java never will) — wire
  break for anyone, or let it go?
- WebAuthn ceremony option defaults (go-webauthn vs yubico shapes; browsers
  accept both) — any non-browser client reading them?
- The OpenAPI documents (`/api/openapi.json` etc.): Java should serve the
  vendored lockfile verbatim — any client parsing these at runtime?
- Platform-scoped scheduled jobs for client-scoped callers (C1's second half).

## Rulings of 2026-09-06 (`docs/rulings-2026-09-06.md`) — what they add for Go

The owner ruled every open question the same day. The Go hand-off is now
the list below; items A1–C7 above stand unless amended here.

| Ruling | Go change | Amends |
|---|---|---|
| #3 | Apply `2026-09-05-auth-rulings.patch` (introspection `client_id` = `azp`, and the rest of the batch) | D3 |
| #6 | `shared/bff/scheduled_jobs.go`: keep hiding platform-scoped jobs from client-scoped callers **and** stop listing other clients' jobs to them | C1 |
| #7 | `eventtype/entity.go:208`: carry the request's `clientScoped` | B2 |
| #8 | `identityprovider/operations/create.go:28`: `OIDCMultiTenant *bool` with `omitempty`; re-dump the lockfile and hand it over (Java re-vendors it) | B1 |
| #9 | `shared/sdk/dispatch_job_create.go:63`, `dispatch_jobs_batch.go:144`: check `platform:messaging:batch:dispatch-jobs-write` | A2 |
| #10a | `event/api/api.go` batch: **partial success with honest per-item results** — persist the valid items, report the invalid item's failure in its `results[]` slot (never `SUCCESS`) | C2 (was: reject the whole batch) |
| #10b | `shared/sdk/audit_batch.go:34`: `PrincipalID` required (non-pointer, in the DTO's `required`), refuse an item without it; re-dump the lockfile | C3 (was: default to the caller) |
| #11 | `oauthapi/token.go:476,485`: 400 `unauthorized_client` | C5 |
| #13 | Principal `/{id}/…` sub-routes apply the by-id tenancy check; role / application-access / developer-credential mutations check the coarse permission **before** loading (no 404-vs-403 oracle) | new |
| #14 | Scheduled-job sync `archiveUnlisted` archives only the calling application's unlisted jobs (Java already narrows: `SyncScheduledJobs`, X-02(a)) | new |
| #15 | `POST /api/service-accounts/{id}/token` writes an audit row: actor, account, never the token; a failed audit does not fail the mint | new |
| #16 | Service-account codes: `app:<code>` is a reserved namespace — provisioning writes it, the create API refuses a user code starting with `app:` | new |
| #18 | `/api/config/platform` fallback brand `FlowCatalyst` (notify already via the patch) | — |
| #1, #4, #5, #12, #17, #19, #20 | nothing for Go | — |

Still Go's, unchanged: A1 (seeder literal — first), B3 (`hasLoginClient`),
B4 (dispatch-pool copy, frontend), C4 (dedup index), C6 (`/api/me` name),
C7 (`NO_MFA` before `NO_EMAIL_2FA`), D1 (trusted-device cookie on password
change), D2 (`rememberDeviceAllowed`).

Two lockfile re-dumps come out of this (#8, #10b); do them together and
hand the file over once.

## After fixing

Run the corpus (`PARITY_GO_SRC=… mvn -q -pl parity -am test -Dtest=ParityRunTest -Dsurefire.failIfNoSpecifiedTests=false`);
each fixed item turns its allow-list entry stale and the run names it —
delete the entry. A1 also lets `e2e/runner/side.ts`'s Go start work, so
`pnpm e2e:both` becomes the e2e command and the two `test.fail` pins in
B1/B2 flip once the SPA/contract changes land.
