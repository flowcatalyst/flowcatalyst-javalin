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

## No grace period on client-secret rotation (auth-core Q14)

**Owner ruling 2026-08-24: keep current behaviour in the port; spec the
grace period here as a later improvement.** Java reproduces the hard
cutover exactly — this is not a port deviation.

### Current behaviour, both sides

`oauth_clients.client_secret_ref` is a single column holding one encrypted
reference, and rotation overwrites it:

```go
// SetSecretRef records a rotated encrypted secret reference.
func (c *OAuthClient) SetSecretRef(ref string) {
    c.SecretRef = &ref          // overwrite, not append
    c.UpdatedAt = time.Now().UTC()
}
```

`RotateOAuthClientSecret` (`operations/oauth_client.go:317`) mints a secret,
calls `SetSecretRef`, stashes the plaintext for one-shot retrieval, emits
`OAuthClientSecretRotated`, and saves. Verification takes exactly one ref
with no fallback (`verifyClientSecret`, decrypt-and-compare in constant
time). There is no `previous_secret_ref` anywhere in the schema.

So the instant rotate returns, every caller still presenting the old secret
gets `401 Invalid client credentials`.

### Why it is worth changing later

Confidential clients are machine-to-machine: the secret lives in a config
file or a secrets manager, in every replica. A hard cutover makes rotation
a synchronised operation — mint, then every deployment holding the old
secret is broken until redeployed. There is no window in which both work,
so a fleet cannot be rolled gradually, and rolling *back* to the previous
deployment restores a secret that no longer works.

The practical consequence is that operators rotate rarely, which is the
opposite of what rotation is for.

### The change: two secrets, two ways to end the overlap

**Additive schema** — `previous_secret_ref VARCHAR`,
`previous_secret_expires_at TIMESTAMPTZ`, both nullable. Additive keeps the
Go rollback path intact (Go ignores the columns), with one caveat worth
stating: after a rollback, a client still on the old secret stops being
accepted, because only the Java side knows to check `previous_secret_ref`.

**Verification** — try `client_secret_ref`; if that fails and
`previous_secret_expires_at > now()`, try `previous_secret_ref`. Both
comparisons stay constant-time, and a failure of both must be
indistinguishable from a single failure in timing and in the response.

**Ending the overlap — both paths are required:**

1. **Timed lapse.** Rotate moves current → previous with
   `previous_secret_expires_at = now + grace`. Grace is configured, with a
   sane default (24h); `graceSeconds: 0` on the rotate request reproduces
   today's hard cutover, so the strict behaviour remains available rather
   than being replaced.
2. **Explicit immediate revoke.** A separate operation that clears
   `previous_secret_ref` now, without minting anything. **This is the more
   important of the two**: a leak is usually discovered *after* a routine
   rotation, at which point the operator needs to kill the old credential
   without disturbing the new one. Making it a parameter of rotate only
   would force an unnecessary second rotation — and another fleet-wide
   redeploy — at exactly the wrong moment.

**Events** — `OAuthClientSecretRotated` already exists and carries the
rotation; add one for the explicit revoke so the compromise response is
auditable and distinguishable from a routine lapse.

**Observability** — emit a signal whenever a request authenticates on the
*previous* secret. That is the operator's answer to "who still has not
redeployed?", visible while the window is open rather than as 401s after it
closes. Without it the grace period trades a loud failure for a silent one.

### The trade-off being accepted

During the window there are two live credentials for one client. That is
only a real weakness when rotating *because* the old secret leaked — which
is precisely the case path 2 exists to serve. Keeping `graceSeconds: 0`
available means the strict behaviour is a choice at the call site rather
than a property of the platform.

---

## `flushGroup` is honoured from any target (router Q54)

**Owner ruling 2026-08-24: honour the Go behaviour — correct for this
deployment's context, where targets own the records they are pointed at.
Logged here to revisit.** The Java port implements no gate.

### Current behaviour, both sides

A target answering a 2xx with `{"ack": true, "flushGroup": true}` causes the
router to **ACK the remaining messages of that message group without
delivering them** (`mediator.go:356-365`, `pool.go:760-806`). Suppression is
TTL-bounded — `delaySeconds` on the same response, default 60 s, capped at
5 min — and self-heals: the next message after the window probes the target.

The saving is real and is the point of the feature: the check runs *before*
the rate limiter, so a flushed group spends neither a rate-limit token nor a
concurrency slot, where previously the target had to absorb every sibling
one delivery at a time.

### Why it is worth revisiting

The safety condition is that the target **already owns the records** being
pointed at (the message-pointer pattern) and will re-drive them itself.
Flushed messages are never delivered and, once ACKed, are gone from the
broker for every backend.

Nothing enforces that condition. It is asserted in code comments and in
`docs/wire-contract.md`, and honoured from **any** target that sets the
flag. A target that sets it while holding the only copy of a payload loses
data in a way that is indistinguishable from a bug: no error, no warning, no
metric (see below), just messages that quietly stop arriving.

The exposure is bounded by who can be a mediation target — targets are
configured, not arbitrary — which is why honouring it is reasonable here.
It becomes worth revisiting if targets are ever operated by parties who do
not also own the underlying records, or if a target's implementation can be
changed without the platform's knowledge.

### If it is revisited, the shape

Honour `flushGroup` only for pools or targets explicitly opted in by config,
so the capability is granted rather than assumed. One config field, one
check at the point the outcome is applied, and a line in the integrator
documentation stating the ownership requirement as a precondition rather
than a warning.

### Related gaps, worth closing regardless (router Q52, Q53)

These are not the safety question, but they are what makes the safety
question hard to monitor:

- A message ACKed because its group is suppressed records **no pool metric
  at all** — not success, not transient, not rate-limited. A pool whose
  groups are being flushed heavily looks *idle* on `/monitoring` and in
  Prometheus rather than busy-but-suppressed. The registry's own
  `suppressed` counter exists and nothing reads it.
- `GroupFlushRegistry.SuppressedUntil`, `.Clear` and `.Stats` have no
  callers outside tests, so an operator cannot ask "why is this group
  quiet?" — the question the TTL design explicitly anticipates — and cannot
  lift a suppression early.
