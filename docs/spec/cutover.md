# Spec — cutover and rollback rehearsal

Phase 5's last item: the procedure that turns Go off, and the one that turns
it back on, practised on a copy of production until neither is interesting.
`dropin-verification.md` Phase C is the router's side of the same day; this
document is the platform's, and the two run together.

Status 2026-09-05: design (orchestrator). Nothing here runs until the gates
in §1 are green.

## 0. What makes this safe to attempt at all

- **One database, both servers, never at once.** Java adopts a Go database
  by baselining Flyway at V1 without running it, and every Java migration
  after V1 is additive and ignorable by Go (`Migrator` class doc; V2–V7 are
  Go's own 046–052, applied with `IF NOT EXISTS` guards). Go's
  `goose_db_version` is never touched. So the database is not "migrated to
  Java"; it is shared, and rollback is "start Go again".
- **One RSA key, one app key.** Session cookies, access and ID tokens, the
  2FA pending tokens and the trusted-device cookies are all derived from the
  same key material (auth-core §4); encrypted columns from the same
  `FLOWCATALYST_APP_KEY`. Given the same material, a token minted by one
  side validates on the other — the parity harness proves the claim sets
  match (rule 4 in `parity-harness.md` §5); §3 below proves acceptance.
- **State that lives in rows survives the switch**: refresh-token
  families, grants, rate-limit buckets (Postgres store), login attempts,
  MFA enrolments, passkeys, ceremonies in `oauth_oidc_payloads`. State that
  lives in memory does not, and on both sides that is only caches
  (OIDC client cache, CORS allowlist cache, the platform-config cache) that
  refill from rows.

## 1. Gates — every one green before a rehearsal is scheduled

| Gate | Evidence |
|---|---|
| Parity harness S0–S3 green, coverage 1.0, allow-list reviewed by the owner | `parity/target/parity-report/report.md` from a run on the release candidate |
| Frontend e2e green on Java, no `expectDiff` without a ruling | `pnpm e2e:both` table |
| Router shadow run (dropin Phase B) with an explained diff list | that plan's artefact |
| Env parity: every Go server variable Java reads, or is listed as deliberately dropped | §4 table, re-derived from Go's `docs/environment-variables.md` with the script in §4 |
| Additive-migration rule holds for every `V<n>` after V1 | reviewed by reading; a `MigrationsAreAdditiveTest` that rejects `DROP`, `RENAME`, `ALTER … TYPE` on a Go-read table is the cheap guard to add before the first rehearsal |
| Metric names on `/metrics` match Go's after the corpus ran (names + label keys; values differ) | parity harness S3 adds this as one step: scrape both, diff the name sets — dashboards and alerts are compiled against those names |
| Release artefact chosen | the jlink image (`Dockerfile`, 121 MB, HotSpot) for the platform tier; native only where the router tier wants the start time — never a first cutover on the native build |

## 2. The rehearsal environment

A staging stack that mirrors production's shape: Postgres, Redis if
production has `FC_STANDBY_ENABLED`, the load balancer in front, Go running
as it does today. The database is a **restore of a production
`pg_dump`** (scrubbed if policy needs it), not a seed — the adoption path
has to meet real rows: legacy passkey rows (skipped by design), partitions
that already exist, principals with every IdP type, an `oauth_oidc_payloads`
table with expired rows in it.

Both servers get the same env. Java's is Go's with the names in §4 checked.
The signing key is delivered the way production delivers it (`…_PATH` or
the inline PEM variables); the app key likewise.

## 3. The procedure, timed, three times

Each step has an owner, a check, and a rollback point. Record wall-clock
per step; the third run's numbers are the ones the production runbook
carries.

1. **Freeze.** Announce; stop deploys; note the Go image tag and the
   `goose_db_version` max (052 today).
2. **Snapshot.** `pg_dump` to the rollback bucket; verify it restores into a
   scratch database (a snapshot that has not been restored is a hope).
3. **Start Java beside Go, no traffic.** Same database, `FC_API_PORT`
   different. Watch its log for the three lines that matter: Flyway
   baseline at V1 then V2–V7 as *already applied or applied*, the seeder
   reporting nothing to do, `/health` 200 with every check up (the
   partition readiness check is the one most likely to say otherwise on a
   real database). Rollback point: stop Java; nothing has changed.
4. **Token continuity.** On Go, log in as a staging user (cookie), mint an
   access token through an OAuth client, and take a refresh token through
   the code flow. Present all three to Java: `/auth/me` with the cookie,
   `/api/me` with the access token, `/auth/refresh` with the refresh token.
   Then the reverse: tokens Java minted, presented to Go. A refresh that
   rotates on one side must be refused as reused on the other (family
   rows are shared; that is the C-Q rulings' reuse detection working across
   implementations). Rollback point: unchanged.
5. **Smoke on Java.** Run the parity harness's S0 scenario against the
   Java port alone (the runner has a single-side mode for this, or the e2e
   `auth` group with `E2E_SIDE=java`). Rollback point: unchanged.
6. **Switch.** Compose: point the service at the Java image and restart;
   ALB: register the Java target, wait for healthy, deregister Go
   (`FC_ALB_DEREGISTRATION_DELAY_SECONDS` respected). Go keeps running,
   taking nothing. **Time this step; it is the cutover.**
7. **Soak.** The checklist, on the clock: logins succeeding at the usual
   rate; the `fc_auth_backoff_store_errors_total` counter flat; the
   purger's ten sweeps logging on schedule; scheduled jobs firing; outbox
   and stream loops (if on this tier) draining; no 5xx above baseline;
   dashboards populated (metric names, §1). Thirty minutes in staging,
   a day in production.
8. **Stop Go.** Only after the soak. Note the time. Go's process is gone;
   its image stays pullable.
9. **Rollback drill — every rehearsal, not only when needed.** Start Go
   again on the same database: goose sees 052 and does nothing, the Flyway
   table is a stranger it ignores, the columns Java added carry defaults
   Go never reads. Switch traffic back (step 6 reversed), run the same
   token-continuity check the other way (Java-minted sessions on Go), soak
   ten minutes, then switch to Java again. **Time it.** The rollback
   number is the one the owner wants before saying yes to production.

What the rehearsal must *not* rely on: the snapshot from step 2. Rollback
is "start Go on the live database". The snapshot exists for the failure
this document does not foresee, and restoring it means losing writes since
step 2 — a decision, not a step.

## 4. Env parity, re-derived

Go documents 160 variables in `docs/environment-variables.md`. Checked
2026-09-05 by extracting every name and alias from that table and grepping
the Java `server` and `fcdev` sources:

- 152 are read by Java under the same name or alias.
- 7 belong to the Go SDK and its examples (`FC_BASE_URL`, `FC_TOKEN`,
  `FC_APP`, `FC_ISSUER`, `FC_CLIENT_ID`, `FC_CLIENT_SECRET`,
  `FLOWCATALYST_SIGNING_SECRET`) — not server knobs; the Java SDK has its
  own.
- 1 is a server knob Java does not read: `FC_JWT_ACCESS_TOKEN_TTL_SECS`
  (access-token lifetime, default 3600; sets `exp` and `expires_in`
  together). If a deployment sets it, Java must honour it — see
  `docs/backlog.md`.

Re-run before each rehearsal (Go moves):

```sh
cd ../flowcatalyst-go && python3 - <<'EOF'
import re, subprocess
doc = open('docs/environment-variables.md').read()
names = set()
for line in doc.splitlines():
    cells = [c.strip() for c in line.strip('|').split('|')]
    if line.startswith('|') and len(cells) >= 3:
        for c in (cells[0], cells[2]):
            names.update(re.findall(r'`([A-Z][A-Z0-9_]{3,})`', c))
java = set(subprocess.run(['grep', '-rhoE', '[A-Z][A-Z0-9_]{3,}',
    '../flowcatalyst-javalin/server/src/main/java', '../flowcatalyst-javalin/fcdev/src/main/java'],
    capture_output=True, text=True).stdout.split())
print(sorted(n for n in names if n not in java))
EOF
```

## 5. Production, after the third rehearsal

The same nine steps, the same checklist, the same timings expected. One
non-critical client first if the deployment is multi-tenant by instance;
otherwise the whole platform, in the window the rollback time allows. Go's
image stays deployable for one full release cycle after; the additive
migration rule stays in force for the same period, and only then is it
lifted (and `Migrator`'s class doc updated).
