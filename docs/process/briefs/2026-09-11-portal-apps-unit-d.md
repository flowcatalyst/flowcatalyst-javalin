# Brief — portal apps, unit D: parity corpus + frontend e2e

Runs after units F, A, B, C are merged on branch `portal-apps` (the
orchestrator commits; **you do not commit**). Go reference:
`/Users/andrewgraaff/Developer/flowcatalyst-go` at HEAD (read-only; it
already has this feature — note its `git log -1` in your report). Spec:
`docs/spec/portal-apps.md` (Part B § numbers below). Read `CLAUDE.md`,
`parity/README.md`, `docs/spec/parity-harness.md` §§ on scenarios /
allow-list / coverage, `e2e/README.md`, `docs/spec/frontend-e2e.md`.

`export JAVA_HOME=$(mise where java)`. Build via the reactor
(`mvn -pl parity -am …`), never `mvn install`. If the harness or runner
breaks in a way that doesn't yield to one clear fix, stop and report.

## 1. Parity scenarios

The lockfile now has 252 operations; coverage must be 252/252 again.

- New `parity/scenarios/portal-apps/portal-apps.json` covering
  `listPortalApps`, `createPortalApp` (CONFIDENTIAL with a callback, PUBLIC,
  duplicate code in another case ⇒ 409, wildcard callback ⇒ 400),
  `updatePortalApp` (rename, deactivate), `deletePortalApp` (one and two
  OAuth clients — the message suffix), and the §4.5 OAuth-client cases
  (`portalAppId` on create/update/response, `PORTAL_APP_CLIENT_MISMATCH`,
  `""` unlinks, clearing `portalClientId` clears the link,
  `PORTAL_APP_REQUIRES_PORTAL_CLIENT`).
- Extend `parity/scenarios/portal-users/portal-users.json`: ensure with
  `portalAppCode` (the §9.4 response), list with `q` / `portalAppCode` /
  `page` / `size` (the §9.3 cases), `grantPortalUserApp`,
  `revokePortalUserApp`, unknown app code ⇒ 404, inactive app grant ⇒ 400.
- Extend `parity/scenarios/portal/portal.json` (the portal login plane):
  the §9.5 gate sequence and the §9.6 redemption claims, if the harness can
  drive a portal password login + code redemption (it already drives the
  portal plane — follow what the file does).
- A profile-only scenario (§9.9): create a role-less USER principal, log it
  in for real, assert 403 `NO_PLATFORM_ROLE` on `/bff/roles` and
  `/api/clients`, 200 on `GET /api/me` and `/auth/me`.

Run the whole corpus against Go HEAD. Target: 0 DIFF, 0 ERROR. Triage every
DIFF — decide which side is wrong **against the spec**, not by majority:
a Java defect ⇒ describe it precisely (file, behaviour, the failing step) in
your report and do **not** fix production code yourself; a Go defect or an
owner-sanctioned difference ⇒ an `expected-diffs.json` entry with a reason
and a ruling reference (use `portal-apps.md` Part A's J-numbers where they
apply). Never allow-list something you cannot justify from the spec.

## 2. Frontend e2e

The re-synced SPA (Go frontend `373fe93`) removed the "Invite Portal User"
button, so `e2e/tests/identity.spec.ts`'s invite flow is dead. Replace it
with flows for the new pages (§7), each with a reloaded proof as the suite's
other flows do:

1. **Portal Apps**: create an app with a callback URL ⇒ the credentials
   dialog shows the app code, a client id and a secret; after a reload the
   app is listed with its OAuth client id; delete it ⇒ absent after reload.
2. **Portal Users**: seed an identity through the API
   (`POST /api/portal-users` with `portalAppCode`, `returnInviteLink: true`)
   ⇒ the page lists it with status *Invited* and the app chip; server search
   narrows the table; revoking the chip removes it after a reload.
3. **Profile-only**: a role-less user who logs in lands on `/profile`, any
   other route redirects there, and the sidebar is empty.

Run `pnpm e2e:java` (and `pnpm e2e:both` if Go's side runs — report Go's
column either way). All Java flows green.

## Report

Go commit; scenario files and steps added; corpus result (steps, OK,
ACCEPTED, DIFF, ERROR, coverage); every DIFF with its triage; e2e table;
Java defects found (for the orchestrator to fix).
