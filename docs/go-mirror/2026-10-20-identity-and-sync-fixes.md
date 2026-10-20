> Mirroring is paused (2026-10-16); what Java is still missing from Go is in `PENDING.md` in this directory.
> This file is a **hand-off**: nothing here is implemented in Java yet. Its work items are PENDING rows L1..L14.

# Go fixes F28-F34 and F36-F45 (identity, token, sync, router config) and the Rust-found identity items, audited against Java (2026-10-20)

Source: `../flowcatalyst-go` `docs/platform-bug-fixes.md` entries F28..F45 and the commits they name (up to Go HEAD
`04b9c564`), plus Go `docs/identity-go-vs-rust-2026-10-19.md` (the Go vs Rust identity review). Java tree read at
`d2289701` (clean, nothing in `server/src` changed by this commit). Every "Java today" statement below was read in
`server/src/main/java/io/flowcatalyst/...` (paths abbreviated to start at `platform/...`, `router/...`, `server/...`);
nothing was run except one `java.net.URI` parse probe noted under section 20. Where a statement could not be
established it says **UNVERIFIED**.

| Go commit | Entry | What |
|---|---|---|
| `ebb18542` | F28 | service-account `scope` must agree with its client links (`400 SCOPE_REACH_MISMATCH`) |
| `0fb7f0c7` | F29 | router-config `ETag`/`304`, 30 s poll default, paced missing-queue recheck, in-process nudge |
| `87cbc30f` | F30 | role / platform-role / connection / subscription syncs skip and do not count unchanged rows |
| `4f2b3908` | F31 | `attempt_count` counts the terminal attempt; scheduled-job sync rollup subject |
| `49ba1494` | F32 | application admin routes answer `404 Application_NOT_FOUND` (A2 routes; not built in Java) |
| `281d40d3` | F33 | IdP role mapping: role must exist at write; stale mapping skipped at login |
| `8165e614` | F34 | `token_use` is an allowlist for API credentials |
| `377fe770` | F36 | refresh rotation atomic (Java already is, and has the leeway Go declined) |
| `d2827d89` | F37 | TOTP step claim atomic (Java already is) |
| `65e6787f` | F38 | `FC_AUTH_ALLOW_TEST_HEADERS` refused outside local/test (Java already refuses, other gate) |
| `53031d69` | F39 | one cookie builder; `FC_SESSION_COOKIE_SAME_SITE` |
| `81a6f588` | F40 | `/oauth/revoke` only revokes the authenticated client's tokens; optional client-auth-only introspect |
| `dabca1d8` | F41, F42 | `FC_OIDC_REJECT_UNVERIFIED_EMAIL`; `FC_OIDC_ISSUER_PATTERN_STRICT` |
| `26b78d4f` | F43 | passkey counter persisted, regression refused (Java already does both) |
| `a24f549b` | F44 | return-URL control characters (Java already refuses them), fuzz targets |
| `d7114f37` | F45 | dependency bumps, `FC_HTTP_REAL_IP_MIDDLEWARE` |

**Out of scope, one line:** F22-F27 (function version latch, `fn.dispatch` pool/connection, `fcdev login`, the
`platform:function-deployer` role, the migration-numbering bootstrap), F35 (per-function webhook service account, Go
migration 084), runner plumbing, version pruning and `clientCodes` are Go function-runner work; the Java function
runner is on hold (owner, 2026-10-17), so none of it is mirrored. The dispatch-job operator tooling (edit payload,
window, requeue guard, split permissions) is K1..K7 in `2026-10-19-dispatch-job-operator-tooling.md`.

## 0. Summary

Status: **SAME** Java already behaves like Go (a sentence says whether it is stricter); **DIFFERS** Java has the
feature but behaves differently; **MISSING** Java has no equivalent; **UNVERIFIED** could not be established.
Effort S under half a day, M about a day. Order is the recommended implementation order (1 first); `-` means no
code change (tests or a decision only).

| Item | What | Java status | Effort | Order | PENDING row |
|---|---|---|---|---|---|
| F34 | `token_use` allowlist (api or absent) on bearer, cookie and function endpoints | DIFFERS (denylist of `identity`) | S | 1 | L1 |
| F40 | `/oauth/revoke` ownership; `FC_OAUTH_REVOKE_ENFORCE_CLIENT`, `FC_OAUTH_INTROSPECT_CLIENT_AUTH_ONLY` | DIFFERS (no ownership check, no switches) | S | 2 | L2 |
| F33 | IdP role mapping existence at write; stale mapping skipped at login | DIFFERS | S | 3 | L3 |
| F28 | `SCOPE_REACH_MISMATCH` on service-account create/update | MISSING | S | 4 | L4 |
| F31 | `attemptCount` counts the terminal attempt; scheduled-job sync rollup subject | DIFFERS (both) | S | 5 | L5 |
| F30 | unchanged roles, platform roles, connections, subscriptions not rewritten or counted | DIFFERS (event types already SAME) | M | 6 | L6 |
| F29 | router-config `ETag`/`304`; 30 s poll; paced recheck; nudge | MISSING (poll default DIFFERS: 300 s) | M | 7 | L7 |
| F41 | `FC_OIDC_REJECT_UNVERIFIED_EMAIL` | MISSING | S | 8 | L8 |
| F42 | `FC_OIDC_ISSUER_PATTERN_STRICT` | DIFFERS (unanchored `find()`, no warning) | S | 9 | L9 |
| F39 | `FC_SESSION_COOKIE_SAME_SITE` | DIFFERS (one session builder exists; SameSite hard-coded Lax; no knob) | S | 10 | L10 |
| F32 | application admin routes answer 404 | MISSING (the routes are PENDING A2); land A2 with 404, not 403 | S, with A2 | with A2 | L11 |
| F36 | atomic refresh rotation | SAME (Java ahead: 10 s replay leeway) | - | - | L12 (tests) |
| F37 | atomic TOTP step claim | SAME (no concurrency test) | S (test) | - | L12 (tests) |
| F43 | passkey counter persisted, regression refused | SAME (ownership via user handle; no software-authenticator test) | S (tests) | - | L12 (tests) |
| F44 | return-URL control characters | SAME (Java stricter; no fuzz target) | S (tests) | - | L12 (tests) |
| F38 | test-headers switch refused in production | SAME in effect (gate is `FLOWCATALYST_DEV_MODE`, not `APP_ENV`) | - | - | decision, L13 |
| F45 | dependency bumps | UNVERIFIED (Java has its own dependency set; no scan run) | S (advisory) | last | L14 |
| R1 | JWT clock leeway default 0 | SAME for platform tokens; upstream id_token verifier uses 60 s (DIFFERS, deliberate?) | - | - | L12 / decision |
| R2 | bearer validation cache bound | SAME (no cache exists on the bearer path) | - | - | - |
| R3 | JWKS refetch on unknown `kid` | SAME for the platform's JWKS; UNVERIFIED for the upstream IdP (library default) | - | - | - |
| R4 | password-login timing equalisation | SAME (`PasswordHash.equalizeTiming`) | - | - | - |
| R5 | redirect matcher URL-aware (percent-encoded dots) | SAME (URI-parsed; probe refused the Rust payloads) | S (tests) | - | L12 (tests) |
| R6 | introspect ownership | = F40 | - | - | L2 |

Migrations needed by any in-scope item: **none.** (Java is at `V33`; `V34` stays reserved for A1.) No item changes
the OpenAPI shape except F29 (router-config gains `If-None-Match` and `ETag`), F28/F40/F41 documentation strings, and
the new error codes `SCOPE_REACH_MISMATCH` (400) and `EMAIL_NOT_VERIFIED` (403).

Conventions for the batch: as `PENDING.md` "How to resume" (one commit per logical change on `main`, not pushed; a
ledger with "Was the defect present in Java?", "Changes", "Tests (mutation-checked)", "Deviations from Go",
"Not verified"). Go tests are under `../flowcatalyst-go/internal/...`; those ending `_pg_test.go` need
`-tags=integration` to run there.

### Things in Go that look wrong (found while reading; not fixes to mirror)

1. **F36 makes a legitimate racing client log itself out.** Go's rotation is now atomic, but it kept "a retry of a
   rotated token revokes the family". Two requests of one SDK session that refresh at the same moment (the Laravel and
   TS SDKs hold no lock) now produce one winner and one loser presented as a replay, so the **family is revoked**: the
   user is signed out. Before F36 both succeeded (the fork bug). Java's `RefreshRotation` has the 10 s replay leeway
   (sibling rotation inside the leeway while the family is intact) for exactly this; **do not remove it to match Go**.
   Go's own review recommended the leeway (`identity-go-vs-rust-2026-10-19.md` G1) and F36 declined it.
2. **F39 `FC_SESSION_COOKIE_SAME_SITE=none` does not force `Secure`.** Browsers reject `SameSite=None` without
   `Secure`; Go's `SessionCookie` only passes through the caller's `secure` flag (false when test headers are on).
   Port with a startup refusal of `none` when cookies are not secure.
3. **F29 nudge fires inside the writing transaction.** `PlatformSink.WriteEvent` calls the hook before the commit; the
   watcher waits `NudgeDelay` (1 s) and repeats once, so a transaction longer than about 2 s is only picked up by the
   30 s poll. Harmless, but the comment's "so the writing transaction has committed" is a timing assumption.
4. **F28 counts duplicate `clientIds`.** `derivedTier` uses `len(clientIDs)`; `["a","a"]` is PARTNER in the check. Java
   `Principal.withServiceReach` also counts `ids.size()` without de-duplication, so check and token agree, but a
   de-duplicated link set would give CLIENT. Neither side de-duplicates; mention only.
5. **F40 introspection cannot be ownership-checked.** Access tokens carry no OAuth client claim in Go; the opt-in
   switch only removes the Bearer path. Java's introspect reports `client_id` from the token's `azp`
   (`OAuthIntrospectionApi.java:53`), so Java could enforce ownership for access tokens where Go cannot; this hand-off
   does not ask for it (see L2 option).

---

## 1. F34. `token_use` is an allowlist for API credentials (L1, S, order 1)

**Go** (`8165e614`): `sessiontoken.IsAPICredential(use)` = `use == "" || use == "api"`. Applied in
`middleware.introspect` **before** the cookie/bearer split (so it covers both transports; cookie refusal is silent
anonymous pass-through, bearer refusal is the existing 401 `errIdentityTokenNotAPICredential`), in the function
runner's invoke verifier, and in the `/oauth/authorize` session check (`ValidateSession`). Wire impact: none for any
token the platform issues today (`api` from client_credentials, dashboard paths, API-access clients; `identity` from
interactive authorization_code and portal; no marker on the cookie and legacy tokens).

**Java today: DIFFERS.**
- `platform/shared/auth/Authenticator.java:221-224` `bearer()`: rejects only `TOKEN_USE_IDENTITY`; any other marker
  (`field`, `foo`, `API`) is accepted with whatever scope/permissions the claims carry.
- `Authenticator.java:208-219` `session()`: no `token_use` check at all (the cookie path reads only `subject` and
  re-resolves authority), so a platform-signed access token of **any** class placed in the session cookie authenticates
  as that subject. Go had the same gap and closed it.
- `platform/shared/auth/jwks/BearerAuthenticator.java:88-90` (function-endpoint verifier, Go's `runner/auth.go`
  equivalent): denylist of `identity`.
- `router/api/auth/PlatformTokenFilter.java:73`: already an allowlist, **stricter** than Go (exactly `api`, so it also
  refuses the legacy no-marker token and the cookie). Leave it.
- `/oauth/authorize` session check: Java uses `TokenClaims.isSessionToken()` (`TokenClaims.java:61-63`: no `token_use`,
  no `type`, no `jti`), which is stricter than Go's allowlist; nothing to do.

**Change.**
1. `TokenClaims`: add `public static boolean isApiCredential(String tokenUse)` = `tokenUse == null || tokenUse.isEmpty()
   || TOKEN_USE_API.equals(tokenUse)` (Go treats `""` as absent; `JwtVerifier.java:210` reads the claim through
   `stringClaim`, check whether a blank claim comes back `null` or `""` and keep both admitted). Exact match, case
   sensitive: `"API"` is refused (Go test).
2. `Authenticator.introspect` (or at the top of both `bearer()` and `session()`): refuse when
   `!isApiCredential(claims.tokenUse())`: bearer returns `new Rejected(IDENTITY_TOKEN_REJECTED)` (the existing 401 body,
   `Authenticator.java:66-68`; keep the message verbatim, it is Go's); cookie returns `Rejected` too, which the existing
   `Authenticator.java:152-160` branch already turns into a silent unauthenticated pass-through for a cookie.
3. `BearerAuthenticator.java:88`: replace the `when ... TOKEN_USE_IDENTITY.equals` guard by `when !isApiCredential(...)`,
   message "a non-API token is not an API credential" (any text; Go's runner message is internal).
4. Update the class doc of `Authenticator` (lines 32-34 say "`token_use == "identity"` is rejected") and
   `docs/spec/auth-core.md` where it states the denylist.

**Go tests to port by name.** `middleware.TestAuthenticatorTokenUseIsAnAllowlist` (refused: `field`, `foo`,
`identity`, `API`, `id_token` on bearer **and** cookie; admitted: `api` and none), `middleware.TestAuthenticatorRejectsIdentityBearer`
(unchanged), `sessiontoken.TestIsAPICredential_Allowlist`, `runner.TestCallerFromClaims_TokenUseAllowlist` (port against
`BearerAuthenticator`). Java side: `AuthenticatorTest`, `PlatformTokenFilterTest`, `TestJwks`.

**Migration.** None.

**Risk / default.** Default on (Go shipped it on). Breaks a client only if it presents a token whose `token_use` is
neither `api` nor absent; Java mints only `api`, `identity` and none (`TokenIssuer.java:172,195`,
`RsaServiceAccountTokenMinter.java:71`), so nothing legitimate changes. **UNVERIFIED:** whether any Java-minted
**portal** session cookie carries a marker (`PortalSso`, `portalauth`); `TokenIssuer.sessionToken` (line ~262) mints
the cookie and the `isSessionToken()` doc says it has none, so the cookie path should be unaffected, but run the portal
SSO tests (`PortalSsoTest`) after the change.

## 2. F40 and R6. `/oauth/revoke` ownership, optional client-auth-only introspect (L2, S, order 2)

**Go** (`81a6f588`): RFC 7009 §2.1. `authenticateClientOrBearer` now returns the authenticated OAuth client (nil for a
Bearer caller). In `Revoke`, when a client authenticated by credentials and the stored refresh token has an OAuth
client binding that differs from `client.ClientID`, the token is left alone, a WARN is logged
(`"oauth revoke: token belongs to another client; not revoked"`), and the response is still **200** with no body.
Bearer callers and unbound tokens are unchanged. Switches (both read at startup, `envBool`):
`FC_OAUTH_REVOKE_ENFORCE_CLIENT` default **true** (`false` restores cross-client revoke);
`FC_OAUTH_INTROSPECT_CLIENT_AUTH_ONLY` default **false** (`true` makes `/oauth/introspect` answer
`401 invalid_client "Client authentication required"` to a Bearer-authenticated call).

**Java today: DIFFERS.** `platform/auth/oauth/OAuthIntrospectionApi.java:68-80` `revoke`: after
`authenticateClientOrBearer` (line 87) it calls `s.grants().revokeByHash(RefreshToken.hash(token))` with no ownership
check; `authenticateClientOrBearer` returns only an error (the client is discarded). `introspect` (line 33) takes the
same path with no switch. Java's other refresh surface already enforces the binding (`RefreshRotation.rotate`
`stored.oauthClientId().equals(requestingClientId)`, `RefreshRotation.java:143`), using the OAuth `client_id` string
from `ClientAuthentication.authenticateClient(...).client().clientId()`; reuse the same identity here.

**Change.**
1. `OAuthIntrospectionApi.authenticateClientOrBearer`: return a small result (error or the authenticated
   `OAuthClient`, `null` for a Bearer caller) and accept a `bearerAllowed` flag; with `bearerAllowed=false` and a Bearer
   present answer `OAuthError.invalidClient("Client authentication required")` (401, code `invalid_client`).
2. `revoke`: `if (client != null && enforce) { found = s.grants().findByHash(hash); if (found.isPresent() &&
   found.get().oauthClientId() != null && !found.get().oauthClientId().equals(client.clientId())) { log WARN; ctx.status(200); return; } }`
   then the existing revoke. Keep "no body, no Content-Type" (line 79).
3. `Env`: add `FC_OAUTH_REVOKE_ENFORCE_CLIENT` (bool, default true) and `FC_OAUTH_INTROSPECT_CLIENT_AUTH_ONLY` (bool,
   default false); thread through `OAuthState`. Document in `docs/environment-variables` (Java's equivalent table) and
   `docs/spec/auth-core.md` §6.2 O3/O4.
4. Optional, not in Go: Java could enforce ownership for **access-token** introspection using `azp`; do not do this
   without an owner ruling (it would change what resource servers see).

**Go tests to port by name.** `oauthapi.TestRevokeIsScopedToTheIssuedClient` (another client's revoke answers 200 and
the token stays live; flag off restores the old behaviour), `oauthapi.TestIntrospectClientAuthOnlyRefusesBearer`
(default: a bad Bearer is `invalid_token`; switch on: `invalid_client`). Java side: the existing
`OAuthProviderTest`/`OAuth*ApiTest` for introspect and revoke.

**Migration.** None.

**Risk / default.** Revoke enforcement **default on**, as Go (`FC_OAUTH_REVOKE_ENFORCE_CLIENT=true`): the callers
Go found (fcdev logout, the Rust SDK `revoke_token`) revoke their own tokens. **UNVERIFIED for Java:** which Java
callers revoke with a different client than the one the token was issued to (the Java SDK, `fcdev logout` in this
repo's `fcdev/`, the Laravel SDK bridge); grep those before shipping. Introspect switch **default off** (resource
servers may introspect with a Bearer today).

## 3. F33. Stale IdP role mapping (L3, S, order 3)

**Go** (`281d40d3`): `CreateIdpRoleMapping` resolves the platform role and refuses an unknown one, **`400
ROLE_NOT_FOUND`**, in `Execute`, **before** the duplicate check (so an unknown role beats `MAPPING_EXISTS`); shape
validation (`INVALID_ROLE_NAME`, F18) stays in `Validate`. There is no mapping update operation, so create is the only
write. At login, `LoginEndpoint.syncIdpRoles` resolves each authorised platform role (`role.ResolveName`) and **skips**
one that fails with a validation-kind error, logging WARN `"skipped IDP role mapping: its platform role does not exist
(stale mapping)"` with principal, role and code; the valid roles are granted and removals still happen. A non-validation
(storage) error still fails the sync. `SyncIdpRoles` itself is unchanged and strict.

**Java today: DIFFERS** (the pre-F33 Go behaviour).
- Write: `platform/authadmin/operations/CreateIdpRoleMapping.java:38` shape-checks only; the comment at 36-37 says
  "existence is checked when the mapping is applied at login". Line 46 `RoleCeiling.requireRoles` treats an unknown
  role as having no permissions (`RoleCeiling.RolePermissions.from`: `.orElse(List.of())`, `RoleCeiling.java` doc "A
  role name that does not exist is not this rule's business"), so an unknown role passes the ceiling.
- Login: `platform/auth/oidc/OidcBridgeApi.java:600-621` `syncIdpRoles` passes every mapped role to
  `SyncIdpRoles`, whose `RoleName.resolveAll` (`principal/operations/SyncIdpRoles.java`, `RoleName.java:91`) throws
  `400 ROLE_NOT_FOUND` on the first missing one; `OidcBridgeApi.java:614-620` catches `RuntimeException` and logs "idp
  role sync failed", so one stale mapping drops the whole sync (other valid roles not granted, lost roles not removed)
  for every user of the provider. Login itself is not failed.

**Change.**
1. `CreateIdpRoleMapping.of(repo, rolePermissions)` gains the `RoleRepository` (wired in `AuthAdminConfigApi.java:182`,
   `s.rolePermissions()` is built from it already); in `execute`, first statement:
   `RoleName.resolve(roles, cmd.platformRoleName())` (400 `ROLE_NOT_FOUND`, `RoleName.java:77-82`). Update the comment.
2. `OidcBridgeApi.mapClaimRoles` or `syncIdpRoles`: after the allow-list filter, for each platform role call
   `RoleName.resolve(s.roles(), r)`; on a `UseCaseException` of validation kind skip it with
   `LOG.atWarn().setMessage("skipped IDP role mapping: its platform role does not exist (stale mapping)")` plus
   `principal`, `platformRole`, `code`; rethrow anything else. Then call `SyncIdpRoles` with the survivors.
3. Existing stale rows are not touched (Go): they are skipped at login; an operator deletes them.
4. Java's ceiling stays where it is (Java is **ahead of Go** here: Go has no mapping ceiling, `PENDING.md` "Where Java
   is ahead"); the existence check goes before the ceiling so a missing role is `ROLE_NOT_FOUND`, not a ceiling
   verdict on an empty permission list.

**Go tests to port.** `bridge.TestOidcLogin_StaleMappingIsSkippedNotFatalToTheRoleSync` (a real callback; one valid, one
stale mapping; login completes; the valid role is granted) and `operations.TestCreateIdpRoleMapping_RefusesAnUnknownPlatformRole`.
Java side: `AuthAdminConfigOperationsTest` lines 387-474 create mappings to `app:role` through a stub
(`name -> List.of()`); they now need the role to exist (Go made the same change), and the OIDC bridge tests.

**Migration.** None. **Risk.** Default on. API clients that create a mapping to a role that does not exist yet now get
400 instead of 201: create the role first.

## 4. F28. `SCOPE_REACH_MISMATCH` on service accounts (L4, S, order 4)

**Go** (`ebb18542`): the stored `scope` is descriptive; the token tier follows the client links (none = ANCHOR, one =
CLIENT, several = PARTNER). Create, create-with-credentials and update refuse **`400 SCOPE_REACH_MISMATCH`** when the
effective stored scope (trimmed, upper-cased; only ANCHOR/CLIENT/PARTNER count, anything else or unset is left alone)
differs from the tier the effective links give. Message: `scope <WANT> does not match the account's client links (<n>
linked: tokens are ANCHOR with no clients, CLIENT with one, PARTNER with several, so these links give <GOT>). Change
clientIds and scope together, or omit scope`. On update the check runs only when the request carries `scope` or
`clientIds`, over the **merged** result, so renaming a legacy inconsistent account works and
`{"scope":"ANCHOR","clientIds":[]}` is the one-call fix. A refused call stores nothing. Existing data is not migrated.
Decision recorded in Go: refuse, never widen the token to follow the field.

**Java today: MISSING.** `platform/serviceaccount/ServiceAccount.java:31-32` documents `scope` as "optional
free-form scope tag ... never validated"; `UpdateServiceAccount.java:45-47` and
`CreateServiceAccountWithCredentials.java:101-104` store whatever `scope` and `clientIds` arrive. The tier derivation
is already the same rule (`Principal.withServiceReach`, `principal/Principal.java:374-383`: empty -> ANCHOR, one ->
`changeClient`, else PARTNER), so the same mismatch exists: `PUT scope=ANCHOR` beside `clientIds` is echoed while the
token stays CLIENT/PARTNER. Java has no separate plain create (only `CreateServiceAccountWithCredentials`).

**Change.** A static helper beside the operations, e.g. `serviceaccount/operations/ClientReach.requireScopeMatchesReach(String scope, List<String> clientIds)`
(same trimming, upper-casing, three tiers, same message), called:
- in `CreateServiceAccountWithCredentials.execute` after `sa` is built (line ~101-104), over `sa.scope()` / `sa.clientIds()`;
- in `UpdateServiceAccount.execute` after `Access.byId(...).update(...)` computes the merged account, only
  `if (cmd.scope() != null || cmd.clientIds() != null)`, over the merged `sa.scope()` / `sa.clientIds()`
  (throw before `scoped.commit`).
Use `UseCaseException.validation("SCOPE_REACH_MISMATCH", ...)`. Update `docs/spec/service-account-reach.md` and the
`scope`/`clientIds` descriptions in the OpenAPI (Go changed the descriptions only, `api/openapi.lock.json` +3 lines).
SPA: Go's `ServiceAccountDetailDrawer.vue` now sends `clientIds: []` for ANCHOR (it hid the picker but still sent the
old links) and shows the tier the links give instead of "ANCHOR" for an account with no stored scope; take it with the
next SPA sync (`2026-09-14-frontend-source-shared.md`), by hand if the drawers have diverged.

**Go tests to port.** `operations.TestServiceAccountScope_MustMatchReach` and its eight sub-tests (create ANCHOR with
links refused and nothing stored; create CLIENT with no links refused; matching scopes accepted case-insensitively and
the token tier is that scope; update ANCHOR while links remain refused with stored scope and principal tier unchanged;
scope and links together `ANCHOR` + none accepted and the principal becomes ANCHOR; adding links to a stored-ANCHOR
account refused; a rename does not re-check; both together to CLIENT with one link). Go also changed
`TestCreateServiceAccount_HappyPath` (it used `anchor` beside one client). Java side: `ServiceAccountApiTest` /
`ServiceAccountOperationsTest` (find the ones that send `scope` with `clientIds`).

**Migration.** None. **Risk.** Default on (Go). Breaks API clients and the SPA that send an inconsistent pair; the
SDKs' provisioning paths (`ProvisionServiceAccount`, `SyncPrincipals`) do not set `scope` (**UNVERIFIED** for
`application` provisioning paths; grep `.withScope(`).

## 5. F31. `attemptCount` counts the terminal attempt; scheduled-job sync rollup subject (L5, S, order 5)

**Go** (`4f2b3908`).
(a) `Lifecycle.Complete` and `Lifecycle.Fail` bump `attempt_count` in the same statement that settles the job
(`attempt_count = j.attempt_count + 1`), so `attemptCount` equals attempts made: first-try success 1, one retry then
success 2, three tries then failure 3; the credentials-refused fast fail (401/403) also counts. Deferrals (ack=false,
429), holds and stale recoveries are not attempts. `Requeue`/`ResetToPending` still zero it; retry arithmetic
(`attemptNumber = attempt_count + 1`) unchanged. Existing rows keep their old counts (not migrated).
(b) The scheduled-job sync rollup subject is `platform.scheduledjobs.<applicationCode>` (was
`platform.scheduledjobs.synced.<applicationCode>`), so its audit row reads entity type `Scheduledjobs`, entity id the
application code (was the literal `synced`). Audit history is not rewritten.

**Java today: DIFFERS** on both.
(a) `platform/dispatchjob/DispatchJobLifecycle.java:533-543` `markCompleted`/`markFailed` set only completion/error
columns; `scheduleRetry` (line 549-554) sets `attempt_count = attemptNumber` absolutely. So Java reads 0 on a first-try
success and one less than the attempt rows on a terminal failure. `processing/ProcessingApi.java:357-359` carries a
comment "attempt_count is left as the ordinary Failed path would leave it (not bumped by markFailed) — a requeue does not
reset it, so bumping here would make a requeued job fail immediately". **That comment is stale/wrong:** Java's requeue
writer resets it (`DispatchJobLifecycle.java:618-620` doc and `requeue(...)` at ~678:
`Changes.of("attempt_count = 0, ...")`). Re-check every path that returns a job to PENDING for a retry budget
(requeue writer, `requeue`, any redrive) before relying on this; if one does not zero it, the terminal bump would make
that job fail early.
(b) `platform/scheduledjob/operations/ScheduledJobEvents.java:47-49` `syncSubjectFor` builds
`buildSubject("platform","scheduledjobs","synced."+applicationCode)`, used at line 199. The audit entity type/id are
derived from the subject by `EventConventions` (`extractAggregateType`/`extractEntityId`, used by `OutboxSink.java:112-113`);
**UNVERIFIED** that the platform's own audit writer uses the same helpers (read `PlatformSink.writeAudit`,
`PlatformSink.java:86`), so confirm the resulting audit row after the change.

**Change.**
(a) `markCompleted`: `Changes.of("completed_at = ?, duration_millis = ?, attempt_count = attempt_count + 1", ...)`;
`markFailed`: `"last_error = ?, attempt_count = attempt_count + 1"`. (The existing writers use bare column names in
`SET`, e.g. `"attempt_count = 0, ..."` at ~678, so no `j.` alias is needed; confirm against the `Transition`
statement builder.) Delete/rewrite the stale comment in `ProcessingApi`. `DeliveryPayload`'s
`attemptCount() + 1` envelope number is unaffected (read before the claim).
(b) `syncSubjectFor` -> `buildSubject("platform","scheduledjobs", applicationCode)`; keep `SYNC_MESSAGE_GROUP`
(`platform:scheduledjobs:<code>`, line 204-210) as is. The published event's `subject` changes with it: subscribers
filtering on `platform.scheduledjobs.synced.*` break (event type `platform:admin:scheduledjobs:synced` is unchanged).

**Go tests to port.** `processing.TestProcess_AttemptCountCountsTheTerminalAttempt` (first try 1 and equal to the
attempt rows; one retry then delivered 2; exhausted 2 of 2), `operations.TestSyncScheduledJobs_AuditEntityTypesAreConsistent`
(per job `Scheduledjob`, per sync one `Scheduledjobs` keyed by the application code). Go changed
`TestProcess_UnauthorizedFailsFast`, `TestProcess_ConcurrentDeliveriesCallSubscriberOnce`,
`TestLifecycleCallbackOutcomeColumns`; the Java equivalents are in `ProcessingApiTest`, `DispatchJobLifecycleTest`,
`DispatchJobRepositoryTest`, `DispatchJobOperationsTest`, and `ScheduledJobOperationsTest.java:453` pins the old
subject (`"platform.scheduledjobs.synced.sjsyncapp"`) and must change.

**Migration.** None. **Risk.** Default on (Go). Visible: `attemptCount` on settled jobs is one higher; console
"Attempts: n / max" now reads the attempts made. Event subject change in (b) is a wire change for subscribers.

## 6. F30. Unchanged syncs write nothing (L6, M, order 6)

**Go** (`87cbc30f`): each sync compares the definition with the stored row **before** writing; an equal row is not
saved, not counted in `updated`, gets no `*Updated` event and no `updated_at` bump. Response shape unchanged
(`created`, `updated`, `deleted`, `syncedCodes`; counts now mean "rows written"). Helpers `syncdiff.SameText` (nil
equals nil only) and `SameSet` (order and duplicates ignored). Compared fields:

| Sync | Compared |
|---|---|
| `SyncRoles` (SDK) | display name, description, `clientManaged`, permissions as a set **only when the definition lists some** (omitted still means keep the stored ones); an adopted id-less role (F18) always counts (its application id needs writing) |
| `SyncPlatformRoles` | display name, description, permissions as a set; a healed missing application id counts |
| `SyncConnections` | name (trimmed), description, external id, service account |
| `SyncSubscriptions` | name, description, target, connection, data-only, max retries and timeout (only when sent), dispatch pool link (id and code), event-type binding **codes** as a set |

A differing row still updates and counts. The subscription comparison ignores binding filters because they are not
persisted (Go finding; open there as an owner/design question). `syncedCodes`/the synced-name list must still list an
unchanged definition (so `removeUnlisted` does not delete it).

**Java today: DIFFERS**, except event types.
- `platform/eventtype/operations/SyncEventTypes.java:33-34,72-78`: **SAME** already (bug 6: unchanged neither saved nor counted).
- `role/operations/SyncRoles.java:103-116`: every existing SDK row is rebuilt via `existing.syncedFromSdk(...)`
  (`Role.java:246-250`, which already keeps stored permissions on an empty list) and saved with a `RoleUpdated`,
  `updated++`, unconditionally. The adopted-id-less path (lines 87-97) puts the adopted row in `existingByName`, so it
  takes the same branch.
- `role/operations/SyncPlatformRoles.java:82-92`: every `CODE` row is rebuilt and saved (`updated++`).
- `connection/operations/SyncConnections.java:114-120`: `existing.withName(...).withDescription(...).withExternalId(...).withServiceAccountId(...)` saved and counted every run (no name trim in Java: compare `in.name()` as written).
- `subscription/operations/SyncSubscriptions.java:85-87`, `applyInput` (line ~215): saved and counted every run.
- Binding filters: **SAME as Go** — `EventTypeBinding.filter` is "carried in memory, not persisted" (`EventTypeBinding.java:13`).
- `RoleCeiling` inputs: an unchanged role contributes `RoleCeiling.changed(existing, same)` = empty, so skipping changes
  no ceiling outcome.

**Change.** In each loop, compute the candidate (as today) and `continue` before `saves.add`/`updated++` when it equals
the stored row on the compared fields; keep the `syncedNames`/`syncedCodes`/`syncedSet` additions before the skip.
Add small `SyncDiff.sameText(String,String)` (`Objects.equals`) and `sameSet(Collection,Collection)` helpers (e.g. in
`platform/shared/`). For roles: `adopted` set of adopted names so those never skip; for permissions compare only when
`!in.permissions().isEmpty()`. For subscriptions compare the **applied copy** against the stored one (name,
description, endpoint, connection id, data-only, max retries, timeout, dispatch pool id and code, binding codes as a
set), exactly Go's `subscriptionChanged`. Note Java's `applyInput` re-resolves the pool and clears `connectionId`
when absent; compare after applying so that is honoured.

**Go tests to port.** `role/operations.TestSyncRoles_UnchangedRolesAreNotUpdated` (same, reordered permissions, omitted
permissions are unchanged; each of display name, description, permissions, managed updates; `updated_at` untouched),
`TestSyncPlatformRoles_UnchangedRolesAreNotUpdated` (the real catalogue, then one drifted role),
`connection/operations.TestSyncConnections_UnchangedConnectionsAreNotUpdated`,
`subscription/operations.TestSyncSubscriptions_UnchangedSubscriptionsAreNotUpdated` (each managed field changes
exactly one row). Go changed three existing tests that asserted the old count (`TestSyncConnections_CreateIdempotentUpdate`,
`TestSyncRoles_UpsertPreserveAndRemoveUnlisted`, `TestSyncPlatformRoles_CatalogueLifecycle`); the Java equivalents are
in the role/connection/subscription operation tests and `SdkSync*` API tests.

**Migration.** None. **Risk.** Default on (Go). SDKs only pass the counts through; a client asserting `updated == n`
on an idempotent re-sync breaks (the Go TS and Laravel SDK specs were left alone). Not examined in Go either:
dispatch pools, processes, principals syncs (and not here).

## 7. F29. Router picks up a new client's queue in seconds (L7, M, order 7)

**Go** (`0fb7f0c7`), three parts.
1. *Conditional document.* `GET /api/dispatch/router-config` returns an `ETag` (strong, `"` + hex of the first 16
   bytes of SHA-256 of the rendered JSON + `"`; the document is deterministic) and `304` to `If-None-Match` (a comma
   list, weak `W/` validators and `*` understood). The document is built either way. The router's `ConfigSource` keeps
   each URL's validator beside its cached document, sends `If-None-Match` only when it holds both, and on `304` reuses
   the cached document (change detection, last-known-good and stale warnings unchanged); a source with no `ETag` (a
   third-party config service) is fetched in full as before.
2. *Interval 300 s -> 30 s* (`defaultConfigPollInterval`; `FC_ROUTER_CONFIG_INTERVAL_SECONDS` /
   `FLOWCATALYST_CONFIG_INTERVAL` still override). Paced missing-queue recheck: a queue missing less than 10 minutes is
   probed on every tick, every missing queue once per 5 minutes (the old cadence for all); implemented with a
   first-missing timestamp per queue.
3. *Nudge for an in-process router.* A platform write of a client, dispatch pool or subscription event
   (`platform:admin:client:`, `...:dispatch-pool:`, `...:dispatch-pools:`, `...:subscription:` prefixes) calls a hook
   (set when the router runs in the platform's process) that nudges the config watcher; the watcher waits `NudgeDelay`
   (1 s), fetches, waits again and fetches once more to confirm; nudges coalesce. A separate router relies on 1 and 2.

**Java today: MISSING** (default DIFFERS).
- `platform/dispatch/api/RouterConfigApi.java:87` always `ctx.json(builder.build())`: no `ETag`, no `If-None-Match`.
- `router/config/http/HttpConfigSource.java` (request at ~448): no validator cache; its class doc says it "never
  suppresses an unchanged answer" on purpose (a regained leadership needs the config re-applied), so the 304 must hand
  back the **cached document** rather than an empty result.
- `router/manager/RouterServer.java:65` `CONFIG_POLL_INTERVAL_DEFAULT = Duration.ofMinutes(5)`; the poll is the
  housekeeping task at `server/Router.java:334-336`. Env `FC_ROUTER_CONFIG_INTERVAL_SECONDS`/`FLOWCATALYST_CONFIG_INTERVAL`
  at `server/Env.java:564`, parsed by `RouterServer.parseConfigPollInterval` (line 75; its warning text names the 300 s
  default and a test pins it).
- Missing-queue recheck: `router/manager/RouterManager.java:969` `toBuild` includes every wanted queue without a
  consumer, so **every apply re-probes every missing queue**. At a 30 s tick that is every unused tenant queue every
  30 s, the cost Go's pacing exists to avoid. The missing-streak set is `missingQueues` (line 151).
- No nudge hook: `platform/shared/platformsink/PlatformSink.java:61` `writeEvent` has none. Java's `Server` can host
  platform and router in one process (`Server.java:388` `env.routerEnabled() ? Router.build(...)`), so the in-process
  nudge applies to that deployment; fcdev runs that way.

**Change.**
1. `RouterConfigApi`: compute the ETag from the serialized body (same recipe: quoted lowercase hex of the first 16
   bytes of SHA-256 over the JSON written to the wire; serialize once, hash those bytes), set `ETag`, answer `304` with
   no body on a match (same `etagMatches` rules). Add `If-None-Match` and the `ETag` response header to the OpenAPI
   (Go changed `api/openapi.lock.json` +20/-... for this operation and its generated types only).
2. `HttpConfigSource`: per-URL `etag` and cached parsed config maps; send `If-None-Match` only when both exist; on 304
   return the cached config; store/clear the validator from the response `ETag` as Go does.
3. `RouterServer.CONFIG_POLL_INTERVAL_DEFAULT` -> `Duration.ofSeconds(30)`; fix `parseConfigPollInterval`'s warning
   text and the tests that pin 300 (`RouterServerTest`, `EnvTest`); update `docs/spec/router-env.md`,
   `docs/deployments.md`. **Owner decision (Go's too): a deployment that sets the interval explicitly keeps its value;
   check the IaC for `FLOWCATALYST_CONFIG_INTERVAL`.**
4. `RouterManager`: first-missing timestamp per queue; the config-poll recheck probes young (< 10 min) missing queues
   every tick and all of them once per 5 min; manual `/config/reload` (R-33) probes all. Keep the one-INFO-per-streak
   rule.
5. Nudge: `PlatformSink.writeEvent` calls a static, settable hook (`Runnable`, null by default) when the event type
   starts with `platform:admin:client:`, `platform:admin:dispatch-pool:`, `platform:admin:dispatch-pools:` or
   `platform:admin:subscription:` (the same four families exist in Java's `PlatformEventTypes.java:109-125`).
   `Server` sets the hook to a `RouterServer.nudgeConfig()` that schedules one `pollConfiguration()` after 1 s and a
   confirming one after another 1 s on the housekeeping/scheduler thread, coalescing calls made while one is pending,
   and no-oping when this instance is not the leader. It must only signal (it runs inside the writing transaction).

**Go tests to port.** `router.TestFetch_ConditionalRequestsAnswer304AsUnchanged`,
`TestFetch_SourceWithoutETagIsFetchedInFull`, `TestWatch_NewQueuePickupDelay`, `TestWatch_NudgesAreCoalesced`,
`TestRecheckMissingQueues_YoungEveryTickOldOnTheSlowCadence` (`internal/router/config_refresh_test.go`);
`dispatch.TestRouterConfigEndpoint_ConditionalGet` (200 + ETag, 304, list and weak validators, a new client changes the
ETag); `client/operations.TestCreateClient_NudgesTheRouterConfig`; `platformsink.TestAffectsRouterConfig`. Go changed
`TestNewServerAppliesNotifyBatchIntervalFromConfig` (pinned the 300 s default). Java side: `RouterServerTest`
(config-poll), an `HttpConfigSource` test with a fake server, `RouterConfigApiTest`.

**Migration.** None. **Risk.** The 30 s poll and the 304 are safe with the validator in place. The unconditional
re-probe of missing queues must be paced **before** the interval drops, or the broker sees every unused tenant queue
every 30 s. Default on for all three parts (Go).

## 8. F32. Application admin routes answer 404 (L11, with A2)

**Go** (`49ba1494`): for the F20 routes under `/api/applications/{appCode}/` (users, invitations) another application's
code, an unbound credential and (for application-scoped callers) an unknown code all answer
`404 Application_NOT_FOUND "Application not found: <code>"`, whoever asks, so a credential cannot enumerate which
applications exist; missing permission is still 403, checked **before** the application is resolved. The SDK sync
routes (`/api/applications/{appCode}/*/sync`, `requireAppAccess`) keep their 403.

**Java today: MISSING.** The routes do not exist (`PENDING.md` A2: `404` for a different reason). No
`/api/applications/{appCode}/users` or `.../invitations` registration exists (grep of `/api/applications/{` finds only
`ApplicationApi` id routes and `SyncPrincipals`); `Application_NOT_FOUND` exists for the sync routes
(`application/operations/Access.java:18`).

**Change.** Not separate work: **amend PENDING A2** so the three cases answer `404 Application_NOT_FOUND`, not
`403 "Not authorised for application '<code>'"` (A2's text in `PENDING.md` still says 403; Go changed it after A2 was
written). The Java SDK copy of `ApplicationAdminResourcesTest.anotherApplicationIsA403` (Go `c2591ddd` changed the
Go-repo copy to 404) is not in this repo (**UNVERIFIED**: grep found no such test under `flowcatalyst-javalin`; the
SDK sources live in the Go repo's `clients/java-sdk`).

**Go tests.** `appusers.TestAppUsers_ApplicationBinding` (six routes x other application, unbound credential, unknown
code as scoped and as all-applications caller: all `Application_NOT_FOUND`, nothing changed), `TestAppUsers_Invite`,
`TestApplicationAdminPagesEndToEnd` over HTTP (404 body); all failed before the change. **Migration.** None (A1's
`V34` stands). **Risk.** None for Java (new routes).

## 9. F36. Atomic refresh rotation (SAME, Java ahead)

**Go** (`377fe770`): one guarded `UPDATE ... WHERE consumed_at IS NULL AND not revoked AND not expired` that revokes and
writes `replacedBy`, plus the replacement insert, in one transaction; exactly one presentation wins; the others affect
zero rows and are replays (family revoked). No replay leeway (see "Things in Go that look wrong" 1).

**Java today: SAME, with the leeway Go declined.** `platform/auth/grant/RefreshRotation.java:148-161` consumes with
`tx.consumeValidByHash(hash)` (`GrantStore.java:231`, `UPDATE ... WHERE consumed_at IS NULL ... RETURNING`), inserts the
replacement and `markReplaced` in one `store.inTransaction`, and `REPLAY_LEEWAY` (10 s, line 113) rotates a sibling
inside the leeway while the family is intact, otherwise revokes the family. `RefreshRotationTest.twoConcurrentPresentationsRotateExactlyOnce`
(line 188) already exists. **No change; do not remove the leeway.** Test to port for completeness:
`grantstore.TestRotate_ConcurrentPresentationsExactlyOneWins` is covered by the Java test above.

## 10. F37. Atomic TOTP step claim (SAME; add the concurrency test)

**Go** (`d2827d89`): `Repository.ClaimTOTPStep` is `UPDATE ... SET last_used_at = $step WHERE id = $1 AND
(last_used_at IS NULL OR last_used_at < $step)`; `VerifyTOTP` passes only if that call claimed the step; the earlier
read stays as a fast path.

**Java today: SAME.** `platform/auth/mfa/Mfa.java:227-235` keeps the read fast path then
`repo.advanceLastUsed(...)`; `MfaRepository.java:91-96` is the identical guarded update (`LAST_USED_AT IS NULL OR <`),
the loser is `TotpCheck.Replayed`. **Gap:** `MfaServiceTest` has `aReplayedCodeIsToldApartFromAWrongOne` (line 96) and
the enrolment-replay test (line 113) but no concurrent test. **Port** `mfa.TestVerifyTOTP_ConcurrentReplayExactlyOnePasses`
(12 concurrent submissions of one code, exactly one passes) as a Java test; no production change. Migration: none.

## 11. F38. Test-headers switch (SAME in effect)

**Go** (`65e6787f`): `fc-server` refuses to start when `FC_AUTH_ALLOW_TEST_HEADERS=true` unless `APP_ENV` is `local` or
`test` (case-insensitive, trimmed; `EnvCfg.CheckTestHeadersAllowed`). fcdev sets the flag in code and bypasses the
check.

**Java today: SAME in effect, different gate.** `server/Main.java:34-52,227-229`: `mustRefuseTestHeaders(env)` =
`env.authAllowTestHeaders() && !env.routerDevMode()` (`FLOWCATALYST_DEV_MODE`, `Env.java:565`), logs an ERROR and
`System.exit(1)` before anything else; fcdev goes through `StartCommand` (`fcdev/.../StartCommand.java:425`), not
`Main`. Tests: `MainTest`, `EnvTest`. **No change required.** Decision for the owner (not a defect): Go keys the gate
on `APP_ENV`, Java on `FLOWCATALYST_DEV_MODE`; an operator following Go's docs ("also set `APP_ENV=local`") gets a
refusal on Java. Either document the difference in the Java env table or additionally honour `APP_ENV in {local,test}`
(L13). Note `FLOWCATALYST_DEV_MODE` also drives router dev behaviour (`Env.routerDevMode`), so reusing it as the
auth-bypass gate couples the two.

## 12. F39. Session-cookie construction and `FC_SESSION_COOKIE_SAME_SITE` (L10, S, order 10)

**Go** (`53031d69`): `platformmw.NewCookie`/`SessionCookie`/`ClearSessionCookie` build the `fc_session` set/clear
(7 sites), the trusted-device cookie, the SSO binding cookie and the UI flash cookie; attributes unchanged (SameSite
Lax, **Strict** for the trusted-device cookie; HttpOnly always; Path `/`; Secure from the caller; MaxAge/Expires as
before). New `FC_SESSION_COOKIE_SAME_SITE`: `lax` (default), `strict`, `none`; case-insensitive and trimmed; an
unknown value refuses startup (`FC_SESSION_COOKIE_SAME_SITE: "<v>" is not one of lax, strict, none`); applies to
`fc_session` only (set and clear carry the same SameSite so the browser matches the original); the trusted-device
cookie stays Strict.

**Java today: DIFFERS (partly SAME).** The structural defect (11 inline builders) does not exist: one
`platform/auth/login/SessionCookie.java` builds the session cookie for every login surface (`Platform.java:313,341,366`
wire the same class), `TrustedDeviceCookie.java:32,36` (Strict) and `LoginBinding.java:53,65` have their own. But
`SessionCookie.java:63` hard-codes `HttpCookie.SameSite.LAX`, and `clear()` (line 72-79) writes the header **by hand**
with `SameSite=Lax` literals (and the legacy `fc_session` clear in secure mode), so a knob has to change both. There
is no env switch. The seam already supports it (`http/HttpCookie.java` `SameSite {STRICT, LAX, NONE}`).

**Change.** `Env`: `FC_SESSION_COOKIE_SAME_SITE` (default `lax`; refuse startup on other values); `SessionCookie`
takes the mode; `set` uses it; `clear()` renders the same mode text (`Lax|Strict|None`). Do not touch the
trusted-device or binding cookies. Reject `none` when cookies are not secure (the Go gap, "Things in Go that look
wrong" 2). Update `docs/spec/cookie-hardening.md` §3.

**Go tests to port.** `middleware.TestSessionCookieAttributes`, `TestSessionSameSiteKnob` (`""`, `lax`, `Strict`,
`\tnone` accepted; `bogus` refused; set and clear agree), `TestNewCookieDefaultsAndSessionCookie`,
`login.TestTrustedDeviceCookie...` (attribute pinning), `bridge/cookie_attrs_test.go`. Java side: a `SessionCookie`
unit test for set/clear attributes under each mode, `EnvTest`. **Migration.** None. **Risk.** Default `lax` = no change.

## 13. F41. `FC_OIDC_REJECT_UNVERIFIED_EMAIL` (L8, S, order 8)

**Go** (`dabca1d8`): `FC_OIDC_REJECT_UNVERIFIED_EMAIL` default **false**. When true, an SSO callback (employee and
portal) whose id_token carries `email_verified` explicitly false — the JSON boolean `false` or the string `"false"`
(any case, trimmed; Cognito sends strings) — is refused with **`403 EMAIL_NOT_VERIFIED`** ("the identity provider
reports this email address as unverified") and a failed login attempt recorded with reason `"SSO: email not verified by
the IdP"`. Absent, `null`, `true`, `"true"` and any other value are accepted. The check sits after the identifier is
known and before the `#ext#` guest check.

**Java today: MISSING.** `auth/oidc/IdTokenClaims.java` does not read `email_verified` (it keeps the full claim set in
`rawClaims`, so the value is available); `OidcBridgeApi.java:322-328` goes from the lower-cased email straight to the
`#ext#` check. (Java's own *issued* ID tokens always say `email_verified: true`, `TokenIssuer.java:204,233`; that is
the other direction and unchanged.)

**Change.** `Env` flag; in `OidcBridgeApi` callback after `email` is computed (line ~322) and before the `#ext#`
check: if enabled and `claimExplicitlyFalse(claims.rawClaims().get("email_verified"))`, write
`HttpError.write(ctx, 403, "EMAIL_NOT_VERIFIED", "the identity provider reports this email address as unverified", Map.of())`
and `record(s, ctx, state, AttemptOutcome.FAILURE, email, null, "SSO: email not verified by the IdP")`, return. The
portal branch (`state.portal()`, line 351) runs after, so one check covers both.

**Go tests to port.** `bridge.TestOidcLogin_EmailVerifiedPolicy` (six cases: default accepts false; enforced refuses
`false` and `"false"`; enforced accepts absent, `true`, `"true"`; refused is 403 `EMAIL_NOT_VERIFIED` and no session),
`bridge.TestClaimExplicitlyFalse`. Java side: the OIDC bridge API tests. **Migration.** None.
**Risk.** Default **off**, exactly as Go (some IdPs send false for accounts the tenant trusts).

## 14. F42. `FC_OIDC_ISSUER_PATTERN_STRICT` (L9, S, order 9)

**Go** (`dabca1d8`): the multi-tenant `oidcIssuerPattern` was an unanchored regex search. `FC_OIDC_ISSUER_PATTERN_STRICT`
default **false** keeps the partial match but logs one WARN per unanchored pattern
(`"OIDC issuer pattern is not anchored with ^...$; any issuer that merely CONTAINS a match is accepted. Anchor it or set
FC_OIDC_ISSUER_PATTERN_STRICT=true"`; "anchored" = starts with `^` and ends with `$` not preceded by `\`). `true` makes
it a full match (`^(?:pattern)$`); an invalid pattern never matches. Exact equality with the configured issuer is
checked first in both modes; single-tenant providers never use the pattern.

**Java today: DIFFERS.** `auth/oidc/OidcProvider.java:278-291` `issuerMatches`: exact equality first, then
`Pattern.compile(p).matcher(iss).find()` (unanchored search, javadoc says "unanchored unless it anchors itself"), a bad
pattern returns false, no warning. The configuration is `OidcProvider.Config(..., issuerPattern)` built in
`OidcClients.java:190`.

**Change.** `Env` flag, threaded into `OidcProvider.Config` (or a static settable like Go, but prefer config). Strict:
`Pattern.compile("(?:" + p + ")").matcher(iss).matches()`. Lax: unchanged `find()` plus a once-per-pattern WARN (a
`ConcurrentHashMap.newKeySet()` of warned patterns) when not anchored. Java regex syntax differs from Go RE2 for exotic
patterns; the common Entra/Google/Keycloak forms are identical.

**Go tests to port.** `bridge.TestIsValidIssuer` (legit forms in both modes: exact IdP issuer, anchored and unanchored
patterns matching the whole issuer, local http issuer, Google issuer; near-misses `https://evil.example/?next=<tenant>`,
`<tenant>/extra`, `x<tenant>`, `https://accounts.google.com.evil.example`, `https://evil.example/https://accounts.google.com`
accepted in lax only where the pattern is unanchored, always refused in strict; anchored patterns reject near-misses in
both; single-tenant ignores the pattern). Java side: `OidcProviderTest`. **Migration.** None. **Risk.** Default
**off**, as Go: a deployed pattern written without `^...$` would stop matching if strict were the default.

## 15. F43. Passkey counter (SAME)

**Go** (`26b78d4f`): `authenticateComplete` ran the assertion operation without the persisted credential id, so the
sign counter, backup flags and `last_used_at` were never stored, and `CloneWarning` was never read. Now
`persistAssertion` looks the credential up by authenticator id, requires it to belong to the signing principal, and
persists; a `CloneWarning` assertion is refused (`INVALID_CREDENTIALS`, failed attempt recorded
`"Passkey signature counter regression"`). Authenticators that always report counter 0 never trip it.

**Java today: SAME.** `passkey/api/PasskeyApi.java:275-284` refuses `!asserted.counterValid()` (ruling I-Q13) with
`invalidCredentials` and a recorded failed attempt; `AuthenticatePasskey` (`operations/AuthenticatePasskey.java:33`)
persists `signCount`, user-verified and backed-up flags and `lastUsedAt` through `Passkey.authenticated` (`Passkey.java:45`).
Ownership: the Yubico library's `StoreAdapter.lookup` filters by the request's user handle
(`PasskeyService.java:285-289`), so a credential of another principal cannot satisfy the ceremony. No change.
**Gap:** Go added a software-authenticator test rig (`webauthntest/softauth.go`) and `persist_pg_test.go`; Java's
passkey tests should cover: counter stored after a login, regression refused, another principal's credential refused,
`DeleteCredential` only by the owner (`TestPersistAssertion_StoresCounterAndLastUsed`,
`TestPersistAssertion_RefusesAnotherPrincipalsCredential`, `TestDeleteCredential_OnlyOwnerMayRevoke`,
`TestCeremonyState_SingleUse`, `TestCeremonyState_TypesAreIsolated`, `TestCeremonyState_Expired`). Whether each already
exists in `server/src/test/.../passkey/` was **not read** (UNVERIFIED).

## 16. F44. Return-URL control characters (SAME, Java stricter)

**Go** (`a24f549b`): `safeRelativeReturnURL` (SSO callback and password reset) refuses any byte `< 0x20` or `0x7f`, so
`/<TAB>/evil.com` (read by a browser as `//evil.com`) is no longer an open redirect. Fuzz targets added for the
redirect-URI matcher, session-token verify, return URL, bearer extraction, id_token hint parsing, backoff delay.

**Java today: SAME, stricter.** `platform/shared/RelativeRedirect.java:33-52` is the one rule for the OIDC bridge
`return_url` (`OidcBridgeApi.java:669`) and the password-reset/setup redirects (`PasswordResetApi.java:326`): refuses
non-`/` start, `//`, scheme, authority, backslash (raw or decoded), any control/whitespace/space character raw or
decoded, a decoded `//`, and unparsable URIs. `RelativeRedirectTest` exists. **No change.** Optionally port the Go fuzz
property (`bridge.FuzzSafeRelativeReturnURL`: an accepted value must be returned unchanged and, after dropping
tab/CR/LF and mapping `\` to `/`, still start with `/` and not `//`) as a jqwik/`@RepeatedTest` property over
`RelativeRedirect.isSafe`; also `passwordreset.FuzzSafeRelativeReturnURL`/`FuzzResetReturnURL`.

## 17. F45. Dependencies (L14, advisory; UNVERIFIED)

**Go** (`d7114f37`): go-webauthn 0.17.4 -> 0.18.2 (with `ExtensionsUnsolicitedOutputPolicy: Ignore` to keep the 0.17
behaviour of tolerating a ceremony whose client volunteers an unrequested extension output; a test proves it fails
without), go-oidc 3.18 -> 3.21, boombuler/barcode (QR; output pinned byte-identical by `TestQRDataURI_Stable`), x/net
(reachable HTTP/2 advisories in the router), mongo-driver; chi 5.3.0 not taken; `FC_HTTP_REAL_IP_MIDDLEWARE` (default
true, unchanged) covers three chi `RealIP` advisories.

**Java today: not a one-to-one mirror.** Java's identity stack is different libraries: `pom.xml` has
`nimbus-jose-jwt` 10.9.1, Yubico `webauthn-server-core` 2.9.0, `zxing` 3.5.4 (QR); the HTTP layer is Javalin/Vert.x
(no chi). No vulnerability scan was run, so **UNVERIFIED** whether those versions carry advisories. Two items carry
over as checks, not changes:
- Yubico `webauthn-server-core` 2.9.0 has **no** `allowUnrequestedExtensions` setting in its `RelyingParty` (checked with
  `javap` on the 2.9.0 jar), so Java's tolerance of a ceremony with unsolicited client extension outputs is whatever the
  library does; **UNVERIFIED**, and relevant only if a bump is taken. Add a ceremony test with a client that volunteers
  an extension (Go's `ceremony_test.go` does) before bumping.
- QR output stability (`TestQRDataURI_Stable`) matters only if `zxing`/`QrPng` is bumped; Java's `QrPng` is its own
  renderer (`auth/mfa/QrPng.java`).
Recommendation: run the project's dependency audit (`mvn org.owasp:dependency-check-maven:check` or the equivalent the
owner uses) once and bump nimbus/Yubico only on a finding. No migration, no env switch.

---

## 18. Rust-found items that apply to Java (from Go `identity-go-vs-rust-2026-10-19.md`)

The review found these in Rust; each was checked against Java. None needs a code change except the tests listed.

| Item (Rust ref) | Java today |
|---|---|
| **JWT clock leeway default 0** (R4: `jsonwebtoken` defaults to 60 s) | **SAME for platform tokens.** `platform/shared/auth/JwtVerifier.java:178-183` (`exp` must be in the future, `nbf` not in the future, "no leeway, like golang-jwt's defaults", class doc line 29-30); the session cookie goes through the same verifier. **DIFFERS** for the *upstream* IdP id_token: `auth/oidc/OidcProvider.java:247` `verifier.setMaxClockSkew(60)`. Whether Go's go-oidc applies leeway there was **not read** (UNVERIFIED); the review's point is about the platform's own tokens. Owner call whether 60 s on upstream tokens is intended; if not, set 0 (or a documented small value). Test to add: an access token expired by 1 s is refused (the platform verifier already has the logic; pin it). |
| **Bearer validation cache bound** (R5: unbounded `DashMap` keyed by token) | **SAME as Go (no cache).** The platform `Authenticator` verifies the RS256 signature per request (`Authenticator.java` `introspect` -> `verifier.verify(token)`); the only caches on the auth path are `JwksKeySource.keysByKid` (bounded by the JWKS), `TokenManager` (one cached outbound token) and the MCP token. No validated-claims cache exists to bound. Do not add one without a bound. |
| **JWKS refetch on unknown `kid`** (R6: TTL-only cache) | **SAME** for the platform's own JWKS: `BearerAuthenticator.java:71` `keySource.ensureKnown(kid)` refetches on an unknown kid under a 30 s floor (`JwksKeySource.REFETCH_FLOOR`, line 55), keeping the cached keys on an outage. For the **upstream IdP** JWKS (`OidcClients.java:186` `JWKSourceBuilder.create(url).build()`), refetch-on-unknown-kid is the Nimbus builder default as documented, **UNVERIFIED by test**: add a test where the IdP rotates its key (new kid) and the login still verifies. Rust R6's other edge (a kid-less token matching the first key) is **UNVERIFIED** for Java: `JWSVerificationKeySelector` (`OidcProvider.java:240`) selects by algorithm and `kid`, and its behaviour for a kid-less header was not tested; add a test. |
| **Password-login timing equalisation** (R2: Rust missed it on the main login) | **SAME.** `auth/login/LoginApi.java:256-262` calls `PasswordHash.equalizeTiming(password)` (`PasswordHash.java:137`, a verify against a lazily computed dummy Argon2id hash) when the principal is missing, inactive or has no hash; the portal login does the same (`PortalAuthApi.java:187`). Pre-existing; maybe add a test that the not-found path performs one hash verification. |
| **Wildcard redirect matcher URL-aware** (R1: Rust's string matcher accepted `https://evil%2Ecom/.example.com/callback` against `https://*.example.com/callback`) | **SAME.** `auth/oauth/RedirectUriMatcher.java:30-52` parses the incoming URI with `java.net.URI`, requires scheme and host and no userinfo; the wildcard lives only in the leftmost label of a base with >= 2 labels, `*` never consumes a dot, scheme/port compared. **Probe run (the only thing executed for this hand-off):** `new URI("https://evil%2Ecom/.example.com/callback")` and `new URI("https://evil%2Ecom#.example.com/callback")` parse to `getHost() == null` (authority `evil%2Ecom` is registry-based), so `matches` returns false at line 41; `https://evil.com/.example.com/callback` has host `evil.com`. To do: port `oauthapi.TestMatchRedirectURI`'s matrix and add the encoded-dot, `/`, `#`, `?`, `@` and backslash cases from the review plus the property of `oauthapi.FuzzMatchRedirectURI` (a matched URI's browser host must be accepted by the pattern) to `RedirectUriMatcherTest`. |
| **Introspect/revoke ownership** (B1, both sides) | = **F40 / L2** above. |

Also confirmed unchanged and not Go-only: the password-reset/return URL handling (F44), the TOTP claim (F37), the
rotation (F36).

## 19. Order, risk and what to tell the owner

1. L1 (F34), L2 (F40), L3 (F33), L4 (F28), L5 (F31): small, each independent; L1 and L2 are the security items.
2. L6 (F30), L7 (F29): the two M items; pace the missing-queue recheck inside L7 before dropping the interval.
3. L8, L9, L10 (F41, F42, F39): opt-in switches, default behaviour unchanged.
4. L12 (tests only), L13 (decision), L14 (advisory).

Behaviour changes for existing clients if all of L1-L10 ship as Go did: non-API `token_use` bearers/cookies refused
(none issued today); a cross-client `/oauth/revoke` no longer revokes (still 200); IdP role mapping to a missing role
is 400 instead of 201; a service account `scope` that disagrees with its links is 400; `attemptCount` on settled jobs
is one higher; the scheduled-job sync rollup event subject loses `.synced.`; sync `updated` counts drop to rows
actually written; `router-config` gains `ETag`/304 and routers poll every 30 s (check the IaC's explicit interval).
Deploy notes: no migration; no tokens need re-minting (nothing here changes permissions).

## 20. What was not verified

- **Nothing was run** except the `java.net.URI` probe in section 18. No Java test, build or Go test was executed.
- Java audit-row entity derivation for the scheduled-job rollup (section 5b), portal session-cookie `token_use`
  (section 1), Java callers of `/oauth/revoke` (section 2), `ProvisionServiceAccount`/`SyncPrincipals` setting `scope`
  (section 4), existence of the passkey and `Mfa` tests named in sections 10 and 15, the Nimbus JWKS builder's
  refetch-on-unknown-kid behaviour and go-oidc's leeway (section 18), vulnerabilities in the Java dependency set
  (section 17).
- F22-F27 and F35 were not read for this hand-off (out of scope).
