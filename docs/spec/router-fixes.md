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
