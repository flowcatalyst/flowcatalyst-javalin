# Emit `platform:iam:user:logged-in` on a successful OIDC login (owner request 2026-09-17)

Status: **requested by the owner, implementing in both repos.** Scope is
**OIDC logins only** (owner, 2026-09-17). Password, 2FA and passkey logins do
not emit it.

## Reference
The Rust platform emits it — `../flowcatalyst-rust/crates/fc-platform/src/auth/oidc_login_api.rs:709-782`
(event struct `src/principal/operations/events.rs:505-584`, claim capture
`:1105-1133`, access-token decode `:803-811`). Neither Java nor Go emits it
today; both already seed the event type and its schema
(`PlatformEventSchemas.loggedIn()` / Go `seed/event_schemas.go:50`). The
payload must **validate against that seeded schema**.

## Behaviour

### When
On the employee-plane OIDC callback (Java `OidcBridgeApi` callback; Go
`internal/platform/auth/bridge/…`), **after** the session token has been
minted successfully and **before** the redirect — i.e. only for a login that
actually succeeded. Not on the portal-flow completion (`s.portal().complete`),
not on any failure branch.

**Best-effort:** if emitting fails, log WARN (`"failed to emit UserLoggedIn
event (login still succeeded)"`, with `principal`) and complete the login
normally. The login response must not change.

### Envelope
- Emitted through the use-case envelope as an event-only plan (Java
  `Plan.emit(...)`; Go `usecaseop.Emit(...)`) so it is written with its audit
  row like every other event. Operation / command name **`OidcLogin`**,
  command fields `email`, `identityProviderId`.
- Execution context: actor = the logged-in principal's id.
- Event type `platform:iam:user:logged-in`, spec version `1.0`, source
  `platform:iam`, subject `platform.user.{userId}`, message group
  `platform:user:{userId}` — follow the existing principal events' metadata
  helpers where they already produce these shapes.
- Java: the data field for the user is **`userId`** — never a record
  component that shadows `DomainEvent.principalId()` (`DomainEventContractTest`).

### Data (camelCase JSON)
| Field | Value |
|---|---|
| `userId` | the principal's id |
| `email` | the login identifier the callback already uses (normalised email) |
| `loginMethod` | `"OIDC"` |
| `identityProviderCode` | the identity provider's `code` |
| `flowcatalystClaims.email` | same as `email` |
| `flowcatalystClaims.type` | `"USER"` |
| `flowcatalystClaims.roles` | the principal's role names **after** the IdP role sync for this login (re-read the principal; Rust uses the synced principal) |
| `flowcatalystClaims.clients` | `["*"]` for an anchor-scope principal, else its assigned client ids |
| `flowcatalystClaims.applications` | distinct, sorted prefixes before the first `:` of each role name, skipping roles with no prefix (`"ondemand:admin"` → `"ondemand"`) |
| `federatedClaims.idToken` | the **verified** id token's full claim set as a JSON object, minus `nonce`, `at_hash`, `c_hash` |
| `federatedClaims.accessToken` | the access token's payload decoded **without verification** if it is a three-part JWT whose middle part is base64url JSON; otherwise `{}` (opaque token) |

The raw access token and id token strings are **never** put in the event.

### Plumbing this needs
- The code exchange must return the **access token** alongside the id token
  (Java `OidcProvider.exchange` currently returns only `Optional<String>` id
  token).
- The verified claims must carry the **raw claim set** (Java `IdTokenClaims`
  has only parsed fields; add the raw map, built from the verified
  `JWTClaimsSet`).

## Tests — each must fail under its mutant (CLAUDE.md policy)
Drive the real callback against the existing fake IdP test harness (Java
`OidcBridgeTest`; Go's bridge tests). Assert on the **stored event row**
(the outbox/events table the envelope writes), not on a method call.

| # | Assert | Mutant |
|---|---|---|
| T1 | successful OIDC login → exactly one `platform:iam:user:logged-in` event for that user, `loginMethod` `OIDC`, `identityProviderCode` = the IdP code | remove the emit |
| T2 | `federatedClaims.idToken` contains a **custom** claim the fake IdP issued (e.g. `"department"`) and does **not** contain `nonce` | use parsed fields only / don't strip `nonce` |
| T3 | JWT access token → `federatedClaims.accessToken` has its payload claim; opaque access token → `{}` | always `{}` |
| T4 | `flowcatalystClaims.roles` includes a role granted by this login's IdP role sync; `applications` is its prefix | use the principal as loaded before the sync |
| T5 | a failed login (e.g. nonce mismatch) emits no event | emit before the failure checks |
| T6 | the stored event data validates against the seeded `platform:iam:user:logged-in` schema | break a required field name |
| T7 | Java only: `DomainEventContractTest` still passes | — |

"Emit failure does not fail the login" — assert it only if a failing
envelope can be injected cheaply in the existing harness; otherwise say so
and leave it unasserted.
