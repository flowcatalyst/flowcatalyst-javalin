# Go conformance runner

Two files:

- `mediation_conformance_test.go` — the runner. It belongs in **flowcatalyst-go**,
  not here.
- `corpus_selfcheck.go` — a standalone `package main` that validates the
  shape of `conformance/mediation-outcomes.json`. It has no dependency on
  the Go router module, lives here on purpose, and is the one piece of this
  directory that actually builds and runs in this checkout.

Read `conformance/README.md` (the corpus contract) and `conformance/go-runner.md`
(the handover spec this was built from) first. This file only covers
installation, running it, and what was found while writing it.

## Installing the runner

Copy `mediation_conformance_test.go` to `internal/router/mediation_conformance_test.go`
in the flowcatalyst-go checkout. It's already `package router_test` and
already imports `internal/common` and `internal/router` by their real module
paths, so no changes should be needed beyond the copy.

```
cp mediation_conformance_test.go ../../../flowcatalyst-go/internal/router/
```

## Running it

```
go test ./internal/router/... -run TestMediationConformance -v
```

The corpus path defaults to `../flowcatalyst-javalin/conformance/mediation-outcomes.json`,
relative to `internal/router/` — i.e. it expects `flowcatalyst-javalin` to be
checked out as a sibling of `flowcatalyst-go`. Override with:

```
FC_CONFORMANCE_CORPUS=/path/to/mediation-outcomes.json go test ./internal/router/...
```

If the file isn't found, the test **skips** with a clear message rather than
failing — the Go repo has to build standalone.

Expect the run to take a few seconds, not milliseconds: several rows are
retryable outcomes (`ErrorProcess`/`ErrorConnection`), and `DevMediatorConfig`'s
real backoff (1s, then 2s) runs for real between attempts. The runner marks
every case `t.Parallel()` — each gets its own breaker registry, mediator,
and HTTP server, so there's no shared state — which brings the whole suite
down to roughly the cost of the *slowest* single case (~3s) instead of the
sum of all of them (~20s+).

## What it asserts (Phase 1)

`outcome`, `statusCode`, `delaySeconds`, `flushGroup`, `breaker`, `warning`,
`httpCallMade`. It does **not** assert `disposition` — see go-runner.md
"Phase 2": the decision lives in an inline `switch` inside `pool.go`'s
delivery loop with nothing a test can call yet.

### Warnings ARE observable — no Go change needed

go-runner.md asked us to check whether `warnConfig`'s effect can be observed
from `package router_test` without changing Go. It can: `WarningService` is
exported, `Add`/`All` are exported, and `HTTPMediator.SetWarnings` is the
same opt-in hook production wires up at startup. The runner creates a fresh
`WarningService` per case, wires it with `SetWarnings`, and reads it back
with `.All()` after `Mediate`. Nothing here needed a Go source change.

## Verification — what was and wasn't actually run

**This is the important honesty section.** `mediation_conformance_test.go`
imports `github.com/flowcatalyst/flowcatalyst-go/internal/{common,router}`,
which don't exist in this checkout (flowcatalyst-go is a separate, read-only
repo we were not allowed to touch). So this file **has never been compiled
or run against the real flowcatalyst-go module** by this work, and can't be
until someone with write access to that repo drops it in and runs it.

What *was* done, to get real signal instead of just careful reading: every
line of `mediateOnce`/`deliverWithRetry`/`Mediate`'s classification, retry,
and breaker-recording logic, plus `HostKeyFromURL`, `CircuitBreaker`, and
`WarningService`, was hand-copied verbatim (as of commit `819b390`) into a
throwaway scratch Go module, with only the HTTP/2 host-pool machinery
(`host_pool.go`) swapped for a plain `*http.Client` — that machinery affects
connection reuse and throughput, not which `MediationOutcome` a response
maps to, so it's irrelevant to what this test checks. The **actual,
unmodified** `mediation_conformance_test.go` was then built and run against
that scratch module with `go vet`, `go test -v`, and `go test -race`, all
against the real 28-case corpus.

This is strong evidence the runner's own logic (given-kind handling, JSON
parsing, delta/breaker math, divergence handling, parallelism) is correct.
It is **not** a substitute for running it against the real module — the host
pool, HTTP/2 negotiation, and signing paths were never exercised, and a
scratch reimplementation can silently diverge from the real file in ways a
diff would catch and a fresh read might not. Treat the results below as
"very likely, pending a real run," not as fact.

## Findings

### `unexpected-status-1xx`: the runner's actual result disagrees with go-runner.md's account, but matches the corpus

Simulating a bare 1xx status honestly is not straightforward: Go's own
`http.Server` cannot terminate a response on a lone 1xx — sending only
`WriteHeader(199)` and returning leaves `wroteHeader` false
(`net/http/server.go`, since a 1xx header is documented as "informational,"
not terminal), so the server silently completes the response with `200 OK`.
Confirmed empirically before writing the real handler. The runner instead
hijacks the connection for status codes in 100–199 and writes the raw
status line itself (`HTTP/1.1 199 Informational\r\n\r\n`) with nothing
after, which is what an actual external server misbehaving this way would
send.

Reading `net/http/transport.go`'s `persistConn.readLoop` (`is1xxNonTerminal`)
shows the client does **not** surface a non-101 1xx as final — it loops back
to read another response. With nothing more coming, the connection breaks,
and `mediateOnce`'s generic `err != nil` branch (not the `Timeout()` branch)
returns `common.ErrorConnection(...)`. Confirmed by running the case:
outcome `ErrorConnection`, statusCode 0, delaySeconds 30, breaker failure,
no warning — which is exactly what the corpus's `expect` block says, **not**
the "`ErrorProcess(0, 30)` via the default arm" that go-runner.md's
divergence table describes. The row passed with no divergence needed at all
in this run.

This may be a real behavior change between whatever Go version/technique
`819b390`'s verification used and the Go 1.26 stdlib this was checked
against — re-verify once this runs for real in flowcatalyst-go, and if it
still passes cleanly, the `divergence` block on this row can likely be
simplified (it's currently `correct: both`, arguing the outcome *names*
differ for a benign reason; if they now agree outright, that's one fewer
thing to explain, not a problem).

### New: breaker mismatch on `malformed-target-url` / `unsupported-mediation-type`, undocumented until now

Both rows already carry a `divergence` block (`correct: java`) — but its
`basis` only argues about the missing **warning**. Reading `Mediate()`
closely shows a second mismatch on the same two rows: Go's breaker-recording
switch only inspects `outcome.Result`, not *how* the outcome was reached, so
any `MediationErrorConfig` — including the pre-flight rejections, which
never make an HTTP call — records a breaker **success**. The corpus expects
`breaker: none` for both (no call was made, nothing to record). Java's
`HttpMediator.deliver` avoids this because it returns for both pre-flight
cases *before* ever calling `breakers.get(...)` — the breaker is never
touched at all.

Confirmed by running both cases: the runner reports both `warning` **and**
`breaker` mismatches, not just `warning`. Since the row already has
`correct: java`, this doesn't newly fail the build — but the existing
`basis` text undersells the divergence. Worth updating `go` /`basis` in
`mediation-outcomes.json` to mention both, so a future reader fixing only
the warning gap doesn't expect the row to then pass.

### New, undocumented, and genuinely fails: `config-error-other-4xx`

`mediateOnce`'s generic 4xx fallback (`status >= 400 && status < 500`,
catching every 4xx not individually matched — 402, 405, 406, 418, etc.)
only calls `slog.Warn(...)`, never `m.warnConfig(...)`. So no `Warning`
lands in the `WarningService` for these codes, while the individually-coded
400/401/403/404/501 all warn. This row has **no** `divergence` block in the
corpus today, so the runner's failure on it is not suppressed — confirmed
by actually running it: `warning: expected ERROR/CONFIGURATION, got none`,
the only genuine (non-expected-divergence) failure across all 28 cases in
this run.

This is a real question per the corpus's own framing, not an automatic "fix
Go": maybe the omission is a plain bug (warn here too, matching the pattern
established by 400/401/403/404), or maybe there's a reason "other" 4xx
codes are meant to be quieter than the named ones that hasn't been written
down. Either way it needs a decision and, if Go stays as-is, an argued
`divergence` block — right now it will just show as a red test with no
explanation the moment this file is dropped into flowcatalyst-go and run.

## What blocks a full Phase 1 pass today

Nothing needs a **Go source change** to run Phase 1 at all — the corpus
loads, every `given.kind` is handled, and warnings are observable as-is.
What blocks a **clean, all-green** Phase 1 run is the `config-error-other-4xx`
finding above: as things stand, running this test for real in flowcatalyst-go
should produce exactly one unexplained failure. Everything else (four rows)
is either already argued in the corpus's `divergence` blocks or passes
outright.

`disposition` (Phase 2) is out of scope by design — see go-runner.md.

## `corpus_selfcheck.go`

Standalone, no repo dependency:

```
go run corpus_selfcheck.go
```

Parses the corpus, asserts every case has a unique `id`, a known
`given.kind`, and the six required `expect` fields (`outcome`, `statusCode`,
`warning`, `disposition`, `breaker`, `metric` — `delaySeconds`, `flushGroup`
and `httpCallMade` stay optional per the case shape in `conformance/README.md`),
and asserts every `divergence` block carries both `correct`
(java/go/both) and `basis`. Prints a case count, a per-`given.kind`
breakdown, and the divergence table, then exits non-zero on any problem.

Built, run, and deliberately run again against a corrupted copy (duplicate
id, unknown `given.kind`, a stripped required field, an unargued divergence)
to confirm it actually catches what it claims to — all four were caught,
each with the right message, none silently passed. Output against the real
corpus:

```
cases: 28

by given.kind:
  breakerOpen                1
  malformedTargetUrl         1
  response                   24
  unreachableTarget          1
  unsupportedMediationType   1

divergences: 5
  [java  ] malformed-target-url                   ACK-drops every message routed through it, permanently...
  [java  ] success-carries-real-2xx-status        The status is the target's own answer...
  [both  ] unexpected-status-1xx                  Neither may claim the message was rejected...
  [java  ] unfollowed-3xx-is-permanent            Retrying reproduces the redirect every time...
  [java  ] unsupported-mediation-type             ACK-drops every message routed through it, permanently...

PASS — corpus is well-formed.
```
