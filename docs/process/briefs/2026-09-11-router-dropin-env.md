# Brief — router drop-in: the Rust fc-router's environment, Teams alerts

Branch `router-dropin-env` in `/Users/andrewgraaff/Developer/flowcatalyst-javalin`
(checked out; **do not commit, do not switch branches**). The owner wants to
run the Java image in place of the Rust `fc-router` on ECS with the **same task
definition**. Reference, read-only: `/Users/andrewgraaff/Developer/flowcatalyst-rust`
— `bin/fc-router/src/main.rs` (env loading, lines ~300–600),
`bin/fc-router/.env.example`, `crates/fc-router/src/notification.rs` (the
Teams notifier). Read `CLAUDE.md` (testing policy + mutation check) first.

Build: `export JAVA_HOME=$(mise where java)`;
`mvn -q -B -pl server -am test -Dtest='<pattern>' -Dsurefire.failIfNoSpecifiedTests=false`,
one Maven run at a time, in the foreground. Never `mvn install`. If an error
doesn't yield to one clear fix, stop and report with your diagnosis.

The live task definition sets: `API_PORT=8080`, `PLATFORM_ENABLED=false`,
`MESSAGE_ROUTER_ENABLED=true`, `FLOWCATALYST_CONFIG_URL`,
`FLOWCATALYST_CONFIG_INTERVAL=300`, `FLOWCATALYST_STANDBY_ENABLED=false`,
`AUTH_MODE=NONE`, `NOTIFICATION_TEAMS_ENABLED=true`,
`NOTIFICATION_TEAMS_WEBHOOK_URL` (a Power Automate "post card" workflow),
`NOTIFICATION_BATCH_INTERVAL=300`, `NOTIFICATION_MIN_SEVERITY=WARNING`,
`RUST_LOG=info`, `AWS_REGION`. Every one must mean in Java what it means in Rust.

In every alias list below, the **first set** name wins (Java's `EnvReader`
`firstSet` / `*Alias` helpers); Java's canonical `FC_*` name stays first.

## 1. `API_PORT` (server/Env.java + Dockerfile)

- `apiPort` = `FC_API_PORT`, then `API_PORT`, then `PORT`, default 8080.
- `Dockerfile`: remove `FC_API_PORT=8080` from `ENV` — an image-level
  `FC_API_PORT` would silently beat a task's `API_PORT`. The code default is
  already 8080. Make the `HEALTHCHECK` probe the configured port:
  `CMD sh -c 'wget -q -O /dev/null "http://127.0.0.1:${FC_API_PORT:-${API_PORT:-${PORT:-8080}}}/health" || exit 1'`.
  Update the Dockerfile header comment (`Run:` line) to match.

## 2. Config poll interval

`RouterServer.CONFIG_POLL_INTERVAL` (5 min, a constant) becomes configurable:
`FC_ROUTER_CONFIG_INTERVAL_SECONDS`, alias `FLOWCATALYST_CONFIG_INTERVAL`
(seconds), default 300. A value that is set but not a positive integer: WARN
naming it, use 300 (Rust silently defaults; a typo deserves a line). Thread
it from `Env` into wherever `RouterServer` schedules the config poll. Test
that the poll actually runs at the configured interval (an injected/fixed
clock or a short interval and a counting config source — assert the count,
not that a field was set).

## 3. Standby (active/passive via Redis)

`Env` / the `LeaderElection.Config` built in `server/Router.java`:

| Setting | Names, in precedence order | Default |
|---|---|---|
| enabled | `FC_STANDBY_ENABLED`, `FLOWCATALYST_STANDBY_ENABLED`, `STANDBY_ENABLED` | false |
| Redis URL | `FC_STANDBY_REDIS_URL`, `FLOWCATALYST_STANDBY_REDIS_URL`, `FLOWCATALYST_REDIS_URL`, `REDIS_URL` | `redis://127.0.0.1:6379` |
| lock key | `FC_STANDBY_LOCK_KEY`, `FLOWCATALYST_STANDBY_LOCK_KEY` | `fc:server:leader` (unchanged — do **not** switch to Rust's `fc:router:leader`; the orchestrator raises that with the owner) |
| lock TTL (s) | `FC_STANDBY_LOCK_TTL_SECONDS`, `FLOWCATALYST_STANDBY_LOCK_TTL` | 30 (today's `LeaderElection.LOCK_TTL`) |
| heartbeat (s) | `FC_STANDBY_HEARTBEAT_SECONDS`, `FLOWCATALYST_STANDBY_HEARTBEAT_INTERVAL` | 10 (today's `HEARTBEAT`) |
| instance id | `FC_INSTANCE_ID`, `FLOWCATALYST_INSTANCE_ID`, `HOSTNAME` | whatever Java derives today when none is set |

`LeaderElection.Config` already refuses heartbeat ≥ TTL; that must surface as
a clear startup failure naming both env values, not a stack trace deep in
wiring. Tests: each alias reaches the built `Config`; precedence; TTL/heartbeat
defaults unchanged.

## 4. Teams alerts (`router/observability/WarningNotifier.java`)

Env: webhook `FC_NOTIFY_WEBHOOK_URL`, then `NOTIFICATION_TEAMS_WEBHOOK_URL`.
`NOTIFICATION_TEAMS_ENABLED`: absent ⇒ enabled iff a URL is set (Rust);
explicitly `false` ⇒ disabled even with a URL (**deliberate deviation**: Rust
ignores an explicit false; say so in a code comment). Min severity: as today
(`FC_NOTIFY_MIN_SEVERITY`, `NOTIFICATION_MIN_SEVERITY`), accepting `WARN` as
well as `WARNING` (Rust accepts both). Batch interval
`FC_NOTIFY_BATCH_INTERVAL_SECONDS`, alias `NOTIFICATION_BATCH_INTERVAL`,
default **300** (Rust's; today's Java default is 30); `0` ⇒ no batching.

Behaviour — match Rust's `BatchingNotificationService` + `TeamsWebhookNotificationService`:

- Below min severity: dropped.
- `CRITICAL`: sent **at once**, its own POST, Rust's
  `build_critical_error_card` layout (the Java `category` is the card's
  "Source:"), and not also counted in the batch.
- Everything else accumulates. Every interval, if anything accumulated, **one**
  POST: Rust's `build_warning_card` layout carrying Rust's `send_batch`
  summary text (header `FlowCatalyst Warning Summary (<start> to <end>)`,
  sections Critical/Error/Warn/Info with counts, per category either
  `  - CAT: message` or `  - CAT: N occurrences` + `    Example: …`, then
  `Total Warnings: N`), severity = the highest seen, category `Processing`,
  source `BatchingNotificationService`. Remove today's flush-at-20: a flood
  must still produce one card per interval (that is the point of batching).
  Keep memory bounded: aggregate per (severity, category) as a count plus the
  first message — never an unbounded list of notices.
- Interval `0`: each notice is sent immediately as its own warning card (no
  summary).
- JSON exactly Rust's: `{"attachments":[{"contentType":"application/vnd.microsoft.card.adaptive","content":{"type":"AdaptiveCard","version":"1.4","body":[…]}}]}`,
  same element types, text, colours (`Attention` for Critical/Error,
  `Warning` for Warn, `Accent` for Info), emoji, and `Time:` as
  `yyyy-MM-dd'T'HH:mm:ss` UTC. Severity names in the card as Rust prints them
  (`Critical`, `Error`, `Warn`, `Info`), whatever Java's enum names are.
- Unchanged: nothing here may fail or block routing; a failed POST logs and
  drops; `close()` flushes what's pending.

Tests (against a local HTTP server capturing POST bodies, parsed as JSON —
assert structure and text, not a string blob): 100 WARNINGs within one
interval ⇒ exactly **one** POST whose summary counts 100; a CRITICAL ⇒ an
immediate POST with the critical card and it is absent from the next summary;
INFO below a WARNING floor ⇒ nothing; interval 0 ⇒ one card per notice;
explicit `NOTIFICATION_TEAMS_ENABLED=false` with a URL ⇒ no notifier; the
card's `attachments[0].contentType` and `content.type`.

## 5. `RUST_LOG` (server/Logging.java)

It is the Rust router's `tracing` filter, e.g. `info` or
`info,fc_router=debug,tower_http=warn`. Level resolution: `FC_LOG_LEVEL` if
set, else `RUST_LOG`: its bare level token (a comma-separated entry with no
`=`) sets the root level; `fc_router=<level>` sets `io.flowcatalyst.router`;
any other `target=level` is ignored (log one INFO line listing what was
ignored). Unknown level words behave as today's unknown `FC_LOG_LEVEL`. Test
the parsing as a pure function.

## 6. Doc

`docs/spec/router-env.md`: one table — every variable the Rust fc-router
reads (from `main.rs` + `.env.example`), and for each what Java does (same
name / alias of `FC_…` / ignored-because-… / dev-only). Mark
`LOCALSTACK_*` and `FLOWCATALYST_DEV_MODE` as dev-only (say whether Java has
an equivalent). This is what the owner reads before editing a task
definition.

## Report

Files; the env table's summary; a **mutation table** (each mutant + the
assertion that killed it): alias order reversed for `API_PORT`; batch
flush-at-20 restored; CRITICAL batched instead of immediate; min-severity
filter dropped; config interval ignored (constant used); explicit
`NOTIFICATION_TEAMS_ENABLED=false` ignored; `RUST_LOG` `fc_router=` directive
ignored. Then the full server suite once (`mvn -q -B -pl server -am test`),
result verbatim.
