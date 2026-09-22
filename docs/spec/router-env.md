# Spec — the Rust `fc-router`'s environment, read by the Java drop-in

Brief: `docs/process/briefs/2026-09-11-router-dropin-env.md`. This is what the
owner reads before editing an ECS task definition that currently runs the
Rust `fc-router` binary, to run the Java image against the **same task
definition** instead (`FC_PLATFORM_ENABLED=false`, `MESSAGE_ROUTER_ENABLED=true`).

Source of truth for the Rust side: `../flowcatalyst-rust/bin/fc-router/src/main.rs`
(env loading) and `bin/fc-router/.env.example`, both read-only, both still
moving — check `../flowcatalyst-rust`'s `git log` before trusting this table
against a newer Rust commit. Source of truth for the Java side: `Env.java`
(`server/Env.java`), `EnvReader.java`, `Logging.java`,
`router/manager/RouterServer.java`, `router/standby/LeaderElection.java`,
`router/observability/WarningNotifier.java`.

In every alias chain below, the **first name listed** wins when more than one
is set (`EnvReader#firstSet`/`*Alias` — first-set-wins, not left-to-right
merge). Java's canonical `FC_*` name is always listed first, even where Rust
itself does not have one.

## Standby lock key — a deliberate Go/Rust/Java difference

`FC_STANDBY_LOCK_KEY` defaults to **`fc:router:leader`** in Java (owner ruling
2026-09-11). The Go platform's unified `fc-server` still defaults its own
equivalent knob to `fc:server:leader`, and this Java binary carries that same
`fc:server:leader` default for **its own** non-router subsystems (the
scheduler's and stream-processor's leader gates, `Server.leaderGate`, which
suffix `Env.standbyLockKey()` with their own subsystem name). The router's
default changed specifically because this binary is meant to drop in for the
Rust `fc-router`, and Rust's own default (`load_standby_config` in `main.rs`)
is `fc:router:leader`.

**Consequence for a mixed fleet**: if a Go `fc-server` and a Java router (or a
Java `fc-server` with the router subsystem on) ever run standby against the
*same* Redis with the *same* lock key, one side's default must be set
explicitly — they no longer agree by default. Setting `FC_STANDBY_LOCK_KEY`
(or `STANDBY_ENABLED`'s Go-side equivalent) on whichever side needs to match
the other resolves it; leaving both on defaults now means a Go leader and a
Java router leader can each believe they hold a *different* key and run
active/active against the same queues.

## The table

| Variable(s), first-wins | Rust `fc-router` | Java | Default |
|---|---|---|---|
| `FC_API_PORT`, `API_PORT`, `PORT` | `API_PORT`, `PORT` (`env_first_parse`) | same two, `FC_API_PORT` canonical first (Java-only name; harmless extra alias for a Rust-sourced task definition) | `8080` |
| `FC_METRICS_PORT` | accept-and-log no-op — metrics always ride the API port's `/metrics` | **real second listener** (`Metrics.java`, `Env.metricsPort`) bound on this port, *in addition to* `/metrics` on the API-port-mounted router surface. A task definition that set this for the Rust binary as a no-op now gets a second live listener in Java — usually harmless (nothing else claims the port) but worth knowing before assuming parity | `9090` |
| `FC_ROUTER_HTTP_PREFIX` | optional path prefix nesting the whole route tree a second time | mounts the router API under this prefix (`Env.routerHttpPrefix`); same idea, default differs | Rust: unset (root only). Java: `/router` |
| `FC_DRAIN_TIMEOUT_SECONDS` | pool-drain budget on shutdown | same (`Env.routerDrainTimeoutSec`) | `60` |
| `FC_ROUTER_STRICT_ROUTING` | malformed-message ACK vs default-and-continue | same (`Env.routerStrictRouting`) | `false` |
| `FC_ROUTER_SYNTH_POOL_IDLE_SECS` | idle TTL for a synthesised `{client}-DEFAULT-POOL` | same (`Env.routerSynthPoolIdleSecs`; `0`/unset → implementation default, negative → Rust disables the sweep, Java's own synth-pool code treats it the same way) | `0` → 1h (Rust) / implementation default (Java) |
| `FC_ROUTER_DEFERRAL_MAX_DELAY_SECONDS` | not present — this Rust binary predates the head-of-line deferral ruling | the deferral admission schedule's reservation horizon (owner ruling 2026-09-22, `docs/spec/router-hol-deferral.md` §3, §7; `Env.routerDeferralMaxDelaySeconds`, `PoolAdmission#DEFAULT_HORIZON`). `0`/unset → implementation default (3600). Go carries the same knob (`ServerConfig.DeferralMaxDelay`); Rust does not | `0` → 3600s |
| `FC_ROUTER_DEFERRAL_BUDGET` | not present, same reason | how many deferred messages one queue's consumer may have outstanding before it stops polling into pools that are all full (§1, §7; `Env.routerDeferralBudget`, `RouterManager#DEFAULT_DEFERRAL_BUDGET`). `0`/unset → implementation default (5000). Go carries the same knob (`ServerConfig.DeferralBudget`); Rust does not | `0` → 5000 |
| `FLOWCATALYST_CONFIG_URL` | required unless `FLOWCATALYST_DEV_MODE=true`; comma-separated URLs supported | required to run the config-service mode; blank means no queues configured, full stop — `FC_DEFAULT_BROKER` plays no role here any more (`Router.configSource`). **Superseded (R4, `docs/go-mirror/2026-09-12-dispatch-rulings.md`, 2026-09-12):** this row used to describe a *deliberate* difference — blank + `FC_DEFAULT_BROKER=postgres` ran a synthesised single-queue built-in Postgres broker instead of refusing to start, where the Rust binary refuses with neither set. R4 removed that branch entirely ("not just for dev — one code path, as intended"): a bare Java run with `FC_DEFAULT_BROKER=postgres` and no config URL now also stops consuming anything, closing most of the gap with Rust's stricter behaviour (Rust still refuses to *start* outright; Java starts and simply runs with no queues). `fcdev` no longer relies on the removed branch either — it now points its own `FLOWCATALYST_CONFIG_URL` at its own platform's served router-config document (`docs/spec/deployed-dispatch.md` §3), whose document names Postgres-backed queues in dev and SQS-backed ones in prod: dev and prod share this one code path | none |
| `FC_ROUTER_CONFIG_INTERVAL_SECONDS`, `FLOWCATALYST_CONFIG_INTERVAL` | `FLOWCATALYST_CONFIG_INTERVAL` only, seconds (`load_config_sync_config`) | same value, `FC_ROUTER_CONFIG_INTERVAL_SECONDS` canonical first; a **set-but-not-a-positive-integer** value WARNs naming it and falls back to the default — Rust silently defaults (`RouterServer.parseConfigPollInterval`) | `300` |
| `FLOWCATALYST_DEV_MODE` *(dev-only)* | `true`/`1` → built-in LocalStack config + test endpoints | `Env.routerDevMode` selects the mediation client's HTTP version pin (`HttpMediator`, `docs/spec/router-h2.md`) and a couple of dev-only code paths; Java has **no** built-in LocalStack router config equivalent — `fcdev` is the Java dev-mode binary and configures itself independently, not through this flag | `false` |
| `LOCALSTACK_ENDPOINT` *(dev-only)* | SQS client endpoint override, only read in dev mode | not read by the router composition root; `fcdev`/tests reach LocalStack their own way | `http://localhost:4566` (Rust) |
| `LOCALSTACK_SQS_HOST` *(dev-only)* | dev-config queue URL host | not read; same as above | `http://sqs.eu-west-1.localhost.localstack.cloud:4566` (Rust) |
| `FC_STANDBY_ENABLED`, `FLOWCATALYST_STANDBY_ENABLED`, `STANDBY_ENABLED` | `FLOWCATALYST_STANDBY_ENABLED`, then legacy `STANDBY_ENABLED` (`env_first_bool`) | same three, `FC_STANDBY_ENABLED` canonical first (`Env.standbyEnabled`, `Router.electionConfig`) | `false` |
| `FC_STANDBY_REDIS_URL`, `FLOWCATALYST_STANDBY_REDIS_URL`, `FLOWCATALYST_REDIS_URL`, `REDIS_URL` | same four names in the same order (`load_standby_config`) | same four, same order (`Env.standbyRedisUrl`) | `redis://127.0.0.1:6379` |
| `FC_STANDBY_LOCK_KEY`, `FLOWCATALYST_STANDBY_LOCK_KEY` | `FLOWCATALYST_STANDBY_LOCK_KEY` only | same, `FC_STANDBY_LOCK_KEY` canonical first (`Env.standbyLockKey`) | **`fc:router:leader`** both sides — see the section above for the wrinkle with Go's `fc:server:leader` default |
| `FC_STANDBY_LOCK_TTL_SECONDS`, `FLOWCATALYST_STANDBY_LOCK_TTL` | `FLOWCATALYST_STANDBY_LOCK_TTL` only | same value, `FC_STANDBY_LOCK_TTL_SECONDS` canonical first (Java-only name) added (`Env.standbyLockTtlSeconds`) | `30` |
| `FC_STANDBY_HEARTBEAT_SECONDS`, `FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL` | `FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL` only | same value, `FC_STANDBY_HEARTBEAT_SECONDS` canonical first added (`Env.standbyHeartbeatSeconds`) | `10` |
| `FC_INSTANCE_ID`, `FLOWCATALYST_INSTANCE_ID`, `HOSTNAME` | `FLOWCATALYST_INSTANCE_ID`, then `HOSTNAME` | same three, `FC_INSTANCE_ID` canonical first added (`Env.standbyInstanceId`); blank on either side derives a random id | Rust: `""` (empty instance id if both unset). Java: a random UUID |
| heartbeat ≥ lock TTL | not validated at all — `StandbyRouterConfig` accepts it | refused at startup with a message naming both `FC_STANDBY_HEARTBEAT_SECONDS` and `FC_STANDBY_LOCK_TTL_SECONDS` and their values (`Router.electionConfig`, `LeaderElection.Config`'s own constructor check) | — |
| `FC_NOTIFY_WEBHOOK_URL`, `NOTIFICATION_TEAMS_WEBHOOK_URL` | `NOTIFICATION_TEAMS_WEBHOOK_URL` only | same, `FC_NOTIFY_WEBHOOK_URL` canonical first (`Env.routerNotifyWebhookUrl`) | none (no notifier) |
| `NOTIFICATION_TEAMS_ENABLED` | can only *widen* — a non-empty URL alone means notify; an explicit `false` is ignored once a URL is set | **deliberate deviation**: an explicit `false` always disables, even with a URL configured (`WarningNotifier.create`) | absent → enabled iff a URL is set, both sides |
| `FC_NOTIFY_MIN_SEVERITY`, `NOTIFICATION_MIN_SEVERITY` | `FC_NOTIFY_MIN_SEVERITY`, then legacy `NOTIFICATION_MIN_SEVERITY` (WARN-logged as deprecated) | same two names and order (`Env.routerNotifyMinSeverity`, `Warnings.parseMinSeverity`); accepts `WARN` as well as `WARNING`, matching Rust's own vocabulary | `WARNING`/`WARN` |
| `FC_NOTIFY_BATCH_INTERVAL_SECONDS`, `NOTIFICATION_BATCH_INTERVAL` | `NOTIFICATION_BATCH_INTERVAL` only | same value, `FC_NOTIFY_BATCH_INTERVAL_SECONDS` canonical first added (`Env.routerNotifyBatchIntervalSeconds`); `0` on both sides means no batching — every notice its own card | `300` |
| `RUST_LOG` | the `tracing_subscriber::EnvFilter` string, e.g. `info,fc_router=debug,tower_http=warn` | consulted **only when `FC_LOG_LEVEL` is unset** (`Logging.resolveLevels`); a bare comma-separated token with no `=` sets the root level, `fc_router=<level>` sets `io.flowcatalyst.router`'s level, any other `target=level` is collected and logged as ignored (one INFO line) rather than silently dropped | `info` |
| `FC_LOG_LEVEL` *(Java-only canonical)* | not read | wins outright over `RUST_LOG` when set at all — `RUST_LOG` is not consulted, not even its `fc_router=` directive | unset |
| `LOG_FORMAT` | `json` for structured logs, default text | `FC_LOG_FORMAT`/`LOG_FORMAT`, `json` → JSON, `text`/`plain`/`console`/`pretty` → text, unset → text on an interactive terminal else JSON (`Logging.formatOf`) — same vocabulary, Java adds the auto-detect | text (Rust) |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | read by the AWS SDK's own default credential/region chain, not by `main.rs` directly | same — the AWS SDK for Java reads the identical chain | SDK default chain, both sides |
| `AUTH_MODE`, `FC_ROUTER_AUTH_USER`/`AUTH_BASIC_USERNAME`, `FC_ROUTER_AUTH_PASS`/`AUTH_BASIC_PASSWORD` | resolved in `fc_router::api::AuthConfig::from_env` (not `main.rs`) — `AUTH_MODE=NONE` (or unset) disables BasicAuth on the router surface | same three names, same `NONE` behaviour, already implemented before this brief (`Env.routerAuthMode`/`routerAuthUser`/`routerAuthPass`) | `NONE` |
| `FC_PLATFORM_ENABLED`, `PLATFORM_ENABLED` *(Java-only)* | n/a — the Rust binary IS the router, it has no platform/API-tier toggle | selects whether the platform (identity/API) subsystem runs in this unified binary (`Env.platformEnabled`) | `true` |
| `FC_ROUTER_ENABLED`, `MESSAGE_ROUTER_ENABLED` *(Java-only)* | n/a — same reason | selects whether the router subsystem runs (`Env.routerEnabled`) — must be `true` for this drop-in | `false` |
| `FC_EXIT_AFTER_START` *(Java-only)* | n/a | build-time only: the image's AOT training run (`docs/spec/jvm-memory.md` §1a) — starts every enabled subsystem normally, logs `training run complete`, then stops and returns (`Env.exitAfterStart`, `Main#exitAfterStart`). Never set on a real deployment | `false` |

## Report cross-reference

The mutation table and test list pinning each behaviour above live in the
brief's execution report (§"Report" of
`docs/process/briefs/2026-09-11-router-dropin-env.md`), not duplicated here —
this file is the reference table an operator reads before touching a task
definition, not the test evidence that it is correct.
