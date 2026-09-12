# Go hand-off — the router's first configuration fetch (2026-09-12)

For the Go agent. Two owner rulings taken on 2026-09-12 evening after the
Java e2e found a startup regression in Java's deployed-dispatch unit D
(`docs/STATUS.md`, "Deployed dispatch"). Go's `internal/router` and
`internal/server/run.go` were read for both; one ruling Go already
satisfies, one it does not.

---

## R-A — the first configuration apply never blocks startup (Go: already so)

**Ruling:** on leadership gain the router starts its first configuration
fetch-and-apply on its own thread. Listeners bind regardless of whether the
config service is reachable. Leadership itself is still decided
synchronously.

**Go today:** `run.go` starts the engine with `go routerSrv.Run(ctx)` and
`Run` starts the watcher with `go Watch(...)`, whose first `apply()` is the
first fetch. Both listeners bind straight after. **Nothing to do.**

**Why it was a Java defect:** Java's `RouterServer.gainLeadership` called
`applyConfiguration()` on the election's thread, and `Server.start()` bound
both listeners only after the router had started. With a config URL pointing
at a listener in the same process (fcdev since unit D), or at a config
service that is down at boot, startup stalled for the full 12×5 s retry
window. The router ECS service's ALB health check hits `/health` on the API
port with a 0 s grace period (`docs/deployments.md`), so a deployed
router-only task whose platform was momentarily unreachable would have been
killed in a loop.

## R-B — a source that has never answered is retried until it does (Go: mirror item)

**Ruling:** a config URL that has never once succeeded keeps being retried at
the retry cadence (5 s) until its first success; only after that does the
poll interval (default 5 min) govern re-fetches. Leadership loss or shutdown
ends the retrying. A periodic poll that fires while the initial apply is
still in flight is skipped, so two fetches never run concurrently.

**Go today (`config_sync.go`):** `Watch` calls `apply()` once, and
`fetchWithRetry` gives each URL `MaxAttempts` (12) × `RetryDelay` (5 s).
After that `apply()` returns, raises the CONFIGURATION warning, and the
watcher waits for the next `tick` — `ConfigPollInterval`. A config service
that is down for 61 s at boot therefore leaves the router with no pools for
up to five more minutes.

**Where to put the loop:** in the watcher, not in `ConfigSource`. `Fetch`
runs every URL in parallel and joins them; an unbounded per-URL retry inside
it would hold a healthy URL's configuration hostage to a dead one. The
Java shape, for reference:

- `Watch`'s initial `apply()` becomes a loop: apply; if the fetch produced no
  configuration and the context is still live, sleep `RetryDelay` and apply
  again; stop at the first applied configuration.
- The ticker's `apply()` is skipped while that initial loop is still
  running.
- One WARN on the first failed attempt of the streak, one INFO when it
  finally applies; not a line per retry.

**Tests to carry over** (all in Java's `RouterServerTest`, each proven with
a mutant):

1. `start` returns while a fetch is still blocked on a latch; consumers
   appear once it is released.
2. A source that answers empty three times and then succeeds ends with the
   consumers running and at least four fetch calls, with no poll tick.
3. Leadership lost while the first fetch is looping stops the loop: the
   fetch count stops growing and no consumer starts even after the source
   begins to answer.
4. The periodic poll returns without fetching while the initial apply is in
   flight, and fetches again once it has landed.

## Known gap (both sides)

With several config URLs, R-B applies to the merged result: once *any* URL
has answered, a second URL that has never answered is retried only at the
poll cadence. Acceptable today (every deployment has one URL); noted so it
is not mistaken for an oversight.
