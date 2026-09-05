# Frontend e2e (`docs/spec/frontend-e2e.md`)

Prerequisites: Node 24 + pnpm, Go 1.27+ (builds Go's `fcdev` out of
`../flowcatalyst-go`, read-only), a JDK (`JAVA_HOME`, or `mise where java`),
`openssl` on `PATH`. Run `pnpm install && pnpm exec playwright install
chromium` once, and `../tools/sync-frontend.sh` before a run whose Java SPA
might be stale.

- `pnpm e2e:go` / `pnpm e2e:java` — the suite against one side (each starts
  its own `fcdev`, runs, tears down).
- `pnpm e2e:both` — Go then Java, plus the `/index.html` SPA gate; prints
  one pass/fail table and exits non-zero on a Java failure or a
  cross-side mismatch.
- `pnpm test:unit` — the runner's own unit tests (mail-log parser, port
  picker, SPA gate decision).

Reports: `test-results/{go,java}.log` (each side's captured output — §4
reads mail from it), `test-results/{go,java}-report.json`, traces on
failure under `test-results/`, and the HTML report via `playwright-report/`.
