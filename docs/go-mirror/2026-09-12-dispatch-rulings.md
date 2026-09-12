# Go hand-off — deployed-dispatch rulings (2026-09-12)

For the Go agent. Supersedes parts of
`docs/go-mirror/2026-09-11-deployment-env-handoff.md`. Authority for the
design is `docs/spec/deployed-dispatch.md` §3; this file records the owner's
rulings of 2026-09-12 and two orchestrator rulings taken on evidence.

Read §0 first — it corrects the earlier hand-off.

---

## 0. Correction: §1 and §2 of the 2026-09-11 hand-off are already done in Go

The earlier hand-off asked Go to honour `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT`
and the three OIDC TTLs. **Go already had, at commit `e87b88d` ("honour the
deployed environment: dispatch callback + OIDC TTLs"), committed 2026-09-12
09:31 — five commits *past* `466dc11`**, our last sync point, not an ancestor
of it as first written here (the other four are SDK/fcdev version bumps). It
landed after that hand-off was written and neither side noticed; `docs/deployments.md`'s "Go doesn't read this
name either" note is stale and is being corrected.

Java has now built the same thing (2026-09-12). Do **not** redo it. What is
left is reconciliation:

### 0.1 Two names Java has and Go does not — Go should add them

Java follows its own convention (`Env.java` header): the `FC_*` name is
canonical, the deployed name is the alias. Go reads only the deployed name for
two of the three TTLs:

| Setting | Go today | Java today |
|---|---|---|
| Access TTL | `envIntAlias("FC_JWT_ACCESS_TOKEN_TTL_SECS", "OIDC_ACCESS_TOKEN_TTL", …)` | same — **match** |
| Session TTL | `envInt("OIDC_SESSION_TTL", …)` — no `FC_*` name | `FC_SESSION_TTL_SECS` → `OIDC_SESSION_TTL` |
| Refresh TTL | `envInt("OIDC_REFRESH_TOKEN_TTL", …)` — no `FC_*` name | `FC_REFRESH_TOKEN_TTL_SECS` → `OIDC_REFRESH_TOKEN_TTL` |

Harmless in the deployed environment (the IaC sets only the `OIDC_*` names, so
both sides resolve identically), but it is an avoidable asymmetry. Suggested Go
change, matching the access-TTL line directly above it in `envcfg.go`:

```go
SessionTTLSecs:      positiveOr(envIntAlias("FC_SESSION_TTL_SECS", "OIDC_SESSION_TTL", 0), 24*60*60),
RefreshTokenTTLSecs: positiveOr(envIntAlias("FC_REFRESH_TOKEN_TTL_SECS", "OIDC_REFRESH_TOKEN_TTL", 0), 7*24*60*60),
```

Low priority — flag if you disagree rather than doing it silently.

### 0.2 A Java defect Go does not have — Java is fixing it, no Go change

Go's `positiveOr` sends a zero, negative or unparseable TTL to the default.
Java's new `longAlias` only does that for an *unparseable* value; a parsed
negative passes straight through, and then:

- `OIDC_SESSION_TTL=0` or negative → `TokenIssuer.Config` throws → **the
  platform refuses to start**, where Go quietly uses 24 h.
- `OIDC_REFRESH_TOKEN_TTL=-5` → **Java issues refresh tokens that expired five
  seconds ago**, silently. Nothing validates this one.

Go's behaviour is the correct one and Java is being changed to match
(`positiveOr` semantics on all three). Recorded here so the Go agent does not
"fix" Go toward Java's current shape. **No Go change.**

---

## 1. The owner's rulings, 2026-09-12 (§3 design)

Four questions were put to the owner after the Java-side survey found that the
settled §3 text could not be built as written. The answers:

### R1 — the priority carrier: accept `queue`, validated to the two values

**The problem found.** Settled item 2 makes `msg_subscriptions.queue` the
priority carrier and describes it as "read-only and set by nothing today".
It is worse than that: **nothing on either side can write it.** Go's
`internal/platform/subscription/operations/create.go` and `update.go` never
mention `queue`; the repository reads and writes the column
(`repository.go:97,126,165,307`) and the DTO returns it
(`api/dto.go:172`), so it round-trips whatever a legacy Go-era row already
holds and is `NULL` for everything created since. Java is identical. Verified
both sides.

Meanwhile the shipped SPA **does** send `queue` on subscription create and
update, and its create form *requires* the field (`e2e/fixtures/catalogue.ts`
defaults it to `"default"`). Both servers discard it silently, because both
ignore unknown JSON properties. A user fills in "Queue" and the value vanishes.

**Ruling:** add `queue` to the subscription create and update request shapes,
validated to `DEFAULT` or `HIGH_PRIORITY`. This makes the SPA's existing field
work rather than adding a new one.

**R1a — validation is case-insensitive (owner, 2026-09-12).** Java cannot
change the SPA: the server embeds a bundle built from the Go repo's frontend
(`Frontend.embeddedOrNone()`, currently Go frontend `13e80c9`), and the create
drawer sends `queue: "default"` with the field required. Strict validation
would 400 every subscription create from the UI, and the fix would live in the
other repo.

So both sides validate the two names **ignoring case**: `"default"` maps to
`DEFAULT`, `"high_priority"` to `HIGH_PRIORITY`, and only genuinely unknown
text is rejected. The SPA keeps working unchanged today. Turning the field into
a two-value dropdown is then a tidy-up the Go frontend can do whenever
convenient — **not** a prerequisite, and not a reason to sequence the repos.

**Consequences to plan for:**
- It is a wire-contract change: the OpenAPI lockfile, the SDKs, and both
  servers. `queue` is currently in `SubscriptionResponse` only, in both the
  lockfile and `sdk/openapi/openapi.json`.
- Legacy rows may hold arbitrary values — staging shows Integral queue segments
  like `workers-high`, not `HIGH_PRIORITY`. Write validation cannot reach them;
  see R5 for what the read path does.

### R2 — FIFO dedup id: unique per publish attempt

**The conflict found.** Settled item 3 says the dedup id is the job id. Java's
`DispatchPublisher` records ledger rule **R-18**: a publisher "MUST NOT set any
queue-native deduplication id … dedup is the platform's own `status`/
`scheduled_for` machinery, never the broker's." The hazard is concrete —
`StaleQueuedJobPoller` re-publishes jobs stranded in `QUEUED`, and with dedup
id = job id any re-publish inside SQS FIFO's five-minute dedup window is
silently dropped, stranding the job again.

**Ruling:** the dedup id is **the job id plus the publish attempt**, so it is
never reused. SQS FIFO requires *some* dedup id (or content-based dedup on the
queue); this supplies one while honouring R-18's intent — the broker never
silently swallows a re-publish, and `status`/`scheduled_for` remain the only
deduplication authority. R-18 stands, with this clarification.

Message group id is unchanged: the dispatch job's message group.

### R3 — the router-config endpoint is authenticated with a shared secret

**The problem found.** The platform must serve its router-config document
somewhere the router can GET it. The router runs `AUTH_MODE=NONE`, with no
database and no Secrets Manager, and reaches the platform in-VPC as
`http://fc-platform:8080` — but 8080 is also the public ALB, so any path there
is internet-reachable. The document lists client identifiers, pool codes and
SQS queue URLs.

**Ruling (revised, same day — this is the one to build).** An earlier answer
chose a shared-secret header; on seeing that the fc-router task role has
deliberately no Secrets Manager access, the owner changed it. **The endpoint
carries no authentication and is instead kept off the public ALB**: the
platform serves it on its **internal listener** (`FC_METRICS_PORT`, 9090),
which is not internet-facing, and the router reaches it through a **new
Service Connect alias**.

Consequences:
- **§3's "The router needs no new setting" survives** — the router learns the
  endpoint from the `FLOWCATALYST_CONFIG_URL` it already has. No token on
  either side, no secret in a task definition, no IAM widening.
- **This blocks on infrastructure.** The router cannot fetch config until the
  Service Connect alias exists, so the IaC change gates the feature end to end.
  Flag it early rather than discovering it at integration.
- The platform's internal listener now serves an application endpoint, not
  just `/metrics`. Worth a note where that listener is set up, so the next
  reader does not assume it is metrics-only.

Path: `/api/config/*` is already taken on the platform by the platform-config
aggregate (`/api/config/{app}/{section}/{property}`) and by the public legacy
`/api/config/platform`. Pick a different path; Java is proposing
`/api/dispatch/router-config`, beside the existing dispatch routes.

### R4 — remove the fixed single-queue branch entirely

**Ruling:** remove it outright, not just for dev. Any deployment with
`FC_DEFAULT_BROKER=postgres` must have a config URL; there is one code path,
as §3 intends.

**Consequence to accept knowingly:** a bare server run with
`FC_DEFAULT_BROKER=postgres` and no config URL stops consuming anything. In
Java that is `Router.configSource`'s default-broker branch plus
`Router.usesDefaultPostgresBroker` (which `Main` uses to decide whether a
router-only process still needs a database pool), and five pinned behaviours in
`RouterConfigSourceTest`. Dev points its config URL at its own platform
instead. Check the equivalent surface on the Go side —
`internal/server/subsystems.go` and whatever decides Go's default-broker
router config — and report what removing it breaks there before removing it.

### R5 — client-less jobs use `platform` in the tenant position

The two assumptions §3 flagged as never actually stated are now **owner-ruled
(2026-09-12)** and no longer assumptions.

Platform-wide dispatch jobs — `msg_dispatch_jobs.client_id` is nullable, and
`PoolCodeResolver` already has a client-less branch emitting a bare
`{poolCode}` — publish to `FC-{env}-platform-DEFAULT.fifo`. Every queue name
keeps the same four-segment shape.

**Guard required:** a client whose identifier is literally `platform` would
collide with this lane. `tnt_clients.identifier` is validated on create
(`ClientIdentifier`, a lower-case slug) and is immutable afterwards (O1), so
one check at creation closes it permanently. Add it on both sides, with a test.

### R6 — an unusable `queue` value publishes as `DEFAULT`

Write validation (R1) cannot reach rows that already exist. The read path will
meet three cases: `NULL` (every row created before this ships), legacy Go-era
text such as `workers-high`, and jobs with no `subscription_id` at all.

**Ruling:** all three publish to the client's `DEFAULT` queue. A publish is
never failed on this condition.

Accepted cost, recorded deliberately: a legacy row reading `workers-high` is
treated as normal priority with nothing announcing the downgrade. The owner
chose this over warning on it, and over refusing to publish. If that becomes a
problem in practice the remedy is a migration (R6's rejected option 3), not a
change to the publish path.

### R7 — platform pool codes take the `platform-` prefix; R-16 is superseded

Settled item 5 says pool keys are `{clientIdentifier}-{poolCode}` and
`platform-{poolCode}`. `PoolCodeResolver` implements the opposite for
platform-level pools, and it is recorded as **dispatch-seam §2 / ledger R-16**:

| Job's pool | Job's client | Published `poolCode` before this ruling |
|---|---|---|
| set, owned by a client | — | `{clientIdentifier}-{poolCode}` |
| set, platform-level | — | `{poolCode}` — **no prefix** |
| unset/unresolvable | resolves | `{clientIdentifier}-DEFAULT-POOL` |
| unset | unresolvable | `DEFAULT-POOL` |

The pool code the scheduler stamps must match a pool in the router's merged
config, so the served document and the resolver cannot disagree.

**Ruling (owner, 2026-09-12): follow item 5.** Platform-level pools publish
`platform-{poolCode}`, and the unresolvable case publishes
`platform-DEFAULT-POOL`. The router merges our document with four Integral
configs and pools merge by `code` first-wins, so an unprefixed platform pool
(`WEBHOOKS`) would silently inherit an Integral tenant's settings of the same
name, with only a merge-conflict log line to show for it.

**Consequences:**
1. R-16 and dispatch-seam §2 are **superseded** — both updated, naming the
   superseding ruling rather than deleting the old text.
2. It changes the `poolCode` on the wire for platform-wide jobs, so it is a
   real Go-vs-Java divergence until Go mirrors, and needs an entry in
   `parity/expected-diffs.json` if a corpus step exercises it.
3. **The served document does not need default-pool rows.**
   `RouterManager.poolFor` takes an exact match, then **auto-synthesises any
   code ending in `-DEFAULT-POOL`** (concurrency 20), and only then warns
   (`ROUTING`) and falls back to the bare `DEFAULT-POOL` that `wantedPools`
   always injects. So `platform-DEFAULT-POOL` and
   `{clientIdentifier}-DEFAULT-POOL` both self-create. The document carries
   only the real named rows from `msg_dispatch_pools`, and a named pool
   missing from it is degraded — warned and routed to the fallback — never
   lost. The document being incomplete is survivable.

---

## 2. Orchestrator rulings (taken on evidence, not owner questions)

### O1 — queue names keep using the client *identifier*; the rename risk is already closed

§3 flagged: "A client whose identifier changes would rename its queues …
Decide before this ships: forbid the rename, or keep the queue name from the id
rather than the identifier."

Evidence: `tnt_clients.identifier` is **already immutable in code on both
sides**. Java has no `withIdentifier`; `Client.rename` threads the identifier
through unchanged; `UpdateClient`'s command carries only `{id, name}`;
`docs/spec/client.md:23` says "immutable after create". The rename is de facto
already forbidden.

**Ruling:** keep the human-readable identifier in the queue name, as Integral's
convention requires. Add a test that pins the immutability so a future
`withIdentifier` cannot be added without someone confronting this. Residual
risk — a direct SQL update to `tnt_clients.identifier` orphans that client's
queues — is accepted and recorded, not defended against in code.

### O2 — the publish batch chunks, and reverts only the unpublished remainder

`PendingJobPoller` claims 100 rows and `DispatchPublisher.publish` is
documented all-or-nothing; SQS caps a `SendMessageBatch` at 10, and partial
batch failure is its normal mode. All-or-nothing cannot be honoured against
SQS.

**Ruling:** publish in chunks of 10, and on failure revert **only the
jobs that were not published** to `PENDING`, rather than the whole batch.
Successfully published jobs are legitimately `QUEUED` and will be processed;
reverting them would create duplicates. This changes `DispatchPublisher`'s
documented contract, which must be updated to say so.

Note this also shrinks R2's exposure: the only remaining re-publish path is
`StaleQueuedJobPoller`, not the failure path.

---

## 3. Java has now built §3 — Go can mirror (updated 2026-09-12)

The hold is lifted. Java built it in four units, reactor green on an
uncontended run at each: `92b6c9c` (priority carrier), `94c6e8d` (settings,
naming, R7 resolver, served document), `603a14f` (SQS publisher), `d9e2263`
(one code path, dev and prod). Mirror from `docs/spec/deployed-dispatch.md` §3
plus the rulings above, the way portal-apps and the secret-scheme work were
mirrored — not by transcribing the Java.

Three things Java learned by building it that the spec alone will not tell you:

1. **The Postgres publisher must route per (tenant, priority) too.** Java's
   published to one queue named after the database URL. The moment dev
   consumed the served document — which advertises composed names — every dev
   dispatch job went where nothing was listening. Both publishers now share
   one destination resolver so they cannot drift.
2. **Scheme mismatch.** Java's database URL uses `postgresql://` while its
   queue factory registers only `postgres`, so the served document named every
   dev queue with a scheme the router refused outright. Check the equivalent on
   the Go side.
3. **Do not create the queue table from the consumer-build path when the queue
   is on the platform's own database.** The platform owns that schema; doing
   DDL there from a router process also makes consumer construction fail
   wherever the shared pool cannot hand out a connection.

Both assumptions the spec listed as unconfirmed are settled — R5 and R6 above.

The two assumptions §3 listed as unconfirmed are now settled as R5 and R6
above. **No design question on §3 is currently open with the owner.** What
remains before Go starts is Java's implementation and the infrastructure
dependency in R3 (the Service Connect alias for the internal listener).

## 4. Unchanged from the 2026-09-11 hand-off

Item 4 stands: `FLOWCATALYST_JWT_PUBLIC_KEY`, `FC_WEBAUTHN_RP_NAME` and
`FC_STATIC_DIR` are unread by both sides and should come out of
`../inhance/iac/compute/index.ts`. That is an IaC change, not a Go change.
