# Spec — the function service in the admin SPA (package H)

Owner go-ahead 2026-09-22. The function service has a complete, documented API
(`GET /api/openapi-functions.json`, `docs/spec/function-openapi.md`) and no screens: nothing under
`frontend/src/pages` shows a function, a version, a host, a domain or a policy. A promoted
function's subscriptions, scheduled jobs and dispatch pool already appear on their own pages with
source `FUNCTION` — this package adds the function itself.

Read first: `docs/function-service-overview.md`, then `function-api.md` §4–§6,
`function-invocation.md` §3, `function-context.md` §1, `function-public-routes.md` §1,
`function-artifact-upload.md` §3. The SPA's own conventions are the code: an aggregate is an
`api/<name>.ts` module over `api/client.ts`, a `pages/<name>/` folder of a list page with drawers as
child routes, a block in `router/index.ts`, a nav entry in `config/navigation.ts`, and a
route→permission line in `stores/permissions.ts`. `dispatch-pools` is the template to copy (a
list, a create drawer, a detail drawer, 94 + 850 lines).

**The frontend is shared with the Go repo as a subtree** (`tools/frontend-drift.sh`). This work
is Java-first — there is no Go function service — so the drift check will report these files
until Go takes the subtree; say so in the report, do not "fix" the drift.

## 1. Types — from the function document, not by hand

`frontend/openapi-ts.config.ts` generates `src/api/generated` from the main lockfile. The function
API is a second document. Extend the config so the generator runs for both (two `defineConfig`
inputs, or a second config file `openapi-ts.functions.config.ts` and a second `api:generate`
script — whichever `@hey-api/openapi-ts`'s version here supports cleanly), emitting into
`src/api/generated-functions/`. The input is `../server/src/main/resources/openapi/functions.openapi.json`
(Go has no counterpart; the config's existence check pattern already handles "one repo only").
Types are generated, committed, and **never hand-edited**; `api/functions.ts` imports them.

## 2. Screens

All under the existing authenticated layout, permission-gated (§4), tenant-scoped by the server
(the SPA sends no client filter; the server's reach rule decides what a tenant sees).

### 2.1 Functions — `/functions`

List page: address, owner (client name or "Platform"), runtime, live version (or "—"), status,
updated. Filters: application, client (anchor only), status. Row click → detail drawer.

**Detail drawer** `/functions/:address` — tabs:

- **Overview**: address, owner, runtime, description, created/updated; the live alias and its
  version; **Hosts** — the status document (`GET …/status`): each host in the pool, its state,
  what it reports for this function's versions (`LOADED` / `REGISTERED` / `FAILED` + reason),
  last heartbeat age. Actions: edit description, **delete** (confirm dialog naming what cascades:
  versions, aliases, routes, trigger objects, artifacts).
- **Versions**: the version list — number, state (`PUBLISHED` / `READY` / `LIVE` / `RETIRED`),
  digest (short, copy button), signer identity when recorded, published at, artifact ref. Row
  expand → the manifest, pretty-printed and read-only. Actions per row: **Promote** (enabled only
  when `READY`; confirm), **Retire** (confirm; disabled for the live version). A **Publish**
  button opens a drawer (§2.2).
- **Config & secrets** (`function-context.md`): two tables of the keys the *live* manifest
  declares (and any key with a value that the manifest no longer declares, flagged "not declared").
  Config: key, value, inline edit → `PUT …/config` (the whole map, as the API takes it). Secrets:
  key, "set"/"not set", set/replace (value never displayed after save; `PUT …/secrets/{key}`),
  delete. A banner when any declared key is unset: "promote will refuse until every declared key
  has a value" (`SETTINGS_MISSING`). When the platform has no `FLOWCATALYST_APP_KEY` the secret
  routes answer 503 — show that as a disabled state with the reason, not as an error toast.
- **Public routes**: the live manifest's `public[]` entries with each hostname's verification
  state (joined from `GET /api/function-domains`), and a link to the domain (§2.3).
- **Invoke** (developer aid, `platform:function:version:invoke` only): method, path, headers,
  body → `POST` through the SPA's client to the function host? **No** — the SPA talks to the
  platform, and the host is a separate origin the SPA cannot reach reliably. Instead show the
  ready-made `fcdev fn invoke …` / `curl` lines for the private entry, with the address and
  version filled in, and a copy button. No network call.

### 2.2 Publish drawer

A file input for the jar, a textarea/file input for `manifest.json`, an optional file input for
the Sigstore bundle. On submit: sha256 the jar in the browser (`crypto.subtle.digest`), `PUT
/api/functions/{address}/artifacts/{digest}` with the raw bytes (`application/octet-stream` — the
SPA's client must be able to send a non-JSON body; extend `api/client.ts` minimally if it cannot,
without changing any existing call's behaviour), then `POST …/versions` with the returned
`artifactRef`, the digest, the manifest and the bundle. Show upload progress; surface the
platform's error codes verbatim (`DIGEST_MISMATCH`, `ARTIFACT_TOO_LARGE`, `MANIFEST_INVALID` and
its `details`, `SIGNATURE_*`, `ARTIFACT_STORE_NOT_CONFIGURED` ⇒ "the platform has no artifact
store configured"). On success close the drawer and land on the new version row.

### 2.3 Domains — `/function-domains`

List: hostname, owner, state (`PENDING` / `VERIFIED`), claimed at, verified at. **Claim** drawer:
hostname (+ client for an anchor). The detail drawer shows the TXT record to create (name and
value, copy buttons), a **Verify** button (calls the route; shows the result and the reason on
failure), and **Release** (confirm; warn if any live manifest routes to it — the platform
refuses with the code it uses; surface it). `.localhost` hostnames show "auto-verified in dev
mode" instead of a TXT record.

### 2.4 Policies — `/function-policies`

Anchor-only. List: owner (client or Platform), signers (issuer + subject, one per line), limit
ceilings, updated. Detail drawer edits the signer list and the ceilings (`PUT …/{owner}`), with the
platform's defaults shown beside each ceiling. A client's policy that does not exist yet reads as
"platform defaults — no policy" with a Create action.

### 2.5 Pools — a read-only card

On the functions list page, a small panel from `GET /api/function-pools`: pool name, hosts
(count, states), functions loaded. No actions.

### 2.6 Manifest editor (package M4, `docs/spec/function-manifest-authoring.md`)

A `ManifestEditorDrawer` on the function detail (`src/pages/functions/ManifestEditorDrawer.vue`),
opened three ways, each just a different starting manifest (or none) passed into the same drawer:

- **New manifest** — a toolbar button on the Versions tab (`function-ui.md` §2.1). Starts from the
  same template `fn init --manifest-only` writes: `runtime: jvm`, entrypoint
  `com.example.fn.Handler`, one `platform`-authenticated `GET /hello` endpoint.
- **Edit as new version** — a Versions-tab row action. Fetches that version's stored manifest
  (`getVersion`) and starts from it.
- **Import file** — a file input on the Versions tab toolbar; also present inside the drawer
  itself. Parses the chosen file as manifest JSON.

Two synced views of one model (a plain `PublishManifestRequest`, never a hand-rolled type —
U8 still applies): a **form** covering every field of the JSON Schema (`function-manifest.schema.
json`) — runtime/entrypoint/pool/warm, an optional limits override, lists of endpoints (with a
CORS sub-form), subscriptions, schedules (payload as its own JSON textarea, nothing else is),
public routes (alias prefixes as a comma list), config/secrets/httpAllow (comma lists), and
database connections — and a **JSON** textarea. Editing the form re-serialises the JSON view on
every change; editing JSON re-parses into the form on every valid edit, and on an invalid edit
leaves the form showing the last valid model (disabled, with a warning) until the text parses
again — never a half-applied document. A top-level `"$schema"` in imported/pasted JSON is
discarded (it is `parseStrict`'s editor hint, M1 §2, never part of the wire shape) and reinjected
on export.

**Validate** (`functionsApi.checkManifest`, M2.2) sends the live model plus a typed alias
(default `live`) and renders: an invalid manifest's errors (code + message; every error's
`details.pointer`, spec `manifest-all-errors.md`, attaches it to the form field the pointer names —
`/endpoints/1/auth` under endpoint 1's Auth field — falling back to its section (a whole-array
pointer, or a pre-pointer response with no `details.pointer` at all, matched by the old
code-prefix rule), then to the flat error list, which always shows every error regardless), or a
valid manifest's promote plan, rendered by `manifestPlanText.ts#renderPlanLines` — line-for-line
the same `+`/`~`/`-`/`!` format as `fcdev fn validate` (`ValidateCommand.java#printText`, which
also now prints each error as `code pointer: message`; the two are kept in lockstep by hand, there
is no shared source).

**Export** (`manifestModel.ts#exportManifest`) downloads `manifest.json`: `"$schema"` first
(pointing at `GET /api/schemas/function-manifest.json` on the SPA's own origin), then every field
present in the model, in the JSON Schema's own property order, with absent optional fields left
out entirely — an import/export round trip reproduces exactly what was imported, plus `$schema`.

**Publish with this manifest** opens the existing Publish drawer (§2.2) with the live edited model
pre-filled into its manifest text (`PublishVersionDrawer`'s new `initialManifest` prop) — the jar
is still chosen there as before; the drawer's own upload/publish flow (U3/U4) is unchanged.

No new dependency: no JSON-schema form library, no code editor, no ajv — the server remains the
only validator (`checkManifest`), matching M4's rule.

E2E (`e2e/tests/functions.spec.ts`, extends E1 as step 12 — see the table, outside
`frontend/`): open the editor from a version, add a subscription, Validate shows `Create`,
publish, promote.

## 3. Navigation

One group "Functions" in `config/navigation.ts` after "Dispatch Jobs": Functions, Domains,
Policies (icon choices from the existing PrimeIcons set). Entries hide when the user lacks the
route's permission, as every other entry does.

## 4. Permissions (`stores/permissions.ts`)

| Route | Permission |
|---|---|
| `/functions` | `platform:function:function:view` |
| `/function-domains` | `platform:function:domain:manage` |
| `/function-policies` | `platform:function:policy:manage` |

In-page actions gate on their own permission: create/edit/delete `function:manage`; publish
`version:publish`; promote `alias:promote`; retire `version:publish`; config and secrets
`secret:manage`; invoke lines `version:invoke`. A gated action is hidden, not disabled, matching
the SPA's existing rule (check how `dispatch-pools` does it and match).

## 5. Error and empty states

The SPA's existing patterns: the client's toast for transport errors, field errors from
`details`, an empty-state message with the first action. Function-specific: a function whose live
version has a **corrupt manifest** comes back from the list without a `liveVersion` — render "—",
never crash the list (this exact case took the platform down once; `function-api.md` §4.4).

## 6. Tests — what must fail when broken

`frontend/tests/*.test.ts` (vitest, as the existing ones), plus one e2e flow.

| # | Behaviour | Mutant |
|---|---|---|
| U1 | `stores/permissions.ts` maps each of the three routes to the permission above; the router guard refuses `/functions` to a user without `function:view` and admits one with it (extend `route-permission-guard.test.ts`'s pattern) | drop a mapping |
| U2 | navigation hides the Functions group for a user with none of the three permissions and shows exactly the entries the user's permissions admit | show all |
| U3 | the publish drawer computes the sha256 of the chosen file and uploads **before** it publishes, and publishes with the ref the upload **returned** (mock the API module; assert call order and arguments; a fixed byte array with a known digest) | publish first; use a locally built ref |
| U4 | the publish drawer surfaces `DIGEST_MISMATCH` / `MANIFEST_INVALID` with its `details` in the form, and does not call publish after a failed upload | swallow the error; publish anyway |
| U5 | the versions tab enables Promote only for `READY`, and Retire never for the live version | enable always |
| U6 | the function list renders a row whose `liveVersion` is absent as "—" and renders the rest | throw on null |
| U7 | the secrets table never renders a stored value: after a set, the cell reads "set" (assert the value string is absent from the DOM) | echo the value |
| U8 | the generated function types are used, not hand-written: a conventions test (like `tests/conventions/`) asserts `api/functions.ts` imports from `generated-functions` and that no file under `src/api` declares an interface named like a generated one | — |
| U9 | (M4, §2.6) `manifestModel.ts#exportManifest(parseManifestText(text))` round-trips the sample manifest exactly (plus `$schema`), and keys come out in the JSON Schema's own property order at the top level | drop a field from the export walk; reorder the key list |
| U10 | (M4) `manifestPlanText.ts#renderPlanLines` renders create/update/delete/settings-missing/no-changes lines in `ValidateCommand.java`'s exact `+`/`~`/`-`/`!` format, both as a pure function and as rendered by the drawer's Validate result | swap a line's prefix; render a plan generically instead of via `renderPlanLines` |
| U11 | the manifest editor: a form edit re-serialises the JSON view; a valid JSON edit updates the form; an invalid JSON edit leaves the form on the last valid model (disabled, with a warning) rather than corrupting it | drop the form→JSON sync; apply an unparseable document to the model anyway |
| U12 | Validate sends the drawer's **current** (live-edited) model, not a snapshot taken at mount, and a server `ENDPOINT_*` error is additionally rendered under the Endpoints section next to the flat error list | send a captured copy from mount; always render zero endpoint-section errors |
| U13 | (`manifest-all-errors.md` §3) An error's `details.pointer` attaches it under the exact field the pointer names (`/endpoints/1/auth` → endpoint 1's Auth field), not just the section; a pointer that names no rendered field still appears in the flat error list | attach every error to its section regardless of pointer, so a field-level pointer never reaches its field |
| U13 | "Publish with this manifest" opens the publish drawer with the **exact current edited model** (not the manifest the editor started from); the publish drawer's own `initialManifest` prop pre-fills its manifest text so publish can proceed without re-choosing a manifest file | pass the original `initialManifest` prop through unedited; ignore the prop in the publish drawer |
| E1 | **e2e** (`e2e/tests/functions.spec.ts`, Playwright, against the Java platform as the others run): as the bootstrap admin, claim `hello.localhost`, publish the sample jar (`examples/function-hello`, built by the flow's setup or a checked-in fixture jar) with its manifest through the SPA, watch the version reach `READY` (the in-process fcdev host reports it), promote, and assert the Hosts panel shows `LOADED` and the Public routes tab shows `hello.localhost` verified | — (integration pin) |
| E2 | step 12 of E1: edit the live version as new, remove its subscription, Validate shows `- subscription … (delete)`, publish from the editor, promote, Validate again shows `no changes` (removal, not addition: adding needs a second seeded event type) | the dry run and promote disagreeing — the re-validate is not empty |

`vue-tsc -b` (the `build` script) must pass — it is the type check. `oxlint` clean.

## 7. Slices

| Slice | Contents |
|---|---|
| **H1** | generated types (§1), `api/functions.ts` (every operation of the document), routes, nav, permissions (§3, §4), the functions list + Overview tab + delete; U1, U2, U6, U8 |
| **H2** | Versions tab + Publish drawer (§2.1, §2.2); U3, U4, U5 |
| **H3** | Config & secrets, Public routes, Invoke tabs; Domains; Policies; the pools card; U7 |
| **H4** | the e2e flow E1; `make frontend` rebuilds the embedded SPA; `docs/functions.md` gains a "from the UI" section; the drift note |

The frontend toolchain: `pnpm` in `frontend/` (`pnpm install`, `pnpm test`, `pnpm build`,
`pnpm api:generate`). No Maven is involved until H4's `make frontend`.
