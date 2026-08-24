# `loginbackoff` — make `GlobalLockSecs` a real lock (Q11)

Owner ruling: **make it a real lock** — 2026-08-24. To be made in Go first
(`../flowcatalyst-go/internal/platform/auth/loginbackoff/`), then ported.

Related: `docs/spec/auth-core.md` §8.4 (backoff state machine), constants
rows 20–25, Q11.

---

## 1. What the code does today

Two windows guard a login attempt, both recomputed from scratch on every
request. There is no stored state anywhere — verified: the only two
occurrences of "lockout" in the Go tree are comments in `ratelimit.go:61`
and `portalauth/endpoints.go:218` saying the portal plane *deliberately*
has no lockout table.

| Window | Gate | Where |
|---|---|---|
| Per (identifier, IP) | after `FreeAttempts` (3), an exponential delay `BaseDelaySecs`·2ⁿ capped at `MaxDelaySecs` (300) | `loginbackoff.go:134` |
| Per identifier, any IP | failures since `max(now-GlobalWindowSecs, lastSuccess)` ≥ `GlobalCeiling` (100) | `loginbackoff.go:149` |

`GlobalLockSecs` (900) is assigned to exactly one thing:

```go
if int64(globalCount) >= policy.GlobalCeiling {
    lock := policy.GlobalLockSecs
    return Decision{Allowed: false, RetryAfterSecs: uint32(lock), Reason: ReasonGlobalCeiling}, nil
}
```

It is **only** the number put in the `Retry-After` header. No lock is
recorded, no expiry is stored, nothing consults it again.

## 2. The defect

The gate is the sliding hourly count, so the denial lasts until enough
failures age out of the window — **not** 900 seconds. That makes the
advertised `Retry-After` wrong in both directions, and wrong in the
*dangerous* direction most of the time:

- **Usually too short.** Trip the ceiling with a burst and the count stays
  ≥100 for the rest of the hour. A client that honours `Retry-After: 900`
  waits 15 minutes, retries, and is denied again — for up to another 45
  minutes, with no accurate hint at any point.
- **Occasionally too long.** If the 100 failures are spread across the
  hour, the count can fall below the ceiling seconds after the denial.
- **The knob is decorative.** Raising `FC_LOGIN_GLOBAL_LOCK_SECS` changes
  a header and nothing else. An operator hardening login by turning it up
  gets exactly the protection they had before. This is the part that makes
  it a defect rather than a cosmetic naming issue: it looks like a security
  control and is not one.
- **Nothing is observable.** No record that a ceiling trip happened — no
  row to alert on, nothing to show a support agent asking why a user
  cannot sign in.

**Correction to the first analysis.** An earlier framing of Q11 said the
header could leave a caller waiting longer than necessary. That case exists
but is the rare one; the common case is the opposite and worse — today's
gate is *stronger* than the advertised 900 s, and the header under-promises.
This matters for the fix: a lock of *exactly* 900 s, replacing the count
gate, would **weaken** the platform. See §3.

## 3. The fix: deny while `locked OR over-ceiling`, advertise the later of the two

A real enforced lock, without giving up the hourly ceiling.

```
trip     := globalCount >= GlobalCeiling
lockEnds := lastGlobalFailureAt + GlobalLockSecs
countEnds:= (the failure whose expiry drops the count below the ceiling) + GlobalWindowSecs

deny when trip AND now < max(lockEnds, countEnds)
RetryAfterSecs = ceil(max(lockEnds, countEnds) - now)
```

Properties, each of which is the reason for a clause:

- **It is a real lock.** Once tripped, access is refused for at least
  `GlobalLockSecs` — the constant now means what its name says, and setting
  the env var changes behaviour.
- **It is never weaker than today.** `countEnds` is today's gate; taking the
  max can only extend a denial, never shorten one. This is a strict
  tightening, which is what makes it safe to ship without a staged rollout.
- **`Retry-After` becomes true.** A caller that waits the advertised
  interval and retries is allowed through, which is the whole point of the
  header.
- **It cannot be held open by an attacker.** See §4 — this depends on an
  invariant that must be preserved deliberately.

### Alternative considered and rejected

*Pure 900 s lock, replacing the count gate.* Simpler, and it is the literal
reading of "make it a real lock" — but it caps the maximum denial at 15
minutes where today it can reach an hour. Rejected: the fix should not
trade away protection to fix a header. Noted here so the choice is not
silently re-litigated later.

## 4. Load-bearing invariant: a denied attempt is never recorded

All three gate call sites `return` immediately on a denial, before any
`recordAttempt`:

| Call site | Denial path |
|---|---|
| `login/endpoint.go:403` | `writeTooManyRequests` then `return` — `recordAttempt` at :411/:426/:520 is not reached |
| `login/twofactor.go:172` | `writeTooManyRequests` then `return` |
| `webauthn/api/api.go:231` | `huma.Error429TooManyRequests` |

So the failure timeline **freezes** while an identifier is denied. That is
what makes `lastGlobalFailureAt + GlobalLockSecs` a bounded, deterministic
lock end: attempts made during the lock cannot push it further out.

Without this invariant the design inverts into a denial-of-service: an
attacker who knows an email could hold the victim locked out indefinitely
at one attempt every 15 minutes. **The Java port must preserve it**, and it
needs a test of its own (§6) because it is an emergent property of early
`return`s rather than something the code states.

Note also that `trip` remains part of the condition. Because the count is
still required, holding a lock open costs an attacker a sustained ~100
failures/hour — exactly what it costs today. Dropping `trip` and gating on
the lock alone would reduce that to ~4/hour.

## 5. Contract and code changes

**Repository.** `FailureCountByIdentifierSince` returns only a count. It
needs the two timestamps the decision now requires. Extend it to mirror the
pair-window method that already returns a timestamp:

```go
// was: FailureCountByIdentifierSince(ctx, identifier, since) (int, error)
FailureStatsByIdentifierSince(ctx, identifier, since) (count int, lastFailureAt *time.Time, nthOldest *time.Time, err error)
```

`nthOldest` is the failure at `OFFSET (count - GlobalCeiling)` ordered
ascending — the one whose expiry from the window drops the count to
`ceiling-1`. It is `nil` when `count < ceiling`, in which case the caller
does not need it. Passing the ceiling into the repository is acceptable
here; the alternative (returning the whole timestamp list) is worse at 100+
rows per check.

**Reason.** Add `ReasonGlobalLocked = "global_locked"` alongside the
existing `ReasonGlobalCeiling`, and return it when `lockEnds > countEnds` —
so logs and metrics distinguish "still inside the enforced lock" from
"still over the hourly ceiling". Both are `[C]`-visible only as the 429;
the reason string is internal.

**`Decision`** is unchanged in shape.

**Java port.** Same decision function, same repository contract. The
decision belongs in a pure function over `(policy, now, stats)` with no I/O,
so the table in §6 becomes a `@ParameterizedTest` with no database —
mirroring how `defaultPolicy()`/`fakeRepo` are tested in Go today.

## 6. Tests

Go and Java both, driven off the same table:

| Case | Setup | Expect |
|---|---|---|
| Under ceiling | 99 failures in-window | allowed |
| Trip | 100 failures, last just now | denied, `RetryAfterSecs ≈ 900` |
| Lock enforced past count-clear | 100 failures all ~59 min old, last one 10 min ago | denied — today's code allows this once they age out; the lock does not |
| Ceiling outlives the lock | 100 failures spread, last 20 min ago, count still ≥100 | denied, `RetryAfterSecs` = time to `countEnds`, **not** 900 |
| Lock expires | last failure 901 s ago, count fallen below ceiling | allowed |
| Success clears both | a success after the trip | allowed (cutoff moves to `lastSuccess`) |
| `Retry-After` is honest | wait exactly the advertised interval, retry | allowed — the assertion the whole change exists for |
| Denied attempts don't extend | trip, then 5 denied attempts, then wait 900 s from the *last recorded failure* | allowed — pins §4 |

The `Retry-After`-is-honest case and the denied-attempts case are the two
that would silently regress; keep them named after what they protect.

## 7. Out of scope

Recording ceiling trips as an auditable event, and admin unlock, are both
worth having and neither is required by this fix. `docs/backlog.md`.
