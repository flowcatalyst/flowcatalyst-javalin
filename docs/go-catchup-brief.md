# Brief: catch up with two Go changes (after Vert.x conversion P1)

Sequencing: start after the Vert.x conversion's P1 (handler adapter) lands; both items touch the
principal and identity-provider handlers that P1 rewrites. Reference: `../flowcatalyst-go` main.

## 1. `inviteRedirectUri` on create-user / create-principal — Go `5ce1668`

Behaviour to match exactly (read the Go commit and `internal/platform/principal/api/{api,dto}.go`):
- Accepted on `POST /api/principals/users` and `POST /api/principals`, optional.
- Validated BEFORE anything is written: must be permitted by a redirect URI of an active, non-portal
  OAuth client with the `authorization_code` grant, linked to an application the caller can access;
  clients linked to no application count only for all-applications callers. Same matcher as
  `/oauth/authorize` (tenant wildcards work). Failure: 400 `INVITE_REDIRECT_URI_INVALID`, no user created.
- Stored on the invite token, so both the platform-sent invite email and `returnInviteLink` honour it;
  the set-password endpoint returns it and the set-password page follows it (after 2FA enrolment when
  the domain requires it). Ignored when no invite is created (password supplied, federated user, or
  `sendInvitation:false` without `returnInviteLink`).
- Tests to port: unit (redirect reaches both invite modes); integration (wildcard accepted; another
  app's URL, machine-to-machine client, portal client, unregistered URL, relative path all rejected with
  no user created; all-applications callers get the wider scope).
- Regenerate `sdk/openapi/openapi.json` + the lock; Java SDK picks the field up from generated models;
  document it in the SDK docs. Add a parity scenario step so Go-vs-Java covers it.

## 2. `mappingScope` on identity-provider requests — Go `c05e1ed`

Go made `mappingScope` a required field on create/update identity-provider requests; the Java repo's
Go-vs-Java parity has been failing on it since (see the Rust parity harness lane report,
`docs/parity/l1.md` in the Rust repo). Port the field with Go's validation and defaults, regenerate the
spec/lock, and confirm the Go-vs-Java parity run is green again.

## Definition of done

Both fields in `openapi.json` and the lock; `parity/` Go-vs-Java run exit 0 with the new steps covered;
`e2e/` unaffected; `mvn -q test` green. Then tell the Rust parity programme (its resume log,
`docs/parity/progress.md` on `parity/integration`) to re-baseline the Java reference commit.
