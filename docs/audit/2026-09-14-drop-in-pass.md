# Drop-in pass, 2026-09-14 — Java vs Go `f81fd5a` + working tree

Owner's question: is the Java platform a drop-in replacement for Go —
identical behaviour unless agreed, same APIs, same environment, same UI?
This is the consolidated answer from five checks run the same day against
the Go working tree (`f81fd5a` plus the uncommitted application-managed
invitations change, which compiles). Detail per check:

| Check | Report | Result |
|---|---|---|
| Wire parity (every route, bodies byte-compared) | `parity/target/parity-report/` (run), corpus commit `9cd963f` | **1,324 steps, 0 DIFF, 0 ERROR, 0 stale**; lockfile 254/254, outside-lockfile 104/104; 13 new invitation steps |
| Route surface outside the lockfile, every listener | `2026-09-14-route-surface-parity.md` | Go 188 / Java 184 registrations; 0 method or listener mismatches; **2 Go-only routes** (fixed, `db5dd90`); 1 ruled auth-mode difference |
| Environment variables + CLI | `2026-09-14-env-and-cli-parity.md` | ~108 Go reads joined; 5 "configurable" Go knobs are hard-coded constants (see §2); 2 new findings; CLI flag-for-flag except `completion` and `-v`/`-V` |
| Schema | (no diff file: identical) | fresh Go-migrated PG 18 dump **byte-identical** to the fixture; fingerprint normaliser gap fixed (`db5dd90`) |
| Browser suite both sides + metrics + logs | `2026-09-14-observability-parity.md` | Java 53/53, Go 46/53 (7 mismatches, all classified, §3); SPA gate matched; log shapes differ (§2) |

## 1. Java defects found and fixed today

| Defect | Found by | Fix |
|---|---|---|
| Every pool showed 0 deliveries on the dashboard and in Prometheus while queue counters moved (staging report; the collector map was copied before any pool existed) | owner, staging | `061e91c` — live view; two tests, two mutants |
| `DELETE /warnings`, `DELETE /warnings/old` absent | route audit | `db5dd90` — Go's contract (unconditional / age-only, default 8 h), three tests |
| `SchemaFingerprintTest` brittle across Postgres point releases (INDEX deparse) | schema audit | `db5dd90` — normaliser applied to index definitions, fixture regenerated |
| Warning + stack trace per request when a signed-in browser hits an unknown `/api/*` path (SPA catch-all is `NO_DB`; the authenticator's session lookup hit the `NO_DB` pool guard) | observability audit | this commit — the authenticator skips `NO_DB` requests; `Exchange.group()`; test + mutant; `admission.md` §11.7 |
| The two invitation e2e flows asserted a total mail count that both sides legitimately exceed (the post-confirm "password changed" notice); passed on Java only because its outbox is asynchronous | e2e both sides | this commit — the negative assertions moved before the confirm and filter by subject |

## 2. Differences that need an owner ruling (Java is not "wrong", but cutover changes behaviour)

1. **Teams notification batching.** The router task sets
   `NOTIFICATION_BATCH_INTERVAL=300`. Go never reads it (batch 20 / 10 s
   hard-coded, `internal/router/server.go:203`); Java honours it. After
   cutover Teams cards arrive every 300 s instead of every 10 s. Options:
   set the IaC to 10 to preserve today's behaviour, or accept 300 as what
   the operator wrote. Same shape for `FLOWCATALYST_CONFIG_INTERVAL`
   (Go hard-codes 300 s; the IaC also says 300, so no change there).
2. **Structured log shape.** Go: flat slog JSON (`time`, `level`, `msg`,
   fields at top level). Java: logback JSON (`timestamp` epoch ms,
   `formattedMessage`, fields nested in `kvpList`, plus `loggerName`,
   `threadName`, `mdc`, `throwable`). Every CloudWatch Insights query and
   alert that parses fields must be rewritten, or Java adopts Go's flat
   shape (a logback layout, one unit). Recommend the latter before cutover;
   it is the only "same operational behaviour" item found today.
3. **Prometheus.** Go's metrics endpoint is a placeholder (0 series); Java
   serves a real registry. Already ruled (`Metrics.java`); dashboards gain,
   nothing loses.
4. **Standby knobs.** Java lets `FC_STANDBY_LOCK_TTL_SECONDS`,
   `FC_STANDBY_HEARTBEAT_SECONDS` and `FC_INSTANCE_ID` be set; Go
   hard-codes 30 s / 10 s / random UUID. Same defaults; no action unless
   someone sets them.
5. **`versioncache`.** Go caches principal versions (LRU + Redis) behind
   `FC_PRINCIPAL_VERSION_CACHE_*`; Java reads the database directly
   (`principal.md` §7, already recorded). Not set in the IaC. No action.
6. **Router-config unauthenticated answer.** Go 403 `UNAUTHENTICATED`, Java
   401 `UNAUTHORIZED` — ruled Java-first (`router-config-auth.md` §5).

## 3. Go defects handed off (`docs/go-mirror/2026-09-14-drop-in-pass.md`)

- `FindTrustedDevicesByPrincipal` returns a nil slice → `{"devices": null}`
  → the shared SPA's 2FA card throws on `devices.length` and disappears;
  three 2FA browser flows fail on Go, pass on Java.
- `clientScoped` dropped on event-type create (ruled 2026-09-06 #7, Java
  fixed, Go mirror still pending): one catalogue flow fails on Go.
- `NOTIFICATION_BATCH_INTERVAL` and `FLOWCATALYST_CONFIG_INTERVAL` are
  documented as configurable but never read.
- The invitation change is still uncommitted on `f81fd5a`; the parity
  corpus pins it against the working tree only.

## 4. Small Java follow-ups (backlog)

- `fcdev`: `completion` subcommand missing; version flag is `-v` at the root
  and `-V` on subcommands. Cosmetic, one Sonnet unit.
- One browser flow (`platform/documentation`) failed once and passed on
  retry; cause not found.
- The two-CPU p99 tail is admission queueing, not memory (round 16).
- The e2e runner reuses a cached jar without a staleness check.

## 5. Verdict

On the wire, the schema, the SPA and the route surface, Java is a drop-in
for Go at `f81fd5a` as of today, with the listed exceptions all either fixed
or ruled. The one operational gap that would surprise an operator on day
one is the log shape (§2.2); the one behavioural change the IaC would cause
is the Teams batch interval (§2.1). Both are decisions, not code, and both
fit in an afternoon once decided.
