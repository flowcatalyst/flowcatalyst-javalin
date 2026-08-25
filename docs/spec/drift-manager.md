# Router drift re-extraction — manager.go + Postgres queue (2026-08-25)

Re-extraction of three Go commits against `docs/spec/router.md` (extracted at
`eff2a29`; this note is written against Go `18f1460`, current HEAD). Scope per
assignment: `18f1460` (manager.go pool-code propagation), `31f22de`
(Postgres quarantine), and the **manager.go + queue.go/postgres.go/nats.go**
parts of `88b4549` (broker message id). `88b4549`'s `pool.go`/`sqs.go`
internals are out of scope here — another pass covers those.

**Section-number correction.** The task brief cites "§4 manager" and "§7.4
Postgres". As the doc is actually structured: manager/routing behaviour lives
in **§3.3 "Routing"** and **§2.6 "DispatchMode"** (§4 is "State machines" —
tracker/breaker/consumer-health, not the manager's pool resolution). Postgres
is **§7.3**; §7.4 is NATS. Citations below use the real numbers.

---

## `18f1460` — scheduler propagates the client-namespaced pool code

### 1. What changed, behaviourally

Two independent halves, only one in scope here:

- **Scheduler half (out of router scope, in `docs/spec/dispatch-propagation.md`)**:
  `internal/platform/scheduler/poolcode.go` (new) resolves, at *publish* time,
  the pool code a dispatch job's `Message.PoolCode` carries: `{clientIdentifier}-{poolCode}`
  when the job's pool has an owning client, `{poolCode}` unprefixed for a
  platform-level pool, `{clientIdentifier}-DEFAULT-POOL` when the job has no
  pool but a resolvable client, else the bare `DEFAULT-POOL`. Previously
  `PoolCode` was never set (`dispatcher.go` had it commented out), so every
  dispatch job silently landed in the global `DEFAULT-POOL` regardless of its
  subscription's configured pool.
- **Router half (`internal/router/manager.go`, in scope)**: `poolForMessage`
  gained a branch, checked *between* the known-pool lookup and the
  unknown-code warning path: any code ending `-DEFAULT-POOL` that is not
  already a registered pool is **synthesised on demand** — a new pool at
  concurrency 20 (the same default `Reconfigure` gives the bare
  `DEFAULT-POOL`), no rate limit, registered into `m.pools` under that exact
  code, logged at INFO. No `ROUTING` warning is raised for it. A pool of the
  same code arriving later from config always overwrites the synthesised one.

The observable effect on a message: a dispatch job whose subscription names a
configured pool now actually runs under that pool's concurrency and rate
limit for the first time — previously the setting existed in config but
nothing ever routed to it. A job whose client has no explicit pool gets its
*own* per-client `{clientIdentifier}-DEFAULT-POOL` bucket instead of sharing
the single global `DEFAULT-POOL` with every other client's unpooled traffic.

Also in this commit, `internal/server/run.go`: `defaultPostgresRouterConfig`
renames its synthesised pool from `"default"` to `"DEFAULT-POOL"` (concurrency
4) — under the old name it received zero traffic, because empty pool codes
fall back to the literal string `DEFAULT-POOL`, auto-added at concurrency 20,
not to a pool named `"default"`.

### 2. Which spec sections are now wrong

- **§2.6**, the "Q16 is ruled" paragraph (lines ~379–385): *"Go publishes
  neither, so in Go every dispatch job reaches the router as `IMMEDIATE` in
  `DEFAULT-POOL` and the ordered path is never taken for them."* — **now
  false**. Go publishes both: `dispatchMode` since `7414bc5` (already noted
  elsewhere in the doc), `poolCode` since `18f1460`. The router-side
  consequence this paragraph predicts ("a subscription's configured dispatch
  pool … starts applying") is now live in Go too, not just a Java-only
  cutover concern.
- **§3.3**, step 3: *"non-empty but unknown → `DEFAULT-POOL` **plus one
  `ROUTING` warning per message**"* — **now incomplete**. A code ending
  `-DEFAULT-POOL` is unknown-to-config but is *not* routed to the shared
  `DEFAULT-POOL` and raises *no* warning; it gets its own silently-synthesised
  pool. The "unknown code" branch now has two outcomes, not one, and the
  discriminator is the `-DEFAULT-POOL` suffix.
- **§7.3** default-broker bootstrap paragraph: *"calls
  `Manager.Reconfigure({pool "default" concurrency 4; queue "default"
  visibility 30})`"* — the pool code is now `"DEFAULT-POOL"`, not `"default"`.

### 3. Exact new behaviour

`poolForMessage(code)` resolution order, as of `18f1460`:

1. `code` is a live entry in `m.pools` → that pool.
2. Else `isDefaultPoolCode(code)` (`code == "DEFAULT-POOL" || strings.HasSuffix(code, "-DEFAULT-POOL")`)
   → synthesise `NewPool(PoolConfig{Code: code, Concurrency: 20}, …)`, store it
   in `m.pools[code]`, return it. **Config always wins**: this only runs on a
   registry miss, and a subsequent `Reconfigure` that defines the same code
   overwrites the synthesised pool.
3. Else → log a `ROUTING` warning, fall back to `m.pools["DEFAULT-POOL"]`.

No new SQL, no new table. This is pure in-process registry logic — the
`isDefaultPoolCode` suffix test is the *only* structural read ever performed
on a composed code (both halves of `{clientIdentifier}-{poolCode}` may
contain hyphens, so it can never be split back apart).

### 4. Open questions

- Synthesised pools are never evicted except by config overwrite. A
  short-lived client that stops publishing leaves its `{identifier}-DEFAULT-POOL`
  pool (and its goroutine/worker footprint) alive in `m.pools` forever. Not
  addressed by this commit or by `dispatch-propagation.md`.
- The scheduler half is a prerequisite this router-side change is inert
  without (a code ending `-DEFAULT-POOL` only ever arrives once the scheduler
  publishes one) — already flagged as a blocking dependency in §2.6
  ("needs the dispatchjob *ignore*/*completed* routes").

---

## `31f22de` — Postgres quarantines malformed rows instead of stalling the queue

### 1. What changed, behaviourally

Before: a claim (`UPDATE … RETURNING`) commits *before* the payload is
JSON-parsed. A parse failure returned an error from the whole `Poll` call —
every healthy message claimed in the same batch was returned to nobody
(silently dropped from the caller's view, still claimed at the DB level), and
because eligibility is FIFO-per-group, a poison row at a group's head blocked
that entire group forever. The row itself became visible again once its claim
timed out, was re-claimed, and failed identically — forever, with no bound.

After: a JSON-unmarshal failure on one row no longer aborts the poll. The row
is moved out of `queue_messages` and into a new `queue_message_errors` table
in one statement; the rest of the batch's healthy messages are returned
normally.

### 2. Which spec sections are now wrong

- **§3.1 "Interfaces as behavioural contracts"** row for `Poll`: *"malformed
  payloads must not be returned (SQS: acked; NATS: termed; **Postgres: poll
  error**, §7.3)"* — now false for Postgres; it neither returns the row nor
  errors the poll, it quarantines and continues.
- **§7.3**, the Poll (claim) row: *"A malformed payload **fails the whole
  poll** (`postgres.go:171-173`) — the row stays claimed until visibility
  lapses, then fails again (poison). **load-bearing or accident?**"* — now
  false, and the flagged question is answered (this was an accident, now
  fixed).
- **§12 edge case 17** and **§13 Q17** (lines ~746, ~1790) already describe
  the ruling and record that Java implemented it, and flag Go as *not yet*
  doing so. That "Go doesn't do this yet" premise is now false — see the
  detailed divergence in the Java-comparison section below, because Go's
  version of the fix is **not the same fix**.

### 3. Exact new behaviour

New table, created in `InitSchema` alongside `queue_messages` (not a
migration — this backend provisions its own schema, possibly against a
non-platform database):

```sql
CREATE TABLE IF NOT EXISTS queue_message_errors (
    id               TEXT NOT NULL,
    queue_name       TEXT NOT NULL,
    message_group_id TEXT,
    payload          TEXT NOT NULL,
    error            TEXT NOT NULL,
    receive_count    INTEGER,
    created_at       BIGINT NOT NULL,
    failed_at        BIGINT NOT NULL,
    PRIMARY KEY (queue_name, id)
);
```

`Poll` claim query gains three output columns (`message_group_id`,
`created_at`, `receive_count`) alongside `id`/`payload`, needed to carry the
row's provenance into quarantine. Per claimed row:

- Scan failure (reading the `ResultSet`/row itself) → **still aborts the
  whole poll**, unchanged — treated as an infrastructure fault, not a bad
  row.
- `json.Unmarshal` failure on `payload` → the row is appended to an
  in-memory `quarantine` slice; the row loop `continue`s past it (not
  returned as a message). After the claim-query cursor is fully drained and
  closed, each quarantined row is moved with **one statement**, on the same
  pool but a **separate transaction** from the claim itself:

```sql
WITH moved AS (
  DELETE FROM queue_messages
   WHERE queue_name = $1 AND id = $2
  RETURNING id, queue_name, message_group_id, payload, receive_count, created_at
)
INSERT INTO queue_message_errors
    (id, queue_name, message_group_id, payload, error, receive_count, created_at, failed_at)
SELECT id, queue_name, message_group_id, payload, $3, receive_count, created_at, $4
  FROM moved
ON CONFLICT (queue_name, id) DO NOTHING
```

`$3` = the parse error string, unbounded length, never truncated. `$4` =
`time.Now().Unix()`. **`ON CONFLICT … DO NOTHING`**: if the same `(queue_name,
id)` is quarantined a second time, the `INSERT` is skipped — the *first*
recorded failure survives, the `DELETE` still applies (that's the part that
must always succeed, per the commit message). If the move statement itself
errors, the row is logged and left claimed — it will be re-claimed and
retried on a later poll; the healthy messages already gathered in this batch
are still returned. `Poll` never returns an error for a quarantine failure.

The claimed-but-not-yet-parsed row's visibility bump (from the claim UPDATE)
already committed before the parse ever runs, so between claim and
quarantine there is a window where a crash leaves the row claimed-but-not-yet-
quarantined; it simply retries on the next visibility lapse (best-effort, by
design — matches the "left claimed" comment on move failure).

### 4. Open questions

- `ON CONFLICT DO NOTHING` means a row that fails, is somehow re-queued
  under the same id, and fails a *second time with a different reason*
  silently keeps the *first* failure's `error`/`payload`/`failed_at`. Is that
  the intended forensic record (first cause wins) or a loss of the latest,
  possibly more relevant, diagnosis?
- No index on `queue_message_errors` beyond the PK — fine for occasional
  inspection, but there's no documented retention/cleanup path, so this table
  grows unbounded with no owner-visible cap (unlike `queue_messages`, which
  is bounded by live traffic).

---

## `88b4549` — broker message id passed to `Ack` (manager.go + queue.go + postgres.go + nats.go scope)

### 1. What changed, behaviourally

`Consumer.Ack` gained a second parameter, `brokerMessageID string`. Previously
SQS's `Ack` recovered the SQS `MessageId` it needed for its at-least-once
redelivery guard (`pendingDelete`) from a *second* map,
`receiptToMessageID: receipt → MessageId`, populated at poll time and pruned
by age once past 1000 entries. On a lookup **miss** (map entry evicted before
the ack arrived — routine for a message held through a slow-target retry),
`Ack` silently recorded nothing and deleted the message anyway: no error, no
log. The next redelivery of that (still in-flight-at-SQS) copy was then not
recognised as already-acked, and the target received the message a second
time.

The fix removes the lookup entirely: the caller already has the id
(`QueuedMessage.BrokerMessageID`, populated at poll time and carried
everywhere the message is carried), so `Ack` now takes it directly.
`receiptToMessageID` is deleted outright. Postgres and NATS accept but ignore
the new parameter — a Postgres receipt handle already embeds the row id and
the row is deleted outright; NATS acks the message object itself. Neither has
an at-least-once redelivery to suppress.

In scope here, three call sites in `manager.go` were updated to pass the id
through: `ForceAckInFlight` (`c.Ack(ctx, entry.ReceiptHandle,
entry.BrokerMessageID)`) and the `ExternalRequeue` ack in `route`
(`source.Ack(ctx, msg.ReceiptHandle, msg.BrokerMessageID)`).

### 2. Which spec sections are now wrong

- **§3.1**, `Consumer` interface table, `Ack(ctx, receipt)` row: signature is
  now `Ack(ctx, receipt, brokerMessageID)`. The doc's contract description
  ("permanently remove the delivery identified by `receipt`") is still
  behaviourally accurate but the signature it implicitly documents is stale.
- **§7.2** (SQS) `Ack` row: *"forget `receipt`, `pendingDelete[MessageId]=now`"*
  — still describes the *outcome* correctly, but omits that `MessageId` is now
  supplied by the caller rather than recovered from `receiptToMessageID`, and
  the **Maps** row's `receiptToMessageID` (pruned >15 min once over 1000
  entries) no longer exists at all — it was deleted in this commit.
- Not spec text but worth flagging: this commit is a **bug fix for a defect
  that existed only in Go's specific implementation choice** (deriving the id
  via a second lookup map rather than carrying it on the value). It is not a
  new *contract* requirement so much as evidence that the contract should
  have carried the id from the start — see the Java comparison below.

### 3. Exact new behaviour

- `queue.Consumer.Ack(ctx context.Context, receipt string, brokerMessageID string) error` —
  interface change, all three backends updated.
- SQS: `Ack` calls `markDeleted(brokerMessageID)` (no-ops if `""` — inserting
  an empty key would match every id-less message)  then issues
  `DeleteMessage`. `alreadyDeleted(brokerMessageID)` at the top of `Poll`
  checks the same map. Retention unchanged: `pendingDelete` entries pruned by
  `PendingDeleteTTL` (15 min) at the top of every poll — bounded by delete
  *rate*, not total volume.
- Postgres: `Ack(ctx, receipt, _)` — parameter present, unused; deletes by
  `receipt_handle` exactly as before.
- NATS: `Ack(_, receipt, _)` — parameter present, unused; pops the pending
  map by receipt exactly as before.
- No SQL/table shape changes in Postgres beyond the signature; no
  transactional semantics changed.

### 4. Open questions

None new from this commit's manager/queue.go/postgres.go/nats.go slice — it's
a straightforward plumbing fix. (The `pool.go` half, which is where the
outcome-resolution call sites that decide *when* to call `Ack` live, is out
of scope for this note.)

---

## Java comparison

Files: `server/src/main/java/io/flowcatalyst/router/manager/RouterManager.java`,
`.../manager/QueueBroker.java`, `.../queue/postgres/PostgresQueue.java`,
`.../queue/Acknowledger.java` (plus `.../pool/QueuedMessage.java` and
`.../queue/sqs/SqsQueue.java`, read for context).

### `18f1460` — pool-code namespacing

- **Router-side synthesis: AGREE**, near-exactly. `RouterManager.poolFor`
  (`RouterManager.java:196-211`) implements the identical three-branch order:
  known code → that pool; unknown code ending `DEFAULT_POOL_SUFFIX` ("-DEFAULT-POOL")
  → `pools.computeIfAbsent(code, … poolFactory.create(new Pool.Config(synthesised,
  DEFAULT_POOL_CONCURRENCY /* 20 */, 0)))`, no warning; anything else unknown
  → `Warnings.raise(WARNING, "ROUTING", …)` then fall back to `DEFAULT-POOL`.
  `DEFAULT_POOL_CONCURRENCY = 20` matches Go's `defaultPoolConcurrency`
  exactly. `applyPools` (line 278) also protects synthesised pools from being
  removed by a `reconfigure` that doesn't mention them, and a config-supplied
  pool of the same code overwrites the synthesised one via the normal
  `wanted.forEach` path — matches "config always wins".
- **Scheduler-side resolution (`poolcode.go` / `PoolCodeResolver`): UNIMPLEMENTED.**
  There is no `platform/scheduler` (or equivalent poller/dispatcher) package
  in `flowcatalyst-javalin` at all — no `PoolCodeResolver`, no
  `{clientIdentifier}-{poolCode}` composition, no publish-time resolution.
  This matches the project memory note "Data plane unported": the entire
  producer side of Q16 is not yet built in Java, only the consumer
  (router-manager) side that's *ready* for it. `docs/spec/dispatch-propagation.md`
  already specifies exactly this chain in full, so this is a known,
  documented gap, not a newly-discovered one.
- **Default-broker bootstrap rename (`"default"` → `"DEFAULT-POOL"`,
  `server/run.go`): UNIMPLEMENTED / not applicable yet.** No Java equivalent
  of `defaultPostgresRouterConfig`/`FC_DEFAULT_BROKER` synthesis was found
  under `router` — consistent with "data plane unported."

### `31f22de` — Postgres quarantine

**Same mechanism, different table and different conflict semantics — this is
the one the task flagged to check carefully, and it is a real divergence, not
just naming.**

- **Mechanism: AGREE.** `PostgresQueue.poll` (`PostgresQueue.java:219-277`)
  claims via one `WITH … FOR UPDATE SKIP LOCKED … UPDATE … RETURNING`
  statement, then per row: a `JsonProcessingException` from `Json.read`
  triggers `moveToFailed(id, e)` and `continue`s, while any other failure
  (a `SQLException` from the `ResultSet`/claim itself) propagates out and
  aborts the whole poll via `PostgresQueueException` — this is the identical
  split Go makes (parse failure quarantines; scan/infra failure aborts).
  `moveToFailed` (line 190) is explicitly best-effort: a failure there is
  logged and swallowed, the row stays claimed and is retried later — matches
  Go's "logged and left claimed" comment.
- **Table name: DIVERGE.** Java: `queue_messages_failed`. Go: `queue_message_errors`.
  Different name, not just different migration path — a query or
  operational runbook written against one will not find the other.
- **Column naming: DIVERGE (minor).** Java's error column is `error_message`;
  Go's is `error`. Both otherwise carry the same fields
  (`id, queue_name, message_group_id, payload, created_at, receive_count, failed_at`).
- **Conflict semantics: DIVERGE (behavioural, not cosmetic).** Java's
  `MOVE_TO_FAILED_SQL` (`PostgresQueue.java:82-98`) uses
  `ON CONFLICT (queue_name, id) DO UPDATE SET payload=EXCLUDED.payload,
  failed_at=EXCLUDED.failed_at, error_message=EXCLUDED.error_message,
  receive_count=EXCLUDED.receive_count` — a **re-quarantine overwrites with
  the latest failure**. Go's is `ON CONFLICT (queue_name, id) DO NOTHING` — a
  **re-quarantine is silently dropped, the first failure's record survives**.
  These produce different forensic records for the same sequence of events
  (same id fails, gets requeued somehow, fails again differently): Java shows
  an operator the most recent cause; Go shows them the original one. Neither
  is obviously more correct — Java's is more useful for "what's wrong with
  it *now*", Go's preserves "what was wrong with it *first*", which matters
  more if quarantine itself should be a rare, investigate-once event. Given
  the safety framing this project asks for: **Java's "latest wins" is the
  safer default for an operator debugging a live incident** (stale error text
  pointing at an already-fixed cause is actively misleading), but this
  should be a stated choice, not an accidental one — right now it's an
  undocumented divergence in both directions.
- **Error-text bound: DIVERGE (Java is stricter, arguably safer).** Java's
  `truncate()` (line 206) caps the stored error message at 1000 characters;
  Go's `$3` (`err.Error()`) is stored unbounded. A pathological parse error
  (e.g. a huge malformed payload echoed into the message) could grow a Go row
  without bound; Java's column stays capped. Minor but real.
- **Transaction shape: AGREE.** Both: claim commits in its own statement/
  transaction; the quarantine move is a second, separate single-statement
  transaction (atomic DELETE+INSERT via one CTE-fed statement in both), never
  sharing a transaction with the claim. Both tolerate a crash between the two
  as "row stays claimed, retried later" rather than guaranteeing atomicity
  across claim+quarantine.

**Net for the "same ruling, same thing?" question the task asked**: no. Same
overall shape (move don't delete, one atomic statement, best-effort on
failure, continue the batch) but a different table name, a different error
column name, and — the part that actually changes observable behaviour on a
re-quarantined id — opposite `ON CONFLICT` policies. `docs/spec/router.md`
§12 item 17 and §13 Q17 both currently read as though Go doing this at all
were the open question; the real open question now is reconciling these two
independently-designed schemas (which one is the port target, assuming Go
isn't automatically "right" here either).

### `88b4549` — broker message id to `Ack`

**AGREE, and Java never had the defect class to begin with — this is the
other item the task flagged to check.**

- Java's `Acknowledger.ack(QueuedMessage message)` (`Acknowledger.java:31`)
  was never a bare-`receipt`-string signature. `QueuedMessage`
  (`pool/QueuedMessage.java:22`) has always carried `brokerMessageId` as a
  named field alongside `receiptHandle`, so every acknowledgement path passes
  the broker id as part of the same value it passes the receipt handle in —
  there was never a second lookup map standing between "the id I polled with"
  and "the id I ack with" for the router to forget to consult.
- `SqsQueue.ack(QueuedMessage message)` (`SqsQueue.java`, `ack` method) reads
  `message.brokerMessageId()` directly and puts it straight into
  `pendingDelete` — no map indirection, so there is no eviction window in
  which the id could be silently unavailable. `SqsQueue` does keep a
  `receiptToMessageId` map, but it is populated at poll time and only ever
  *removed from* on ack (`receiptToMessageId.remove(receiptHandle)`) for
  cleanup — nothing reads it to *decide* what id to ack with, so its own
  pruning (`RECEIPT_MAP_PRUNE_THRESHOLD = 1000`, same threshold Go used) has
  no bearing on correctness, only memory.
- `RouterManager.routeOne`'s `ExternalRequeue` branch (`RouterManager.java:157-163`)
  calls `source.ack(message)` with the full `QueuedMessage` — the Go
  equivalent of passing `msg.BrokerMessageID` explicitly is automatic here.
- The force-ack API path (`RouterApi.java:675-706`, `forceAck`) builds its
  `ackTarget` from the tracker's `InFlightMessage.brokerMessageId()`
  (`RouterApi.java:698-700`) — the direct equivalent of Go's
  `c.Ack(ctx, entry.ReceiptHandle, entry.BrokerMessageID)` fix in
  `ForceAckInFlight`.
- Postgres/NATS-equivalent: `PostgresQueue.ack(QueuedMessage message)`
  (`PostgresQueue.java:283-300`) only ever used `message.receiptHandle()`,
  never a broker id — matching Go's Postgres `Ack`, which now accepts and
  ignores the parameter for the same reason (the receipt already embeds row
  identity).

Conclusion: Go's `88b4549` is a same-behaviour bug fix that brings Go's
`Consumer.Ack` signature up to what Java's `Acknowledger.ack` shape already
guaranteed by construction (an id-bearing value type, not a bare string plus
a side map). No Java change is needed here; this is a case where the earlier
Java design decision (carry `brokerMessageId` on `QueuedMessage` itself)
independently avoided a defect Go had to discover and fix separately.
