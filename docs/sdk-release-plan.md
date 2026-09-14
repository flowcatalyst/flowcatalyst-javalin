# SDK release pipeline for this repo

Status: plan, 2026-09-14 (owner asked: "split out the SDKs and send them to
the SDK repos"). Decisions in §4; nothing built until they are made.

## 1. What exists today

| SDK | Source | Version | Standalone repo | Pipeline |
|---|---|---|---|---|
| TypeScript | Go repo `clients/typescript-sdk` | 0.11.19 | `flowcatalyst/typescript-sdk` (source + committed `dist/`, tags `vX.Y.Z`; consumers pin the git tag) | Go `split-typescript-sdk.yml`: on tag `typescript-sdk/v*`, `splitsh-lite --prefix=clients/typescript-sdk`, force-push `main`, build `dist/` in the target, commit, re-tag `vX.Y.Z` |
| Laravel | Go repo `clients/laravel-sdk` | 0.10.17 | `flowcatalyst/laravel-sdk` (pure source mirror; Packagist reads the tag) | Go `split-laravel-sdk.yml`: same split, no build |
| Java | **two copies**: Go repo `clients/java-sdk` (own pom, Jackson 2, 0.0.4, tagged `java-sdk/v0.0.4`) and this repo's `sdk/` reactor module (Jackson 3, depends on the sibling `usecase` module, `VERSION` 0.0.0, 50 tests) | — | none anywhere; `flowcatalyst/flowcatalyst-java` is an unrelated 2026-03 project, not an SDK repo |

All three SDKs generate from one document: each SDK's `openapi/openapi.json`
is byte-identical to `api/openapi.lock.json`, which this repo carries as
`server/src/main/resources/openapi/openapi.lock.json`. Releases are cut by
`scripts/release.sh <kind> <bump>` (bump base = max(VERSION file, highest
tag); commits the bump, tags `<sdk>/vX.Y.Z`, pushes; the workflow does the
rest). Go's own workflow warns that two source repos force-pushing the same
standalone `main` will fight — so whichever repo holds an SDK's source must
be the only one with its split workflow enabled.

## 2. Proposed shape here

1. **TypeScript and Laravel move into this repo** under `clients/`, with
   history, exactly as the frontend did (subtree split in a scratch clone;
   the Go tree untouched). Their `VERSION` files come with them so numbering
   continues (0.11.19 → next is 0.11.20). Go's two split workflows are then
   retired in the Go repo (hand-off note), or they will fight this repo's.
2. **One spec source.** `make sdk-spec` here copies the lockfile into each
   SDK's `openapi/openapi.json` (no dump step: the lockfile IS the document);
   `make sdk-generate` runs the TS and Laravel generators; a CI check fails
   when a generated tree is stale, the way `frontend-types-verify` does.
3. **Split workflows here**, ported from Go's with the prefixes changed:
   `split-typescript-sdk.yml` (`clients/typescript-sdk` →
   `flowcatalyst/typescript-sdk`, builds `dist/`), `split-laravel-sdk.yml`
   (`clients/laravel-sdk` → `flowcatalyst/laravel-sdk`). Secrets
   `TYPESCRIPT_SDK_TOKEN` / `LARAVEL_SDK_TOKEN` added to this repo's Actions
   secrets by the owner. `scripts/release.sh` ported (kinds `ts`, `laravel`,
   `java`; `dev` stays with `release-fcdev.yml`).
4. **Java SDK: publish an artifact, and mirror the source.** A source split
   of `sdk/` alone does not build, because it depends on `usecase`. So on
   tag `java-sdk/v*` the workflow (a) sets the reactor version from the tag
   and deploys `flowcatalyst-usecase` and `flowcatalyst-sdk` to **GitHub
   Packages** (`io.flowcatalyst`, the Maven repository of this GitHub org —
   Maven Central can follow when the org has a Sonatype namespace), and (b)
   optionally splits `sdk/` to a new `flowcatalyst/java-sdk` source repo for
   humans (README, issues), with its README pointing at the artifact. The
   Go repo's `clients/java-sdk` is retired — this module is the one that
   received today's invitation handover, so it is already the canonical
   one. Versioning restarts the tag stream here from 0.0.5 (the Go repo's
   last was 0.0.4) so `java-sdk/v*` stays monotonic across the move.
5. **fcdev tag ownership** (already flagged in `release-fcdev.yml`): unchanged
   by this plan; a separate decision.

## 3. What it costs

One Sonnet unit per SDK for the moves and workflows (the TS/Laravel
workflows are near-copies), one for the Java publish job (settings.xml
with the `GITHUB_TOKEN`, `distributionManagement` in the parent pom, a
version-from-tag step), plus the owner's actions: two secrets, one new repo
(if the Java source mirror is wanted), and the Go-side retirement of the
split workflows and `clients/`.

## 4. Decisions

1. Move TypeScript and Laravel into this repo now (recommended, mirrors the
   frontend ruling), or leave them in Go until cutover?
2. Java SDK delivery: GitHub Packages artifact (recommended), a source
   mirror repo, or both?
3. The Go repo retires `clients/` and the split workflows on the same day
   (required for 1; a hand-off note is written for the Go agent).
