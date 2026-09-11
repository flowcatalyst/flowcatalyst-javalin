# Brief — portal apps follow-up: assign-unassigned + the P6 lost update (Go `13e80c9`)

Branch `portal-assign` in `/Users/andrewgraaff/Developer/flowcatalyst-javalin`
(checked out; **do not commit, do not switch branches**). Already done by the
orchestrator: lockfile at Go `13e80c9` (so `LockfileCoverageTest` is red on
`assignUnassignedPortalUsers` until you land it), SPA synced, spec Part B
refreshed. Spec: `docs/spec/portal-apps.md` — Part B §2.2 (the new persist
rule), §3.2a, §4.2 (`unassigned` filter), §4.4 (`unassignedUsers`, the new
route), §9 scenarios 9 and 10, §11 P6. Read `CLAUDE.md` (testing policy,
mutation check) and `CONVENTIONS.md` §2/§3/§6 first. Go
(`/Users/andrewgraaff/Developer/flowcatalyst-go`, read-only) is evidence only.

Build: `export JAVA_HOME=$(mise where java)`;
`mvn -q -B -pl server -am test -Dtest='<pattern>' -Dsurefire.failIfNoSpecifiedTests=false`.
Never `mvn install`. If an error doesn't yield to one clear fix, stop and
report it with your diagnosis.

## 1. P6 — persist deletes only explicitly revoked grants (§2.2)

Today `PortalIdentityRepository.persist` → `syncGrants` deletes every
`portal_identity_apps` row not in the loaded `apps`, so a grant another
request added between our load and our save is silently lost. Change:

- `PortalIdentity` carries the app ids revoked since load — a new component
  `Set<String> revokedAppIds` (immutable, never null, empty on every load and
  on `create`). `revoke(appId)` removes the grant **and** records the id;
  `grant(appId, …)` of a recorded id un-records it (and is still a no-op if
  held). Every other transition carries the set through.
- `persist`: delete only `revokedAppIds` rows for the resolved id, then
  insert every grant in `apps` `ON CONFLICT DO NOTHING` (unchanged).
- Test = §9 scenario 10 at the repository level, with two real loads: copy X
  loaded holding {A}; grant B through a second loaded copy and persist it;
  X grants C, revokes A, persists ⇒ the table holds exactly {B, C}. The
  existing revoke-then-persist and racing-ensure tests must still pass.

## 2. `AssignUnassignedToApp` (§3.2a) — `portalidentity.operations`

A `TxOperation` (template: `portalapp.operations.DeletePortalApp`), command
`AssignUnassignedToAppCommand(clientId, portalAppId)`, both required
(`TARGET_REQUIRED`). App exists + same client (`resourceNotFound("PortalApp", id)`),
active (400 `PORTAL_APP_INACTIVE`, the existing message). Load the client's
identities with no grant (a repository method: `NOT EXISTS` on
`portal_identity_apps`, `ORDER BY created_at, id`), grant each with source
`ADMIN`, persist, emit the existing `PortalIdentityAppGranted` — all in the
one transaction. Status untouched. Result record `(appId, appCode,
List<String> identityIds)`. `Authorize.publicAccess()`; the controller gates.

## 3. HTTP

- `POST /api/portal-apps/{id}/assign-unassigned` (`assignUnassignedPortalUsers`)
  in `PortalAppApi`: body `{clientId}` (blank ⇒ 400 `CLIENT_ID_REQUIRED`),
  manage gate, 200 `{"portalAppCode": …, "assigned": n}`. Lockfile shapes:
  `AssignUnassignedBody`, `AssignUnassignedResponse`.
- `GET /api/portal-apps`: `unassignedUsers` (count of the client's identities
  with no grant) **only when `clientId` is given**, absent otherwise.
- `GET /api/portal-users`: `unassigned=true` adds the `NOT EXISTS` condition
  to `PortalIdentityRepository.search` (a new `SearchFilter` component);
  with a non-blank `portalAppCode` ⇒ 400 `FILTER_CONFLICT`, message
  `unassigned and portalAppCode cannot be combined`, checked before the app
  code is resolved.

## 4. Tests (behaviour, not existence)

§9 scenario 9 exactly, through the API: two users with no app (one
suspended) and one holding app B ⇒ `unassignedUsers = 2` and the filter
finds 2; filter + `portalAppCode` ⇒ 400 `FILTER_CONFLICT`; assign A ⇒ the
two gain A (assert `portal_identity_apps` rows with source `ADMIN`), the
suspended one is still `DISABLED`, the B user has no A, `unassignedUsers`
becomes 0, a second run answers `assigned: 0`; inactive app ⇒ 400
`PORTAL_APP_INACTIVE`. Operation test: one `app-granted` `msg_events` row
**and** one `aud_logs` row per assigned identity, none for the B user. A
view-only caller gets 403 on the route. `unassignedUsers` absent when
`clientId` is omitted (anchor).

Mutation check — list the assertion that kills each: P6 back to "delete
not-in-set"; `grant` not un-recording a pending revoke; drop `NOT EXISTS`
(assign everyone); skip the inactive check; count `unassignedUsers` across
all clients; `FILTER_CONFLICT` check removed.

Then `LockfileCoverageTest` (253/253) and the full server suite once. Report:
files, routes, mutation table, full-suite result, spec ambiguities.
