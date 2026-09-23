# Spec — pools are the trust boundary: a client's functions run only in pools granted to it

Owner ruling 2026-09-23 ("go ahead with the pool ownership rule"), from the assessment in the
2026-09-23 conversation: a function runs inside the host JVM with no sandbox, so the **pool**
(a separate process / ECS service) is the only real isolation boundary. Today a manifest chooses
its `pool` freely. Before any client-owned function reaches production, a client's functions must
be confined to pools the platform provisioned for that client. Amends `function-registry.md`
§4.3/§6.4, `function-api.md` §4.3, `function-ui.md` (policies).

## 1. The rule

- `fn_client_policies` gains `pools TEXT[] NOT NULL DEFAULT '{}'` (migration V16, Java-only table —
  no Go fingerprint change). `ClientPolicy` gains `pools: List<DnsLabel>`; `PolicyResponse` and
  `PutPolicyRequest` gain `pools: ["acme", "acme-batch"]` (each a `DnsLabel`, no duplicates ⇒
  `POLICY_INVALID` naming the offender, as the signers/ceilings validation does).
- **Platform-owned functions** (owner = platform): any pool, as today.
- **Client-owned functions**: at publish, the manifest's `pool` must be in the owner's policy
  `pools`, else `400 POOL_NOT_ALLOWED`: `pool 'default' is not granted to client <code>; granted:
  acme, acme-batch` — or `granted: none — grant a pool in the client's function policy` when the
  list is empty or the policy row is absent. **Absent policy ⇒ no pools**: the safe default is
  refusal; granting is an anchor's deliberate act.
- The check lives where the other per-owner constraints live: `ClientCeilings` gains
  `allowedPools: Optional<Set<DnsLabel>>` (empty optional = unrestricted, the platform's shape;
  `ClientCeilings.of(defaults)` stays unrestricted only for the platform owner — `PublishVersion`
  step 3 builds the client shape from the policy row, or "no pools" when the row is absent), and
  `Manifest.parseStrict` raises `POOL_NOT_ALLOWED` right after `POOL_INVALID`.
- **Publish-time only.** Narrowing a client's pools later does not retire what is already
  published or live (the platform never silently un-deploys); it stops the next publish. The
  policy PUT answers with a `warnings: ["3 published versions of 2 functions use pools no longer
  granted: …"]` list when narrowing, computed from `fn_versions` × manifests of that owner's
  functions (non-retired versions only), so the anchor sees what to migrate. `docs/functions.md`
  says so.

## 2. Surfaces

- API: the two policy routes + the list carry `pools`; OpenAPI document updated; conformance test
  PUTs a policy with a pool and reads it back.
- SPA: `FunctionPolicyDetailDrawer.vue` gains a "Granted pools" chips/list input (add/remove, DNS
  label validation client-side); list page column "Pools". Warnings from the PUT shown as a toast.
- fcdev: nothing (the developer sees `POOL_NOT_ALLOWED` from `fn publish`; the message names the
  remedy).
- `docs/functions.md` §policies and `docs/function-service-overview.md` (trust model paragraph:
  functions are trusted code; the pool is the isolation boundary; a client's code runs only in
  pools granted to it).

## 3. Load-bearing behaviours (one mutant per condition; absence as well as presence)

| # | Behaviour | Mutant |
|---|---|---|
| O1 | client-owned function, policy grants `acme`: manifest `pool: acme` publishes; `pool: default` ⇒ `POOL_NOT_ALLOWED` naming `default`, the client and `acme` | skip the check |
| O2 | client-owned, **no policy row** ⇒ refused with "granted: none — grant a pool…"; empty `pools` on a row ⇒ same | default absent policy to unrestricted |
| O3 | platform-owned function ⇒ any pool, with and without a platform policy row | apply the client rule to the platform |
| O4 | PUT policy with an invalid label / duplicate ⇒ `POLICY_INVALID` naming it; round-trips `pools` | — |
| O5 | narrowing the grant answers `warnings` naming the count of affected versions/functions; widening ⇒ no warnings; the already-live version keeps serving (desired state unchanged) | drop the warning; retire on narrowing |
| O6 | migration V16 no-op on the current schema / applies on the previous; fingerprint clean | — |
| O7 | SPA: the drawer round-trips pools; the list shows them (vitest) | — |
