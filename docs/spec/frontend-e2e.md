# Spec — frontend end-to-end, both servers (`e2e/`)

Phase 5's second item. The parity harness (`parity-harness.md`) compares the
wire. This compares what a person sees: the Vue SPA driven by a browser
through every screen, **the same flows against Go and against Java**, on a
fresh database each. A flow that passes on Go and fails on Java is a
finding; a flow that fails on both is a frontend bug and out of scope here.

Status 2026-09-05: design (orchestrator). Flows are a Sonnet unit per group
after the parity harness lands; the runner is a small piece of the same brief.

## 0. Why the browser, when the wire is already compared

Three things the wire comparison cannot see:

1. **Which requests the SPA makes, in what order, with what it read from
   the previous response.** The parity corpus is hand-written; the SPA is
   the real client. A field it reads that Java spells differently shows up
   here as a blank cell or a broken button, not as a diff in a scenario
   nobody wrote.
2. **The routes that only exist for the browser**: the SPA shell itself,
   the method-mismatch shim (`GET /auth/login` renders the app), cache
   headers on hashed assets, the OIDC callback landing, the logout redirect.
3. **Cookies as the browser handles them** — `SameSite`, `Secure`,
   `HttpOnly`, path — which a `CookieManager` accepts more leniently than
   Chromium does.

## 1. Where it lives, what it uses

- `e2e/` in the Java repo: a pnpm project, TypeScript, `@playwright/test`,
  Chromium only. The Go repo is read-only and cannot host it.
- One fixture per side, chosen by `E2E_SIDE=go|java`; `pnpm e2e` runs the
  whole suite against one side. `pnpm e2e:both` runs Go first and Java
  second and prints a two-column result table — the artefact the triage
  reads.
- The SPA build under test is **the same on both sides**: Go embeds
  `frontend/dist` from its tree at `go build`; Java embeds
  `server/src/main/resources/frontend`, refreshed by
  `tools/sync-frontend.sh` (an out-of-tree Vite build of the Go repo's
  `frontend/src`, stamped with the source commit in
  `server/src/main/resources/frontend.source-commit`, outside the served tree). The runner fetches `/index.html` from both
  sides, blanks Vite's per-build asset hashes (two builds of one source
  differ only in `index-<hash>.js`), and refuses to run when the documents
  still differ — a mismatched build is a
  finding of its own, not a source of forty spurious ones. (The Go repo's
  `frontend/dist` is a local build artefact, not committed; the owner
  refreshes it with `make frontend` there.)

## 2. Starting a side

Both sides start through their own `fcdev`, which is also how a developer
runs them, so the e2e run exercises the developer path for free:

| Side | Command | Notes |
|---|---|---|
| Go | `go build -o <scratch>/fcdev ./cmd/fcdev` (in the Go repo, output out of tree) then `fcdev init --yes …` and `fcdev start --embedded-db-path <scratch>/pg --embedded-db-port <free> --embedded-db-reset` | Go's embedded Postgres downloads binaries on first use |
| Java | `mvn -q -pl fcdev -am package -DskipTests` then `java -jar fcdev/target/<shaded jar> init --yes …` and `… start` with the same flags | same flags by design (`fcdev-commands.md`) |

Shared env, both sides: `FC_API_PORT` (a free port per side),
`FC_JWT_SIGNING_KEY_PATH` and `FLOWCATALYST_APP_KEY` generated per run,
`FC_WEBAUTHN_RP_ID=localhost` and origins = the side's base URL, no SMTP
(both sides log mail — §4 reads it back), rate limits at defaults, the
same bootstrap admin as the parity harness. Readiness = `/health` 200.
Every flow starts from a **fresh database** (`--embedded-db-reset` per
side per run; flows within a run share it and create their own rows under
unique names, the same no-truncation rule the Java and Go suites use).

## 3. Flows — one file per screen group, every screen visited

Assertions follow CLAUDE.md: the *observable outcome after a reload*, never
"the drawer opened". A created thing is asserted by navigating away, coming
back, and finding it in the list; a deleted thing by its absence after
reload; a permission by the 403 banner *and* the missing row.

- **auth**: login (wrong password shows the error and no session; right
  password lands on the dashboard); logout (cookie gone, `/dashboard`
  bounces to login); profile page; change password then re-login with the
  new one; forgot password → the link from the server log (§4) → set
  password → login; the SPA shell on a deep link when unauthenticated
  returns to that deep link after login.
- **2fa**: enrol TOTP on the profile page (the setup component shows the
  secret as text — read it, compute codes in the test with `otpauth`),
  confirm, log out, log in through the challenge, use a recovery code,
  trust the device and log in without a challenge, admin resets 2FA from
  the user drawer and the challenge is gone.
- **passkeys**: Playwright's CDP virtual authenticator (`WebAuthn.enable`,
  `addVirtualAuthenticator` with `ctap2`, `internal`, resident keys,
  user-verified); register from the passkeys section, list it, log out, sign
  in with it, revoke it, sign-in fails.
- **tenancy**: clients list/create/theme; users create, then the
  **confinement** flow: a client-scoped user logs in, sees only their
  client's rows on every list page, gets the permission-denied modal on an
  anchor-only screen. This flow is the one that must never be skipped.
- **catalogue**: applications (create drawer, detail, developer pages and
  versions), event-types (create, add schema, finalise/deprecate, archive),
  events list + detail + debug page, processes create/edit, subscriptions,
  connections, dispatch pools, dispatch jobs list + debug page, scheduled
  jobs + instances + instance detail.
- **authorization**: roles list/create/edit, permissions page, sync-platform
  button.
- **authentication admin**: oauth clients (create, rotate secret shows the
  new one once), identity providers, email domain mappings, anchor domains,
  reset approvals page (queue a request via the API, approve it in the UI).
- **identity**: service accounts (create, credential shown once), portal
  users, developer users.
- **platform**: audit log (the rows the earlier flows produced are there),
  login attempts (the failed login from the auth flow is there), CORS
  origins, settings names and theme, docs page.

Each file names, in a comment at the top, the Go screen it drives and the
parity scenarios (S1/S2 groups) that cover the same routes on the wire, so
a failure can be cross-read.

## 4. Mail without a mail server

Both servers log a mail they cannot send, body included (Go
`email.LogService`, Java's logging transport). The runner captures each
side's stdout to a file, and a helper `lastMailTo(address)` reads the newest
message for that recipient from it and extracts the first link. That is
enough for reset and invite flows and costs nothing to run. (The same trick
serves the parity harness's mail-carried flows, §8 there — preferred over an
SMTP sink.)

## 5. Result and triage

Per flow, per side: pass/fail with the Playwright trace on failure.
`pnpm e2e:both` exits non-zero when any flow differs between sides or fails
on Java. Triage is the parity harness's §9 rule, applied to a screen: Java
defect (fix + a wire test in `server` that would have caught it), Go defect
(backlog + the flow marked `expectDiff` with a ruling), deliberate ruling.
A flow that fails on both sides is filed as a frontend issue and marked
`fixme` so it stops the run for neither side.

## 6. Sizing and order

Runner + fixture + the auth group first (proves the loop on both sides).
Then the groups above, three Sonnet agents, one brief each, orchestrator
reviews every assertion for "works, not exists". Roughly forty flows;
2–3 days of agent time once the parity harness's seeds and env are reusable.

## 7. Out of scope

Visual regression, accessibility, performance, mobile viewports, browsers
other than Chromium, the Go frontend's own unit tests (vitest, stays in the
Go repo).
