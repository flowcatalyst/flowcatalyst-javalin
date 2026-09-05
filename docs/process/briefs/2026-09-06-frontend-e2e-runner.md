# Brief — frontend end-to-end: the runner, the fixtures, the auth group

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. Design:
**`docs/spec/frontend-e2e.md`** — read all of it; section numbers below are
its. This unit builds the runner and proves the loop with the `auth` group
(§3); the other groups are later briefs.

## What you build

```
e2e/package.json            pnpm; @playwright/test pinned; scripts e2e, e2e:both, e2e:go, e2e:java
e2e/playwright.config.ts     Chromium only; baseURL from E2E_BASE_URL; trace on first retry
e2e/runner/                  start/stop a side (§2), the index.html gate (§1), mail-from-log (§4)
e2e/fixtures/                the admin login fixture, the client-scoped principal fixture
e2e/tests/auth.spec.ts       the auth flows of §3, one test per bullet
e2e/README.md                ten lines: prerequisites, the three commands, where the report is
```

The Java repo's root `.gitignore` gets `e2e/node_modules`, `e2e/test-results`,
`e2e/playwright-report`. No other file outside `e2e/` changes; if you think one
must, stop and say so.

## Starting a side (§2) — exactly

- **Go**: `go build -o <scratch>/fcdev ./cmd/fcdev` from
  `/Users/andrewgraaff/Developer/flowcatalyst-go` (READ-ONLY tree; the
  output goes to your scratch dir). Then `fcdev init --yes --database-url
  <url>` is **not** used — the embedded path is: `fcdev init --yes
  --admin-email … --admin-password … --code e2e --name E2E --root <scratch>
  --embedded-db-path <scratch>/pg-go --embedded-db-port <free>` if `init`
  supports the embedded flags (read `cmd/fcdev/init.go`; if it only takes
  `--database-url`, start the embedded Postgres through `fcdev start` first
  and run `init` against its URL — say which in the report), then `fcdev
  start --api-port <free> --metrics-port <free> --embedded-db-path
  <scratch>/pg-go --embedded-db-port <same> --embedded-db-reset --pid-file
  <scratch>/go.pid`. Go's `fcdev` downloads Postgres binaries on first use.
- **Java**: `mvn -q -pl fcdev -am package -DskipTests` once (the shaded jar
  is `fcdev/target/flowcatalyst-fcdev-*.jar`, main class
  `io.flowcatalyst.fcdev.FcDev`), then the same `init` / `start` flags —
  `docs/spec/fcdev-commands.md` says they match by design; report any that
  do not.
- Readiness: `GET /health` 200 on the API port, 60 s budget. Stop: SIGTERM,
  then kill after 10 s. Capture each side's stdout to
  `e2e/test-results/<side>.log` — §4 reads mail from it.
- Env for both (§2): `FC_JWT_SIGNING_KEY_PATH` (generate one RSA-2048 PKCS#8
  PEM per run with `openssl genpkey`), `FLOWCATALYST_APP_KEY` (32 random
  bytes, standard base64 with padding), `FC_WEBAUTHN_RP_ID=localhost`,
  `FC_WEBAUTHN_ORIGINS=http://localhost:<port>`, no `FC_SMTP_HOST`. The
  admin is `e2e-admin@example.com` / a fixed passphrase without identity
  words. Playwright's `baseURL` is `http://localhost:<port>` (not 127.0.0.1
  — the cookie is `Secure` and Chromium treats `localhost` as a secure
  context; `127.0.0.1` too, but keep one spelling everywhere).

## The SPA gate (§1)

Before any test the runner fetches `/index.html` from both sides and
compares the bytes. The Java side serves `server/src/main/resources/frontend`
(refresh with `tools/sync-frontend.sh` before you run — do run it); the Go
side serves whatever `frontend/dist` its tree had at `go build`, which you
cannot refresh (read-only). If they differ, print both sides' asset
hashes and the Java copy's `server/src/main/resources/frontend.source-commit`,
and **continue only with `E2E_ALLOW_SPA_MISMATCH=1`**, marking the run's
report "SPA revisions differ". Say in your report whether they differed.

## The auth group (§3), as tests

Each test states in its title the outcome it pins. Assertions are on
reloaded state, never on a transient element (CLAUDE.md):

1. wrong password → the error text is shown and `GET /auth/me` (via
   `page.request`) is 401;
2. right password → lands on `/dashboard`; reload keeps the session;
3. logout → `/dashboard` bounces to the login page; the cookie is gone;
4. deep link while logged out → after login the URL is the deep link;
5. profile page renders the admin's email;
6. change password → logout → old password fails, new one works → change
   back;
7. forgot password → the link from the server log (§4: newest mail to that
   address, first `http…` in the body) → set a new password → login.

`pnpm e2e:both` runs the suite against Go, then Java, and prints one table:
test × side → pass/fail, exit non-zero on any Java failure or any test
that passes on one side and fails on the other (§5).

## Tests you owe on the runner itself

Small unit tests (vitest is fine, or Playwright's own test runner over
pure functions): the mail-from-log parser (newest message for an address,
first link, ignores other addresses), the port picker, the index.html
gate decision. Break each once and watch it fail.

## Report

The three commands and their timings on this machine; the table from
`pnpm e2e:both` verbatim; whether the SPA revisions differed; every test
that passed on one side and failed on the other, with the trace path;
anything the design got wrong (`// SPEC?`). Commit on your branch; do not
merge.
