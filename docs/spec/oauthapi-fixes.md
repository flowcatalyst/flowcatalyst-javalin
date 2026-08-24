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

## Porting note

Once these land in Go, update `docs/spec/auth-core.md` §6.2 (mounting), §6.2a
(grant table), §7 (rate-limit buckets) and the Q4/Q5/Q6 rows, then port the
corrected behaviour. The Java `Authenticator` already has the pieces: it
attaches context without gating, declines non-Bearer schemes, and refuses
identity bearers — so the Java side needs the route placement and the client
resolution, not a middleware change.
