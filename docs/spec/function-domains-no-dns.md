# Spec — domain claims need no DNS verification (owner clarification 2026-09-23)

"Tenants can't deploy their own functions. We deploy our own functions that run for our tenant
solutions." TXT verification defended one tenant from another claiming its domain; with one operator
claiming every domain there is nobody to defend against. A claim is verified by being made. Amends
`function-public-routes.md` §1, `function-zones-and-aliases.md` §1, `function-registry.md` §6.5,
`function-api.md` §3 (events).

## 1. What goes

- `POST /api/function-domains/{hostname}/verify`, `VerifyFunctionDomain`/`VerifyCommand`,
  `TxtResolver`, `JndiTxtResolver`, `DnsException`, `DnsUnavailableException`, the `DNS_UNAVAILABLE`
  and `DOMAIN_NOT_VERIFIED` codes, the event `platform:function:domain:verified` (remove from the
  catalogue/seed wherever `domain:claimed` is registered), the resolver wiring in `Platform.java`,
  and `jdk.naming.dns` from the jlink module list if it was added for this (check `git log -S`).
- `FunctionDomain.verification` (the sealed `Pending`/`Verified`), `verificationToken`; columns
  `fn_domains.verification_token` and `verified_at` dropped by migration `V16__fn_domains_no_verification.sql`
  (Java-only table — no Go fingerprint change; follow what J3's V15 commit `f7c453a8` touched:
  `migration.index`, `MigratorTest`, `GoAdoptionTest`, jOOQ regeneration).
- `DomainResponse.verification` and `RecordView`; the dev-mode `.localhost` special case in
  `ClaimFunctionDomain` (every claim is the same now — delete the label check and its tests).
- `FunctionTriggerSync.requireVerifiedOwnedDomain` loses its "verified" clause: two `if`s remain
  (covering claim exists; owner matches) and the code becomes **`PUBLIC_HOSTNAME_NOT_CLAIMED`**
  ("hostname '<h>' is not under a domain claimed by this function's owner"). Update every test and
  document naming the old code.
- fcdev: `fn domain verify` subcommand; the TXT record lines in `fn domain claim`'s output and
  `DomainCommandTest`.
- SPA: the "Create this DNS record" panel and Verify button in `FunctionDomainDetailDrawer.vue`;
  the verification column/tag in `FunctionDomainListPage.vue`; the claim drawer's post-claim
  record display in `FunctionDomainClaimDrawer.vue`; `frontend/tests` accordingly.
- e2e `functions.spec.ts`: the "auto-verified (dev mode)" assertion (the route row itself stays).
- OpenAPI document: the verify operation, `verification` on `DomainResponse`, the two codes;
  `FunctionOpenApiCoverageTest` route count; the conformance flow's verify call.
- `parity/surface.json`: the verify route.

## 2. What stays

Claims are still zones, still never nest, still owned (reach), still released only when unrouted.
`FunctionDomain` keeps `id, hostname, owner, createdAt`. The event `domain:claimed`/`released`.

## 3. Docs

`docs/functions.md` (domains: "claim it; that's all — then point the CNAME at the load balancer";
the trust-model paragraph: functions are the operator's trusted code; tenants never deploy code; a
client-owned function runs for that tenant's solutions), `docs/function-service-overview.md`,
`docs/deployments.md` (the CNAME/wildcard note stands), the four specs above.

## 4. Load-bearing behaviours

| # | Behaviour | Mutant |
|---|---|---|
| N1 | a fresh claim (any owner, any zone, not `.localhost`) can be routed immediately: publish with a hostname under it succeeds | require something else before routing |
| N2 | publish with a hostname under nobody's claim ⇒ `PUBLIC_HOSTNAME_NOT_CLAIMED`; under another owner's ⇒ the same code | drop either clause |
| N3 | `GET /api/function-domains/{hostname}` body has no `verification` key; the verify route is gone (404 with the router's not-found shape, not a handler) | — |
| N4 | migration no-op on the new schema / applies on the previous; jOOQ compiles | — |
