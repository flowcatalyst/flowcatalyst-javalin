# Spec — HTTP/2 on the server listeners

Owner requirement, 2026-09-06: "we need to enable HTTP/2/3". Go's inbound
server is HTTP/1.1 only (no TLS in the process, no `h2c` handler), so this
is a Java-side capability, not a parity item — the parity corpus and the
e2e keep running over HTTP/1.1 and must not change.

**Rewritten 2026-09-08** (Vert.x cutover, `docs/vertx-plan.md` Phase 3):
Jetty is gone from the codebase; every listener below is Vert.x
(`io.flowcatalyst.http.vertx.VertxListener`), not a Jetty connector — this
document was updated in place rather than kept as a Jetty-era record, since
the two implementations differ enough that stale Jetty prose next to
current Vert.x prose would be more confusing than useful. **Q6 (HTTP/3 on
Vert.x) is closed: dropped.** Vert.x 5.1 serves HTTP/3 only through Netty's
incubator QUIC native codec, and nothing in this platform needs it in
production — an ALB terminates TLS and never speaks h3 to the target, so h3
was always a client-edge feature of the deployment, not the process (§5).
`FC_HTTP3_ENABLED=true` is now a startup error
(`io.flowcatalyst.server.transport.Listeners#resolve`) rather than a
silently-accepted knob; `FC_HTTP3_PORT` is read but unused.

## 1. Listeners

| Listener | Env | Protocols | Default |
|---|---|---|---|
| **API** (TCP) | `FC_API_PORT` (alias `PORT`) | HTTP/1.1 **and h2c** — cleartext HTTP/2 by prior knowledge and by `Upgrade: h2c` | on, 8080 |
| **API TLS** (TCP) | `FC_TLS_PORT` | TLS 1.2/1.3 with ALPN → **h2**, http/1.1 | on only when TLS material is configured (§2), 8443 |
| ~~API HTTP/3~~ | ~~`FC_HTTP3_PORT`~~ | **dropped** (Q6, above) | `FC_HTTP3_ENABLED=true` is a startup error |
| **Metrics** (TCP) | `FC_METRICS_PORT` | HTTP/1.1 | unchanged, 9090 |

Current implementation (`io.flowcatalyst.http.vertx.VertxListener`):

- The plain listener is `HttpServerOptions.setHttp2ClearTextEnabled(true)` —
  h2c by prior knowledge and by `Upgrade: h2c` on the SAME port as HTTP/1.1,
  no second connector needed (Vert.x multiplexes on the one `HttpServer`).
  Why h2c on the plain listener: production sits behind an ALB that
  terminates TLS (`docs/spec/cutover.md`); an ALB target group with protocol
  version `HTTP2` talks h2c to targets, and that is the only way HTTP/2
  reaches the process in that topology.
- The TLS listener is a SECOND `HttpServer` on the same `Vertx` instance and
  the same `Router` (`VertxListener.Options.Tls`, `VertxListener#prepare`):
  `setSsl(true).setUseAlpn(true).setKeyCertOptions(...)`. Vert.x negotiates
  HTTP/2 over ALPN automatically once SSL is on — no separate "enable h2"
  flag the way the plain listener needs for cleartext h2c. The key material
  is handed over as PKCS#12 bytes (`PfxOptions`): `TlsMaterial`'s
  `java.security.KeyStore` (§2, unchanged) is re-encoded to PKCS#12 bytes in
  memory (`VertxListener.Tls#pfxOptions`) since Vert.x has no
  `KeyStore`-object entry point.
- Metrics stays a plain HTTP/1.1 listener (`Metrics.java`): its own
  `VertxListener.Options` with `h2c=false`, bound to every interface (an
  external scraper reaches it, unlike the loopback-only outbox admin API).
- Shutdown: `HttpServer#shutdown(SHUTDOWN_GRACE)` on both listeners (in
  parallel is not needed — sequential is fine, `VertxListener#close`), then
  `vertx.close()`.

## 2. TLS material

Two equivalent ways to hand the server a certificate; exactly one may be set:

| Env | Meaning |
|---|---|
| `FC_TLS_KEYSTORE_PATH` + `FC_TLS_KEYSTORE_PASSWORD` | a PKCS#12 keystore holding one key entry with its chain (`keytool -genkeypair -storetype PKCS12`; what ACM/OpenSSL exports produce with `openssl pkcs12 -export`) |
| `FC_TLS_CERT_PATH` + `FC_TLS_KEY_PATH` | PEM: the leaf-first certificate chain and an unencrypted PKCS#8 private key (`-----BEGIN PRIVATE KEY-----`) — what most cert tooling writes |

The PEM pair is loaded with the JDK alone: `CertificateFactory.getInstance("X.509")`
reads a PEM chain as-is; the key's Base64 body decodes to a `PKCS8EncodedKeySpec`
and the algorithm is tried as RSA then EC (Ed25519 too if cheap). Both forms
end as an in-memory `KeyStore` (`TlsMaterial`, unchanged by the Vert.x
cutover — it stays a pure `java.security` class with no framework
dependency); nothing is written to disk. Errors are startup errors with the
path in the message (`FC_TLS_CERT_PATH …: not a PEM certificate`), never a
listener that silently stays HTTP/1.1. Setting one of a pair without the
other, or both forms at once, is a startup error too. `FC_HTTP3_ENABLED=true`
is now unconditionally a startup error (§ above) — TLS material no longer
changes that.

## 3. Where

`server/src/main/java/io/flowcatalyst/server/transport/`:
`Listeners` (resolves `Env` into `Optional<VertxListener.Tls>` for
`Server#buildApiAndReaper` — also where `FC_HTTP3_ENABLED=true` is rejected),
`TlsMaterial` (§2, a sealed `Keystore | Pem` read from `Env`, unchanged).
`io.flowcatalyst.http.vertx.VertxListener` (module `server`, package
`io.flowcatalyst.http.vertx`) owns the actual `HttpServer` construction —
see `docs/spec/vertx-listener.md`.
`Env` carries the six `FC_TLS_*` / `FC_HTTP3_*` members (read through the
`EnvReader` like every other knob — `Platform`/`Server` never read the
process environment directly).

Dependencies: `io.vertx:vertx-core` and `io.vertx:vertx-web` only (already
in `server/pom.xml` for the listener itself; no additional dependency for
TLS/h2 — Vert.x's ALPN and h2c support ship in `vertx-core`). No Jetty, no
native TLS/QUIC library, no `jetty-bom`.

Native image (`-Pnative`): Vert.x/Netty reachability comes from the GraalVM
metadata repository plus whatever the tracing agent finds under
`server/native-config/`; no quiche FFM binding to register any more.

## 4. Tests (`server/src/test/java/io/flowcatalyst/server/transport/`)

Each starts a real `Server` (as `ServerTest`/`TestHttp` do) on free ports.

1. **h2c prior knowledge** (`Http2Test`): a Vert.x `HttpClient` configured
   for `HttpVersion.HTTP_2` + `setHttp2ClearTextUpgrade(false)` (the same
   shape `VertxMediationClient` uses in production — the JDK's own
   `HttpClient` never does prior knowledge) gets `/health` 200 with the
   response version HTTP/2. **h2c upgrade**: the JDK `HttpClient` with
   `Version.HTTP_2` against the plain port gets 200 and reports
   `HttpClient.Version.HTTP_2` for the second request at the latest.
   **HTTP/1.1 still works** on the same port (TestHttp as today). **The
   metrics listener never upgrades**: the same JDK-client two-request shape
   that reaches HTTP/2 against the API listener stays HTTP/1.1 against
   `FC_METRICS_PORT` for both requests.
2. **TLS + ALPN** (`TlsAlpnTest`): with a PKCS#12 made by `keytool` in a temp
   dir (the JDK ships it — resolve it under `System.getProperty("java.home")/bin`),
   the JDK `HttpClient` with an SSLContext trusting that cert gets `/health`
   over `https://` with version HTTP/2; with `Version.HTTP_1_1` it gets
   HTTP/1.1. **PEM form**: `openssl` is not assumed — convert the PKCS#12 to
   PEM in the test with JDK APIs (export the cert with
   `CertificateFactory`/Base64 and the key with `PKCS8EncodedKeySpec`) and
   start a second server from the PEM pair; same assertions. A wrong
   password / a missing file / both forms set: startup fails with the path
   in the message.
3. ~~HTTP/3~~: dropped with Q6; `Http3Test` deleted.
4. **Nothing else moved**: the parity harness and the e2e are untouched
   (HTTP/1.1, plain).
5. Mutants (run, confirm, revert): disable `setHttp2ClearTextEnabled` →
   test 1 fails; drop `setUseAlpn` on the TLS listener → test 2 falls back
   to HTTP/1.1 and fails; enable h2c on the metrics listener → test 1's
   "never upgrades" assertion fails.

## 5. Owner notes

- With the ALB in front, the production win is **h2c to targets** (target
  group protocol version `HTTP2`) — a terraform/console change, not code.
  End-to-end HTTP/3 to the browser was always the ALB's feature, not the
  target's, which is why dropping the server-side h3 listener (Q6) costs
  production nothing; the only topology it would have served (a process
  terminating TLS itself — fcdev, a bare host, a future NLB) has no h3
  requirement today.
- `fcdev` keeps plain HTTP (the SPA's `Secure` cookie is fine on
  localhost); an `fcdev start --tls` is a later nicety, not this unit.
