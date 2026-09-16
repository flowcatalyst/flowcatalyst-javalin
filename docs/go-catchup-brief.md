# Brief: catch up with two Go changes (after Vert.x conversion P1)

Sequencing: start after the Vert.x conversion's P1 (handler adapter) lands; both items touch the
principal and identity-provider handlers that P1 rewrites. Reference: `../flowcatalyst-go` main.

## 1. `inviteRedirectUri` on create-user / create-principal — Go `5ce1668` + `0e07df6`

Behaviour to match exactly (read both Go commits; `0e07df6` replaced the OAuth-client matcher with a
plain URL rule — do NOT port the matcher):
- Accepted on `POST /api/principals/users` and `POST /api/principals`, optional.
- Validated BEFORE anything is written: must be an absolute `http` or `https` URL. Rejected: a relative
  path, a URL with no scheme, `javascript:` or `data:` URLs, and URLs with embedded credentials
  (`https://trusted@evil.test`). Failure: 400 `INVITE_REDIRECT_URI_INVALID`, no user created.
- Stored on the invite token, so both the platform-sent invite email and `returnInviteLink` honour it;
  the set-password endpoint returns it and the set-password page follows it (after 2FA enrolment when
  the domain requires it). The link holder cannot change it, so the page is not an open redirect.
  Ignored when no invite is created (password supplied, federated user, or `sendInvitation:false`
  without `returnInviteLink`).
- Audit: record the redirect on the create-user audit entry so a misused caller is traceable.
- Tests to port: unit (redirect reaches both invite modes); integration (absolute https and http
  accepted; relative path, schemeless, `javascript:`, `data:`, embedded credentials rejected with no
  user created).
- Spec unchanged by `0e07df6`; the field itself needs adding to `sdk/openapi/openapi.json` + the lock
  (from `5ce1668`), and the SDK doc comments should carry the new wording.

## 2. `mappingScope` on identity-provider requests — Go `c05e1ed`

Go made `mappingScope` a required field on create/update identity-provider requests; the Java repo's
Go-vs-Java parity has been failing on it since (see the Rust parity harness lane report,
`docs/parity/l1.md` in the Rust repo). Port the field with Go's validation and defaults, regenerate the
spec/lock, and confirm the Go-vs-Java parity run is green again.

## Definition of done

Both fields in `openapi.json` and the lock; `parity/` Go-vs-Java run exit 0 with the new steps covered;
`e2e/` unaffected; `mvn -q test` green. Then tell the Rust parity programme (its resume log,
`docs/parity/progress.md` on `parity/integration`) to re-baseline the Java reference commit.
