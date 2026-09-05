# Brief — Phase 3 C4: reset approvals (`iam_reset_approval_requests`, `/api/reset-approvals`)

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. The
password-reset core (`io.flowcatalyst.platform.passwordreset`, commit
`6770386`) already exists: its `ApprovalQueue` seam is what you implement,
its `ResetLinks.sendResetEmail(principal, reset2fa)` is what approval
sends, and the purger already marks PENDING rows past expiry `EXPIRED`
(`AuthHousekeeping.expirePendingApprovalRequests`, ruling defect 11).
Ruling I-Q19 keeps `requireStrongFactorForReset = false`, so the queue is
idle in production — port it fully anyway (feature parity; the lockfile
lists the routes).

## Authority

`docs/spec/auth-identity.md` **§3.7, §8.6, §11.10** and the §0.5 rulings
(defect 11: `EXPIRED` is written, the reviewer's `note` is persisted;
Q22: the system actor is `system`). The lockfile is the contract for the
three operations: `GET /api/reset-approvals` → `{requests:[…]}`,
`POST /api/reset-approvals/{id}/approve` and `/deny` → `{message}`.

## Files you own

```
server/src/main/java/io/flowcatalyst/platform/resetapproval/{ResetApprovalRequest, ResetApprovalStatus, ResetApprovalRepository, ResetApprovalQueue}.java
server/src/main/java/io/flowcatalyst/platform/resetapproval/operations/{QueueResetApproval, QueueCommand, DecideResetApproval, DecideCommand, ResetApprovalEvents}.java
server/src/main/java/io/flowcatalyst/platform/resetapproval/api/ResetApprovalApi.java
server/src/test/java/io/flowcatalyst/platform/resetapproval/**
```

Plus one registration block in `server/Platform.java` **right after the
`AuditLogApi.register(...)` line** (do not touch any other Platform
region — the orchestrator has pending edits elsewhere in that file), and
replace `ApprovalQueue.none()` in the `PasswordResetApi.State`
construction with your `ResetApprovalQueue` — that one substitution is
the only other Platform change. Template: `io.flowcatalyst.platform.oauthclient.**`;
CONVENTIONS.md §2/§3/§8.

## The aggregate (§3.7, §11.10)

`ResetApprovalRequest`: `id` (`EntityType.RESET_APPROVAL_REQUEST`, prefix
`rar_`), `principalId`, `clientId` (nullable), `status`
`PENDING|APPROVED|DENIED|EXPIRED` (enum with `parse`), `reset2fa`
(always true from `create`), `note` (nullable), `decidedBy`, `decidedAt`,
`expiresAt` (72 h), `createdAt`. Transitions: `create` → PENDING;
`approve(by, note)` / `deny(by, note)` only from PENDING and only while
unexpired (the repository's guarded `UPDATE … WHERE status = 'PENDING'
AND expires_at > now()` decides — 0 rows ⇒ the operation throws
`ALREADY_DECIDED` "request is no longer pending", a business-rule 400).

Repository: `findById`, `findPending(Visibility)` — anchors see every
pending unexpired row oldest first, others `client_id = ANY(clients)`
(an empty list ⇒ none), `hasPendingFor(principalId)`, `persist`/`delete`
(Persist), `decide(id, status, decidedBy, note, tx)` returning the row
count.

Operations (events under source `platform:iam`, subject
`platform.reset-approval.<id>`, group `platform:reset-approval:<id>`;
`principalId()` is the actor — the request's user is `userId`):
- `QueueResetApproval{principalId, clientId}` as the `system` actor:
  principal without a `clientId` ⇒ nothing (anchor users get no approval
  path); an unexpired PENDING request already exists ⇒ nothing; else
  insert `create(principalId, clientId, 72 h)` and emit
  `platform:iam:reset-approval:queued`; then notify every client-admin
  e-mail (active principals with role `platform:client-admin` and that
  `clientId` — add `PrincipalRepository.findClientAdminEmails(clientId)`
  if no such read exists; name it in the report) with the link
  `<base>/authentication/reset-approvals/<id>` through
  `Notifications.resetApprovalNeeded(to, link)`.
- `DecideResetApproval{id, status APPROVED|DENIED, note?}` by the admin:
  404 `ResetApprovalRequest_NOT_FOUND`; guarded decide; 0 rows ⇒
  `ALREADY_DECIDED`; event `platform:iam:reset-approval:decided`
  `{requestId, userId, status, decidedBy}`.

`ResetApprovalQueue implements passwordreset.ApprovalQueue` runs the
queue operation (best-effort: a failure is logged, the request answers
its silent 200 regardless).

## `/api/reset-approvals` (§8.6 table)

- `GET` — gate: any of the user create/update/delete permissions
  (`Checks.requireAny(ac, Permission.USER_CREATE, USER_UPDATE, USER_DELETE)`
  — check the enum names); `{requests:[{id, principalId, email, name,
  clientId?, expiresAt, createdAt}]}` with e-mail/name resolved per row
  from `PrincipalRepository` (blank strings when the principal is gone).
- `POST …/{id}/approve` — `Checks.requireUserAdmin(ac, request.clientId())`;
  decide APPROVED; principal gone → 404; `ResetLinks.sendResetEmail(p,
  request.reset2fa())` best-effort (log on failure); 200
  `{"message":"Reset approved — the user has been emailed a link"}`.
- `POST …/{id}/deny` — same gate; decide DENIED; 200 `{"message":"Reset request denied"}`.
  Both accept an optional body `{note?}` persisted as the reviewer's note (defect 11).

## Tests

`ResetApprovalRequestTest` (transitions), `ResetApprovalRepositoryTest`
(the guarded decide: two decisions on one row — only the first counts;
visibility by client list), `ResetApprovalOperationsTest` (events +
audit rows; queue skips anchor users and duplicates; client-admin
notices via a capturing `MailService`), `ResetApprovalApiTest` (TestHttp
with test headers: anchor sees all, a client admin sees only its client,
approve mails a reset link — capture it — and a second approve is 400
`ALREADY_DECIDED`, deny persists the note, a non-admin is 403).

Mutants to run and report as killed:
1. the decide loses its `status = 'PENDING'` guard → the double-decision test fails;
2. `findPending` ignores the client list → the client-admin visibility test fails;
3. the queue no longer skips a principal without a client → its test fails;
4. approve does not send the reset link → its test fails;
5. the reviewer's note is not persisted → the deny test fails.

Run: `export JAVA_HOME=$(mise where java)`;
`mvn -q -pl server test -Dtest='ResetApproval*Test,LockfileCoverageTest,ServerTest,PasswordResetApiTest' -Dsurefire.timeout=300`
then the full `mvn -q -pl server clean test -Dsurefire.timeout=600` once.
`-Werror` is on (Jackson 3: `asString()`, never `asText()`). Never
`mvn install`. Never two Maven runs at once.

## Report

Routes table; the lockfile coverage line (expect 245/245); the five
mutants with the killing assertion; anything you needed and could not
find (name it, do not work around it); commit nothing.
