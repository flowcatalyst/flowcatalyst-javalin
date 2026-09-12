# Platform parity harness

Runs the same scenarios (`parity/scenarios/**/*.json`) against Go's `fc-server` and the
in-process Java `Server` on cloned, byte-identical seed databases, then normalises and diffs
every response. See `docs/spec/parity-harness.md` for the design and `docs/process/briefs/2026-09-05-p5-parity-harness.md`
for what this module covers.

Requires a Go 1.27+ toolchain (or `PARITY_GO_BIN_DIR` pointing at prebuilt `fcdev`/`fc-server`
binaries) and does not build/run without one — `mvn test` stays green regardless (`ParityRunTest`
skips itself).

CLI: `java -cp <classpath> io.flowcatalyst.parity.ParityMain [--go-src <path>] [--go-bin-dir <path>]
[--scenarios <dir>] [--only <glob>] [--report <dir>]`. Exit code is non-zero on any `DIFF`, `ERROR`,
stale `expected-diffs.json` entry, false `covers` claim, or coverage under threshold.

Test: `PARITY_GO_SRC=/abs/path/to/flowcatalyst-go mvn -pl parity -am test -Dtest=ParityRunTest
-Dsurefire.failIfNoSpecifiedTests=false` runs `ParityRunTest`, which asserts
the harness itself didn't break (no step `ERROR`) — a `DIFF` is expected and reported, not asserted
away; the report lives under `parity/target/parity-report/`. Add `PARITY_ONLY=<glob>` (e.g.
`smoke/*`, `applications/*`) to run just one scenario group — useful for a scenario-authoring agent
running only the group it owns, without editing the test. Give `PARITY_GO_SRC` an absolute path:
surefire's working directory is `parity/`, so a repo-root-relative `../flowcatalyst-go` resolves to
`flowcatalyst-javalin/flowcatalyst-go` and the run dies with a misleading "Cannot run program go".
Without `-Dtest=ParityRunTest`, `-am` also runs the whole server suite first.

Go HEAD `cb83fd5` cannot bootstrap a fresh database (`docs/backlog.md`: the Go seeder writes
`schema_type = 'JSON'`, which migration 051's own CHECK constraint rejects) — the harness fills the
event-type catalogue with the Java seeder between two Go `fcdev init` runs; see `Seed`'s class doc
for the exact sequence and why it is safe (both seeders are idempotent by code/version).
