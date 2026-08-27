# Service account — fixes to make in Go, then port

Go-side changes agreed while extracting `docs/spec/serviceaccount.md`. Same
pattern as `router-fixes.md` and `oauthapi-fixes.md`: **make them in Go
first**, so the Java port reproduces corrected behaviour rather than a defect
plus a deviation.

Owner ruled 2026-08-27 on Fixes 1–3. Fix 4 is a Java-side change already made,
recorded here because the Go equivalent is worth the same treatment.

Verified against Go `426ac85`.

---

## Fix 1 — `roles` on the service-account response is always `[]`

**Owner ruling: fix it — hydrate the roles.**

**Defect.** `rowToServiceAccount` (`internal/platform/serviceaccount/repository.go:121`)
sets the field unconditionally:

```go
Roles:         []RoleAssignment{},
```

The repository never hydrates it, because roles live on the linked `SERVICE`
principal in `iam_principal_roles`, not on the service-account row.
`fromEntity` (`api/dto.go:170`) then maps that empty slice straight onto
`ServiceAccountResponse.Roles`.

**Impact.** `GET /api/service-accounts/{id}` renders `"roles": []` for an
account that holds roles, while `GET /api/service-accounts/{id}/roles` —
which fetches them from the principal via `serviceAccountRoles` — returns the
real set. One route says none, its own sibling says several. A UI or SDK
reading the account object concludes the account is unroled.

This is the same shape as `principal` spec §11 Q4, where the by-id read and
its sub-route disagree; that one is still open, this one is ruled.

**Fix.** Hydrate `Roles` on the single-account read using the same lookup
`listRoles` already uses, so the two routes cannot disagree. Concretely:
`FindByID` and `FindByCode` populate `Roles` from `iam_principal_roles` via
the linked principal, exactly as `State.serviceAccountRoles` does.

**Keep it off the LIST read**, matching the existing `principalId` decision
(`api/dto.go:154-158`: populated on the single read, "omitted from list
responses to avoid a per-row lookup"). If the list is ever changed to hydrate,
both fields should move together.

**Test.** An account with two roles reports both on `GET /{id}` and the same
two on `GET /{id}/roles` — asserted as *equal sets*, not merely non-empty, so
the two routes are pinned to each other rather than to a literal.

---

## Fix 2 — `lastUsedAt` is never written

**Owner ruling: fix it — stamp it.**

**Defect.** `grep LastUsedAt` over the Go tree finds only
`PreviousSecretLastUsedAt` on `auth.OAuthClient` — a different field on a
different aggregate. The service account's `LastUsedAt` is read from the
column (`repository.go:115`), carried on the entity, rendered on the wire
(`dto.go:160`) — and never assigned. It is always null.

**Impact.** An operator cannot tell a live service account from one whose
credentials were rotated into a config nobody deploys any more. That is the
question the field exists to answer, and it is the one an operator asks
before revoking.

**Fix.** Stamp `last_used_at` when the account's credentials are actually
*used*. There are two distinct uses and they should both count:

1. **Token mint** — `POST /api/service-accounts/{id}/token`
   (`api/api.go:281`). An anchor obtaining a bearer for the account is a use.
2. **Outbound webhook credential resolution** — `NewCachedOutboundCredsResolver`
   (`secretresolver.go`), which hands the bearer/signing secret to a delivery.

Note the resolver is **memoised with a TTL**, so a naive stamp inside it fires
only on a cache miss. That is acceptable and should be stated rather than
worked around: the field means "last known use", and a per-delivery write on a
hot path would cost more than it is worth. Write it best-effort and outside
the delivery's own transaction — a failed stamp must never fail a delivery or
a mint.

**Test.** Mint a token, re-read the account, assert `lastUsedAt` moved from
null to non-null; assert a failed stamp does not fail the mint.

---

## Fix 3 — the `_ref` columns hold plaintext

**Owner ruling: fix it.**

**Defect.** `wh_auth_token_ref` and `wh_signing_secret_ref` are named as
*references* — the convention elsewhere for "a pointer into a secret store" —
but hold the secret itself. `repository.go:92` writes
`WhAuthTokenRef: creds.Token` where `creds.Token` is the plaintext produced by
`generateAuthToken()`, and `secretresolver.go` reads it straight back out as
`OutboundCreds.BearerToken`.

**This one needs care, because the plaintext is genuinely required.** The
bearer token is stamped on outbound webhook requests, so it cannot be hashed;
some recoverable form must exist. The defect is not that a secret is stored —
it is that the *name* asserts an indirection that does not exist, so anyone
reasoning about the blast radius of a database dump will get it wrong.

**Fix, in preference order:**

1. **Encrypt at rest under the app key**, as the Java `principal` aggregate
   already does for developer credentials (`DeveloperSecrets` +
   `Encryption.fromKeys`). The column then genuinely holds a reference-like
   ciphertext and the name becomes true. This is the real fix.
2. If (1) is deferred, **rename the columns** to `wh_auth_token` /
   `wh_signing_secret` so the schema stops claiming an indirection it does not
   have. A migration, but a cheap one, and it stops the next reader being
   misled.

Doing neither leaves a schema that lies about where secrets are.

**Java note.** The port should implement (1) from the start — the encryption
plumbing already exists and is wired in `Platform.register`. Record it as a
deliberate deviation if Go has not landed the change by then.

---

## Fix 4 — the rotation stash writes before the commit

**Already fixed in Java (2026-08-27). Recommended for Go.**

**Defect.** `stashSecret(sa.ID, "token", token)` is called inside `Execute`
(`operations/regenerate_token.go:52`), which builds the `Plan` that the
envelope commits *afterwards*. If the commit fails, a freshly generated
plaintext is left in the process-wide `stash` for the full `stashTTL`
(2 minutes) belonging to a rotation that never happened. The same shape is in
`regenerate_secret.go` and `create_credentials.go`.

**Impact is bounded but the shape is wrong.** The handler only pops after a
successful operation, so nothing delivers the orphan today — it expires. But a
side channel that can hold a secret for a rolled-back transaction is one
refactor away from handing it out, and the TTL comment
(`regenerate_token.go:95-99`) reasons only about "the handler died between
commit and response", not about the commit failing.

**Fix.** Remove the process-wide stash and hand the plaintext back through a
**caller-owned sink** — the operation writes to it after authorisation and
after minting, the handler reads it after the run returns. That makes three
properties structural rather than remembered:

- the plaintext cannot outlive the request (the sink is a local);
- an unauthorised or rejected request never reaches the minting path;
- a rolled-back commit discloses nothing, because the handler only reads the
  sink on the success path.

It also deletes `stashTTL`, `sweepStash`, `stashFresh`, `PopStashedSecret` and
the `sync.Map` outright.

**Java.** Done. `DeveloperSecrets` is now minting-only; `SetDeveloperCredential`
takes a `Consumer<String> disclose`, mints **after** `requireSelfOrUserAdmin`,
and `PrincipalApi` owns an `AtomicReference` for the response. Both directions
are asserted: the plaintext reaches the caller on success, and the sink stays
null when the request is rejected for authorisation or for the business rule.
The service-account port will use the same shape rather than porting the
stash.
