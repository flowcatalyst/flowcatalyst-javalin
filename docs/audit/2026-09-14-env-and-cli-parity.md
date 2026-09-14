# 2026-09-14 — Env and CLI parity audit (Part 1 of the drop-in audit)

Static audit only — no `mvn`/`go build`/`go test` run (per task instructions;
another task owns the machine's builds). Go reference:
`/Users/andrewgraaff/Developer/flowcatalyst-go` at `f81fd5a` (working tree,
read-only). Java repo: `/Users/andrewgraaff/Developer/flowcatalyst-javalin`.

Baseline for "known/changed/new": the "Java compatibility" table in
`docs/deployments.md` (dated 2026-09-11 in the brief, but the file has since
picked up dated edits through 2026-09-14 — treated here as "the previous
audit" regardless). That table is scoped to the **IaC's actual variables**
(`../inhance/iac`); this audit is scoped to **every env read in the Go source
tree**, which is a larger set. Rows the IaC doesn't set are marked `(not in
IaC)` in the Known/Changed/New column and were not in scope for that table.

## 0. Headline discoveries (read this first)

Five Go-side behaviors turned out to be **hardcoded constants that a prior
document (`docs/spec/router-env.md`) had described as environment-configurable
because that spec compared Java against the old standalone **Rust**
`fc-router` binary, not against the unified Go `fc-server` this audit was
asked to check.  Verified by reading the call sites, not just grepping for
the env var name:

1. **`NOTIFICATION_BATCH_INTERVAL` / `FC_NOTIFY_BATCH_INTERVAL_SECONDS` is not
   read anywhere in Go.** `internal/router/server.go:203` calls
   `NewNotifier(cfg.NotifyWebhookURL, 20, 10*time.Second)` — batch size 20,
   interval 10s, both literals. Java reads the var with a 300s default
   (`Env.java:476`). **Consequence**: the router ECS task def sets
   `NOTIFICATION_BATCH_INTERVAL=300` (`docs/deployments.md` fc-router table)
   expecting a 300s batch window; Go silently uses 10s regardless, Java
   would actually honor the 300 the operator wrote. Cutting over to Java
   *changes* observed Teams-notification batching behavior (300s vs 10s) even
   though Java is the one "reading the variable it was told to read."
2. **`FC_STANDBY_LOCK_TTL_SECONDS`/`FLOWCATALYST_STANDBY_LOCK_TTL` and
   `FC_STANDBY_HEARTBEAT_SECONDS`/`FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL`
   are not read anywhere in Go.** `internal/common/config.go:100-109`
   (`NewLeaderElectionConfig`) hardcodes `LockTTLSeconds: 30`,
   `HeartbeatIntervalSeconds: 10`; neither `internal/server/subsystems.go`
   nor `internal/router/server.go` overrides them from env when building the
   standby config. Java reads both with matching defaults (30/10) — same
   *default* behavior, but Java lets an operator change them and Go does not.
3. **`FC_INSTANCE_ID`/`FLOWCATALYST_INSTANCE_ID`/`HOSTNAME` are not read
   anywhere in Go for standby.** `internal/common/config.go:107` always sets
   `InstanceID: uuid.NewString()`. Java reads the three-name chain, falling
   back to a random UUID when unset — same *effective* default, but again
   Java adds override capability Go lacks.
4. **`FLOWCATALYST_CONFIG_INTERVAL` / `FC_ROUTER_CONFIG_INTERVAL_SECONDS` is
   not read anywhere in Go either.** `internal/router/server.go:27-29`
   declares `ServerConfig.ConfigPollInterval time.Duration` as the knob, but
   grepping the whole tree for `FLOWCATALYST_CONFIG_INTERVAL` and
   `FC_ROUTER_CONFIG_INTERVAL_SECONDS` outside comments turns up nothing —
   `internal/server/envcfg.go`'s `EnvCfg` struct has no field for it, and
   `ConfigPollInterval` is only ever set once, from the hardcoded default in
   `ApplyDefaults` (`server.go:181-182`: `if cfg.ConfigPollInterval == 0 {
   cfg.ConfigPollInterval = 300 * time.Second }`). Java reads
   `FC_ROUTER_CONFIG_INTERVAL_SECONDS`/`FLOWCATALYST_CONFIG_INTERVAL` and
   would actually change the poll cadence if an operator set it
   (`router-env.md` already documents Java's side correctly; it's the
   "Rust reads it, so does Go" implication that's wrong for the *unified* Go
   binary). Same pattern as #1, currently invisible because the IaC's value
   (300s, `docs/deployments.md` fc-router table) happens to equal the
   hardcoded default — a cutover only surfaces this if someone ever sets it
   to something else.
5. **`RUST_LOG` and `LOG_FORMAT` are not read anywhere in Go.**
   `internal/logging/logging.go:24-35` (`Init`) reads only `FC_LOG_LEVEL`
   (default `info`) and always writes JSON to stderr — no format switch, no
   `RUST_LOG` fallback. Java's `Logging.java` reads `RUST_LOG` as a fallback
   when `FC_LOG_LEVEL` is unset and `FC_LOG_FORMAT`/`LOG_FORMAT` for a
   text/JSON switch — both **harmless extra compatibility surface** (the IaC
   sets `RUST_LOG=info` on all three services, a leftover from when this ran
   the Rust binary; Java tolerates it, Go silently ignores it, both end up at
   INFO/JSON).

None of these five are regressions in Java — in every case Java reads *more*
than Go does, and current defaults still agree. They matter because a
cutover that assumes "Go already honors this IaC variable" is wrong for #1
and #4 specifically (real behavior changes: Teams-alert batching and router
config-poll cadence), and because `docs/spec/router-env.md`'s framing ("Rust
`fc-router`... both sides") no longer describes the actual Go reference for
#1–#4.

Also newly found, not in the 2026-09-11 table:

6. **`FC_PRINCIPAL_VERSION_CACHE_SIZE` / `FC_PRINCIPAL_VERSION_CACHE_TTL_SECS`
   are read by Go (`internal/server/wire_services.go:126-127`) and ignored by
   Java** — the entire `internal/platform/shared/versioncache` subsystem (a
   Redis-backed, then in-process-LRU-backed, "has this principal changed"
   cache sitting in front of Postgres) has no Java port at all. Not in the
   IaC today (no ECS task sets it), so it did not surface in the previous
   audit, but it is a genuine `ignored` row, and a real subsystem gap, not
   just an env-name miss.
7. **`DISPATCH_QUEUE_TYPE` / `DISPATCH_QUEUE_URL` / `DISPATCH_QUEUE_REGION`
   are now read** (`Env.java:427-429`, feeding
   `DispatchQueueSettings#resolve`) — the 2026-09-11 table listed these as
   `⚠ IGNORED` for both fc-platform and fc-worker. This is **fixed since the
   last audit**; mark `changed`.
8. **`FLOWCATALYST_JWT_PUBLIC_KEY`, `FC_WEBAUTHN_RP_NAME`, and
   `FC_STATIC_DIR` are not read by Go either — correcting the 2026-09-11
   table's framing, not just Java's.** That table listed all three as
   Java-only gaps and, for the public key, asked for "an owner ruling on
   whether Java's derivation is actually wired up". Verified this run: Go
   never reads `FLOWCATALYST_JWT_PUBLIC_KEY` (only
   `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY`, `envcfg.go:279,341-347` — the
   *current* public key is derived from the private key on both sides, same
   as Java); `FC_WEBAUTHN_RP_NAME` is never read anywhere in Go
   (`wire_services.go:181-188` sets only `RPID` and `RPOrigins`, no
   `RPDisplayName`/`Name` field from env); `FC_STATIC_DIR` has zero hits
   anywhere in the Go tree (`grep -rn STATIC_DIR .` — nothing; the Go
   frontend is embedded via `embed.FS` too, same as Java's classpath
   approach). All three are dead variables inherited by *both* ports, most
   likely leftover from the original Rust binary's env surface — not an
   asymmetry, and the open "owner ruling" question on the public key is
   resolved: there's nothing to rule on, since Go doesn't read it either.
   Still worth an IaC cleanup note (three dead SSM-sourced/literal variables
   nobody needs), but downgrade from "Java gap" to "shared dead variable" in
   any follow-up.

## 1. Every environment read in Go

### 1a. `internal/server/envcfg.go` (`LoadEnv`, `EnvCfg`) — the central table

| Variable(s), first-wins | Go default | File:line | Purpose |
|---|---|---|---|
| `FC_API_PORT`, `PORT` | `8080` | `envcfg.go:204` | Unified API listener port |
| `FC_METRICS_PORT` | `9090` | `envcfg.go:205` | Metrics listener port |
| `FC_DATABASE_URL`, `DATABASE_URL` (→ `DB_HOST`+`DB_NAME`+`DB_PORT`+`DB_USERNAME`+`DB_PASSWORD` → default) | `postgresql://postgres@localhost:5432/flowcatalyst` | `envcfg.go:207,313-335` | DB connection string, 3-mode resolution |
| `FC_JWT_ISSUER`, `FC_EXTERNAL_BASE_URL`, `EXTERNAL_BASE_URL` | `http://localhost:8080` | `envcfg.go:208` | JWT issuer / external base URL |
| `FC_PLATFORM_ENABLED`, `PLATFORM_ENABLED` | `true` | `envcfg.go:210` | Platform/API subsystem toggle |
| `FC_ROUTER_ENABLED`, `MESSAGE_ROUTER_ENABLED` | `false` | `envcfg.go:211` | Router subsystem toggle |
| `FC_SCHEDULER_ENABLED`, `DISPATCH_SCHEDULER_ENABLED` | `false` | `envcfg.go:212` | Dispatch-job scheduler toggle |
| `FC_SCHEDULED_JOB_ENABLED`, `SCHEDULED_JOB_SCHEDULER_ENABLED` | `false` | `envcfg.go:213` | Scheduled-job cron toggle |
| `FC_STREAM_PROCESSOR_ENABLED`, `STREAM_PROCESSOR_ENABLED` | `false` | `envcfg.go:214` | Stream processor toggle |
| `FC_OUTBOX_ENABLED`, `OUTBOX_PROCESSOR_ENABLED` | `false` | `envcfg.go:215` | Outbox processor toggle |
| `FC_MCP_ENABLED` | `false` | `envcfg.go:216` | MCP subsystem toggle |
| `FC_ROUTER_HTTP_PREFIX` | `/router` | `envcfg.go:218` | Router API mount prefix on unified listener |
| `FC_MCP_PORT` | `8090` | `envcfg.go:219` | MCP listener port |
| `FC_MCP_BIND` | `127.0.0.1` | `envcfg.go:220` | MCP bind host |
| `FC_STREAM_EVENTS_ENABLED` | `true` | `envcfg.go:224` | Stream sub-toggle |
| `FC_STREAM_DISPATCH_JOBS_ENABLED` | `true` | `envcfg.go:225` | Stream sub-toggle |
| `FC_STREAM_FAN_OUT_ENABLED` | `true` | `envcfg.go:226` | Stream sub-toggle |
| `FC_STREAM_PARTITION_MANAGER_ENABLED`, `FC_STREAM_PARTITIONS_ENABLED` | `true` | `envcfg.go:229` | Partition manager toggle |
| `FC_STREAM_BATCH_SIZE` | `0` (impl default) | `envcfg.go:230` | Stream batch size (one shared knob — no per-projection override in Go) |
| `FC_STREAM_FAN_OUT_SUBS_REFRESH_SECS` | `0` → 5s | `envcfg.go:231` | Fan-out subscription cache TTL |
| `FC_STREAM_PARTITION_MONTHS_FORWARD` | `0` → 3 | `envcfg.go:232` | Partition manager tuning |
| `FC_STREAM_PARTITION_RETENTION_DAYS` | `0` → 90 | `envcfg.go:233` | Partition manager tuning |
| `FC_STREAM_PARTITION_RETENTION_DAYS_SCHEDULED_JOBS` | `0` → 30 | `envcfg.go:234` | Scheduled-job partition retention |
| `FC_STREAM_PARTITION_TICK_HOURS` | `0` → 24 | `envcfg.go:235` | Partition manager tick cadence |
| `FC_OUTBOX_PLATFORM_URL`, `FC_OUTBOX_API_URL`, `FC_API_BASE_URL`, `FLOWCATALYST_URL` | `""` | `envcfg.go:241` | Outbox target platform URL |
| `FC_OUTBOX_PLATFORM_AUTH_TOKEN`, `FC_OUTBOX_TOKEN`, `FC_API_TOKEN` | `""` | `envcfg.go:242` | Outbox static bearer |
| `FC_OUTBOX_BATCH_SIZE` | `0` (lib default) | `envcfg.go:243` | Outbox batch size |
| `FC_OUTBOX_MAX_IN_FLIGHT` | `0` (lib default) | `envcfg.go:244` | Outbox max in-flight |
| `FC_OUTBOX_POLL_INTERVAL_MS` | `0` (lib default) | `envcfg.go:245` | Outbox poll interval |
| `FC_OUTBOX_MAX_CONCURRENT_GROUPS`, `FC_MAX_CONCURRENT_GROUPS` | `0` → 10 | `envcfg.go:246` | Outbox max concurrent groups |
| `FC_OUTBOX_BLOCK_ON_ERROR` | `true` | `envcfg.go:247` | Outbox block-on-error |
| `FC_OUTBOX_ADMIN_PORT` | `0` (off) | `envcfg.go:248` | Outbox admin API port |
| `FC_OUTBOX_BACKEND`, `FC_OUTBOX_DB_TYPE` | `postgres` | `envcfg.go:252` | Outbox storage backend |
| `FC_OUTBOX_MONGO_URI`, `FC_OUTBOX_DB_URL` | `""` | `envcfg.go:253` | Outbox Mongo URI |
| `FC_OUTBOX_MONGO_DB` | `flowcatalyst` | `envcfg.go:254` | Outbox Mongo DB name |
| `FLOWCATALYST_CONFIG_URL` | `""` | `envcfg.go:256` | Router config-service URL(s), comma-separated |
| `FLOWCATALYST_DEV_MODE` | `false` | `envcfg.go:257` | Router dev-mode flag |
| `FC_NOTIFY_WEBHOOK_URL` | `""` | `envcfg.go:258` | Router Teams webhook |
| `FC_DRAIN_TIMEOUT_SECONDS` | `60` | `envcfg.go:259` | Router pool-drain budget |
| `FC_ROUTER_SYNTH_POOL_IDLE_SECS` | `0` (impl default) | `envcfg.go:260` | Synth-pool idle TTL |
| `FC_ROUTER_STRICT_ROUTING` | `false` | `envcfg.go:261` | Malformed-message ACK vs default-and-continue |
| `FC_NOTIFY_MIN_SEVERITY` | `""` (pkg floor stands) | `envcfg.go:262` | Router notifier min severity |
| `FLOWCATALYST_CONFIG_INTERVAL`, `FC_ROUTER_CONFIG_INTERVAL_SECONDS` | **not read anywhere** — `router.ServerConfig.ConfigPollInterval` is only ever set from the hardcoded `300 * time.Second` default (`server.go:181-182`) | `server.go:27-29,181-182,342` (no `envcfg.go` field at all) | Router config-poll cadence — see §0 #4 |
| `FC_ROUTER_PLATFORM_URL`, `FC_API_BASE_URL`, `FLOWCATALYST_URL` | `""` | `envcfg.go:263` | A-01 settled-report hook base URL |
| `FC_ROUTER_CLIENT_ID` | `""` | `envcfg.go:264` | Router's own OAuth client id |
| `FC_ROUTER_CLIENT_SECRET` | `""` | `envcfg.go:265` | Router's own OAuth client secret |
| `FC_ALB_ENABLED` | `false` | `envcfg.go:267` | ALB self-registration toggle |
| `FC_ALB_TARGET_GROUP_ARN` | `""` | `envcfg.go:268` | ALB target group ARN |
| `FC_ALB_TARGET_ID`, `FC_ALB_INSTANCE_IP` | `""` | `envcfg.go:269` | ALB target id (this instance's IP) |
| `FC_ALB_TARGET_PORT` | `8080` | `envcfg.go:270` | ALB target port |
| `FC_ALB_REGION` | `""` (SDK chain) | `envcfg.go:271` | ALB region override |
| `FC_ALB_DEREGISTRATION_DELAY_SECONDS` | `0` | `envcfg.go:272` | ALB deregistration delay |
| `FC_STANDBY_ENABLED`, `STANDBY_ENABLED` | `false` | `envcfg.go:274` | Standby/HA toggle |
| `FC_STANDBY_REDIS_URL`, `REDIS_URL` | `""` → `redis://127.0.0.1:6379` | `envcfg.go:275` | Standby Redis URL |
| `FC_STANDBY_LOCK_KEY` | `fc:server:leader` | `envcfg.go:276` | Standby lock key |
| `FC_JWT_SIGNING_KEY_PATH` | `""` | `envcfg.go:278` | JWT signing key file path |
| `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY` | `""` | `envcfg.go:279,341-347` | Previous public key (rotation) |
| `FC_AUTH_ALLOW_TEST_HEADERS` | `false` | `envcfg.go:280` | `X-FC-Test-Principal` dev escape hatch |
| `FLOWCATALYST_URL`, `FC_MCP_PLATFORM_URL`¹ | `""` | `envcfg.go:282` | MCP platform base URL |
| `FLOWCATALYST_CLIENT_ID` | `""` | `envcfg.go:283` | MCP OAuth client id |
| `FLOWCATALYST_CLIENT_SECRET` | `""` | `envcfg.go:284` | MCP OAuth client secret |
| `FC_DISPATCH_PROCESSING_ENDPOINT`, `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT` | `""` → `http://localhost:{apiPort}/api/dispatch/process` | `envcfg.go:288,303-305` | Dispatch callback URL |
| `FC_DISPATCH_QUEUE_TYPE`, `DISPATCH_QUEUE_TYPE` | `""` | `envcfg.go:292` | Dispatch queue type (SQS vs Postgres) |
| `FC_DISPATCH_QUEUE_URL`, `DISPATCH_QUEUE_URL` | `""` | `envcfg.go:293` | Dispatch queue URL (region/account derivation only) |
| `FC_DISPATCH_QUEUE_REGION`, `DISPATCH_QUEUE_REGION` | `""` | `envcfg.go:294` | Dispatch queue region override |
| `FC_DISPATCH_QUEUE_PREFIX` | `""` | `envcfg.go:295` | Composed queue name prefix |
| `OIDC_SESSION_TTL` | `0` → 86400 (positiveOr) | `envcfg.go:297` | Session TTL seconds |
| `FC_JWT_ACCESS_TOKEN_TTL_SECS`, `OIDC_ACCESS_TOKEN_TTL` | `0` → 3600 | `envcfg.go:298` | Access-token TTL seconds |
| `OIDC_REFRESH_TOKEN_TTL` | `0` → 604800 | `envcfg.go:299` | Refresh-token TTL seconds |

¹ `envcfg.go:282` is actually `envFirst("FLOWCATALYST_URL", "FC_MCP_PLATFORM_URL", "", "")` — **note**: this reads `FC_MCP_PLATFORM_URL` too, *contradicting* the finding below (§3) that `internal/mcp/config.go`'s `LoadConfig()` does not. Both are real, separate call sites: `EnvCfg.MCPPlatformURL` (used to wire the in-process `Server`-hosted MCP subsystem when `FC_MCP_ENABLED=true`) reads the alias; the **standalone** `fcdev mcp` / `cmd/fc-server`-adjacent `internal/mcp.LoadConfig()` (used only by the Go `fcdev mcp` subcommand, not by `fc-server`) does not. See §3 for the corrected per-binary breakdown.

`envcfg.go` also defines `webauthnOrigins()` (`envcfg.go:363-373`, reads
`FC_WEBAUTHN_ORIGINS` then `FC_WEBAUTHN_RP_ORIGIN`, default
`http://localhost:8080`) — **not called from `LoadEnv`**; it and
`FC_WEBAUTHN_RP_ID` (`envOr("FC_WEBAUTHN_RP_ID", "localhost")`) are read
directly from `internal/server/wire_services.go:186-187`, outside `EnvCfg`.

### 1b. Scattered `os.Getenv`/helper reads outside `envcfg.go`

| Variable(s) | Go default | File:line | Purpose |
|---|---|---|---|
| `DB_SECRET_PROVIDER` | `aws` | `dbsecret.go:56` | Must be `aws`, else startup error |
| `DB_SECRET_ARN` | `""` | `dbsecret.go:48,162` | AWS Secrets Manager secret ARN (SM mode gate) |
| `DB_HOST` | `""` | `dbsecret.go:49,163`, `envcfg.go:317` | SM-mode + 2nd-mode DB host |
| `DB_NAME` | `flowcatalyst` | `dbsecret.go:65`, `envcfg.go:323` | DB name |
| `DB_PORT` | `""`/`5432` | `dbsecret.go:65`, `envcfg.go:324` | DB port |
| `DB_USERNAME` | `postgres` | `envcfg.go:325` | 2nd-mode DB user |
| `DB_PASSWORD` | `""` | `envcfg.go:326`, `dbsecret.go` | 2nd-mode DB password |
| `DB_SECRET_REFRESH_INTERVAL_MS` | `300000` (5 min) | `dbsecret.go:166` | SM credential-rotation poll interval; `<=0` disables rotation |
| `FC_WEBAUTHN_RP_ID` | `localhost` | `wire_services.go:186` | WebAuthn RP id |
| `FC_WEBAUTHN_ORIGINS`, `FC_WEBAUTHN_RP_ORIGIN` | `http://localhost:8080` | `envcfg.go:364`, `wire_services.go:187` | WebAuthn allowed origins |
| `AUTH_MODE` | `""` | `run.go:276` | `NONE` (case-insens.) disables router BasicAuth |
| `FC_ROUTER_AUTH_USER`, `AUTH_BASIC_USERNAME` | `""` | `run.go:280` | Router BasicAuth username |
| `FC_ROUTER_AUTH_PASS`, `AUTH_BASIC_PASSWORD` | `""` | `run.go:281` | Router BasicAuth password |
| `FLOWCATALYST_APP_KEY` | `""` | `subsystems.go:138`, `encryption.go:124`, `decrypt-check/main.go:31`, `fcdev/init.go:253`, `fcdev/start.go:172`, `fcdev/mcp_bootstrap.go` (via `encryption.FromEnv`) | Field-encryption key |
| `FLOWCATALYST_APP_KEY_PREVIOUS` | `""` | `encryption.go:128` | Field-encryption key during rotation |
| `FLOWCATALYST_JWT_PRIVATE_KEY`, `FC_JWT_SIGNING_KEY_PEM` | `""` | `signing_key.go:39-42` | Inline JWT signing key PEM (2nd-choice after `FC_JWT_SIGNING_KEY_PATH`) |
| `FC_RATE_LIMIT_DISABLE` | `""` (not `"1"`) | `ratelimit.go:129` | `1` → Noop rate-limit store |
| `FC_REDIS_URL` | `""` | `ratelimit.go:133`, `versioncache/store.go:58` | Redis backend for rate-limit + principal-version-cache stores |
| `FC_LOGIN_BACKOFF_FREE_ATTEMPTS` | `3` | `loginbackoff.go:43` | Login backoff |
| `FC_LOGIN_BACKOFF_BASE_SECS` | `2` | `loginbackoff.go:44` | Login backoff |
| `FC_LOGIN_BACKOFF_MAX_SECS` | `300` | `loginbackoff.go:45` | Login backoff |
| `FC_LOGIN_GLOBAL_WINDOW_SECS` | `3600` | `loginbackoff.go:46` | Login backoff (global) |
| `FC_LOGIN_GLOBAL_CEILING` | `100` | `loginbackoff.go:47` | Login backoff (global) |
| `FC_LOGIN_GLOBAL_LOCK_SECS` | `900` | `loginbackoff.go:48` | Login backoff (global) |
| `FC_SCHEDULED_JOB_POLL_SECONDS` | unset → impl default | `cron.go:60` | Scheduled-job scheduler poll cadence |
| `FC_SCHEDULED_JOB_DISPATCH_SECONDS` | unset → impl default | `cron.go:63` | Scheduled-job dispatch cadence |
| `FC_SCHEDULED_JOB_DISPATCH_BATCH` | unset → impl default | `cron.go:66` | Scheduled-job dispatch batch size |
| `FC_SCHEDULED_JOB_HTTP_TIMEOUT_SECONDS` | unset → impl default | `cron.go:69` | Scheduled-job HTTP timeout |
| `FC_RL_OAUTH_TOKEN_IP_PER_MIN` | `600` | `ratelimit.go:68` | Distributed rate-limit policy |
| `FC_RL_OAUTH_TOKEN_CLIENT_PER_MIN` | `300` | `ratelimit.go:69` | Distributed rate-limit policy |
| `FC_RL_OAUTH_AUTHORIZE_IP_PER_MIN` | `600` | `ratelimit.go:70` | Distributed rate-limit policy |
| `FC_RL_OAUTH_AUTHORIZE_CLIENT_PER_MIN` | `300` | `ratelimit.go:71` | Distributed rate-limit policy |
| `FC_RL_PASSWORD_RESET_IP_PER_HOUR` | `20` | `ratelimit.go:72` | Distributed rate-limit policy |
| `FC_RL_PASSWORD_RESET_EMAIL_PER_HOUR` | `5` | `ratelimit.go:73` | Distributed rate-limit policy |
| `FC_RL_PORTAL_LOGIN_PER_15MIN` | `10` | `ratelimit.go:74` | Distributed rate-limit policy |
| `FC_OAUTH_TOKEN_IP_RATE_PER_MIN` | `120` | `governor.go:50` | In-process governor (defense in depth) |
| `FC_OAUTH_TOKEN_IP_BURST` | `60` | `governor.go:51` | In-process governor |
| `FC_OAUTH_TOKEN_CLIENT_RATE_PER_MIN` | `60` | `governor.go:60` | In-process governor |
| `FC_OAUTH_TOKEN_CLIENT_BURST` | `30` | `governor.go:61` | In-process governor |
| `FC_OIDC_RATE_PER_MIN` | `60` | `governor.go:71` | In-process governor |
| `FC_OIDC_BURST` | `30` | `governor.go:72` | In-process governor |
| `FC_PRINCIPAL_VERSION_CACHE_SIZE` | `10000` | `wire_services.go:126` | In-process LRU size for the version-cache Reader |
| `FC_PRINCIPAL_VERSION_CACHE_TTL_SECS` | `30` | `wire_services.go:127` | In-process LRU TTL |
| `FC_SMTP_HOST`, `SMTP_HOST` | `""` (→ LogService) | `email.go:65` | SMTP host |
| `FC_SMTP_PORT`, `SMTP_PORT` | `587` | `email.go:67` | SMTP port |
| `FC_SMTP_USERNAME`, `SMTP_USERNAME` | `""` | `email.go:68` | SMTP username |
| `FC_SMTP_PASSWORD`, `SMTP_PASSWORD` | `""` | `email.go:69` | SMTP password |
| `FC_SMTP_FROM`, `SMTP_FROM` | `noreply@flowcatalyst.local` | `email.go:70` | SMTP from address |
| `FC_SMTP_SECURE`, `SMTP_SECURE` | `false` | `email.go:71` | SMTP implicit-TLS vs STARTTLS |
| `FLOWCATALYST_BOOTSTRAP_ADMIN_EMAIL` | `""` | `seed/admin.go:23,54` | Bootstrap admin email |
| `FLOWCATALYST_BOOTSTRAP_ADMIN_PASSWORD` | `""` | `seed/admin.go:24,55` | Bootstrap admin password |
| `FLOWCATALYST_BOOTSTRAP_ADMIN_NAME` | `""` | `seed/admin.go:25,66` | Bootstrap admin display name |
| `FC_LOG_LEVEL` | `info` | `logging.go:24-35` | Root log level — **only** var `logging.Init` reads; no `RUST_LOG`, no format switch |
| `FLOWCATALYST_SIGNING_SECRET` | `""` | `pkg/fcsdk/webhook/validator.go:89` | **SDK-side** (client library) webhook signature secret — not a server var, out of scope for the drop-in but flagged since it's under `pkg/` |

`internal/secrets/env.go` is a generic `env://VAR_NAME` secret-reference
resolver (reads whatever key name a `env://` ref names at runtime) — not a
fixed variable, excluded from the table.

`pkg/fcsdk/examples/*` (`list-event-types`, `fc-sync`,
`scheduled-jobs-runner`) read `FC_BASE_URL`/`FC_APP`/`FC_TOKEN`/`FC_ISSUER`/
`FC_CLIENT_ID`/`FC_CLIENT_SECRET` — these are **sample client programs**, not
part of any deployed FlowCatalyst binary; excluded from the join in §3.

### 1c. `cmd/fcdev` — dev-monolith-local env reads (flag-default seeding, not `EnvCfg`)

Covered in §4 (CLI diff) since every one of these is a flag default, not a
bare read.

## 2. Every environment read in Java

`server/src/main/java/io/flowcatalyst/server/Env.java` (`Env.load`) is the
single central table — reproduced in full below via its `EnvReader` calls
(the record's javadoc already documents each field 1:1 with the Go source,
so this table only adds status). All from `Env.java:398-521` unless noted.

| Variable(s), first-wins | Java default | Status vs Go §1a/1b |
|---|---|---|
| `FC_API_PORT`, `API_PORT`, `PORT` | `8080` | read (Java adds `FC_API_PORT` as a Java-only canonical first name — Go only has `API_PORT`→`PORT`, no `FC_` name) |
| `FC_METRICS_PORT` | `9090` | read |
| `FC_TLS_PORT` | `8443` | **new** — no Go analog (HTTP/2 TLS + HTTP/3 listener, `docs/spec/http-transport.md`) |
| `FC_TLS_KEYSTORE_PATH` / `_PASSWORD` | `""` | **new** |
| `FC_TLS_CERT_PATH` / `FC_TLS_KEY_PATH` | `""` | **new** |
| `FC_HTTP3_ENABLED` | `false` | **new** |
| `FC_HTTP3_PORT` | = tlsPort | **new** |
| `FC_DATABASE_URL`, `DATABASE_URL`, `DB_HOST`+… | same 3-mode resolution | read |
| `FC_JWT_ISSUER`, `FC_EXTERNAL_BASE_URL`, `EXTERNAL_BASE_URL` | `http://localhost:8080` | read |
| `FC_JWT_ACCESS_TOKEN_TTL_SECS`, `OIDC_ACCESS_TOKEN_TTL` | `3600` | read |
| `FC_SESSION_TTL_SECS`, `OIDC_SESSION_TTL` | `86400` | read |
| `FC_REFRESH_TOKEN_TTL_SECS`, `OIDC_REFRESH_TOKEN_TTL` | `604800` | read |
| `FC_PLATFORM_ENABLED`, `PLATFORM_ENABLED` | `true` | read |
| `FC_ROUTER_ENABLED`, `MESSAGE_ROUTER_ENABLED` | `false` | read |
| `FC_SCHEDULER_ENABLED`, `DISPATCH_SCHEDULER_ENABLED` | `false` | read |
| `FC_SCHEDULED_JOB_ENABLED`, `SCHEDULED_JOB_SCHEDULER_ENABLED` | `false` | read |
| `FC_STREAM_PROCESSOR_ENABLED`, `STREAM_PROCESSOR_ENABLED` | `false` | read |
| `FC_OUTBOX_ENABLED`, `OUTBOX_PROCESSOR_ENABLED` | `false` | read |
| `FC_MCP_ENABLED` | `false` | read |
| `FC_ROUTER_HTTP_PREFIX` | `/router` | read |
| `FC_DEFAULT_BROKER` | `""` | **new** (Java-only broker-selection knob; nearest Go concept is the `FC_DISPATCH_QUEUE_TYPE` value, not the same thing — see `docs/spec/router-env.md`'s superseded-note) |
| `FC_DISPATCH_PROCESSING_ENDPOINT`, `DISPATCH_SCHEDULER_PROCESSING_ENDPOINT` | → `http://localhost:{apiPort}/api/dispatch/process` | read |
| `FC_DISPATCH_QUEUE_TYPE`, `DISPATCH_QUEUE_TYPE` | `""` | read — **changed since 2026-09-11** (was ignored) |
| `FC_DISPATCH_QUEUE_URL`, `DISPATCH_QUEUE_URL` | `""` | read — **changed since 2026-09-11** (was ignored) |
| `FC_DISPATCH_QUEUE_REGION`, `DISPATCH_QUEUE_REGION` | `""` | read — **changed since 2026-09-11** (was ignored) |
| `FC_DISPATCH_QUEUE_PREFIX` | `""` | read |
| `FC_MCP_PORT` | `8090` | read |
| `FC_MCP_BIND` | `127.0.0.1` | read |
| `FC_STREAM_EVENTS_ENABLED` | `true` | read |
| `FC_STREAM_DISPATCH_JOBS_ENABLED` | `true` | read |
| `FC_STREAM_FAN_OUT_ENABLED` | `true` | read |
| `FC_STREAM_PARTITION_MANAGER_ENABLED`, `FC_STREAM_PARTITIONS_ENABLED` | `true` | read |
| `FC_STREAM_BATCH_SIZE` | `0` | read |
| `FC_STREAM_FAN_OUT_BATCH_SIZE` | `0` | **new** — Go has no per-projection override, only the one shared `FC_STREAM_BATCH_SIZE` |
| `FC_STREAM_EVENTS_BATCH_SIZE` | `0` | **new** |
| `FC_STREAM_DISPATCH_JOBS_BATCH_SIZE` | `0` | **new** |
| `FC_STREAM_FAN_OUT_SUBS_REFRESH_SECS` | `0` | read |
| `FC_STREAM_PARTITION_MONTHS_FORWARD` | `0` | read |
| `FC_STREAM_PARTITION_RETENTION_DAYS` | `0` | read |
| `FC_STREAM_PARTITION_RETENTION_DAYS_SCHEDULED_JOBS` | `0` | read |
| `FC_STREAM_PARTITION_TICK_HOURS` | `0` | read |
| `FC_SCHEDULED_JOB_POLL_SECONDS` | `0` | read |
| `FC_SCHEDULED_JOB_DISPATCH_SECONDS` | `0` | read |
| `FC_SCHEDULED_JOB_DISPATCH_BATCH` | `0` | read |
| `FC_SCHEDULED_JOB_HTTP_TIMEOUT_SECONDS` | `0` | read |
| `FC_OUTBOX_PLATFORM_URL`, `FC_OUTBOX_API_URL`, `FC_API_BASE_URL`, `FLOWCATALYST_URL` | `""` | read |
| `FC_OUTBOX_PLATFORM_AUTH_TOKEN`, `FC_OUTBOX_TOKEN`, `FC_API_TOKEN` | `""` | read |
| `FC_OUTBOX_BATCH_SIZE` / `_MAX_IN_FLIGHT` / `_POLL_INTERVAL_MS` | `0` | read |
| `FC_OUTBOX_MAX_CONCURRENT_GROUPS`, `FC_MAX_CONCURRENT_GROUPS` | `0` | read |
| `FC_OUTBOX_BLOCK_ON_ERROR` | `true` | read |
| `FC_OUTBOX_ADMIN_PORT` | `0` | read |
| `FC_OUTBOX_BACKEND`, `FC_OUTBOX_DB_TYPE` | `postgres` | read |
| `FC_OUTBOX_MONGO_URI`, `FC_OUTBOX_DB_URL` | `""` | read |
| `FC_OUTBOX_MONGO_DB` | `flowcatalyst` | read |
| `FLOWCATALYST_CONFIG_URL` | `""` | read |
| `FC_ROUTER_CONFIG_INTERVAL_SECONDS`, `FLOWCATALYST_CONFIG_INTERVAL` | `""` (300 applied downstream) | **aliased-different-behavior** — Go doesn't read this at all (§0 #4); real behavior gap, same pattern as `FC_NOTIFY_BATCH_INTERVAL_SECONDS` below |
| `FLOWCATALYST_DEV_MODE` | `false` | read |
| `FC_NOTIFY_WEBHOOK_URL`, `NOTIFICATION_TEAMS_WEBHOOK_URL` | `""` | read |
| `NOTIFICATION_TEAMS_ENABLED` | `""` (raw) | read — **deliberate semantic deviation** (§0 already known; explicit `false` always disables in Java, only widens in Go) |
| `FC_NOTIFY_MIN_SEVERITY`, `NOTIFICATION_MIN_SEVERITY` | `WARNING` | read |
| `FC_NOTIFY_BATCH_INTERVAL_SECONDS`, `NOTIFICATION_BATCH_INTERVAL` | `300` | **aliased-different-behavior** — Go doesn't read this at all (§0 #1); real behavior gap |
| `FC_DRAIN_TIMEOUT_SECONDS` | `60` | read |
| `FC_ROUTER_STRICT_ROUTING` | `false` | read |
| `FC_ROUTER_SYNTH_POOL_IDLE_SECS` | `0` | read |
| `AUTH_MODE` | `""` | read |
| `FC_ROUTER_AUTH_USER`, `AUTH_BASIC_USERNAME` | `""` | read |
| `FC_ROUTER_AUTH_PASS`, `AUTH_BASIC_PASSWORD` | `""` | read |
| `FC_ROUTER_PLATFORM_URL` | `""` | **aliased-different** — Go also accepts `FC_API_BASE_URL`/`FLOWCATALYST_URL` as fallbacks (`envcfg.go:263`); Java reads **only** `FC_ROUTER_PLATFORM_URL` (`Env.java:483`, the javadoc admits "no alias, alias sprawl is an open owner question") |
| `FC_ROUTER_CLIENT_ID` / `_SECRET` | `""` | read |
| `FC_ALB_ENABLED` | `false` | read |
| `FC_ALB_TARGET_GROUP_ARN` | `""` | read |
| `FC_ALB_TARGET_ID`, `FC_ALB_INSTANCE_IP` | `""` | read |
| `FC_ALB_TARGET_PORT` | `8080` | read |
| `FC_ALB_REGION` | `""` | read |
| `FC_ALB_DEREGISTRATION_DELAY_SECONDS` | `0` | read |
| `FC_STANDBY_ENABLED`, `FLOWCATALYST_STANDBY_ENABLED`, `STANDBY_ENABLED` | `false` | read |
| `FC_STANDBY_REDIS_URL`, `FLOWCATALYST_STANDBY_REDIS_URL`, `FLOWCATALYST_REDIS_URL`, `REDIS_URL` | `redis://127.0.0.1:6379` | read |
| `FC_STANDBY_LOCK_KEY`, `FLOWCATALYST_STANDBY_LOCK_KEY` | `fc:router:leader` | **aliased-different-default** — Go default `fc:server:leader` (documented deliberate deviation, owner ruling 2026-09-11, `docs/spec/router-env.md`) |
| `FC_STANDBY_LOCK_TTL_SECONDS`, `FLOWCATALYST_STANDBY_LOCK_TTL` | `30` | **new-but-same-default** — Go hardcodes 30, doesn't read any var (§0 #2) |
| `FC_STANDBY_HEARTBEAT_SECONDS`, `FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL` | `10` | **new-but-same-default** — Go hardcodes 10, doesn't read any var (§0 #2) |
| `FC_INSTANCE_ID`, `FLOWCATALYST_INSTANCE_ID`, `HOSTNAME` | `""` → random UUID | **new-but-same-default** — Go always generates a fresh UUID, doesn't read any var (§0 #3) |
| `FC_JWT_SIGNING_KEY_PATH` | `""` | read |
| `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY` | `""` | read |
| `FC_AUTH_ALLOW_TEST_HEADERS` | `false` | read |
| `FC_CORS_CACHE_TTL_MS` | `30000` | **new** — no Go analog (CORS config is DB-driven with no cache-TTL knob on the Go side; grep found zero `CORS`-named env reads in Go) |
| `FLOWCATALYST_APP_KEY` | `""` | read |
| `FLOWCATALYST_APP_KEY_PREVIOUS` | `""` | read |
| `FLOWCATALYST_URL`, `FC_MCP_PLATFORM_URL` | `""` | read — see note in §3 (Go reads this alias too, but only in `EnvCfg`, not in `internal/mcp.LoadConfig()`) |
| `FLOWCATALYST_CLIENT_ID` / `_SECRET` | `""` | read |
| `FC_MCP_PLATFORM_AUTH_TOKEN` | `""` | **new** — Java-only interim static-bearer path (`docs/spec/mcp.md` §2); nearest Go behavior is `ClientSecret`-only-as-bearer in `internal/mcp/config.go:134-135`, a different mechanism |
| `FC_WEBAUTHN_RP_ID` | `localhost` | read |
| `FC_WEBAUTHN_ORIGINS`, `FC_WEBAUTHN_RP_ORIGIN` | `[http://localhost:8080]` | read |

Not modeled as `Env` fields but read directly by their owning subsystem
(`Env.java`'s own javadoc lists this exclusion) — verified present by name:

| Variable(s) | Java file | Status |
|---|---|---|
| `FC_DB_POOL_SIZE`, `FC_DB_POOL_SIZE_API`/`_BFF`/`_DISPATCH`/`_BACKGROUND` | `Pools.java` | **new** — Go's `database.Config.MaxConnections` exists but is **never set from any env var** anywhere in Go (`database.go:44`, confirmed by grep — no caller sets it), so Go has *no* operator control over pool size at all; Java's whole 4-pool-gated architecture (`docs/spec/admission.md` §11.7) is new |
| `FC_RATE_LIMIT_DISABLE` | `RateLimitStores.java` | read |
| `FC_REDIS_URL` | `RateLimitStores.java` | read (rate-limit backend only — see versioncache gap below) |
| `FC_LOGIN_BACKOFF_FREE_ATTEMPTS` / `_BASE_SECS` / `_MAX_SECS`, `FC_LOGIN_GLOBAL_WINDOW_SECS` / `_CEILING` / `_LOCK_SECS` | `BackoffPolicy.java` | read (all 6) |
| `FC_RL_OAUTH_TOKEN_IP_PER_MIN` / `_CLIENT_PER_MIN`, `FC_RL_OAUTH_AUTHORIZE_IP_PER_MIN` / `_CLIENT_PER_MIN`, `FC_RL_PASSWORD_RESET_IP_PER_HOUR` / `_EMAIL_PER_HOUR`, `FC_RL_PORTAL_LOGIN_PER_15MIN` | `RateLimit.java` | read (all 7) |
| `FC_OAUTH_TOKEN_IP_RATE_PER_MIN` / `_BURST`, `FC_OAUTH_TOKEN_CLIENT_RATE_PER_MIN` / `_BURST`, `FC_OIDC_RATE_PER_MIN` / `_BURST` | `Governor.java` | read (all 6) |
| `FC_PRINCIPAL_VERSION_CACHE_SIZE`, `FC_PRINCIPAL_VERSION_CACHE_TTL_SECS` | — | **ignored** — no match anywhere in `server/src/main/java`; the whole `versioncache` subsystem is unported (§0 #5) |
| `FC_SMTP_HOST`/`SMTP_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, `_FROM`, `_SECURE` | `SmtpMailService.java` | read (all 6, per `docs/deployments.md`) |
| `FLOWCATALYST_BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD`/`_NAME` | `Seeder.java` | read (all 3, same canonical names as Go's `EnvBootstrapEmail`/`Password`/`Name` constants) |
| `FC_LOG_LEVEL` | `Logging.java` | read |
| `RUST_LOG` | `Logging.java` | **read (Java-only extra; Go doesn't)** — §0 #4 |
| `FC_LOG_FORMAT`, `LOG_FORMAT` | `Logging.java` | **read (Java-only extra; Go doesn't)** — §0 #4 |
| `FC_WEBAUTHN_RP_NAME` | — | **ignored** (known, `docs/deployments.md`) |
| `DB_SECRET_PROVIDER`, `DB_SECRET_ARN`, `DB_HOST`, `DB_NAME`, `DB_PORT`, `DB_SECRET_REFRESH_INTERVAL_MS` | `DbSecretMode.java`, `DbSecretRefresher.java`, `DbSecretFetcher.java` | read (default `DB_SECRET_REFRESH_INTERVAL_MS` = 300000, matches Go) |
| `FC_STATIC_DIR` | — | **ignored** (known, `docs/deployments.md`; Java serves the SPA from the classpath unconditionally) |

## 3. The join — status and Known/Changed/New vs the 2026-09-11 table

Only rows that were **in the IaC** (`docs/deployments.md`'s per-service
tables) are eligible for a Known/Changed/New verdict against that table;
everything else is marked `(not in IaC)`.

| Variable | Join status | vs 2026-09-11 table |
|---|---|---|
| `DISPATCH_QUEUE_TYPE` | read (via `FC_DISPATCH_QUEUE_TYPE` alias) | **changed** — was `⚠ IGNORED` |
| `DISPATCH_QUEUE_URL` | read | **changed** — was `⚠ IGNORED` |
| `DISPATCH_QUEUE_REGION` | read | **changed** — was `⚠ IGNORED` |
| `FLOWCATALYST_JWT_PUBLIC_KEY` | ignored by **both** Java and Go (§0 #8) — Java: no match in `server/src/main/java`, only `_PREVIOUS_PUBLIC_KEY` is read; Go: same, `envcfg.go` only reads the previous key too | **changed** — the 2026-09-11 table framed this as a Java-only gap needing an owner ruling on Java's derivation; this audit found Go doesn't read it either, so the ruling question is resolved (both sides derive the current public key from the private key) |
| `FC_WEBAUTHN_RP_NAME` | ignored by **both** (§0 #8) — Go's `wire_services.go:181-188` sets only `RPID`/`RPOrigins` from env, never a display name | **changed** — was framed as Java-only; it's a shared dead variable |
| `FC_STATIC_DIR` | ignored by **both** (§0 #8) — zero hits anywhere in the Go tree; Go embeds the frontend via `embed.FS` unconditionally too | **changed** — was framed as Java-only; it's a shared dead variable |
| `RUST_LOG` | read as fallback (§0 #4) | known — table already called this `aliased (last resort)`; this audit adds the fact that **Go itself never reads it** |
| `DB_SECRET_PROVIDER`/`_ARN`, `DB_HOST`, `DB_NAME` | read | known |
| `REDIS_URL` | read (standby only; consulted only when `STANDBY_ENABLED`) | known |
| `FLOWCATALYST_APP_KEY` | read | known |
| `FLOWCATALYST_JWT_PRIVATE_KEY` | read | known |
| `FLOWCATALYST_JWT_PREVIOUS_PUBLIC_KEY` | read | known |
| `PORT` | read (aliased) | known |
| `PLATFORM_ENABLED` | read (aliased) | known |
| `STREAM_PROCESSOR_ENABLED` | read (aliased) | known |
| `DISPATCH_SCHEDULER_ENABLED` | read (aliased) | known |
| `MESSAGE_ROUTER_ENABLED` | read (aliased) | known |
| `STANDBY_ENABLED` | read (aliased) | known |
| `EXTERNAL_BASE_URL` | read (aliased) | known |
| `OIDC_ACCESS_TOKEN_TTL` / `OIDC_SESSION_TTL` / `OIDC_REFRESH_TOKEN_TTL` | read | known |
| `SMTP_HOST`/`_PORT`/`_SECURE`/`_USERNAME`/`_FROM`/`_PASSWORD` | read | known |
| `FC_SCHEDULED_JOB_ENABLED` | read (canonical) | known |
| `FLOWCATALYST_CONFIG_URL` | read | known |
| `FLOWCATALYST_CONFIG_INTERVAL` | read (aliased) in Java; **not read at all in Go** (§0 #4 — new finding, hardcoded 300s) | known **as a Java behavior**, but same correction as `NOTIFICATION_BATCH_INTERVAL` below: the prior table's "both sides" implication was never checked against Go source before this audit |
| `FLOWCATALYST_STANDBY_ENABLED` | read (aliased) | known |
| `AUTH_MODE` | read | known |
| `NOTIFICATION_TEAMS_ENABLED` | read (deliberate deviation) | known |
| `NOTIFICATION_TEAMS_WEBHOOK_URL` | read (aliased) | known |
| `NOTIFICATION_MIN_SEVERITY` | read (aliased) | known |
| `NOTIFICATION_BATCH_INTERVAL` | read (aliased) | known **as a Java behavior**, but this audit's §0 #1 is new information: Go does not read it at all, so the "both sides honor this" implication in the prior table's phrasing was never checked against Go source before now |
| `API_PORT` | read (aliased) | known |
| `AWS_REGION` | read (implicit, SDK chain) | known |
| `FC_ROUTER_PLATFORM_URL` (2026-09-14 IaC addition, `docs/deployments.md` "Changed in the IaC on 2026-09-14") | read, but **narrower than Go** — Go also falls back to `FC_API_BASE_URL`/`FLOWCATALYST_URL`; Java reads only the one name | **new row** (added to the IaC this week) — flag as `aliased-different` since Java's alias chain is a strict subset of Go's |
| `FC_ROUTER_CLIENT_ID` / `FC_ROUTER_CLIENT_SECRET` (same 2026-09-14 addition) | read | new row, matches |

Rows read/aliased by Java with **no IaC entry today** (not eligible for a
verdict, listed for completeness — these are the `(not in IaC)` set most
likely to matter if a future task definition starts setting them):
`FC_DB_POOL_SIZE*`, `FC_TLS_*`, `FC_HTTP3_*`, `FC_CORS_CACHE_TTL_MS`,
`FC_STREAM_*_BATCH_SIZE` per-projection overrides, `FC_MCP_PLATFORM_AUTH_TOKEN`,
`FC_STANDBY_LOCK_TTL_SECONDS`/`FC_STANDBY_HEARTBEAT_SECONDS`/`FC_INSTANCE_ID`,
all the rate-limit/login-backoff/governor knobs, `DB_SECRET_REFRESH_INTERVAL_MS`.

Rows Go reads that Java **ignores**, with no IaC entry today (so invisible to
a cutover unless someone starts setting them): `FC_PRINCIPAL_VERSION_CACHE_SIZE`,
`FC_PRINCIPAL_VERSION_CACHE_TTL_SECS`.

## 4. `fcdev` CLI diff (Go `cobra` vs Java `picocli`)

Every Go subcommand has a Java counterpart; flag surfaces match closely.
Full per-command flag/env tables:

### `fcdev` (root) / `fcdev start`

Identical flag set on both (Go `addStartFlags`, `start.go:52-67`; Java
`StartOptions.java:35-118`), mirrored onto the root command both sides:

| Flag | Env | Go default | Java default | Status |
|---|---|---|---|---|
| `--api-port` | `FC_API_PORT` | `8080` | `8080` | match |
| `--metrics-port` | `FC_METRICS_PORT` | `9090` | `9090` | match |
| `--embedded-db` | `FC_EMBEDDED_DB` | `true` | `true` | match |
| `--embedded-db-port` | `FC_EMBEDDED_DB_PORT` | `15432` | `15432` | match |
| `--embedded-db-path` | `FC_EMBEDDED_DB_PATH` | `<userDataDir>/flowcatalyst/embedded-pg` | same | match |
| `--embedded-db-reset` | — | `false` | `false` | match |
| `--embedded-db-binary` | `FC_EMBEDDED_DB_BINARY` | — | `""` | **Java-only** — no Go flag/env; Go always resolves its own bundled binary, Java can be pointed at a `.txz` explicitly |
| `--database-url` | `FC_DATABASE_URL` | `""` | `""` | match |
| `--scheduler` | `FC_SCHEDULER_ENABLED` | `true` | `true` | match |
| `--scheduled-job` | `FC_SCHEDULED_JOB_ENABLED` | `true` | `true` | match |
| `--stream` | `FC_STREAM_PROCESSOR_ENABLED` | `true` | `true` | match |
| `--outbox` | `FC_OUTBOX_ENABLED` | `false` | `false` | match |
| `--router` | `FC_ROUTER_ENABLED` | `true` | `true` | match |
| `--mcp` | `FC_MCP_ENABLED` | `false` | `false` | match |
| `--pid-file` | `FC_DEV_PID_FILE` | `<userDataDir>/flowcatalyst/fcdev.pid` | same | match |

Both roots also carry `-h/--help`; Go's root additionally exposes
`--version`/`-v` via cobra's built-in (`main.go:44-46`), Java's `FcDev.java:62`
declares the same `-v/--version` explicitly — match.

### `fcdev stop`

| Flag | Env | Go default | Java default | Status |
|---|---|---|---|---|
| `--pid-file` | `FC_DEV_PID_FILE` | same as start | same | match |
| `--timeout` | — | `20s` | `20s` (`Duration`) | match |

Escalation timing matches: 150ms poll, 5s post-SIGKILL wait
(`stop.go:29,89-98` vs `StopCommand.java:28-30,97-104`).

### `fcdev fresh`

| Flag | Env | Go default | Java default | Status |
|---|---|---|---|---|
| `--database-url` | `FC_DATABASE_URL` | `""` | `""` | match |
| `--embedded-db-port` | `FC_EMBEDDED_DB_PORT` | `15432` | `15432` | match |
| `--embedded-db-path` | `FC_EMBEDDED_DB_PATH` | default path | same | match |
| `--yes` | — | required | required | match |

Table list (`fresh.go:22-74` vs `FreshCommand.java:35-85`) is byte-for-byte
identical, same order, same duplicate entries.

### `fcdev db upgrade`

| Flag | Env | Go default | Java default | Status |
|---|---|---|---|---|
| `--embedded-db-port` | `FC_EMBEDDED_DB_PORT` | `15432` | `15432` | match |
| `--embedded-db-path` | `FC_EMBEDDED_DB_PATH` | default path | same | match |
| `--no-backup` | — | `false` | `false` | match |
| `--yes` | — | `false` | `false` | match |

Backup-path stamp format matches (`20060102-150405` Go vs
`yyyyMMdd-HHmmss` Java — same rendering).

### `fcdev init`

| Flag | Env | Go default | Java default | Status |
|---|---|---|---|---|
| `--database-url` | `FC_DATABASE_URL` | `""` → `postgresql://postgres:postgres@localhost:15432/flowcatalyst?sslmode=disable` | `""` → `postgresql://postgres:postgres@localhost:15432/flowcatalyst` | match (Java drops the redundant `?sslmode=disable`, harmless — JDBC translation doesn't need it, per `InitCommand.java:69-70`'s own comment) |
| `--yes` | — | `false` | `false` | match |
| `--root` | — | `.` | `.` | match |
| `--admin-email` | `FC_BOOTSTRAP_ADMIN_EMAIL` | `""` | `""` | match |
| `--admin-password` | `FC_BOOTSTRAP_ADMIN_PASSWORD` | `""` | `""` | match |
| `--code` | — | `""` | `""` | match |
| `--name` | — | `""` | `""` | match |
| `--app-type` | — | `APPLICATION` | `APPLICATION` | match |
| `--description` | — | `""` | `""` | match |
| `--default-base-url` | — | `""` | `""` | match |
| `--client-identifier` | — | `default` | `default` | match |
| `--client-name` | — | `Default Client` | `Default Client` | match |
| `--api-base-url` | — | `http://localhost:8080` | `http://localhost:8080` | match |

**Behavioral divergence, not a flag/env diff**: Go's `init` step 5 actually
mints a `client_credentials` OAuth client and writes
`FLOWCATALYST_CLIENT_ID`/`FLOWCATALYST_CLIENT_SECRET` into `.env`
(`init.go:241-302`); Java's step 5 prints a deferral notice and never writes
those two keys (`InitCommand.java:221,229-232`). This is the **known,
tracked** deferral (`docs/spec/fcdev-commands.md` §0, gated on the Phase 3
OAuth-client aggregate) — flagged here only because it means a Java-bootstrapped
`.env` has 3 keys where a Go-bootstrapped one has 5.

### `fcdev mcp`

| Flag | Go default | Java default | Status |
|---|---|---|---|
| `--http` | `""` (stdio) | `""` (stdio) | match |
| `--platform-url` | `""` | `""` | match |
| `--client-id` | `""` | `""` | match |
| `--client-secret` | `""` | `""` | match |

Env resolution matches (`FLOWCATALYST_URL`/`FC_MCP_PLATFORM_URL`,
`FLOWCATALYST_CLIENT_ID`, `FLOWCATALYST_CLIENT_SECRET`, then
`http://localhost:{FC_API_PORT}` fallback) — `McpCommand.java:112-118` vs
Go's `mcp.go:41` (`mcp.LoadConfig()`). One difference: Go's static-token
fallback in `RequireCredentials`/`newPlatformClient` treats a **lone**
`ClientSecret` as a static bearer (`config.go:134-135`); Java's static-token
path is a **separate, differently-named** variable
(`FC_MCP_PLATFORM_AUTH_TOKEN`/`FLOWCATALYST_AUTH_TOKEN`,
`McpCommand.java:101-105`) rather than an overload of `FLOWCATALYST_CLIENT_SECRET`
— an operator who set only `FLOWCATALYST_CLIENT_SECRET` (no id) for the Go
binary's static-bearer trick gets silent non-auth from the Java binary.

### `fcdev outbox` / `fcdev outbox create-table`

All flags and env names match 1:1 (`outbox.go:39-49` vs
`OutboxCommand.java:50-82`; `outbox_create_table.go:54-59` vs
`OutboxCommand.java:293-300`), including the `--env-file`/dotenv-first-load
precedence and the `mysql://` → JDBC/DSN URL conversion. Only intentional
difference: `mongodb` is a recognized `--db-type` on both, but Java's
`create-table` refuses it with exit code 2 and a message
(`OutboxCommand.java:333-339`) where Go actually implements it
(`outbox_create_table.go` imports `outboxmongo`) — **documented, tracked**
divergence (`docs/spec/fcdev-commands.md` §3.1), not a silent gap.

One log-line difference found, not previously documented: Go's `outbox`
started-log prints the **full, unmasked** source URL
(`outbox.go:124`: `"source", sourceURL`), while Java masks the password
(`OutboxCommand.java:149,233-238`, `maskCredentials`) — Java is *more*
careful here, contradicting the Go behavior it's supposedly mirroring; worth
a note since a log-diff parity check would flag this as a difference even
though Java is arguably correct.

### `fcdev upgrade`

| Flag | Go default | Java default | Status |
|---|---|---|---|
| `--check` | `false` | `false` | match |
| `--force` | `false` | `false` | match |

`FC_DEV_UPGRADE_REPO` matches (`flowcatalyst/flowcatalyst`,
`upgrade.go:64` vs `UpgradeCommand.java:108`). `FC_DEV_UPGRADE_API_BASE` is a
**Java-only test seam** (`UpgradeCommand.java:91,97-99`) with no operational
default change (defaults to the real GitHub API) — not a parity issue.
Asset-selection logic is a documented, intentional Java divergence (native
vs jar vs "other" launch-kind detection, `UpgradeCommand.java:44-53`) since
Go is always a native binary and Java ships two ways.

### `fcdev version`

Matches: same output line shape (`fcdev <version>[ (<rev>[-dirty])]`).

### Commands present in Go with no Java counterpart

None found — every Go `cmd/fcdev/*.go` subcommand (`start`, `stop`, `init`,
`fresh`, `mcp`, `outbox` (+ `create-table`), `db upgrade`, `upgrade`,
`version`) has a Java class implementing it.

### `cmd/decrypt-check` (Go-only diagnostic, not a subcommand of `fcdev`)

A throwaway `go run ./cmd/decrypt-check [-show] '<ciphertext>'` utility
(`decrypt-check/main.go`) that reads `FLOWCATALYST_APP_KEY` and decrypts one
ciphertext for manual debugging. No Java equivalent exists anywhere in
`fcdev/src/main/java` or `server/src/main/java`. Low-risk: it's a developer
diagnostic never referenced by any deployment, task definition, or `fcdev`
subcommand — noted for completeness, not recommended as a port target unless
an operator specifically asks for it.

## 5. `fc-server` flags / signals

Neither binary accepts CLI flags — both are configured entirely through
environment variables (`cmd/fc-server/main.go` has no `flag`/`cobra` import
at all; `server/src/main/java/io/flowcatalyst/server/Main.java` has no
picocli/`args` handling either — `main(String[] args)` ignores `args`).

Signal handling is equivalent, not identical in mechanism:

| | Go `cmd/fc-server/main.go` | Java `Main.java` |
|---|---|---|
| Registration | `signal.Notify(stop, os.Interrupt, syscall.SIGTERM)` (`main.go:128-129`) | `Runtime.getRuntime().addShutdownHook(...)` (`Main.java:145`) — the JVM invokes shutdown hooks on SIGINT/SIGTERM (and normal `System.exit`) |
| On signal | goroutine logs `"shutdown signal received"`, cancels `rootCtx`; `server.Run` drains on ctx cancellation (`main.go:130-134`) | hook logs `"shutdown signal received"`, calls `running.stop()` synchronously, then closes secret refreshers and pools (`Main.java:145-150`) |
| Shutdown order | context cancel → `server.Run` internal drain (pool close is a `defer p.Close()` at the very end of `main`, `main.go:107`) | `running.stop()` → `dbSecretRefreshersToClose.forEach(close)` → `poolsToClose.close()` (`Main.java:147-149`) — pools close **after** the server stops, same relative order as Go's `defer` (LIFO: server-level cleanup happens inside `Run`, pool closes last) |
| Panic/crash safety | `defer recover()` around all of `main`, logs one structured line + `os.Exit(2)` (`main.go:43-48`) | no equivalent top-level `catch (Throwable)` found in `Main.java` — an uncaught exception in `main` before `Server.start()` returns falls through to the JVM's default uncaught-exception handler (a bare stack trace on stderr, not routed through SLF4J/Logback the way Go's `recover` guarantees a structured line) |

The panic-safety row is the one asymmetry worth flagging: Go guarantees one
greppable structured log line survives any startup panic; Java's `Main.main`
has no top-level try/catch, so a startup failure before logging is
configured, or an exception Logback itself can't format, could print a raw
JVM stack trace that a JSON-only log pipeline drops. Not verified against a
running process in this static audit — flagged as a thing Phase 1's "start
the image and compare `/health`+exit codes" step should specifically probe
(deliberately induce a startup failure both sides and diff what lands in the
log pipeline).

`fc-server` accepts no `--version`/`--help` flag on either side (neither
`main.go` nor `Main.java` parses `args` at all) — an operator who runs
`fc-server --version` gets "unrecognized subsystem toggle, ignored" silence
on the Go side (env-only parsing never looks at argv) and the same silent
no-op on the Java side. Symmetric, not a gap.

## Method note

Enumerated via `grep -rn "os\.Getenv(\|os\.LookupEnv("` across
`cmd/`, `internal/`, `pkg/` (excluding `_test.go`), then read every call site
and its enclosing function to resolve helper-wrapped reads
(`internal/envutil`, per-package `envFirst`/`envBool`/`envOr` clones) back to
concrete variable names and defaults — 71 direct `os.Getenv` + 1
`os.LookupEnv` call sites, plus the `envutil.*` and package-local helper
call sites layered on top. Cross-checked against Java via
`grep -rl '"<VAR>"'` in `server/src/main/java` and `fcdev/src/main/java` per
variable name, then read the matching Java file to confirm the read is live
(not dead code) and to capture its default. No `go build`/`mvn` run.
