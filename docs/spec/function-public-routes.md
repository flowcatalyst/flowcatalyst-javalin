# Spec — public routes: domains, route sync, the public listener, CORS (package F)

Design: `docs/function-runner-plan.md` §4a. Shape settled by `function-invocation.md` §2 (the public
entry, the same function-path from either entry), §3 (`public: [{hostname, pathPrefix}]`, `cors` on an
endpoint), §5. Builds on A (`fn_domains`, `fn_routes`, `Hostname`, `FunctionDomain`,
`FunctionRouteRepository`), B, I2 (promote-time reconciliation), D3 (the private listener — everything
below the route match is reused unchanged).

## 1. Domains (slice F1, platform)

> **Amended by `function-zones-and-aliases.md` §1 (slice J1, 2026-09):** a claim is a **zone**, not a
> single exact hostname — claiming `acme.com` also covers `myapp.acme.com` and every other hostname
> whose labels end in `acme.com`'s, verified once for the whole zone. `d` must have at least two
> labels (already enforced by `Hostname.parse`'s own floor, `HOSTNAME_INVALID`); no two claims may
> nest, by any owner (`DOMAIN_TAKEN`, same wording, no oracle). Every row below that reads "a public
> hostname"/"the claimed hostname" now reads "a hostname covered by a claimed zone, or the zone apex
> itself" — the table's routes and codes are otherwise unchanged.

A public hostname's **zone** must belong to the function's **owner** and be **verified** before any
function can be routed on it — otherwise one tenant registers another's hostname.

| Route | Permission | Behaviour |
|---|---|---|
| `POST /api/function-domains` | `FUNCTION_DOMAIN_MANAGE` (`platform:function:domain:manage`, new; `messaging-admin`) | `{hostname, clientId?}` — absent `clientId` ⇒ platform-owned, needs anchor; else `checkScopeAccess`. `Hostname.parse`, then the zone's own floor (≥ 2 labels — the same `HOSTNAME_INVALID`, no second `DOMAIN_INVALID` code). Nesting (by anyone, equals/covers/is-covered-by `d`) ⇒ 409 `DOMAIN_TAKEN` — **without naming the holder**. 201 `{id, hostname, owner, verification: {state: "PENDING", record: {type: "TXT", name: "_flowcatalyst.<hostname>", value: "fc-verify=<token>"}}}`. Token = 32 random bytes, base64url; stored; `FunctionDomain.toString` already masks it. `ClaimFunctionDomain` / event `platform:function:domain:claimed` (hostname, owner — no token) |
| `GET /api/function-domains?clientId=` | `FUNCTION_VIEW` | reach-filtered; the TXT record is shown only while `PENDING` |
| `GET /api/function-domains/{hostname}` | `FUNCTION_VIEW` | (2026-09-22, backlog unit S3) the claim covering `{hostname}` — 200 the same `DomainResponse` shape `POST`/list give, for the ZONE's own claim (its `hostname` is the zone apex, not necessarily the path param); reach-or-404 through `Access.byHostname` (the SAME predicate `verify`/`release` already share — not duplicated here, and now resolves through `FunctionDomainRepository.covering`); an out-of-reach zone (another client's) is 404, never 403, same rule as every other reach check in this spec. An invalid hostname is the same `400 HOSTNAME_INVALID` the claim route gives — both routes parse through `Hostname.parse` |
| `POST /api/function-domains/{hostname}/verify` | manage | resolves through `Access.byHostname` to the covering zone claim `d`, then resolves TXT `_flowcatalyst.<d.hostname>` through `TxtResolver` — the ZONE's own record, never the caller's raw path param; any value equal to `fc-verify=<token>` ⇒ `VERIFIED`, 200; none ⇒ 409 `DOMAIN_NOT_VERIFIED` listing what was found (truncated, ≤ 5 values, each ≤ 100 chars); resolver failure ⇒ 503 `DNS_UNAVAILABLE`. Already verified ⇒ 200, no event. Event `…:domain:verified` |
| `DELETE /api/function-domains/{hostname}` | manage | resolves through `Access.byHostname` to the covering zone claim; 409 `DOMAIN_IN_USE` naming the functions while any `fn_routes` row's hostname is covered by the zone (`FunctionRouteRepository.listUnder`); else 204, `…:domain:released` |

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

- **Publish**: each `public[].hostname` must be **covered by** a `VERIFIED` zone **of the function's
  owner** (`FunctionDomainRepository.covering`, `function-zones-and-aliases.md` §1) ⇒ else
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

## 7. Slice F2 implementation notes (session addendum, 2026-09-20)

- **F11's fake `TxtResolver` seam**: `Platform.java`'s composition root wires a fixed
  `JndiTxtResolver` into `FunctionDomainApi.State` with no injection point for a real `Server` built
  from this repo's test harnesses. F11 uses the documented fallback instead: a `.localhost` hostname
  under dev mode (`FLOWCATALYST_DEV_MODE=true`, already set for `FunctionHostListenerIntegrationTest`),
  which auto-verifies at claim time (§1, F3) — no DNS, no separate `verify` call. A future slice that
  wants the fake-resolver path exercised end to end needs `Server`/`Platform` to accept an injectable
  `TxtResolver`.
- **CORS `Access-Control-Allow-Methods` when an endpoint's own `methods` is empty** ("every method"):
  the header cannot enumerate "every method", so the host echoes `cors.methods` plus the SPECIFIC
  method the preflight requested (already known allowed, since the endpoint declares no restriction
  of its own) rather than refusing or guessing a fixed list.
- **CORS origin equality**: compared as `(scheme, host, port)` via `java.net.URI`, lower-casing
  scheme and host; a configured entry omitting the port and a request `Origin` naming the scheme's
  default port explicitly (`https://a.com` vs `https://a.com:443`) are NOT treated as equal — an
  edge case narrow enough that over-engineering default-port normalisation seemed the wrong
  trade-off for this slice.
- **CORS is unversioned-only**: the versioned smoke-test path (`/functions/{address}:{n}/...`)
  never runs CORS handling — its own auth must resolve strictly before the entry/endpoint may be
  looked at at all (spec `function-host-listener.md` §4), which is incompatible with a preflight
  that carries no auth by definition; nothing in this spec asks a browser to smoke-test a candidate
  version anyway.
- **`fc_fn_invocations_total{entry}`**: added as a 4th label (`address`, `version`, `outcome`,
  `entry`), 2 bounded values (`private`, `public`); `InvocationObserver#refused`/`#completed` grew a
  `(..., Entry)` overload defaulting to the pre-F2 signature (`Entry.PRIVATE`), so every existing
  observer/test double keeps compiling and behaving unchanged.
- **`X-Forwarded-For` "malformed entry ⇒ the peer"**: the right-most entry is used only when it is
  syntactically an IPv4 or IPv6 literal (`TrustedProxies#isIpLiteral`, no DNS); this is also what
  the class's own `isTrusted(String)` uses to avoid ever resolving arbitrary caller-supplied text.
- **No IPv4-mapped-IPv6 "unwrap" step in `TrustedProxies`**: an early version normalised a 16-byte
  IPv4-mapped candidate to 4 bytes before comparing. Direct experiment
  (`TrustedProxiesTest#jdkFoldsIpv4MappedAddressesToInet4AddressAlways`) showed `java.net.InetAddress`
  itself already folds `::ffff:a.b.c.d` — both via `getByName` on the literal and via `getByAddress`
  on the raw 16 mapped bytes — down to a plain `Inet4Address` before any caller ever sees it, so a
  genuinely 16-byte mapped candidate can never reach this class through a standard JDK path; the
  original "unwrap" test passed for the wrong reason (both sides already matched at 4 bytes either
  way) — decorative by the Testing Policy's own definition. The unwrap code was removed rather than
  kept as untested defensive dead code.
