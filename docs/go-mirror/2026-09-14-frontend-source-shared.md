# Go hand-off — the SPA source moved into the Java repo (2026-09-14)

For the Go agent. Context: until now the Java repo embedded a *built* copy
of the Vue SPA (`server/src/main/resources/frontend`, refreshed
out-of-tree from `../flowcatalyst-go/frontend`'s source by
`tools/sync-frontend.sh`). The Vue SPA source itself has now been imported
into the Java repo, with its Go history, as `frontend/` (git subtree) —
`tools/sync-frontend.sh` is gone, replaced by `tools/build-frontend.sh`
(`make frontend`), which builds the Java repo's own `frontend/` out of
tree and syncs the result into `server/src/main/resources/frontend`.

## Both repos own the source for now

The Go repo keeps its own `frontend/` for a while — this is not a cutover.
Rule: the two `frontend/` trees should stay as close to byte-identical as
possible; every Java-specific piece of wiring (Makefile target, `tools/`
scripts, docs) lives outside the folder, not inside it. The two exceptions
inside the folder itself:

- `frontend/embed.go` and `frontend/handler.go` are gone from the Java
  copy — they are Go's embed glue, and Java's equivalent
  (`server/src/main/java/io/flowcatalyst/server/Frontend.java`) already
  exists outside the folder.
- `frontend/openapi-ts.config.ts` and `frontend/scripts/watch-api.ts`
  changed — see below. Both are safe for Go to take verbatim.

## `tools/frontend-drift.sh`

Diffs `frontend/` here against `${1:-../flowcatalyst-go}/frontend`
(`diff -rq`, excluding `node_modules`, `dist`, `embed.go`, `handler.go`,
`*.tsbuildinfo`, `components.d.ts`, `vite.config.d.ts`, `.vite`), prints
the differing paths, and exits 1 if any differ (0 if identical). Run it
from the Java repo, pointing at a Go checkout, whenever the two trees
might have drifted. `tools/pull-frontend-from-go.sh` does the reverse-sync
(Go → Java) with the same excludes for when the Go copy moves first; its
header documents the Java → Go direction as the same rsync with the paths
swapped, to be run from the Go repo by its owner.

## Two files the Go copy should take verbatim

- **`frontend/openapi-ts.config.ts`** — previously hardcoded
  `../api/openapi.lock.json` (Go's lockfile path). It now picks whichever
  of `../api/openapi.lock.json` (Go) or
  `../server/src/main/resources/openapi/openapi.lock.json` (Java, a
  byte-for-byte copy of the same file) exists at run time, so one file
  works unchanged in both repos. `OPENAPI_LIVE=true` still points at a
  running server, as before.
- **`frontend/scripts/watch-api.ts`** — previously watched
  `../../core/flowcatalyst-platform/src/main/java`, a path that exists in
  neither repo (stale). It now watches `../server/src/main/java` (the
  Java repo's own source), which exists here. The script only ever reacts
  to `.java` filenames, so it is inherently Java-backend-specific — there
  is no Go-side fallback for it to offer.

## What the Go repo may do later

The e2e runner (`e2e/runner/build.ts`) already builds Go's `fcdev` in a
scratch copy of the Go tree whose `frontend/dist` is swapped for the Java
side's embedded copy (`server/src/main/resources/frontend`), so both
binaries serve byte-identical SPAs for the frontend e2e suite. The Go repo
could adopt the same idea for its own build: embed this repo's
`server/src/main/resources/frontend` (built by `make frontend`, source
commit stamped in `server/src/main/resources/frontend.source-commit`) as
its own `frontend/dist`, rather than building its own `frontend/src` a
second time — once the owner decides which repo's `frontend/` is
authoritative. Not done as part of this unit.
