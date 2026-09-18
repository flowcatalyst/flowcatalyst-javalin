# SDK auth fixes: PHP and TypeScript (reported 2026-09-18)

Seven defects were reported by the Integral team. Verified against the SDK
sources: **five confirmed, one disproved, one partly**. The Java SDK (`sdk/`)
has none of these code paths — no OIDC login, no JWT handling, no principal,
no guards, no session store — so it needs **no change**; say so in the report
rather than inventing one.

Both SDKs live in two identical copies (`clients/<sdk>` in
`flowcatalyst-javalin` and in `flowcatalyst-go`). Work in whichever copy has
its dependencies installed, then copy `src/**` and `tests/**` to the other so
`diff -rq` is empty. Do not bump VERSION, do not tag, do not release.

Platform facts these fixes depend on (verified, do not re-litigate):
- An interactive login's access token is an **identity** token: `roles`,
  `clients`, `applications` are empty and `token_use = "identity"`
  (`TokenIssuer.identityAccessToken`, `InteractiveMint`). Only an `apiAccess`
  client gets authority in the access token.
- The **id_token** carries `roles`, `applications`, `clients`,
  `all_applications` (`TokenIssuer.idToken`), and the refresh grant mints a
  **fresh id_token** whenever the original login's scope contained `openid`
  (`OAuthTokenApi` refresh path). The PHP SDK's default scope is
  `openid profile email` and it already stores the refreshed id_token.
- `clients` and `applications` claims are `"{id}:{code}"` pairs, or `["*"]`
  (`ClaimShapes`).
- JWKS: `{platform base}/.well-known/jwks.json`, also advertised as
  `jwks_uri` in `/.well-known/openid-configuration`.

## PHP (`clients/laravel-sdk`)

### P1 — verify the id_token (security)
`OidcAuthController::parseIdToken` (~:370-406) decodes the id_token without
verifying anything but `exp`, `sub` and an email-ish claim; its own docblock
says so. The whole principal — roles included — is built from it.

Verify it the way the access token already is: reuse `AccessTokenValidator` /
`JwksCache` (RS256 pinned, JWKS from discovery, kid lookup). Required checks:
**signature**, `iss` == the configured issuer, `aud` contains the SDK's
`client_id`, `exp`, `nbf` (same 60 s leeway the access-token path uses), plus
the existing `nonce` check. A failure is a login failure — the existing
error path, never a silent fallback to the unverified claims.

### P2 — the bearer check must verify `iss` (security)
`AccessTokenValidator` verifies signature/`exp`/`nbf`/`sub` but never
compares `iss`, though its own docblock claims it does (:17). Compare it to
the configured issuer and reject a mismatch. Keep `aud` optional exactly as
today (`expected_audience`, unset by default) — do not turn it on here.

### P3 — roles survive a refresh
`TokenRefresher` (~:50-63) rebuilds the user from the **access token**, which
for a normal client is authority-free, so roles/clients/applications go empty
about an hour into a session and stay empty (the handler persists them).
Rebuild from the **verified id_token** the refresh returned (it is already
stored at :65-66), keeping the identity fields as today. If the refresh
returned no id_token, keep the **previous** principal's roles/clients/
applications rather than overwriting them with empties — never silently
downgrade authority.

### P4 — one return-URL parameter, validated (security)
`OidcAuthController` reads `return_url` (:79, :258) and redirects to it
unvalidated (:188, :260) — an open redirect. The guards
(`RequireSession`/`RequireAuth`) send `returnTo`, which nothing reads, so the
deep link is also silently dropped.

- Accept **`returnTo`** (what the guards send) and keep reading `return_url`
  as a deprecated alias so existing links still work.
- Validate both: accept only a **relative path** starting with a single `/`
  (reject `//host`, any scheme, any absolute URL); anything else falls back
  to the configured post-login redirect. Mirror TS's `sanitizeReturnTo`
  (`typescript-sdk/src/fastify/plugin.ts:462-474`).
- Apply the same validation to the refresh route's redirect (:258-260).

### P5 — `hasApplicationAccess` matches codes
`FlowCatalystAuthenticatable::hasApplicationAccess($code)` compares the
passed **code** against the **ids** list, so it is always false against a
real token (`"{id}:{code}"` pairs). Match against the **codes** and the
**ids** (either half), and return true when the principal has all
applications (`hasAllApplications()`), not only on the clients wildcard
(`hasFullAccess()`).

## TypeScript (`clients/typescript-sdk`)

### T1 — `canAccessClient` matches either half
`principal.ts:83,108-110` tests the raw `clients` claim, whose entries are
`"{id}:{code}"` pairs, so `canAccessClient("clt_a")` is false for
`"clt_a:acme"`. Parse the claim the way `applications` already is
(`claims.ts:177`, `parseApplicationsClaim`) and match an id **or** a code;
keep the `"*"` anchor branch. Expose the parsed halves on the principal
snapshot if that is what the applications side does — match the existing
shape, do not invent a new one.

### T2 — expired sessions are actually reaped
`PgSessionStore.reapExpired()` exists (`pg-session-store.ts:94-100`) but
nothing calls it, and `write()` mints a **new sid every call** (:70) —
including on every mid-session refresh (`plugin.ts:239`) — without deleting
the row it supersedes, so one long session leaves one orphan row per hour.

- `write()` deletes the superseded sid in the same round trip (it knows the
  old sid; if it does not, thread it through) so rotation stops orphaning.
- Give the store an opt-out-able periodic reap: an interval (default, say,
  hourly, unref'd so it never holds the process open) started when the store
  is wired into the plugin, plus a `close()`/dispose that clears it. Reads
  already filter on `expires_at`, so this is about table growth only.
- Document it in the SDK README where the store is described.

### Not a defect — do not "fix"
TS reads roles from the **id_token** already (`claims.ts:140`,
`flow.ts:149-156`), including on refresh. The reported "TS takes roles from
the access token" is disproved. Leave it alone.

## Tests — each must fail under its mutant (CLAUDE.md policy)

PHP (phpunit, `tests/`): no tests exist today for any of P1–P4.

| # | Assert | Mutant |
|---|---|---|
| P1a | a login whose id_token is signed by an unknown key is refused; no session is created | skip the signature check |
| P1b | an id_token with the wrong `iss`, and one with the wrong `aud`, are each refused | drop that check |
| P1c | a correctly signed id_token logs in and the principal carries its roles | — (guards the happy path) |
| P2 | a bearer access token with a foreign `iss` is rejected; the same token with the right `iss` is accepted | drop the `iss` comparison |
| P3a | after a refresh whose id_token carries roles, the principal still has them | rebuild from the access token |
| P3b | after a refresh with **no** id_token, the previous roles survive | overwrite with the access token's empties |
| P4a | `returnTo=/deep/link` lands there; `return_url=/deep/link` (deprecated alias) still works | ignore `returnTo` |
| P4b | `returnTo=https://evil.test/x` and `returnTo=//evil.test/x` both fall back to the configured redirect | drop the validation |
| P5 | `hasApplicationAccess('integral')` is true for a claim `"app_01H…:integral"`, false for an unrelated code; true when the principal has all applications | compare against ids only |

TypeScript (`node --test`, `tests/fastify/`):

| # | Assert | Mutant |
|---|---|---|
| T1a | `canAccessClient("clt_a")` and `canAccessClient("acme")` are both true for `["clt_a:acme"]`; an unrelated id/code is false; `"*"` still grants | compare the raw pair |
| T2a | `write()` twice leaves exactly one row for that session (the superseded sid is gone) | drop the delete |
| T2b | `reapExpired()` removes only rows whose `expires_at` has passed, and the plugin wires a reaper that can be disabled | — plus: stop wiring it, assert the wiring test fails |

Existing tests that pass only because their fixtures use bare ids
(`FlowCatalystAuthenticatableTest`, `principal.test.ts`) must be updated to
the real `"{id}:{code}"` shape, and each change listed in the report.
