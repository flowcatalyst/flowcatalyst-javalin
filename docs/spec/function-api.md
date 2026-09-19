# Spec — function platform API: operations, routes, control plane (work package B, phase 1)

Design: `docs/function-runner-plan.md` §3, §4, §9. Build order: `docs/function-runner-workplan.md`
§2 B. Builds on `docs/spec/function-registry.md` (A — entities, repositories, rulings R1–R4) and
`docs/spec/function-artifacts.md` (C — `SignatureVerifier`). Java-first: there is no Go.

Phase 2 routes (test invoke, `function-routes`, `function-domains`, RouteSync) are package F.
**Trigger sync has its own spec** (`function-triggers.md`, to be written): turning manifest triggers
into subscriptions and dispatch pools collides with the application's own SDK sync (`removeUnlisted`
hard-deletes `API`/`CODE` rows it does not list) and needs its own design. Publish here leaves a
named seam for it (§5.1 step 8).

## 0. Where this departs from the workplan, and why

| Workplan says | This spec says | Why |
|---|---|---|
| "OpenAPI first: add to `sdk/openapi/openapi.json`, regenerate the lock" | the routes are **outside the lockfile**: `/api/function` joins `LockfileCoverageTest.OUTSIDE_LOCKFILE_PREFIXES`, every route is listed in `parity/surface.json` | the lockfile is Go's generated document, copied, never hand-edited (CONVENTIONS §7); `sdk/openapi/openapi.json` is a copy of it, not a source. `GET /api/dispatch/router-config` is the Java-first precedent. The wire contract is §4–§6 of this spec; an OpenAPI document for the CLI/SDK is owed to package E |
| events `fc.function.version.published` … | `platform:function:version:published` … (§3) | every platform event is `platform:<domain>:<aggregate>:<action>`; subjects must be `domain.aggregate.id` or `EventConventions.extractEntityId` breaks |
| no route manages signer policy | `GET`/`PUT /api/function-policies/{owner}` (§4.3) | without it nothing can ever be published with signatures on |
| Heartbeat "upsert `fn_hosts`" as a service | the host row is written with `UnitOfWork.inTransaction`, **no event, no audit**; only a version becoming `READY` is an operation (§6.2) | a heartbeat is telemetry every 15 s per host; an event and an audit row per beat is noise that buries the real trail |
| `GET /control/functions/desired-state` with a "`function-host` scope" | role `platform:function-host` carrying one permission, exactly as `platform:router` (§2) | `router-config-auth.md` R3′ is the precedent and scopes are not how this platform grants authority |

## 1. Layout

`platform/function/operations/` — `Access`, `FunctionEvents`, one operation per file, command
records beside them. `platform/function/api/` — `FunctionApi` (functions, versions, aliases, status,
pools), `FunctionPolicyApi`, `FunctionControlApi`. Registered in `Platform` after the existing
registrations; handlers use `io.flowcatalyst.http.{Routes,Exchange,Group}` only.

`{address}` is one path segment containing dots (`billing.invoices.create`). A test pins that the
router delivers it intact, and that `/api/functions/a.b` (two parts) is `400 ADDRESS_INVALID`, not 404.

## 2. Permissions and roles

New context `function`. `Permission` enum + `seed/Permissions` strings (`PermissionTest` pins them equal):

| Constant | Code | Gates |
|---|---|---|
| `FUNCTION_VIEW` | `platform:function:function:view` | every read |
| `FUNCTION_MANAGE` | `platform:function:function:manage` | create, describe, enable/disable, delete |
| `FUNCTION_PUBLISH` | `platform:function:version:publish` | publish, retire |
| `FUNCTION_PROMOTE` | `platform:function:alias:promote` | promote |
| `FUNCTION_POLICY_MANAGE` | `platform:function:policy:manage` | policy read and write |
| `FUNCTION_HOST_CONTROL` | `platform:function:host:control` | `/control/functions/*` |

Roles, appended to `PlatformRoles` in this order (`PlatformCatalogueTest` pins names and indexes):
`platform:function-publisher` (view, publish, promote — what a pipeline's service account holds) and
`platform:function-host` (host control only). `platform:messaging-admin` gains view, manage,
publish, promote and policy-manage (super-admin has them through `platform:*:*:*`).

**Reach** (who may touch *this* function), checked in the operation's `execute` right after the load,
through `operations/Access`:

- `Access.requireReach(AuthContext, Function)`: owner `Client(id)` ⇒ `Checks.checkScopeAccess(ac, id)`;
  owner `Platform` ⇒ `Checks.requireAnchor(ac)`. Then `Checks.checkApplicationAccess(ac,
  f.applicationId(), f.address().application().value())` — an application-scoped service account
  publishes only its own application's functions.
- Reads apply the same rule as a filter (list) or a 404 (by address — a function you cannot reach
  does not exist for you; never 403, which would confirm the address).
- Policy routes and `/control/functions/*`: `requireAnchor` + the permission. A missing credential on
  `/control/functions/*` is **401**, not the platform's usual 403 (`RouterConfigApi` precedent).

`/control/` joins `Platform.isPlatformPath` so it runs inside the authenticator; it is not public.

## 3. Events

`FunctionEvents`: source `platform:function`; subject `platform.function.<functionId>`; message group
`platform:function:<functionId>` for **every** event below, so one function's history is ordered.
(The policy event has no function: its subject is `platform.function-policy.<owner key>` and its group
`platform:function-policy:<owner key>`.)

| Type | Data |
|---|---|
| `platform:function:function:created` | `functionId, address, applicationId, clientId?, runtime` |
| `platform:function:function:updated` | `functionId, address, description?, status` |
| `platform:function:function:deleted` | `functionId, address` |
| `platform:function:version:published` | `functionId, address, versionId, version, digest, pool, signerIssuer?, signerSubject?` |
| `platform:function:version:ready` | `functionId, address, versionId, version, hostId` |
| `platform:function:version:retired` | `functionId, address, versionId, version` |
| `platform:function:alias:changed` | `functionId, address, alias, versionId, version, previousVersionId?` |
| `platform:function:policy:updated` | `owner` (`platform` or the client id), `signerCount` — never the signer list's secrets; there are none, but the rule is "counts, not contents" for policy events |

No component shadows a `DomainEvent` accessor (`DomainEventContractTest`). `address` is the rendered
string. Absent optionals are omitted (`NON_ABSENT`).

## 4. Functions and policies

Errors use the platform envelope. Codes not listed come from package A's types unchanged
(`ADDRESS_INVALID`, `LABEL_INVALID`, …).

### 4.1 `POST /api/functions` — `CreateFunction` / `CreateCommand`

Body `{applicationCode, serviceName, name, runtime, description?, clientId?}`. `serviceName` is
required — the `default` service is a tooling default (A §3.2). 201 with the function (§4.4).

Validate: `applicationCode` non-blank (`APPLICATION_CODE_REQUIRED`); `serviceName`, `name` ⇒
`DnsLabel.parse` with the field's name; `runtime` ⇒ `Runtime.parseStrict`. Execute: application by
code (`ApplicationCode.parse` first) ⇒ 404 `resourceNotFound("Application", code)`; **R1**: the
stored code must itself be a `DnsLabel`, else validation `APPLICATION_CODE_NOT_ADDRESSABLE` —
"application code '<code>' cannot be part of a function address: it must be a DNS label (a-z, 0-9,
'-'); codes with '_' or longer than 63 characters cannot own functions". `clientId` given ⇒ the
client must exist (404) and `checkScopeAccess`; absent ⇒ owner `Platform`, `requireAnchor`.
`checkApplicationAccess`. Address taken ⇒ conflict `FUNCTION_EXISTS` naming the address.

### 4.2 Reads, update, delete

- `GET /api/functions?address=<pattern>&clientId=&status=&page=&pageSize=` — `address` is a
  `FunctionAddressPattern` (`ADDRESS_PATTERN_INVALID`); `clientId=platform` selects platform-owned;
  paginated with the `apicommon` records the portal-user list uses; ordered by address; filtered by
  reach. `GET /api/functions/{address}` — 404 when absent or out of reach.
- `PUT /api/functions/{address}` — `UpdateFunction` / `UpdateCommand` `{description?, status?}`, both
  optional, "absent = untouched". `Function.describe` and, for `status`, an exhaustive switch to
  `enable()`/`disable()` (CONVENTIONS: a selecting field is routed, not modelled). One `updated`
  event. 204, no body (`ApplicationApi.update`'s precedent). **The body has no `serviceName`, `name`, `applicationCode`, `clientId` or `runtime`; a body
  carrying any of them is 400 `FUNCTION_IMMUTABLE_FIELD` naming it** (design §10.17 — silently
  ignoring a rename is how someone believes they renamed a function).
- `DELETE /api/functions/{address}` — `DeleteFunction` / `DeleteCommand`. Cascades to versions,
  aliases and routes (R4). 204. The trigger spec adds the cleanup of its platform objects.
- **`DeleteApplication` gains a guard**: conflict `APPLICATION_HAS_FUNCTIONS` ("delete its <n>
  functions first") when `FunctionRepository` lists any for the application. There is no FK, and an
  orphaned function keeps an address nobody can ever reuse.

### 4.3 Policies — `FunctionPolicyApi`

`{owner}` is a client id or the literal `platform`. `FunctionOwner` gains `static fromKey(String)` /
`String key()` with `PLATFORM_KEY = "PLATFORM"` as the one spelling of the stored key (the repository
uses it; this supersedes A §6.4's "only the repository spells it"), and `ClientPolicy.id()` returns
`owner.key()` — never null, because the audit row wants an entity id. The wire literal is lower-case
`platform`; `fromWire`/`toWire` on `FunctionOwner` hold that mapping.

- `GET /api/function-policies/{owner}` — the policy, or the **effective default** when no row exists:
  `{owner, signers: [], ceilings: {…the platform defaults…}, stored: false}`.
- `PUT /api/function-policies/{owner}` — `PutFunctionPolicy` / `PutPolicyCommand`, a full replacement:
  `{signers: [{issuer, subject, runtimes: ["jvm"]}], ceilings: {maxDurationMs?, maxConcurrency?,
  maxWasmMemoryMb?, maxDbPoolSize?}}`. Blank issuer/subject ⇒ `SIGNER_INVALID`; empty or unknown
  runtimes ⇒ `RUNTIME_INVALID`; duplicate (issuer, subject) ⇒ `SIGNER_DUPLICATE`; a ceiling ≤ 0 ⇒
  `CEILING_INVALID`. A ceiling absent ⇒ null ⇒ the platform default applies (A §4.6). Client owner ⇒
  the client must exist. 200 with the stored policy.

### 4.4 Function response

`{id, address, applicationCode, serviceName, name, applicationId, clientId?, runtime, description?,
status, live?: {version, versionId}, createdAt, updatedAt}`.

## 5. Versions and aliases

### 5.1 `POST /api/functions/{address}/versions` — `PublishVersion` / `PublishCommand`

Body `{artifactRef, digest, signatureBundle?, manifest}`. A `TxOperation` (it reads `nextVersion`
under the function's row lock and writes in the same transaction). 201
`{id, version, state: "PUBLISHED", digest, signer?: {issuer, subject}}`.

1. Load by address, `requireReach`. `DISABLED` ⇒ conflict `FUNCTION_DISABLED`.
2. `artifactRef` non-blank and its scheme one of `oci`, `file`, `s3` (`ARTIFACT_REF_INVALID`);
   `Digest.parse`.
3. Policy: `policies.findByOwner(owner)`; ceilings = `policy.ceilings(defaults)` or
   `ClientCeilings.of(defaults)` when there is no row.
4. `Manifest.parseStrict(manifest, function.runtime(), defaults, ceilings)`.
5. **Signature**, through `sealed Signatures = Required(SignatureVerifier) | Off`, chosen once at
   startup from `Env` (`FC_FN_SIGNATURES`, `required` by default; `off` is accepted **only** when
   `FLOWCATALYST_DEV_MODE` is true — otherwise startup fails. "Signatures off" must be impossible to
   set on a production task definition by typo or by intent).
   `Required`: bundle absent ⇒ validation `SIGNATURE_REQUIRED`; `verify(bundle, digest)` `Rejected` ⇒
   validation `SIGNATURE_REJECTED` with the reason's name in `details.reason`; then
   `policy.permits(signer, runtime)` — no policy row permits nothing — else authorization
   `SIGNER_NOT_PERMITTED` naming issuer and subject (the publisher needs to see exactly what string
   to put in the policy). `Off`: the bundle is stored if sent, the signer is null.
6. Same digest already published for this function ⇒ conflict `VERSION_DIGEST_EXISTS` naming the
   existing version number. (Checked before the insert so it is a 409, not a unique-violation 500.)
7. `nextVersion` → `FunctionVersion.publish` → commit with `version:published`.
8. **Trigger seam**: `TriggerSync.onPublish(scoped, function, version)` — an interface; this package
   wires `TriggerSync.none()`. `function-triggers.md` replaces it. It runs inside the transaction so
   a trigger that cannot be honoured fails the publish.

On any failure nothing persists — pinned by a test that fails at step 8 with a throwing seam and
finds no version row and no event.

### 5.2 The rest

- `GET /api/functions/{address}/versions` — newest first:
  `{id, version, state, digest, artifactRef, pool, warm, signer?, publishedBy, publishedAt, readyAt?, retiredAt?, live: bool}`.
  `GET …/versions/{v}` adds `manifest`. `{v}` not a positive integer ⇒ 400 `VERSION_INVALID`.
- `POST …/versions/{v}/retire` — `RetireVersion` / `RetireCommand`. The function's live version ⇒
  conflict `VERSION_IS_LIVE` ("promote another version first"). `FunctionVersion.retire`. 200 with the
  version. Event `version:retired`.
- `PUT …/aliases/live` — `PromoteVersion` / `PromoteCommand`, body `{version}`. **R3: the version must
  be `READY`**, else conflict `VERSION_NOT_READY` — "version <n> has not been verified by any host in
  pool '<pool>' yet". Then `Function.promote` (its own errors: `VERSION_RETIRED`, `FUNCTION_DISABLED`,
  `ALIAS_UNCHANGED`). 200 `{alias, version, versionId, previousVersion?}`. Event `alias:changed`.
  `PUT …/aliases/<anything else>` ⇒ 400 `ALIAS_UNSUPPORTED`. `GET …/aliases` lists them.
  Rollback is promoting an older `READY` version; there is no separate operation.

## 6. Control plane — `FunctionControlApi`

Both routes: `requireAnchor` + `FUNCTION_HOST_CONTROL`; 401 without a credential.

### 6.1 `GET /control/functions/desired-state?pool=<label>` (`Group.API_READ`)

`pool` ⇒ `DnsLabel` (`POOL_INVALID`). Body:

```json
{ "pool": "default",
  "functions": [ { "address": "billing.invoices.create", "functionId": "fnc_…", "versionId": "fnv_…",
                   "version": 12, "role": "live", "mode": "lazy", "digest": "sha256:…",
                   "artifactRef": "oci://…", "signatureBundle": "…", "manifest": { … } } ],
  "unload": [ { "address": "billing.invoices.create", "version": 9 } ] }
```

- For every `ACTIVE` function: its `live` version (`role: live`) and — **R3** — its newest
  `PUBLISHED` version if that is newer than live (`role: candidate`; a host fetches and verifies a
  candidate and reports it `REGISTERED`, never serves it). Each only if **its own** manifest's `pool`
  is the requested pool — a function may move pools between versions. `mode` is `warm` when the
  manifest says so, else `lazy`; a candidate is always `lazy`.
- A `DISABLED` function contributes nothing to `functions`.
- `unload`: every (address, version) some host of this pool seen within `FunctionHost.LIVE_WINDOW`
  (45 s = three missed 15 s beats) still reports, that is not in `functions`. Computing it from what
  hosts report rather than "all retired versions" keeps the document from growing for ever.
- Order: `functions` by (address, version), `unload` likewise — **the bytes are deterministic**.
- `ETag`: `"` + hex sha256 of the body bytes + `"`. `If-None-Match` equal ⇒ **304, no body**, `ETag`
  repeated. Weak validators and lists (`W/"…"`, `a, b`) are compared by stripping `W/` and splitting
  on commas; `*` matches. There is no precedent for conditional GET in this codebase — this is it.

New repository reads this needs (A's repositories, same one-hydration-path rules):
`FunctionVersionRepository.newestPublishedByFunctions(Collection<String>)` → map.

### 6.2 `POST /control/functions/heartbeat` (`Group.API_WRITE`)

Body `{hostId, pool, state: "ACTIVE"|"DRAINING", loaded: [{address, version, state:
"REGISTERED"|"LOADED"|"FAILED", error?}]}`. `hostId` 1–100 chars of `[A-Za-z0-9._:-]`
(`HOST_ID_INVALID`); `pool` a label; an unreadable `loaded` entry is **400** here (`LOADED_INVALID`
naming the index) — the stored reader is lenient, the wire is not. `error` is truncated to 1000
chars. 204.

1. Upsert the host (`register` on first sight, else `heartbeat`) via `UnitOfWork.inTransaction` — no
   event, no audit (§0).
2. For each `ok()` entry: resolve address → function → version. If it is `Published`, run
   `MarkVersionReady` (an `Operation`, audit principal = the host's service account): `markReady(now)`,
   event `version:ready` with `hostId`. Already `Ready` or `Retired` ⇒ nothing, no event. An entry
   naming an unknown function or version is ignored (the function may have just been deleted).
3. A **`FAILED`** report never changes a version's state; it is visible in Status.

### 6.3 Status and pools

- `GET /api/functions/{address}/status` →
  `{address, status, live?: {version}, versions: [{version, state}], hosts: [{hostId, pool, state, lastHeartbeat, stale: bool, loaded: [{version, state, error?}]}]}`
  — hosts that report this address, their entries for it only; `stale` = outside `LIVE_WINDOW`.
- `GET /api/function-pools` → `[{pool, hosts}]` counting hosts within `LIVE_WINDOW`. `FUNCTION_VIEW` + anchor.

## 7. Slices (one coder at a time in the main tree; C runs beside them in a worktree)

| Slice | Contents | Needs |
|---|---|---|
| B1 | §2 permissions/roles, §3 events, §4 functions + policies, `DeleteApplication` guard, lockfile prefix, `surface.json`, `Platform` wiring, `isPlatformPath` | A |
| B2 | §6 control plane, status, pools, `MarkVersionReady` | B1 |
| B3 | §5 publish, promote, retire, `Signatures`, `FC_FN_SIGNATURES` | B1, **C merged** |
| B4 | parity scenarios for every route + `expected-diffs.json` entries ("Java-first, no Go route") | B1–B3 |

## 8. Load-bearing behaviours — named tests, mutation-checked (CLAUDE.md)

| # | Behaviour | Mutant |
|---|---|---|
| P1 | R1: an application coded `logistics_portal` cannot own a function; the message says why | skip the `DnsLabel` check on the stored code |
| P2 | a function out of reach is 404 on read and on every write, and absent from the list — for a client-scoped principal against another client's function, a non-anchor against a platform function, and an application-scoped service account against another application's | drop each of the three reach clauses in turn |
| P3 | after **every** mutating route (update, publish, promote, retire, heartbeat) the function's address, application, owner and runtime are unchanged; `PUT` with `name` is 400 | accept and ignore the field |
| P4 | `DeleteApplication` refuses while functions exist, and succeeds after they are deleted | skip the guard |
| P5 | create/update/delete/publish/promote/retire/policy each write exactly one `msg_events` row of the named type with the function's message group, and one `aud_logs` row whose `operation` is the command's simple name | emit with a different message group |
| P6 | the heartbeat writes **no** event and **no** audit row when nothing becomes ready; exactly one `version:ready` the first time, none the second | route the host upsert through an `Operation`; drop the `Published` guard |
| P7 | publish is atomic: a throwing trigger seam leaves no version row and no event | call the seam after commit |
| P8 | `SIGNATURE_REQUIRED` / `SIGNATURE_REJECTED` / `SIGNER_NOT_PERMITTED` (incl. "no policy row") / accepted — with a `TestSigstore` bundle from C; and with `Signatures.Off` a bundle-less publish succeeds with a null signer | skip `permits`; treat a missing policy as permit-all |
| P9 | `FC_FN_SIGNATURES=off` without dev mode fails startup | accept it |
| P10 | over-ceiling manifest rejected using **the owner's** ceilings: raise one client's ceiling, the same manifest publishes for it and still fails for another client | always use platform defaults |
| P11 | two concurrent publishes of different digests get versions n and n+1, both 201 | (A's M10 covers the lock; this pins the operation uses it inside one transaction) read `nextVersion` outside the tx |
| P12 | promote requires `READY`; retire refuses the live version; promote → promote older = rollback works | drop each guard |
| P13 | desired state: live + newer candidate; a candidate older than live is absent; pool filter is per version; disabled function absent; `unload` lists a retired version a live host still reports and drops it once no live host does | each clause |
| P14 | desired state bytes are identical across two calls with rows inserted in different orders; `If-None-Match` ⇒ 304 with no body; after a promote the ETag differs and the body returns | sort by insertion; ignore `If-None-Match` |
| P15 | `/control/functions/*`: no credential ⇒ 401; a principal without the role ⇒ 403; `platform:function-host` ⇒ 200; that role can do **nothing** under `/api/functions` | put the route on the public-path list; grant the role `FUNCTION_VIEW` |
| P16 | `{address}` with dots routes; two-part address is 400 not 404 | — (pin only) |
| P17 | policy `GET` with no row returns the platform defaults with `stored: false`; `PUT` then `GET` round-trips; the platform policy is reachable as `platform` and its audit row has a non-null entity id | return 404 for no row |

## 9. Open for the owner

- **Q6 — who may set `clientId` absent?** As specified, only an anchor can create a platform-owned
  function. Fine for now; say if application service accounts should be able to.
- **Q7 — `FC_FN_SIGNATURES=off` only in dev mode.** fcdev sets dev mode, so `fn watch` works. If
  there is a non-dev environment that must run unsigned (a staging without CI identity), it needs a
  different switch, deliberately named.
