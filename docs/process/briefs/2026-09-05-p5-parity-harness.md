# Brief — Phase 5: the platform parity harness (`parity/` module) + S0 smoke

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. You are
building the runner described in **`docs/spec/parity-harness.md`** — read
it first, all of it; every section number below refers to it. The spec is
the authority; where it is silent, ask by leaving a `// SPEC?` comment and
choose the simplest thing that keeps the run honest.

You build the harness and the S0 smoke corpus (§8). You do **not** write S1–S3.

## The shape of the work, in order

1. **Module skeleton.** New reactor module `parity` (add `<module>parity</module>`
   after `fcdev` in the root `pom.xml` — that is the only root change).
   Model `parity/pom.xml` on `fcdev/pom.xml`: depends on
   `flowcatalyst-server` (compile — it runs the Java side in-process),
   `embedded-postgres` (zonky) with the same platform-binary profile block
   fcdev has, `picocli`, slf4j/logback, junit + assertj in test scope.
   Nimbus, Jackson 3 and `com.upokecenter:cbor` arrive transitively through
   `server`; do not add versions the parent does not manage. No shade plugin.
   Package `io.flowcatalyst.parity`.
2. **Go build + seed** (§1). `GoBinaries`: from `PARITY_GO_SRC` (default
   `../flowcatalyst-go`) run `go build -o <scratch>/fcdev ./cmd/fcdev` and
   the same for `fc-server`; `PARITY_GO_BIN_DIR` skips the build and uses
   prebuilt ones. `Seed`: start zonky (`EmbeddedPostgres.builder().start()`,
   as `io.flowcatalyst.testpg.TestPg` does — that class is test scope in
   `server`, so write your own thin copy), create `seed`, run `fcdev init`
   with the flags in §1, start `fc-server` on `seed` with the §2 env, poll
   `GET /health` (budget 60 s), SIGTERM it (`Process.destroy()`, wait,
   then `destroyForcibly`), then `CREATE DATABASE parity_go TEMPLATE seed`
   and `parity_java`. Read `${client.id}`, `${app.id}`, `${admin.id}` from
   `seed` (the tables are `tnt_clients`, `app_applications`, `iam_principals`
   — confirm with the jOOQ generated classes in `server`) before cloning.
3. **Two sides** (§1, §2). `Side` interface: `baseUrl()`, `stop()`.
   `GoSide` = the subprocess on `parity_go`, stdout/stderr to `go.log`.
   `JavaSide` on `parity_java`: build a `DataSource` (Hikari the way `Main`
   does — read `server/src/main/java/io/flowcatalyst/server/Main.java`),
   `Migrator.migrate(ds)`, `new Seeder(ds).run()`,
   `Env.load(Map)` with the §2 map, `new Server(env, new Server.Mode.Platform(ds),
   Server.Spa.none(), new PrometheusRegistry()).start()`; the port is
   `Running.apiPort()`. Log to `java.log` (a logback appender configured
   programmatically, or route through a file logger — keep it simple).
   Generate the RSA PEM (PKCS#8, 2048) and the app key
   (`Encryption.KEY_BYTES` random bytes, padded standard base64) once per run.
4. **Scenario model + substitution** (§3): records `Scenario`, `Step`,
   `Request`; a `Vars` class per side holding captures and built-ins;
   `${…}` substitution over every string in the request tree; the built-ins
   listed in §3 (`${totp:var}` may use `io.flowcatalyst.platform.auth.mfa.Totp`
   from `server`; `${pkce.*}` is S256 over a 43-char verifier).
5. **Runner** (§3, §4): JDK `HttpClient` per side with its own
   `CookieManager`, `Redirect.NEVER`, 10 s per request. Run the scenario on
   Go to completion, then on Java. Build the step records (§4: status, the
   named headers — one constant with a comment per entry — body as
   `JsonNode` when the content type is JSON, else text; SHA-256 for PNG and
   for `/api/openapi.*`).
6. **Normaliser** (§5) as a pure function `Normalised normalise(StepRecord, Vars, String baseUrl, Step)`
   applying the seven rules **in the spec's order**. Rule 4 uses Nimbus
   `SignedJWT.parse` on any string with two dots whose first segment
   base64url-decodes to JSON with an `alg`. Unit-test each rule in
   isolation with hand-built records — this is the part a subtle bug hides
   in, and it must be right before any scenario runs.
7. **Diff + report** (§6): a JSON structural diff producing
   `{pointer, go, java}` entries (missing on one side is a diff with `null`
   marked distinctly from JSON null — use a sentinel string `«absent»`);
   `expected-diffs.json` loading with the stale-entry rule; `report.json`
   and `report.md`; exit code rules exactly as §6 lists them.
8. **Coverage** (§7): load the lockfile from the `server` classpath
   (`/openapi/openapi.lock.json`), match `METHOD path` against templates
   (`{id}` segments), `covers` verification, `parity/surface.json` (start it
   with the S0 routes only; S3 fills it), `REQUIRED_COVERAGE = 0.0`.
9. **Entry points**: `ParityMain` (picocli: `--go-src`, `--go-bin-dir`,
   `--scenarios` (default `parity/scenarios`), `--only <glob>`, `--report`
   (default `parity/target/parity-report`)); and `ParityRunTest`, a JUnit
   test that runs the whole thing and is skipped with
   `Assumptions.assumeTrue(System.getenv("PARITY_GO_SRC") != null || System.getenv("PARITY_GO_BIN_DIR") != null)`.
   Document both in `parity/README.md` (ten lines).
10. **S0 smoke** (§8): `parity/scenarios/smoke/event-types.json` with every
    step §8 S0 lists, `covers` filled in, `expect.status` on the login and
    create steps only.

## Tests you owe (CLAUDE.md: assert that it works)

- Normaliser: one test per rule, plus the interaction that matters —
  a JWT whose `sub` equals a captured id comes out as `«adminId»` inside
  the decoded claims (rule 4 then rule 1 inside it).
- Diff: absent-vs-null distinct; array order significant unless
  `unordered`; `ignore` removes both sides.
- Allow-list: a stale entry fails the run; a matching one yields `ACCEPTED`.
- Coverage: a `covers` claim not backed by a request fails the scenario;
  template matching treats `/api/event-types/{id}` and `/api/event-types/abc`
  as one operation and `/api/event-types` as another.
- Substitution: undefined `${x}` is an error, not empty.
- For each of these, break the code once and confirm the test fails; say in
  the report which assertion pins which rule.
- `ParityRunTest` green on this machine with `PARITY_GO_SRC=../flowcatalyst-go`
  (Go 1.27 is installed; `go build` takes ~6 s; the Go repo is READ-ONLY —
  never edit anything under it).

## What the first real run will show

The S0 scenario **will produce diffs**; that is the point. Do not normalise
them away and do not edit Java to make them vanish. Put every diff in your
report verbatim with your reading of which of the three §9 closures it
looks like. The orchestrator triages. The only allow-list entry you may add
yourself is the `$schema` one (§10), cited `owner? parity-harness.md §10`.

If Go fails to start against the zonky database, capture `go.log` and stop —
report the exact stderr. Do not spend more than two attempts on a Go
startup problem; that is the orchestrator's to debug.

## Build

`export JAVA_HOME=$(mise where java)`; from the repo root
`mvn -q -pl parity -am test -Dsurefire.failIfNoSpecifiedTests=false`
(the `-am` builds `usecase`/`sdk`/`server` from source — never `mvn install`
from a worktree). `-Werror` is on: Jackson 3 is `tools.jackson`, `asString()`
not `asText()`, no deprecated calls. Never run two Maven builds at once in
your worktree. Full check before you report: `mvn -q -pl parity -am clean test`
with `PARITY_GO_SRC` set, then once without (the run test must skip cleanly).

## Report

Files created; each test and the rule it pins; the mutants you tried; the
S0 diffs verbatim with your triage guess; the Go start/stop timings; anything
in the spec you found underspecified (`// SPEC?` lines). Honest verdict on
whether the run is trustworthy.
