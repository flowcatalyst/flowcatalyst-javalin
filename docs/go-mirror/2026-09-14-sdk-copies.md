# Go hand-off — SDK sources shared, Java SDK release model settled (2026-09-14)

For the Go agent. Context: `docs/sdk-release-plan.md` (this repo). The owner
asked to "split out the SDKs and send them to the SDK repos" — this unit
built the pipeline in the Java repo. It does **not** retire anything on the
Go side; that is a separate, later step this note flags.

## Both repos hold the TypeScript and Laravel sources for now

Exactly like the frontend move (`docs/go-mirror/2026-09-14-frontend-source-shared.md`):
this is not a cutover. `clients/typescript-sdk` and `clients/laravel-sdk`
were imported into the Java repo with their Go history (subtree split from
Go `3f1d299`, an earlier unit), and the Go repo keeps its own copies and its
own `split-typescript-sdk.yml` / `split-laravel-sdk.yml` unchanged. The Java
repo now has its own copies of those two workflows, pointed at the same
target repos (`flowcatalyst/typescript-sdk`, `flowcatalyst/laravel-sdk`) and
secret names (`TYPESCRIPT_SDK_TOKEN`, `LARAVEL_SDK_TOKEN`).

`tools/sdk-drift.sh` (Java repo) diffs both folders against a Go checkout
(`diff -rq`, excluding `node_modules`, `dist`, `vendor`, `.DS_Store`, and
`openapi-processed.json` — gitignored on the Go side too) and exits 1 on any
difference. Run from the Java repo: `tools/sdk-drift.sh ../flowcatalyst-go`.

## Tag ownership: only one repo may cut a given tag

Both Go's and the Java repo's split workflows force-push the same standalone
`main` (`flowcatalyst/typescript-sdk`, `flowcatalyst/laravel-sdk`). If both
repos' workflows are live, a `typescript-sdk/v*` or `laravel-sdk/v*` tag
pushed from either repo will fight the other's next push. **Today, only the
Go repo's workflows are actually usable** — the Java repo has no
`TYPESCRIPT_SDK_TOKEN` / `LARAVEL_SDK_TOKEN` secrets yet, so its copies are
present but dormant. Whoever cuts the next TS/Laravel release should keep
doing it from Go until the owner explicitly retires the Go side and adds
those two secrets here.

## The Java SDK's release model, decided

`docs/sdk-release-plan.md` §4 laid out a "GitHub Packages artifact + optional
source mirror" option and a "Go's `clients/java-sdk` is retired" step. **The
owner ruled otherwise**: the Java SDK (this repo's `sdk/` reactor module)
gets exactly the release *Go's `clients/java-sdk` already has* — a `VERSION`
file (seeded at `0.0.4`, Go's last `java-sdk/v*` tag) and
`scripts/release.sh java <bump>`, which bumps the file and tags
`java-sdk/vX.Y.Z`. No publish workflow, no GitHub Packages artifact, no new
source repo. This is a decision **about this repo's module**, not a
retirement instruction for Go's `clients/java-sdk` — nothing here asks the
Go repo to change or remove its own Java SDK copy. If the two are to
converge later (one canonical Java SDK, one `java-sdk/v*` tag stream), that
is a separate owner decision, not made today.

## Go's `clients/java-sdk` is behind on the invitation-handover work

Worth flagging directly: `docs/java-sdk-invitation-handover.md` (Go repo)
asked an agent to mirror the `sendInvitation` / `returnInviteLink`
create-user work into `clients/java-sdk`. Checked as part of this unit —
**that work landed in the Java repo's `sdk/` module, not in Go's
`clients/java-sdk`.** Specifically, Go's `clients/java-sdk` has none of:

- the `createUser` Javadoc describing `sendInvitation` / `returnInviteLink`,
  the precedence rule, and the never-log warning on `inviteLink`
  (present in this repo's `sdk/src/main/java/io/flowcatalyst/sdk/resources/PrincipalsResource.java`);
- the "Creating users and invitations" README section (present in this
  repo's `sdk/README.md`);
- a `PrincipalsResourceTest` covering the three behaviours the hand-off note
  asked for (present in this repo's
  `sdk/src/test/java/io/flowcatalyst/sdk/resources/PrincipalsResourceTest.java`).

`grep -n "sendInvitation\|returnInviteLink" clients/java-sdk/src -r` in the
Go repo currently returns nothing. This is not something this unit fixed —
it is exactly the kind of two-copy drift the tag-ownership rule above exists
to prevent, and it is worse for Java (no drift script covers it, since Go's
`clients/java-sdk` and this repo's `sdk/` are structurally different modules,
not a subtree split of the same source) than for TS/Laravel. Flagging it
here so whoever next touches Go's `clients/java-sdk` knows it needs the same
patch this repo's `sdk/` already has, or a decision to stop maintaining it.
