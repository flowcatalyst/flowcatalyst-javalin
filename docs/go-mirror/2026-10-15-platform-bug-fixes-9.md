# Java mirror of Go F17: code-first connections stored the application's principal id (2026-10-15)

Source: `../flowcatalyst-go/docs/platform-bug-fixes.md` F17; Go commit `c74650d`, migration
`075_connection_service_account_principal_ids.sql`. Predecessor:
`2026-10-14-platform-bug-fixes-8.md`. Commit is on `main`, not pushed.

## Was the defect present in Java?

The stored id: **yes**. `SyncConnections` copied `app_applications.service_account_id` (the
service account's PRINCIPAL id, `prn_...`) into `msg_connections.service_account_id`, which must be
the service account's own id (`sac_...`). Every code-first connection therefore reported (and
stored) a principal id, and `GET /api/connections` showed it.

The symptom Go saw (unsigned delivery): **no, masked in Java**. Java's
`ServiceAccountRepository.findByIdOrServicePrincipalId` is the one resolution the delivery signer
and the signing-reach check share; it accepts either kind of id, so a `prn_` value still found its
account and signed. That tolerance is Java-only and was kept (it also covers rows not yet healed).
The old sync tests seeded a principal whose service-account id equalled its own id, which hid the
difference; the fixture now uses two rows with two ids.

## Changes

- `SyncConnections` resolves the application's principal to the account behind it
  (`ConnectionRepository.serviceAccountForPrincipal`) and stores that; a principal with no account
  fails the whole sync with 400 `APPLICATION_SERVICE_ACCOUNT_REQUIRED`, nothing persisted. Every
  sync re-stamps existing API/CODE rows, so stale rows heal on the next sync.
- `PUT /api/connections/{id}` accepts optional `serviceAccountId` (set-if-provided, trimmed).
  Blank: 400 `SERVICE_ACCOUNT_REQUIRED`. Not an existing service account's own id (a principal id
  is not one): 400 `SERVICE_ACCOUNT_NOT_FOUND`. Java adds, as create already does, the signing
  reach check: 403 `SERVICE_ACCOUNT_OUT_OF_REACH` (Go has no reach check on this path; Go's create
  does not validate at all, Java's validates existence and reach).
  `UpdateConnection.of` takes a `SigningReach`. `openapi.lock.json` gains the field (hand-patched,
  `openapi.lock.source-commit` not moved).
- `V29__connection_service_account_principal_ids.sql`: Go 075's statement, idempotent, so running
  both migrators on one database is harmless. Data fix, not reversible.

## Tests (mutation-checked)

`SyncConnectionsTest` (sac_ id asserted, principal-without-account refused; red before the fix),
`ConnectionOperationsTest` (update sets / omits / blank / unknown / principal id / out of reach),
`ConnectionServiceAccountMigrationTest` (re-points, leaves correct, bare and unrelated rows; run
twice), `MigratorTest` and `GoAdoptionTest` (migration counts). Mutants killed: skip the update
assignment, skip the existence check, skip the reach check, skip the blank check, migration as a
no-op, migration without the null guard.

## Not verified

No run against a deployed or dev database; no live outbound delivery; the SDK generated clients
(`clients/*/generated`) were not regenerated for the new PUT field.
