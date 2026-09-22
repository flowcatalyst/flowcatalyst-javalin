# Function backlog, 2026-09-22 — five small units

Origin: `docs/backlog.md` §"Function API gaps the SPA exposed", §"`fn config set` / `fn secret set`
on a function that does not exist yet", §"Function hosts that stopped without deregistering
accumulate", §"The function settings routes should expose the CANDIDATE version's declared keys".
Each amends the spec it names; update that spec in the same commit. All Java-first, outside the
lockfile; the function OpenAPI document (`functions.openapi.json`, spec `function-openapi.md`) is
the contract and its coverage/conformance tests must stay green — a new route or field is
documented in the same commit or `FunctionOpenApiCoverageTest` fails.

## Unit S — server (one agent, main tree)

### S1. Settings routes: `declared` over the candidate, not only live (`function-context.md` §1)

`GET/PUT /api/functions/{address}/config` and `GET …/secrets` today compute `declared`/`missing`
from the LIVE manifest, so before the first promote they say nothing is declared while
`PromoteVersion.requireSettingsPresent` refuses with `SETTINGS_MISSING`.

- Both GET routes (and PUT config's response) accept an optional `?version=<n>`. **Absent**:
  `declared` = the ordered union of the live manifest's keys and the keys of the **newest
  non-retired version** (highest `version` whose state is `PUBLISHED` or `READY`; the live version
  itself when nothing newer exists), live's keys first, then the candidate's not already listed.
  **Present**: `declared` = live ∪ that version's keys; an unknown version ⇒
  `404 FunctionVersion_NOT_FOUND` (the same error `getVersion` gives; a non-integer ⇒
  `400 VERSION_INVALID`). A RETIRED version named explicitly is still honoured (its keys are
  historical but the caller asked).
- `missing` = `declared` minus the present keys, as today.
- The response gains `declaredBy: [{version: n, keys: [...]}]` — one entry per manifest that
  contributed (live first), so the UI can say which version wants a key. `ConfigResponse` and
  `SecretListResponse` both.
- Document `version` as a query parameter and `declaredBy` on both schemas in
  `functions.openapi.json`.

### S2. Policies: a list route and `updatedAt` (`function-api.md` §4.3)

- `GET /api/function-policies` — every **stored** policy row, ordered `client_id` (the platform
  row `FunctionOwner.key()` first, then client ids ascending), gated exactly as the two existing
  routes (`requireAnchor` + `FUNCTION_POLICY_MANAGE`). No paging: the table has at most one row per
  client. Response `{policies: [PolicyResponse…]}`. Owners without a row are not listed — the
  SPA knows the client list and renders "defaults" for the rest (it already does).
- `PolicyResponse` gains `updatedAt` (ISO instant; `null` on the `effectiveDefault` shape, which
  has no row). `ClientPolicyRepository.listAll()`.

### S3. Domain by hostname (`function-public-routes.md` §1)

- `GET /api/function-domains/{hostname}` → 200 `DomainResponse`; reach-or-404 through
  `Access.byHostname(repo, hostname, ac)` (already written for verify/release — use it, do not
  copy it); `FUNCTION_VIEW`. An invalid hostname ⇒ the same 400 the claim route gives.

### S4. `publishFunctionVersion` 400 codes (`function-openapi.md` §3, O4)

- The 400 description of `POST /api/functions/{address}/versions` names every code the operation
  can answer: `ARTIFACT_REF_REQUIRED, MANIFEST_REQUIRED, ENDPOINT_INVALID` plus the whole
  `function-registry.md` §4.3 table (`MANIFEST_UNKNOWN_FIELD, MANIFEST_INVALID, RUNTIME_INVALID,
  RUNTIME_MISMATCH, ENTRYPOINT_REQUIRED, ENTRYPOINT_INVALID, POOL_INVALID, LIMIT_INVALID,
  LIMIT_OVER_CEILING, LIMIT_NOT_APPLICABLE, TRIGGER_INVALID, TRIGGER_DUPLICATE, ROUTE_INVALID,
  ROUTE_AMBIGUOUS, DB_INVALID, CONFIG_INVALID`) and the digest/artifact codes the handler raises
  (read `PublishVersion` and the API handler; list what is actually thrown, nothing invented).
- `FunctionOpenApiConformanceTest` drives **two** 400 refusals through `assertErrorConforms`:
  a manifest with an unknown key (`MANIFEST_UNKNOWN_FIELD`) and one with `runtime: "cobol"`
  (`RUNTIME_INVALID`). Mutant: remove either code from the description ⇒ the test fails.

### S5. Stale host purge (`function-api.md` §6.2)

- `FunctionHost.PURGE_AFTER = Duration.ofDays(1)`. The heartbeat handler, inside the transaction
  that persists the host row, calls `s.hosts().deleteStale(now.minus(PURGE_AFTER), tx)` —
  `DELETE FROM fn_hosts WHERE last_heartbeat < ? AND id <> <the heartbeating host>` — and logs
  at INFO `function hosts purged` with `count` when it deleted any. The existing
  `(pool, last_heartbeat)` index serves it; one cheap statement per heartbeat, no scheduler.
- Test: a host row with `last_heartbeat` 25 h ago is gone after any other host's heartbeat; one
  23 h ago survives; the heartbeating host itself is never deleted even if its stored row is old
  (it is being updated in the same transaction — order the delete before the persist and pin
  that the host survives). Mutants: drop the `id <>` guard; compare with `>`.

### S tests (one mutant per condition; say which assertion pins which)

| # | Behaviour | Mutant |
|---|---|---|
| S1a | no live, one PUBLISHED version declaring `A` ⇒ `declared=[A]`, `missing=[A]`, `declaredBy=[{version:1,keys:[A]}]` | live-only (old code) |
| S1b | live declares `A`, newer PUBLISHED declares `B` ⇒ `declared=[A,B]`; `?version=1` ⇒ `[A]` | drop the union; ignore the parameter |
| S1c | `?version=9` ⇒ 404 `FunctionVersion_NOT_FOUND`; `?version=x` ⇒ 400 | — |
| S1d | secrets route: the same three, values never in the body | — |
| S2a | list returns the stored rows, platform first, `updatedAt` equals the row's | drop the ordering; null updatedAt |
| S2b | non-anchor ⇒ 403; anchor without the permission ⇒ 403 | drop either gate |
| S3a | reachable ⇒ 200 with the claim's shape; another client's domain ⇒ 404 (never 403) | skip the reach check |
| S4 | the two refusals conform | remove a code from the description |
| S5 | as above | as above |

`RouteGroupTest`, `FunctionOpenApiCoverageTest`, `FunctionOpenApiConformanceTest` green.

## Unit F — fcdev (one agent, worktree, `mvn -pl fcdev -am test` from the reactor, never install)

`fn config set` and `fn secret set` create the function when the platform answers 404 for its
address, exactly as `publish` does (`Publisher.ensureFunctionExists`): promote that method to
package-private static on `Publisher` and call it, do not copy it. Both commands gain the options
`publish` has for the purpose: `--manifest <file>` (default: `manifest.json` in the working
directory when it exists — the runtime comes from it), `--client <id>`, `--no-create`.

- 404 + `--no-create` ⇒ exit 1 with the platform's message (unchanged).
- 404, no `--no-create`, no manifest readable ⇒ exit 1: `function <address> does not exist and no
  manifest.json was found to create it from — pass --manifest, or run fn publish first`.
- 404, manifest present ⇒ create (runtime from the manifest, owner from `--client`), then set.
- `config get` / `secret list` are **not** changed — a read must not create.

Tests (`ConfigCommandTest`, `SecretCommandTest` against `FakePlatform`): `set` on an unknown
address with a manifest creates the function (the fake records the POST body's runtime and
address) and stores the value; with `--no-create` exits 1 and posts nothing; with no manifest
exits 1 with the message above and posts nothing; `get` on an unknown address still exits 1 and
posts nothing. Mutants: skip the create; ignore `--no-create`; create on `get`.

`docs/fcdev.md` and `docs/functions.md` (the "publish first" ordering note) updated: the order
`set` → `deploy` now works.

## Unit U — SPA (after S lands; `pnpm test`, `pnpm lint`, `pnpm build`)

- Regenerate `frontend/src/api/generated-functions` from the updated document.
- `FunctionConfigSecretsTab.vue`: drop `loadVersionManifests` and the N+1; use `declared` and
  `declaredBy` from the two routes; show "declared by v<n>" beside a key when the version is not
  live.
- `FunctionPolicyListPage.vue`: one `listPolicies()` call, merged with the client list for the
  "defaults" rows; show `updatedAt`.
- `FunctionDomainDetailDrawer.vue`: `getDomain(hostname)`; the `?owner=` carry-over goes.
- Existing vitest specs updated; the e2e `functions.spec.ts` still passes against the rebuilt jar
  (orchestrator runs it).
