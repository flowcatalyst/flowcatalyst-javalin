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
