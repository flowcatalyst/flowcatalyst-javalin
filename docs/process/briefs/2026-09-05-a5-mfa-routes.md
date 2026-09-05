# Brief — Phase 3 A5 routes: `/auth/2fa/*` and `/auth/change-password*`

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. The core
(`io.flowcatalyst.platform.auth.mfa.*`, commit `fa3a8a9`) is done and
mutation-checked; **do not change its behaviour**. This unit is the HTTP
layer over it.

## Authority

- `docs/spec/auth-identity.md` §6.2 (decision, already implemented by
  `LoginMfaGate`), **§6.3, §6.4 (the 14 routes — the table is the
  contract), §6.6, §6.7, §6.8**, and the §0.5 rulings in force: **I-Q12**
  (verify enforces the domain's allowed-method list; recovery codes never
  restricted), **I-Q11** (remember-device only when `DomainPolicy.rememberEnabled()`),
  **I-Q21** (trusted-device list items: `id, label?, expiresAt, createdAt,
  lastUsedAt?` — platform time shape, i.e. plain `Instant` fields through
  `Json.MAPPER`; **no `principalId`**), **defect 10** (`DELETE
  /auth/2fa/methods/{m}` and `/trusted-devices/{id}` → **404** when
  nothing was deleted, platform `NOT_FOUND` envelope), **I-Q20** (mounting
  as Go: six token-gated routes public, eight self-service routes inside
  the authenticator).
- Where the spec table and this brief disagree, the spec table wins;
  say so in the report.

## Files you own

```
server/src/main/java/io/flowcatalyst/platform/auth/mfa/api/TwoFactorApi.java
server/src/main/java/io/flowcatalyst/platform/auth/mfa/api/ChangePasswordApi.java
server/src/main/java/io/flowcatalyst/platform/auth/mfa/TwoFactorNotifier.java
server/src/test/java/io/flowcatalyst/platform/auth/mfa/api/TwoFactorApiTest.java
server/src/test/java/io/flowcatalyst/platform/auth/mfa/api/ChangePasswordApiTest.java
```

Plus these **small, named** edits elsewhere:

- `auth/login/LoginApi.java`: add an overload
  `public static void completeLogin(Context ctx, State s, Principal p, String ip, List<String> recoveryCodes)`
  (the existing four-arg one delegates with `null`) and a
  `loginResponse(State, Principal, List<String> recoveryCodes)` that fills
  the existing `recoveryCodes` field (omit when null/empty — it is
  `@JsonInclude(NON_NULL)` by the mapper default; check). Make `record(...)`
  and `unauthorized(...)` package-private if you need them, or copy the
  three lines — do not restructure the class.
- `server/Platform.java`: register both APIs right after `LoginApi.register`
  (one block; the `mfa`, `mfaTokens`, `mfaGate`, `loginMappingRepo`,
  `loginAttemptRepo`, `backoff`, `cookiesSecure` locals are already there —
  build a `LoginApi.State` once and share it with `TwoFactorApi.State`).
  Extend `isPublicPath` with exactly: `/auth/2fa/verify`,
  `/auth/2fa/challenge/email`, `/auth/2fa/enroll/totp/begin`,
  `/auth/2fa/enroll/totp/confirm`, `/auth/2fa/enroll/email/begin`,
  `/auth/2fa/enroll/email/confirm`. Every other `/auth/2fa/*` route and
  both change-password routes stay inside the authenticator (a bad bearer
  is 401'd by it; use `Auth.scoped` + `Auth.current()` like `LoginApi.me`).
- `server/LockfileCoverageTest.java`: `/auth/` is already an
  outside-lockfile prefix; nothing to add. Run it anyway.

## The core API you build on (read the classes; this is the map)

| Need | Call |
|---|---|
| parse `mfaToken` / `enrollToken` | `MfaToken.parse(token, Purpose.PENDING \| ENROLL)` → `Optional<Claims(subject, purpose)>`; empty ⇒ 401 `UNAUTHENTICATED` "Invalid or expired session" + header `WWW-Authenticate: Cookie realm="fc_session"`. Then load the principal (`PrincipalRepository.findById`), missing or inactive ⇒ the same 401. |
| domain policy | `new DomainPolicy.Evaluator(mappings).evaluate(p.email())` → `requires2fa()`, `permittedMethods()`, `permits(MfaMethod)`, `rememberEnabled()`, `rememberDays()` |
| confirmed factors | `mfa.confirmed(pid)` (`List<MfaMethod>`), `mfa.methods(pid)` (rows for `status.methods[]`: `id, principalId, type=method.name(), confirmedAt?, lastUsedAt?, createdAt`) |
| TOTP | `mfa.beginTotpEnrollment(pid, p.email())` → `TotpEnrollment(secret, uri, Optional qr)`; `mfa.confirmTotpEnrollment(pid, code)`; `mfa.verifyTotp(pid, code)`; exceptions `Mfa.AlreadyEnrolled`, `Mfa.NoPendingEnrollment`, `Mfa.EncryptionUnavailable` |
| e-mail PIN | `mfa.beginEmailEnrollment(pid, email)`, `mfa.confirmEmailEnrollment(pid, code)`, `mfa.sendLoginEmailPin(pid, email)`, `mfa.verifyLoginEmailPin(pid, code)`; a `RuntimeException` out of the send ⇒ **502** `EMAIL_SEND_FAILED` |
| recovery codes | `mfa.verifyRecoveryCode(pid, code)`, `mfa.generateRecoveryCodes(pid)`, `mfa.ensureRecoveryCodes(pid)` (first set, empty list when none generated), `mfa.remainingRecoveryCodes(pid)` |
| methods | `mfa.removeMethod(pid, MfaMethod)` → false ⇒ 404 |
| trusted devices | `mfa.issueTrustedDevice(pid, userAgent, Duration.ofDays(policy.rememberDays()))` → raw cookie value; `mfa.listTrustedDevices`, `mfa.countTrustedDevices`, `mfa.revokeTrustedDevice(pid, id)` → false ⇒ 404; `mfa.revokeAllTrustedDevices(pid)` |
| the cookie | `new TrustedDeviceCookie(cookiesSecure).set(ctx, raw, ttl)` / `.clear(ctx)` |
| finish a login | `LoginApi.completeLogin(ctx, loginState, p, ClientIp.of(ctx), recoveryCodesOrNull)` |
| backoff on verify | `BackoffCheck.check(emailLowerCased, ip, now)` → `Decision(allowed, retryAfterSecs, reason)`; denied ⇒ 429 `TOO_MANY_REQUESTS` "too many failed login attempts; try again later" + `Retry-After`; store failure ⇒ 503 `BACKOFF_UNAVAILABLE` exactly as `LoginApi.login` does |
| attempts | `LoginAttemptRepository.recordAttempt(LoginAttempt.attempt(AttemptType.USER_LOGIN, outcome, reason, email, pid, ip, userAgent))` — `FAILURE` "Invalid 2FA code" on a wrong code; the `SUCCESS` row comes from `completeLogin` |
| audit rows | `AuditLogRepository.insertBatch(List.of(new AuditLog(EntityType.AUDIT_LOG.generate(), "PRINCIPAL", pid, op, null, pid, p.name(), null, null, now)))` with `op` ∈ `2FA_TOTP_ENROLLED`, `2FA_EMAIL_ENROLLED`, `2FA_METHOD_REMOVED`, `2FA_RECOVERY_REGENERATED` |
| notices | new `TwoFactorNotifier` interface (this unit): `twoFactorEnrolled(email, MfaMethod)`, `twoFactorMethodRemoved(email, MfaMethod)`, `recoveryCodesRegenerated(email)`, `recoveryCodeUsed(email)`, `newTrustedDevice(email, label)`, `passwordChanged(email)`; `static logging()` default like `principal.Notifier.logging()`. Wire the logging one in Platform. |
| errors | `HttpError.write(ctx, status, code, message, Map.of())` — the platform envelope; `INVALID_JSON` = 400 "malformed request body" |

## Change-password (§6.8)

Session-gated. Body `{currentPassword, newPassword, code}`. Order:
`SSO_MANAGED` 400 (`p.isFederated()`), `NO_PASSWORD` 400 (no hash), wrong
current → 401 `INVALID_CURRENT_PASSWORD` (`PasswordHash.matches`),
`PasswordPolicy.check(newPassword, email, name)` rejected → 400 `<its code>`,
any confirmed factor and no `code` → 400 `{"code":"MFA_REQUIRED","message":"Enter a code from your second factor to change your password.","methods":[…confirmed]}`,
code accepted by **any** confirmed factor (`verifyTotp`, `verifyLoginEmailPin`)
or a recovery code when TOTP is confirmed, else 400 `INVALID_CODE`; then
`p.withPasswordHash(PasswordHash.hash(newPassword))` persisted through
`UnitOfWork` + `PrincipalRepository.persist` (see how `LoginApi.rehash`
writes), `mfa.revokeAllTrustedDevices(pid)` + `TrustedDeviceCookie.clear`,
`GrantStore.revokeAllForPrincipal(pid)` (best-effort, log on failure),
`notifier.passwordChanged(email)`, 200 `{"message":"Your password has been changed."}`.
`POST /auth/change-password/send-email-code`: `NO_MFA` (no confirmed
factor) / `NO_EMAIL_2FA` (EMAIL_PIN not confirmed) / `NO_EMAIL` 400,
`SEND_FAILED` 500, 200 `{"message":"A code has been sent to your email."}`.

## Tests (TestHttp over the embedded Postgres, like `LoginApiTest` and `OAuthProviderTest`)

Fixtures: raw `iam_principals` inserts with `PasswordHash.hash(...)`
(copy `LoginApiTest`), mappings through `EmailDomainMappingRepository`
+ `IdentityProviderRepository` (copy `LoginMfaGateTest.mapping`). Drive
TOTP codes with `Totp.code(secret, Totp.stepOf(Instant.now()))`; capture
PINs with a `MailSender` lambda that records the body (see
`MfaServiceTest.pinIn`). Wire a real `LoginApi` in the same `TestHttp`
so the flows are end-to-end: `/auth/login` → `enrollment_required` →
`/auth/2fa/enroll/totp/begin` → `confirm` → **cookie set + `recoveryCodes`
in the body**; `/auth/login` → `mfa_required` → `/auth/2fa/verify` with
`rememberDevice:true` → `fc_td` cookie set → the next `/auth/login`
proceeds without a challenge.

Assert behaviour, not existence (CLAUDE.md). Then run these mutants and
report each as killed (a mutant that survives means the test is
decorative — fix the test):

1. `verify` skips the `policy.permits(method)` check → the 403
   `METHOD_NOT_ALLOWED` test must fail.
2. `DELETE /auth/2fa/methods/{m}` answers 200 when `removeMethod` returned
   false → the 404 test must fail.
3. `verify` sets the trusted-device cookie even when
   `rememberEnabled()` is false → the no-cookie test must fail.
4. `LAST_FACTOR` (409) check dropped → its test must fail.
5. change-password skips the `MFA_REQUIRED` gate → its test must fail.
6. a wrong code at `verify` records no `FAILURE` attempt row → the
   attempt-count test must fail.

Run: `export JAVA_HOME=$(mise where java)`;
`mvn -q -pl server test -Dtest='TwoFactorApiTest,ChangePasswordApiTest,LoginApiTest,LockfileCoverageTest,ServerTest' -Dsurefire.timeout=300`
then the full `mvn -q -pl server clean test -Dsurefire.timeout=600` once.
Never `mvn install`. Never two Maven runs at once.

## Report

Routes table with status codes as implemented; which spec-table cells you
could not satisfy and why; the six mutants with the assertion that killed
each; test counts; anything in the core you needed and could not find
(do not work around it silently — name it).
