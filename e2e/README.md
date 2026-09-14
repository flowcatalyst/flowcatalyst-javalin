# Frontend e2e (`docs/spec/frontend-e2e.md`)

Prerequisites: Node 24 + pnpm, Go 1.27+ (builds Go's `fcdev` out of
`../flowcatalyst-go`, read-only), a JDK (`JAVA_HOME`, or `mise where java`),
`openssl` on `PATH`. Run `pnpm install && pnpm exec playwright install
chromium` once, and `make frontend` (from the repo root) before a run whose
Java SPA might be stale.

- `pnpm e2e:go` / `pnpm e2e:java` — the suite against one side (each starts
  its own `fcdev`, runs, tears down).
- `pnpm e2e:both` — Go then Java, plus the `/index.html` SPA gate; prints
  one pass/fail table and exits non-zero on a Java failure or a
  cross-side mismatch.
- `pnpm test:unit` — the runner's own unit tests (mail-log parser, port
  picker, SPA gate decision).
- Knobs: `E2E_TEST_TIMEOUT_MS` (per flow, default 60000) and `E2E_RETRIES`
  (default 1) — a discovery run wants `E2E_RETRIES=0`; `E2E_FORCE_JAVA_BUILD=1`
  rebuilds the Java jar; `E2E_GO_EMBED_TREE_SPA=1` builds Go against its own
  `frontend/dist` instead of the Java-synced SPA (see the spec §1).

Reports: `test-results/{go,java}.log` (each side's captured output — §4
reads mail from it), `test-results/{go,java}-report.json`, traces on
failure under `test-results/`, and the HTML report via `playwright-report/`.
