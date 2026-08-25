# Platform improvements deferred out of the port

Items here are **not porting work**. The port's job is to reproduce the Go
platform's behaviour faithfully; these are changes that would improve the
platform itself, apply equally to Go and Java, and are deliberately *not*
being made as part of the migration.

Nothing in this file blocks a port unit. Each entry records the current
behaviour (which both sides implement), why it is worth changing later, and
what the change would actually cost — so a future decision starts from
evidence rather than a rediscovery.

---

## No refresh-family revocation on authorization-code replay (auth-core Q13)

**Owner ruling 2026-08-24: keep current behaviour.** Java reproduces the Go
behaviour exactly; this is logged as a future improvement, not a port
deviation.

### Current behaviour, both sides

An authorization code is single-use, enforced atomically:

```sql
UPDATE oauth_oidc_payloads SET consumed_at = NOW()
WHERE id = $1 AND consumed_at IS NULL AND expires_at > NOW()
RETURNING …
```

(`grantstore.FindAndConsume`, called from `oauthapi/token.go:514`.) A replay
matches no row, so the handler answers `400 invalid_grant "Invalid or
expired authorization code"`. **The replay is rejected — that part is
correct and stays.**

Nothing further happens. The tokens minted by the *first* redemption of that
code remain live.

### Why it is worth changing later

A code presented twice means the code leaked — referrer header, browser
history, a proxy log, a malicious app on the redirect URI. RFC 6749 §4.1.2
(and OAuth 2.1 more firmly) says the server must deny the request **and**
should revoke all tokens previously issued from that code.

The attack that survives today:

1. Attacker obtains the leaked code and redeems it first, winning the race —
   receiving an access token and a refresh token.
2. The legitimate client redeems and gets `invalid_grant`. The user sees one
   failed login, retries, and succeeds.
3. Nothing is revoked. The attacker's refresh token keeps rotating.

The single moment the platform could have detected the compromise is
observed and discarded, and to every human involved it looks like a
transient glitch.

Notably, **the revocation machinery already exists**: `token.go:607-611`
roots a `TokenFamily` on the first refresh token specifically so "the whole
family [can be] revoked if a rotated-out token is ever replayed", and
`grantstore.Rotate` implements that reuse detection. The code-replay signal
and the family-revocation mechanism are both present and simply not wired
to each other.

### What the change would cost

More than it appears, which is part of why it is deferred:

1. **Distinguish the two nil cases.** `FindAndConsume` returns `nil` for
   both "no such code" and "already consumed" — indistinguishable at the
   call site, so there is currently no way to *detect* a replay as opposed
   to a bad code. Needs the row returned regardless with `consumed_at`
   inspected, or a `SELECT` fallback.
2. **Link code → issued tokens.** No such link is stored. Revoking "the
   tokens issued from this code" requires stamping the code id (or its
   resulting grant/family id) onto what the first redemption minted.
3. **Decide the blast radius.** Revoke only the tokens from that code, or
   the whole rotation family descended from them? The family is the useful
   answer and the more destructive one.
4. **Avoid a new denial-of-service.** Once a replay revokes live tokens,
   anyone who can replay a *stale* code can log a user out. Codes are
   short-lived and single-use, which bounds this, but it should be reasoned
   about rather than discovered.

### Scale of the risk

Smaller than the login-backoff issues (Q11, Q12): it requires the code to
leak first, and the leak window is short. It is recorded here because it is
exactly the case where the audit trail matters most, and because the
mechanism to fix it is already built.

---

## Client-secret rotation grace window (auth-core Q14) — SHIPPED IN GO

**Status changed 2026-08-25.** This was specced here as a deferred
improvement with the port keeping Go's hard cutover. Go has since
implemented it (`77ede1d`), so this section now records **what Go actually
does** — the Java port reproduces this, not the earlier hard cutover, and
not the spec that preceded it.

### What Go now does

`migration 046` adds `previous_secret_ref` and `previous_secret_expires_at`,
both nullable and additive — an existing row reads as "no overlap in
flight", which is exactly the old behaviour.

| Concern | Behaviour |
|---|---|
| Rotate | `RotateSecretRef` demotes the outgoing secret and bounds it by a grace window |
| Default grace | **24 hours** (`DefaultSecretGrace`) — long enough to roll a fleet across a normal deployment window without being an open-ended second credential |
| Request body | `POST /api/oauth-clients/{id}/rotate-secret` takes an **optional** body `{ "graceSeconds": <int64> }`. A pointer, so existing body-less POSTs from the SPA and the SDK alias keep working |
| `graceSeconds: 0` | Immediate cutover, keeping nothing — the right choice for a secret believed compromised |
| Negative | Rejected, `GRACE_INVALID` |
| Response | `previousSecretExpiresAt`, **omitted on an immediate cutover** so a caller can tell the two apart |
| Verification | `acceptClientSecret` tries the current ref then the previous one while unexpired. Both the client-authenticated grants **and** `client_credentials` go through it — the latter matters most, being the path a fleet mid-rollout uses |
| Only one previous | Rotating twice inside a window retires the older one rather than accumulating valid credentials |
| Immediate revoke | `POST /api/oauth-clients/{id}/revoke-previous-secret` closes an already-in-flight overlap. **Idempotent** — calling it twice, or after the timer has fired, is fine |
| Expiry enforcement | Read through `UsablePreviousSecretRef`, which enforces the expiry. The row still carries a lapsed secret until the purger clears it, so reading the field directly would keep it alive |
| At-rest hygiene | `StartPurger` clears lapsed overlaps. Hygiene, not enforcement — the expiry check above is what actually gates it |

### Two details worth carrying into the Java port verbatim

**`acceptClientSecret` runs both compares rather than returning early on a
current-secret match.** Short-circuiting would make a still-valid *old*
secret measurably slower than a new one, leaking where a client sits in its
rotation. Constant-time comparison of each ref is not enough on its own if
the control flow itself varies.

**Expiry is enforced on read, not on write.** A lapsed previous secret stays
in the row until the purger runs, so any code path that reads
`previous_secret_ref` directly rather than through the usability check would
silently keep a retired credential alive.

### Where our earlier spec differed

Recorded because the differences are decisions, not accidents:

- We specced the grace as *configured with a sane default*; Go made it a
  per-request `graceSeconds` with a 24h default and no server-side config.
  The per-request form is better — a routine rotation and an emergency one
  want different windows, and a config value cannot express that.
- We assumed a rollback caveat would need stating. It still holds: the
  columns are additive so Go ignores them, but after a rollback a client on
  the old secret stops being accepted, because only the newer code checks
  `previous_secret_ref`.
- We called for **a signal when a request authenticates on the previous
  secret** — the answer to "who still has not redeployed?" while the window
  is open. Go has **not** implemented that, and without it a grace period
  trades a loud failure for a silent one. Still worth having; see below.

### Still open

**No UI.** The API is complete; the OAuth client drawer still rotates with
the default and offers neither a grace-window choice nor revoke-previous.
What the drawer needs:

1. A grace control on rotate, with **0 presented as an explicit "cut over
   now"** rather than a number to type — it is the compromise-response path
   and should read as one.
2. A separate **Revoke previous secret** action, enabled only while an
   overlap is in flight, showing `previousSecretExpiresAt`.
3. Whether anything is still authenticating on the old secret — which needs
   the signal below.

### The missing signal, specified

Without this the drawer cannot answer the question an operator actually has
during a rollout, and a grace window trades a loud failure for a silent one.

**Record it where the fallback already happens.** `acceptClientSecret`
already knows it matched the *previous* ref rather than the current one.
That is the only place the fact exists, and it costs nothing to note.

- **Emit an event, not just a counter.** `OAuthClientPreviousSecretUsed`,
  carrying the client id and the time. A counter answers "is anyone?"; an
  event answers "who, and how recently?", which is what decides whether it
  is safe to revoke.
- **Do not log the secret, the ref, or any prefix of either.** The event's
  value is the client identity and the timestamp.
- **Surface it on the client resource** as `previousSecretLastUsedAt`
  (nullable), alongside `previousSecretExpiresAt`. Two timestamps together
  answer the whole question: how long is left, and is anyone still there.
- **Rate-limit or coalesce the write.** A fleet mid-rollout may authenticate
  thousands of times an hour on the old secret; last-write-wins on a
  timestamp column is enough and avoids turning a rollout into write load.

**Then the drawer reads:** *"Previous secret valid for another 6 hours —
last used 4 minutes ago"*, and **Revoke previous secret** is obviously the
wrong button to press. Without it the same drawer shows only a countdown,
and revoking is a guess.

### The grace control itself

Present `graceSeconds: 0` as an explicit **"Cut over now"** action, not a
number to type. It is the compromise-response path — the one taken when a
secret is believed leaked — and typing a zero into a duration field reads
like a default, not like a decision. A short list (24h default, 1h, cut over
now) with the last visually separated says what the API means.

