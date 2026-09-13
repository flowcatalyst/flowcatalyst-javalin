# Reach-only routes gain their permission gates (owner go-ahead 2026-09-13)

Follows `docs/spec/permissions-from-roles.md` §3a. Nine API classes gated on
`Checks.requireAnchor` alone, so once the anchor bypass was withdrawn an
anchor holding only a read-only role could still write there. Owner rule for
codes: **reuse a good existing permission; mint a new one only where none
fits.** Every family below already has codes; two sub-resources fold under
their parent's codes rather than minting. `requireAnchor` stays on every
route (reach); the permission gate is added after it.

## 1. Route → permission

| Class | Routes | Permission |
|---|---|---|
| `ClientApi` | list, search (GET+POST), by-identifier, get, `{id}/applications` GET | `CLIENT_VIEW` |
| | create | `CLIENT_CREATE` |
| | update, notes, `{id}/applications` PUT, application enable/disable | `CLIENT_UPDATE` |
| | delete | `CLIENT_DELETE` |
| | activate / suspend / deactivate | `CLIENT_ACTIVATE` / `CLIENT_SUSPEND` / `CLIENT_DEACTIVATE` |
| `OAuthClientApi` | list, by-client-id, get | `OAUTH_CLIENT_VIEW` |
| | create | `OAUTH_CLIENT_CREATE` |
| | update, activate, deactivate | `OAUTH_CLIENT_UPDATE` |
| | rotate-secret, regenerate-secret, revoke-previous-secret | the regenerate-secret code (`platform:auth:oauth-client:regenerate-secret`) |
| | delete | `OAUTH_CLIENT_DELETE` |
| `IdentityProviderApi` | list, get | `IDP_VIEW` |
| | create / update / delete | `IDP_CREATE` / `IDP_UPDATE` / `IDP_DELETE` |
| `AuthAdminConfigApi` | anchor-domains GET / POST / PUT / DELETE | `ANCHOR_DOMAIN_VIEW` / `CREATE` / `UPDATE` / `DELETE` |
| | auth-configs GET / POST / PUT / DELETE | `CLIENT_AUTH_CONFIG_VIEW` / `CREATE` / `UPDATE` / `DELETE` |
| | idp-role-mappings GET | `IDP_VIEW` (a mapping is IdP configuration — folded, no new code) |
| | idp-role-mappings POST / DELETE | `IDP_UPDATE` |
| `EmailDomainMappingApi` | list, by-domain, get | `EMAIL_DOMAIN_MAPPING_VIEW` |
| | `lookup` | **unchanged, no gate** — consulted before login to pick the IdP for an email domain; it never had `requireAnchor` and was never reach-only |
| | create / update + move-provider / delete | `_CREATE` / `_UPDATE` / `_DELETE` |
| `PlatformConfigApi` | `{app}/access` GET | `CONFIG_VIEW` |
| | access POST / DELETE | `CONFIG_UPDATE` (access grants are configuration — folded, no new code) |
| | `platform-config/{app}` GET, `config/{app}/{section}/{property}` GET / PUT / DELETE | **unchanged** — these were never reach-only: they carry their own per-application access-grant gate (`Access`), under which a non-anchor grant holder reads or writes one application's config without holding a permission code. Folding a permission code onto them is a different design question (grant model vs. role model), left open in `docs/backlog.md` |
| `CorsOriginApi` | `/allowed` | **public, unchanged** |
| | list, get | `CORS_ORIGIN_VIEW` |
| | add / delete | `CORS_ORIGIN_CREATE` / `CORS_ORIGIN_DELETE` |
| `LoginAttemptApi` | list | `LOGIN_ATTEMPT_VIEW` |
| `DeveloperBff` | applications, get, event-types, openapi current/versions/{specId} | `DEVELOPER_APPLICATION_OPENAPI_VIEW` |
| | sync-platform-openapi | `DEVELOPER_APPLICATION_OPENAPI_SYNC` |

## 2. Roles: codes that no built-in role held

The IdP, email-domain-mapping, CORS-origin and config families existed in
`Permissions` but were assigned to no role — harmless while anchors bypassed
the gate, fatal now. Seed additions (Java-first; Go mirrors; SeederTest's
fixture gains the rows):

| Role | Adds |
|---|---|
| `platform:iam-admin` | IdP view/create/update/delete; email-domain-mapping view/create/update/delete |
| `platform:iam-readonly` | IdP view; email-domain-mapping view |
| `platform:admin` | config view/update; CORS-origin view/create/delete |
| `platform:admin-readonly` | config view; CORS-origin view |
| `platform:viewer` | IdP view; email-domain-mapping view; config view; CORS-origin view |

`platform:super-admin` holds the wildcard; `auth-admin`/`auth-readonly`
already hold the OAuth-client and auth-config families; `admin` already
holds clients, anchor domains, login attempts and the developer family.

## 3. Tests (each with a killed mutant)

Per API class, in its existing `*ApiTest`: one read and one write refused
for an anchor test principal that holds every permission **except** the
family under test (403 `PERMISSION_REQUIRED`), and the same calls accepted
with the family's specific code (not the wildcard). Mutant: remove that
route's `require` line. `PlatformCatalogueTest`/`SeederTest` pin the new
role rows. `CorsOriginApiTest`: `/allowed` still answers without any
principal.

## 4. Parity

The corpus authenticates as the super-admin, so route gating changes no
existing step. The role listings change (permissions arrays) and are
allow-listed as Java-first with this spec, to retire when Go mirrors
(`docs/go-mirror/2026-09-13-reach-only-routes.md`).
