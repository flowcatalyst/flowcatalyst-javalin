# Spec — the developer surface: fcdev hosts functions, the `fn` CLI, the sample, the pipeline (package E)

Design: `docs/function-runner-plan.md` §8, §10.15. Workplan §2 E. Builds on everything before it:
`function-api.md` (B), `function-invocation.md` (I), `function-context.md` (D4), `function-host-*.md` (D).

## 0. Departures from the workplan

| Workplan | Here | Why |
|---|---|---|
| `fcdev fn publish` shells out to `oras` and `cosign` | it does **not**; it takes `--artifact-ref` and `--bundle` produced by earlier pipeline steps, and the workflow example calls `oras`/`cosign` itself | a CLI that drives two external tools has three failure surfaces and cannot be tested here; a pipeline step per tool is how Actions workflows are written anyway. Locally nothing is pushed or signed at all |
| `fcdev fn watch` "hot-loads with signature checks off" as a special host mode | `watch` is publish → wait ready → promote against the local platform, on change | one code path: what works under `watch` is what production does, including manifest validation, wiring and settings checks |
| `@AsFunction` in the Java SDK | **not built** | it predates R10/R11: a function's wiring is its manifest, versioned with its artifact. An annotation scanned from a *different* application would be a second, competing declaration. Revisit only if someone needs it |
| sample under `usecase/examples/` | `examples/function-hello`, a reactor module in an `examples` profile (off by default) | it must resolve `function-api` from the reactor (nothing is published to a repository yet) without slowing every build |

## 1. fcdev hosts functions (slice E1)

`fcdev start` runs a function host beside the platform, **on by default** (`--no-functions` /
`FC_DEV_FUNCTIONS=false` turns it off). Port `--fn-port` / `FC_FN_PORT`, default **8090** (8080 is
the platform's); observability on `FC_FN_METRICS_PORT` default 9091.

- **Identity**: `FunctionDevBootstrap` (the `RouterClientBootstrap` pattern, idempotent, fresh secrets
  every boot, no events): OAuth client `fcdev-fn-host` (service principal, anchor, role
  `platform:function-host`) and `fcdev-fn-cli` (anchor, roles `platform:function-publisher` +
  `platform:messaging-admin`). The CLI credentials are written to `DevPaths`' state dir as
  `fn-cli.json` (owner-only file, `OwnerOnlyFile`) so `fcdev fn …` needs no flags locally.
- **Platform env fcdev sets for itself**: `FC_FN_SIGNATURES=off` (dev mode is already on),
  `FC_FN_POOL_URL=http://127.0.0.1:<fn port>`. Domain verification off is package F.
- **`FnHostLauncher`**, sealed result `InProcess | ChildProcess | Disabled(reason)`:
  - **JVM fcdev** ⇒ `InProcess`: builds `HostEnv` from the dev settings and starts `FnHost` in the
    same JVM, its own Vert.x instance and ports. fcdev's class path holds the whole server, so the
    filtering parent loader is exercised exactly as production depends on it — pinned by a test that
    a function published to a running dev instance **cannot** load `io.flowcatalyst.server.Platform`.
  - **Native fcdev** (`org.graalvm.nativeimage.imagecode` set — the check `UpgradeCommand` uses) ⇒
    `ChildProcess`: `java -jar <host jar>` with the same env, stdout/stderr relayed with a `[fn-host]`
    prefix, stopped with fcdev (destroy, then `destroyForcibly` after 10 s; also on fcdev's own
    shutdown hook). `java` = `$JAVA_HOME/bin/java`, else `java` on `PATH`. Host jar = `--fn-host-jar`
    / `FC_FN_HOST_JAR`, else `fc-fnhost.jar` beside the fcdev binary. **Missing JDK or jar does not
    fail `fcdev start`**: `Disabled`, one WARN naming exactly what was looked for and both remedies —
    a developer not writing functions must not need a JDK.
  - The branch is chosen by an injectable "is native" predicate so both are testable on the JVM.
- `fcdev stop`, the PID file and `fcdev start`'s ready banner include the function host (banner line:
  `functions  http://127.0.0.1:8090/functions/…`).
- `release-fcdev.yml` additionally attaches `fc-fnhost.jar` (the exec jar) to the release, and
  `fcdev upgrade` fetches it beside the binary — **describe in the spec, implement the workflow
  edit, do not run a release**.

## 2. `fcdev fn` (slice E2)

Global options: `--platform-url` (default the local fcdev's), `--client-id`/`--client-secret` (env
`FLOWCATALYST_CLIENT_ID`/`_SECRET`; default: `fn-cli.json`), `--output text|json`. Exit codes: 0 ok,
1 platform/validation error (the platform's `code` and `message` printed, never a stack trace), 2
usage. Addresses: a full `app.service.name`, or `--app` `--service` (default `default`) `--name`;
**a two-part address is a usage error** (design §8).

| Command | Does |
|---|---|
| `fn publish <jar> --manifest <file>` | sha256 of the jar; local: copies it to `<state>/fn-artifacts/<hex>.jar` and uses that `file://` ref (the build dir's jar is about to be overwritten); remote: `--artifact-ref oci://…` (+ `--bundle <sigstore.json>`). Creates the function when absent (runtime from the manifest, `--client <id>` for a client-owned one, else platform-owned) — `--no-create` to forbid. Prints address, version, digest |
| `fn promote <address> --version <n> [--wait 60s]` | waits (polling status) for `READY`, then promotes; on timeout prints each host's state/error for that version and exits 1 |
| `fn deploy <jar> --manifest <file> [--wait 60s]` | publish + promote — what `watch` runs |
| `fn status <address\|pattern>` | versions, live, hosts, wiring, missing settings |
| `fn versions <address>`, `fn retire <address> --version <n>` | |
| `fn config get\|set <address> [KEY=VALUE…]`, `fn secret set <address> <KEY>` (value from stdin or `--from-file`, **never an argument** — shell history), `fn secret list\|delete` | `set` on config is read-modify-write of the full map |
| `fn invoke <address>[:<version>] [--path /x] [--method POST] [--body <file>\|-] [-H k:v…] [--host-url]` | calls the **host** (`/functions/…`), default `http://127.0.0.1:8090`; a versioned call gets the CLI's bearer token automatically; prints status, headers, body. `--webhook` signs the request as the platform would (needs `--signing-secret` or, locally, reads the application's from the platform) so a `webhook` endpoint can be exercised without emitting an event |
| `fn watch <dir> [--jar <glob>] [--manifest manifest.json]` | debounced (500 ms) file watch; on change runs `deploy`; prints one line per cycle (`v7 live in 1.8 s` / the platform's error); never exits on a failed cycle |

An HTTP client class (`FnClient`) holds every platform call; commands are thin. Token: client
credentials, cached for the process.

## 3. The sample and the pipeline (slice E3)

`examples/function-hello` (reactor module, profile `examples`): one function with a `webhook`
endpoint + subscription, a `platform` endpoint, config + secret use, an emitted event; `manifest.json`;
build = compile (release 21) → `maven-shade-plugin` (function-api `provided`, never bundled — the
host refuses `BUNDLES_API`) → **ProGuard shrink-only** (`-dontobfuscate -dontoptimize`, keep rules
per design §8, keep file beside the manifest) → **the tests run against the shrunk jar**: an
integration test loads `target/*-shrunk.jar` through the real `JvmFunctionLoader` (test-scope
dependency on `function-host`) and invokes each endpoint — a missing keep rule fails there, not in
production. `proguard-maven-plugin` is a build-time plugin of the sample only.

`examples/function-hello/.github/workflows/publish.yml` (a template, not wired into this repo's CI):
build → shrink → test → `oras push` → `cosign sign-blob --new-bundle-format --bundle …` with a
**pinned cosign version** and a comment block: the platform verifies Rekor **v1** bundles with a
signed entry timestamp (`function-artifacts.md` §4); if a newer cosign stops producing them, publish
fails with `UNSUPPORTED_BUNDLE` — pin, and read that section before bumping → `fcdev fn publish
--artifact-ref … --bundle …` → `fcdev fn promote --wait`. The signer subject the policy must hold is
printed by a step (`…/publish.yml@refs/heads/main`), because matching is exact (ruling).

`.github/workflows/fnhost-image.yml` in THIS repo: weekly + manual rebuild of the function-host image
(JDK/Alpine patches — the one thing a managed Lambda runtime would do for us), runs `make
fnhost-smoke` first. Registry/push left as commented placeholders with the org's conventions unknown.

`docs/functions.md`: the developer guide — write a function, manifest reference (generated from the
spec tables, not re-invented), local loop (`fcdev start`, `fn watch`), settings, events, the three
auth modes, versioned smoke test, pipeline, and the honest limits (`Result.fail` cannot stop
retries; schedules ignore `retry`; JVM functions share a process; metaspace sizing rule).

## 4. Load-bearing behaviours (one mutant per condition; absence as well as presence)

| # | Behaviour | Mutant |
|---|---|---|
| E1 | `fcdev start` (JVM): platform + host up; `fn-cli.json` works; publish→ready→promote→invoke of a fixture function end to end in one test; the function cannot load a server class | start without `FC_FN_SIGNATURES=off` (publish 400); wrong pool URL (subscription target unreachable — assert the stored endpoint) |
| E2 | native branch: child process started with the right env and stopped with fcdev (a fake `java` script recording its args/env and trapping TERM); no JDK ⇒ `Disabled` + WARN naming both remedies and `start` still succeeds; no jar ⇒ same | fail start; leave the child running |
| E3 | `--no-functions` starts no host, creates no clients, prints no banner line | — |
| E4 | `fn publish` stores the copy, not the build path; re-publishing the same jar is the platform's `VERSION_DIGEST_EXISTS`, exit 1, message printed; two-part address ⇒ exit 2 | use the build path; swallow the error |
| E5 | `fn promote --wait`: waits for `READY`; times out with the hosts' errors | promote immediately |
| E6 | `fn secret set` never takes the value as an argument and never echoes it (capture stdout/stderr) | print the value |
| E7 | `fn invoke --webhook` produces a signature the host accepts; without it a `webhook` endpoint is 401 | sign the wrong bytes |
| E8 | `fn watch`: two writes within the debounce ⇒ one deploy; a failing deploy is printed and the watch continues to the next change | no debounce; exit on failure |
| E9 | sample: the shrunk jar loads and every endpoint works through the real loader; deleting a keep rule makes that test fail (do it) | — |
