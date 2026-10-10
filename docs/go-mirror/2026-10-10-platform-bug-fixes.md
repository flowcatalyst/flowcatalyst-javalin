# Java mirror of the Go platform bug-fix batch (2026-10-10)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` (bugs 1–25, the dispatch
pool/connection items, the "found while fixing" items) and the two Go commits
before it, `fcce86c` (platformconfig confinement) and `c59e246` (dispatch-pool
per-operation permissions). The owner lifted the Java pause for this list only;
nothing else unmirrored in Go was ported (requeue-by-filter, router/perf, `/ui`,
compression, schemacodegen).

Procedure per entry: read the Go diff, check whether Java has the same defect,
fix it to behave as Go on the wire with a test that fails on the old code, and
mutation-check the load-bearing assertion (break the fix, watch the test fail).
Every commit below is on `main`, not pushed.

## Ledger

| Go | Bug / item | Java had it? | Java commit | Tests (fail before the fix) | Deviations / notes |
|---|---|---|---|---|---|
| `c59e246`, `3d536a7`, `6be78ee`, `210e4b5`, `1fa33ef`, `1f83887`, `e03e54a`, `b49b73a`, `4c87621` | Per-operation permission gates (bugs 8, 13, 17; event types, processes, roles, service accounts, principals, dispatch pools) | **Yes** — every one used `requireAny(create, update, delete)` | `eab1be7e` | `WriteGates` helper + `eachWriteNeedsTheOperationsPermission` in 9 `*ApiTest`s, `ResetApprovalApiTest.listApproveAndDenyNeedUserUpdate`, `EventTypeApiTest.addSchemaStillAdmitsTheApplicationServiceGrant`, `ChecksTest.userAdminChecksTheOperationsPermission` | Role changes on users keep `USER_ASSIGN_ROLES` (Java owner ruling 2026-09-25; Go uses `user:update`). Service-account token/secret rotations keep any-of (Go: anchor only — finding below). |
| `54dcaa0` | Comments name the per-operation gates | One stale comment | `8d48078a` | — | |
| `308589b` | 1, 2: `/bff/event-types` reads unauthorised; repeated facets read the first value | **Yes** (reads required `event-type:view` only — an app service account was refused; filter routes checked nothing; facets first-value; cascading options `[]` without an application) | `c42b1913` | `EventTypeBffReadsTest` (3 tests); mutants: gate dropped, confinement dropped, first-value facets | `Exchange.queryParams(name)` added (Vert.x adapter) for repeated parameters. The read rules move to `eventtype/api/EventTypeReads`, shared with `/api/event-types`. |
| `a3414cc` | Sync Platform Events needs `event-type:sync` or `:manage` | Partly — Java required `EVENT_TYPE_SYNC` since S1.2, refused `:manage` | `22d57563` | `EventTypesBffTest.syncPlatformAlsoAdmitsTheManagePermission` | |
| `5b75de5` | `/bff/roles` per-operation gates | Partly — reads were gated; writes were any-of (sync, create, update) and anchor was checked first | `22d57563` | `RolesBffTest` (each write; sync needs all three; permission before anchor) | Same commit brings the Java `/bff/event-types` writes to Go's per-operation gates (Go's BFF already had them; Java's were any-of). |
| `7b5453a` | 3: add schema double-encoded; MIME/schema type dropped | **Yes** | `55e9a017` | `EventTypeOperationsTest.addSchemaStoresTheDocumentWithMimeAndSchemaType`, `…RefusesSchemasThatDoNotMatchTheirType`, `EventTypesBffTest.addSchemaStoresTheSPAsSchemaAsTheDocument`; mutants: no unwrap, BFF drops fields | Rules on `SchemaType` (`parseWire`, `defaultMimeType`, `normaliseContent`). |
| `17b79ef` | 4: stored XSS in `highlightJson` | **Yes** (same SPA source) | `b5a3371c` | `frontend/tests/schema-highlight.test.ts` (2 of 4 fail before) | Go's diff verbatim. |
| `62b72e3` | 5: archive/delete rules only in the SPA | **Yes** | `aeb5e1d7` | `EventTypeTest.archiveAndDeleteFollowTheSchemaVersions`, `EventTypeOperationsTest.archiveAndDeleteEnforceTheVersionRules`; mutants: each guard | Rules on `EventType` (`canArchive`, `canDelete`, `requireDeletable`). |
| `b7aaaa4` | 6: sync counts unchanged types as updated | **Yes** | `50bfa095` | `EventTypeOperationsTest.syncLeavesUnchangedTypesAlone`; mutant killed | Java's sync applies no schema at all (pre-existing divergence: Go's applies the 1.0 schema), so only name and description are compared. |
| `b7b9a06` | 7: SPA pencil edits an archived event type | **Yes** (SPA) | `17e677ca` | `frontend/tests/event-type-rules.test.ts` | Verbatim. |
| `eb537a7` | 9: subscription create drops `clientScoped`, `sequence` | **Yes** | `fa2ff469` | `SubscriptionApiTest.createStoresClientScopedAndSequence` | Lockfile: the two optional fields (Go's text); `types.gen.ts` hunk. SDK spec copies not refreshed (as Go). |
| `8b98b1f` | 10: SPA Queue free text | **Yes** (SPA) | `83d0b616` | `frontend/tests/subscription-queue.test.ts` | Verbatim. |
| `e6d1dc0` | 11: `dispatchPoolCode` stored only by sync | **Yes** | `b8cc0f18` | `SubscriptionOperationsTest.createAndUpdateStoreTheDispatchPoolCode`; mutant killed | `withDispatchPoolId` (id only) removed. |
| `c3768c7` | 12: blank `connectionId` on update stored `""` | **Yes** | `b8cc0f18` | `SubscriptionOperationsTest.aBlankConnectionIdOnUpdateLeavesTheConnectionAlone`; mutant killed | |
| `61dbcb9` | 14: deleting an active application allowed | **Yes** | `ba538c56` | `ApplicationOperationsTest.deleteIsRefusedWhileActive`, `ApplicationApiTest` 409 envelope | Java has no `APPLICATION_HAS_REFERENCES` check (finding below); only its functions guard follows. |
| `ff65ec4` | 15: client deactivate deleted the client | **Yes** | `e00add96` | `ClientApiTest.deactivateSetsInactiveAndKeepsTheRow`, `ClientOperationsTest.deactivateSetsInactiveThroughTheEnvelope` | New `DeactivateClient` / `DeactivateCommand`, event `platform:admin:client:deactivated`. |
| `db4e776` | 16: client list paging ignored | **Yes** (SPA; Java's `GET /api/clients` is unpaged like Go's) | `5422ae20` | `frontend/tests/clients-list.test.ts` | Verbatim. |
| `563102a` | 18: filter-options not tenant-scoped | **Yes** (both endpoints) | `fb35d351` | `DispatchJobApiTest`/`EventApiTest.filterOptionsAreScopedToTheCallersTenants`; mutant killed | `distinctValues` takes the caller's `Visibility`. |
| `069a65d` | 19: dispatch-job facet options empty | **Yes** (SPA) | `735f0a35` | `frontend/tests/code-facets.test.ts` | `code-facets.ts` verbatim; the list-page hunk applied by hand (Java's page has no requeue-by-filter dialog). |
| `6116eee` | 20: Sign shown to every viewer | **Yes** (SPA) | `8e8bc6fd`, `57c31fa4` | `frontend/tests/dispatch-job-sign.test.ts`; Java-only `dispatch-job-detail-drawer.test.ts` now signs in a view-raw holder and pins "no Sign without it" | |
| `858f02c` | 21: `size` above 1,000 fell back to 100 | **Yes** (both repositories' guard) | `fb35d351` | `DispatchJobRepositoryTest`/`EventRepositoryTest.aSizeAboveTheCapIsClamped` (150 rows); `DebugBffTest` size=5000; mutant killed | `DebugBff`'s doc had flagged the Go behaviour as a defect; now fixed on both sides. |
| `b3c28a4` | 22: event detail has no context data | **Yes** | `d5af9a1e` | `EventApiTest.getByIdCarriesTheContextDataFromTheWriteTable`; full-envelope test now expects `contextData`; mutant killed | |
| `ccf2dd5` | 23, 25: process edit/delete rules SPA-only; blank description `""` | **Yes** | `07794a68` | `ProcessOperationsTest.editAndDeleteFollowTheStatusAndABlankDescriptionIsNone` (3 mutants killed), `ProcessApiTest` 409 | |
| `10a5ac1` | 24: SPA pencil edits an archived process | **Yes** (SPA) | `bf59c185` | `frontend/tests/process-rules.test.ts` | Verbatim. |
| `60dc063` | Create stores the client identifier (pools, connections, subscriptions) | **Yes** | `3ddfffc5` | `createStoresTheClientIdentifier` ×3; mutant killed | `shared/database/ClientIdentifiers`. No backfill (Go decision 10); sync paths do not set it (as Go). |
| `d1158fa` | Pool edit can clear rate limit / description | **Yes** (API + SPA) | `3ddfffc5` | `DispatchPoolOperationsTest.updateClearsTheRateLimitAndDescription`; `frontend/tests/dispatch-pool-edit.test.ts`; mutant killed | Lockfile field docs + `types.gen.ts`, Go's text. |
| `108894d` | A client-scoped user can pick its client | **Yes** (SPA; Java's `/bff/filter-options/clients` already matches Go's) | `714af871` | `frontend/tests/client-options.test.ts` | Java-only `FunctionCreateDrawer`/`FunctionDomainClaimDrawer` also use `ClientSelect` (anchors: same active clients as before). |
| `fcce86c` | platformconfig: confine application-scoped anchors | **No** | — | — | Java gates config by `platform:admin:config:view/manage` from roles (commit `5624058e`, owner ruling 2026-09-23); anchor tier grants nothing and the `application-service` role holds neither permission, so an application's service account cannot read or write another application's config. Residual difference: an application-scoped principal explicitly granted `config:view` reads every application's config in Java (the 2026-09-23 ruling: "no per-application restriction"), where Go now confines it — owner decision. |
| `8b8322e` | `/ui` dialog slots | **N/A** (Go-only `/ui`) | — | — | |
| — | Embedded SPA | — | `b19dd037` | — | `make frontend` at frontend source `735f0a35`. |

`/ui`-only halves of the Go commits (templ pages, `/ui` facet cache, `/ui` forms) have no
Java counterpart and were not ported.

## Owner decisions (carried from Go, plus Java-specific)

1. Go decisions 1–10 (`platform-bug-fixes.md`, "Decisions taken conservatively") apply here
   unchanged: lifecycle writes need **update**; `clientScoped`/`sequence` on create only;
   Queue fixed in the SPA; blank `connectionId` = not provided; unknown pool id stores no code;
   client list paged in the browser; facet options derived in the SPA; add-schema normalisation
   with no new `/api` body fields; Sync Platform Events = anchor + `event-type:sync`/`:manage`;
   no client-identifier backfill.
2. **Role assignment on users**: Java keeps `USER_ASSIGN_ROLES` (owner ruling 2026-09-25,
   backlog item 14); Go's batch put user role changes under `user:update`. One of the two should
   move.
3. **platformconfig** (above): Java's role-gated design has no per-application confinement for a
   principal holding `config:view`; Go now confines application-scoped anchors.
4. **Service-account rotations** (`regenerate-token`, `regenerate-secret`): Java is any of
   create/update/delete (its spec deliberately diverged from Go's anchor-only); under the
   per-operation ruling they are updates — left unchanged, for the owner.
5. **Service-account create/update/delete anchor reach**: Go requires anchor scope on them (owner
   decision #19 there); Java checks the permission only. Not part of this batch; noted.

## New findings (Java), not fixed

- `DeleteApplication` has no reference check (`APPLICATION_HAS_REFERENCES` in Go): only
  functions block a delete; access grants, client configs, service accounts, roles are orphaned.
- `SyncEventTypes` ignores the inputs' schemas (Go applies the 1.0 schema and tallies
  created/updated/unchanged); the BFF sync answer's `schemas` tally is always zeros.
- Go's "new findings" list applies here too, as far as checked: scheduled-job runner reports
  (`/log`, `/complete`) still need the messaging any-of, not `application-service:scheduled-job-instance:write`;
  event types have no client column; the client event names (`platform:admin:client:*`) are not
  catalogued; `DELETE /api/event-types/{id}` (the TS SDK's `archive()`) hard-deletes.

## Verification

- Full reactor, `mvn clean test` at `b19dd037`: BUILD SUCCESS — server 5,681 tests (1 skipped),
  function-host 485 (2 skipped), fcdev 274, parity 56 (1 skipped; `ParityRunTest` skips itself
  without a Go source), sdk 93, usecase 46, function-api 145, eventloop-check 16; 0 failures.
- Frontend: vitest 28 files / 97 tests passing; `vue-tsc -b` clean; oxlint 0 errors (1 pre-existing
  warning in `useReturnTo.ts`, untouched).
- Parity harness against a Go build of the Go working tree (`PARITY_GO_SRC`, embedded Postgres,
  free ports), run twice — on the pre-batch Java (`01d220c7`, temporary worktree) and on
  `b19dd037`: **36 DIFF/ERROR steps before, 16 after, all 16 already present before** (the batch
  closed 20 and introduced none). The 16 left are outside this list: Go commits after the brief
  (`0a8df19` event-type `clientId` ignored on create — its scenario steps now ERROR on the Go
  side; `25c3ed6` client-event catalogue), Java's sync applying no schemas, docs pages, OAuth/
  principal list contents, the SA mint token's claims, `clientScoped` on the event-type list,
  one endpoint-URL message wording. `ParityRunTest` fails on both runs for the Go-side ERROR.
- Mutation checks: every server-side fix above had its load-bearing test re-run against a mutant
  of the fix (named in the commit messages); each mutant was killed.

## Go work after this brief (not mirrored)

The Go repo moved on while this batch was mirrored: `5d63669`, `0a8df19`, `25c3ed6`, `0ae0988`,
`3f0dba4`, `fb36438` and an uncommitted "second batch" section in Go's
`docs/platform-bug-fixes.md` (F1… — the "new findings" being fixed). They were outside this
brief (3d536a7..0eb2b5f + fcce86c + c59e246) and are **not** mirrored; they need their own pass.

## Not verified

- No browser run of the SPA.
- SDK spec copies (`clients/*/openapi`, `sdk/openapi`) were not refreshed, as in Go.
- Routing of a newly created client-scoped pool end to end through a running router.
