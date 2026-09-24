# Audit logs never store passwords or secrets

Owner, 2026-09-24: redact at the source — in the SDK, before anything reaches the outbox or
`aud_logs` — with the platform's ingest as a backstop.

## Findings (verified 2026-09-24)

An audit row stores the whole command as `operation_json` (`PlatformSink.writeAudit`,
`OutboxSink.auditPayload`, and SDK apps' audit rows through `IngestApi` →
`AuditLogRepository`). Redaction today is per field: `@JsonIgnore` on the principal password,
reset password and synced password hash; function `SecretValue` serialises as `***`; the OIDC
client-secret refs are encrypted before the command exists. **Two leaks:**
`serviceaccount` Create/Update commands carry `WebhookCredentials` in plaintext (`token`,
`password`, `signingSecret`); `SetPropertyCommand.value` is plaintext for a `SECRET` config value.
Go's commands serialise the same fields, so production rows written by Go carry them too.

## The rule (one definition, three languages)

Applied to the command document before it is written anywhere, walking objects and arrays:

- A key is **secret** when, lower-cased with `_` and `-` removed, it **ends with** `password`,
  `passwordhash`, `secret`, `secretref`, `passphrase` or `token`, or **equals** `apikey`,
  `privatekey`, `authorization` or `cookie`.
- A secret key's value becomes the string `"***"` — whatever its type — except `null` (kept) and
  booleans (kept).
- A command may also declare **masked fields**: top-level field names masked the same way even
  though the name rule would keep them. `SetPropertyCommand` masks `value` unless `valueType` is
  exactly `PLAIN` (a `null` type keeps the current type, which may be `SECRET`).
- Everything else is untouched.

`docs/spec/audit-redaction-vectors.json` is the contract: each case gives `input`, the command's
declared `masked` fields and the `expected` output. Every implementation's tests run every case.
Each SDK carries a byte-identical copy (the SDKs are split into their own repos), and a test in
this repo fails if a copy differs from the canonical file.

## Java — `usecase` module (used by the platform and by Java SDK apps)

- `io.flowcatalyst.sdk.usecase.AuditRedaction` — `JsonNode redact(JsonNode command, Set<String>
  masked)`, pure, never mutates its input.
- `AuditMasked` — an optional interface a command implements to declare its masked fields
  (`Set<String> auditMaskedFields()`); absent = none.
- `OutboxSink.auditPayload` and `PlatformSink.writeAudit` serialise the command, then redact,
  then write. One helper shared by both (in `usecase`, e.g. `SinkSupport`).
- `SetPropertyCommand` implements `AuditMasked`.
- **Backstop:** `IngestApi` applies `AuditRedaction.redact(json, Set.of())` to every ingested
  audit row's `operationJson` before `AuditLogRepository.insertBatch` — SDK versions that predate
  this, and apps writing their own outbox rows, are covered by the name rule.

## TypeScript and Laravel SDKs

The same rule where each SDK builds an audit outbox row (`clients/typescript-sdk/src/outbox/`
`create-audit-log-dto.ts` / `outbox-manager.ts`; `clients/laravel-sdk/src/Outbox/`
`DTOs/CreateAuditLogDto.php` / `OutboxManager.php`): `operationData` is redacted before it is
serialised into the outbox payload. A masked-fields list may be passed by the caller alongside
`operationData`. Tests run the shared vectors. No SDK release in this unit.

## Tests (mutant each)

1. Java `AuditRedactionTest` runs every vector (mutant: drop the `endsWith("token")` clause →
   the token cases fail; keep booleans masked → the boolean case fails).
2. Through the real platform: creating a service account with HMAC webhook credentials and setting
   a `SECRET` platform config value leave **no** `token`/`signingSecret`/secret value text in the
   `aud_logs` row (`operation_json::text` does not contain the secret strings), while a `PLAIN`
   value is still recorded (mutant: remove the redaction call in `PlatformSink`).
3. `OutboxSink` writes a redacted `operation_json` (mutant: remove the call).
4. Ingest: an SDK-posted row with `{"password":"x"}` is stored as `"***"` (mutant: skip it).
5. TS and PHP: every vector passes (mutant in each: drop one suffix).
6. The vector copies are byte-identical to the canonical file.

## Out of scope here (owner decision pending)

Rows already written — rotation of the exposed credentials and a one-off `jsonb` cleanup of
`aud_logs`; and the same rule in Go's sink before cutover.
