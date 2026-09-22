# Subscription test — a function that stores every event it is delivered

Proves the whole event path on a dev machine: a manifest subscription, wired at promote; the
router's signed delivery; this function; a database it reaches through a platform-held secret.
Every `platform:admin:eventtypes:synced` event (one fires on every `fcdev start`) becomes a row in
`received_events`. What it is, in one page: `docs/function-service-overview.md`.

## Run it

```sh
# 1. the platform + a function host, one process, embedded Postgres on 15432
java --enable-preview -jar fcdev/target/flowcatalyst-fcdev-0.0.1-SNAPSHOT.jar start

# 2. in another terminal — a database and a role of its own on that Postgres (re-runnable)
examples/function-subscription-test/scripts/setup-db.sh

# 3. the DSN goes into the platform as the function's secret; the function never holds it
printf '%s' 'postgres://subscriptiontest:subscriptiontest@127.0.0.1:15432/subscriptiontest' \
  | fcdev fn secret set platform.test.subscription-test EVENTS_DSN

# 4. build, upload, publish, wait for READY, promote
make examples
fcdev fn deploy examples/function-subscription-test/target/function-subscription-test-0.0.1-SNAPSHOT-shrunk.jar \
  platform.test.subscription-test --manifest examples/function-subscription-test/manifest.json

# 5. trigger a delivery: restart fcdev, or "sync platform" on the Event Types page — then look
psql -h 127.0.0.1 -p 15432 -U subscriptiontest subscriptiontest \
  -c 'select id, event_id, event_type, subject, received_at from received_events order by received_at desc'
```

`fcdev fn status platform.test.subscription-test` shows the host holding it `LOADED`; the delivery
is on the Dispatch Jobs page; the function's own log line (`received platform:admin:eventtypes:synced …
stored`) is in fcdev's output. `fn secret set` must come before `fn deploy`: promote refuses while a
declared secret has no value (`SETTINGS_MISSING`).

Step 3 is what the setup script prints at the end, so you can paste it.

**`fcdev start` must be running for every step after 1.** It writes the CLI's credentials to
`fn-cli.json` on start and removes the file when it stops, so `fn …` commands answer "no
FlowCatalyst credentials found" against a stopped platform — start it again in another terminal.
If a Go `fcdev` is on your PATH, use the `fcdev-java` wrapper from `docs/fcdev.md` for every
command here; the function service is Java-only.

## What to look at

- `manifest.json` — one `webhook` endpoint, one subscription pointing at it, one `db` entry whose
  DSN is the secret `EVENTS_DSN`. Nothing else; the platform creates the subscription at promote and
  removes it when the manifest no longer lists it.
- `SubscriptionTestFunction.java` — `init()` creates the table if it is missing; `handle()` inserts
  the envelope as `jsonb` under a TSID and acks. The insert is `ON CONFLICT (event_id) DO NOTHING`:
  the router redelivers on a retry, and a redelivery must be a no-op, not a second row.
- `scripts/setup-db.sh` — the one-time database and role; idempotent; `fcdev fresh` wipes it.
- `src/test/…/ShrunkJarTest.java` — loads the shrunk jar through the real host loader against a real
  Postgres: a delivery is a row, a redelivery is not a second one (that assertion fails when the
  `ON CONFLICT` is removed).

The table:

```sql
received_events (
  id           varchar(26) primary key,   -- TSID (Tsid.java, 30 lines — no library in a sample)
  event_id     text not null unique,      -- the envelope's id
  event_type   text not null,
  subject      text,
  received_at  timestamptz not null default now(),
  event_data   jsonb not null             -- the whole envelope as delivered
)
```
