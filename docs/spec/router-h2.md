# Spec — HTTP/2 for the message router's outbound mediation (owner ruling 2026-09-06)

## 1. Ruling

The router's outbound webhook mediation (every per-message call `HttpMediator` makes)
uses HTTP/2 when deployed and HTTP/1.1 under fcdev. Reason: without h2 the number of
HTTP/1.1 connections needed for the router's concurrency is unbounded in practice.
Prefer h2, fall back to 1.1 when a target cannot negotiate it, and **record the
negotiated version** so a target still on 1.1 is visible.

## 2. Facts

- `java.net.http.HttpClient` defaults to `Version.HTTP_2` with fallback to 1.1 (JDK 25,
  `HttpClientImpl`), so today's router client already prefers h2 — silently. For
  `http://` targets the JDK sends the `Upgrade: h2c` handshake; for `https://` it uses
  ALPN. The platform's own listener accepts h2c (Jetty today via `server/transport`,
  Vert.x in Phase 2 with h2c on), which is where the router's calls land
  (`POST /api/dispatch/process`).
- The dev/deployed fact already exists: `env.routerDevMode()` (selects
  `HttpMediator.DEV_TIMEOUT` vs `PRODUCTION_TIMEOUT`).

## 3. Change

- `HttpMediator.defaultClient(boolean devMode)`: `.version(devMode ? HTTP_1_1 : HTTP_2)`,
  everything else as today (30 s connect timeout, redirects never). `Router.java` passes
  `env.routerDevMode()`. The no-arg `defaultClient()` goes.
- After a successful `client.send`, `HttpMediator` calls
  `metrics.recordHttpVersion(response.version())` → Prometheus counter
  `fc_router_mediation_http_version_total{version="HTTP_2"|"HTTP_1_1"}` on `PoolMetrics`.
  The outcome types are untouched.
- `SubscriberDelivery.defaultClient()` gains `.connectTimeout(Duration.ofSeconds(30))`
  (the missing bound found in the 2026-09-06 review); its version stays the JDK default.

## 4. Tests (break-it-on-purpose)

| # | Behaviour | Pin | Mutant |
|---|---|---|---|
| 1 | Dev mode negotiates HTTP/1.1 against an h2c-capable local server | `HttpMediatorVersionTest`: a Javalin/Jetty test listener with h2c on (the `server/transport` `Listeners` already do this); assert the recorded version is `HTTP_1_1` | drop `.version(...)` → the JDK default negotiates h2 and the assertion fails |
| 2 | Deployed mode negotiates HTTP/2 against the same server, and 1.1 against a 1.1-only server (plain Jetty without h2c) | assert `HTTP_2` then `HTTP_1_1` | force `HTTP_1_1` → first fails |
| 3 | The version counter increments once per delivered request with the right label | assert the counter value | never record → 0 |
| 4 | `SubscriberDelivery`'s client has a connect timeout | `client.connectTimeout()` is present and 30 s | remove → empty |

## 5. Landed 2026-09-06 and one finding for the owner (Q7)

Implemented as §3 (`HttpMediator.defaultClient(boolean devMode)`, `PoolMetrics.recordHttpVersion`,
router-wide `PoolMetricsCollector` for the mediator, `SubscriberDelivery` connect timeout; tests
`HttpMediatorVersionTest`, four mutants killed). **Finding, verified with two standalone probes
while writing the tests:** the JDK `java.net.http.HttpClient` never attempts the `Upgrade: h2c`
dance for a request that carries a body, and it does not do h2c by prior knowledge at all. Every
real mediation call is a POST with a body, so against a **cleartext** target the deployed client
stays on HTTP/1.1 whatever `.version(HTTP_2)` says; only `https://` targets (ALPN during the
handshake) actually get h2. The recorded version counter shows this honestly, but the
"unbounded HTTP/1.1 connections" problem the ruling exists to solve is fixed only for TLS targets.

**Q7 — how should cleartext router → platform mediation reach HTTP/2?** Options:
(a) TLS between router and platform inside the cluster (ALPN, the JDK client as is);
(b) a client that speaks h2c by prior knowledge for the router's mediation only — Jetty's
`HttpClient` with `HttpClientTransportOverHTTP2` (`jetty-http2-client-transport` is already a
server dependency) or OkHttp's `H2_PRIOR_KNOWLEDGE`; the Vert.x client is excluded by the plan;
(c) accept 1.1 for cleartext and bound the connection count another way (the router's per-pool
concurrency already bounds in-flight requests, and idle 1.1 connections are pooled by the JDK
client with a default keep-alive of 20 min — measure how many a busy router actually holds).
