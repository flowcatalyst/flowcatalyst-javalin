# Auth table retention — wire up `ratelimit.Prune` (Q12)

Owner ruling: **fix** — 2026-08-24. Owner is making the same change in Go.

Related: `docs/spec/auth-core.md` §7 (rate-limit buckets), Q12, Q18.

---

## 1. The janitor already exists

`../flowcatalyst-go/internal/server/subsystems.go:485` — `StartPurger` is an
always-on loop, no env toggle, ticking **every minute**, sweeping four
ephemeral auth tables. Each sweep is an idempotent
`DELETE … WHERE expires_at < NOW()`; failures are logged and the loop
continues.

| Table | Repo | Purged |
|---|---|---|
| `oauth_oidc_payloads` | `payload.Repository` | yes |
| `oauth_oidc_login_states` | `bridge.LoginStateRepo` | yes |
| `webauthn_ceremonies` | `webauthn.CeremonyRepository` | yes |
| `portal_login_flows` | `portalauth.FlowRepo` | yes |
| **`iam_rate_limit_events`** | `ratelimit.PostgresStore` | **no — this is the defect** |

## 2. The defect

`ratelimit.PostgresStore.Prune(ctx, olderThan)` (`postgres.go:70`) issues
`DELETE FROM iam_rate_limit_events WHERE occurred_at < $1`. It has **no
callers** anywhere in the tree — verified by grep across all non-test Go.

`Policies.MaxWindow()` (`ratelimit.go:78-80`) exists solely to feed it: its
doc comment reads "the longest window across all policies — used by the
prune". It has no callers either. So both halves of the intended design were
written and neither was wired in.

Consequence: `iam_rate_limit_events` grows without bound, one row per
rate-limited request, forever. Every limiter decision scans it. The table
that exists to *protect* the login surface becomes the thing that degrades
it, and the degradation is worst exactly when the platform is under the
attack the limiter exists for.

## 3. The fix

Add a fifth sweep to `StartPurger`, in the same shape as the other four:

```
if n, err := rateLimitStore.Prune(ctx, policies.MaxWindow()); err != nil {
    slog.Warn("rate limit prune failed", "err", err)
} else if n > 0 {
    slog.Debug("rate limit prune", "removed", n)
}
```

**Retention = `Policies.MaxWindow()`**, not a fixed constant. Any row older
than the longest configured window cannot affect a decision, so this is the
largest safe cutoff and it tracks policy changes automatically. Do not
hardcode an hour: a policy edit would silently make the prune wrong in one
direction or the other.

**Cadence: leave it at one minute** — the same tick as the existing four. A
per-minute `DELETE` on an indexed timestamp is cheap, and matching the
existing loop means one cadence to reason about.

**HA.** Every instance runs its own purger today, unguarded by leadership.
That is safe for an idempotent `DELETE … WHERE occurred_at < cutoff` — the
losers delete zero rows. Adding the fifth sweep does not change this, and
the fix should **not** introduce a leadership check that the existing four
sweeps do not have; making the purger leader-only is a separate decision
about all five.

## 4. Java port

The Java server currently has **no periodic-task infrastructure at all** —
no `ScheduledExecutorService`, nothing equivalent to `StartPurger`. Grep of
`server/src/main/java` confirms it. The `ratelimit` package is likewise
unported (`iam_rate_limit_events` exists only as a jOOQ generated table);
the `rateLimit` hits under `platform/dispatchpool/**` are pool throughput
limits, an unrelated concept.

So Q12 lands in Java as a requirement on the auth port, not an edit to
existing code:

- The auth port introduces a purger owning all five sweeps, started and
  stopped with the server, on a virtual thread with **interruption** as the
  cancellation signal — not a ported `ctx` (CONVENTIONS §8).
- One failing sweep must not stop the loop or the other four.
- Retention comes from the policy object, mirroring `MaxWindow()`.
- Test: insert rows straddling the cutoff, run one sweep, assert only the
  older rows are gone and the sweep is idempotent on a second run. Assert on
  owned rows only — no table-wide counts (CONVENTIONS §6).

## 5. Adjacent finding — `iam_login_attempts` has no retention at all

Not part of Q12; surfaced while verifying it. There is **no** `DELETE` for
`iam_login_attempts` anywhere in the Go tree. The backoff queries it with
`since cutoff` on every login, so old rows are dead weight in an
ever-growing index — the same failure mode as §2, on the more heavily
queried table.

Unlike rate-limit events, though, login attempts are plausibly a
**security audit trail** rather than pure mechanism, and deleting them is
not obviously correct. **Owner question:** is `iam_login_attempts` retained
deliberately as history? If yes, it needs an index supporting the backoff's
`(identifier, ip, occurred_at)` and `(identifier, occurred_at)` lookups
independent of table size, and archival is a separate concern. If no, it
joins the purger with a retention well above `GlobalWindowSecs` (3600) —
days, not the window — so the audit value is not destroyed to serve the
limiter. Logged in `docs/backlog.md`.
