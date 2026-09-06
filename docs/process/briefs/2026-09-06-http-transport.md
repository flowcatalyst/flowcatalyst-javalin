# Brief — HTTP/2 (h2c + h2) and HTTP/3 on the API listener

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. The design
is **`docs/spec/http-transport.md`** — read all of it; section numbers below
are its. Owner requirement 2026-09-06.

## Files you own

```
server/pom.xml                                   the Jetty HTTP/2, ALPN, HTTP/3/QUIC dependencies (§3), test-scope clients
server/src/main/java/io/flowcatalyst/server/transport/{Listeners,TlsMaterial,Http3}.java
server/src/main/java/io/flowcatalyst/server/Env.java       the FC_TLS_* / FC_HTTP3_* members (read via EnvReader — read how the others do it)
server/src/main/java/io/flowcatalyst/server/Server.java    install the connectors in buildApiAndReaper (cfg.jetty.addConnector / modifyServer) — nothing else in this file
server/src/test/java/io/flowcatalyst/server/transport/**   §4
README.md (env table) and docs/spec/cutover.md §4 (mark the new variables Java-only)
```

Read first: `Server.buildApiAndReaper`, `Metrics.java`, `Env.java` +
`EnvReader.java`, `ServerTest`/`TestHttp` for how a real server is started
in tests, and `javap -cp ~/.m2/repository/io/javalin/javalin/7.2.3/javalin-7.2.3.jar io.javalin.config.JettyConfig`
plus Javalin's `JavalinServer` (the jar's classes, or its sources jar if
present in `~/.m2`) for how the default connector is created — the spec
asks you to say which way (§1) was needed to own the API port.

## Rules

- `-Werror`, Jackson 3, never `mvn install`, one Maven run at a time on your
  worktree's `target/`; `export JAVA_HOME=$(mise where java)`.
- Free ports only (>20000), pick them at test time; stop every server you start.
- No Javalin `before`/`after` filter for `Alt-Svc` (§1) — transport-level.
- Nothing about routes, JSON, cookies, the parity harness or the e2e changes.
- `keytool` from `java.home`, no `openssl` dependency in tests (§4.2).
- If the quiche native library does not load on this machine, HTTP/3's
  end-to-end test is an `Assumptions.assumeTrue` with the load error in
  the message, and the report says so — do not spend more than one hour on
  it; the connector must still be built and `Alt-Svc` still asserted.

## Tests you owe

§4 in full, with the three mutants run, confirmed and reverted, and the
killing test named in the report for each. Full `mvn -q -pl server clean
test` green before you report.

## Report

Which Javalin hook owned the API port; the dependency list with versions;
the protocol each test observed (`response.version()`); whether quiche
loaded here; the `Alt-Svc` header as sent; anything the spec got wrong
(`// SPEC?`). Commit on your branch; do not merge.
