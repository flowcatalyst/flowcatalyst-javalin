# Java mirror of the Go partner-users batch: reset-page account email, match emails, partner visibility, developer-users scoping, partner tokens (2026-11-05)

Source: Go commits `5b037103` (password-reset validate returns the account email), `12fa50de` (match a
pasted list of emails), `889dd243` (client administrators see the partners granted their client),
`0e75b790` (developer-users scoped like the users list), `b25a3494` (partner token test); verified
only: `d8db4c1a` (bearer `clients` claim split to bare ids), `6ec301af` (Go test-fixture flake).
Predecessor: `2026-10-20-identity-and-sync-fixes.md`. **Uncommitted** in the working tree; the
orchestrator reviews and commits. The Go console pages (`/ui/users/match-emails`, the templ reset
page) are Go-only by design and not mirrored.

## Was the defect present in Java?

| Item | Present in Java? |
|---|---|
| A. validate names the account | **Yes** (feature gap): `GET /auth/password-reset/validate` answered `{valid, reason, requiresFactor, portal?}` with no `email`, and the SPA reset page had no username field, so a password manager guessed the account. |
| B. match emails | **Yes** (feature gap): no route, no parser, no SPA dialog. |
| C. partners visible to granted client admins | **Yes, and stricter than Go.** The users list hid every clientless principal from a non-anchor (Go had the same). Java's by-id read and its `/{id}/…` sub-reads went through `Access.requireReadable` → `Checks.canAccessScope`, which refuses a clientless principal to a non-anchor (Go is lenient on the clientless case), so in Java a client admin could not even open a partner by id. No `grantedClientIds` narrowing anywhere. Mutations on non-CLIENT principals were already refused for non-anchors (`Access.blockNonClientTarget`, from `requireManageable` / `requireUserAdmin` / `requireRoleAssigner`); confirmed and now pinned by a test, not changed. |
| D. developer-users visibility | **Yes**: `listDeveloperUsers` returned every developer to any `USER_VIEW` holder. Go's third finding (`FindByRole` did not hydrate client grants) was **not** present: Java's `findByRole` goes through the shared `findMany`, which hydrates roles, grants and application access in one place (the "one hydration path" rule in `CONVENTIONS.md` §8). Now pinned by the partner-developer assertions. |
| E. partner tokens | Behaviour already right (`TokenIssuer.Authority.full` → `ClaimShapes.clients` with store-backed `ClaimLabels`); **no test** followed a TO_PARTNER flip to the token claims. Ported. |
| `d8db4c1a` bearer `clients` split | **No.** `Authenticator` (line ~233) runs the claim through `ScopeClaim.parse`, which splits `"{id}:{label}"` to the id and recognises `"*"`. Unchanged. |
| `6ec301af` "last all-applications admin" race | **No.** Java has no last-all-applications-admin guard (see Out of scope) and surefire runs test classes sequentially, so neither race can occur. |

## Changes

Server (`server/src/main/java/io/flowcatalyst/platform/…`):

- `passwordreset/PasswordResetApi.java`: `validate` adds `email` on a valid token through the new
  `tokenEmail(State, ResetToken)` — the principal's email, or the portal identity's (via
  `PortalPasswords.find`) on a `ptu_` token; best-effort (lookup error → WARN + field omitted; blank
  → omitted).
- `principal/EmailList.java` (new): `record EmailList(emails, invalid)` with `static parse(String)`,
  the port of Go `ParseEmailList` (rule table in its doc).
- `principal/operations/Access.java`: new `visible(AuthContext, Principal)` — the one listing rule
  (anchor → all; homed → caller can access the home client; clientless `PARTNER` → caller can access
  any granted client; other clientless → hidden). `requireReadable` additionally admits a clientless
  principal that `visible` admits (the partner case), so the by-id read and its sub-reads serve the
  read-only partner view.
- `principal/api/PrincipalApi.java`: list filters through `Access.visible`; new
  `POST /api/principals/match-emails` (`routes.post`, i.e. `API_READ` — a read, no operation) with
  `MatchEmailsRequest` / `MatchEmailsResponse` and the limits `MATCH_EMAILS_MAX_TEXT_BYTES = 200_000`
  (UTF-8 bytes, as Go's `len`) and `MATCH_EMAILS_MAX = 1000`; `listDeveloperUsers` filters through
  `Access.visible`; `PrincipalResponse.narrowedFor(AuthContext)` filters `grantedClientIds` to the
  caller's accessible clients for non-anchors, applied by list, match-emails, developer-users and
  get-by-id.
- `server/src/main/resources/openapi/openapi.lock.json`: Go's `MatchEmailsRequest`,
  `MatchEmailsResponse` and `/api/principals/match-emails` `post` (`matchPrincipalEmails`) copied
  verbatim minus `x-sdk` (the Java lock carries none); file re-serialised byte-identically otherwise
  (sorted keys, 2-space indent, Go's `<`/`>`/`&` escapes). `PrincipalResponse` was
  already identical in both locks.

Tests: `principal/EmailListTest.java` (new); `principal/api/PrincipalApiTest.java`;
`passwordreset/PasswordResetApiTest.java` (also the exact-JSON validate assertion now includes
`email`); `principal/operations/PrincipalOperationsTest.java`; `shared/openapi/LockfileTest.java`
(paths 187 → 188, operations 253 → 254).

SPA (`frontend/src/…`):

- `api/auth.ts` (`validateResetToken` result gains `email?`), `pages/auth/ResetPasswordPage.vue`
  (read-only `<InputText id="username" name="username" type="email" autocomplete="username">`
  inside the form before the password fields when `email` is present; comment updated).
- `api/users.ts` (`MatchEmailsResponse` alias, `usersApi.matchEmails(text)`),
  `components/UserMatchEmailsDialog.vue` (new, Go's file verbatim), `pages/users/UserListPage.vue`
  ("Match emails" dialog button in the header).
- `pages/users/UserDetailBody.vue`: `readOnly` (`clientScoped && user.scope !== 'CLIENT'`) hides the
  info card's whole `#actions` slot, Assign Roles (folded into `canAssignRoles`), Manage
  Applications, Grant Application Access and the Account Actions section, and skips the autoEdit
  deep link; in `clientScoped` mode an all-applications user shows "This user has access to every
  application."
- `pages/users/ClientUsersPage.vue` (partner rows: "Partner" Tag in the Client column, no edit
  pencil; comment), `pages/users/ClientUserDetailDrawer.vue` (comment).
- `api/generated/index.ts`, `api/generated/types.gen.ts`: regenerated with `openapi-ts` from the Java
  lock (the repo's process). **The regeneration also picked up earlier lock changes that had never
  been regenerated** (event-ingest `source` optional with its description, sync `passwordHash` /
  `passwordHashIgnored` / skipped-roles descriptions). Type-check is clean with them; the
  orchestrator may prefer to keep them (the generated file now matches the lock) or split them out.

Docs: `docs/spec/auth-identity.md` §8.3 (`email`), `docs/spec/principal.md` §3 (match-emails and
developer-users rows, the listing-visibility table, grant narrowing, match-emails semantics).

## Tests (mutation-checked)

Each mutant was applied to the main code, the named test run through the reactor
(`mvn -o -pl server -am test -Dsurefire.timeout=300 -Dtest=…`), and the file restored by `cp` from a
backup.

| # | Mutant (main code) | Pinning test / assertion | Result |
|---|---|---|---|
| M1 | `validate` never adds `email` | `PasswordResetApiTest#validateNamesTheAccountByEmailForAUserAndAPortalToken` — user token's `email` | killed |
| M2 | portal token looked up as a principal | same test — portal token's `email` is the identity's address | killed |
| M3 | lookup failure not swallowed (catch narrowed) | `#validateOmitsTheEmailWhenTheLookupFails` — still `valid:true`, no `email` | killed |
| M4 | a comma ends a display name | `EmailListTest` rows "outlook last-first names", "plain address before a named one" | killed |
| M5 | `Access.visible` drops the partner branch | `PrincipalApiTest#partnerVisibleToGrantedClientAdmins`, `#developerUsersScopedLikeTheUserList` | killed |
| M6 | partner needs *all* grants accessible (`allMatch`) | `#partnerVisibleToGrantedClientAdmins` — partner granted A and B is visible to A's admin | killed |
| M6b | any clientless principal reached through grants (scope check dropped) | `#developerUsersScopedLikeTheUserList` — an anchor developer with a grant row to the caller's client stays hidden (fixture added after the first run survived) | killed |
| M7 | no narrowing | `#partnerVisibleToGrantedClientAdmins` — match/list/get carry `[clientA]` only | killed |
| M7b | narrowing applied to anchors too | — | survived: **equivalent** (`canAccessClient` is true for every client for an anchor) |
| M8 | match ignores visibility | `#matchEmailsClientAdminCannotSeeOtherClient` — other client's and anchor users are `unmatched` | killed |
| M9 | text limit counted in chars | `#matchEmailsLimits` — 100001 × `é` (200002 bytes) is `TEXT_TOO_LONG` | killed |
| M10 | only-invalid input is `EMAILS_REQUIRED` | `#matchEmailsLimits` — `"junk"` is 200 with `invalid:["junk"]` | killed |
| M10b | `TOO_MANY_EMAILS` at exactly 1000 | `#matchEmailsLimits` — 1000 addresses is 200 | killed |
| M11 | match drops the `type == USER` check | `#matchEmailsAnchorSeesAllInInputOrder` | survived: redundant check (see Not verified) |
| M12 | developer-users unscoped | `#developerUsersScopedLikeTheUserList` — client admin sees exactly `mine` + `partner` | killed |
| M13 | developer-users not narrowed | same — partner carries `[clientA]` | killed |
| M14 | `requireReadable` back to the old rule | `#partnerVisibleToGrantedClientAdmins` — by-id 200 for the granted partner | killed |
| M15 | `text` parsed before `emails` | `#matchEmailsAnchorSeesAllInInputOrder` — order `c, b, a` | killed |
| M16 | by-role lookup hydrates no grants (Go's `0e75b790` defect) | `#developerUsersScopedLikeTheUserList` — partner developer visible with `[clientA]`; anchor sees both grants | killed |
| M17 | `Authority.full` gives a PARTNER no clients | `PrincipalOperationsTest#toPartnerTokensCarryEveryGrantedClient` — `clients` = old home + new, as `id:identifier` | killed |

## Deviations from Go

- **By-id reads stay strict except for the partner case.** Go's by-id read and `/{id}/…` sub-reads
  let any `user:view` holder read any clientless principal (anchors included). Java keeps its
  out-of-scope-is-not-found rule (PR-4) and only adds the visible-partner row, so a client admin
  reads a partner granted its client and nothing else clientless. Needed so the SPA's read-only
  partner view (roles, application access) loads.
- `PrincipalResponse.narrowedFor` is applied where the brief names it (list, match-emails,
  developer-users, get-by-id), as in Go; other routes returning a `PrincipalResponse` (update,
  add/remove role, set-client-association, create-user) are anchor-only or CLIENT-only for a
  non-anchor, so they never show a non-anchor a partner.
- `EmailList` trims with the Unicode `White_Space` set (as Go's `strings.TrimSpace`), not
  `String.strip`. Lower-casing is `toLowerCase(Locale.ROOT)` vs Go's `strings.ToLower` (differs
  only for context-sensitive letters such as final sigma).
- Match-emails reads `findAll` like Go; when two USER rows share an address case-insensitively
  (legacy data) the last one read wins, in Java's newest-first order.
- The partner token test builds the id token's `clients` from `Authority.full(...).clients()` (the
  same `ClaimShapes.clients` + `ClaimLabels` path `InteractiveMint` uses) rather than through an
  `OAuthState`; the claim content under test is identical.
- The Java lock omits Go's `x-sdk: admin` on the new operation (the Java lock carries no `x-sdk`
  anywhere). The SDK copies of the lock (`clients/*/openapi`, `sdk/openapi`) were not refreshed
  (PENDING H).

## Out of scope (noticed, not changed)

- **Last-all-applications-admin guard not mirrored.** Go `9966a2fa` / `d1099d0b` (2026-10-09,
  `internal/platform/principal/last_admin.go`: never demote the last all-applications
  administrator; `confirmSelfRestriction` on application-access) has no Java counterpart and is not
  listed in `PENDING.md`. The Java SPA's `usersApi.assignApplicationAccess` also lacks Go's
  `confirmSelfRestriction` parameter.
- `docs/spec/principal.md` §3's `GET /api/principals/{id}` row still says a non-self, out-of-scope
  read answers 403 `FORBIDDEN`; the code answers `Principal_NOT_FOUND` (PR-4, ruled 2026-09-01). Left
  as found.
- Bearer path: Java keeps the anchor's `"*"` in `AuthContext.clients` (`ScopeClaim.WILDCARD`), Go now
  drops it; `canAccessClient` short-circuits on the anchor tier either way, so no behaviour differs.

## Not verified

- The SPA was type-checked (`vue-tsc -b`, clean) and linted (`oxlint`, 0 errors; the one warning is
  pre-existing in `composables/useReturnTo.ts`); it was not run in a browser, and no SPA unit test
  covers the reset page's username field, the match dialog or the read-only partner view.
- Go's match-emails "SERVICE principal is never a match" is pinned, but in Java the
  `type == USER` check in `matchEmails` is redundant with the repository (a SERVICE row is hydrated
  without a user identity, so `email()` is `null`); mutant M11 shows the check alone is not what
  the test pins.
- `fcdev` was not started; the embedded SPA was not rebuilt (`make frontend`).
