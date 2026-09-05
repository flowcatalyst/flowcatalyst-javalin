# Brief — Phase 5 S1: one parity scenario per lockfile group (three agents)

Orchestrator: Fable. Coders: three Sonnet agents, medium effort, own
worktrees, **scenario JSON only — no harness changes, no server changes**.
The harness (`parity/`, `docs/spec/parity-harness.md`) is on main; you write
`parity/scenarios/<group>/<name>.json` files in the format §3 defines and
`parity/scenarios/smoke/event-types.json` demonstrates. Each scenario is a
specification argument first: its top-level `"why"` string (free text, the
runner ignores it) names the behaviour a wire diff there would break.

## Your groups

| Agent | Lockfile tags (operation counts) |
|---|---|
| S1-A | applications (25), clients (16), roles (16), anchor-domains (4), auth-configs (4), config (3), platform-config (4), platform (5), docs (3) |
| S1-B | principals (29), service-accounts (14), portal-users (5), oauth-clients (10), identity-providers (5), idp-role-mappings (3), email-domain-mappings (8), login-attempts (1), reset-approvals (3), webauthn (6) |
| S1-C | event-types (8), events (7), processes (8), subscriptions (7), connections (7), dispatch-pools (8), dispatch-jobs (12), scheduled-jobs (15), audit-logs (9) |

Operations per tag come from `server/src/main/resources/openapi/openapi.lock.json`
(`paths.*.<method>.tags`, `operationId`, `requestBody` schema, responses).
The Java route handlers under `server/src/main/java/io/flowcatalyst/platform/<aggregate>/api/`
and their `*ApiTest` show request bodies that are valid today; the spec
`docs/spec/<aggregate>.md` names the documented 4xx codes.

## What every group scenario contains

One file per tag (a big tag may split into two files), and for each tag:

1. **The happy path in order**: create → get by id → list (with each filter
   the lockfile documents, one step per filter) → update → get → the
   state-changing verbs (activate/deactivate/archive/rotate/…) → delete →
   get again (404 shape). Batch/bulk routes included. Capture every id the
   response returns (`"capture": {"xId": "/id"}`) — an uncaptured id shows
   up as a diff and that is your bug, not the harness's.
2. **Every documented 4xx** once: validation (400), unknown id (404),
   duplicate code (409), the business-rule refusals the spec names.
3. **Tenant confinement**: the smoke scenario's tail is the template —
   create a second client and a `CLIENT`-scoped principal with the role
   the group needs, log in as it, and try the group's read and write
   routes against the anchor client's rows. The refusal shape (403 vs 404)
   is exactly what the harness must compare; do not `expect` a status on
   those steps.
4. **Ordering**: lists compared in order by default; mark `unordered` only
   where the spec says the order is unspecified, and say why in `"why"`.
5. `"covers"`: every operationId the file exercises. The runner fails a
   false claim, and §7's coverage table is the acceptance criterion:
   your tags' operations must all be hit.

Rules:

- `expect.status` only on steps a later step depends on (a create whose id
  you capture, a login). Everywhere else the diff is the oracle.
- No `ignore` entries without a reason string, and expect the reviewer to
  push back on each one. Do not add to `parity/expected-diffs.json` — the
  orchestrator triages diffs; you report them.
- Names and codes carry `${run}` so two runs never collide and both sides
  see identical values.
- Passkey steps use `"authenticator": "register" | "assert"` (S1-B,
  webauthn tag): begin → authenticator → complete, for registration and
  authentication; list and revoke in between.
- Mail-carried secrets (invites, reset tokens, e-mail PINs) are out of
  reach in v1: exercise the request/response surface (the 200 that says
  "sent") and stop there.

## Running

`export JAVA_HOME=$(mise where java)`; from your worktree root
`PARITY_GO_SRC=/Users/andrewgraaff/Developer/flowcatalyst-go mvn -q -pl parity -am test -Dtest=ParityRunTest -Dsurefire.failIfNoSpecifiedTests=false`
runs every scenario (add `-Dparity.only=<glob>` if the runner supports it —
read `ParityRunTest`/`ParityMain` for the switch), report under
`parity/target/parity-report/`. The Go repo is READ-ONLY. Never two Maven
runs at once in your worktree. A run takes a few minutes (Go build + seed).

Expect `DIFF` and `ERROR` results — they are the product. An `ERROR` on
one side only is usually your scenario (a body the server rejects, a wrong
pointer): fix those. A `DIFF` is a finding: leave it, report it.

## Report

Per scenario: the operations covered, the `"why"` of each confinement
step, and **every DIFF verbatim from `report.md`** with your one-line
guess per §9 (Java defect / Go defect / ruling on record — cite the ruling
id when you can find it in `auth-core.md` §0.5, `auth-identity.md` §0.5,
`CONVENTIONS.md` or `docs/backlog.md`). Every `ERROR` you could not turn
green, with the response body. The coverage table for your tags. Commit
the scenarios on your branch; do not merge.
