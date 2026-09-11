# Go fix list — found porting portal apps (Go `373fe93`/`2fe6bf0`), 2026-09-11

Five Go-side defects found while porting and cross-checking the portal-apps
feature against Go `2783ff9`. None is patched here: the first two are in the
Go-authored SPA (embedded verbatim in Java, so they bite both servers equally),
the last three are server-side and allow-listed in `parity/expected-diffs.json`
by these numbers. Spec: `docs/spec/portal-apps.md` Part A.

## P1 — the "New Portal App" button never renders, for anyone (SPA)

`frontend/src/pages/portal/PortalAppsPage.vue` gates every write control on
`canManage = permissionsStore.hasPermission("platform:iam:portal-user:manage")`,
but `stores/permissions.ts`'s `setPermissions` has **no caller anywhere in the
SPA**, so `userPermissions` stays `[]` and `hasPermission` is always false (it
could only pass on a literal `"*"`, which no seeded role emits). Portal apps
cannot be created, edited or deleted through the UI on either server. Fix:
derive from `authStore.user.permissions` (as `canAccessPath` already does) or
populate the store at session load. Pinned as a `test.fixme` in
`e2e/tests/identity.spec.ts` (the flow is written and runs once this is fixed).

## P2 — a cold page load skips the route-permission guard (SPA)

`router/index.ts:617` registers `createRoutePermissionGuard()` as a global
`beforeEach`, which runs before the per-route `authGuard` (`beforeEnter`). On
a cold load `authStore.isAuthenticated` is still false, so the guard's first
branch ("unauthenticated — authGuard will handle") calls `next()`; `authGuard`
then hydrates the session but never re-runs the permission check. So a
role-less user who types `/dashboard` stays there instead of being sent to
`/profile` (spec §7). No data leaks — the server's profile-only gate refuses
every API call the page makes — but the promised redirect does not happen,
and a cold load is the only way such a user reaches another route. Fix: await
the session check in the global guard before deciding. `test.fixme` in the
same e2e file.

## P3 — the portal access token has no `azp` (server)

`internal/platform/auth/oauthapi/portal_token.go` mints the portal identity's
access token with `GenerateIdentityAccessToken(synth)`; every other
client-bound identity token uses `GenerateIdentityAccessTokenFor(p, clientID)`
and so carries `azp`. Java stamps it (spec J11). Fix: use the `…For` variant
with `client.ClientID`.

## P4 — the unsupported-scheme message renders `"ref"://` (server)

`internal/platform/shared/encryption/secretref.go:97` formats
`%w %q://` — the scheme is quoted *before* `://` is appended, giving
`unsupported secret-manager scheme "ref"://; supported: …, literal: …`. It
also lists `literal:` among the secret-manager schemes, which it is not (it is
the dev bypass). Java answers the same code (`UNSUPPORTED_SECRET_SCHEME`) with
`oidcClientSecretRef: unsupported secret-manager scheme "ref://"; supported:
aws-sm, aws-ps, gcp-sm, vault, env (…)` (spec J12). Fix: `%q` over
`scheme+"://"`, and drop `literal:` from the list.

## P5 — a portal id_token's `updated_at` is the mint time (server)

`portal_token.go` builds the synthetic `principal.Principal` without an
`UpdatedAt`, and `authservice.idTokenClaims` replaces a zero value with now —
so every portal login tells the RP the profile just changed, which is exactly
what the platform id_token's `updated_at` rule exists to prevent. Java carries
the identity's own `updated_at` (spec J11). Fix: `UpdatedAt: ident.UpdatedAt`
on the synth.
