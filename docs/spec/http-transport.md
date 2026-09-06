# Spec — HTTP/2 and HTTP/3 on the server listeners

Owner requirement, 2026-09-06: "we need to enable HTTP/2/3". Today the Java
server speaks HTTP/1.1 only (Javalin 7.2.3 on Jetty 12.1.12, the default
connector); Go's inbound server is HTTP/1.1 too (no TLS in the process, no
`h2c` handler), so this is a Java-side capability, not a parity item — the
parity corpus and the e2e keep running over HTTP/1.1 and must not change.

## 1. Listeners

| Listener | Env | Protocols | Default |
|---|---|---|---|
| **API** (TCP) | `FC_API_PORT` (alias `PORT`) | HTTP/1.1 **and h2c** — cleartext HTTP/2 by prior knowledge and by `Upgrade: h2c` | on, 8080 |
| **API TLS** (TCP) | `FC_TLS_PORT` | TLS 1.2/1.3 with ALPN → **h2**, http/1.1 | on only when TLS material is configured (§2), 8443 |
| **API HTTP/3** (UDP) | `FC_HTTP3_PORT` | QUIC → **h3** | on only when TLS material is configured **and** `FC_HTTP3_ENABLED=true`; default port = `FC_TLS_PORT` |
| **Metrics** (TCP) | `FC_METRICS_PORT` | HTTP/1.1 | unchanged, 9090 |

- The h2c connector is the API connector: one `ServerConnector` with an
  `HttpConnectionFactory` and an `HTTP2CServerConnectionFactory`, installed
  through Javalin's `cfg.jetty.addConnector((server, httpConfig) -> …)` on
  the same port Javalin would have used (set `cfg.jetty.port` to `-1`/no
  default connector if Javalin insists on adding its own — read
  `io.javalin.config.JettyConfig` and `JavalinServer`; say in the report
  which way was needed). Why h2c on the plain listener: production sits
  behind an ALB that terminates TLS (`docs/spec/cutover.md`); an ALB target
  group with protocol version `HTTP2` talks h2c to targets, and that is the
  only way HTTP/2 reaches the process in that topology.
- The TLS connector: `SslConnectionFactory` → `ALPNServerConnectionFactory`
  ("h2", "http/1.1") → `HTTP2ServerConnectionFactory` + `HttpConnectionFactory`,
  with `SecureRequestCustomizer` on the `HttpConfiguration` and
  `jetty-alpn-java-server` (the JDK's ALPN, no native library).
- The HTTP/3 connector: `HTTP3ServerConnector` from `jetty-http3-server`
  over `jetty-quic-server` and the `jetty-quic-quiche-foreign` binding
  (FFM; Java 22+; the quiche native library ships inside the artifact for
  linux x86_64/aarch64 and macOS aarch64 — verify by loading it, and say
  which platforms you could verify). Jetty 12.1's `ServerQuicConfiguration`
  wants a PEM work directory (quiche reads the certificate and key from
  files): use `<java.io.tmpdir>/fc-quic-<pid>` created at start with owner-only
  permissions and deleted at stop. Every response on the TLS connector
  carries `Alt-Svc: h3=":<FC_HTTP3_PORT>"; ma=86400` when HTTP/3 is on
  (`HttpConfiguration.addCustomizer` or a Jetty `Handler.Wrapper`, not a
  Javalin `after` filter — the header belongs to the transport, and it must
  not appear on the h2c/HTTP/1.1 plain listener where it would be a lie).
- Metrics stays a plain HTTP/1.1 Jetty as today (`Metrics.java`).
- Shutdown: the existing `setStopTimeout(SHUTDOWN_GRACE)` applies to every
  connector; HTTP/2 GOAWAY and QUIC close are Jetty's job. **Known
  limitation (2026-09-06):** a QUIC session whose client vanished holds the
  graceful stop for the whole grace period — `docs/backlog.md` "HTTP/3
  sessions hold the graceful stop".

## 2. TLS material

Two equivalent ways to hand the server a certificate; exactly one may be set:

| Env | Meaning |
|---|---|
| `FC_TLS_KEYSTORE_PATH` + `FC_TLS_KEYSTORE_PASSWORD` | a PKCS#12 keystore holding one key entry with its chain (`keytool -genkeypair -storetype PKCS12`; what ACM/OpenSSL exports produce with `openssl pkcs12 -export`) |
| `FC_TLS_CERT_PATH` + `FC_TLS_KEY_PATH` | PEM: the leaf-first certificate chain and an unencrypted PKCS#8 private key (`-----BEGIN PRIVATE KEY-----`) — what most cert tooling writes |

The PEM pair is loaded with the JDK alone: `CertificateFactory.getInstance("X.509")`
reads a PEM chain as-is; the key's Base64 body decodes to a `PKCS8EncodedKeySpec`
and the algorithm is tried as RSA then EC (Ed25519 too if cheap). Both forms
end as an in-memory `KeyStore` handed to `SslContextFactory.Server`; nothing
is written to disk except the QUIC work directory above. Errors are startup
errors with the path in the message (`FC_TLS_CERT_PATH …: not a PEM
certificate`), never a listener that silently stays HTTP/1.1. Setting one of
a pair without the other, or both forms at once, is a startup error too.
`FC_HTTP3_ENABLED=true` without TLS material is a startup error ("HTTP/3
needs a certificate").

## 3. Where

`server/src/main/java/io/flowcatalyst/server/transport/`:
`Listeners` (builds the connectors from `Env`, installed by `Server.buildApiAndReaper`),
`TlsMaterial` (§2, a sealed `Keystore | Pem` read from `Env`),
`Http3` (the connector, the work directory, the `Alt-Svc` customizer).
`Env` gains the six `FC_TLS_*` / `FC_HTTP3_*` members (read through the
`EnvReader` like every other knob — `Platform`/`Server` never read the
process environment directly), and `docs/environment-variables` wherever the
Java repo documents its env (README's table, `cutover.md` §4's parity table:
these are Java-only additions, mark them so).

Jetty dependencies (all `${jetty.version}` = the one Javalin brings; use the
`jetty-bom` if the parent pom does not already import it):
`org.eclipse.jetty.http2:jetty-http2-server`, `org.eclipse.jetty:jetty-alpn-server`,
`org.eclipse.jetty:jetty-alpn-java-server`, `org.eclipse.jetty.http3:jetty-http3-server`,
`org.eclipse.jetty.quic:jetty-quic-server`, `org.eclipse.jetty.quic:jetty-quic-quiche-foreign`.
Test scope: `org.eclipse.jetty.http2:jetty-http2-client-transport` (HTTP/2 client),
`org.eclipse.jetty.http3:jetty-http3-client-transport` (HTTP/3 client).

Native image (`-Pnative`): the quiche binding is FFM + a bundled shared
library; register what the tracing agent finds under `server/native-config/`
and say whether the native binary can serve h3 (if not, HTTP/3 is a jar/jlink
feature and `docs/STATUS.md` says so — do not block the unit on it).

## 4. Tests (`server/src/test/java/io/flowcatalyst/server/transport/`)

Each starts a real `Server` (as `ServerTest`/`TestHttp` do) on free ports.

1. **h2c prior knowledge**: Jetty's HTTP/2 client (`HTTP2Client` +
   `HttpClientTransportOverHTTP2`, cleartext) gets `/health` 200 and the
   response version is HTTP/2. **h2c upgrade**: the JDK `HttpClient` with
   `Version.HTTP_2` against the plain port gets 200 and reports
   `HttpClient.Version.HTTP_2` for the second request at the latest.
   **HTTP/1.1 still works** on the same port (TestHttp as today).
2. **TLS + ALPN**: with a PKCS#12 made by `keytool` in a temp dir (the JDK
   ships it — resolve it under `System.getProperty("java.home")/bin`), the
   JDK `HttpClient` with an SSLContext trusting that cert gets `/health` over
   `https://` with version HTTP/2; with `Version.HTTP_1_1` it gets HTTP/1.1.
   **PEM form**: `openssl` is not assumed — convert the PKCS#12 to PEM in the
   test with JDK APIs (export the cert with `CertificateFactory`/Base64 and
   the key with `PKCS8EncodedKeySpec`) and start a second server from the
   PEM pair; same assertions. A wrong password / a missing file / both forms
   set: startup fails with the path in the message.
3. **HTTP/3**: `Alt-Svc` is present on the TLS listener's responses and
   absent on the plain one; Jetty's HTTP/3 client fetches `/health` over
   `h3` when the quiche library loads on this machine (`Assumptions` when it
   does not — and the report says whether it did on macOS arm64 and on
   Linux amd64 via CI).
4. **Nothing else moved**: the parity harness and the e2e are untouched
   (HTTP/1.1, plain); the metrics listener answers HTTP/1.1 only (an h2c
   prior-knowledge attempt on `FC_METRICS_PORT` fails).
5. Mutants (run, confirm, revert): drop the `HTTP2CServerConnectionFactory`
   → test 1 fails; drop the ALPN factory → test 2 falls back to HTTP/1.1
   and fails; put `Alt-Svc` on the plain listener → test 3's absence
   assertion fails.

## 5. Owner notes

- With the ALB in front, the production win is **h2c to targets** (target
  group protocol version `HTTP2`) — a terraform/console change, not code.
  End-to-end HTTP/3 to the browser is the ALB's feature, not the target's;
  the server-side h3 listener is for deployments where the process
  terminates TLS itself (fcdev, a bare host, a future NLB). Say so in
  `docs/STATUS.md` when this lands so nobody expects h3 through the ALB.
- `fcdev` keeps plain HTTP (the SPA's `Secure` cookie is fine on
  localhost); an `fcdev start --tls` is a later nicety, not this unit.
