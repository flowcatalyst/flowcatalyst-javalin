# Brief — Phase 5 S3: the routes outside the lockfile

Orchestrator: Fable. Coder: one Sonnet agent, medium effort, own worktree,
**scenario JSON only** (plus pruning `parity/surface.json`). Read
`docs/process/briefs/2026-09-05-p5-s1-scenarios.md` first — every rule
there holds — then `docs/spec/parity-harness.md` §3, §7, §8, and the
scenarios already on main under `parity/scenarios/auth/` (the format at
its fullest: form bodies, `location-param:` captures, `${totp:…}`, the
passkey `authenticator` steps) and `parity/scenarios/webauthn/`.

## The surface

`parity/surface.json` lists every `METHOD path` Java registers outside the
lockfile (generated from the route registrations). Your job: every entry
hit by some scenario, so the report's "outside-lockfile surface" line
reads 100%. First prune it: an entry that is *also* a lockfile operation
(`/api/config/{app}/{section}/{property}` and its siblings — check
`openapi.lock.json`) comes out of `surface.json`, with the reason in your
report. Then run the corpus once (`PARITY_GO_SRC=… mvn -q -pl parity -am
test -Dtest=ParityRunTest -Dsurefire.failIfNoSpecifiedTests=false`, ~12
minutes) and read `report.md`'s "missed surface" list — that is your
worklist; smoke, `auth/*` and `webauthn/*` already cover a good part.

## Groups, one file each under `parity/scenarios/<group>/`

- **bff**: `/bff/dashboard/stats`, `/bff/developer/**` (applications, event
  types, openapi current/versions/{specId}, `sync-platform-openapi`),
  `/bff/event-types/**` (list, filters, get, create, update, patch, archive,
  schemas add/finalise/deprecate, sync-platform, delete), `/bff/roles/**`
  (list, filters, permissions list/add/get, get/create/update/delete,
  sync-platform), `/bff/scheduled-jobs/**`, `/bff/filter-options/clients`.
  Spec `docs/spec/bff.md`. Confinement: the dashboard is admin-only; the
  rest per the spec — a client-scoped principal tries each.
- **me-public-config**: `/api/me`, `/api/me/applications`, `/api/me/clients`,
  `/api/me/clients/{clientId}`, `…/applications`; `/api/public/platform`,
  `/api/public/login-theme` (unauthenticated); `/api/config/platform`;
  `/api/openapi.json`, `/api/openapi.yaml`, `/q/openapi` (the documents
  are compared by hash — they *will* differ; report the diff, do not
  ignore it; the orchestrator rules); `/health`.
- **portal**: create a portal OAuth client (`POST /api/oauth-clients` with
  `portalClientId` = a client you created, `PUBLIC`, redirect URIs) and a
  portal identity (`POST /api/portal-users` with `returnInviteLink: true`;
  capture the link with `location-param`-style parsing — the token is the
  `token` query parameter of `/inviteUrl`: capture `inviteUrl`, then use
  `${b64url:…}`? No: add a step `GET /auth/password-reset/validate?token=`
  and `POST /auth/password-reset/confirm` with the token to set the
  identity's password — read `PasswordResetApi` for the bodies), then
  `POST /portal/auth/check-domain`, `POST /portal/auth/login` (right and
  wrong password; the response carries an authorization code for the
  portal client), `GET /portal/authorize` with the code, `POST
  /portal/auth/password-reset` (always a silent 200), `GET
  /portal/auth/oidc/login` for a domain with no IdP (the refusal shape).
  Specs `auth-identity.md` §5, §8. The token must be captured from the
  invite URL: the runner has no query-parameter capture on a *body*
  member, so capture the whole `inviteUrl` and pass it to a step that
  does not need the bare token if one exists; if none does, write the
  step with `"token": "${inviteUrl}"` and report the ERROR — the
  orchestrator adds a `body-param:` capture form. Do not modify the harness.
- **auth-remainder**: `/auth/2fa/enroll/*` through the enrolment gate (an
  email-domain mapping with `require2fa` on, a fresh principal in that
  domain, login → `enrollment_required` + `enrollToken`, enrol TOTP with
  `${totp:…}`), `/auth/2fa/challenge/email` and `/auth/2fa/methods/email/*`
  request surfaces (the PIN travels by mail; assert the 200 shapes and the
  refusals), `/auth/change-password/send-email-code`, `/auth/oidc/login`
  and `/auth/oidc/session/end` refusal shapes (no IdP in the seed),
  `/auth/password-reset/request` (silent 200; the wrong-email shape).

## Rules that bite here

- Sequence within a file only; never rely on another file's rows.
- `expect.status` only on the steps later steps depend on.
- Random values (challenges, ids, tokens, cursors, invite URLs) are masked
  by the harness's auto-capture; if a diff is only such a value, the
  member name is not on the auto-capture list — report it, do not `ignore`.
- No allow-list edits; every DIFF verbatim with a §9 guess.

## Report

Per file the routes covered; the surface line before and after; every
DIFF and ERROR verbatim with a guess; what you pruned from `surface.json`
and why. Commit on your branch; do not merge.
