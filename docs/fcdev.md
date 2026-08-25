# `fcdev` — the developer monolith (Java)

`fcdev` runs every FlowCatalyst subsystem in one process against an embedded
PostgreSQL: no Docker, no compose, no separate migration step. It is the Java
reading of `flowcatalyst-go/cmd/fcdev` — same subcommands, same flags, same
environment variables, same ports, same on-disk layout — so a developer can
switch between the Go and the Java binary and keep one embedded cluster, one
signing key and one PID file.

Code: `fcdev/src/main/java/io/flowcatalyst/fcdev/**` (module
`io.flowcatalyst:flowcatalyst-fcdev`). It wraps the same
`io.flowcatalyst.server.Server` that `fc-server` runs, adding the embedded
database and the dev defaults.

---

## 1. Installing

### With JBang (recommended)

The repo root carries a `jbang-catalog.json` with one alias, `fcdev`, pointing
at `fcdev/fcdev.java` — a tiny JBang script that depends on the
`flowcatalyst-fcdev` jar and delegates to `io.flowcatalyst.fcdev.FcDev`.

```sh
# once: JBang itself (https://www.jbang.dev/download/)
curl -Ls https://sh.jbang.dev | bash -s - app setup

# install `fcdev` on your PATH from the catalog
jbang app install fcdev@<github-org>/flowcatalyst-javalin        # from GitHub (alias@org/repo)
#   or, from a checkout:
jbang catalog add --name flowcatalyst /path/to/flowcatalyst-javalin/jbang-catalog.json
jbang app install fcdev@flowcatalyst
#   or, with no catalog at all:
jbang app install --name fcdev /path/to/flowcatalyst-javalin/fcdev/fcdev.java

fcdev            # = fcdev start
```

JBang downloads Java 25 itself if needed (`//JAVA 25`), resolves
`io.flowcatalyst:flowcatalyst-fcdev` from Maven Central **or your local
`~/.m2`** (`//REPOS mavencentral,local`) and caches the launcher. While the
artifact is not published, build and install it first:

```sh
JAVA_HOME=$(mise where graalvm) mvn -q -pl fcdev -am -DskipTests install
jbang fcdev/fcdev.java version                   # runs straight from ~/.m2
```

**Upgrading.** `fcdev upgrade` (the Go self-updater) is not ported; with JBang
an upgrade is `jbang app install --force fcdev@<catalog>` (re-resolves the
artifact), and `jbang cache clear` if a SNAPSHOT got stuck.

### Without JBang: the executable jar

`mvn -pl fcdev package` shades everything — fc-server, its dependencies and
the PostgreSQL binaries for macOS (arm64 + amd64), Linux (amd64 + arm64) and
Windows (amd64) — into one jar (≈ 170 MB, the PostgreSQL archives are ≈ 110 MB of
it). That jar is the GitHub-Releases fallback:

```sh
java -jar fcdev/target/flowcatalyst-fcdev-0.0.1-SNAPSHOT.jar            # = start
java -jar fcdev/target/flowcatalyst-fcdev-0.0.1-SNAPSHOT.jar version
```

Java 25 is required (virtual threads, the server's language level).

---

## 2. First run

```
$ fcdev
=== FlowCatalyst Dev Monolith ===
subsystem configuration api_port=8080 embedded_db=true embedded_db_port=15432 …
Extracting Postgres...                      ← once: the bundled PG 18 archive is unpacked into the cache dir
… initdb completed …                        ← once: the cluster is initialised under the data dir
embedded postgres started port=15432 path=… version=PG18
postgres connected
migrations applied count=… schema_version=…
generated persistent JWT signing key at …/flowcatalyst/jwt-signing-key.pem
…
api server listening addr=:8080
```

There is **no download at run time**: the PostgreSQL server binaries for every
supported platform ship inside the jar (zonky `embedded-postgres-binaries-*`,
version pinned by `zonky-binaries.version` in the root POM, currently 18.4.0)
and the one matching the host is extracted on first start. Subsequent starts
reuse the extracted binaries and the existing cluster.

Then open http://localhost:8080 (the embedded Vue SPA) and sign in with the
bootstrap admin — `admin@flowcatalyst.local` / `DevPassword123!`.
`X-FC-Test-Principal` test headers are enabled
(`FC_AUTH_ALLOW_TEST_HEADERS=true`) so `/api/*` can be hit without a token.

Stop with Ctrl-C, or from another terminal with `fcdev stop`.

---

## 3. Where things live

```
<userDataDir>/flowcatalyst/                 persistent, per-user (never /tmp)
├── embedded-pg/                             FC_EMBEDDED_DB_PATH / --embedded-db-path
│   └── data/                                the PostgreSQL cluster (PG_VERSION, postgresql.conf, …)
│       (data.bak-pg<N>-<stamp>/             left by `fcdev db upgrade`)
├── jwt-signing-key.pem                      JWT signing key, 0600 (FC_JWT_SIGNING_KEY_PATH if you set it)
├── app-key                                  FLOWCATALYST_APP_KEY field-encryption key, 0600
└── fcdev.pid                                PID of the running `fcdev start` (FC_DEV_PID_FILE / --pid-file)

<userCacheDir>/flowcatalyst/embedded-pg/     re-creatable: the extracted PostgreSQL binaries
└── PG-<md5-of-archive>/{bin,lib,share}      (zonky unpacks the bundled .txz here once; safe to delete)
<userCacheDir>/flowcatalyst-dev/mcp-credentials.json    local MCP OAuth client (Go; not yet written by Java)
```

| | `userDataDir` | `userCacheDir` |
|---|---|---|
| macOS | `~/Library/Application Support` | `~/Library/Caches` |
| Linux | `$XDG_CONFIG_HOME`, else `~/.config` | `$XDG_CACHE_HOME`, else `~/.cache` |
| Windows | `%AppData%` | `%LocalAppData%` |
| any | `$XDG_DATA_HOME` wins when set | |

(These are Go's `os.UserConfigDir()` / `os.UserCacheDir()`, exactly as the Go
`fcdev` resolves them — `io.flowcatalyst.fcdev.DevPaths`.)

The JWT signing key and the app key are stored in the **parent** of the
embedded data dir, so `--embedded-db-path /x/pg` puts them in `/x/`.

---

## 4. Commands and flags

`fcdev` with no subcommand is `fcdev start`; the root command accepts the same
flags. Every default is seeded from the environment first (Go's
`envIntDefault` / `envStrDefault` / `envBoolDefault`: an unparseable value
falls back to the built-in default), and an explicit flag wins. Booleans are
`1/true/yes/on` and `0/false/no/off`; on the command line use Go's
`--router=false` form.

### `fcdev start` (= `fcdev`)

| flag | env | default |
|---|---|---|
| `--api-port` | `FC_API_PORT` | `8080` |
| `--metrics-port` | `FC_METRICS_PORT` | `9090` |
| `--embedded-db` | `FC_EMBEDDED_DB` | `true` |
| `--embedded-db-port` | `FC_EMBEDDED_DB_PORT` | `15432` (`0` = any free port) |
| `--embedded-db-path` | `FC_EMBEDDED_DB_PATH` | `<userDataDir>/flowcatalyst/embedded-pg` |
| `--embedded-db-reset` | — | `false` (wipe the data dir before starting) |
| `--database-url` | `FC_DATABASE_URL` | `""` → use the embedded Postgres; set = skip it |
| `--scheduler` | `FC_SCHEDULER_ENABLED` | `true` |
| `--scheduled-job` | `FC_SCHEDULED_JOB_ENABLED` | `true` |
| `--stream` | `FC_STREAM_PROCESSOR_ENABLED` | `true` |
| `--outbox` | `FC_OUTBOX_ENABLED` | `false` |
| `--router` | `FC_ROUTER_ENABLED` | `true` |
| `--mcp` | `FC_MCP_ENABLED` | `false` |
| `--pid-file` | `FC_DEV_PID_FILE` | `<userDataDir>/flowcatalyst/fcdev.pid` |

What `start` does, in order (`StartCommand`):

1. **banner** — the subsystem configuration line.
2. **PID file** — written (0600) so `fcdev stop` can find this instance; a
   failure is a warning, not fatal. Removed on exit only if it still holds our
   PID (a newer instance's file is never clobbered).
3. **embedded Postgres** — unless `--database-url`. `--embedded-db-reset`
   deletes the data dir first. A cluster of another PostgreSQL major is
   refused with *"embedded Postgres data dir is PG17 but this fcdev embeds
   PG18 … Run 'fcdev db upgrade' … or 'fcdev start --embedded-db-reset'"*.
   Otherwise the cluster in `<path>/data` is started (initialised on first
   run: superuser `postgres`/`postgres`, trust auth on localhost, database
   `flowcatalyst`) and the URL is
   `postgresql://postgres:postgres@localhost:<port>/flowcatalyst?sslmode=disable`.
   With `--embedded-db=false` and no URL, start fails: nothing to connect to.
4. **connect + migrate** — `Database.newPool` + `Migrator.migrate` (Flyway,
   same migrations as fc-server).
5. **dev defaults** — `FLOWCATALYST_BOOTSTRAP_ADMIN_EMAIL=admin@flowcatalyst.local`,
   `FLOWCATALYST_BOOTSTRAP_ADMIN_PASSWORD=DevPassword123!`,
   `FLOWCATALYST_BOOTSTRAP_ADMIN_NAME=Local Admin` unless already set (the
   names `seed.EnvBootstrap*` / `Seeder.ENV_BOOTSTRAP_*` read); a persistent
   JWT signing key (`FC_JWT_SIGNING_KEY_PATH` unset → generate/read
   `<parent>/jwt-signing-key.pem`); a persistent field-encryption key
   (`FLOWCATALYST_APP_KEY` unset → generate/read `<parent>/app-key`, a
   32-byte base64 key). Both non-fatal: on failure the server falls back to
   an ephemeral key with a warning.
6. **seed** — `io.flowcatalyst.platform.seed.Seeder`: platform application,
   roles + permissions, event types + schemas, default processes, the
   bootstrap admin (idempotent; a second start is a no-op).
7. **MCP credentials** — the local MCP OAuth client + credentials file
   (*stubbed, see §7*).
8. **server env** — the process environment plus: the DB URL, the two ports,
   `FC_PLATFORM_ENABLED=true`, `FC_AUTH_ALLOW_TEST_HEADERS=true`, the six
   subsystem toggles from the flags, `FC_DEFAULT_BROKER=postgres` unless set
   (`FC_DEFAULT_BROKER=none` turns the in-process queue off). Any other
   `FC_*` you export is honoured as in fc-server.
9. **run** — `new Server(env, new Server.Mode.Platform(pool), Frontend.embeddedOrNone(), registry).start()`:
   API on `--api-port`, `/health` `/ready` `/metrics` on `--metrics-port`,
   the SPA served for anything no API route claims. SIGINT/SIGTERM → stop the
   server (drains), close the pool, stop Postgres (`pg_ctl stop -m fast`),
   remove the PID file.

Java cannot mutate its own environment, so where Go calls `os.Setenv` fcdev
builds a map and hands it to `Env.load(Map)` — `DevEnv`.

### `fcdev stop`

| flag | env | default |
|---|---|---|
| `--pid-file` | `FC_DEV_PID_FILE` | `<userDataDir>/flowcatalyst/fcdev.pid` |
| `--timeout` | — | `20s` (Go duration syntax) |

Reads the PID file, sends SIGTERM (`ProcessHandle.destroy()`), polls every
150 ms up to `--timeout`, then SIGKILL (`destroyForcibly()`) and waits 5 s
more. No PID file / dead PID is not an error (a stale file is removed).

### `fcdev fresh --yes`

| flag | env | default |
|---|---|---|
| `--database-url` | `FC_DATABASE_URL` | `""` → boots the embedded Postgres itself |
| `--embedded-db-port` | `FC_EMBEDDED_DB_PORT` | `15432` |
| `--embedded-db-path` | `FC_EMBEDDED_DB_PATH` | `<userDataDir>/flowcatalyst/embedded-pg` |
| `--yes` | — | required |

Migrates (so a brand-new cluster has the tables), then one
`TRUNCATE TABLE <the 43 FlowCatalyst tables> RESTART IDENTITY CASCADE` — the
same explicit list as the Go `freshTables` (`FreshCommand.FRESH_TABLES`);
anything not listed belongs to a consumer app and is left alone; the Flyway
history is kept — then re-seeds with start's bootstrap defaults. Stop
`fcdev start` first when using the embedded database (the cluster is locked).

### `fcdev db upgrade`

| flag | env | default |
|---|---|---|
| `--embedded-db-port` | `FC_EMBEDDED_DB_PORT` | `15432` |
| `--embedded-db-path` | `FC_EMBEDDED_DB_PATH` | `<userDataDir>/flowcatalyst/embedded-pg` |
| `--no-backup` | — | `false` (delete instead of moving aside) |
| `--yes` | — | `false` (skip the `Proceed? [y/N]` prompt) |

A PostgreSQL major upgrade is not in-place and the bundled distribution ships
no `pg_upgrade`, so: the old cluster is moved to
`data.bak-pg<old>-<yyyyMMdd-HHmmss>` (or deleted), a fresh cluster on the
bundled major is initialised, migrations + seed re-run. No-op when there is no
cluster or it is already on the bundled major.

### `fcdev version` / `fcdev --version`

`fcdev 0.8.23` — the version comes from `fcdev/src/main/resources/VERSION`
(a copy of the Go `cmd/fcdev/VERSION`); a release build can append a VCS
revision with `-Dfcdev.vcs.revision=<sha>`.

### Logging

`Logging.init` from the server module: stderr, level `FC_LOG_LEVEL`
(`debug|info|warn|error`), format `FC_LOG_FORMAT` (`text|json`; text on an
interactive terminal by default). No `logback.xml`.

---

## 5. Tests

`mvn -q -pl fcdev test` — flag/env seeding, PID-file ownership, directory
resolution, the dev-env projection, and `StartIntegrationTest`, which boots
`start` programmatically (embedded PostgreSQL in a temp dir on a free port,
API/metrics on port 0, the SPA) and checks `/health`, `/ready`, `/`, the
migrated cluster and the state files. It self-skips when the bundled
PostgreSQL cannot start in the environment.

---

## 6. Differences from the Go binary worth knowing

- **Binaries are bundled, not downloaded.** Go's fcdev downloads the
  PostgreSQL archive on first run into `<cache>/flowcatalyst/embedded-pg/bin`;
  Java extracts it from the jar into `<cache>/flowcatalyst/embedded-pg/PG-<md5>`.
  Same cache dir, different layout; both are safe to delete.
- `--embedded-db-port 0` picks a free port (handy for tests); Go has no
  equivalent.
- Not-yet-ported subcommands exit **2** with `fcdev <name>: not yet ported`;
  their flags are accepted (so scripts fail loudly rather than mysteriously).

## 7. Not yet ported

| subcommand | status |
|---|---|
| `fcdev init` | stub — flags accepted, exits 2 |
| `fcdev mcp` | stub |
| `fcdev outbox`, `fcdev outbox create-table` | stub |
| `fcdev upgrade` | stub — use `jbang app install --force …` |
| MCP credential bootstrap (step 7) | `DevBootstrap.bootstrapMcpCredentials` logs and skips |

Everything else — the server-side subsystems the toggles enable (router,
stream, schedulers, outbox, MCP) — is whatever `flowcatalyst-server` has
ported; `Server.start()` logs `… not yet ported; toggle ignored` for the rest.
