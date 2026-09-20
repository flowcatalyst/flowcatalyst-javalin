# Spec — public routes: domains, route sync, the public listener, CORS (package F)

Design: `docs/function-runner-plan.md` §4a. Shape settled by `function-invocation.md` §2 (the public
entry, the same function-path from either entry), §3 (`public: [{hostname, pathPrefix}]`, `cors` on an
endpoint), §5. Builds on A (`fn_domains`, `fn_routes`, `Hostname`, `FunctionDomain`,
`FunctionRouteRepository`), B, I2 (promote-time reconciliation), D3 (the private listener — everything
below the route match is reused unchanged).

## 1. Domains (slice F1, platform)

A public hostname must belong to the function's **owner** and be **verified** before any function can
be routed on it — otherwise one tenant registers another's hostname.

| Route | Permission | Behaviour |
|---|---|---|
| `POST /api/function-domains` | `FUNCTION_DOMAIN_MANAGE` (`platform:function:domain:manage`, new; `messaging-admin`) | `{hostname, clientId?}` — absent `clientId` ⇒ platform-owned, needs anchor; else `checkScopeAccess`. `Hostname.parse`. Taken (by anyone) ⇒ 409 `DOMAIN_TAKEN` — **without naming the holder**. 201 `{id, hostname, owner, verification: {state: "PENDING", record: {type: "TXT", name: "_flowcatalyst.<hostname>", value: "fc-verify=<token>"}}}`. Token = 32 random bytes, base64url; stored; `FunctionDomain.toString` already masks it. `ClaimFunctionDomain` / event `platform:function:domain:claimed` (hostname, owner — no token) |
| `GET /api/function-domains?clientId=` | `FUNCTION_VIEW` | reach-filtered; the TXT record is shown only while `PENDING` |
| `POST /api/function-domains/{hostname}/verify` | manage | resolves TXT `_flowcatalyst.<hostname>` through `TxtResolver`; any value equal to `fc-verify=<token>` ⇒ `VERIFIED`, 200; none ⇒ 409 `DOMAIN_NOT_VERIFIED` listing what was found (truncated, ≤ 5 values, each ≤ 100 chars); resolver failure ⇒ 503 `DNS_UNAVAILABLE`. Already verified ⇒ 200, no event. Event `…:domain:verified` |
| `DELETE /api/function-domains/{hostname}` | manage | 409 `DOMAIN_IN_USE` naming the functions while `fn_routes` rows exist for it; else 204, `…:domain:released` |

`TxtResolver` is an interface (`List<String> txt(String name) throws DnsException`); the production
implementation uses JNDI DNS (`com.sun.jndi.dns`, in the JDK — no dependency) with a 5 s timeout;
the jlink module list for the server image gains `jdk.naming.dns` **only if `jdeps` does not already
report it** (check; say which). Tests inject a fake. **Dev mode** (`FLOWCATALYST_DEV_MODE`): a domain
under `.localhost` (RFC 6761: always loopback) is verified at claim time, no DNS — so fcdev's public
entry works with `http://hello.localhost:<port>/`. Nothing else is ever auto-verified.

Reach for a domain is its owner's, exactly as for a function (`function-api.md` §2); out of reach ⇒ 404.

## 2. Route sync (slice F1)

Joins I2's promote-time reconciliation (`FunctionTriggerSync`), same transaction, same rules:
**validated at publish, materialised at promote, removed at delete, untouched by disable** (a disabled
function answers 404 by address already; its routes stay reserved).

- **Publish**: each `public[].hostname` must be a `VERIFIED` domain **of the function's owner** ⇒ else
  validation `PUBLIC_HOSTNAME_NOT_VERIFIED` naming it (same code whether unclaimed, pending, or another
  owner's — no oracle). `(hostname, pathPrefix)` already routed to **another** function ⇒ conflict
  `PUBLIC_ROUTE_TAKEN` naming that function's address **only when the caller can reach it**, else just
  the route.
- **Promote**: `fn_routes` for the function := the new live manifest's `public` set
  (`FunctionRouteRepository.replaceForFunction` — no difference ⇒ no write); the unique constraint is
  the race backstop ⇒ mapped to the same 409, never a 500. The conflict is re-checked here (another
  function may have promoted in between).
- **Prefix overlap across functions is allowed** and resolved by specificity: `api.acme.com` + `/` and
  `api.acme.com` + `/billing` may belong to two functions; the longest matching prefix wins (whole
  segments: `/billing` matches `/billing` and `/billing/x`, not `/billingx`). Equal pairs are the
  conflict above.
- **Desired state** gains a top-level `publicRoutes: [{hostname, pathPrefix, address}]` for functions
  whose live version is in the requested pool, sorted (hostname, pathPrefix) — deterministic bytes.
  `GET /api/function-routes?hostname=&address=` lists them (`FUNCTION_VIEW`, reach-filtered).

## 3. The public listener (slice F2, host)

`FC_FN_PUBLIC_PORT` (default **8081**; `0`/unset-in-fcdev rules below). A second `FnHttpServer` entry
on its own port — the load balancer's target — sharing permits, registry, reconciler and the whole
pipeline from "endpoint match" down (D3 §2 steps 4–10). What differs is only how the function and the
function-path are found:

1. `Host` header (or `:authority`): lower-cased, port stripped, must parse as a `Hostname` ⇒ else
   `404 NOT_FOUND`. `X-Forwarded-Host` is **ignored** — the load balancer forwards `Host`; honouring a
   client-settable header would let anyone choose their route.
2. Longest-prefix match over that hostname's `publicRoutes` ⇒ `(address, prefix)`; none ⇒ `404`.
3. function-path = the request path with the prefix stripped (prefix `/billing`, path `/billing/x` ⇒
   `/x`; path `/billing` ⇒ `/`). `Request.originalHost`/`originalPath` carry what arrived.
4. **`/functions/…` is not special here**: it is matched like any other path, so it is 404 unless a
   function really owns that prefix on that hostname. There is no by-address access and **no versioned
   access** on the public listener. An inbound `X-FlowCatalyst-Function` header is dropped before the
   function sees the request (design §4a rule 1).
5. Then D3's pipeline: endpoint match on the function-path, body cap, the endpoint's `auth`, permits,
   load, invoke, respond. A `webhook` endpoint is reachable publicly only with a valid platform
   signature — fine, and occasionally useful; say so in the guide.
6. The private listener is unchanged and must stay unreachable from the internet — `docs/deployments.md`:
   the load balancer targets 8081 only; 8080 is Service Connect only.

**Trust of the forwarded client address**: `Request.remoteAddress` on the public listener is the
**right-most** `X-Forwarded-For` entry when the TCP peer is in `FC_FN_TRUSTED_PROXIES` (CIDR list;
default: RFC 1918 + loopback — an ALB in the VPC), else the TCP peer. Left-most is client-controlled.

## 4. CORS (slice F2) — on both listeners, per endpoint

Only endpoints that declare `cors`. `Cors(origins, methods, headers, allowCredentials)`; `origins`
entries are exact origins (`https://app.acme.com`) or `*`; **`*` with `allowCredentials` is rejected at
publish** (`ENDPOINT_INVALID`) — browsers refuse it and "reflect any origin with credentials" is the
classic hole.

- **Preflight** (`OPTIONS` + `Origin` + `Access-Control-Request-Method`): answered by the **host**, the
  function is not invoked, no permit taken, **no auth applied** (a preflight carries no credentials).
  Origin allowed and method in (`cors.methods` ∪ the endpoint's methods) ⇒ `204` +
  `Access-Control-Allow-Origin` (the origin itself, or `*`), `-Allow-Methods`, `-Allow-Headers`
  (`cors.headers` ∩ requested, case-insensitive), `-Allow-Credentials: true` when set,
  `Access-Control-Max-Age: 600`, `Vary: Origin`. Not allowed ⇒ `204` with **no** CORS headers (the
  browser blocks; the server does not reveal policy by status).
- **Actual request** with an allowed `Origin`: the function runs; the host adds `-Allow-Origin`,
  `-Allow-Credentials`, `Vary: Origin` to whatever the function returned, **replacing** any CORS
  headers the function set itself (one authority). Disallowed origin ⇒ the request still runs (CORS is
  a browser control, not an access control) and gets no CORS headers.
- An `OPTIONS` request that is not a preflight goes to the function if the endpoint's methods allow it.

## 5. fcdev

`fcdev start` also opens the public listener (`--fn-public-port`, default 8091; banner line). With dev
mode's `.localhost` rule, `hello.localhost:8091` works out of the box. `fn` CLI: `fn domain claim|verify|list|release`.

## 6. Load-bearing behaviours (one mutant per condition; absence as well as presence)

| # | Behaviour | Mutant |
|---|---|---|
| F1 | a hostname claimed by one owner cannot be claimed by another and the error never names the holder; verify succeeds only when a TXT value equals the token **exactly** (prefix, suffix, other record, case-changed ⇒ not verified); resolver failure is 503, not "not verified" | `contains`; name the holder; treat failure as unverified |
| F2 | publish is refused for an unclaimed hostname, a pending one, and **another owner's verified one** — same code for all three | check existence only; skip the owner comparison |
| F3 | `.localhost` auto-verifies **only** in dev mode; `evil.localhost.example.com` never does | suffix match without the dot; ignore dev mode |
| F4 | promote materialises exactly the manifest's set; promote v2 (drops a route) frees it for another function in the same transaction-visible way; equal `(hostname, prefix)` on two functions ⇒ 409 at publish **and** at promote (race: both published before either promoted); the unique-violation path is a 409, not a 500 | skip the promote-time re-check; let the constraint surface as 500 |
| F5 | `PUBLIC_ROUTE_TAKEN` names the other function only to a caller who can reach it | always name it |
| F6 | longest whole-segment prefix wins; `/billing` does not match `/billingx`; prefix stripping yields `/` for an exact match; the function sees the same `path` from both listeners | `startsWith`; first-registered wins |
| F7 | public listener: unknown host 404; `X-Forwarded-Host` ignored; `/functions/<address>/…` and `/functions/<address>:1/…` are 404 **and the function's invocation counter stays 0**; inbound `X-FlowCatalyst-Function` never reaches the function | route by address on the public port; honour `X-Forwarded-Host` |
| F8 | `remoteAddress`: untrusted peer ⇒ the peer, whatever `X-Forwarded-For` says; trusted peer ⇒ the right-most entry | left-most; trust everyone |
| F9 | CORS: preflight allowed/disallowed (headers present/absent exactly), not invoked, no permit, no auth on a `platform` endpoint's preflight; actual request gets host-set headers replacing the function's own; `*`+credentials rejected at publish; `Vary: Origin` always with an origin-specific allow | reflect any origin; invoke the function on preflight; let the function's header win |
| F10 | desired-state `publicRoutes` sorted and per pool; bytes deterministic; ETag moves on a route change | — |
| F11 | in-process end to end (extends D3's acceptance harness): claim → verify (fake resolver) → publish → promote → `GET http://127.0.0.1:<public port>/x` with `Host: api.example.test` reaches the function with `path=/x`; the same function by address on the private port gets the same `path` | — |
