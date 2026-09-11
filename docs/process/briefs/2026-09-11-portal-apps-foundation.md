# Brief — portal apps, unit F (foundation): schema, lockfile, domain core

You work in `/Users/andrewgraaff/Developer/flowcatalyst-javalin` on branch
`portal-apps` (already checked out, **do not switch branches, do not
commit** — the orchestrator reviews and commits). `../flowcatalyst-go` is
read-only reference.

Read first: `CLAUDE.md` (testing policy — mandatory), `CONVENTIONS.md` §2, §6,
§8, `docs/database.md`, and **`docs/spec/portal-apps.md`** (Part A = Java
decisions J1–J10, Part B = the contract; section numbers below are Part B's).

Build: `export JAVA_HOME=$(mise where java)`; from the repo root,
`mvn -q -B -pl server -am test -Dtest='<pattern>' -Dsurefire.failIfNoSpecifiedTests=false`.
Never hardcode a JDK path. You are the only agent building right now.

Three more units (A: portal-users API; B: portal-apps API + OAuth-client
wire; C: login gate + profile-only filter) start from your result, so the
domain core must be complete, tested, and shaped exactly as below — they will
code against these names.

## 1. Lockfile

Copy `../flowcatalyst-go/api/openapi.lock.json` over
`server/src/main/resources/openapi/openapi.lock.json`; write `2fe6bf0` to
`openapi.lock.source-commit`. `LockfileCoverageTest` will then report the six
new operations as unimplemented — **expected, leave it**; every other test
that reads the lockfile must stay green (if request-schema validation now
rejects something an existing test sends, report it, don't paper over it).

## 2. Schema (spec §1)

- `server/src/main/resources/db/migration/V9__portal_apps.sql` = the
  `-- +goose Up` section of `../flowcatalyst-go/internal/migrate/sql/053_portal_apps.sql`,
  byte-identical (comments included, no Down), plus a two-line header comment
  in the style of V2–V7 naming Go 053. Add it to `db/migration.index`.
- Re-capture the Go schema fixture at Go HEAD and regenerate the fingerprint,
  exactly as commit `dd1874b` did for 046–052 (read that commit:
  `git show dd1874b -- server/src/test docs/database.md tools/`). Update
  `GoAdoptionTest` (goose rows through 053, V9 a no-op on a Go database) and
  `MigratorTest` / `IndexedMigrationsTest` counts.
- Regenerate jOOQ: `mvn -pl server -Pjooq-codegen process-test-classes`, then
  `tools/jooq-verify.sh` must pass.

## 3. TSID

`EntityType.PORTAL_APP("pta")` next to `PORTAL_USER`.

## 4. `io.flowcatalyst.platform.portalapp` (new package, J1)

- `PortalAppCode` — parser record (template: other `*Code` parser records,
  e.g. `serviceaccount.ServiceAccountCode`): `parse(String)` trims +
  lower-cases (Locale.ROOT) then validates `^[a-z0-9][a-z0-9_-]{0,99}$`;
  invalid ⇒ `UseCaseException.validation("CODE_INVALID", "code must be 1-100
  lower-case letters, digits, '-' or '_', starting with a letter or digit")`.
  A static `normalize(String)` (trim + lower, no validation) for lookups.
- `PortalApp` record `(id, clientId, code, name, description, active,
  createdAt, updatedAt)` implementing `HasId`; `description` null ⇔ blank;
  `code` stored normalised. `create(clientId, PortalAppCode, name,
  description)` (new `pta` id, active, trims name); `update(String name,
  String description, Boolean active)` — each argument `null` ⇒ unchanged,
  description trimmed and blank ⇒ `null`, code never changes (spec §3.5).
- `PortalAppRepository implements Persist<PortalApp>` (jOOQ over the
  generated `PORTAL_APPS`, the shape of `PortalIdentityRepository`):
  `findById`, `findByClientAndCode(clientId, code)` (normalises),
  `findByOAuthClientId(String oauthClientId)` — the §2.4 join on
  `oauth_clients.client_id` (the OAuth `client_id` string, **not** the row
  id), `Optional.empty()` for an unlinked/legacy client;
  `findByClient(clientId)` and `findAll()` ordered by name;
  `userCounts(Collection<String> appIds) → Map<String,Integer>` (grant rows
  per app, one query; absent ⇒ 0); `persist` (upsert by id; `code` and
  `client_id` absent from the `SET` list); `delete`.

## 5. `PortalIdentity` extension (§2.2, §2.3, J3)

- New records/enums in `io.flowcatalyst.platform.portalidentity`:
  `PortalAppGrant(String appId, PortalAppGrantSource source, Instant grantedAt)`,
  `PortalAppGrantSource { INVITE, JIT, ADMIN }` with `parse`,
  `PortalUserState { INVITED, INVITE_EXPIRED, ACTIVE, SUSPENDED }`.
- `PortalIdentity` gains `List<PortalAppGrant> apps` (immutable copy, never
  null, ordered by `grantedAt`), `Instant invitedAt`, `Instant inviteExpiresAt`.
  Transitions: `hasApp(appId)`, `grant(appId, source)` (idempotent — returns
  `this` when held), `revoke(appId)` (idempotent), `state(Instant now)` per
  the §2.3 table, first match wins. Every existing transition carries the new
  components through unchanged.
- `PortalIdentityRepository`:
  - every read loads the grants with the identity (one extra query per
    identity is fine for single reads; the search below must batch them —
    one query for the page, not N);
  - `persist` keeps its upsert, adds `RETURNING id`, then **syncs** the grant
    rows to exactly `apps` keyed by the returned id (delete rows not in the
    set, insert missing `ON CONFLICT DO NOTHING`) — so a racing ensure
    resolves to the winner's id;
  - `invited_at` / `invite_expires_at` are **not** in the upsert at all;
    `markInvited(String id, Instant invitedAt, Instant expiresAtOrNull)` is a
    direct autocommit `UPDATE … SET invited_at, invite_expires_at, updated_at = now()`;
  - `search(SearchFilter filter) → SearchPage(List<PortalIdentity> items, long total)`
    with `SearchFilter(String clientId, String q, String portalAppId, int page, int size)`
    implementing §4.2's SQL: conditions added only when present (J10),
    `q` trimmed + lower-cased + LIKE-escaped (`\`→`\\`, `%`→`\%`, `_`→`\_`)
    + `%`, `email LIKE ? ESCAPE '\' OR lower(name) LIKE ? ESCAPE '\'`,
    `EXISTS` on `portal_identity_apps` for the app filter, `ORDER BY
    created_at DESC, id DESC`, `LIMIT/OFFSET`; `total` = `count(*)` with the
    same WHERE. Clamping page/size is the API's job (unit A), not yours.
  - keep `findByClient` for now (unit A retires it).

## 6. `OAuthClient.portalAppId` (J4)

Add `String portalAppId` to the `oauthclient.OAuthClient` record right after
`portalClientId` (null ⇔ blank), carried by every transition, read/written by
`OAuthClientRepository`. Add the invariant next to wherever
`PORTAL_API_ACCESS_CONFLICT` is enforced today: `portalAppId != null &&
portalClientId == null` ⇒ `validation("PORTAL_APP_REQUIRES_PORTAL_CLIENT",
"a portal app can only be linked to a portal client (portalClientId)")`.
Fix every constructor call site (main and test). **No wire change** — the
request/response DTOs are unit B's.

## 7. Tests (assert behaviour — CLAUDE.md)

- `PortalAppCodeTest`: the §9.2 table as a `@ParameterizedTest`.
- `PortalIdentityTest`: the §9.1 state table at a fixed `now` (include "password
  set after the link expired ⇒ ACTIVE", "SSO invite (expires null) never
  expires", "DISABLED beats everything"); grant/revoke idempotence.
- `PortalIdentityRepositoryTest`: grants round-trip in `grantedAt` order;
  revoke then persist ⇒ the row is gone from `portal_identity_apps` (assert
  the table, not the entity); a second identity object with a different id
  for the same (client, email) persists its grants under the **stored** id;
  `markInvited` round-trip and not clobbered by a later `persist`; search: the
  §9.3 table (`PAT` / `jonas` / `jones` / `under_` / `u%` / app filter /
  page 1 size 2 ⇒ 1 row, total 3) on your own fresh client.
- `PortalAppRepositoryTest`: `findByOAuthClientId` returns the linked app and
  empty for an unlinked portal client; `userCounts`; deleting an app cascades
  its grants; deleting an identity cascades its grants.
- The OAuth-client invariant.
- Then run the full server suite once; report any failure outside
  `LockfileCoverageTest`.

**Mutation check**, and list which assertion kills each: remove the grant
delete in the sync; drop the `ESCAPE` escaping; make search `%q%` instead of
`q%`; swap rules 2 and 3 of the state table; key the grant sync by the
entity's id instead of the `RETURNING` id.

## Report

Files touched; the migration/fixture procedure you used; test counts;
the mutation table; anything in the spec you found ambiguous or wrong.
