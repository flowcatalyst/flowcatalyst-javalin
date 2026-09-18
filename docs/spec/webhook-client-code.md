# The delivered webhook names its tenant (owner ruling 2026-09-18)

## Problem
A subscriber's endpoint is multi-tenant: it must know which client a delivery
is for. Today the envelope carries only `clientId` — the opaque TSID
(`DeliveryPayload.java:33`, Go `processing.go:450`) — and a `dataOnly`
subscription carries nothing at all, because it sends the raw payload with no
envelope. The human client code (`tnt_clients.identifier`) never reaches the
subscriber, and no header names the tenant.

## Ruling
Both repos, identical behaviour.

### R1 — `clientCode` in the envelope
The non-`dataOnly` envelope gains `clientCode` beside the existing
`clientId`: the client's `identifier` slug (e.g. `acme`). Omitted — the key
absent, not null — when the job has no `clientId`, or when the client cannot
be resolved. `clientId` keeps its current meaning and position; nothing else
in the envelope changes.

### R2 — `X-FlowCatalyst-Client` header
Every delivery whose client resolves carries
`X-FlowCatalyst-Client: {clientId}:{clientCode}` — the same `"{id}:{code}"`
pair shape the platform's `clients`/`applications` claims already use.

- Sent for **`dataOnly` deliveries too** — that is the case the envelope
  cannot cover, and the reason this is a header.
- **Never a half pair**: no `clientId` (a platform-scoped job), or a client
  that does not resolve, means the header is **omitted entirely**.
- The signature (`X-FlowCatalyst-Signature`) covers the **body only**, as
  today — adding a header must not change how a subscriber verifies.

### R3 — resolution is cached in memory
The delivery path must not pay a database round trip per job. Resolve
`clientId → identifier` through an in-memory cache:

- A client's `identifier` is immutable after create, so a **hit is cached for
  the process's life**; there is no staleness to invalidate.
- A **miss is not cached indefinitely** — a client created after the first
  miss must resolve on a later delivery. Either do not cache negatives, or
  cache them for a short bounded window; say which you chose.
- Reuse an existing cached `tnt_clients` lookup if the delivery side already
  has one (the scheduler has `PoolCodeResolver#clientIdentifier` in Java, but
  it lives on the scheduler, not the processing endpoint — check before
  duplicating). A repository failure resolves to "unknown": the delivery goes
  ahead without the code and header, logged at most once per client, never
  failing the delivery.

## Tests — each must fail under its mutant
| # | Assert | Mutant |
|---|---|---|
| T1 | a client-scoped job's envelope carries `clientCode` = the client's identifier, alongside `clientId` | drop the field |
| T2 | a platform-scoped job (no `clientId`): envelope has neither key, and **no** `X-FlowCatalyst-Client` header | emit the header with an empty half |
| T3 | the header is exactly `{clientId}:{clientCode}` | send the code alone |
| T4 | a **`dataOnly`** delivery: body is the raw payload byte-for-byte (unchanged), and the header is present | skip the header in dataOnly mode |
| T5 | an unresolvable client: delivery still happens, no header, no `clientCode` | fail or block the delivery |
| T6 | two deliveries for the same client perform **one** lookup (count on a fake/counting repository); a client that missed once resolves on a later delivery | cache negatives for ever |
| T7 | `X-FlowCatalyst-Signature` still verifies over the body alone with the header present | fold the header into the signed material |

## Docs
Update the dispatch-seam delivery-request section in each repo (Java
`docs/spec/` — find the file that documents "Delivery request construction";
Go `docs/`) to show the new field and header, and to state the signature is
unchanged.
