# Router — fixes to make in Go, then port

Go-side changes agreed while re-extracting `docs/spec/router.md`. Same
pattern as `docs/spec/oauthapi-fixes.md`: **make them in Go first**, so the
Java port reproduces corrected behaviour rather than a defect plus a
deviation.

---

## Fix 1 — a successful mediation reports 200 even when it was not (Q51)

**Owner ruling 2026-08-24: carry the real status. Fix Go too.**

**Defect.** `common.Success()` hard-codes the status:

```go
func Success() MediationOutcome {
	return MediationOutcome{Result: MediationSuccess, StatusCode: 200}
}
```

Both success paths in `mediateOnce` use it, so a target answering `201
Created` or `204 No Content` is recorded as 200. The inconsistency is
visible in the same function: the `ack:false` branch immediately above
*does* copy the real status (`mediator.go:353`), and so does the error path
at `:404`. Only success loses it — including the `flushGroup` branch added
in `eff2a29` (`mediator.go:360`), which builds `common.Success()` and never
corrects it.

**Impact is diagnostic, not behavioural** — verified: nothing in the router
branches on `MediationOutcome.StatusCode`. It reaches logs and pool metrics
only. So this is a truthfulness fix, not a correctness one: an operator
reading delivery logs cannot currently tell a 204 from a 200, and a target
that switches to 202 shows no change at all.

**Fix.** Give the constructor the status, so the compiler prevents a future
caller from forgetting it:

```go
func Success(status int) MediationOutcome {
	return MediationOutcome{Result: MediationSuccess, StatusCode: status}
}
```

Then `mediator.go:360` becomes `out := common.Success(status)` and `:369`
becomes `return common.Success(status)`. Only two production call sites
exist, plus two in `guardrail_test.go` which pass an explicit `200`.

Preferred over setting `out.StatusCode = status` at each site (the shape the
`Deferred` branch uses): that is the pattern that allowed the omission in
the first place, and `eff2a29` repeated it when adding a third branch.

**Test.** A target answering 201 — and a target answering `201` with
`{"flushGroup": true}` — produces an outcome carrying 201. The second case
is the one that regressed when `flushGroup` was added.

**Java.** Already correct: `MediationOutcome.Success` carries the true
status, and `MediationResponseTest.carriesRealStatus` pins both the plain
and the flushing case. No Java change needed once Go lands.

---

## Fix 2 — the quarantine table and its conflict policy (owner ruling 2026-08-25)

**Owner ruling: use `queue_messages_failed`, and keep the LATEST failure.**
Java already does both; this is a Go-side change.

**Divergence.** Both implementations move a malformed row out of the live
queue table in one atomic statement and carry on with the batch — the same
ruling (Q17), reached separately. Two details differ, and neither was
documented until the 2026-08-25 re-extraction:

| | Go (`31f22de`) | Java | Ruled |
|---|---|---|---|
| table | `queue_message_errors` | `queue_messages_failed` | **`queue_messages_failed`** |
| error column | `error` | `error_message` | follow the table |
| re-quarantine | `ON CONFLICT … DO NOTHING` → keeps the **first** failure | `ON CONFLICT … DO UPDATE` → keeps the **latest** | **latest** |
| error text | unbounded | bounded to 1000 chars | Java's |

**Why the table name matters more than it looks.** Two names means an
operator has two places to look, and a rollback from Java to Go — or the
reverse — silently changes where quarantined rows land. Rows written before
the switch become invisible to the tooling that runs after it. That is a
migration hazard, not a naming preference.

**Why latest, not first.** A row that fails, is re-queued and fails again is
almost always being *worked on*: someone changed the payload, or the schema,
or the consumer. The most recent failure is the one that describes what is
wrong now. `DO NOTHING` pins the record to the first attempt and silently
discards every later diagnosis, which is precisely backwards for the case the
table exists to serve.

**Change in Go:** rename the table and its error column to match, and switch
`ON CONFLICT (queue_name, id) DO NOTHING` to `DO UPDATE`. Bounding the stored
error text is worth taking at the same time — an unbounded error string from a
pathological payload is stored verbatim, once per queue row.

---

## Fix 3 — an unfollowed 3xx is retried for ever (owner ruling 2026-08-24)

**Defect.** A 3xx has no `case` in `mediateOnce`'s switch, so it falls to
`default:` → `ErrorProcess(30, "HTTP %d: Unexpected status")` with `StatusCode`
left unset. That is a *retryable* classification, so the router retries a
redirect that will be reproduced identically every time, for ever, and reports
status 0 while doing it.

**Change.** Classify 3xx as `ErrorConfig(status, "HTTP %d: redirect not
followed — target misconfigured")` — permanent, ACK-dropped, breaker success,
with an ERROR `warnConfig`.

**Do not "fix" it by following redirects instead.** 301/302/303 downgrade POST
to GET and drop the body, so the target would receive nothing and the router
would record a success (§13 Q5).

---

## Fix 4 — BLOCK_ON_ERROR hands untried siblings back (owner ruling 2026-08-25)

**Owner ruling: ACK the siblings, matching Java.** This reverses half of
`8804827`; the mode-differentiation in that commit is right and stays.

**Current Go.** When the head fails terminally under `BLOCK_ON_ERROR`, the head
is ACKed and `releaseBuffered` NACKs every untried sibling back to the broker,
"they redeliver once the failure is resolved".

**Why that does not hold.** They redeliver on the **broker's own timer**, not
when the failure is resolved — nothing connects the two. By then the head has
been ACKed away, so the first sibling becomes the new head and is delivered.
"Add item" is applied to an order that was never created. `BLOCK_ON_ERROR`
exists precisely to prevent that, so the nack silently breaks the guarantee the
mode is named after.

**Change.** ACK the buffered siblings alongside the head. The messages are not
lost: they remain as dispatch-job rows in the platform store, which is the
system of record.

**Known residual gap** (shared with Java, tracked, not part of this change):
nothing marks those sibling jobs for review — their rows stay
`QUEUED`/`PROCESSING` rather than `FAILED`. Java emits a `MessageSettled`
flight-recorder event carrying reason `rejected-group-blocked` so the ids are
recoverable; an equivalent hook in Go would be worth having, and is the same
hook the drop-in verification harness needs (`docs/spec/dropin-verification.md`).

---

## Fix 5 — three warning gaps (found by the conformance corpus)

The rule the corpus encodes: **a permanent ACK-drop must warn; a retryable
outcome must not.** A permanent drop deletes the message and the warning is its
only trace. Go follows this for 400/401/403/404/501 and then stops.

1. **Unnamed 4xx.** The generic `status >= 400 && status < 500` arm calls
   `slog.Warn` only, never `warnConfig`. So an operator is told about a 404 and
   not about a 422 — same permanence, same deletion, no notice. Add
   `m.warnConfig(WarningError, …)`.
2. **`msg.MediationType != HTTP`** returns `ErrorConfig` silently.
3. **`HostKeyFromURL` failure** (target URL with no host) returns `ErrorConfig`
   silently.

Both pre-flight paths ACK-drop *every* message routed through them and are
configuration mistakes an operator can fix — more clearly so than a 404. Add an
ERROR `warnConfig` to each.

---

## Fix 6 — pre-flight rejections record a breaker success (found by the runner)

`Mediate` records a breaker **success** for any `ErrorConfig`, including the two
pre-flight rejections above — which never touch the network.

A call that was never made is no evidence about the target's health in either
direction. Feeding it in as a success actively masks a failing endpoint: a
misconfigured target URL would hold the breaker closed on a host that is down.

**Change.** Skip the breaker entirely when the rejection happened before the
request was sent. Java already does.

---

## Fix 7 — auto-acknowledge is moot (owner ruling 2026-08-25: one hour)

`AutoAcknowledgeAge` defaults to `MaxWarningAge`, both 8 h, so the same
`cleanup()` pass that auto-acknowledges a warning then deletes it for being 8 h
old. Nothing can observe the acknowledged state, and a stale CRITICAL holds the
router `Degraded` for the full 8 h.

**Change.** `AutoAcknowledgeAge = 1 hour`, `MaxWarningAge` unchanged at 8 h. The
warning stays visible in history for the full 8 h; acknowledging stops it
driving health, it does not hide it.

---

## Fix 8 — install the conformance runner

`conformance/go/mediation_conformance_test.go` in the Java repo is written and
ready to drop into `internal/router/` (package `router_test`). See
`conformance/go/README.md` and `conformance/go-runner.md`.

- **Phase 1 needs no production changes.** Warnings turned out to be observable
  through `SetWarnings`. It asserts outcome, statusCode, delaySeconds,
  flushGroup, breaker, warning and httpCallMade.
- Read the corpus by path — `../flowcatalyst-javalin/conformance/mediation-outcomes.json`,
  overridable by `FC_CONFORMANCE_CORPUS`. **Do not copy it**; two copies drift
  and then prove nothing. Skip, do not fail, when the file is absent.
- **Phase 2** is extracting the inline `switch outcome.Result` in `pool.go`
  (~line 901) into a pure function so `disposition` — what actually happens to
  the message — becomes assertable. No behaviour change; the arms move, the
  side effects stay.

**A failing row is a question, not a verdict.** Three possible answers: Go is
wrong, Java is wrong, or the corpus is wrong. Rows carrying a `divergence` block
with `correct: "java"` are expected to fail in Go until the fix above lands —
that is what they record.

**One row is disputed and the runner settles it.** `unexpected-status-1xx`:
reading `mediator.go` says a 1xx reaches the `default` arm as `ErrorProcess`,
but `net/http`'s `readLoop` waits for a terminal response after any non-101 1xx
rather than treating it as final, which would make Go produce `ErrorConnection`
and match Java exactly. A reproduction of Go's classifier agreed with the second
reading. Report what the real module does.
