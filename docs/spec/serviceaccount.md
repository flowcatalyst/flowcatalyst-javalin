# Service account — behavioural spec

Semantic extraction (CONVENTIONS §8 step 1) of Go's
`internal/platform/serviceaccount` — machine-to-machine principals carrying
webhook credentials and roles. **Not code.** The Java is written *from* this;
tests assert it.

`[C]` = wire/storage contract to reproduce byte-for-byte. `[I]` = internal
mechanics the Java may restructure while the stated behaviour holds. Open
questions are numbered **Q1…Qn** and collected in §9; until an owner ruling
lands, the Go behaviour is the spec.

Extracted against Go `426ac85` (2026-08-27), which includes `de868dd` — see
§8, it changes what a minted token carries.

## 1. Purpose and boundaries

A service account is the *credential-holding* half of a machine identity. The
*authorisation* half is a *linked `SERVICE` principal* in the `principal`
aggregate, which is where roles actually live. This aggregate owns:

- identity (`code`, `name`, `description`, `active`),
- reach (`clientIds`, `scope`, `applicationId`),
- **webhook credentials** — how the platform authenticates *outbound* calls
  made on this account's behalf,
- a projection of the linked principal's roles for reading.

The account and its principal are two rows that must agree. Role assignment
(§4.5) writes through to the principal; the account's `roles` are a read
projection.

## 2. Aggregate

```
ServiceAccount { id, code, name, description?, active, clientIds[], scope?,
                 applicationId?, webhookCredentials, roles[], lastUsedAt?,
                 createdAt, updatedAt }
```

`id` is a TSID with the service-account prefix. `serviceAccountTableID` exists
in Go's struct as a non-serialised join key (`json:"-"`) and is **not** part
of the wire contract.

### 2.1 `WebhookCredentials` [C]

```
{ authType, token?, username?, password?, headerName?,
  signingSecret?, signingAlgorithm?, signatureHeader? }
```

`authType` ∈ `NONE | BEARER_TOKEN | BASIC_AUTH | API_KEY | HMAC_SIGNATURE`,
read **leniently**: unknown or absent → `NONE` (**Q1**: load-bearing or
accident? A misspelled `BEARER_TOKEN` silently sends an unauthenticated
webhook — the same shape as the dispatch-mode default the owner ruled on for
the router, where "expensive to discover you never had it" decided the
question). Every other field is optional and absent means absent — **never
`""`** inside the JVM (CONVENTIONS).

### 2.2 `RoleAssignment`

```
{ roleName, clientId?, assignmentSource?, assignedAt, assignedBy? }
```

Note the JSON name is `roleName` while Go's field is `Role` — the wire name
is the contract.

## 3. HTTP surface (lockfile) — 14 operations

All routes require a bearer. Gates, with anchors passing every permission
gate: **read** = `SERVICE_ACCOUNT_VIEW`; **write** = any of
`SERVICE_ACCOUNT_{CREATE,UPDATE,DELETE}`; **delete** = `SERVICE_ACCOUNT_DELETE`
specifically.

| Method / path | Gate | Success |
|---|---|---|
| `GET /api/service-accounts` | read | 200 `{serviceAccounts[], total}` |
| `POST /api/service-accounts` | write | 201 `ServiceAccountResponse` |
| `GET /api/service-accounts/code/{code}` | read | 200; unknown → 404 `ServiceAccount_NOT_FOUND` |
| `GET /api/service-accounts/{id}` | read | 200; unknown → 404 |
| `PUT /api/service-accounts/{id}` | write | 204 |
| `POST /api/service-accounts/{id}/deactivate` | write | 204 |
| `DELETE /api/service-accounts/{id}` | **delete** | 204 |
| `GET /api/service-accounts/{id}/roles` | read | 200 role list |
| `PUT /api/service-accounts/{id}/roles` | **anchor only** | 200 `ServiceAccountRolesAssignedResponse` |
| `POST /api/service-accounts/{id}/regenerate-token` | write | 200 (see §5) |
| `POST /api/service-accounts/{id}/regenerate-auth-token` | write | 200 — **alias of the row above** |
| `POST /api/service-accounts/{id}/regenerate-secret` | write | 200 (see §5) |
| `POST /api/service-accounts/{id}/regenerate-signing-secret` | write | 200 — **alias** |
| `POST /api/service-accounts/{id}/token` | **anchor only**, audited | 200 `ServiceAccountTokenResponse` (§8) |

The two alias pairs are one handler each, registered twice: the SPA calls the
short paths, `fcsdk` the long ones. Both must exist and behave identically.

**Two routes are anchor-only rather than permission-gated** — role assignment
and token mint. Both hand out authority rather than editing a record, so the
gate is the tier, not a permission that could be granted to a client
administrator.

## 4. Operations

### 4.1 Create (`CreateServiceAccountWithCredentials`)

Validate, in order: `code` non-blank → `CODE_REQUIRED`; code format →
`INVALID_CODE_FORMAT`; `name` non-blank → `NAME_REQUIRED`. Then a code
uniqueness check → 409 `CODE_EXISTS` "Service account with code '<code>'
already exists".

Creation also mints an OAuth client secret (`generateOAuthClientSecret`
returns plaintext + a stored reference) and the initial webhook credentials.
**The plaintext is returned once and never stored in the clear** — see §5.

### 4.2 Update / 4.3 Deactivate / 4.4 Delete

`id` non-blank → `ID_REQUIRED`; unknown → 404 `ServiceAccount_NOT_FOUND`.
Deactivate sets `active=false`; delete removes the row. Both answer 204.

### 4.5 Assign roles (`AssignRolesToServiceAccount`)

`serviceAccountId` non-blank → `SERVICE_ACCOUNT_ID_REQUIRED`; unknown → 404.
The operation **resolves the linked `SERVICE` principal** and writes the role
set there; the account's `roles` projection follows. This is why the route is
anchor-only: it grants authority in the `principal` aggregate.

### 4.6 Regenerate auth token / signing secret

`serviceAccountId` non-blank → `SERVICE_ACCOUNT_ID_REQUIRED`; unknown → 404.
Each rotates one credential and emits its own event
(`ServiceAccountTokenRegenerated`, `ServiceAccountSecretRegenerated`).

## 5. The one-shot secret stash [I, but the *behaviour* is contract]

A freshly generated plaintext must reach the caller **once** and then cease to
exist. Go stashes it keyed by `(id, kind)` and the HTTP handler pops it to
build the response.

Rules the Java must keep, however it is structured:

- **Pop is destructive** — a second read gets nothing.
- **Entries expire (Go: 2 minutes).** The legitimate pop happens microseconds
  after the stash, in the same request; anything older means the handler never
  collected it — for instance it died between commit and response. A plaintext
  that outlived its response must not be handed to anyone later.
- **Expiry is swept on every store**, so no background thread is needed and
  the map holds only in-flight entries.

**Do not port the stash.** Java removed its equivalent on 2026-08-27 after
finding the same flaw: the stash was written from inside the operation's
`execute`, which runs *before* the commit, so a failed commit left a plaintext
in a process-wide map for the full TTL belonging to a credential that was
never stored. Nothing delivered it, but a side channel that can hold a secret
for a rolled-back transaction is one refactor away from handing it out.

The shape to use instead — already in `principal`, and what this port must
copy — is a **caller-owned sink**: the operation takes a `Consumer<String>`,
mints **after** the authorisation rules have passed, and writes the plaintext
to it; the handler owns an `AtomicReference` and reads it only after `run`
returns. Three properties become structural rather than remembered: the
plaintext cannot outlive the request, an unauthorised request never reaches
the minting path, and a rolled-back commit discloses nothing. No TTL, no
sweep, no global. Go fix: `serviceaccount-fixes.md` Fix 4.

## 6. Events

`operations/events.go` carries one event per mutation: created, updated,
deactivated, deleted, roles-assigned, token-regenerated, secret-regenerated.
Per CONVENTIONS, an event record must **never shadow a `DomainEvent`
accessor** — an event about a service account names it `serviceAccountId`,
never `principalId` (which is the actor).

### 5.1 The `_ref` columns hold plaintext

`wh_auth_token_ref` and `wh_signing_secret_ref` are named as *references* but
hold the secret itself (`repository.go:92` writes the plaintext; the outbound
resolver reads it straight back). The plaintext is genuinely required — the
bearer is stamped on outbound webhooks, so it cannot be hashed — but the name
asserts an indirection that does not exist.

**Owner ruling 2026-08-27: fix it.** The Java port **encrypts at rest under
the app key** from the start, as `principal` already does for developer
credentials; the encryption plumbing exists and is wired in
`Platform.register`. Go fix: `serviceaccount-fixes.md` Fix 3 — encrypt, or at
minimum rename the columns so the schema stops claiming an indirection it does
not have.

## 7. Persistence

`repository.go` + `serviceaccount.sql.go`. Reads hydrate the roles
projection. **One `findOne(Condition)` for every single-row lookup**
(`findById`, `findByCode`), per the rule promoted from the `principal` audit —
Go's separate lookups are exactly how `de868dd` happened.

## 8. Token mint — `POST /{id}/token` [C]

Anchor-only. In order:

1. anchor gate;
2. token minting wired at all, else 500 `TOKEN`;
3. load account, unknown → 404;
4. **account** inactive → 400 `SERVICE_ACCOUNT_INACTIVE` "the service account
   is deactivated — reactivate it before minting a token";
5. load the linked `SERVICE` principal; absent → 500 `PRINCIPAL` (a service
   account without one is a broken invariant, not a user error);
6. **principal** inactive → 400 `SERVICE_ACCOUNT_INACTIVE` "the service
   account's principal is deactivated" — a *different message* under the same
   code, so the two states are distinguishable in logs;
7. flatten the principal's roles to a permission ceiling and mint with that
   scope — **the same grant computation as the `client_credentials` path**;
8. **best-effort audit row**: who obtained a credential for which account.
   Never the token itself. A failed audit must not fail the mint.

**Go drift `de868dd` (2026-08-27) applies here.** `FindByServiceAccount`
hydrated roles only, not the client-access and application-access junctions,
so an app-scoped account's token carried an empty `applications` claim —
indistinguishable from one confined to nothing, since `CanAccessApplication`
has no anchor bypass. Java's `PrincipalRepository` is structurally immune (all
lookups share one `findOne`), so this needs no port-side fix — but the token
mint must be **tested** for a non-empty `applications` claim on an app-scoped
account, because that is the assertion Go lacked.

## 9. Two fields that are always empty — verified, not suspected

Both were going to be open questions; the extraction answered them, so they
are stated as facts the port must decide about rather than questions about Go.

### 9.1 `roles` on the account response is **always `[]`**

`rowToServiceAccount` sets `Roles: []RoleAssignment{}` unconditionally — the
repository never hydrates them, because roles live on the linked `SERVICE`
principal in `iam_principal_roles`, not on the service-account row.
`fromEntity` then maps that empty slice into `ServiceAccountResponse.roles`.

So `GET /api/service-accounts/{id}` renders `"roles": []` for an account that
has roles, while `GET /api/service-accounts/{id}/roles` — which fetches them
from the principal — returns the real set. **One route says none, the sibling
says several.** Structurally the same trap as `principal` §11 Q4, where the
by-id read and its sub-route disagree.

**Owner ruling 2026-08-27: hydrate it.** `FindByID`/`FindByCode` populate
`roles` from the linked principal using the same lookup the sub-route uses, so
the two routes cannot disagree. Kept **off** the list read, matching the
existing `principalId` decision; if the list ever hydrates, both move
together. Go fix: `serviceaccount-fixes.md` Fix 1.

### 9.2 `lastUsedAt` is never written

`grep LastUsedAt` over the Go tree finds only `PreviousSecretLastUsedAt` on
`auth.OAuthClient`, a different field on a different aggregate. Nothing writes
the service account's. It is on the aggregate, read from the column, and
rendered — and always null.

**Owner ruling 2026-08-27: stamp it.** Both uses count — the token mint
(`POST /{id}/token`) and outbound webhook credential resolution. The resolver
is TTL-memoised so the stamp fires on a cache miss rather than per delivery;
that is the intended meaning ("last known use") rather than a compromise.
Best-effort and outside the delivery's transaction: a failed stamp must never
fail a mint or a delivery. Go fix: `serviceaccount-fixes.md` Fix 2.

## 10. Open questions for the owner

1. ~~**Q3**~~ — **settled by drift `6cbe708` (X-06), folded in during the Java
   port (2026-09-05):** unknown values reject loudly (`INVALID_AUTH_TYPE` on
   the wire, `CorruptServiceAccountException` on a corrupt stored row); blank
   still means `NONE`. See §11.
2. **Q4** — the stash TTL (2 minutes) is moot: the Java port never had a
   stash (§5) — no TTL, no sweep, no global. No ruling needed.
3. **Q5** — `principalId` is populated on the single read and omitted from
   list responses "to avoid a per-row lookup". The list already does one
   hydration pass; is the omission still worth the asymmetry? **Still open** —
   the Java port kept Go's answer (omit).

## 11. Deliberate deviations

Two Go commits landed after this spec's extraction commit (`426ac85`); both
are folded into the Java port as drift, per the task that implemented it:

- **Drift `fdcd2c1` (2026-09-05, upgrade legacy plaintext on read).** A
  Go-created database may still hold plaintext in `wh_auth_token_ref` /
  `wh_signing_secret_ref`. `ServiceAccountRepository`'s single-row reads
  (`findById`, `findByCode`) now upgrade a plaintext value to `encrypted:…`
  **compare-and-set** — `UPDATE … WHERE id = ? AND col = <plaintext seen>` —
  so a racing rotation's new secret is never clobbered by a re-encryption of
  the stale value the read saw. Pinned by
  `ServiceAccountRepositoryTest#upgradeLegacySecretsDoesNotClobberARotationThatWonTheRace`
  (constructs the race directly: a rotation lands between the "as seen"
  snapshot and the upgrade call) and
  `#findByIdUpgradesLegacyPlaintextToEncryptedInPlace`. Not called from
  `findAll` — matches the existing "no per-row lookup on the list" decision.

- **Drift `6cbe708` (2026-09-05, X-06, settles Q3).** `WebhookAuthType.parse`
  no longer coerces an unrecognised value to `NONE`: blank still means `NONE`,
  but anything else throws `WebhookAuthType.UnrecognisedAuthTypeException`.
  The wire boundary (`ServiceAccountApi.WebhookCredentialsDto#toEntity`)
  catches it and re-throws validation `INVALID_AUTH_TYPE`; the stored
  boundary (`ServiceAccountRepository`) catches it and wraps it in
  `CorruptServiceAccountException`, exactly mirroring
  `platform.dispatchjob.DispatchJobStatus` / `CorruptDispatchJobException`.
  A corrupt row fails the whole `findAll` list, not just that row. Pinned by
  `ServiceAccountTest#authTypeRejectsUnrecognisedValuesLoudly`,
  `ServiceAccountRepositoryTest#findByIdFailsLoudlyOnACorruptWebhookAuthType`,
  `ServiceAccountOperationsTest#findAllFailsTheWholeListOnOneCorruptRow`, and
  `ServiceAccountApiTest#createRejectsAnUnknownWebhookAuthType`.

Two further deviations the port could not avoid, recorded rather than
improvised around:

- **The OAuth client secret (§4.1) is not backed by a real OAuth client.**
  "Creation also mints an OAuth client secret (`generateOAuthClientSecret`
  returns plaintext + a stored reference)" cannot be implemented as written:
  there is no Java `auth`/`OAuthClient` aggregate in this codebase yet (the
  auth port is blocked on owner rulings — `docs/spec/auth-core.md`,
  `docs/spec/auth-identity.md`). `CreateServiceAccountWithCredentials` creates
  the service account and its linked `SERVICE` principal only.
  `ServiceAccountApi#create` returns the `unavailable:auth-not-ported`
  marker in both `oauth` fields so `CreateServiceAccountResponse.oauth`
  (required by the lockfile) has a well-formed shape without pretending to
  be a credential. Once the `auth` aggregate lands, this
  operation should gain a real OAuth-client write and the stub should be
  deleted.
- **The token mint's "best-effort audit row" (§8 step 8) is not written.**
  `AuditLogRepository` is read-only by design ("the rows are written by the
  unit-of-work sink, never here"), and the mint emits no domain event (§6 has
  no "token minted" entry) for the envelope to carry an audit row alongside.
  Adding a write path to `AuditLogRepository` is outside this unit's scope
  (`io.flowcatalyst.platform.serviceaccount.**`). `MintServiceAccountToken`
  and `ServiceAccountApi#mintToken` implement every other step of §8 exactly,
  including the account/principal inactive checks with their two distinct
  messages and the `last_used_at` stamp.
- **The token mint signs RS256 under the platform signing key**
  (`RsaServiceAccountTokenMinter`, orchestrator 2026-09-05), with `kid` in
  the header and the full access-token claim set (`type`, `tier`, `email`,
  `name`, `clients`, `roles`, `applications`, `all_applications`, `scope`,
  `token_use=api`, `jti`), so a minted bearer is accepted by this server's
  own `JwtVerifier` — pinned by `RsaServiceAccountTokenMinterTest` (verifies
  under the platform key; a token under another key is rejected) and by the
  API test through the real authenticator. `ServiceAccountTokenMinter` stays
  the seam the platform token service will take over when the auth port
  lands. A first cut signed HS256 under the *encryption* app key; rejected
  in review — key reuse across purposes, and unverifiable by the RS256
  authenticator.
- **The OAuth pair on create is an explicit marker, not a fake credential:**
  both `oauth.clientId` and `oauth.clientSecret` are the literal
  `unavailable:auth-not-ported` until the `auth` aggregate exists (a random
  value that looked like a credential would be stored by an integrator and
  fail silently later). Queued in `backlog.md` "Port work queued".
