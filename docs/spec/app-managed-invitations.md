# Application-managed invitations (create-user flags + first-login password setup)

Status: spec, 2026-09-14; §1a `inviteRedirectUri` added 2026-09-16 from Go `5ce1668`, rule
relaxed to any absolute http(s) URL the same day from Go `0e07df6`
(`resolveInviteRedirect`, `invite_redirect_pg_test.go`). Source of the behaviour: the Go working tree on
2026-09-14 (uncommitted on top of `f81fd5a`; handover
`../flowcatalyst-go/docs/java-sdk-invitation-handover.md`), files
`internal/platform/principal/api/{api,dto}.go`,
`internal/platform/auth/login/endpoint.go`,
`internal/platform/passwordreset/api/api.go`, `internal/server/wire_public.go`,
`api/openapi.lock.json`, `frontend/src/pages/auth/{LoginPage,ResetPasswordPage}.vue`.
Extends `principal.md` §7/§10, `auth-identity.md` §8, `auth-core.md` §6.1 A1.

## 0. What it is for

An application onboarding its own users through the SDK wants to send its
own branded e-mail, or none at all, instead of the platform's "set your
password" invite. Two patterns:

1. **Login-detected (recommended).** Create the user with
   `sendInvitation:false`, mail your own link to *your* application. When the
   user arrives at the platform's hosted login (via `/oauth/authorize`),
   check-domain reports `passwordSetupRequired`, the SPA shows "Create your
   password" and asks the platform to e-mail a set-password link (mailbox
   ownership is proven by the mail; the login page never accepts a first
   password inline). After the password is set the platform signs the user in
   and follows the stored OAuth redirect back to the application.
2. **Embedded link.** Create the user with `returnInviteLink:true`, read
   `inviteLink` from the response and embed it in your own mail. The user
   sets a password on the platform and is signed in. Pass `inviteRedirectUri`
   (e.g. your application's home page) to send them back to your
   application afterwards; without it they land on the platform's landing
   page.

The invite link is a live 72-hour bearer credential: never logged, never in
the audit trail, never in a `toString`.

## 1. Create-user flags (`POST /api/principals/users`, `POST /api/principals`)

Both request bodies gain two optional booleans. The lockfile
(`server/src/main/resources/openapi/openapi.lock.json`, copied from Go's
`api/openapi.lock.json`) is the arbiter; the copy lands first, or request
validation refuses the unknown keys.

| Field | Absent means | Effect |
|---|---|---|
| `sendInvitation` | `true` | `false` suppresses **all** platform mail for the new user: neither the invite (passwordless) nor the "account created" welcome (password supplied). Ignored for service accounts and federated/OIDC users, who never get mail. |
| `returnInviteLink` | `false` | `true` mints the 72-hour invite token and returns the set-password link as `inviteLink`. Only for a passwordless INTERNAL user; absent when a password was supplied or the user is federated. |
| `inviteRedirectUri` | absent | Where the invitee goes once the password is set (and 2FA enrolled where the domain requires it), with their platform session already established — normally the calling application's own page, which then starts its sign-in as usual. **Any absolute `http`/`https` URL**; it is not an OAuth redirect URI. Rides on the INVITE token, so both delivery modes honour it. Validated **before any write** (§1a); ignored when no invite is minted (a password was supplied, the user is federated, or `sendInvitation:false` without `returnInviteLink`). Added 2026-09-16 (Go `5ce1668`, relaxed to any absolute URL in Go `0e07df6`). |

**Precedence** (`notifyNewUser(state, principal, password, sendInvitation, returnInviteLink, inviteRedirect) → String|null`; `inviteRedirect` is the already-validated URI or `null` and is handed to whichever mint fires — `inviteLink(p, inviteRedirect)` on step 3, `sendInvite(p, inviteRedirect)` on step 5):

1. Service principal (`userIdentity == null`), federated (`isFederated()`), or
   blank e-mail → nothing, `null`.
2. `passwordless` = password null or empty.
3. `returnInviteLink && passwordless` → `inviteEmailer.inviteLink(p)`; return
   the link. A `RuntimeException` from the mint is logged at WARN (principal
   id only, never the link) and answers `null` — best-effort, and it does
   **not** fall through to sending the platform's own mail. The platform's
   own invite is never sent on this branch even when `sendInvitation` is
   true: each mint invalidates the previous token, so mailing a second link
   would break the one just returned.
4. `!sendInvitation` → log INFO "invite suppressed by caller" (principal id),
   `null`. Nothing is mailed, welcome included.
5. `passwordless` → `inviteEmailer.sendInvite(p)`; else
   `notifier.accountCreated(email)`. Failures logged, as today. `null`.

Bulk import always calls with `(true, false)` — the CSV surface gains no flags.

### 1a. `inviteRedirectUri` validation (`resolveInviteRedirect(raw) → String|null`)

Runs in both create handlers **after** the user-admin check and **before**
the PARTNER-merge lookup and the `CreateUser` write, so a refused value
leaves nothing behind — no user, no token, no mail. Bulk import never
receives one (`null`).

1. `raw` null or blank after trim → `null` (no redirect).
2. Otherwise `uri = raw.trim()` must parse as an absolute URL whose scheme is
   `http` or `https` (case-insensitive), with a non-empty authority (host) and
   **no userinfo**. The accepted value is the trimmed `uri`.
3. Anything else → 400 `INVITE_REDIRECT_URI_INVALID`
   `inviteRedirectUri must be an absolute http or https URL` (a `usecase`
   Validation error, like the other create-user 400s). Refused by
   construction: a relative path, a scheme-less or scheme-relative value,
   `javascript:` / `data:` / any non-web scheme, a URL with embedded
   credentials (`https://trusted@evil.test`), a scheme with no host.

Why this is enough (Go `0e07df6`, 2026-09-16, replacing the earlier rule
that tied the value to OAuth redirect URIs): the redirect is set by an
authenticated caller already allowed to create the user, and it is stored on
the invite token rather than read back from the link — whoever holds the link
cannot change it — so the set-password page is not an open redirect. The
value is the application's own page, not an OAuth `redirect_uri`, so the
`/oauth/authorize` allow-list was the wrong shape for it. No repository is
involved; the check is pure.

Downstream is already in place: `ResetToken.redirectUri` is stored at mint,
`POST /auth/password-reset/confirm` echoes it as `redirectUri` on both the
`ok` and `enrollment_required` answers (`passwordreset` spec), and the SPA's
set-password page follows it once the flow completes (immediately, or after
2FA enrolment).

`InviteEmailer`'s two methods take the redirect: `sendInvite(Principal, String
redirectUri)` and `String inviteLink(Principal, String redirectUri)`; `null`
means none. `ResetLinks` passes it to the INVITE mint (the same slot the
portal invites use).

Responses:

- `POST /api/principals/users` → `PrincipalResponse` gains a trailing
  `inviteLink` (`@JsonInclude(NON_NULL)`), set only on this create response
  when step 3 minted; `PrincipalResponse.from(...)` leaves it `null`
  everywhere else (list, by-id, update, PARTNER-merge return).
- `POST /api/principals` → status unchanged (201), body becomes
  `CreatePrincipalResponse{id, inviteLink?}` (`inviteLink` NON_NULL). A
  dedicated record in `PrincipalApi`, not a field on the shared
  `CreatedResponse`, which many unrelated creates reuse.

`InviteEmailer` gains `String inviteLink(Principal p, String redirectUri)` — mint the INVITE
token (deleting the subject's outstanding tokens, as every mint does) and
return the set-password link without mailing. `ResetLinks.inviteLink` is that
implementation. `InviteEmailer.logging()`'s `inviteLink` throws
`IllegalStateException("invite emailer not configured")` — step 3 turns it
into the logged best-effort `null`.

## 2. `POST /auth/check-domain` gains `passwordSetupRequired`

`CheckDomainResponse` gains a trailing `Boolean passwordSetupRequired`,
`@JsonInclude(NON_NULL)`: present (and `true`) only when the account is
awaiting password setup; the key is absent otherwise — never `false`, never
alongside `"authMethod":"external"`.

Set on every branch that answers `internal` (malformed domain, unmapped
domain, mapping to a non-OIDC provider) by:

1. e-mail trimmed + lower-cased; blank → omit.
2. When a backoff gate is wired (`State.backoff != null`):
   `backoff.check(email, ClientIp.of(ctx), now)` **read-only** — no attempt
   row is recorded; not allowed → omit (the plain `internal` answer, not a
   429). check-domain has no outcome of its own to record against, so the
   login pair's brute-force budget throttles this account-existence signal
   the same way a wrong password would.
3. `principals.findByEmail(email)`; any exception → omit.
4. `passwordSetupRequired = principal.awaitingPasswordSetup()`.

`Principal.awaitingPasswordSetup()` (one definition on the aggregate; §2 and
§3 must agree, so neither re-derives it): `active && isUser() &&
userIdentity != null && userIdentity.passwordHash() == null &&
!isFederated()` (federated = external identity linked or provider `OIDC`; a
non-OIDC provider such as a legacy import label is still eligible).

This is a UX hint, not a security decision: every failure omits the flag.

## 3. `POST /auth/password-setup/request`

Registered by `PasswordResetApi` next to `/auth/password-reset/request`, same
route group; added to `Platform.isPublicPath` (the exact path). Body
`{email, redirectUri?}`.

| # | Condition | Outcome |
|---|---|---|
| 1 | bad JSON / not an object | 400 `INVALID_BODY` `malformed request body` |
| 2 | everything else | 200 `{"message":"If your account needs a password, we've emailed you a link to create it."}` — always the same body; nothing reveals existence or eligibility |

`tryIssuePasswordSetupInvite(state, email, redirectUri)` runs before the
200 and every `RuntimeException` is logged at WARN (domain only) and
suppressed:

1. e-mail trimmed + lower-cased; blank → nothing.
2. `principals.findByEmail`; absent or `!awaitingPasswordSetup()` → nothing.
3. `policy.evaluate(email).internal()` must be true (mirrors check-domain's
   `internal` resolution: unmapped, or mapped to a non-OIDC provider);
   otherwise nothing.
4. `redirectUri`: kept only when it is a safe same-site relative path —
   starts with exactly one `/`, not `//`, not `/\`; anything else (absolute
   URL, scheme, bare host, empty) is dropped silently. Reuse an existing
   helper if the codebase has one (the portal/bridge login has the same
   rule); otherwise one package-private static in `passwordreset`.
5. `links.sendInviteRedirect(p, redirect)` — the ordinary 72-hour INVITE
   token carrying the redirect, the ordinary "Set your password" mail.

## 4. `POST /auth/password-reset/confirm` signs an invited user in

`PasswordResetApi.State` gains `TokenIssuer issuer` and `SessionCookie
cookie`, nullable **together** (as `LoginApi.State.attempts/backoff`); null
= the confirm never signs anyone in (today's behaviour, and what every
existing test constructs). `Platform` wires the same `tokenIssuer` and a
`new SessionCookie(cookiesSecure, (int) env.sessionTtlSeconds())` the login
uses — the two cookies must never drift.

After `postResetTwoFactor` has built the response (§8.4 step 12), and only
on the principal branch (never the portal branch):

- `shouldAttemptSessionMint(purpose, status, wired)` — package-private pure
  predicate: `wired && purpose == INVITE && "ok".equals(status)`. RESET keeps
  today's UX; `enrollment_required` mints its own session when enrolment
  completes.
- When true, `maybeEstablishSession`: re-read the principal (absent →
  nothing); `policy.evaluate(email).requires2fa()` → nothing (minting here
  would bypass the challenge); `issuer.sessionToken(id, email)` — a
  `RuntimeException` is logged WARN and leaves the user to sign in normally
  (the password write already succeeded); `cookie.set(ctx, token)`;
  `out.put("sessionEstablished", true)`.
- The key is absent otherwise (never `false`).

No login-attempt row is recorded for this sign-in (as in Go). Recorded as an
open question in `docs/backlog.md`, not changed here.

## 5. SDK (`sdk/`)

`sdk/openapi/openapi.json` is replaced by Go's
`clients/java-sdk/openapi/openapi.json` (it also brings the earlier
assign-unassigned / portal-app additions the vendored copy lacked). The
generated `CreateUserRequest`/`CreatePrincipalRequest` gain the two flags,
`PrincipalResponse` gains `inviteLink`, `CreatePrincipalResponse` appears.
Hand-written layer: Javadoc on `PrincipalsResource.createUser` describing
both flags, `inviteRedirectUri` (§1a), the precedence rule and the never-log warning; no convenience
overloads; no `create(CreatePrincipalRequest)` (none exists). README gains
"Creating users and invitations" with a snippet per pattern of §0.

## 6. Frontend

The SPA is Go's; `tools/sync-frontend.sh` rebuilds it out of tree and
refreshes the embedded copy. The 2026-09-14 login/reset pages must be synced
for the login-detected pattern to exist in the Java binary.

## 7. Tests that pin the load-bearing behaviour (break each on purpose once)

Principal API (real HTTP + DB, a counting `InviteEmailer`/`Notifier` fake):

| Assertion | Mutant it kills |
|---|---|
| default passwordless create-user: `sendInvite` 1, `inviteLink` 0, welcome 0, body has no `inviteLink` key | flags defaulting wrong way |
| `sendInvitation:false`, passwordless **and** with password: 0 mails of either kind | the welcome escaping the suppression |
| `returnInviteLink:true`: body `inviteLink` equals the fake's link, `sendInvite` 0, welcome 0; same with `sendInvitation:false` | precedence inverted; the platform mail also sent |
| `returnInviteLink:true` + password: no `inviteLink` key, welcome 1 | minting for a user with a password |
| the fake's `inviteLink` throws: 200, no `inviteLink` key, `sendInvite` 0 | best-effort falling through to the mail |
| `POST /api/principals` with the flag: body exactly `{id, inviteLink}`; without: exactly `{id}` | `CreatedResponse` still answered; `null` emitted |
| a real `ResetLinks.inviteLink` answers a link whose token `GET /auth/password-reset/validate` reports valid with purpose INVITE (or via `ResetTokenRepository`) | interface method not minting |
| `inviteRedirectUri` set to an absolute https URL with surrounding whitespace, with `returnInviteLink:true`: 200 with `inviteLink`, and the fake received exactly the trimmed URL | redirect dropped on the mint path; not trimmed |
| a plain `http://localhost:5173/...` URL with the platform invite (no `returnInviteLink`): the fake's `sendInvite` received the URL | redirect dropped on the mail path; `http` refused |
| a real `ResetLinks` mint with a redirect: the token row's `redirect_uri` equals the URL | `ResetLinks` passing `null` |
| each of: a relative path, no scheme, scheme-relative `//host`, `javascript:`, `data:`, `ftp:`, embedded credentials, `https:///path` → 400 `INVITE_REDIRECT_URI_INVALID` **and** `findByEmail` is empty afterwards | any clause of step 2 dropped; validation after the write |
| `inviteRedirectUri: "  "`: 200, the fake received `null` | blank treated as a URL |
| `POST /api/principals` with `javascript:alert(1)`: 400, no user | the field only wired on `/users` |

Login API:

| Assertion | Mutant |
|---|---|
| eligible user → body is exactly `{"authMethod":"internal","passwordSetupRequired":true}` | flag never set |
| user with a password / inactive / unknown e-mail → no `passwordSetupRequired` key (not `false`) | predicate wrong; `false` emitted |
| OIDC-mapped domain with an eligible user → `external`, no flag | flag on the external branch |
| a backoff-locked (email, ip) pair → no flag for an eligible user, and no attempt row written | limiter not consulted; recording |

Password reset API:

| Assertion | Mutant |
|---|---|
| password-setup/request for an eligible user: one INVITE token for the subject, expiry ≈ now+72h, mail to the user containing `/auth/set-password?token=`, 200 fixed message | eligibility or purpose wrong |
| user with a password / OIDC-mapped domain / unknown: no token, no mail, same 200 | leak or over-issue |
| `redirectUri:"/dashboard"` stored on the token; `"//evil.example"` and `"https://…"` stored as null | open redirect |
| confirm of an INVITE token, domain without 2FA: `Set-Cookie: fc_session=…; Path=/; HttpOnly; SameSite=Lax; Max-Age=<session ttl>` **and** the cookie authenticates a `GET /auth/me`, body `sessionEstablished:true` | mint skipped; wrong TTL |
| confirm of a RESET token: no `Set-Cookie`, no `sessionEstablished` key | purpose gate |
| INVITE + 2FA-required domain, no factor: `enrollment_required`, no cookie | 2FA bypass |
| State with `issuer == null`: no cookie | null-safety |
| unit tables: `Principal.awaitingPasswordSetup` (the nine Go cases), `shouldAttemptSessionMint` (five cases), the safe-relative rule (eight cases) | each branch |

Contract: `LockfileCoverageTest`, the schema-validation tests and
`FrontendTest` stay green on the copied lockfile and SPA.
