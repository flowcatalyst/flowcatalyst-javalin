# Spec — `fcdev`, the developer binary (`io.flowcatalyst.fcdev`)

Behavioural contract for the developer monolith, written from what a
developer observes at the command line and on disk (`docs/fcdev.md`, the
`--help` output, the CLI tests and `StartIntegrationTest`) — not from the Go
source. The Go binary and this one are meant to be interchangeable on the
same machine: same flags, same environment variables, same ports, same
directories, same files — so the observable surface *is* the contract.

Items tagged **[owner?]** are "load-bearing or accident?" questions for the
owner; until ruled on, the behaviour is kept as-is.

## 1. Invocation model

- One executable, `fcdev`, delivered three ways: `jbang` alias (`fcdev` in
  `jbang-catalog.json` → `fcdev/fcdev.java` → `io.flowcatalyst.fcdev.FcDev`),
  the shaded jar (`java -jar flowcatalyst-fcdev-<v>.jar …`), or a plain
  `main` call. All three are the same `FcDev.main(String[])`.
- Requires Java 25. No preview features.
- `fcdev` with **no subcommand is `fcdev start`**; the root command accepts
  the full `start` flag set, so `fcdev --api-port 9000` ≡
  `fcdev start --api-port 9000`.
- Subcommands: `start`, `stop`, `init`†, `fresh`, `mcp`†, `outbox`†
  (+ `outbox create-table`†), `db` (+ `db upgrade`), `upgrade`†, `version`,
  `help`. † = stubbed (§9). Every subcommand accepts `-h/--help`; the
  subcommands additionally accept picocli's `-V/--version`; the root command
  accepts `-h/--help` and `-v/--version` (lower-case `v`, Go parity).
- Logging is initialised **before** parsing, from the process environment:
  stderr, level `FC_LOG_LEVEL` (`debug|info|warn|error`, default `info`),
  format `FC_LOG_FORMAT` (`text|json`; default text on a TTY, JSON otherwise).
  Everything the binary *logs* (banner, progress, errors) is SLF4J on stderr;
  what it *prints* (version, stop/upgrade messages, prompts, help) is on stdout.

### Flag / environment seeding rule

Every flag default is seeded from the environment first, then an explicit
flag wins. Seeding semantics (identical to the server's `EnvReader`):

| kind | rule |
|---|---|
| string | value, or the built-in default when unset **or empty** |
| int | value when set and it parses as a signed base-10 int, else the default (silently) |
| bool | `1/true/yes/on` → true, `0/false/no/off` → false (case-insensitive, trimmed); anything else — including unset — → the default |

Boolean flags take a value in Go's `--flag=false` form (`arity 0..1`, bare
`--flag` = true). Unset and empty environment variables are the same thing
everywhere.

### Exit codes

| code | when |
|---|---|
| 0 | the command completed (including the "nothing to do" outcomes of `stop` and `db upgrade`) |
| 1 | any runtime failure: the exception message is **logged** (`fcdev exited with error err=…`, stack trace only at `debug`), no usage text |
| 2 | usage error (unknown option, unparseable value — picocli prints the problem + usage to stderr); **and** every stubbed subcommand (§9) |
| 130 / 143 | Ctrl-C / SIGTERM during `start` — the JVM's own signal exit after the shutdown hook ran (§3.3) |

**[owner?]** Stubs share exit code 2 with usage errors ("a script notices").
Keep, or give them a distinct code (e.g. 3) so scripts can tell "typo" from
"not ported"?

## 2. Where things live

| | `userDataDir` | `userCacheDir` |
|---|---|---|
| macOS | `~/Library/Application Support` | `~/Library/Caches` |
| Linux / other | `$XDG_CONFIG_HOME`, else `~/.config` | `$XDG_CACHE_HOME`, else `~/.cache` |
| Windows | `%AppData%` | `%LocalAppData%` |
| any OS | `$XDG_DATA_HOME` wins when set | — |
| fallbacks | no resolvable dir → `~/.local/share`; no home → `.` | no resolvable dir → `~/.cache`; no home → `./.flowcatalyst-cache` |

(`~` is the JVM's `user.home`; the OS is `os.name`.)

```
<userDataDir>/flowcatalyst/                  persistent, per-user
├── embedded-pg/                              --embedded-db-path / FC_EMBEDDED_DB_PATH  (= <path>)
│   ├── data/                                 the PostgreSQL cluster; <path>/data/PG_VERSION holds the major
│   └── data.bak-pg<N>-<yyyyMMdd-HHmmss>/     left behind by `fcdev db upgrade`
├── jwt-signing-key.pem                       RSA PEM, 0600 — in the PARENT of <path>
├── app-key                                   32 random bytes, base64 (std alphabet, padded), 0600 — parent of <path>
└── fcdev.pid                                 "<pid>\n", 0600 — --pid-file / FC_DEV_PID_FILE

<userCacheDir>/flowcatalyst/embedded-pg/      re-creatable; safe to delete
└── PG-<md5-of-archive>/{bin,lib,share}       the bundled PostgreSQL binaries, extracted on first start
<userCacheDir>/flowcatalyst-dev/mcp-credentials.json   (reserved; not written yet — §9)
```

Rules:
- Key files (`jwt-signing-key.pem`, `app-key`) live in the **parent** of the
  embedded data path, so `--embedded-db-path /x/pg` puts them in `/x/`. With
  `--embedded-db-path /` (no parent) they land in `/` itself.
- 0600 is applied on POSIX file systems only; elsewhere the file is written
  with default permissions.
- The PG binaries go to the cache dir, never next to the data, so a data
  wipe (`--embedded-db-reset`, `fresh`, `db upgrade`) does not re-extract.

## 3. `fcdev start` (= `fcdev`)

### 3.1 Flags

| flag | env | default | notes |
|---|---|---|---|
| `--api-port <port>` | `FC_API_PORT` | `8080` | `0` = ephemeral (Java; see §8) |
| `--metrics-port <port>` | `FC_METRICS_PORT` | `9090` | `0` = ephemeral |
| `--embedded-db[=<bool>]` | `FC_EMBEDDED_DB` | `true` | |
| `--embedded-db-port <port>` | `FC_EMBEDDED_DB_PORT` | `15432` | `0` = any free port (Java addition) |
| `--embedded-db-path <dir>` | `FC_EMBEDDED_DB_PATH` | `<userDataDir>/flowcatalyst/embedded-pg` | |
| `--embedded-db-reset[=<bool>]` | — | `false` | wipe `<path>` (the whole dir, not just `data/`) before starting |
| `--database-url <url>` | `FC_DATABASE_URL` | `""` | set ⇒ the embedded PG is **not** started at all |
| `--scheduler[=<bool>]` | `FC_SCHEDULER_ENABLED` | `true` | |
| `--scheduled-job[=<bool>]` | `FC_SCHEDULED_JOB_ENABLED` | `true` | |
| `--stream[=<bool>]` | `FC_STREAM_PROCESSOR_ENABLED` | `true` | |
| `--outbox[=<bool>]` | `FC_OUTBOX_ENABLED` | `false` | |
| `--router[=<bool>]` | `FC_ROUTER_ENABLED` | `true` | |
| `--mcp[=<bool>]` | `FC_MCP_ENABLED` | `false` | |
| `--pid-file <file>` | `FC_DEV_PID_FILE` | `<userDataDir>/flowcatalyst/fcdev.pid` | |

### 3.2 Sequence

Each step either completes or fails the start (exit 1) — except where marked
*non-fatal*. On a failure after step 3, everything already started is torn
down in reverse (pool closed, PG stopped, PID file removed if ours) before
the error is reported.

| # | step | observable effect |
|---|---|---|
| 1 | banner | logs `=== FlowCatalyst Dev Monolith ===` and one `subsystem configuration api_port=… embedded_db=… embedded_db_port=… scheduler=… scheduled_job=… stream=… outbox=… router=… mcp=…` line (the effective values after seeding + flags) |
| 2 | PID file | writes `<pid>\n` (0600, parents created, a stale file is overwritten) at `--pid-file`. **Non-fatal**: on failure logs `could not write pid file — `fcdev stop` won't find this instance path=… err=…` and carries on |
| 3 | embedded PG | skipped entirely when `--database-url` is set. Otherwise: `--embedded-db=false` ⇒ fail with `no --database-url given and --embedded-db=false; nothing to connect to`. `--embedded-db-reset` ⇒ log a warning and delete `<path>` recursively first. **Major-version guard**: if `<path>/data/PG_VERSION` exists and differs from the bundled major, fail with exactly *"embedded Postgres data dir is PG`<have>` but this fcdev embeds PG`<want>`; a major upgrade is not in-place. Run 'fcdev db upgrade' (backs up the old cluster, re-initialises PG`<want>`, re-runs migrations + seed) or 'fcdev start --embedded-db-reset' to wipe it"*. Then start the cluster in `<path>/data` on `--embedded-db-port` (first run: `initdb`, superuser `postgres`/`postgres`, trust auth on localhost; the bundled binaries are extracted into the cache dir if not yet present), create database `flowcatalyst` if missing, and log `embedded postgres started port=… path=… version=PG<major>`. Start-up waits at most 60 s. The URL becomes `postgresql://postgres:postgres@localhost:<port>/flowcatalyst?sslmode=disable` |
| 4 | connect + migrate | HikariCP pool (size `max(4, #cpus)`), logs `postgres connected`; Flyway migrate (the server's migrations), logs `migrations applied count=… schema_version=…` |
| 5 | dev defaults | into the working environment map (never the real process env): `FLOWCATALYST_BOOTSTRAP_ADMIN_EMAIL=admin@flowcatalyst.local`, `…_PASSWORD=DevPassword123!`, `…_NAME=Local Admin` — each only when unset/empty. JWT key: when `FC_JWT_SIGNING_KEY_PATH` is unset/empty, ensure `<parent>/jwt-signing-key.pem` exists (generate an RSA PEM if missing or empty, 0600, logs `generated persistent JWT signing key at …`) and set the variable to its path — **non-fatal** (warn, server falls back to an ephemeral key). App key: when `FLOWCATALYST_APP_KEY` is unset/empty, ensure `<parent>/app-key` (generate 32 random bytes → base64 if missing/empty, 0600) and set the variable to its *content* — **non-fatal** (warn) |
| 6 | seed | the server `Seeder` against the pool with the working env (platform application, roles + permissions, event types, default process, the bootstrap admin); idempotent — a second start is a no-op. Logs `seed complete bootstrap_admin=<email>` |
| 7 | MCP credentials | **stubbed**: logs `MCP credential bootstrap not yet ported; skipping (would write <userCacheDir>/flowcatalyst-dev/mcp-credentials.json for http://localhost:<api-port>)` |
| 8 | server env | the working env plus, **always overriding**: `FC_DATABASE_URL=<url>`, `FC_API_PORT`, `FC_METRICS_PORT`, `FC_PLATFORM_ENABLED=true`, `FC_AUTH_ALLOW_TEST_HEADERS=true`, and the six toggles `FC_SCHEDULER_ENABLED`, `FC_SCHEDULED_JOB_ENABLED`, `FC_STREAM_PROCESSOR_ENABLED`, `FC_OUTBOX_ENABLED`, `FC_ROUTER_ENABLED`, `FC_MCP_ENABLED` from the flags; plus `FC_DEFAULT_BROKER=postgres` **only when unset/empty**. Every other `FC_*` / `FLOWCATALYST_*` the developer exported passes through untouched |
| 9 | run | the shared `Server` with the embedded SPA if the server jar carries one (else warn `frontend not embedded …; API only`): API on `--api-port`, `/health` `/ready` `/metrics` on `--metrics-port`. Logs `… listening addr=:<port>`. Blocks until a shutdown signal |

Observable state after a successful first start: the cluster under
`<path>/data` (PG_VERSION = bundled major), `flyway_schema_history` populated,
one `iam_principals` row for `admin@flowcatalyst.local`, the two key files,
the PID file holding this JVM's PID, the extracted binaries under the cache.
Sign-in: `admin@flowcatalyst.local` / `DevPassword123!`; `X-FC-Test-*`
headers accepted on `/api/*`.

### 3.3 Shutdown

SIGINT / SIGTERM (or `fcdev stop`) → a single shutdown hook runs, once, in
this order: (a) log `shutdown signal received`; (b) stop the server (Jetty
drains in-flight requests, up to 30 s); (c) close the pool; (d) stop the
embedded PG (`pg_ctl stop -m fast`), logging `stopping embedded postgres`;
(e) delete the PID file **only if it still contains our PID** — a newer
instance's file is never clobbered; a file we cannot read is left alone.
Each later step runs even when an earlier one fails. The main thread then
returns 0 (the JVM is already exiting on the signal).

PID-file ownership rules, in full: write overwrites anything (a crashed
instance must not block a fresh start); remove is conditional on content;
`stop` removes it after a successful stop only if it still names the PID it
stopped, and removes a **stale** file (PID not alive) unconditionally.

**[owner?]** Step 2 is a warning, not an error: an instance that cannot
write its PID file still starts, and `fcdev stop` then cannot find it.
Intended (never let a permissions hiccup block local dev) or accident?

**[owner?]** `--embedded-db-reset` deletes the whole `<path>`, not just
`<path>/data` — so any `data.bak-pg*` backups from `db upgrade` under it go
too. Intended?

**[owner?]** `--database-url` set + `--embedded-db=true` (the default)
silently skips the embedded PG. Keep (convenient), or warn that the flag is
ignored?

## 4. `fcdev stop`

| flag | env | default |
|---|---|---|
| `--pid-file <file>` | `FC_DEV_PID_FILE` | `<userDataDir>/flowcatalyst/fcdev.pid` |
| `--timeout <duration>` | — | `20s` — Go duration syntax: `300ms`, `1.5s`, `2m`, `1h30m`, units `ns us µs ms s m h`, optional sign, `0`; a bare number is a usage error ("missing unit?") |

Outcomes (all print to stdout, all exit 0 unless stated):

| situation | behaviour | message |
|---|---|---|
| no PID file | nothing to do | `No running fcdev instance found (no pid file at <path>).` |
| PID file malformed | **exit 1** | `malformed pid file <path>: …` (logged) |
| PID not alive | delete the file | `No running fcdev instance (pid <n> not alive); removed stale pid file.` |
| alive | print `Stopping fcdev (pid <n>)…`, send SIGTERM (`ProcessHandle.destroy()`), poll every **150 ms** up to `--timeout` | on exit: remove the PID file if it still names `<n>`, `Stopped fcdev (pid <n>).` |
| still alive after `--timeout` | print `fcdev did not exit within <timeout>; sending SIGKILL.` (timeout in Go format, e.g. `20s`, `1m30s`), SIGKILL (`destroyForcibly()`), wait **5 s** more | `Force-stopped fcdev (pid <n>).`; still alive after that → **exit 1** `pid <n> still running after SIGKILL` |
| signal not permitted | **exit 1** | `signal pid <n>: not permitted` / `force-kill pid <n>: not permitted` |

Liveness is `ProcessHandle.isAlive()`; a process owned by another user
counts as alive (as in Go's EPERM rule). Note `stop` kills whatever PID the
file names — it does not verify the process *is* an fcdev.

**[owner?]** The 150 ms poll and the fixed 5 s post-SIGKILL wait are not
flags. Load-bearing or arbitrary?

## 5. `fcdev fresh --yes`

| flag | env | default |
|---|---|---|
| `--database-url <url>` | `FC_DATABASE_URL` | `""` → boot the embedded PG itself (same major guard and URL as `start`) |
| `--embedded-db-port <port>` | `FC_EMBEDDED_DB_PORT` | `15432` |
| `--embedded-db-path <dir>` | `FC_EMBEDDED_DB_PATH` | `<userDataDir>/flowcatalyst/embedded-pg` |
| `--yes` | — | **required**; without it: exit 1, `refusing to truncate without --yes` |

Sequence: (embedded PG up, `embedded postgres started for fresh port=… path=…`)
→ migrate (so a brand-new cluster has the tables) → **one statement**
`TRUNCATE TABLE <list> RESTART IDENTITY CASCADE` → log
`FlowCatalyst tables truncated table_count=43` → re-seed with `start`'s
bootstrap defaults (the three `FLOWCATALYST_BOOTSTRAP_ADMIN_*` defaults, same
unset-only rule) → log `FlowCatalyst reseeded — sign in with the bootstrap
admin email=admin@flowcatalyst.local` → stop the embedded PG. Exit 0.

The list (43 names, this order; `oauth_idp_role_mappings` and `oauth_clients`
appear twice, which PostgreSQL accepts):

```
aud_logs, msg_events_read, msg_events, msg_dispatch_jobs, msg_dispatch_job_attempts,
msg_scheduled_job_instances, msg_subscription_event_types, msg_event_type_spec_versions,
msg_subscriptions, msg_event_types, msg_connections, msg_dispatch_pools, msg_scheduled_jobs,
oauth_oidc_payloads, oauth_oidc_login_states, oauth_client_grant_types,
oauth_client_allowed_origins, oauth_client_redirect_uris, oauth_client_application_ids,
oauth_clients, oauth_idp_role_mappings, oauth_identity_provider_allowed_domains,
oauth_identity_providers, webauthn_credentials, iam_login_attempts, iam_password_reset_tokens,
tnt_client_auth_configs, tnt_anchor_domains, iam_principal_application_access,
iam_client_access_grants, iam_principal_roles, iam_role_permissions, iam_principals, iam_roles,
oauth_idp_role_mappings, oauth_clients, tnt_cors_allowed_origins, iam_service_accounts,
app_client_configs, app_applications, tnt_clients, app_platform_config_access, app_platform_configs
```

Anything not listed (consumer-app tables, `flyway_schema_history`,
`goose_db_version`) is untouched. The embedded cluster is single-writer: run
`fcdev stop` first, or `fresh` fails to start PG.

**[owner?]** The list is explicit rather than "every table with a
FlowCatalyst prefix", and contains two duplicates. Load-bearing (consumer
apps share the DB) — keep; but are `msg_processes` and
`tnt_email_domain_mappings` (both seeded, both absent from the list) an
accident? After `fresh` they keep their rows while everything around them
is gone.

## 6. `fcdev db` / `fcdev db upgrade`

`fcdev db` alone prints its help (stdout) and exits 0.

| flag | env | default |
|---|---|---|
| `--embedded-db-port <port>` | `FC_EMBEDDED_DB_PORT` | `15432` |
| `--embedded-db-path <dir>` | `FC_EMBEDDED_DB_PATH` | `<userDataDir>/flowcatalyst/embedded-pg` |
| `--no-backup` | — | `false` |
| `--yes` | — | `false` |

| situation | behaviour |
|---|---|
| no `<path>/data/PG_VERSION` | log `no embedded cluster yet — nothing to upgrade; 'fcdev start' will initialise it target=PG<want>`, exit 0 |
| same major | log `embedded Postgres already on the target major — nothing to do version=PG<want>`, exit 0 |
| different major | print the plan on stdout: `Embedded Postgres upgrade: PG<have> → PG<want>` / `  data dir: <path>/data` / either `  the old cluster will be DELETED (--no-backup)` or `  the old cluster will be moved aside to a timestamped backup` / `  a fresh cluster is initialised; migrations + bootstrap seed re-run`. Unless `--yes`: prompt `Proceed? [y/N]: ` on stdout and read one line from stdin — `y`/`yes` (case-insensitive, trimmed) proceeds; anything else or EOF → exit 1 `aborted`. Then: `--no-backup` ⇒ delete `<path>/data`; else rename it to `<path>/data.bak-pg<have>-<yyyyMMdd-HHmmss>` (local time) and log `old cluster backed up path=…`. Then start a fresh cluster on the bundled major (pool size 4), migrate, seed with the bootstrap defaults, stop PG, log `embedded Postgres upgraded version=PG<want>`, print `Done — now on PG<want>. Sign in with the bootstrap admin (admin@flowcatalyst.local).`, exit 0 |

Key files are untouched (tokens survive the upgrade). The guard in `start`
guarantees no fcdev is running against the old cluster (a mismatched major
never gets that far).

**[owner?]** `db upgrade` re-seeds with the bootstrap defaults but does not
apply `start`'s JWT/app-key steps (not needed — the files already exist) nor
honour `FLOWCATALYST_BOOTSTRAP_ADMIN_*` overrides differently from `start`.
Consistent as far as observed; flagging only that `fresh`/`db upgrade`
seed through the same path as `start` and must keep doing so.

## 7. `fcdev version` / `fcdev --version` / `-v`

Prints `fcdev <version>[ (<rev>)]` to stdout, exit 0. `<version>` is the
trimmed content of the `VERSION` resource (currently `0.8.23`); `<rev>` is
the first 12 chars of the build's `-Dfcdev.vcs.revision`, omitted when the
build set none. The embedded PostgreSQL version (`18.4.0` → major `18`) is
stamped into the jar at build time and is what the §3 guard compares against;
a jar whose build stamping failed refuses to evaluate the guard
(`fcdev-build.properties was not filtered`).

## 8. Differences from the Go binary (visible ones)

| difference | status |
|---|---|
| PG binaries bundled in the jar (~110 MB of ~168 MB) and extracted to `<cache>/flowcatalyst/embedded-pg/PG-<md5>`; Go downloads on first run into `<cache>/flowcatalyst/embedded-pg/bin` | **[owner?]** keep the fat jar (offline-capable, one artifact), or switch the JBang path to resolve only the host's `embedded-postgres-binaries-<os>-<arch>` artifact at install time so the jar shrinks to ~50 MB and the GitHub-Releases jar stays fat? |
| `--embedded-db-port 0` / `--api-port 0` / `--metrics-port 0` bind a free port | **[owner?]** Java addition (tests rely on it). Keep? |
| `fcdev upgrade` self-update | not ported; JBang `app install --force` is the upgrade path |
| `-V` on subcommands (picocli standard) in addition to root `-v` | cosmetic |
| Java cannot `setenv`: the "environment" the server and seeder see is a copy of the process env plus fcdev's additions; child processes (PG) see the real env | by construction |

## 9. Still stubbed

| surface | behaviour today |
|---|---|
| `fcdev init` (all Go flags accepted) | stderr `fcdev init: not yet ported in the Java fcdev — use the Go fcdev, or sign in with the bootstrap admin fcdev start creates`, exit 2 |
| `fcdev mcp` | `fcdev mcp: not yet ported in the Java fcdev`, exit 2 |
| `fcdev outbox`, `fcdev outbox create-table` | same, exit 2 |
| `fcdev upgrade` | `… — with JBang: `jbang app install --force fcdev@<catalog>`; with the jar: download the latest release`, exit 2 |
| start step 7, MCP credential bootstrap | logged and skipped; `<userCacheDir>/flowcatalyst-dev/mcp-credentials.json` is never written |
| the subsystems the toggles enable | whatever `flowcatalyst-server` has ported; for the rest the server logs `<name> subsystem not yet ported; toggle ignored` |

## 10. Conformance

`fcdev/src/test/java/io/flowcatalyst/fcdev/*Test.java`: flag/env seeding
(`StartOptionsTest`), directory resolution per OS (`DevPathsTest`), PID-file
ownership (`PidFileTest`), durations (`DurationConverterTest`), key files /
table list / backup name / version guard (`DevBootstrapTest`), the command
surface incl. exit codes and stubs (`FcDevCliTest`), and a real boot
(`StartIntegrationTest`: embedded PG on a free port in a temp dir, API and
metrics on port 0, `/health`, `/ready`, the SPA, the migrated + seeded
cluster, the state files).
