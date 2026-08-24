# `oauthapi` — three fixes to make in Go first, then port

Owner-approved 2026-08-24. All three live in
`../flowcatalyst-go/internal/platform/auth/oauthapi/` (plus one route move in
`internal/server/`). They are grouped because they touch the same package and
the same tests. **Make them in Go first**, then the Java port follows the
corrected behaviour rather than reproducing the defect and deviating from it.

Related: `docs/spec/auth-core.md` Q4 (corrected analysis), Q5, Q6.

---

## Fix 1 — `/oauth/userinfo` is unreachable for ordinary OIDC clients

**Defect.** `RegisterUserinfoRoutes` is mounted inside the Authenticator group
(`wire_routes.go:197-200`, group opened at :83). The middleware has one
hard-fail path (`middleware.go:89-92`): an explicit `Authorization: Bearer`
carrying a token it will not accept is 401'd before the handler. And
`middleware.go:187-189` refuses any token with `token_use=identity`.

An ordinary OIDC client — one **not** flagged `APIAccess` — receives exactly an
identity token from the `authorization_code` grant (`token_apiaccess.go:28-29`).
So the canonical relying-party sequence *authorization_code → access_token →
`GET /oauth/userinfo`* is rejected by the middleware in front of the handler's
own `validateBearer` (`userinfo.go:60-74`), which uses `s.Auth.ValidateToken`
and would have accepted it. This contradicts the design intent recorded at
`authservice.go:405-407`, which says the identity token's "only uses are proving
authentication at `/oauth/userinfo` and letting the relying-party app read the
principal's identity".

Latent because `APIAccess` clients get `token_use=api` and sail through, and no
FlowCatalyst SDK calls userinfo (TypeScript only types `userinfo_endpoint` when
parsing discovery; Laravel and Java read identity from the `id_token`). Standard
third-party RPs are the population that hits it.

**Fix.** Move `RegisterUserinfoRoutes` from `registerPlatformAPI` into
`registerPublicRoutes`. Move the discovery routes (`/.well-known/openid-configuration`,
`/.well-known/jwks.json`) out as well — they are unauthenticated by definition
and gain nothing from the group; a caller that blanket-attaches a stale bearer
to a JWKS fetch currently gets a 401 instead of the keys.

**Regression test.** Mint an identity token for a non-`APIAccess` client via the
`authorization_code` grant, `GET /oauth/userinfo` with it as a Bearer, assert
200 and the expected claims — i.e. the exact sequence that 401s today.
`TestAuthenticatorRejectsIdentityBearer` stays as-is: the middleware's rule is
not changing, only which routes sit behind it.

**Do not change:** `/oauth/token`, `/oauth/introspect`, `/oauth/revoke` stay
where they are. `extractToken` (`middleware.go:129-132`) deliberately declines a
non-Bearer scheme, so Basic-authenticated introspect/revoke are unaffected, and
anonymous calls to any of them already work — the Authenticator attaches context
and calls `next`, it is not a gate.

---

## Fix 2 — `client_credentials` refuses Basic while discovery advertises it

**Defect.** `parseTokenRequest` (`token.go:132-145`) reads form values only and
never merges Basic credentials. `Token` authenticates the client via
`authenticateClient` (`token.go:233-236`, where Basic wins over body params) for
every grant **except** `client_credentials`, which authenticates inside its own
handler (`token.go:159-160`) and reads `req.ClientID` / `req.ClientSecret`
straight from the body (`token.go:326-334`). A standard client using
`client_secret_basic` for `client_credentials` therefore gets
`400 invalid_request "Missing client_id"` — while `discovery.go:58` advertises
`client_secret_basic` as supported.

**Fix.** Route `client_credentials` through the same client resolution as the
other grants: fall back to `basicAuthCreds(r)` when the body carries no
`client_id`/`client_secret`, keeping the existing precedence (Basic wins) and
the existing body-vs-Basic mismatch check (`token.go:207-209`).

Keep the developer-credential path intact: when the resolved `client_id` has the
`prn_` prefix and no `OAuthClient` row matches, the self-service developer
credential flow still applies (`token.go:341-346`).

**Rationale for not deleting Basic instead.** RFC 6749 §2.3.1 makes
`client_secret_basic` a MUST for an authorization server (`client_secret_post` is
the MAY); standard OIDC libraries default to it; and discovery has already
promised it, so withdrawing it is a breaking change for any client that read the
document. If the owner later confirms that no third party integrates directly,
removing Basic **and** its discovery advertisement together is a separate,
deliberate decision — not a side effect of this fix.

**Tests.** `token_test.go` already exercises Basic (`:65`, `:73`, `:94`); add a
`client_credentials` case authenticating purely with Basic, and one asserting a
body `client_id` that disagrees with the Basic identity is still rejected.

---

## Fix 3 — Basic-only clients evade the per-client rate limit

**Defect.** The per-client rate buckets are keyed on the body `client_id`, so a
client authenticating with Basic and no body `client_id` is limited by IP alone.
That is precisely the caller you most want throttled: brute-forcing client
secrets over Basic evades the per-client limiter.

**Fix.** Resolve the client identity from `basicAuthCreds(r)` *before* the
rate-limit decision, so Basic-authenticating and body-authenticating clients hit
the same bucket. Resolution for rate-limiting purposes must not itself become an
oracle — key the bucket on the presented `client_id` string, whether or not it
matches a real client, exactly as the body path does today.

**Test.** Repeated Basic-authenticated `client_credentials` attempts with a bad
secret hit the per-client 429 at the same threshold as the body-authenticated
equivalent.

---

## Fix 4 — cookie mint failure after a correct password answers 400 (Q10)

**Defect.** `internal/platform/auth/login/endpoint.go:500` answers
`httperror.BadRequest("MINT_FAILED", err.Error())` when the session cookie
cannot be minted *after* the password has already been verified. The request
was well-formed and the credentials were right; the failure is entirely
server-side. A 400 tells the caller to fix something that is not wrong, and
invites a retry that cannot succeed.

**Fix.** Answer 500. `httperror` currently exposes only `Forbidden`,
`BadRequest` and `NotFound` — add an `Internal(code, msg string, cause error)`
constructor wrapping `usecase.Internal` (which `Status` already maps to 500),
or construct the `usecase.Error` directly at the call site.

**Do not leak the cause.** `err.Error()` must not go into the 500 body — log it
and return a fixed message, consistent with how the rest of the platform
handles internal failures.

**Owner ruling:** 2026-08-24. Outstanding in Go as of that date (the file is not
in the current working tree).

---

## Fix 5 — `defaultScopes` is a string on create, an array everywhere else (Q9)

**DONE in Go — verified 2026-08-24** at `b5b471d` (make it an array, with a
compat shim) and `f908c3b` (retire the shim and the legacy `scopes` alias),
branch `fix/oauth-endpoint-compliance`, working tree clean.

**Final contract.** `defaultScopes` is the single wire name for a client's
scope list, `{"items":{"type":"string"},"type":"array"}`, byte-identical on
`CreateOAuthClientRequest`, `UpdateOAuthClientRequest` and
`OAuthClientResponse`. The `scopes` alias is gone from both request DTOs
along with the precedence logic that chose between the two.

The defect this closed was worse than the asymmetry alone: a
read-modify-write round trip (GET a client, change a field, POST it back)
fed an array into a string field and silently corrupted the scope list.

### Correction 1 — the lenient decoder cannot work under huma

The recommendation here was a `ScopeList` type with a custom
`UnmarshalJSON` accepting either shape. **That is not implementable in this
codebase**, and the owner established it empirically rather than by
inspection: huma validates the parsed body against the registry schema
*before* unmarshaling into the Go struct, and that registry schema is the
same object the OpenAPI document is emitted from. Typing the field
`array<string>` in the document therefore also types it `array<string>` for
validation — a string body is rejected with `expected array at
body.defaultScopes` (422) and the decoder never runs.

What delivered the intent was normalising the raw JSON **ahead of**
validation: a `LegacyDefaultScopesCompat` middleware guarded on
`POST /api/oauth-clients`, leaving the document genuinely strict while the
string form kept working and appeared nowhere in the contract.

**This generalises.** Any future "document the strict shape, accept the
loose one quietly" change in Go must run before validation — a middleware
or a body rewrite, never a decoder. The pagination-standardisation change
is the next candidate. In **Java** the constraint does not apply: Javalin +
Jackson deserialise first and JSON-schema validation is deferred, so a
custom deserialiser would work there. That asymmetry is a reason to keep
leniency decisions explicit per side rather than assuming the port mirrors
the Go structure.

### Correction 2 — the deprecation window collapsed to zero

The recommendation was to retire the string form after a deprecation
window. In the event, `f908c3b` retired it the same day, on the evidence
that no first-party caller sends the old form: the SPA and all three
generated SDK client sets use `defaultScopes` array-shaped only. Released
SDKs built against the old string form are the population that was
exposed for the window's duration.

One residual, recorded in the commit message and worth keeping visible:
both request bodies are relaxed by `RelaxRequestBodies`, so a caller still
sending `scopes` is **ignored rather than rejected** — a silent
partial-success where the client believes it set scopes and did not.

### Porting note

**Nothing to port.** The Java side implements the strict array on all three
DTOs and no compat path. `TestOAuthClientScopesWireNameIsUniform` is the Go
invariant test; the Java equivalent belongs in the `oauthclient` aggregate's
API test when it lands. The vendored lockfile in this repo has been synced
to `f908c3b` (`server/src/main/resources/openapi/openapi.lock.json` and
`sdk/openapi/openapi.json`, byte-identical to `api/openapi.lock.json`);
`LockfileCoverageTest` passes — 178 paths / 243 operations / 231 schemas
unchanged, the delta is schema-only and no ported Java code referenced the
field.

---

## Fix 6 — `expires_in` is a literal, not the configured TTL (Q15)

**Owner ruling 2026-08-24: fix — derive it.** Owner is making the same
change in Go.

**Defect.** The access-token lifetime is configuration —
`AuthService.Config.AccessTokenExpirySecs` (`authservice.go:184`), which is
what actually stamps the JWT `exp` via `generateTokenWithExpiry`
(`:388/:398/:410`). Six response sites ignore it and write `3600`:

| Site | Response | Token minted |
|---|---|---|
| `oauthapi/token.go:500` | `client_credentials` | `GenerateAccessTokenWithScope` |
| `oauthapi/token.go:622` | `authorization_code` | access token |
| `oauthapi/token.go:724` | `refresh_token` | access token |
| `oauthapi/portal_token.go:72` | portal token exchange | access token |
| `login/endpoint.go:326` | platform token refresh (`expiresIn`) | `GenerateAccessToken` |
| `serviceaccount/api/api.go:347` | admin-minted SA token (`expiresIn`) | access token |

All six mint an **access token**, so all six take the same TTL — there is
no per-token-kind complication.

**This is latent, not live.** `wire_services.go:86` sets
`AccessTokenExpirySecs: 3600` as its own literal, and `DefaultConfig()`
agrees, so today the advertised value and the real `exp` always match. The
defect is the *coupling*: nothing connects the two, the constant is not
env-driven, and the first person to shorten the TTL silently makes six
responses lie. A client that trusts `expires_in` caches the token for an
hour and then takes 401s for the remainder without a clue why — while the
JWT's own `exp` claim, the real authority, said otherwise all along.

**Fix.** Read the value from the same config that mints the token, at every
one of the six sites. No wire shape changes: `expires_in` stays an integer
in seconds and, at the current configuration, stays `3600`, so no SDK,
lockfile or frontend regeneration is needed. Prefer passing the resolved
number from the token-minting call rather than each handler reaching into
config independently, so the two cannot drift again.

**Worth doing at the same time:** make the TTL genuinely configurable
(`wire_services.go:86` is currently a literal). Deriving `expires_in` is
what makes that safe to do — the config is not really tunable today, since
tuning it would start the lying.

**Tests.** Configure a non-default TTL (e.g. 900), exercise each of the six
responses, and assert `expires_in` equals the configured value **and**
matches the `exp` claim of the token in the same response. Asserting the
two agree is the assertion that would have caught this; asserting the
literal is what let it through.

**Java port.** Derive it from the start — the port must not reproduce the
literal. `docs/spec/auth-core.md` constants row 2 records the coupling.

---

## Already done in Go (verified 2026-08-24, uncommitted working tree)

| Question | Change | Where |
|---|---|---|
| Q7 — `invalid_grant` on the refresh grant should be 400, not 401 | Done. Returns `StatusBadRequest` for both the client-binding failure and an invalid/expired refresh token, with a comment citing RFC 6749 §5.2. The spec row was stale. | `token.go:656-668` |
| Q8 — discovery advertised `RS256` even when running HS256 | Done. `IDTokenSigningAlgValuesSupported: []string{s.Auth.Algorithm()}`, so an HS256 deployment stops telling relying parties to verify RS256 against an empty JWKS. | `discovery.go` |

---

## Porting note

Once these land in Go, update `docs/spec/auth-core.md` §6.2 (mounting), §6.2a
(grant table), §7 (rate-limit buckets) and the Q4/Q5/Q6 rows, then port the
corrected behaviour. The Java `Authenticator` already has the pieces: it
attaches context without gating, declines non-Bearer schemes, and refuses
identity bearers — so the Java side needs the route placement and the client
resolution, not a middleware change.
