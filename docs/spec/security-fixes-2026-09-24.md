# Security fixes from the 2026-09-24 review

Two read-only reviews (identity/access surface; functions + delivery plane) found the defects
below. The orchestrator verified the critical ones against the code before writing this. Standing
rulings that decide these fixes:

- **Permissions from roles at every tier** (owner, 2026-09-13; `docs/spec/permissions-from-roles.md`,
  `docs/spec/reach-only-routes.md`): the ANCHOR tier is *reach* (every client), never authority.
  A gate that checks only the tier, or skips the permission for an anchor, contradicts the ruling.
  New gates reuse an existing permission; mint one only where none fits (reach-only-routes rule).
- **Trust model** (owner, 2026-09-23): functions are the operator's code. Tenants' API callers,
  client admins and application SDK credentials are NOT trusted with each other's data or with
  platform authority.
- **Result types** (`CONVENTIONS.md` §8): new decision points return a sealed outcome; the envelope's
  `UseCaseException` stays the way operations refuse.
- Every fix has a test that fails on the unfixed code (state the mutant in the report).

Streams S1–S3 run in parallel worktrees; S0 is the orchestrator's own.

## S0 — orchestrator (cross-cutting and function host)

1. `Exchange.path()` answers the path the router matched (`normalizedPath`); a gate must never see
   `/auth/../api/…` while the router dispatches `/api/…`. Router `BasicAuthFilter`, `ProfileOnlyGate`,
   `SchemaValidation`, `OAuthIpLimits` and path-matched before-filters all read it.
2. Function host: `BearerAuthenticator` refuses `token_use=identity` (as the platform does);
   `hasReach` parses `clients`/`applications` with `ScopeClaim` (today every non-anchor call 404s).
3. fcdev: `UpgradeCommand`'s client follows redirects (GitHub release downloads are 302s — upgrade
   and the first-use host-jar fetch cannot work today); a missing `.sha256` for fcdev's own binary is
   a hard error; `OwnerOnlyFile` creates the file 0600 before writing the secret.
4. Audit-log API applies `AuditRedaction` on read, as the BFF does.
5. Error-context items: secret verification distinguishes "unverifiable" (decrypt failure,
   malformed ref) from "no match" and logs it; `JwksKeySource` logs fetch failures and restores the
   interrupt flag.

## S1 — authority gates (principal, role, service account, application, OAuth client create)

1. **Remove the anchor permission bypass** in `Access.requireUserAdmin` (`if (!ac.isAnchor())`): every
   tier needs the permission.
2. **Every `Checks.requireAnchor`-only gate that issues authority or credentials also requires a
   permission**: principal roles PUT/POST/DELETE, application-access, send-password-reset, reset-2fa,
   developer-credential set/revoke, client-access grant/revoke/list, client-association,
   service-account roles + `/token` mint, `RolesBff` create/update/delete/sync-platform/create-
   permission, `ApplicationApi` attach/provision SA, provision login client, enable/disable for
   client, `EventTypesBff.syncPlatform`. Find every other `requireAnchor`-only gate and classify it
   (authority-issuing → gate; read-only reach → note). Report the permission chosen per route.
   Pinned scenario: an application's provisioned service account (only `application-service`)
   cannot assign itself `platform:super-admin` nor mint a token after trying.
3. **Principal sync** (`SyncPrincipals`, both entry points `PrincipalApi.syncUsers` and
   `SdkSyncApi`): an existing principal is touched only if the caller could administer it
   (`blockNonClientTarget` + in-scope rule, as `requireUserAdmin`); role names must exist and belong
   to the sync's application (app-scoped route) — on the platform route (no application) only roles
   the caller may assign (`assertAssignableRoles` semantics); `passwordHash` is applied to newly
   created principals, and to an existing principal only when the caller is super-admin (report
   this as an owner question). Pinned: a client admin cannot sync `platform:super-admin` onto
   itself, cannot set an anchor user's password hash, cannot deactivate a principal outside reach.
4. **OAuth client create/update**: `principalId` must name an existing, active SERVICE principal the
   caller can reach.
5. **Role permissions are confined to the role's application**: `CreateRole`, `SyncRoles`, the grant
   routes, `RolesBff` — a permission whose first segment is not the role's application code is
   refused (`platform:` permissions only on `platform:` roles). Pinned: an SDK credential syncing
   `myapp:admin` with `platform:*:*:*` is refused.

## S2 — sessions and tokens (auth/clientselection, auth/oauth, auth/grant, 2FA/passkey self-service, redirects, mail limits)

1. `/auth/client/switch` and `/auth/client/*`: session-cookie authentication only; the principal
   must be active; a minted token keeps the caller's confinement (never widens to `Authority.full`
   from a narrowed token).
2. `/oauth/authorize`: the session is the cookie only (or a bearer whose `token_use` is the session
   kind — decide from `TokenClaims`, report which); the principal must be active. Code redemption
   (`OAuthTokenApi`) re-checks active.
3. `client_credentials` at the token endpoint refuses unless the client's principal is an active
   SERVICE principal (defence in depth for S1.4).
4. `/auth/webauthn/register/*`, `/auth/2fa/methods/*`, `/auth/2fa/recovery-codes/*`: session-cookie
   authentication only.
5. Refresh rotation is atomic (`UPDATE … WHERE consumed_at IS NULL RETURNING`; the replacement is
   issued only when exactly one row changed) and `RefreshRotation` returns a sealed outcome
   (`Rotated | Unknown | ReuseDetected(family, revokedCount) | Refused(reason)`); reuse is logged at
   WARN with the family id. Pinned: two concurrent presentations → exactly one succeeds.
6. Safe relative redirects (`OidcBridgeApi` return URL, `PasswordResetApi.safeRelativeRedirect`):
   one shared predicate that parses with `URI` and refuses any scheme, authority, backslash, or
   control/whitespace character. Pinned: `/%09/evil.com`, `/\evil.com`, `//evil.com`.
7. `/auth/password-reset/request` and `/auth/2fa/challenge/email`: per-email and per-IP buckets,
   reusing the limiter portal reset already uses; the answer stays indistinguishable.

## S3 — delivery plane (subscriptions, connections, dispatch-job ingest, scheduled jobs)

1. A subscription or connection may name a service account (or connection) only if it belongs to
   the subscription's client/application or the caller can otherwise reach it (create AND update).
   Spec text "not validated" is superseded by this reach rule — report it as an owner note.
2. Dispatch-job and event ingest: a non-anchor caller must name an accessible, non-null `clientId`;
   a `subscriptionId` outside that client is refused; no fall-back to the code-prefix application's
   service account when that application is not the caller's.
3. A caller-supplied dispatch-job id that already exists is refused (no duplicate `id` rows).
4. `JobDispatcher`: the response is read with a cap under an overall deadline, so a slow or huge
   response cannot stall the single scheduler loop or exhaust the heap.
