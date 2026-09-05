# `fcdev init` / `mcp` / `outbox` / `upgrade` — the stubbed subcommands

Extracted 2026-09-05 by the orchestrator against Go HEAD from
`cmd/fcdev/{init,mcp,mcp_bootstrap,outbox,outbox_create_table,outbox_token,
upgrade,helpers}.go`. The Java stubs (`fcdev/.../NotPorted.java`) already
carry the exact flag surface of each command; this spec fixes behaviour.
`[C]` contract. Conventions: `docs/fcdev.md` §4 (flag/env precedence:
explicit flag > env > default; `--env-file` loads a dotenv without
overriding existing env), picocli `Callable<Integer>`, runtime failures log
one line and exit 1 (`FcDev.commandLine`'s exception handler), never a
usage dump.

## 0. What Phase 3 still gates (say so, do not fake it)

Two pieces need the OAuth-client aggregate and `/oauth/token`, which are
Phase 3 (`docs/auth-rulings.md`):

- `fcdev init` step 5 (mint a `client_credentials` OAuth client for the
  application's service account) and therefore the
  `FLOWCATALYST_CLIENT_ID` / `FLOWCATALYST_CLIENT_SECRET` lines of `.env`.
- `fcdev start`'s MCP credential bootstrap (`DevBootstrap.bootstrapMcpCredentials`
  already logs and skips; unchanged here).

Everything else below is implementable now. `init` runs to completion
without step 5, writes the `.env` keys it can, and prints one clearly
marked notice (§1.6). No placeholder secrets, ever — the serviceaccount
unit's `OAUTH_UNAVAILABLE` marker is the precedent.

## 1. `fcdev init` [C]

Bootstraps a local environment against `--database-url`
(`FC_DATABASE_URL`, default `postgresql://postgres:postgres@localhost:15432/flowcatalyst`
— the embedded Postgres a running `fcdev start` exposes; `init` does **not**
start the embedded database itself, as Go does not). Steps, in order, each
printing the same lines as Go (`fcdev init`, `→ …`, `  → …`):

1. **Migrate + seed**, idempotent: `Migrator.migrate(pool)` then
   `new Seeder(pool).run()`.
2. **Anchor admin.** `SELECT EXISTS (… iam_principals WHERE type='USER' AND
   scope='ANCHOR')` → "→ admin user already present, skipping creation".
   Otherwise prompt for email (`--admin-email` / `FC_BOOTSTRAP_ADMIN_EMAIL`)
   and password (`--admin-password` / `FC_BOOTSTRAP_ADMIN_PASSWORD`; read
   without masking, as Go) and create in one transaction: internal identity
   provider (`code='internal'`, reused if present), an ANCHOR email-domain
   mapping for the email's domain (reused if present), a USER/ANCHOR
   principal with `PasswordHash.hash(password)` and the
   `platform:super-admin` role (`assignedBy`/source `BOOTSTRAP`). Reuse the
   Seeder's bootstrap code path where it already does exactly this rather
   than writing a second one — the Seeder's `bootstrapAdminTx` is the
   reference; if its signature suits, call it with the prompted values.
   Note the Seeder already creates `admin@flowcatalyst.local` on `fcdev
   start`, so on a dev box this step usually reports "already present".
3. **Default client**: `--client-identifier` (default `default`) found by
   identifier and reused, else `Client.create(name, identifier)` persisted.
4. **Application**: `--code` / `--name` / `--app-type` (`APPLICATION` |
   `INTEGRATION`, case-insensitive; anything else → error "invalid
   application type …") / `--description` / `--default-base-url`; an
   existing code → error `application with code "x" already exists (id=…).
   Pick a different code or run fcdev fresh`, exit 1.
5. **Service account + SERVICE principal**, one transaction: service
   account `code = "app:" + appCode`, `name = appName + " Service Account"`,
   description `"Service account for application: " + appName`, linked to
   the application; a SERVICE principal for it, ANCHOR scope, `applicationId`
   and `clientId` (the default client) set; the application's
   `serviceAccountId` set to the **principal** id (Go's comment: the FK is
   to `iam_principals`). Then, in place of the OAuth client: print
   `  → OAuth client: deferred until the auth aggregate lands (docs/auth-rulings.md); FLOWCATALYST_CLIENT_ID/SECRET not written`.
6. **`.env`** at `--root/.env` (default `./.env`): merge
   `FLOWCATALYST_BASE_URL` (`--api-base-url`, default `http://localhost:8080`),
   `FLOWCATALYST_APP_CODE`, `FLOWCATALYST_APP_KEY` (from
   `FLOWCATALYST_APP_KEY` env, else generate via the platform's encryption
   key generator and use it for the run) — existing keys rewritten in place,
   new keys appended under `# FlowCatalyst (added by \`fcdev init\`)`, sorted;
   values containing whitespace or `#'"\`$` single-quoted with `'\''`
   escaping; file written `0600`; unchanged content → "already current, no
   update needed". Then the summary block as Go prints it, minus the OAuth
   client line, plus the deferral notice.

Prompts (`--yes` semantics as Go): a flag value wins; `--yes` with no flag
value and no default → error `--yes mode requires a flag value for: <question>`;
interactive otherwise, `[default]` suffix when there is one, empty input
takes the default or errors `<question> is required`. Optional fields never
error.

All writes go through the repositories directly (no `UnitOfWork`, no
domain events, no audit rows) — infrastructure bootstrap, the exception
`CONVENTIONS.md` §3 documents.

## 2. `fcdev mcp` [C]

Runs the MCP server from `io.flowcatalyst.mcp` **out of process from the
platform**: stdio transport by default (`StdioServerTransportProvider` from
the MCP SDK; JSON-RPC on stdin/stdout, **every log line to stderr** —
stdout is the protocol), or `--http <bind>` for the streamable-HTTP
listener via `McpServer.start` (`host:port` split on the last `:`).

Config: `McpConfig.resolve(...)` exactly as `Server` uses it (env, then the
`mcp-credentials.json` file `DevPaths.mcpCredentialsPath()` names, then
flag overrides `--platform-url` / `--client-id` / `--client-secret`). Auth
mode as `PlatformClient.AuthMode.resolve`: client credentials when id and
secret are present (the token manager against `<platform-url>/oauth/token`
— an external Go platform today), else the static bearer
`FC_MCP_PLATFORM_AUTH_TOKEN` / `FLOWCATALYST_AUTH_TOKEN`, else none with
one WARN on stderr ("starting MCP server without credentials") and
proceed, as Go. Ctrl-C / SIGTERM stops it; HTTP mode shuts down within 5 s.

## 3. `fcdev outbox` [C]

The standalone outbox poller: the **same** `OutboxProcessor` +
`HttpDispatcher` + `PostgresOutboxRepository` `Server` wires, pointed at an
external application database and an external platform.

- Resolution: `--env-file` (default `.env`, loaded first, never overriding
  set env), then per flag: explicit flag > env > default —
  `--source-db-url` (`FC_OUTBOX_SOURCE_DB_URL`, **required**, else error
  `--source-db-url (or FC_OUTBOX_SOURCE_DB_URL) is required`),
  `--target-url` (`FC_OUTBOX_PLATFORM_URL`, default `http://localhost:8080`),
  `--auth-token` (`FC_OUTBOX_PLATFORM_AUTH_TOKEN`), `--client-id`
  (`FC_OUTBOX_CLIENT_ID`, then `FLOWCATALYST_CLIENT_ID`), `--client-secret`
  (`FC_OUTBOX_CLIENT_SECRET`, then `FLOWCATALYST_CLIENT_SECRET`),
  `--token-url` (`FC_OUTBOX_TOKEN_URL`, default `<target-url>/oauth/token`),
  `--scope` (`FC_OUTBOX_SCOPE`), `--batch-size` / `--max-in-flight` /
  `--poll-interval-ms` (`FC_OUTBOX_*`, 0 = library default).
- `repository.initSchema()` before polling (idempotent).
- Auth: id + secret → a `HttpDispatcher.TokenSource` backed by
  `io.flowcatalyst.mcp.TokenManager` (already implements the cached
  client-credentials mint with the 60 s refresh buffer; add the optional
  `scope` form field to it, absent when blank, and expose `invalidate()`
  for the dispatcher's 401 hook). Else the static token. Else none. Log
  `fcdev outbox started source=… target=… auth=client_credentials|static-token|none`
  — the source URL **with the password masked**.
- Runs until SIGTERM/Ctrl-C, then `fcdev outbox stopped`.

### 3.1 `fcdev outbox create-table` [C]

- `--db-type` (`FC_OUTBOX_BACKEND`, then `FC_OUTBOX_DB_TYPE`; default
  `postgres`; aliases `pg|postgres|postgresql`, `mysql|mariadb|maria`,
  `mongo|mongodb`; unknown → error `unknown --db-type "x": want postgres,
  mysql, or mongodb`), `--db-url` (`FC_OUTBOX_SOURCE_DB_URL`, then
  `FC_OUTBOX_DB_URL`, then `FC_OUTBOX_MONGO_URI`; required), `--db-name`
  (`FC_OUTBOX_MONGO_DB`, default `flowcatalyst`).
- `postgres`: `PostgresOutboxRepository.initSchema()` on a pool for the URL;
  prints `Created outbox_messages table + indexes (postgres).`
- `mysql`: the MySQL DDL below, verbatim, through `mysql-connector-j`
  (already managed in the parent POM; add it to fcdev); a `mysql://user:pass@host[:port]/db?params`
  URL is converted to a JDBC URL (`jdbc:mysql://host:port/db?params`, port
  3306 when absent, credentials as connection properties — never in the
  log); anything without `://` is taken as an already-JDBC-shaped DSN and
  prefixed with `jdbc:mysql://` if it lacks the prefix. Prints
  `Created outbox_messages table + indexes (mysql).`
- `mongodb`: **not supported in the Java fcdev** — the Mongo outbox is on
  the backlog by ruling. Print `fcdev outbox create-table: mongodb is not
  supported in the Java fcdev (Mongo outbox backend is on the backlog)` on
  stderr, exit 2.

MySQL DDL (from Go `pkg/fcsdk/outboxsql/schema.go`, byte-for-byte):

```sql
CREATE TABLE IF NOT EXISTS outbox_messages (
    id VARCHAR(26) PRIMARY KEY,
    type VARCHAR(20) NOT NULL,
    message_group VARCHAR(255),
    payload LONGTEXT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    retry_count SMALLINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    error_message TEXT,
    client_id VARCHAR(26),
    payload_size BIGINT,
    headers JSON,
    INDEX idx_outbox_messages_pending (status, message_group, created_at),
    INDEX idx_outbox_messages_stuck (status, created_at),
    INDEX idx_outbox_client_pending (client_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## 4. `fcdev upgrade` [C]

Self-update from GitHub Releases, `--check` / `--force` as Go.

- Repo `FC_DEV_UPGRADE_REPO` (default `flowcatalyst/flowcatalyst`); tag
  prefix `fcdev/v`; `GET https://api.github.com/repos/<repo>/releases?per_page=100`
  with `User-Agent: fcdev-upgrade`, `Accept: application/vnd.github+json`;
  skip drafts, prereleases, other prefixes, non-`X.Y.Z` versions; highest
  wins; none → error `no fcdev/vX.Y.Z releases found for <repo>`.
- Output lines exactly: `current version: …`, `checking for updates…`,
  `latest version:  …`; `--check` → `update available: a → b (run \`fcdev
  upgrade\`)` or `fcdev is up to date.`; not newer and no `--force` →
  `fcdev is already up to date. Use --force to reinstall.`
- **Which artifact** (the Java divergence, decided here): the command
  detects how it is running. Native binary (no `java.home` class path jar —
  `ProtectionDomain.getCodeSource()` is the executable, or
  `System.getProperty("org.graalvm.nativeimage.imagecode")` is set) →
  asset `fcdev-v<ver>-<os>-<arch>.tar.gz` (`.zip` on Windows, binary
  `fcdev.exe`), `os` ∈ `darwin|linux|windows`, `arch` ∈ `amd64|arm64`
  (Java's `os.arch` `x86_64`→`amd64`, `aarch64`→`arm64`), inner path
  `<stem>/fcdev`, basename fallback for flat archives. Executable jar →
  asset `fcdev-v<ver>.jar`, replacing the jar at the code-source path. Any
  other launch (`mvn exec`, IDE, JBang-managed) → error `fcdev upgrade:
  not running from a release artifact; reinstall with the method you used
  to install`. Sidecar `<asset>.sha256` verified when published (`<hex>
  <name>` format, leading hex only), else the warning line as Go.
- Replace atomically: temp file in the destination's directory, `0755`,
  rename; Windows moves the live file to `.old` first and rolls back on
  failure. Permission failure → the Go message about elevated permissions.
- Success line `✓ upgraded fcdev a → b (<dest>)`.

## 5. Tests

- `InitCommandTest` (embedded Postgres): fresh database → admin, client,
  application, service account and principal rows as specified (assert the
  application's `serviceAccountId` is the **principal** id), `.env`
  created with exactly the three keys and `0600`; second run with the same
  code → the "already exists" error and exit 1, and the `.env` untouched;
  `--yes` without `--code` → the exact error. `EnvFileWriterTest`: in-place
  rewrite, append under the header, quoting rules, idempotent no-op.
- `OutboxCommandTest`: flag > env > dotenv > default for `--source-db-url`
  and `--client-id` (both env names); missing source → exact error, exit 1;
  a real run against the embedded Postgres and a stub platform
  (`HttpServer`) delivers a pending row and marks it (reuse
  `OutboxProcessorTest`'s shape); auth resolution → `client_credentials`
  when id+secret (the stub `/oauth/token` is hit and the bearer forwarded),
  `static-token`, `none`. `CreateTableCommandTest`: postgres creates the
  table (query `information_schema`), re-run is a no-op; `mysql://` URL →
  JDBC URL conversion asserted as a pure function; mongodb → exit 2 and the
  message; unknown type → exit 1 and the message.
- `McpCommandTest`: `--http 127.0.0.1:0` starts and `/health` answers;
  stdio mode: spawn with the SDK's `StdioClientTransport` **in-process is
  not possible**, so run the command in a child JVM only if cheap;
  otherwise assert the stdio branch by a `initialize` JSON-RPC round trip
  over piped streams (`StdioServerTransportProvider` accepts
  `InputStream`/`OutputStream` in its builder — use that) and that nothing
  but JSON-RPC reached stdout.
- `UpgradeCommandTest`: a stub GitHub API (`HttpServer`) with drafts,
  prereleases, SDK tags and two fcdev tags → the highest chosen; `--check`
  lines; sha256 mismatch → error and the current file untouched; a jar
  launch replaces a temp "self" path atomically (inject the self-path);
  `compareSemver` / `isCleanSemver` tables. Never hit the real network.

Mutants: `serviceAccountId` set to the service-account id (init test
fails); dotenv overriding a set env var (outbox precedence test fails);
sha256 check skipped (upgrade test fails); the mysql conversion dropping
the port default.
