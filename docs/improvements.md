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
