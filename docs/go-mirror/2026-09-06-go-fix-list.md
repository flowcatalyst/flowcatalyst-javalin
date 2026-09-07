# Go-side fixes found by the port (2026-09-06)

> **Re-synced against Go `b3c75cd` the same night.** The owner's Go agent
> had already landed `ba45035`, `3c22690`, `642f5da`, `491d961`, `b3c75cd`,
> which close A1, A2, B1, B2 (create/update only — see G1), B3, B4, C1, C3
> (Go's way — see G5), C4, C5, C6, C7 and D1. The corpus against that HEAD
> (this repo, 2026-09-06 01:00): 8 DIFF steps, all explained below, and 11
> allow-list entries gone stale and deleted. **What is still Go's is §G.**

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

## G. Still Go's after the re-sync (2026-09-06, Go `b3c75cd`)

| # | Item | Where | Note |
|---|---|---|---|
| G1 | `clientScoped` still never reaches the SPA on Go: (a) `EventTypeResponse.fromEntity` never copies it; (b) **the SPA creates through `POST /bff/event-types`, whose `bffCreateEventTypeRequest` has no `clientScoped` and whose `CreateCommand` literal omits it** (`shared/bff/event_types.go:178`), so `3c22690` fixed a route the drawer does not use. The e2e flow "a client-scoped event type is tagged Yes" fails on Go for this reason | `internal/platform/eventtype/api/dto.go:66`; `internal/platform/shared/bff/event_types.go:84,178` (+ the BFF update) | carry it on the BFF create/update and the `/api` response; re-dump the lockfile |
| G2 | `setDifference` iterates a map, so `added` / `removed` on `PUT /api/principals/{id}/roles` and the application-access counterpart come back in random order | `internal/platform/principal/api/api.go:1766` | range over the request's slice instead (Java answers request order); allow-list `bff* confinement-assign-roles /added` |
| G3 | Ruling #6 reaches only the BFF list: `GET /api/scheduled-jobs` still shows platform-scoped jobs to client-scoped callers (`FilterClientScoped` passes `nil` client ids) | `internal/platform/scheduledjob/api` list | Java mirrors Go there for now; align both when Go changes |
| ~~G4~~ | **Done in Go `b422466`** (same `RESERVED_CODE` and message as Java). Ruling #16 mirror: `app:` is a reserved service-account namespace — Java's create path answers 400 `RESERVED_CODE` ("codes starting with 'app:' are reserved for application service accounts"); the value object accepts `app:<code>` | `internal/platform/serviceaccount/operations/create_credentials.go` + `validate.CodePattern` | use the same code and message |
| ~~G5~~ | **Resolved by Go `ece54fe`: `principalId` is required per item (`BAD_REQUEST`, "principalId is required"), Java follows the same night.** Was: ruling #10b says audit-log `principalId` becomes *required*; Go `491d961` instead defaults it to the ingesting principal (and Java does the same). Both sides agree today; the lockfile still marks it optional | `internal/platform/shared/sdk/audit_batch.go` | keep the default (amend the ruling) or make it required on both and re-dump |
| G6 | Rulings #13, #14, #15 mirrors: (13) principal `/{id}/…` sub-routes tenancy-checked and mutations gated before load — Java answers 404 for an out-of-scope id exactly as for a missing one (PR-4); (14) sync `archiveUnlisted` narrowed to the calling application; (15) the token mint emits `platform:iam:serviceaccount:token-minted` (account, its SERVICE principal, `expiresInSeconds`, `permissions` — never the token) with audit operation `MintServiceAccountTokenCommand`, after the mint and best-effort | see the rulings table above | Java done for all three |
| G8 | **The profile page's 2FA card is blank on Go for anyone without a trusted device** (frontend e2e, the first Go run 2026-09-06): `GET /auth/2fa/trusted-devices` answers `{"devices": null}` for an empty list, the SPA's `TwoFactorSection` does `devices.length` in its template, the render throws and Vue drops the card — so no user can enrol TOTP from the profile. The corpus had accepted the nil-slice `null` as a wire difference; the SPA does not | `internal/platform/auth/login/twofactor_selfservice.go` (trusted-device list) | answer `[]` (`make([]…, 0)`), as Java does; the `**/permissions` nil-slice entry deserves the same look |
| ~~G7~~ | **Done in Go `ece54fe`** (per-item `BAD_REQUEST` with the reason; valid items persisted). Java follows the same night. Ruling #10a: batch event ingest reports every persisted event `SUCCESS` and is all-or-nothing today — the ruled behaviour is partial success with honest per-item results | `internal/platform/event/api/api.go:187` | Java changes first (Phase 5b item 3), then mirror |

**Not a Go defect, but the owner's tree:** `flowcatalyst-go/frontend/dist`
was two weeks behind `frontend/src` (2026-08-24 vs `642f5da`), and the Go
binary embeds it. The e2e runner now builds Go's `fcdev` in a scratch copy
with the freshly built SPA, so the runs here are unaffected; a `make
frontend` there keeps a hand-built Go binary honest.

## After fixing

Run the corpus (`PARITY_GO_SRC=… mvn -q -pl parity -am test -Dtest=ParityRunTest -Dsurefire.failIfNoSpecifiedTests=false`);
each fixed item turns its allow-list entry stale and the run names it —
delete the entry. A1 also lets `e2e/runner/side.ts`'s Go start work, so
`pnpm e2e:both` becomes the e2e command and the two `test.fail` pins in
B1/B2 flip once the SPA/contract changes land.

## G9 — mail is sent inline on the login/MFA/password-reset request (2026-09-06)

`internal/platform/shared/email` `SMTPService.Send` runs `smtp.SendMail` synchronously
inside the request that needs the mail (MFA code, reset link, notifications). A slow or
unreachable mail server holds the login request for the SMTP timeout. The Java port moves
mail to a `mail_outbox` table drained by a background sender with the dispatch-job backoff
ladder (`docs/spec/mail-outbox.md`); the request answers once the row is written. Go
should do the same: enqueue, answer, send in the background.

## G10 — NATS deliveries are never acknowledged; a consumer's ack-resolution key is its registration key, not its own identifier (present in Go too)

`docs/spec/router.md` §7.1: `Identifier()` is "the stable string used as `QueueIdentifier`
on every polled message and as the key for ack/nack resolution". §7.4: the NATS backend's
identity is `<stream>/<consumer>`, which for a queue configured under a different name (the
common case — the config's queue name is an operator-chosen label, not the broker's stream/
consumer pair) differs from the config key the manager registers the consumer under. Both
sides register consumers keyed by the config name and resolve ack/nack by the same key, so
for NATS every delivery fails resolution and nothing is ever acked — JetStream redelivers on
ack-wait until `MaxDeliver` and then dead-letters the message silently.

Where in Go: `internal/router/manager.go:~1261` (`m.consumers[qc.Name] = rc`, keyed by the
config queue name) vs `internal/queue/nats/nats.go` (`QueueIdentifier: q.identifier`, the
`<stream>/<consumer>` pair) — the same registration/resolution mismatch as the Java `G10`
fix below, just without the second, identifier-keyed index Java now maintains.

Measured in the router bench (`bench/router/results/java-nats-q1-c1.server.log`, pre-fix):
50,000 seeded, 3,000 delivered, stream depth rising to 51,000 — every delivered message logs
`"ack skipped: queue BENCH1/router is no longer registered (message bench-N)"` (3,000
occurrences) and redelivers forever. Identical on Go for the same reason.

Java fix: `RouterManager` keeps its existing name-keyed `consumers` map for reconfigure's
wanted-set diffing (§8.2) and adds a second map keyed by `Consumer#identifier()`, maintained
on every put/remove (register, replace, reconfigure-build, stop, forget); `RouterManager#consumer`
(ack/nack resolution, and every caller that resolves from a `message.queueId()` or an
in-flight entry's `queueIdentifier()` — `QueueBroker`, `InFlightRoutes` force-ack,
`StallDetector`, `queueMetricSources()` feeding `BrokerStatsCache`/`GET /monitoring/queues`/
the Prometheus `queue` and `consumer` labels) now resolves through that index; callers that
genuinely want the config name (`RouterServer#stopSources`/`#shutDownSources`,
`RouterShutdown#consumersOf`) use the pre-existing name-keyed `#activeConsumer` instead.
`RouterManager#retireLingeringConsumers` had the same name-vs-identifier mismatch one level
down (comparing a lingering deque's queue-name key against `InFlightTracker#countForQueue`,
which is keyed by identifier) and is fixed the same way, using each lingering consumer's own
`identifier()`.

Go fix: key `m.consumers` (and any lingering/detached-consumer bookkeeping) by
`Consumer.Identifier()` as well as, or instead of, the config name, and resolve ack/nack
through that key — the identity the queue backend itself reports, not the label an operator
gave it in config.

## G11 — the in-flight tracker's broker-id index is keyed on the bare broker id, so it collides across NATS streams and cross-wires acks

`docs/spec/router.md` §2.3: `InFlightMessage` carries two tracker keys — `MessageID` (key #1,
the application's global dedup key) and `BrokerMessageID` (key #2). §7.4: the NATS backend's
`BrokerMessageID` is `<streamSeq>:<consumerSeq>` — unique only *within one stream*. Every
stream's Nth message gets the same broker id, so with more than one NATS queue configured,
`InFlightTracker`'s broker-id index routinely holds one map slot per *broker id*, silently
shared by every queue that happens to be at that sequence number.

`InFlightTracker#register` checks that index first: an arriving message whose broker id
matches an existing entry is classified as a **redelivery** of whatever queue got there first,
regardless of which queue the arriving message actually came from. That swaps the existing
entry's receipt handle to the new arrival's handle and returns without ever tracking the new
arrival under its own application id. The corrupted entry is later ACKed on its *own* queue's
consumer, but with the *other* queue's receipt handle — the broker rejects it because that
handle was never issued on this consumer.

Measured in the router bench with 8 NATS queues (`bench/router/results/java-nats-q8-c1-fixed.server.log`,
read-only evidence, pre-fix): 1,272 occurrences of `"nats: no pending message for receipt
BENCH1:9 on queue BENCH4/router"` and the same pattern for other sequence numbers — every one a
receipt handle from one stream (`BENCH1`) presented to a different stream's consumer
(`BENCH4/router`) because both streams' 9th message shared broker id `9:9`.
Single-queue runs never see this: with one queue every broker id really is unique.

Where in Go: `internal/router/inflight.go` — `byBroker map[string]*common.InFlightMessage`,
keyed on the bare `im.BrokerMessageID` (`Register`, `EnsureTracked`, `Remove`, and the
consistency sweep all read/write it unscoped). Go has the exact same key shape as Java did
before this fix, so it most likely shares the defect; not reproduced against Go directly here.

Java fix: `InFlightTracker`'s broker-id index (`byBrokerId`) is now keyed by
`(queueIdentifier, brokerMessageId)` instead of the bare broker id, so a lookup can never
cross queues. The application-id index (`byMessageId`, key #1) is unchanged — application
message ids are the app's own dedup key and are unique by contract, so no queue scoping is
needed there. Audited every reader of the broker-id index: the only one is
`InFlightTracker#register` itself (the dedup/`ExternalRequeue` classification on poll,
`RouterManager#routeOne`); `QueueBroker#withFreshestHandle` (ack/nack) and the dashboard/API
surfaces (`InFlightRoutes`, `RouterApi`, `Wire`) all read the freshest handle through
`byMessageId`, so they only ever saw the corruption *indirectly*, once `register` had already
poisoned an entry's receipt handle — scoping the one writer/reader fixes all of them.

Go fix: key `t.byBroker` by `(QueueIdentifier, BrokerMessageID)` instead of the bare
`BrokerMessageID` — same change as Java's, everywhere the map is read or written
(`Register`, `EnsureTracked`, `Remove`, the consistency sweep in the stall-detector path).

## G12 — the consumer poll loop's backpressure gate is a fixed 2 s sleep, which starves the workers on a fast broker (and a partial batch adds a second, separate pause on top)

`docs/spec/router.md` §3.2 step 2 (pre-fix): when no pool has spare buffer capacity
(`queueSize >= max(concurrency×20, 50)`), the consumer loop "pauses 2 s and retries." Step 5
(pre-fix): a batch smaller than the poll size ("partial") additionally slept 500 ms before the
next poll, on the theory that a partial batch means the queue is draining. Both are fixed
`Thread.sleep`s, independent of when the condition that caused them actually clears.

Measured on NATS JetStream (fetch ≈ 1 ms) with `Pool.Config(concurrency=256)` (Java, pre-fix,
`bench/router` harness, single CPU): one queue alone drains at 7,916 deliveries/s with the
router at 99% CPU — 256 workers × ~33 ms mediation ≈ 7,750/s, i.e. the workers are the limit,
correctly. **Eight** queues sharing one pool buffer on the same box drain at only 1,312/s with
the router at 22% CPU: eight pollers fill the 5,120-slot pool buffer in well under a second, all
enter the capacity pause, the workers drain the buffer in roughly 0.7 s and then sit idle for the
remainder of the fixed 2 s every poller is sleeping through. The log shows 21 "capacity returned;
resuming queue …" transitions in a 38 s run — 21 pollers that were ready to resume long before
their sleep let them. The 500 ms partial-batch pause is the identical bug in miniature: it holds
a loop back from a broker that may already have the next batch ready.

Both are fixed constants, not derived from anything the loop can observe, and both hold the loop
back from work regardless of whether the condition that justified the pause is still true a
moment later — the general shape CLAUDE.md's "no tuning" note and `docs/spec/admission.md` §0
rule out for a request path; the same reasoning applies to this poll path.

Java fix (`io.flowcatalyst.router.manager.ConsumerLoop`, `io.flowcatalyst.router.pool.Pool`,
`io.flowcatalyst.router.manager.CapacityGate`, 2026-09-07): the capacity pause parks the loop,
**untimed**, on a per-manager `CapacityGate` (a monotonic generation counter under an intrinsic
lock — closes the classic lost-wakeup race a bare `Condition` would have between a waiter's last
check and the moment it actually parks). Every `Pool` is wired to signal the gate the moment its
`queueSize` crosses back under its capacity threshold (`Pool#onCapacityFreed`, fired on the
crossing only, never on every admission or completion, so a busy pool costs one wakeup per
capacity outage, not one per message); a reconfigure or eviction that adds, removes, or drains a
pool signals it too, since either can change what `anyPoolHasCapacity`/`poolsHaveCapacity`
answer. The fixed sleep survives in exactly one place: a loop started against a manager with
*zero* pools registered has nothing that could ever signal it (in production
`RouterManager#reconfigure` always creates `DEFAULT-POOL` before any loop starts, so this is a
defensive fallback, not a real steady-state path). The partial-batch pause is removed outright —
a partial batch now re-polls immediately, exactly like a full one (owner ruling 2026-09-07).

Pinned by `ConsumerLoopTest`: `resumesPromptlyWhenCapacityReturns` — no poll for 300 ms while
every pool stays full, then the next poll within 100 ms of capacity actually returning (elapsed
measured from the signal, not from loop start); mutating the park back to `Thread.sleep(2s)`
fails it at ~1.7 s actual vs. the 100 ms bound. `stopsPromptlyWhileParkedForCapacity` — interrupt
while parked returns in well under 500 ms. `batchesRepollImmediately` — a partial batch's next
poll lands within 100 ms, not 500 ms later.

Go fix: the same two fixed sleeps live in `internal/router/manager.go`'s poll loop (the 2 s
"all pools full" pause and the partial-batch pause after `route()`) — not reproduced against Go
directly here, but the code shape is identical to Java's pre-fix, so it is expected to show the
same collapse on a fast broker with several queues sharing pool capacity. Recommended: an
equivalent per-pool/per-manager "capacity freed" signal (Go's pools already know their own
`queueSize` on every enqueue/dequeue) that the poll loop selects/waits on instead of
`time.Sleep(2 * time.Second)`, and drop the partial-batch `time.Sleep` entirely so a partial batch
re-polls immediately like a full one.

## G13 — NATS JetStream `Poll` (Java): three revisions chasing a throughput collapse under several queues; landed on a genuine listener (owner ruling 2026-09-07) instead of any poller shape. Go still polls (`Fetch`) and should move to the same listener shape (`Consumer.Consume`), not because it has been *proven* to collapse the same way, but because a poller is the wrong shape for NATS on both sides

`docs/spec/router.md` §7.4 "Poll". Bench throughout: `bench/router`, NATS JetStream, 50,000
messages, pool concurrency 256, one router.

**Revision 1 — the per-poll ephemeral subscription.** Java's `NatsQueue.poll` (pre-fix) called
`ConsumerContext#fetch`, which opens a **fresh** ephemeral core-NATS subscription for every poll
and closes it the moment its own local wait budget elapses — set to *exactly* the `expiresIn`
sent to the server (jnats `NatsFetchConsumer`, `maxWaitNanos = expiresInMillis`), with **no
margin**, unlike Go's `jetstream.Consumer.Fetch` (`nats.go` v1.52.0 `jetstream/pull.go`,
`pullConsumer.fetch`), which has the same per-call-ephemeral-subscription shape but waits
`Expires + 1s` — a full second's margin — before giving up locally. Confirmed by instrumentation,
not assumed: counting `msg.metaData().deliveredCount() > 1` per fetch on the pre-fix build
(`QUEUES=4`, 1 CPU) found only 2 redeliveries in the whole run, narrowing rather than confirming
the originally-suspected "lost until ack-wait redelivers" mechanism. Fix: bind **one**
`JetStreamSubscription` for the life of the queue and call `subscription.fetch(batch, maxWait)`
on it every poll instead. Result: `QUEUES=4` 374/s (133 s drain) → 6,930/s (6.0 s drain), an 18×
improvement. `QUEUES=8` improved much less (1,361→1,385/s at 1 CPU) — a second, separate
bottleneck, not this one, remained at higher queue counts.

**Revision 2 — no-wait-first fetch.** A follow-up measurement (a per-second sink sampler) showed
`QUEUES=8`'s shortfall was a **tail**, not a uniform slowdown: 49,954/50,000 delivered in the
first few seconds, the last 46 straggling in over ~20 s — exactly `poll-timeout`. Root cause:
`JetStreamSubscription#fetch` (jnats source, `NatsJetStreamPullSubscription#_fetch`) sends one
pull request for the whole batch and keeps reading until it is either fully satisfied or its own
`expiresIn` lapses — it never returns early just because *some* messages arrived, so a queue with
fewer than `batch` messages left blocks for the full `poll-timeout` before handing back what it
already has. Fix: two phases, neither waiting for a full batch — a `NoWait` pull read back with a
short, network-RTT-bounded timeout, and only if that yields nothing, a genuine wait for the FIRST
message followed by the same immediate drain of anything else already sent. This is closer to
Go's documented behaviour for `Fetch` in general, though `internal/queue/nats/nats.go`'s own call
site does not use the `NoWait`-first shape.

**Revision 3 — a genuine listener, superseding both of the above (owner ruling 2026-09-07).**
Both revisions above were still `NatsQueue` **polling** — issuing its own timed pull requests on
a schedule of its own choosing. The owner ruling that landed on the Rust router's agent first,
and applies here the same way: NATS must be a genuine subscription/listener; polling is an
SQS/Postgres limitation (those brokers have no other shape to offer), not the design NATS itself
calls for. `NatsQueue` now opens **one** standing `MessageConsumer`
(`ConsumerContext#consume(ConsumeOptions, MessageHandler)`, `batchSize=max-messages`) for the
queue's whole life: the NATS client keeps a pull request continuously outstanding and hands each
message to a handler — `buffer.put(msg)`, a `BlockingQueue` bounded at `max-messages` — the
moment it arrives, on the client's own delivery thread; `put` blocking when full **is** the
back-pressure that stops the client asking for more. `poll(max)` is `buffer.take()` (untimed —
free on a virtual thread) for the first message, then `drainTo` for the rest already buffered.
There is no poll cycle left to time and no `expiresIn` to race, so revisions 1 and 2's defects
cannot recur — the shape that caused both (`NatsQueue` issuing its own timed pull requests) no
longer exists. `poll-timeout-ms` on the URI is parsed but unused for NATS now (documented on
`NatsQueueUri`, kept as a parameter). Pinned without a live broker
(`NatsQueueTest`, a hand-written `FakeMessageConsumer` plus direct seeding of the package-private
`buffer`): already-buffered messages return in FIFO order, up to `max`, without waiting (<100 ms);
an empty buffer blocks until a message is handed in, then returns promptly; `close()` while
blocked unblocks within 500 ms; the buffer is bounded — a background thread simulating the
handler blocks on `put` when full and unblocks after one `poll` (mutant: an unbounded buffer
fails this test). Result (`bench/router`, pool concurrency 256): `QUEUES=1` 7,671/s (6.4 s),
`QUEUES=4` 6,401/s (6.6 s), `QUEUES=8` at 2 CPU 3,959/s (13.3 s) — all clear the ≥3,500/s / <15 s
bar with no tail. `QUEUES=8` at 1 CPU: 3,338/s, 15.08 s — a large improvement (was 1,361/s) but
0.08 s over the bar; a per-second timeline shows the count stalling for several seconds around
~47.4k/50k on **both** 1 and 2 CPU runs before catching up, a residual mechanism not root-caused
here. Candidate not yet tested: `ConsumeOptions` was not given an explicit `expiresIn`, so its
internal per-refill pull requests default to 30 s (`BaseConsumeOptions.DEFAULT_EXPIRES_IN_MILLIS`)
— worth checking whether a shorter explicit value changes the stall, but not changed speculatively
here (`feedback_no_tuning.md`: a knob is not owed to a problem that has not been diagnosed).

**Go**, `internal/queue/nats/nats.go`, still calls `jetstream.Consumer.Fetch` in
`Poll(ctx, max)` — a poller, the same shape Java just moved away from on the owner's explicit
ruling that NATS should not be polled at all. Go's own client (`nats.go` v1.52.0
`jetstream/consumer.go`) already has the listener-shaped equivalent: `Consumer.Consume(handler
MessageHandler, opts ...PullConsumeOpt) (ConsumeContext, error)` — directly analogous to Java's
`ConsumerContext#consume`. This is **not** filed as a confirmed throughput defect in Go — nobody
has reproduced the collapse against Go's implementation, and revision 1's finding (Go's 1 s
`Fetch` margin) means Go was likely never exposed to that specific window. It is filed because
the owner ruling is about the *right shape for NATS*, independent of whether the wrong shape has
yet been measured to misbehave on the Go side: recommended follow-up is `Consume` with a handler
pushing into a bounded Go channel, `Poll` reading from that channel instead of calling `Fetch`.

**Revision 3's own follow-on defect, found before it shipped: an untimed `poll()` looks hung to
the consumer-health/stall watchdog.** Revision 3's `poll()` blocks **untimed** in
`BlockingQueue#take` waiting on the broker (correct — see above), but
`ConsumerLoop#lastAlive`/`ConsumerSupervisor` judged liveness solely off `lastPoll`, which only
advances when `poll()` *returns*. An idle continuous subscription and a genuinely hung one are
therefore indistinguishable once the poll has been running longer than
`ConsumerSupervisor#STALL_THRESHOLD` (60 s, `docs/spec/router.md` §5 row 47) — the watchdog
restarts a perfectly healthy, merely-idle consumer, and keeps doing so every threshold, forever.
This is not hypothetical: a Go router carrying the equivalent change (untimed poll, `lastPoll`-only
liveness) was measured collapsing to 1,100 deliveries/s under exactly this "stalled consumer
detected (poll is hung) → restart" cycle. Java had not shown it only because no bench run had
idled a NATS queue past 60 s.

Fix: `Consumer` (queue contract) gains `default Optional<Instant> lastBrokerActivity() { return
Optional.empty(); }` — evidence the broker is alive, independent of whether `poll()` has returned.
`NatsQueue` overrides it: "now" for as long as its connection reads `CONNECTED` (jnats' simplified
`consume` API exposes no positive per-heartbeat callback, only a negative
`ErrorListener#heartbeatAlarm` for a *missed* one, so the connection's own PING/PONG keepalive —
independent of the JetStream idle-heartbeat — stands in as the positive signal), falling back to
the last time a message actually reached it otherwise. Every other backend (Postgres, SQS) keeps
the default: their `poll()` returns within a bounded time, so a stale `lastPoll` already IS the
staleness signal and there is nothing this method would add. `ConsumerLoop` tracks whether a poll
is currently on the stack (`pollInProgress`, set/cleared immediately around the `Consumer#poll`
call) and `lastAlive()` consults `lastBrokerActivity()` while it is true, taking whichever of that
or the existing poll/pause-based instant is later — the broker signal can only ever rescue a loop
the old logic would call stale, never hide one it would call fresh, so a genuinely hung poll
(broker activity as stale as the poll itself) still gets flagged and restarted.
`ConsumerSupervisor#stalled(ConsumerLoop)` — the method `RouterServer#restartStalledLoops` actually
calls on every housekeeping tick — was ALSO reading `lastPoll` directly, bypassing `lastAlive`
entirely; fixed to read `lastAlive()`, which incidentally also protects the existing
capacity-pause case (`ConsumerLoop`'s own `pausedForCapacity` liveness) from the same restart path,
not just the readiness API that already used it. Pinned in `ConsumerLoopTest` (a fake consumer
blocked in `poll()` past the stall threshold with recent broker activity is not restarted; the same
fake with stale broker activity IS restarted — mutant: dropping the `pollInProgress`/broker-activity
branch from `lastAlive()`, or routing `stalled(ConsumerLoop)` back through `lastPoll()`, fails the
first case) and `NatsQueueTest` (`lastBrokerActivity()` advances on each delivered message; a second
poll with nothing new buffered does not advance it again). No timed park, no knob.

Go should apply the equivalent fix once it lands the `Consume`-based listener recommended above:
whatever heartbeat/stall mechanism watches Go's consumer loops needs the same second liveness
signal, or it inherits the identical restart-storm collapse the moment its own `Poll` stops
returning on a timer.
