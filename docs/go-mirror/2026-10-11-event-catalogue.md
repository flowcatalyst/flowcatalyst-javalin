# Java mirror of Go F9: every emitted event type catalogued (2026-10-11)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` F9 and Go commits `8ddaed4` (seed) and
`5c9939d` (docs). Owner ruling 2026-10-11: catalogue every emitted platform event type.
Predecessor: `2026-10-11-platform-bug-fixes-2.md` (F3 found the gap: 80 known uncatalogued codes in
Java). Every commit is on `main`, not pushed.

## Commits

| Commit | Change |
|---|---|
| `0e9a34ba` | `EmittedCatalogueTest` scans every platform source declaring a `DomainEvent`, not only `*Events.java`. This found an 81st uncatalogued code, `platform:admin:audit-log:redacted` (`AuditLogsRedacted`, a standalone record). Mutant: entry removed from the known list, test fails naming `AuditLogsRedacted.java: TYPE`. |
| `938dcd8f` | The 81 codes are catalogued (`PlatformEventTypes`, `PlatformEventSchemas`; 75 → 156 entries). `KNOWN_UNCATALOGUED` is empty. New tests: `EmittedEventSchemaTest`, and `SeederTest.aDatabaseSeededBeforeTheCatalogueGrewGainsTheNewCodesOnTheNextRun`. The go-seed fixture has the 81 new rows. |
| (this file) | Ledger. |

## How the rows reach existing databases

Same mechanism as Go, so no migration. `Main` runs `Migrator.migrate` and then `new Seeder(...).run()`
on every boot that owns the platform schema. `Seeder.seedEventTypes` upserts by code. A missing
code is inserted, and its 1.0 schema is attached when the type has no `1.0` or legacy `v1` version.
An existing row only has its name refreshed. Nothing is renamed or dropped, so there is no
migration. The new `SeederTest` case pins this: a database seeded with the older catalogue gains the
codes and their schemas on the next run, and existing ids are kept. Mutant: event types seeded only
into an empty table; the test fails.

As in Go, a code that an operator already created by hand keeps its row and gets the seeded name. It
gets the seeded schema only if it has no 1.0 version yet.

## The 81 codes

**56 codes are Go's, with Go's names and schemas (equal JSON trees).** Java emits the same data for
them. The Java and Go catalogues were dumped and compared. The fixture rows for these codes were
dumped from Go's `seed.PlatformEventTypes()` at `8ddaed4`, so `SeederTest` checks them against Go:

- `platform:admin:anchor-domain:{created,updated,deleted}`, `platform:admin:auth-config:{created,updated,deleted}`
- `platform:admin:dispatch-pool:{suspended,activated}`: the data names `poolId`; see below.
- `platform:admin:email-domain-mapping:{created,updated,provider-changed,deleted}`
- `platform:admin:identity-provider:{created,updated,deleted}`, `platform:admin:idp-role-mapping:created`
- `platform:admin:oauth-client:{created,updated,activated,deactivated,deleted,secret-rotated}`
- `platform:admin:role:{created,updated,deleted,permission-granted,permission-revoked}`, `platform:admin:roles:synced`
- `platform:admin:platform-config:property-set`
- `platform:admin:process:{created,updated,archived,deleted}`, `platform:admin:processes:synced`
- `platform:admin:scheduled-job:{created,updated,paused,resumed,archived,deleted,fired-manually}`
- `platform:admin:scheduledjobs:synced`: Go's lists are `["array","null"]` (Go writes nil as
  null); Java writes `[]`, which the same schema admits.
- `platform:developer:application-openapi:synced`
- `platform:iam:serviceaccount:deactivated`, `platform:iam:user:developer-credential-{set,revoked}`
- `platform:messaging:dispatch-job:{cancelled,completed}`
- `platform:portal:app:{created,updated,deleted}`
- `platform:portal:identity:{ensured,status-set,deleted,app-granted,app-revoked}`

**2 codes Go also emits, but Java emits different data.** These use Go's names and Java's schemas.
The difference is not fixed on either side; the owner should rule which data is right:

| Code | Go data (Go schema) | Java data (Java schema) |
|---|---|---|
| `platform:admin:idp-role-mapping:deleted` | `mappingId`, `idpRoleName` | `mappingId`, `idpType`, `idpRoleName`, `platformRoleName` (the create's fields) |
| `platform:admin:oauth-client:previous-secret-revoked` | `oauthClientId` | `oauthClientId`, `dropped` (boolean) |

Go's schema has `additionalProperties: false`, so it would reject Java's data. That is why these two
rows differ.

**23 codes are Java only.** Their names follow the existing style, and their schemas are what Java
emits. Go emits none of these codes:

| Code(s) | Name(s) | Go counterpart (not equated; for the owner) |
|---|---|---|
| `platform:iam:passkey:{registered,authenticated,revoked}` | Passkey … | Go emits `platform:admin:passkey:*` with the same data |
| `platform:iam:reset-approval:{queued,decided}` | Reset Approval … | none |
| `platform:iam:serviceaccount:token-minted` | Serviceaccount Token Minted | none |
| `platform:admin:dispatchjob:requeued`, `platform:admin:dispatchjobs:requeued` | Dispatch Job Requeued, Dispatch Jobs Requeued | Go's resend emits `platform:messaging:dispatch-jobs:resent` (`{ids}`) |
| `platform:admin:audit-log:redacted` | Audit Log Redacted | none |
| `platform:function:function:{created,updated,deleted}`, `platform:function:version:{published,ready,retired}`, `platform:function:alias:{changed,removed}`, `platform:function:config:updated`, `platform:function:secret:{set,deleted}`, `platform:function:policy:updated`, `platform:function:domain:{claimed,released}` | Function …, Function Version …, Function Alias …, Function Config Updated, Function Secret …, Function Policy Updated, Function Domain … | Go's function runner emits a different set under `platform:admin:function*` (below); the two function platforms are separate designs |

**Not catalogued in Java: 21 Go codes that Java does not emit.**

- `platform:admin:function:{created,updated,deleted,promoted}`
- `platform:admin:function-version:{published,retired}`
- `platform:admin:function-alias:{set,deleted}`
- `platform:admin:function-setting:{set,deleted}`
- `platform:admin:function-route:{set,deleted}`
- `platform:admin:function-schedule:wired`
- `platform:admin:function-domain:{claimed,released}`
- `platform:admin:passkey:{registered,authenticated,revoked}`
- `platform:admin:platform-config:{access-granted,access-revoked}`
- `platform:messaging:dispatch-jobs:resent`

The Java catalogue therefore differs from Go's by those 21 codes and Java's 23. Both catalogues
still carry the 16 catalogued codes that nothing emits: `platform:admin:edm:*`,
`platform:admin:idp:*`, `platform:iam:anchor-domain:*`, `platform:iam:auth-config:*`,
`platform:iam:role:*`, `platform:iam:roles:synced` and
`platform:iam:user:password-reset-requested`. They were left as they are; the owner rules on them.

## Verification of the schemas against real output

- **Unit test**: `EmittedEventSchemaTest` builds each of the 81 event records through its canonical
  constructor. It serialises `data()` through `Json.MAPPER` (the mapper `PlatformSink` writes
  with) and validates the result against the catalogued schema. A small validator covers the
  subset of JSON Schema the DSL writes. Each event is checked twice: with every field set, and
  with the fields the operation can leave null unset. A per-code `MAY_BE_ABSENT` list records
  those fields, as read off the factories and aggregates. The test also asserts that each schema's
  optional properties are exactly that list.
  - Mutants killed: Go's `previous-secret-revoked` schema restored (`dropped` rejected);
    `token-minted`'s `servicePrincipalId` made required; a required field made optional.
  - A self-test proves the validator rejects an extra property, a missing required property, a
    wrong type, a negative count, a bad array item and a malformed date-time.
- **Real output, all 81 codes**: `PlatformSink.dataJson` was temporarily instrumented to append
  every persisted event's type and data to a file. It was then reverted and was never committed.
  The whole server suite was run with that instrumentation: 2,400 events of 147 types.
  - Every one of the 81 new codes was captured from real operation runs.
  - The catalogued schema accepted all of them. There were **0 mismatches** among the new codes.

## Found, not fixed: older catalogue schemas reject what Java emits

The same capture checked the 75 entries that were already in the catalogue. These schemas came from
Go's seed and were never audited, so this is not new drift. For 22 codes the catalogued schema
rejects the data Java really emits. Go was not checked for the same mismatches, and the owner rules
on whether to fix them. Per the brief, the dispatch-pool question is only noted here:

| Code | Real Java data vs schema |
|---|---|
| `platform:admin:dispatch-pool:{created,updated,archived,deleted}` | Java emits `poolId`; the schema requires `dispatchPoolId` (Go's open `dispatchPoolId` vs `poolId` question; Java emits `poolId` throughout, including suspended/activated) |
| `platform:admin:connection:created` | no `endpoint`, `serviceAccountId` |
| `platform:admin:connection:updated` | no `code` |
| `platform:admin:eventtype:schema-added` | `specVersion` instead of `version`; no `mimeType`, `schemaType` |
| `platform:admin:eventtype:schema-{finalised,deprecated}` | `specVersion` instead of `version` |
| `platform:admin:subscription:created` | no `connectionId`, `eventTypes` |
| `platform:admin:subscription:updated` | no `eventTypesAdded`, `eventTypesRemoved` |
| `platform:admin:subscription:{paused,resumed}` | no `code` |
| `platform:iam:application:created` | no `applicationType` |
| `platform:iam:application:{activated,deactivated}` | no `code` |
| `platform:iam:application:deleted` | no `name` |
| `platform:iam:serviceaccount:created` | no `clientIds` |
| `platform:iam:serviceaccount:updated` | no `clientIdsAdded`, `clientIdsRemoved` |
| `platform:iam:user:created` | no `emailDomain`, `name`, `scope`, `isAnchorUser` |
| `platform:iam:user:deleted` | an extra `email` |
| `platform:iam:user:password-reset-completed` | no `email` |

The capture did not exercise `platform:iam:user:logged-in` or the 16 never-emitted codes, so they
were not checked.

## Test results

- The seed package (`io.flowcatalyst.platform.seed.**`, 8 classes, 36 tests) is green.
- Full reactor `mvn clean test` at `938dcd8f`: **BUILD SUCCESS**. 0 failures, 0 errors:

  | Module | Tests | Skipped |
  |---|---|---|
  | server | 5,698 | 1 |
  | function-host | 485 | 2 |
  | fcdev | 274 | 0 |
  | parity | 56 | 1 (`ParityRunTest` skips without a Go source) |
  | sdk | 93 | 1 |
  | usecase | 46 | 0 |
  | function-api | 145 | 0 |
  | eventloop-check | 16 | 0 |

- The Go/Java **parity harness was not run** this pass. The event-type list it compares (S0 smoke
  and bff, `/items/…`) will now differ by the 23 Java-only and 21 Go-only codes above. Expect new
  DIFFs there, and accept them in `expected-diffs.json` or rule on the codes.
