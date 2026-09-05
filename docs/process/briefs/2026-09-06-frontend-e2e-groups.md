# Brief — frontend end-to-end: the remaining groups (two agents)

Orchestrator: Fable. Coders: two Sonnet agents, medium effort, own
worktrees, **`e2e/tests/*.spec.ts` and `e2e/fixtures/*` only** — the runner
(`e2e/runner`, `e2e/scripts`) is done and not yours; if it lacks something
you need, say so in the report and work around it in the test. Read
`docs/spec/frontend-e2e.md` §3–§5, `e2e/README.md`, `e2e/tests/auth.spec.ts`
(the style: titles that name the outcome, assertions on reloaded state,
`page.request` for the wire check), and `e2e/fixtures/`.

The Go side cannot boot today (`docs/backlog.md`, Go's seeder); run and
prove everything with `pnpm e2e:java`. Write the flows so they will run
unchanged on Go once it boots — nothing Java-specific in a selector or an
expectation, and no `E2E_SIDE` branches.

| Agent | Groups (spec §3) |
|---|---|
| E2E-A | **2fa**, **passkeys** (Playwright's CDP virtual authenticator), **tenancy** (the confinement flow — mandatory), **authorization**, **identity** |
| E2E-B | **catalogue** (applications incl. developer pages, event-types incl. schemas, events, processes, subscriptions, connections, dispatch pools, dispatch jobs, scheduled jobs + instances), **authentication admin**, **platform** |

## Rules

- One spec file per group, named after it; a comment at the top naming the
  screens (`frontend/src/pages/...` in the Go repo, read-only) and the
  parity scenario files that cover the same routes on the wire.
- Every screen in the group gets visited; every create is asserted by
  navigating away and back; every delete by absence after reload; every
  refusal by the modal or banner *and* the missing row.
- The tenancy flow (E2E-A): create a client and a client-scoped principal
  through the UI, log in as it, walk every list page in the app and assert
  it sees only its own client's rows, then hit an anchor-only screen and
  assert the permission-denied modal. This is the one flow that must never
  be skipped or weakened.
- 2FA: the TOTP secret is shown as text on the setup screen; compute codes
  with `otpauth` (add it to `e2e/package.json` devDependencies). Passkeys:
  `CDPSession` → `WebAuthn.enable`, `WebAuthn.addVirtualAuthenticator`
  (`protocol: "ctap2", transport: "internal", hasResidentKey: true,
  hasUserVerification: true, isUserVerified: true`).
- Fixtures shared by both agents (`e2e/fixtures/`): add what you need under a
  file named for your group to avoid the other agent's file.
- No `test.skip` without a reason string that names the missing piece.

## Report

Per group the screens visited and the flows; the `pnpm e2e:java` table
verbatim; anything the SPA does that the design did not foresee; every
flow you could not make pass with why. Commit on your branch; do not merge.
