# Observability + CLI parity audit — dynamic part of the drop-in audit

Date 2026-09-14. Java repo `flowcatalyst-javalin` on `main` @ `9cd963f`. Go
repo `flowcatalyst-go` (READ-ONLY) @ `f81fd5a` + uncommitted working-tree
changes. Both sides driven live through their own `fcdev` (`e2e/runner/side.ts`
`startSide`, reused via a throwaway driver script,
`e2e/scripts/audit-observability.ts`, deleted after this run) against a fresh
embedded Postgres each, exactly as the frontend e2e runner does. `docs/verification-plan.md`
Phase 2 is the intent; `docs/spec/monitoring-routes.md` specs the `/monitoring/*`
router-dashboard API (unrelated — it is not the Prometheus `/metrics` endpoint)
and `docs/spec/jfr-events.md` specs JVM Flight Recorder events (unrelated —
not stdout logs or `/metrics` either); neither rules anything found here.

## 1. Metrics — `GET /metrics` on each side's metrics port

Sequence driven before the scrape: `POST /auth/login` (anchor admin),
`GET /api/event-types`, `POST /api/principals/users` (valid body), a 404
(`GET /api/does-not-exist-route`), then a 403 (`POST /api/clients` +
`POST /api/principals/users` with `scope:"CLIENT"` to mint a client-scoped
user, log in as them, `GET /api/clients` → confirmed `403` on **both** sides).
A 2s pause before the scrape let the async mail-outbox sender drain (§2.1).

**Go's metrics-port `/metrics` is a placeholder — zero series, on both a
fresh instance and after the sequence:**

```
# fc-server metrics placeholder
```

(`internal/server/http.go:64-70`, literal string, `text/plain`). Go's *real*
Prometheus series (the router's) are mounted on the **API** port under a
router prefix (`internal/server/run.go:264`,
`sub.Mount("/metrics", routerapi.PrometheusHandler(state))` inside
`MountRouterHTTP`), not the metrics port the task scrapes — so they are out
of scope for this comparison by construction, not by an oversight here.

**Java's metrics-port `/metrics` serves the real, shared `PrometheusRegistry`**
(`io.prometheus.metrics`, not Micrometer) — 11 series observed in this run
(9,164 bytes; the router's own larger metric family, `RouterPrometheusCollector`,
only registers additional series once dispatch pools/queues exist, none did
here):

| Metric | Type | Labels |
|---|---|---|
| `fc_auth_backoff_store_errors_total` | counter | — |
| `fc_db_gate_held` | gauge | `lane`, `pool` |
| `fc_db_gate_waiting` | gauge | `lane`, `pool` |
| `fc_mail_failed_total` | counter | — |
| `fc_mail_outbox_pending` | gauge | — |
| `fc_mail_sent_total` | counter | — |
| `fc_request_queue_depth` | gauge | `group` |
| `fc_request_queue_wait_seconds` | histogram | `group` |
| `fc_request_rejected_total` | counter | `group` |
| `fc_request_workers_busy` | gauge | `group` |
| `fc_router_mediation_http_version_total` | counter | `version` |

**This is a documented, deliberate asymmetry, not a fresh finding.**
`Metrics.java`'s class doc states it outright: "Go served a placeholder
string at `/metrics` and kept the real series under `/router/metrics`; the
agreed tidy-up is to expose the real Prometheus registry here (the router
alias stays for existing scrapes)." So every one of the 11 names above is
Java-only *by design* — the ruling was to make the metrics port meaningful
on Java rather than mirror Go's placeholder. Cite this rather than filing it
as a new gap. `RouterPrometheusCollector.java`'s own class doc separately
flags an **unruled** TODO (spec `docs/spec/router.md` §13, Q41): both sides
deliberately still omit `fc_messages_submitted_total`,
`fc_messages_rejected_total{reason}`, `fc_consumer_polls_total`,
`fc_consumer_errors_total{type}`, a `result` label on
`fc_messages_processed_total`, and `flowcatalyst_broker_*` — a Go gap Java
reproduces on purpose, not a Java-only miss.

No general per-route HTTP request-count/duration metric exists on **either**
side (nothing named `http_requests_total` / `http_server_requests` /
similar) — the login/event-types/principals/404/403 sequence in this task
doesn't move any router metric either, since none of those routes are
router-mediated traffic.

**Verdict**: 11 metric names present on Java only, 0 on Go only, 0 shared —
this is the ruled Go-placeholder-vs-Java-real-registry split, cited above,
not a new defect.

## 2. Structured logs — field names per event kind

Both sides' captured stdout for the same driven sequence:
`e2e/test-results/go.log`, `e2e/test-results/java.log` (both JSON-lines).

### 2.1 Shape, at the top level

Go (`internal/logging`, `slog` JSON handler) — flat keys, message in `msg`,
extra fields flattened at top level, RFC 3339 timestamp:

```json
{"time":"2026-09-14T16:32:47.628347+01:00","level":"INFO","msg":"bootstrap admin created","email":"e2e-admin@example.com","role":"platform:super-admin","scope":"ANCHOR"}
```

Java (Logback, `LogstashEncoder`-shaped) — epoch-millis timestamp, message in
`formattedMessage`, extra fields **nested inside a `kvpList` array of
single-key objects** rather than flattened, plus `threadName`, `loggerName`,
an `mdc` object, and (on an error) a structured `throwable`:

```json
{"timestamp":1789399972184,"level":"INFO","threadName":"main","loggerName":"io.flowcatalyst.platform.seed.Seeder","mdc": {},"kvpList": [{"email":"e2e-admin@example.com"},{"role":"platform:super-admin"}],"formattedMessage":"bootstrap admin created scope=ANCHOR","throwable":null}
```

| Concept | Go field | Java field | On one side only |
|---|---|---|---|
| timestamp | `time` (RFC 3339 string) | `timestamp` (epoch ms number) | both — different name *and* different type |
| message | `msg` | `formattedMessage` | both |
| level | `level` | `level` | shared |
| logger/component | *(absent)* | `loggerName` | Java only |
| thread | *(absent)* | `threadName` | Java only |
| MDC/context container | *(absent — context fields flattened top-level when present)* | `mdc` (object) | Java only |
| extra structured fields | flat top-level keys | `kvpList` (array of `{key: value}` objects, not a flat object) | both — same data, incompatible shape for any log-shipper field extraction that isn't side-aware |
| exception detail | *(not observed this run — Go would flatten an `err` string key, per convention, but none fired)* | `throwable{className,message,stepArray[…]}` — full structured stack frames | Java only, this run |

None of this is called out as a ruling anywhere in `docs/spec/monitoring-routes.md`,
`docs/spec/jfr-events.md`, `docs/backlog.md` or `docs/STATUS.md` — it is a
genuine, previously-undocumented shape difference. It matters operationally:
a log pipeline built against one side's field names (`time`/`msg` flat vs
`timestamp`/`formattedMessage`+`kvpList`) will not parse the other's output
without side-specific rules, which is exactly the class of gap Phase 2 exists
to catch.

### 2.2 `correlation_id` / MDC context

Both sides' class docs (`Logging.java`, Go's `internal/logging` package doc)
document `correlation_id`, `causation_id`, `principal_id`, `execution_id` as
the shared MDC/context keys. This run's sequence only exercised Java's side
of that claim: one Java WARN line carries `"mdc": {"correlation_id":"3604b498-caab-4755-a5f3-b78175ff5924"}`
(§2.3). No Go log line in this run carried a comparable context field — Go
never emitted a WARN/ERROR with request context during the same sequence, so
this is an absence of *opportunity* to observe it on Go here, not a confirmed
absence of the feature; a request that hits an error path with a live
correlation id on Go would need a separate, targeted run to confirm the
field name matches. Flagging as **inconclusive**, not a finding.

### 2.3 An incidental defect found while capturing logs

The very first authenticated call after login in this sequence
(`GET /api/event-types`) produced a Java WARN with a full exception, not seen
on Go for the equivalent call:

```
"loggerName":"io.flowcatalyst.platform.auth.claims.DbClaimsResolver"
"formattedMessage":"session principal lookup failed"
"throwable":{"className":"java.lang.IllegalArgumentException",
  "message":"NO_DB routes never check out a connection; there is no pool for them", ...}
```

stack: `Pools.forGroup` (`Pools.java:105`) → `Pools$RoutedDataSource.resolve/getConnection`
→ jOOQ → `PrincipalRepository.findById` → `DbClaimsResolver.resolveSession`
→ `Authenticator.session/introspect/handle` → `Platform.lambda$authenticated$0`.
`Pools.java`'s own comment on `forGroup` says outright: *"`NO_DB` never
touches a pool — passing it is a bug"* — so by the code's own contract this
is a real defect: some route (or the session-authentication path itself) is
running under the `NO_DB` admission group while session-cookie
authentication unconditionally needs a DB read for the principal. The
request did not visibly fail end-to-end (the sequence completed and the
403 probe later in the same run worked normally), so this reads as a
**caught, logged, and likely retried/degraded** path rather than a hard
failure — but it is a genuine warning-level exception with a full stack
trace on a plain authenticated GET that Go's equivalent call never produces.
Not chased to a root cause here (out of this task's scope — it surfaced
incidentally while capturing log shape); worth a follow-up: which route(s)
resolve to `NO_DB` admission but still authenticate via `DbClaimsResolver`.

### 2.4 Mail notices

Both sides log the notification mail bodies they cannot send (no SMTP
configured), but under different loggers/messages:

- Go: `logging`'s ad hoc `msg:"[email] SMTP not configured — logging instead
  of sending"`, flat `to`/`subject`/`body` keys.
- Java: `loggerName:"io.flowcatalyst.platform.mail.MailService"`,
  `formattedMessage:"SMTP not configured; mail logged instead of sent"`,
  `kvpList` holding `to`/`subject`/`body`.

Same fields conceptually (`to`, `subject`, `body`), same wrapping-shape
difference as §2.1 (flat vs `kvpList`). Confirmed **not** a functional gap:
a follow-up direct test (create a user with `returnInviteLink:true`, confirm
the password-setup token, then poll the log) showed Java's
`Notifications.passwordChanged` notice *does* fire for every
`/auth/password-reset/confirm` — Go's `s.Notifier.PasswordChanged` call is
unconditional too — but Java's mail path goes through
`OutboxMailService` (`docs/spec/mail-outbox.md`: insert a `PENDING` row on
the caller's thread, a background `MailSender` delivers/logs it off the
request path), which took **~1 second** to appear in the log in a direct
timed test, vs apparently-synchronous logging on Go. This is why two of
Task 1's e2e mismatches (`invitations` §1 below) are runner races, not
product differences — noted here since it is the same mail-logging
mechanism this section is about.

## 3. `fcdev` CLI — `--help`, live

Both binaries built by the e2e runner (Go's scratch `fcdev-go-bin`, Java's
shaded jar), `--help` diffed for the root command and every subcommand both
sides list.

### 3.1 Top-level

- **Go has a `completion` subcommand** (bash/fish/powershell/zsh
  autocompletion-script generation) that **Java has no equivalent for at
  all** — not in the top-level command list, no flag. Not mentioned in
  `docs/spec/fcdev-commands.md`. A real, undocumented gap (low severity —
  dev-ergonomics only).
- **Java has a `--embedded-db-binary=<file>` flag** (env
  `FC_EMBEDDED_DB_BINARY`, "use this .txz instead of resolving one from the
  classpath or Maven Central") on the root command and on `start`, that
  **Go has no equivalent for** — Go's embedded-Postgres binary resolution
  has no override flag. Java-only, undocumented in the spec; plausibly
  necessary given Java resolves its embedded-pg binary from Maven Central
  rather than downloading it the way Go's embedded-postgres library does.
- **Version flag letter differs, and Java is internally inconsistent about
  it.** Go: `-v, --version` at the root, uniformly (subcommands carry no
  `--version` at all — only `-h/--help`). Java: the **root** command binds
  `-v, --version` (lowercase, custom text "print the version and exit"),
  but **every subcommand** (`start`, `init`, `fresh`, `mcp`, `outbox`, `db`,
  `db upgrade`, `outbox create-table`, `upgrade`, `version`, `stop`) instead
  binds `-V, --version` (uppercase, generic picocli text "Print version
  information and exit.") — confirmed live: `fcdev -v` prints the version,
  `fcdev -V` errors `Unknown option: '-V'`, while `fcdev start -V` would
  work and `fcdev start -v` would not (picocli's `mixinStandardHelpOptions`
  applied to subcommands, a separate/manual binding at the root). This is a
  genuine Java-only internal inconsistency (not present as a Go-vs-Java
  gap, since Go's subcommands have no version flag to compare against) —
  worth fixing for its own sake, independent of Go parity.
- Every other root flag/subcommand matches Go one-for-one, including
  defaults (`--outbox`/`--mcp` default false both sides,
  `--router`/`--scheduler`/`--scheduled-job`/`--stream`/`--embedded-db`
  default true both sides, `--api-port 8080`, `--metrics-port 9090`,
  `--embedded-db-port 15432`).

### 3.2 Subcommand-by-subcommand

`start`, `init`, `fresh`, `db`, `db upgrade`, `mcp`, `outbox`, `upgrade`,
`version`, `stop` — flags, descriptions, and defaults all match Go
one-for-one (Java's help additionally annotates each flag with its backing
env var, e.g. `--api-port=<port> API server port (FC_API_PORT; default:
8080)`, which Go's help text doesn't show even though Go flags are
presumably env-bindable too via viper elsewhere — a documentation
improvement on Java's side, not a functional gap).

One real functional difference, already ruled out of scope:
**`outbox create-table --db-type`** supports `postgres | mysql | mongodb`
on Go; Java's help says outright `postgres | mysql (mongodb is not yet
ported)`. This is `docs/backlog.md`'s already-recorded **"Outbox Mongo
backend... Owner ruling 2026-09-05: out of the port, Postgres [+ mysql]
[carries the SDK's on-prem story]"** — cite that, not a new finding.

## Summary

- **Metrics**: 11 names Java-only on the metrics port, 0 Go-only, 0 shared —
  fully explained by the documented, deliberate placeholder-vs-real-registry
  ruling in `Metrics.java`'s class doc (§1). The router's own larger metric
  family lives at a different path/port on Go by design and was out of this
  comparison's scope.
- **Logs**: field *names* diverge structurally for every shared concept
  (`time`/`msg` flat vs `timestamp`/`formattedMessage`+nested `kvpList`,
  plus Java-only `loggerName`/`threadName`/`mdc`/`throwable`) — previously
  undocumented, worth a ruling if any tooling parses both sides' logs with
  one rule set (§2.1). One incidental Java defect found: a `NO_DB` pool
  misrouting warning on the first authenticated GET after login (§2.3),
  not present on Go, not chased to root cause here. Mail-notice logging is
  functionally equivalent on both sides; the apparent 0/1-message gap in
  two Task 1 e2e flows is an artifact of Java's async outbox-mail delivery
  (~1s) racing the e2e test's synchronous, non-retrying log read (§2.4).
- **CLI**: Go has `completion` (Java doesn't); Java has
  `--embedded-db-binary` (Go doesn't); Java's version flag is `-v` at the
  root but `-V` on every subcommand, an internal Java inconsistency; the
  Mongo outbox gap is already ruled out of scope. Everything else matches
  flag-for-flag, default-for-default.
