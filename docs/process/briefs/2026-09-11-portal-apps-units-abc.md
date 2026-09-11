# Brief — portal apps, units A / B / C (parallel, one worktree each)

## Common to all three

You are in a git worktree branched from `portal-apps` after unit F
(foundation) landed: V9 migration, jOOQ for `portal_apps` /
`portal_identity_apps` / new columns, `EntityType.PORTAL_APP`, the
`portalapp` package (`PortalApp`, `PortalAppCode`, `PortalAppRepository`),
`PortalIdentity` with `apps` / `invitedAt` / `inviteExpiresAt` / `grant` /
`revoke` / `hasApp` / `state(now)`, `PortalIdentityRepository.search` /
`markInvited`, `OAuthClient.portalAppId`, and the extended
`EnsurePortalIdentity` (§3.1). Read that code before writing any — use those
names, don't re-invent them. The lockfile is already at Go `2fe6bf0`.

Read first: `CLAUDE.md` (the testing policy is mandatory, including the
mutation check), `CONVENTIONS.md` §2, §3, §6, §8, and
**`docs/spec/portal-apps.md`** — Part A (Java decisions) and Part B (the
contract; § numbers below are Part B's). The spec is the authority; Go
(`../flowcatalyst-go`, read-only) is evidence only. Where the spec and the
lockfile disagree on a wire shape, the lockfile wins — say so in the report.
Null fields are omitted by `Json.MAPPER` already (≈ Go `omitempty`).

Build **through the reactor, in your worktree, never `mvn install`**:
`export JAVA_HOME=$(mise where java)`, then
`mvn -q -B -pl server -am test -Dtest='<pattern>' -Dsurefire.failIfNoSpecifiedTests=false`.
Two other agents build in their own worktrees; that is fine. **Do not
commit.** If a compile error or red test does not yield to one clear fix,
stop and report the error with your diagnosis — the orchestrator debugs.

Operations follow the envelope template (`eventtype`; multi-aggregate:
`application.operations.ProvisionServiceAccount`). Operation tests assert
the `msg_events` row (type, subject, message group, data) **and** the
`aud_logs` row. API tests use `TestHttp` with test headers. Registration: add
your lines in `io.flowcatalyst.server.Platform` near the existing portal
wiring (`PortalUserApi.register…`); don't reorder anything else.

Finish with: your tests, `LockfileCoverageTest`, then the full server suite
once (`mvn -q -B -pl server -am test`), reporting every failure verbatim.

Report: files; routes table (method, path, status, authorization); a
**mutation table** — each mutant you applied and the test assertion that
killed it (a mutant nothing kills means the test is decorative: fix the test);
spec ambiguities and how you resolved them; full-suite result.

---

## Unit A — portal-users API (§3.2, §4.1–§4.3)

You own `io.flowcatalyst.platform.portalidentity.operations/**` (except the
already-landed Ensure — extend only if needed), `portalidentity.api.PortalUserApi`,
`PortalInviteEmailer` / `PortalInvites`, their tests, and the
`PortalUserApi` registration lines.

1. **Operations** `GrantPortalIdentityApp` / `RevokePortalIdentityApp`,
   commands `GrantPortalIdentityAppCommand(clientId, identityId, portalAppId)` /
   `RevokePortalIdentityAppCommand(…)` — all three required
   (`TARGET_REQUIRED`); identity must exist with the same client
   (`resourceNotFound("PortalIdentity", id)`), app likewise
   (`resourceNotFound("PortalApp", id)`); grant uses source `ADMIN`; both
   idempotent and both **always** persist and emit. Events
   `platform:portal:identity:app-granted` `{identityId, clientId,
   portalAppId, portalAppCode, source}` and `…:app-revoked` `{identityId,
   clientId, portalAppId, portalAppCode}`, subject/message group exactly as
   `PortalIdentityEnsured`'s (§3 table).
2. **Invite expiry**: the invite emailer must report when the link it minted
   expires (72 h, the existing invite TTL — find where it's defined, don't
   duplicate the constant) so the API can `markInvited(id, now, expires)`.
3. **`POST /api/portal-users`** per §4.1, all six steps: `portalAppCode`
   resolved by normalised code to the client's app (404 with the normalised
   code as id); the redirect default ordered **with the resolved app's own
   OAuth clients first** (stable), skipping unparsable/wildcard URIs; the SSO
   branch's *pending* rule (never signed in: `lastLoginAt == null`) and
   mark-invited **with no expiry** when pending and (invited or link
   returned); the password branch marks invited with `now + 72h`. Response
   adds `portalAppCode` (normalised; omitted when none) and `state` (§2.3,
   evaluated after marking — re-read the identity).
4. **`GET /api/portal-users`** per §4.2: `q`, `portalAppCode`, `page`
   (negative ⇒ 0), `size` (default 100, ≤0 ⇒ 100, cap 1000) over
   `PortalIdentityRepository.search`; the item shape with `state`, `apps`
   (always an array, `granted_at` order, `code`/`name` falling back to the app
   id when the app can't be resolved — resolve the page's apps in one call,
   not one per row), `invitedAt` / `inviteExpiresAt`; top-level `total`,
   `page`, `size`. Retire `findByClient` if nothing else uses it.
5. **Grant/revoke routes** §4.3 (manage): `POST /api/portal-users/{id}/apps`
   body `{clientId, portalAppCode}` — inactive app ⇒ 400
   `PORTAL_APP_INACTIVE` (the grant route checks this; revoke does not);
   `DELETE /api/portal-users/{id}/apps/{portalAppCode}?clientId=`. Messages
   exactly as §4.3.

Tests must pin: §9.3 through the API (not just the repository), §9.4
exactly, state `INVITE_EXPIRED` for an identity whose `inviteExpiresAt` is in
the past (use `markInvited` with a past expiry), pagination clamps, a
non-manage caller 403 on grant/revoke, view-only caller 200 on list, unknown
app code 404 on list/ensure/grant/revoke, grant-then-revoke removes the row.
Mutants to try at least: drop the app-first ordering of redirect URIs; skip
`markInvited` in the password branch; default size 50; revoke doesn't
persist.

---

## Unit B — portal-apps aggregate + OAuth-client link (§3.3–§3.6, §4.4, §4.5)

You own `io.flowcatalyst.platform.portalapp.operations/**`,
`portalapp.api.PortalAppApi`, the `portalAppId` wire changes in
`oauthclient.api.OAuthClientApi` and the `Create/UpdateOAuthClient`
command + operation, their tests, and the `PortalAppApi` registration lines.
Do not touch `portalidentity/**` or `portalauth/**`.

1. `PortalAppEvents`: `platform:portal:app:created|updated|deleted`, data
   `{portalAppId, clientId, code, name}`, subject `platform.portal-app.{id}`,
   message group `platform:portal-app:{id}`, source `platform:portal` (§3).
2. Operations: `CreatePortalApp` (§3.3, single aggregate);
   `CreatePortalAppWithOAuthClient` (§3.4, `TxOperation`: validation before
   the transaction incl. `INVALID_CLIENT_TYPE` and `REDIRECT_URI_INVALID`
   with the exact message; inside it the client check, `CODE_EXISTS` 409
   with the exact message, the app, and the OAuth client built exactly per
   §3.4's list — reuse `oauthclient` factories and `oauthclient.operations.Secrets`
   the way `ProvisionServiceAccount` does, `client_id` =
   `EntityType.OAUTH_CLIENT.generate()`, emitting the existing
   `OAuthClientCreated`; result record carries the plaintext secret);
   `UpdatePortalApp` (§3.5); `DeletePortalApp` (§3.6, `TxOperation`: delete
   **every** OAuth client with `portal_app_id = app.id` emitting the
   existing `OAuthClientDeleted` each, then the app; result lists the deleted
   OAuth `client_id`s). All `Authorize.publicAccess()`; the controller gates.
3. `PortalAppApi` §4.4: `GET/POST /api/portal-apps`, `PUT/DELETE
   /api/portal-apps/{id}`. Read gate = `Checks.requirePortalUserView`, manage
   = `Checks.requirePortalUserManage` (same semantics as §4). `GET` without
   `clientId`: anchors get every client's apps, others 400
   `CLIENT_ID_REQUIRED`. `POST` answers **201**. `PortalAppResponse` with
   `oauthClients` (ordered by name, always an array) and `userCount` —
   batch both for the list (no per-app queries). Delete message suffix rule
   exactly as §4.4.
4. OAuth clients §4.5: `portalAppId` on create/update requests and the
   response; the controller pre-check (`PortalApp_NOT_FOUND`,
   `PORTAL_APP_CLIENT_MISMATCH` with the exact message, then
   `portalClientId := app.clientId`); update semantics (`""` unlinks,
   omitted keeps, `portalClientId: ""` clears both). The
   `PORTAL_APP_REQUIRES_PORTAL_CLIENT` invariant is already in the entity —
   make sure it surfaces as 400 through the API.

Tests must pin §9.7 and §9.8 exactly (for §9.8's "user loses B's grant",
create the identity + grants via `PortalIdentityRepository` directly), plus:
the OAuth client created by §3.4 is refused by nothing it should pass — assert
its stored fields, not just the response; a PUBLIC app has no secret and
`client_type` PUBLIC; the transaction really rolls back (the `CODE_EXISTS`
case leaves the OAuth-client table count **for that client** unchanged);
events + audit rows for create/update/delete, including the OAuth-client
events from the orchestrations. Mutants to try at least: unlink instead of
delete in DeletePortalApp; add `refresh_token` to the grant types; drop PKCE;
move the code-uniqueness check after the OAuth client insert outside the tx;
skip the mismatch check.

---

## Unit C — login gate, id_token claims, profile-only filter (§5, §6)

You own `io.flowcatalyst.platform.portalauth/**` (password login in
`PortalAuthApi`, the SSO callback in `PortalSso`), the portal branch of
`auth.oauth.OAuthTokenApi` + `PortalSubjects` + `portalidentity.PortalIdentityAccess`
(its implementation), `auth.token.TokenIssuer` (portal id_token claims),
`shared.auth.Authenticator` (principal type), a new filter class in
`shared.auth`, their tests, and the `Platform` wiring for all of it.

1. **Resolve the app** for a flow's OAuth client with
   `PortalAppRepository.findByOAuthClientId(<the OAuth client_id string>)`;
   empty ⇒ legacy, no gate (§2.4).
2. **Password login** §5.1: after a successful password verify and **before**
   the flow is consumed: app present and (`!active` or no grant) ⇒ **403**
   `{"code":"NO_PORTAL_ACCESS","message":"You don't have access to this portal"}`
   in the portal plane's existing body shape; the flow stays unconsumed.
3. **SSO callback** §5.2: inactive app ⇒ the `access_denied` redirect with
   `This portal is not currently available`; absent identity ⇒
   `EnsurePortalIdentity` with source JIT **and the app id** (first login
   grants it); existing non-ACTIVE ⇒ unchanged suspended redirect; existing
   without the grant ⇒ `access_denied` `You don't have access to this portal`
   and **no** grant is added.
4. **Redemption** §5.3: after the exists-and-ACTIVE check, resolve the app for
   the **redeeming** OAuth client's `client_id`; present and (`!active` or no
   grant) ⇒ 400 `invalid_grant` `Portal identity has no access to this portal`.
   `PortalSubjects.Subject` grows what this needs (the identity's client id and
   granted app ids).
5. **id_token claims** §5.4: `portal_client_id` (always, portal logins),
   `portal_app_code` + `portal_app_id` (app-linked only); omitted — not null —
   when absent; non-portal id_tokens never carry them.
6. **Principal type** (J6): contexts built from the session cookie carry
   `PrincipalType.USER`; bearer contexts keep the `type` claim; test-header
   contexts take `X-FC-Test-Principal-Type` (absent ⇒ `null`).
7. **Profile-only filter** §6: a before-filter registered in `Platform`
   immediately after `authenticated(...)` and before `SchemaValidation`;
   role-less ⇔ type USER ∧ no roles ∧ no permissions; allowlist `/auth/*`,
   `/portal/*`, `GET /api/me`; answers 403 in the **platform envelope**
   `{"error":"NO_PLATFORM_ROLE","message":"Your account has no platform
   access. Only your profile is available."}` (use
   `UseCaseException.authorization`, and pin the exact body). Pass-through:
   no context, SERVICE, null type.

Tests must pin: §9.5 exactly (including "wrong password via B ⇒ 401, never
403 first" and "the flow is not consumed on 403" — prove it by completing a
login with the same flow after granting), §9.6 (decode the id_token and
assert the three claims; the legacy-client case has `portal_client_id` and no
app claims; revoke-then-fresh-code ⇒ `invalid_grant`), the three SSO
outcomes, and §9.9 — **plus the integration that actually matters: a real
session-cookie login of a role-less user** (no test headers) gets 403
`NO_PLATFORM_ROLE` on `/bff/roles` and 200 on `GET /api/me` and
`/auth/me`. The filter will change what existing tests see if any of them log
in role-less users for real: report each such test and why, do not weaken the
filter to make them pass. Mutants to try at least: gate before the password
check; consume the flow before the gate; JIT-grant existing identities in
SSO; check the flow's client instead of the redeeming client at `/oauth/token`
(if they can differ — say if they can't); session contexts left `null`-typed;
allowlist `/api/me` for every method.
