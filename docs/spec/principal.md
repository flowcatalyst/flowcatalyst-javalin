# Principal — behavioural spec

The contract for `io.flowcatalyst.platform.principal`. Derived from the
lockfile (`/api/principals*`, 29 operations) plus the validation rules,
authorization placement, state machines, error codes and domain events the
aggregate embodies. The Java is written *from* this; tests assert it.
Questions marked **load-bearing or accident?** need an owner ruling — until
ruled on, the behaviour is kept as described (deviations are listed in §12).

This is the platform's identity aggregate: every user and service account
is a principal, and its rows decide who may do what. Read the authorization
sections (§5, §6) twice.

## 1. Aggregate

A principal is a `USER` (a human, identified by email) or a `SERVICE` (the
identity of a service account). Both carry a tenancy **scope** and the two
access axes: client access (home client + PARTNER grants) and application
access (`allApplications` or an explicit list). USER principals hold
roles via `iam_principal_roles`, each assignment tagged with its source.

| Field | Type | Column | Notes |
|---|---|---|---|
| `id` | `prn_` + 13-char TSID | `id` | |
| `type` | `USER` \| `SERVICE` | `type` | lenient read: unknown → `USER` |
| `scope` | `ANCHOR` \| `PARTNER` \| `CLIENT` | `scope` | lenient read: `null`/unknown → `CLIENT` (most restrictive); the wire (`CreatePrincipalRequest.scope`) rejects unknown with `INVALID_SCOPE` |
| `clientId` | string, optional | `client_id` | home client; `null` for anchors, partners (whose access is grants), portal identities and service accounts |
| `applicationId` | string, optional | `application_id` | the owning application of a service principal; never set by this package |
| `name` | string | `name` | display name; `NewUser` defaults it to the email |
| `active` | boolean | `active` | |
| `userIdentity` | optional | flat columns | present iff `type = USER` **and** `email` is not null on read; see below |
| `serviceAccountId` | string, optional | `service_account_id` | `SERVICE` only |
| `roles` | list of `RoleAssignment` | `iam_principal_roles` | ordered by `assigned_at` on read |
| `assignedClients` | list of client ids | `iam_client_access_grants` | the PARTNER grants, ordered by `client_id`; hydrated on by-id / by-email / list reads |
| `accessibleApplicationIds` | list of application ids | `iam_principal_application_access` | ordered by `application_id`; hydrated on by-id / by-email reads only |
| `allApplications` | boolean | `all_applications` | access to every application (present and future); stored, not derived from scope — an anchor-tier service account can be pinned to one application |
| `externalIdentity` | optional `(providerId, externalId)` | `idp_type`, `external_idp_id` | present iff `external_idp_id` is not null; `providerId` = `idp_type` or `""` |
| `createdAt`, `updatedAt` | timestamps | | `updatedAt` stamped `now()` on every persist |

`UserIdentity` (USER only):

| Field | Column | Notes |
|---|---|---|
| `email` | `email` | lower-cased + trimmed on write; `email_domain` = text after the last `@` (null when none) |
| `provider` | `idp_type` | `INTERNAL` \| `OIDC` \| …; a USER with no provider is persisted as `INTERNAL`; an `externalIdentity` overrides both IdP columns. Kept as a string: the column is an open vocabulary shared with the auth subsystem (**accident?** — an enum would be nicer but would corrupt unknown stored values on rewrite) |
| `externalId` | `external_idp_id` | |
| `passwordHash` | `password_hash` | argon2id (`shared.auth.PasswordHash`) or a migrated upstream hash stored verbatim (sync); **never** on the wire, in an event, in an audit row or in `toString` |
| `lastLoginAt` | `last_login_at` | written by the login flow, carried through persist |
| `devClientSecretRef` | `dev_client_secret_ref` | encrypted self-service developer client-secret; never disclosed after issuance; masked in `toString` |
| `devClientSecretUpdatedAt` | `dev_client_secret_updated_at` | |

Go's `UserIdentity` also declares `emailVerified / firstName / lastName /
pictureUrl / phone` with **no backing column** (zero on read, dropped on
write). They are not modelled in Java. `clientIdentifierMap` (client id →
identifier, for the JWT `clients` claim) is hydrated by Go on by-id reads
for the auth subsystem; not needed by any route here — deferred to the auth
port (§12).

`RoleAssignment(role, assignmentSource, assignedAt)`: `assignmentSource` is
a free string column (`ADMIN_ASSIGNED`, `IDP_SYNC`, `SDK_SYNC`, `SEED`,
`BOOTSTRAP`, `MANUAL`, `null` — rows written by several Go packages); the
three values this package writes are constants. The wire defaults a `null`
source to `"ADMIN"`.

`ClientAccessGrant(id = gnt_…, principalId, clientId, grantedBy, grantedAt,
createdAt, updatedAt)` is its own aggregate root (own `Persist`, own
events) so grant/revoke carry their own events; `(principal_id, client_id)`
is unique.

Factories: `Principal.newUser(email, scope)` → USER, active, name = email,
no roles/grants/apps, `allApplications = true`. `Principal.newService(saId,
name)` → SERVICE, `ANCHOR`, active, `allApplications = true`.
`Principal.newPortalUser(email)` → USER, `CLIENT` scope, **no** client, no
password, `allApplications = false` (inert until a portal app grants it).

## 2. State machines

Status: `active` is a boolean flipped by `activate()` / `deactivate()`
(both idempotent — re-activating an active principal is a no-op write that
still emits its event, **accident?**) and by the generic update's `active`
field.

Role assignment sources (who owns which rows of `iam_principal_roles`):

| Writer | Touches | Keeps | Source written |
|---|---|---|---|
| `AssignRoles` (admin set / add / remove) | **every** assignment — the set is replaced wholesale | nothing (a previously `IDP_SYNC`/`SDK_SYNC` row is rewritten as `ADMIN_ASSIGNED`; the next IdP/SDK sync then treats it as admin-owned — **accident?**) | `ADMIN_ASSIGNED` |
| `SyncIdpRoles` (login bridge) | `IDP_SYNC` rows | every non-`IDP_SYNC` row; an incoming name already kept is not duplicated | `IDP_SYNC` |
| `SyncPrincipals` (SDK / `POST /api/principals/sync`) | `SDK_SYNC` rows | every non-`SDK_SYNC` row; incoming names lower-cased; **not** validated against `iam_roles` (no FK either) — **accident?** | `SDK_SYNC` |

Password lifecycle: set at create (optional, policy-checked), replaced by
`ResetPassword` (admin route *and* the unauthenticated token-gated confirm
flow), carried verbatim by sync (`passwordHash`), cleared when created as
`OIDC`. `SendPasswordReset` never touches the hash — it asks the emailer to
mint a token. Re-encoding of legacy hashes after login (`UpdatePasswordHash`)
and email lower-casing self-heal (`LowercaseEmail`) are login-flow repository
writes outside the envelope — deferred to the auth port (§12).

Developer credential: `SetDeveloperCredential` mints 32 random bytes →
base64url (no padding) plaintext, stores `encrypt(plaintext)` as
`devClientSecretRef`, stamps `devClientSecretUpdatedAt`, and parks the
plaintext in a process-local one-shot stash (2 min TTL) keyed by principal
id; the handler pops it once into the response. `RevokeDeveloperCredential`
clears both columns. Requires the seeded role `platform:developer` at set
time only.

Scope / client association (anchor-only, `SetClientAssociation`):

| `clientId` | `mode` | Result |
|---|---|---|
| `*` | ignored | `ANCHOR`, `clientId = null` |
| id | `CHANGE_CLIENT` | client must exist → `CLIENT`, `clientId = id` |
| id | `TO_PARTNER` | client must exist → `PARTNER`, `clientId = null`; grants (idempotent, `ON CONFLICT DO NOTHING`) for the new id **and** — when the principal was `CLIENT` with a different home client — its old home client; grant rows only, no `client-access-granted` events (**accident?**) |
| id | other / absent | 400 `MODE_REQUIRED` |

## 3. HTTP surface (lockfile)

All routes require a bearer; errors are the `ErrorModel` envelope. "Gate"
is what the handler checks before anything else; resource-level rules are
in §5. `USER_VIEW` = `platform:iam:user:view`; "write" = any of
`USER_CREATE`, `USER_UPDATE`, `USER_DELETE`; "sync" = any of `USER_MANAGE`,
`USER_CREATE`, `USER_UPDATE`, `USER_DELETE`, `USER_ASSIGN_ROLES`. Anchors pass
every permission gate.

| Method / path | Gate | Use case | Success |
|---|---|---|---|
| `GET /api/principals` | `USER_VIEW` | read | 200 `PrincipalListResponse {principals[], total}` |
| `POST /api/principals` | non-anchor with `scope != CLIENT` → 403 `FORBIDDEN` "Client administrators can only create client-scope users"; then `requireUserAdmin(body.clientId)` | `CreateUser` | 201 `CreatedResponse {id}`; then best-effort notify (§7) |
| `POST /api/principals/users` | after scope derivation (§7): same two gates against the *derived* scope/client | `CreateUser` (+ `GrantClientAccess` for PARTNER; partner-merge) | 200 `PrincipalResponse` (re-read) |
| `POST /api/principals/bulk-import` | 400 `CLIENT_REQUIRED` (blank `clientId`); `requireUserAdmin(clientId)`; 400 `NO_ROWS`; 400 `TOO_MANY` (> 1000 rows) | per row: `CreateUser`, then `AssignRoles` | 200 `BulkImportResponse` |
| `POST /api/principals/sync` | sync | `SyncPrincipals` (no application code) | 200 `SyncUsersResponse {created, updated, deleted, syncedEmails}` — `deleted` is the rollup's `deactivated` |
| `GET /api/principals/check-email-domain?email=` | `USER_VIEW`; 400 `EMAIL_REQUIRED`; 400 `INVALID_EMAIL` | read | 200 `CheckEmailDomainResponse` |
| `GET /api/principals/developer-users` | `USER_VIEW` | read (`findByRole("platform:developer")`, by name) | 200 `DeveloperUserListResponse {principals, total}` |
| `GET /api/principals/{id}` | self (caller's own id) needs no permission; otherwise `USER_VIEW`; then non-self + `clientId != null` + no access → 403 `FORBIDDEN` "No access to this principal" | read | 200 `PrincipalResponse` (+ `twoFactorMethods` when an MFA service is configured) |
| `GET /api/principals/{id}/version` | self or `USER_VIEW` | read `lookupVersion` | 200 `{updatedAt}` = `GREATEST(p.updated_at, MAX(updated_at of roles held))`; unknown id → 404 `Principal_NOT_FOUND` (§12 D1) |
| `PUT /api/principals/{id}` | write | `UpdateUser` | 200 `PrincipalResponse` (re-read) |
| `POST /api/principals/{id}/activate` | write | `ActivateUser` | 200 `{message: "Principal activated"}` |
| `POST /api/principals/{id}/deactivate` | write | `DeactivateUser` | 200 `{message: "Principal deactivated"}` |
| `POST /api/principals/{id}/reset-password` | write; then load (404) + `blockNonClientTarget` + `checkScopeAccess(p.clientId)` **in the handler** (the op is public — §5) | `ResetPassword` | 200 `{message: "Password reset successfully"}` |
| `POST /api/principals/{id}/send-password-reset` | load (404); `requireUserAdmin(p.clientId)`; `blockNonClientTarget` | `SendPasswordReset` (no event) | 200 `{message: "Password reset email sent"}` |
| `POST /api/principals/{id}/reset-2fa` | MFA not configured → 500 `MFA_NOT_CONFIGURED` (before the load); load (404); `requireUserAdmin`; `blockNonClientTarget`; non-USER → 400 `NOT_USER` | `mfa.resetAll` + notifier + audit row `2FA_RESET_BY_ADMIN` | 200 `{message: "Two-factor authentication reset"}` |
| `DELETE /api/principals/{id}` | `USER_DELETE` | `DeleteUser` | 204 |
| `GET /api/principals/{id}/roles` | `USER_VIEW`; load (404) | read | 200 `PrincipalRoleListResponse` |
| `PUT /api/principals/{id}/roles` | **no coarse gate**; load (404); non-anchor bounding (§5.3) | `AssignRoles` (set = body ∪ preserved) | 200 `RolesAssignedResponse {roles, added, removed}` — `added/removed` computed in the handler from *sets* (unordered) |
| `POST /api/principals/{id}/roles` | no coarse gate; load; non-anchor bounding of the one role; no-op when already held | `AssignRoles` (current + role) | 200 `PrincipalResponse` |
| `DELETE /api/principals/{id}/roles/{role}` | no coarse gate; load; non-anchor bounding of the one role; no-op when not held (an anchor removing an unknown role gets 200) | `AssignRoles` (current − role) | 200 `PrincipalResponse` |
| `GET /api/principals/{id}/application-access` | `USER_VIEW`; load | read | 200 `ApplicationAccessListResponse {applications, total, allApplications}` |
| `PUT /api/principals/{id}/application-access` | no coarse gate; load; `allApplications = true` by a caller without all-applications → 403 `FORBIDDEN` "Only an all-applications administrator may grant all-applications access"; non-anchor bounding (§5.3) | `AssignApplicationAccess` (set = body ∪ preserved) | 200 `SetApplicationAccessResponse {applications, added, removed, allApplications}` |
| `GET /api/principals/{id}/available-applications` | `USER_VIEW`; load | read: active applications; non-anchor → only those the principal's home client has an enabled client-config for | 200 `PrincipalAvailableApplicationsResponse` |
| `GET /api/principals/{id}/client-access` | `requireAnchor` | read (grants by `granted_at`) | 200 `ClientAccessGrantListResponse` |
| `POST /api/principals/{id}/client-access` | `requireAnchor` | `GrantClientAccess` | 200 `ClientAccessGrantResponse` (re-read) |
| `DELETE /api/principals/{id}/client-access/{clientId}` | `requireAnchor` | `RevokeClientAccess` | 204 |
| `PUT /api/principals/{id}/client-association` | `requireAnchor` | `SetClientAssociation` (mode upper-cased/trimmed) | 200 `PrincipalResponse` |
| `POST /api/principals/{id}/developer-credential` | none — self-or-user-admin inside the op | `SetDeveloperCredential` | 200 `SetDeveloperCredentialResponse {id, clientSecret?}` (`clientSecret` omitted only on a stash miss) |
| `DELETE /api/principals/{id}/developer-credential` | none | `RevokeDeveloperCredential` | 204 |

Not here: `POST /api/applications/{appCode}/principals/sync` (sdksync
surface; the `SyncPrincipals` operation is ported). `CreatePortalUser` and
`SyncIdpRoles` have no route (OIDC callback / login bridge) — ported as
operations, `publicAccess`.

Wire shapes (lockfile components; optional fields omitted when null):

| Schema | Fields | Notes |
|---|---|---|
| `CreatePrincipalRequest` | `email`*, `scope`*, `name`, `clientId`, `password`, `idpType` | |
| `CreateUserRequest` | `email`*, `name`*, `password`, `scope` (`ANCHOR\|PARTNER\|CLIENT`, default `CLIENT`), `clientId` (`clt_` id **or** identifier slug), `enforcePasswordComplexity` (accepted, **ignored** — **accident?**) | |
| `UpdatePrincipalRequest` | `name`, `active`, `email` (identity assertion) | |
| `ResetPasswordRequest` | `newPassword`*, `enforcePasswordComplexity` (default true) | |
| `SendPasswordResetInputBody` | `reset2fa` | body optional (no body = `false`) |
| `AssignPrincipalRolesRequest` | `roles`* | |
| `AddRoleRequest` | `role`* | |
| `AssignApplicationAccessRequest` | `applicationIds`*, `allApplications` | |
| `GrantClientAccessRequest` | `clientId`* | |
| `ClientAssociationRequest` | `clientId`*, `mode` | |
| `BulkImportRequest` | `clientId`*, `users[]{name*, email*, roles}` | |
| `SyncUsersRequest` | `principals[]{email*, name*, roles, active (default true), passwordHash}` | |
| `PrincipalResponse` | `id, type, scope, clientId?, name, active, email?, idpType?, roles[] (names), isAnchorUser, grantedClientIds[], createdAt, updatedAt, hasDeveloperCredential, developerCredentialUpdatedAt?, twoFactorMethods?` | `email`/`idpType` only for users; `idpType` = provider or `INTERNAL`; `twoFactorMethods` only on the by-id read and only with an MFA service |
| `PrincipalListResponse` / `DeveloperUserListResponse` | `principals[], total` | `total` = filtered count before pagination |
| `PrincipalVersionResponse` | `updatedAt` | |
| `PrincipalRoleAssignmentDTO` | `id` (`{principalId}-role-{index}`, synthetic), `roleName`, `assignmentSource` (`ADMIN` when null), `assignedAt` | |
| `RolesAssignedResponse` | `roles[]` (DTOs after re-read), `added[]`, `removed[]` | |
| `ApplicationAccessResponse` | `applicationId, applicationCode, applicationName` | ids that no longer resolve are skipped |
| `PrincipalAvailableApplication` | `id, code, name` | |
| `ClientAccessGrantResponse` | `id, clientId, grantedAt, expiresAt?` | `expiresAt` never set |
| `CheckEmailDomainResponse` | `authMethod` (`internal\|external`), `loginUrl?`, `idpIssuer?`, `domain`, `authProvider` (IdP type, default `INTERNAL`), `isAnchorDomain`, `hasIdpConfig` (= OIDC), `emailExists`, `info` (always `null`, **serialised**), `warning` (`null` or "A user with this email address already exists."), `derivedScope`, `requiresClientId` (= scope ≠ ANCHOR), `allowedClientIds[]` | `loginUrl` = `/auth/oidc/login?domain=<url-encoded>` when external |
| `SetDeveloperCredentialResponse` | `id`, `clientSecret?` | |
| `BulkImportResponse` | `created, skipped, failed, results[]{row (1-based), email (normalised), status (created\|exists\|dropped\|error), message?}` | `exists`+`dropped` count as skipped |
| `SyncUsersResponse` | `created, updated, deleted, syncedEmails[]` | |
| `StatusChangeResponse` | `message` | |

List query parameters (`GET /api/principals`, all optional, evaluated in
memory over every principal): `type` (upper-cased equality), `clientId`
(home client **or** any grant), `active` (`true`/`false`; anything else =
both), `q` (case-insensitive substring of name or email), `roles` (CSV,
any-of), `page` (0-based, default 0), `pageSize` (≤ 0 = all), `sortField`
(`name` | `email` | default `createdAt`, case-insensitive for text),
`sortOrder` (`desc` or ascending). Non-anchors see only principals with a
`clientId` they can access — platform-level principals are hidden from them
(but readable by id, **accident?**).

## 4. Validation (command shape, before authorization)

| Command | Rule | Code (400) | Message |
|---|---|---|---|
| Create / CreatePortalUser | `email` non-blank after trim | `EMAIL_REQUIRED` | `email is required` |
| Create / CreatePortalUser | `email` matches `^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$` (lower-cased) | `INVALID_EMAIL` | `email must be a valid address` |
| Create | `scope` ∈ {ANCHOR, PARTNER, CLIENT} (exact) | `INVALID_SCOPE` | `scope must be ANCHOR, PARTNER, or CLIENT` |
| Create | `clientId` present when scope is CLIENT or PARTNER | `CLIENT_REQUIRED` | `clientId is required for PARTNER or CLIENT scope` |
| Create | non-empty `password` passes the policy (§4.1) with the command's email + name | policy code | policy message |
| Update / Delete / Activate / Deactivate / ResetPassword / SendPasswordReset | `id` non-blank | `ID_REQUIRED` | `id is required` |
| Update | `name`, when present, non-blank | `NAME_REQUIRED` | `name cannot be empty` |
| Update (execute) | `email`, when present and non-blank, equals the stored email (case/space-insensitive) | `EMAIL_IMMUTABLE` | `email cannot be changed here; it is the principal's identity` |
| ResetPassword (strict, default) | `newPassword` ≥ 8 chars (validate); full policy in execute with the loaded email + name | `PASSWORD_TOO_SHORT` / policy code | `newPassword must be at least 8 characters` |
| ResetPassword (`enforcePasswordComplexity = false`) | `newPassword` ≥ 2 chars; no policy | `PASSWORD_TOO_SHORT` | `newPassword must be at least 2 characters` |
| AssignRoles / GrantClientAccess / RevokeClientAccess / AssignApplicationAccess / SetClientAssociation / SyncIdpRoles | `userId` non-blank | `USER_ID_REQUIRED` | `User ID is required` |
| GrantClientAccess / RevokeClientAccess | `clientId` non-blank | `CLIENT_ID_REQUIRED` | `Client ID is required` |
| SetClientAssociation | `clientId` non-blank | `CLIENT_ID_REQUIRED` | `clientId is required (use "*" for anchor)` |
| SetClientAssociation (execute) | specific client needs a known mode | `MODE_REQUIRED` | `mode must be CHANGE_CLIENT or TO_PARTNER for a specific clientId (use "*" for anchor)` |
| SetDeveloperCredential / RevokeDeveloperCredential | `principalId` non-blank | `PRINCIPAL_ID_REQUIRED` | `Principal ID is required` |
| SyncPrincipals | at least one principal | `PRINCIPALS_REQUIRED` | `At least one principal must be provided` |
| AssignRoles / SyncIdpRoles (execute) | every role name exists | `ROLE_NOT_FOUND` | `Role not found: <name>` |
| AssignApplicationAccess (execute) | every application id exists | `APPLICATION_NOT_FOUND` | `Application not found: <id>` |

Go measured password length in **bytes** (`len`); Java counts UTF-16 units —
identical for ASCII (**accident**, noted in §12).

### 4.1 Password policy

One policy (`PasswordPolicy.check(password, email, name)`), NIST-shaped:
length + common-password blocklist + not-your-own-identity, **no**
composition rules. First failing rule wins:

| Rule | Code | Message |
|---|---|---|
| length < 8 | `PASSWORD_TOO_SHORT` | `Password must be at least 8 characters` |
| length > 128 | `PASSWORD_TOO_LONG` | `Password must be at most 128 characters` |
| all one repeated character (after lower-case + trim) | `PASSWORD_TOO_WEAK` | `Password cannot be a single repeated character` |
| contains (or reversed contains) the lower-cased email, its local part, or any whitespace-separated word of the name — tokens shorter than 4 chars only on exact equality | `PASSWORD_CONTAINS_IDENTITY` | `Password cannot contain your email address or name` |
| lower-cased value is in the embedded 10k common-password list | `PASSWORD_TOO_COMMON` | `That password is on the list of most commonly used passwords — choose something less guessable` |
| contains `flowcatalyst` | `PASSWORD_TOO_COMMON` | `Password cannot be based on the product name` |

Applied by: `CreateUser` (password present; email + command name), strict
`ResetPassword` (loaded email + name). **Not** applied by the relaxed SDK
reset, by sync (`passwordHash` is stored verbatim) or by `/users`'
`enforcePasswordComplexity` (ignored). The list lives in
`principal/common_passwords.txt` — candidate for `shared.auth` when the auth
subsystem lands.

## 5. Authorization placement

### 5.1 Handler gates — see §3.

### 5.2 Resource-level rules (operations, post-load)

| Rule | Meaning | Used by |
|---|---|---|
| `blockNonClientTarget(ac, p)` | a non-anchor may act only on `CLIENT`-scope principals → else 403 `FORBIDDEN` "Client administrators can only manage client-scope users" | everything below |
| `requireManageable(p)` = `blockNonClientTarget` + `Checks.checkScopeAccess(ac, p.clientId)` | client-admins touch only CLIENT users of a client they can access; a clientless target needs anchor (`SCOPE_FORBIDDEN`) | `UpdateUser`, `ActivateUser`, `DeactivateUser`, `DeleteUser` (in execute); `reset-password` handler |
| `requireUserAdmin(p)` = `Checks.requireUserAdmin(ac, p.clientId)` + `blockNonClientTarget` | anchors pass; a non-anchor must access the target's client, hold a write permission, and the target must be CLIENT-scope; clientless target → `ANCHOR_REQUIRED` | `AssignRoles`, `AssignApplicationAccess` (in execute); `send-password-reset` / `reset-2fa` handlers |
| `requireSelfOrUserAdmin(p)` | the caller's own principal unconditionally, else `requireUserAdmin` | `SetDeveloperCredential`, `RevokeDeveloperCredential` |

Every operation declares `Authorize.publicAccess()`: by-id writes run
their rule right after the load (above); `CreateUser`, `CreatePortalUser`,
`ResetPassword`, `SyncIdpRoles`, `SyncPrincipals` are reached from
unauthenticated system flows (login JIT provisioning, OIDC callback, the
token-gated reset confirm, the login bridge, the SDK sync) and each entry
point keeps its own gate; `GrantClientAccess`, `RevokeClientAccess`,
`SetClientAssociation` have no per-resource dimension — their callers are
anchor-gated handlers (and `createUser`'s partner-merge, under
`requireUserAdmin`).

**load-bearing or accident?**
- `PUT/POST/DELETE …/roles`, `PUT …/application-access`, developer
  credential routes have no coarse handler gate and load the target before
  authorizing, so an authenticated non-admin learns whether an id exists
  (404 vs 403).
- `GET …/roles`, `GET …/application-access`, `GET …/available-applications`
  require only `USER_VIEW` — no client-scope check on the target.
- `GET /{id}` lets a non-anchor read a platform-level (clientless) principal
  while the list hides it.

### 5.3 Command shaping for non-anchor administrators (handlers)

"allowed" = application ids the **target's home client** has an *enabled*
client-config for (`ClientConfigRepository.findByClient`); a clientless
target → empty set.

| Where | Rule | Error |
|---|---|---|
| assign / add / remove roles, bulk import | every named role must exist | 400 `UNKNOWN_ROLE` "role not found: <name>" |
| same | …be application-scoped (platform roles are never assignable by a client-admin) | 403 `PLATFORM_ROLE_FORBIDDEN` "client administrators cannot assign platform roles" |
| same | …belong to an allowed application | 403 `ROLE_APP_FORBIDDEN` "role belongs to an application the client cannot access" |
| assign roles (set) | the target's existing roles that the admin may not manage (platform / unknown / other-app) are **preserved** (appended, de-duplicated) | — |
| assign application access | every requested id allowed | 403 `APP_FORBIDDEN` "application the client cannot access: <id>" |
| assign application access | existing grants outside "allowed" preserved | — |
| assign application access (any caller) | `allApplications = true` requires the caller's own `allApplications` | 403 `FORBIDDEN` "Only an all-applications administrator may grant all-applications access" |
| available-applications | menu limited to allowed | — |

Anchors skip all of it.

## 6. Conflicts, not-found and business rules (execute phase)

| Operation | Condition | Code | Status |
|---|---|---|---|
| CreateUser / CreatePortalUser | a USER with the normalised email exists | `EMAIL_EXISTS` | 409 |
| Update / Delete / Activate / Deactivate / ResetPassword / SendPasswordReset | no principal | `Principal_NOT_FOUND` | 404 |
| AssignRoles / GrantClientAccess / RevokeClientAccess / AssignApplicationAccess / SetClientAssociation / SyncIdpRoles / Set-/RevokeDeveloperCredential | no principal | `User_NOT_FOUND` (**accident**: two resource names for one aggregate) | 404 |
| ResetPassword | principal not USER | `NOT_A_USER` (conflict) | 409 |
| SendPasswordReset | not USER / has externalIdentity / no email | `NOT_USER` / `OIDC_USER` / `NO_EMAIL` (validation) | 400 |
| SendPasswordReset | emailer not configured / send failed | `EMAILER_NOT_CONFIGURED` / `EMAILER` | 500 |
| AssignRoles / SyncIdpRoles | not USER | `NOT_A_USER` (business rule) | 400 |
| GrantClientAccess | not USER / not PARTNER / client unknown / grant exists | `NOT_A_USER` / `NOT_PARTNER_SCOPE` / `Client_NOT_FOUND` (404) / `GRANT_EXISTS` | 400 / 400 / 404 / 400 |
| RevokeClientAccess | not USER / no grant | `NOT_A_USER` / `Grant_NOT_FOUND` (id `<userId>:<clientId>`) | 400 / 404 |
| AssignApplicationAccess | application inactive | `APPLICATION_INACTIVE` | 400 |
| SetClientAssociation | not USER / client unknown | `NOT_A_USER` / `Client_NOT_FOUND` | 400 / 404 |
| SetDeveloperCredential | not USER / lacks `platform:developer` / no app key | `NOT_A_USER` / `NOT_A_DEVELOPER` (400) / `SECRET` (500) | |

(`businessRule` errors render 400.)

## 7. Handler compositions

**`POST /api/principals/users` (create-user)** — in order:
1. email lower-cased + trimmed; no `@`/trailing `@` → 400 `INVALID_EMAIL` "Invalid email format"; domain = after `@`.
2. `isAnchorDomain` = `tnt_anchor_domains` has the (lower-cased) domain; `mapping` = email-domain mapping for the domain; `idpType` = the mapping's IdP type (`INTERNAL` when unmapped/unknown).
3. `clientId` resolved: `clt_` id, else identifier (lower-cased); unknown → 404 `Client_NOT_FOUND`.
4. scope derivation (pure, `UserScopeDerivation.derive`): the requested scope wins (default `CLIENT`); the domain only **confirms** a privileged scope — `ANCHOR` needs anchor domain or ANCHOR mapping (`ANCHOR_DOMAIN_REQUIRED`) and carries no client; `PARTNER` needs a PARTNER mapping (`PARTNER_DOMAIN_REQUIRED`), a clientId (`CLIENT_REQUIRED` "clientId is required for partner users") allowed by the mapping's primary or granted ids (`CLIENT_NOT_ALLOWED` "clientId <id> is not allowed for partner domain <domain>"); `CLIENT` uses the request's clientId, falling back to a CLIENT mapping's primary; other → `INVALID_SCOPE`.
5. gates: non-anchor & scope ≠ CLIENT → 403; `requireUserAdmin(clientId)`.
6. PARTNER + existing principal by email: same home client → 409 `EMAIL_EXISTS`; else `GrantClientAccess` (no create) and return the refreshed principal.
7. `CreateUser` (name verbatim, idpType from 2), then for PARTNER `GrantClientAccess(new id, clientId)`; re-read; notify; 200.

**Notify new user** (best-effort, never fails the request): skip service
accounts, OIDC users (`externalIdentity` or provider `OIDC`) and blank
emails; no password + invite emailer → `sendInvite`; otherwise
`notifier.accountCreated(email)`. Both are no-op implementations today (§12).

**Bulk import** — per row, sequential, own transactions; statuses: `error`
(blank/`@`-less email, blank name, duplicate email in file, role bounding
failure for a non-anchor, create failure), `exists` (email already a
principal), `dropped` (email domain mapped to a different client — mapping
primary + additional ids; granted ids are access, not ownership), `created`
(also when the role assignment fails: message "created, but roles not
applied: …"). Created users are CLIENT-scope, passwordless → invite.

**check-email-domain** — `derivedScope`: anchor domain → ANCHOR; unmapped →
CLIENT; else the mapping scope. `allowedClientIds`: PARTNER mapping →
primary + granted (de-duplicated); CLIENT mapping → primary; else `[]`.

**Version read** — Go fronts `lookupVersion` with a two-level cache
(local LRU + Redis store, bumped on every principal write). Java reads the
database directly — still correct, no propagation delay; cache is a
deployment optimisation to revisit with the standby/Redis work (§12).

## 8. Domain events

Source `platform:iam`; spec version `1.0`; per-aggregate subject
`platform.principal.{id}` (audit `entity_type = Principal`), message group
`platform:principal:{id}`. Payload field names verbatim; arrays never null.

| Type | `data` | Emitted by |
|---|---|---|
| `platform:iam:user:created` | `principalId, email` | CreateUser, CreatePortalUser, SyncPrincipals (per new row) |
| `platform:iam:user:updated` | `principalId, name` | UpdateUser, SetClientAssociation, SyncPrincipals (per existing / stripped row) |
| `platform:iam:user:activated` | `principalId` | ActivateUser |
| `platform:iam:user:deactivated` | `principalId` | DeactivateUser |
| `platform:iam:user:deleted` | `principalId, email` (`""` for a service principal) | DeleteUser |
| `platform:iam:user:password-reset-completed` | `principalId` | ResetPassword |
| `platform:iam:user:roles-assigned` | `principalId, roles[], added[], removed[]` | AssignRoles, SyncIdpRoles |
| `platform:iam:user:application-access-assigned` | `userId, applicationIds[], added[], removed[]` | AssignApplicationAccess |
| `platform:iam:user:client-access-granted` | `principalId, clientId` | GrantClientAccess |
| `platform:iam:user:client-access-revoked` | `principalId, clientId` | RevokeClientAccess |
| `platform:iam:user:developer-credential-set` | `userId` | SetDeveloperCredential (never the secret) |
| `platform:iam:user:developer-credential-revoked` | `userId` | RevokeDeveloperCredential |
| `platform:iam:principals:synced` | `applicationCode` (`""` when none), `created, updated, deactivated, syncedEmails[]` — subject `platform.principals[.{applicationCode}]`, group `platform:principals` | SyncPrincipals rollup |

Audit rows: `operation` = command record simple name (`CreateCommand`,
`UpdateCommand`, `AssignRolesCommand`, …), `operation_json` = the command.
`CreateCommand`, `ResetPasswordCommand` and `SyncPrincipalInput` carry a
plaintext password / a hash → the command JSON **must not** include them:
the records serialise those components as absent (`@JsonIgnore`). The
envelope writes `msg_events.data` from the event only. `SendPasswordReset`
is not an envelope operation in Go (no event, no audit row) — kept so
(**accident?**).

## 9. Persistence

`iam_principals` upsert `ON CONFLICT (id)` replacing every column except
`created_at` (`updated_at = now()`). USER rows: email lower-cased/trimmed,
`email_domain` derived, `idp_type` defaulted to `INTERNAL`, external
identity wins for the IdP columns; SERVICE rows: null identity columns.
The base persist writes the row **only** — junctions are owned by the
operation that loaded them, through the repository's composed persisters,
each writing in the same transaction as the event:

| Persister | Writes | Used by |
|---|---|---|
| `repo` | row | create, update, activate, deactivate, reset password, developer credential, sync-idp? no → see below |
| `repo.withRoles()` | row + `iam_principal_roles` rewritten from `roles` (delete-then-insert, `ON CONFLICT (principal_id, role_name) DO UPDATE`) | AssignRoles, SyncIdpRoles, SyncPrincipals |
| `repo.withApplicationAccess()` | row + `iam_principal_application_access` rewritten (`ON CONFLICT DO NOTHING`) | AssignApplicationAccess |
| `repo.withClientGrants(ids, grantedBy)` | row + missing `iam_client_access_grants` rows (`ON CONFLICT (principal_id, client_id) DO NOTHING`) | SetClientAssociation |

`ClientAccessGrantRepository`: upsert `ON CONFLICT (id)` (granted_by,
updated_at); delete by id. Delete of a principal removes application access,
grants and roles, then the row. Reads: by id, by email (`type = USER AND
LOWER(email) = lower(trim(input))`), by service account (`type = SERVICE`),
by role (join, by name), users by email domain (by email), all (`created_at
DESC`); roles / grants hydrated in one `IN` query each.

## 10. Dependencies this package defines (no implementation exists yet)

| Interface | Methods | Today |
|---|---|---|
| `PasswordResetEmailer` | `sendResetEmail(principal, reset2fa)` | `notConfigured()` → `EMAILER_NOT_CONFIGURED` |
| `InviteEmailer` | `sendInvite(principal)` | logging no-op |
| `Notifier` | `accountCreated(email)`, `twoFactorReset(email)` | logging no-op |
| `MfaService` | `confirmedMethods(principalId)`, `resetAll(principalId)` | `notConfigured()` → empty / `MFA_NOT_CONFIGURED` |
| `AnchorDomains` | `contains(domain)` | jOOQ read of `tnt_anchor_domains` (belongs to the auth aggregate) |

## 11. Open questions for the owner (summary)

1. `AssignRoles` rewrites every assignment as `ADMIN_ASSIGNED`, silently adopting IdP/SDK-sourced rows.
2. `SyncPrincipals` does not validate role names (no FK); `removeUnlisted` strips `SDK_SYNC` roles from every USER not in the payload regardless of application.
3. Role / application-access / developer-credential mutations have no coarse handler gate and load before authorizing (existence oracle).
4. Read routes under `/{id}/…` check only `USER_VIEW`, not client scope; by-id read is lenient on clientless principals while the list hides them.
5. `SetClientAssociation` emits `user:updated` (name only) and writes TO_PARTNER grant rows without `client-access-granted` events.
6. `/users` accepts `enforcePasswordComplexity` and ignores it; create always enforces the policy.
7. Two not-found resource names (`Principal_NOT_FOUND` vs `User_NOT_FOUND`).
8. `SendPasswordReset` bypasses the envelope (no event / audit).
9. Activate/deactivate are idempotent writes that still emit events.
10. Version read: Go answers 500 for an unknown id (comment says 404) — Java answers 404 (D1).
11. Rollup subject for an application-less sync: Go emits `platform.principals.` (trailing dot) — Java emits `platform.principals` (D2).
12. Password length counted in bytes (Go) vs chars (Java) (D3).

## 12. Deliberate deviations / deferred

- **D1** `GET /{id}/version` unknown id → 404 `Principal_NOT_FOUND` (Go: 500 `REPO`).
- **D2** sync rollup subject without trailing dot when no application code.
- **D3** password length in UTF-16 units.
- **D4** `SendPasswordReset`: "emailer not configured" is raised at send time (after the eligibility checks) instead of before the load.
- Deferred to the auth port: `clientIdentifierMap` hydration, `updatePasswordHash` / `lowercaseEmail` login self-heal writes, `findClientAdminEmails` (reset approval), version cache, MFA service (`reset-2fa` answers 500 `MFA_NOT_CONFIGURED`; the `2FA_RESET_BY_ADMIN` audit row is not written — the audit repository is read-only), emailers/notifier (no-ops).
