# fcdev 0.9.0: released from this repo, with the function host fetched on first use

Owner, 2026-09-24: the Java fcdev is **0.9.0** and this repo owns the `fcdev/v*` release stream.

Today (verified): `fcdev/src/main/resources/VERSION` is `0.8.23` (Go is at 0.8.38);
`UpgradeCommand.DEFAULT_REPO = "flowcatalyst/flowcatalyst"` (the Go repo);
`.github/workflows/release-fcdev.yml` fires on `fcdev/v*`, builds native binaries on a
matrix, and its `fnhost-jar` job attaches `fc-fnhost.jar` (+ `.sha256`) to the release;
`fcdev upgrade` fetches that jar beside the binary (`UpgradeCommand#fetchFunctionHostJarIfPresent`,
checksum **optional**); `fcdev start` runs functions by default (`--no-functions` to turn off) —
in-process on the JVM fcdev, `java -jar fc-fnhost.jar` as a child from the native binary, which
finds the jar via `--fn-host-jar` / `FC_FN_HOST_JAR` / beside the binary and otherwise logs one
warning (`FnHostLauncher` `Disabled`) and starts without functions. `scripts/release.sh` has no
fcdev kind.

## 1. Version and repository

- `fcdev/src/main/resources/VERSION` → `0.9.0` (above Go's 0.8.38, so `fcdev upgrade` sees it as
  newer).
- `UpgradeCommand.DEFAULT_REPO` → `flowcatalyst/flowcatalyst-javalin` (this repo's `origin`).
  `FC_DEV_UPGRADE_REPO` still overrides.

## 2. Releasing it

- `scripts/release.sh` gains kind `dev`: prefix `fcdev`, version file
  `fcdev/src/main/resources/VERSION`, no manifest. Same bump rules, commit, tag `fcdev/vX.Y.Z`,
  push. Remove the header note explaining why there is no `dev` kind.
- `Makefile`: `release-fcdev: ## Cut an fcdev release: BUMP=… (tags fcdev/vX.Y.Z)` →
  `scripts/release.sh dev "$(BUMP)"`, listed beside the SDK targets.
- `release-fcdev.yml`: the workflow must take its version from the tag **and** check it equals
  `VERSION` (fail the release if they differ — a binary reporting a different version than its
  release breaks `fcdev upgrade`'s comparison). Replace CALL-OUT 1 with the resolved rule (this
  repo owns `fcdev/v*`; the Go repo's `release-fcdev.yml` must be disabled — an owner action) and
  delete CALL-OUT 2 (the repo has a remote). Keep everything else.

## 3. The function host on first use

When `fcdev start` runs with functions on, on the **native** binary, and no host jar resolves
(the three existing places), fcdev fetches it instead of disabling functions:

1. From the release **of its own version** (`fcdev/v<Version.current()>`) of the upgrade repo,
   asset `fc-fnhost.jar` and `fc-fnhost.jar.sha256`, through the same GitHub-API code
   `UpgradeCommand` uses (reuse it; do not add a second HTTP client or release parser).
2. The checksum is **required**: no `.sha256` asset, or a mismatch, is a failed fetch.
3. Stored beside the binary when that directory is writable, else at
   `<fcdev data dir>/fnhost/<version>/fc-fnhost.jar` (the data dir fcdev already uses —
   `XDG_DATA_HOME`-based; find it). The jar resolution order gains this cached path after the
   three existing ones, so the next start finds it without a network call.
4. One log line while downloading (`fetching the function host (fc-fnhost.jar, <version>)…`).
5. Any failure (offline, no such release, checksum) falls back to today's `Disabled` warning,
   with the reason and the remedies (`--fn-host-jar`, `fcdev upgrade`, `--no-functions`);
   `fcdev start` still succeeds.
6. The JVM fcdev never fetches (it runs the host in-process).

Also: `fetchFunctionHostJarIfPresent` (upgrade) makes the checksum required the same way.

## Tests (mutant each)

Against the fake GitHub API the `UpgradeCommand` tests already use:
1. Native, no local jar, release has jar + valid sha256 → the jar is written to the cache path,
   its bytes equal the served asset, and the launcher is given that path. Mutant: skip the fetch
   → `Disabled`.
2. Missing `.sha256` → nothing written, `Disabled` with the checksum reason (mutant: treat a
   missing checksum as ok). Mismatched checksum → nothing written (mutant: skip the compare).
3. A cached jar present → no HTTP request at all (count requests; mutant: always fetch).
4. The release asked for is `fcdev/v<Version.current()>` (assert the requested path; mutant:
   ask for `latest`).
5. JVM mode never fetches (request count 0).
6. `release.sh dev patch` computes `0.9.1` from `0.9.0` and tags `fcdev/v0.9.1` — test it the way
   the script's other kinds are tested, if they are; otherwise a dry-run check of the computed
   version is enough (say which).
7. `VERSION` reads `0.9.0` and `DEFAULT_REPO` is this repo (a plain assertion is fine here).

## Docs

`function-developer-surface.md` §1 (first-use fetch, checksum required, cache path, JDK still
required for the native binary), `docs/fcdev.md` if it describes install/upgrade, backlog/STATUS
(owner actions: disable Go's `release-fcdev.yml`; users on the Go fcdev reinstall or point
`FC_DEV_UPGRADE_REPO` — their `fcdev upgrade` still looks at the Go repo).
