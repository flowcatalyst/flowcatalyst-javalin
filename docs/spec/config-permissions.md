# Platform config: permissions from roles; invite sign-in recorded

Owner rulings 2026-09-23. Two small units, one spec.

## A. Config is gated by permissions, like everything else

### A.1 Today

The property routes (`GET/PUT/DELETE /api/config/{app}/{section}/{property}`,
`GET /api/platform-config/{app}`) pass when the caller is **anchor** (no
permission checked) or holds a role listed for that application in
`app_platform_config_access` (`Access.requireRead/requireWrite`).
`platform:admin:config:view` / `:update` gate only the three routes that edit
that table. The table has no UI; the SPA uses the property routes only for
the two Settings pages (Theme → `platform/login/theme`, Names →
`platform/branding/platform-name`). The SDKs carry generated endpoints for
the property routes and no wrapper that calls them.

This contradicts the 2026-09-13 ruling (`permissions-from-roles.md`):
permissions always come from roles; anchor is reach, not authority.

### A.2 Rule

| Route | Requires |
|---|---|
| `GET /api/platform-config/{app}` | `platform:admin:config:view` |
| `GET /api/config/{app}/{section}/{property}` | `platform:admin:config:view` |
| `PUT /api/config/{app}/{section}/{property}` | `platform:admin:config:manage` |
| `DELETE /api/config/{app}/{section}/{property}` | `platform:admin:config:manage` |

- `platform:admin:config:manage` **replaces** `platform:admin:config:update`
  (owner: "manage", so create and delete are covered, not only update).
  `update` ceases to exist: removed from `Permissions`, `Permission`, the
  seed and the catalogue.
- Anchor no longer passes by being anchor. No per-application restriction:
  a holder of `view` reads every application's config.
- 403 with the standard permission-denied envelope the other
  `Checks.require` routes answer (not the old `FORBIDDEN` / "No read access
  to platform config for <app>" text).
- **Secret masking:** a `SECRET` value is returned unmasked only to a holder
  of `manage` (was: anchors). `view` alone sees the mask. The PUT response
  keeps answering the value as written (spec §4 open question 5 unchanged).
- `/api/config/platform` (public, `PublicApi`) is a different route and is
  **unchanged**.

### A.3 The access table goes

- `GET /api/platform-config/{app}/access`, `POST /api/platform-config/{app}/access`,
  `DELETE /api/platform-config/access/{id}` are removed (404 as any unknown
  route). `ConfigAccess`, `ConfigAccessRepository`, `GrantAccess`,
  `RevokeAccess`, their commands and events, and the DTOs go.
- `Access` goes; `SetProperty.authorize` checks `manage` instead.
- **The table itself is not dropped** in this unit: the schema is shared
  with Go until cutover (additive-migrations gate). A backlog line records
  the drop for after cutover.
- OpenAPI lockfile / generated SPA + SDK types regenerated; the three
  endpoints disappear from the TS and Laravel SDKs' generated code.

### A.4 Seeded roles and existing rows

| Role | Before | After |
|---|---|---|
| `platform:super-admin` | `platform:*:*:*` | unchanged (wildcard covers both) |
| `platform:admin` | view + update | view + **manage** |
| `platform:admin-readonly` | view | view |
| `platform:viewer` | view | view |

Migration `V17__config_manage_permission.sql` (data only):
`UPDATE iam_role_permissions SET permission = 'platform:admin:config:manage'
WHERE permission = 'platform:admin:config:update'` — covers custom roles
holding the old code; delete a duplicate if a role already carried both.
(Column names per the baseline; verify before writing.)

### A.5 Tests that must pin it (mutant each)

1. A non-anchor role with `view` reads any app's property; without it 403.
2. An **anchor** principal with no config code: GET 403, PUT 403 (the old
   anchor pass is gone — the mutant restoring `ac.isAnchor() ||` must fail).
3. `view` without `manage`: PUT 403, DELETE 403, and a `SECRET` value is
   masked; with `manage`, unmasked.
4. A role still holding `platform:admin:config:update` after V17 holds
   `manage` (migration test against a seeded row).
5. The three access routes answer 404.
6. The SPA's Theme and Names pages still load and save for `platform:admin`
   (e2e flows already cover them — they must stay green).

## B. An invite sign-in is recorded as a login

`POST /auth/password-reset/confirm` of an INVITE token that mints
`fc_session` (`app-managed-invitations.md` §4, `maybeEstablishSession`)
writes one `iam_login_attempts` row: `USER_LOGIN`, `SUCCESS`, identifier =
the principal's normalised email, principal id, IP and user agent as the
password login records them. Written only when the session is actually
minted — a RESET token, an `enrollment_required` answer or an unwired state
writes nothing. A failure to write the row is logged and does not fail the
confirm (same best-effort rule as the password login's recording).

Test: an invite confirm on a no-2FA domain adds exactly one SUCCESS row for
the principal (count before/after); a RESET confirm adds none; mutant
removing the write fails the first.

## Error handling (applies to A and B)

Per `CONVENTIONS.md` §8 and the owner's standing rule: an expected outcome is
a sealed type returned and switched on, never a `throw`. The permission
checks stay where the conventions already put them — `Checks.require` at the
route, the operation's authorize phase for `SetProperty` — because those are
the envelope's established boundaries. Anything new below them (the
secret-masking decision, B's "was a session minted" decision and its
best-effort row write) returns an outcome: no new exception type, no
nullable-as-result, and a failed row write is a logged outcome, not a caught
exception carried upward.

## C. Go

The owner is moving off Go; no Go mirror is owed. Parity allow-list entries
for the changed status codes on the config routes and the removed access
routes are added with this ruling's id.
