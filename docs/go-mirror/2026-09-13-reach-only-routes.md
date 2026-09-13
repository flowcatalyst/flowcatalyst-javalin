# Go hand-off — reach-only routes gain their permission gates (2026-09-13)

For the Go agent. Authority: `docs/spec/reach-only-routes.md`; context
`docs/go-mirror/2026-09-13-permissions-from-roles.md` (the anchor bypass is
withdrawn; scope is reach, permissions come from roles).

## What Go has today

These handler files call `RequireAnchor` and never `RequirePermission`
(the same gap Java had):

`auth/api/api.go` (anchor domains, auth configs, idp-role-mappings, OAuth
clients, identity providers), `emaildomainmapping/api/api.go`,
`cors/api/api.go`, `loginattempt/api/api.go`, `shared/bff/developer.go`,
plus the client handlers. Once the bypass goes, an anchor holding only a
read-only role can still write on those routes, because there is no
permission gate for the bypass to have been bypassing.

## What to build

`RequireAnchor` stays on every route (reach). Add the permission gate per
the spec's §1 table — every family already has codes in the seed
(`platform:admin:client:*`, `platform:auth:oauth-client:*` incl.
`regenerate-secret`, `platform:iam:idp:*`, `platform:admin:anchor-domain:*`,
`platform:auth:client-auth-config:*`, `platform:iam:email-domain-mapping:*`,
`platform:admin:config:*`, `platform:admin:cors-origin:*`,
`platform:admin:login-attempt:view`, `platform:developer:*`). Two
sub-resources fold under their parent rather than minting codes:
idp-role-mappings under `iam:idp:view` / `iam:idp:update`; platform-config
access grants under `admin:config:view` / `admin:config:update`.
`GET /api/platform/cors/allowed` stays public. Two routes are deliberately
NOT gated: the email-domain-mapping `lookup` (consulted before login to pick
an IdP; it never had `RequireAnchor`) and the platform-config property
routes (`platform-config/{app}`, `config/{app}/{section}/{property}`), which
carry their own per-application access-grant gate under which a non-anchor
grant holder acts without a permission code — whether that grant model
should also require a code is an open question (`docs/backlog.md`), not
part of this unit.

## Seed

Four families existed in the seed but were held by no role. Add, exactly
as Java did (spec §2): `platform:iam-admin` gets IdP and email-domain-mapping
view/create/update/delete; `platform:iam-readonly` their views;
`platform:admin` gets config view/update and CORS-origin
view/create/delete; `platform:admin-readonly` their views;
`platform:viewer` the four views. Java's `SeederTest` fixture
(`go-seed-expected.tsv`) already carries these rows marked Java-first; once
Go seeds them the marks come off.

## Tests to carry over

Per handler: a read and a write refused for an anchor holding everything
except that family (`PERMISSION_REQUIRED`), and the read accepted with the
family's specific view code alone. Parity's role listings are allow-listed
as Java-first until the seed matches.
